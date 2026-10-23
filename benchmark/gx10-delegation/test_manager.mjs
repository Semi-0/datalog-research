// Deterministic tests of the installed package's admission logic; no model or OS sandbox claimed.
import assert from 'node:assert/strict';
import path from 'node:path';
import { createRequire } from 'node:module';
const prefix = process.argv[2];
const require = createRequire(path.join(prefix, 'package.json'));
const { createJiti } = require('jiti');
const jiti = createJiti(import.meta.url);
const { ProcessBackedSubagentManager, ProcessBackedSubagentSession } = await jiti.import(
  path.join(prefix, 'node_modules/@erichll/pi-sandbox/src/subagent.ts'));
const originalStart = ProcessBackedSubagentSession.prototype.start;
const originalStop = ProcessBackedSubagentSession.prototype.stop;
ProcessBackedSubagentSession.prototype.start = async function () {};
ProcessBackedSubagentSession.prototype.stop = async function () {};
try {
  for (const cap of [2, 4]) {
    const manager = new ProcessBackedSubagentManager({ maxConcurrency: cap, maxDepth: 1 });
    try {
      const jobs = await Promise.all(Array.from({ length: cap }, (_, i) => manager.start({ task: 'fixture-' + i, cwd: '/tmp' })));
      assert.equal(manager.list().length, cap);
      await assert.rejects(manager.start({ task: 'overflow', cwd: '/tmp' }), /concurrency limit/);
      await manager.remove(jobs[0].id);
      await assert.rejects(manager.start({ task: 'nested', cwd: '/tmp', depth: 2 }), /nesting depth/);
      assert.equal(manager.list().length, cap - 1);
    } finally { await manager.shutdown(); }
    assert.equal(manager.list().length, 0);
  }
  console.log('PASS: actual pinned manager rejects a fifth worker, caps 2/4, rejects nesting, releases slots');
} finally {
  ProcessBackedSubagentSession.prototype.start = originalStart;
  ProcessBackedSubagentSession.prototype.stop = originalStop;
}
