import { appendFileSync, readFileSync, existsSync, unlinkSync } from 'node:fs';
import { join } from 'node:path';

export function boundedTimeout(requested, maximum) {
    if (typeof requested !== 'number' || !Number.isFinite(requested) || requested <= 0) {
        return maximum;
    }
    return Math.min(requested, maximum);
}

export default function benchmarkRuntime(pi) {
    const directory = process.env.PI_CODING_AGENT_DIR;
    const policy = JSON.parse(readFileSync(join(directory, 'benchmark-policy.json'), 'utf8'));
    const auditPath = join(directory, '../artifacts/pi-runtime.jsonl');
    let continuations = 0;
    let lastAssistant;
    let pendingLengths = [];
    let request = 0;
    let supervisionTimer;
    const steerPath = join(directory, '../artifacts/supervision-steer-request.json');
    const audit = (kind, details = {}) => {
        appendFileSync(auditPath, JSON.stringify({ kind, epoch: Date.now(), request, ...details }) + '\n');
    };

    const applyToolPolicy = () => {
        if (policy.allowed_tools) {
            const tools = [...policy.allowed_tools];
            if (process.env.GX10_GORTEX_PROFILE === '1') {
                const graphNames = new Set(policy.gortex_tools ?? []);
                const registrations = readFileSync(join(directory, '../artifacts/gortex-registration.jsonl'), 'utf8')
                    .trim().split('\n').map(line => JSON.parse(line));
                const graph = registrations.filter(row => row.registered && graphNames.has(row.name)).map(row => row.name);
                tools.push(...new Set(graph));
                pi.setActiveTools(tools);
                const active = pi.getActiveTools();
                if (!(active.includes('workspace') || active.includes('workspace_info')) ||
                    !(active.includes('gortex_read') || active.includes('read_file'))) {
                    audit('gortex_unavailable', { tools: graph, active });
                    throw new Error('Pinned Gortex graph tools did not initialize');
                }
                audit('gortex_ready', { tools: graph });
            }
            pi.setActiveTools(tools);
            audit('allowed_tools', { tools });
        }
    };
    pi.on('session_start', () => {
        if (process.env.GX10_GORTEX_PROFILE !== '1') {
            applyToolPolicy();
        }
    });
    pi.on('before_agent_start', () => {
        if (process.env.GX10_GORTEX_PROFILE === '1') {
            applyToolPolicy();
        }
    });

    pi.on('session_start', () => {
        if (policy.supervision) {
            supervisionTimer = setInterval(() => {
                if (existsSync(steerPath)) {
                    const steering = JSON.parse(readFileSync(steerPath, 'utf8'));
                    pi.sendUserMessage(steering.message, { deliverAs: 'steer' });
                    unlinkSync(steerPath);
                    audit('supervision_steer_delivered');
                }
            }, 1000);
            supervisionTimer.unref();
        }
    });
    pi.on('session_shutdown', () => clearInterval(supervisionTimer));

    pi.on('before_provider_request', event => {
        request += 1;
        const payload = event.payload;
        audit('request', {
            model: payload.model,
            max_tokens: payload.max_tokens ?? payload.max_completion_tokens,
            messages: payload.messages?.length,
            message_chars: JSON.stringify(payload.messages ?? []).length,
            stream: payload.stream,
        });
    });
    pi.on('provider_stream_event', event => {
        const data = event.data;
        if (data?.error) {
            audit('provider_error', { error: data.error });
        }
        for (const choice of data?.choices ?? []) {
            if (choice.finish_reason) {
                audit('provider_finish', { reason: choice.finish_reason, usage: data.usage });
            }
        }
    });
    pi.on('tool_call', event => {
        if (event.toolName === 'bash') {
            const requested = event.input.timeout;
            event.input.timeout = boundedTimeout(requested, policy.tool_timeout_seconds);
            audit('tool_timeout', { toolCallId: event.toolCallId, requested, effective: event.input.timeout });
        }
    });
    pi.on('tool_result', event => {
        if (event.toolName === 'bash') {
            const text = event.content.filter(block => block.type === 'text').map(block => block.text).join('\n');
            if (/timed out|timeout|code 124|code 137/i.test(text)) {
                audit('tool_timeout_result', { toolCallId: event.toolCallId, isError: event.isError });
            }
        }
    });
    pi.on('message_end', event => {
        const message = event.message;
        if (message.role !== 'assistant') {
            return;
        }
        lastAssistant = message;
        audit('assistant_end', { timestamp: message.timestamp, stopReason: message.stopReason,
            error: message.errorMessage, usage: message.usage });
        if (message.stopReason === 'length') {
            pendingLengths.push(message.timestamp);
        } else if (message.stopReason === 'stop' && pendingLengths.length > 0) {
            audit('length_recovered', { timestamps: pendingLengths, completed: message.timestamp });
            pendingLengths = [];
        }
    });
    pi.on('agent_before_settle', event => {
        if (policy.continuation_owner === 'pi-vigilant') {
            return;
        }
        if (lastAssistant?.stopReason !== 'length' || event.outcome === 'aborted') {
            return;
        }
        if (continuations >= policy.length_continuations) {
            audit('length_recovery_exhausted', { continuations });
            return;
        }
        continuations += 1;
        audit('length_continuation', { continuations, timestamp: lastAssistant.timestamp });
        return {
            entries: [{ type: 'custom_message', customType: 'benchmark-response-recovery',
                content: 'Your last response reached its output-token limit. Continue this same task from the saved files. Keep reasoning concise, use tools to finish and verify the implementation, and give a final report. Do not repeat completed work. The 120-second command limits and meaningful-progress supervision still apply; there is no overall wall-clock task deadline.',
                display: false, details: { continuation: continuations } }],
            continue: true,
        };
    });
}
