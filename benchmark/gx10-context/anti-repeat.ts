import { appendFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import antiRepeat from './pi-anti-repeat/src/index.ts';
import vigilant from './pi-vigilant/index.ts';

// Keep the pinned packages unchanged; coordinate their independent continuation policies here.
export default function guardedContinuations(pi) {
    const artifacts = join(process.env.PI_CODING_AGENT_DIR, '../artifacts');
    let paused = false;
    let stopped = false;
    const audit = (event) => {
        const row = { kind: 'anti_repeat', ...event, observed_epoch: Date.now() };
        appendFileSync(join(artifacts, 'anti-repeat.jsonl'), JSON.stringify(row) + '\n');
        appendFileSync(join(artifacts, 'pi-runtime.jsonl'), JSON.stringify(row) + '\n');
    };
    pi.events.on('anti-repeat', (event) => {
        audit(event);
        if (event.type === 'detected') {
            paused = true;
        } else if (event.type === 'stopped') {
            paused = true;
            stopped = true;
        } else if (event.type === 'reset' || event.type === 'enabled') {
            paused = false;
            stopped = false;
        }
    });
    pi.on('agent_start', () => {
        if (!stopped) {
            paused = false;
        }
    });
    antiRepeat(pi);
    const coordinated = {
        ...pi,
        on(type, handler) {
            return pi.on(type, (event, ctx) => {
                if (paused && type !== 'session_shutdown') {
                    audit({ type: 'vigilant_hook_suppressed', hook: type });
                    return;
                }
                return handler(event, ctx);
            });
        },
        sendMessage(...args) {
            if (!paused) {
                return pi.sendMessage(...args);
            }
            audit({ type: 'vigilant_continuation_suppressed' });
        },
        sendUserMessage(...args) {
            if (!paused) {
                return pi.sendUserMessage(...args);
            }
            audit({ type: 'vigilant_steering_suppressed' });
        },
    };
    vigilant(coordinated);
    pi.on('session_start', () => {
        writeFileSync(join(artifacts, 'anti-repeat-ready.json'), JSON.stringify({
            version: '0.2.1', watching: true, policy: 'correct-once-then-stop',
            vigilant_coordination: true, epoch: Date.now(),
        }) + '\n');
    });
}
