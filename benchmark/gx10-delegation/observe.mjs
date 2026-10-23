// Loaded explicitly in every isolated Pi child. Never writes private thinking text.
import fs from 'node:fs';
import path from 'node:path';

export default function observe(pi) {
  const root = process.env.GX10_AGENT_ROOT;
  if (!root) { throw new Error('GX10_AGENT_ROOT required'); }
  const log = path.join(root, 'artifacts', 'activity.jsonl');
  const append = (row) => fs.appendFileSync(log, JSON.stringify({ epoch: Date.now() / 1000, ...row }) + '\n');
  pi.on('session_start', () => append({ type: 'runtime_ready', vigilant_expected: true }));
  pi.on('before_provider_request', (event) => {
    append({ type: 'provider_request' });
  });
  pi.on('tool_call', (event) => {
    append({ type: 'tool_start', tool: event.toolName });
  });
  pi.on('tool_result', (event) => {
    const text = (event.content ?? []).filter(c => c.type === 'text').map(c => c.text).join('\n');
    append({ type: 'tool_end', tool: event.toolName, failed: Boolean(event.isError), text });
  });
  pi.on('message_end', (event) => {
    const message = event.message;
    if (message?.role !== 'assistant') { return; }
    append({ type: 'assistant_end', stopReason: message.stopReason, usage: message.usage,
      text: (message.content ?? []).filter(c => c.type === 'text').map(c => c.text).join('\n') });
  });
  pi.on('agent_end', () => append({ type: 'agent_end' }));
  pi.on('auto_compaction_start', () => append({ type: 'compaction_start' }));
  pi.on('auto_compaction_end', (event) => append({ type: 'compaction_end', error: event.errorMessage ?? null }));
  pi.on('session_shutdown', () => append({ type: 'shutdown' }));
}
