// Version-pinned embedding of pi-sandbox's real bounded, process-backed worker manager.
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { reviewPublicEndpoint } from './network.mjs';

const spec = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
const require = createRequire(path.join(spec.packagePrefix, 'package.json'));
const { createJiti } = require('jiti');
const jiti = createJiti(import.meta.url);
const packageRoot = path.join(spec.packagePrefix, 'node_modules/@erichll/pi-sandbox/src');
const { ProcessBackedSubagentManager } = await jiti.import(path.join(packageRoot, 'subagent.ts'));
const { createDefaultPolicy } = await jiti.import(path.join(packageRoot, 'policy.ts'));
const { runSandboxedCommand } = await jiti.import(path.join(packageRoot, 'runner.ts'));
const save = (p, value) => {
  fs.writeFileSync(p + '.tmp', JSON.stringify(value, null, 2) + '\n');
  fs.renameSync(p + '.tmp', p);
};

function policyFor(job) {
  const policy = createDefaultPolicy(job.cwd, { additionalAllowRead: spec.readPaths,
    network: { strictAllowlist: true, allowedDomains: [], deniedDomains: [] } });
  // A read-only bind of the parent root would mask its writable descendants.
  policy.filesystem.allowRead.push(path.join(job.root, 'agent'));
  policy.filesystem.allowWrite.push(...['tmp', 'artifacts', 'sessions', 'web-cache', 'gortex', 'lens'].map(name => path.join(job.root, name)));
  policy.filesystem.denyWrite.push(path.join(job.root, 'agent'));
  // Only this experiment's inference endpoint bypasses public-web review.
  policy.network.strictAllowlist = !job.publicWeb;
  policy.network.allowedDomains = [];
  if (job.gortexSocket) { policy.network.allowUnixSockets.push(job.gortexSocket); }
  if (job.endpoint) {
    const endpoint = new URL(job.endpoint);
    if (endpoint.hostname !== '127.0.0.1') { throw new Error('Only host-local inference admitted'); }
    policy.network.allowedDomains.push('127.0.0.1:' + endpoint.port);
  }
  return policy;
}

if (spec.mode === 'sandbox-check') {
  const job = spec.jobs[0];
  const protectedFile = path.join(job.root, 'agent', 'sandbox-fixture.json');
  fs.writeFileSync(protectedFile, '{}\n');
  const code = `import os,pathlib
p = pathlib.Path(os.environ["GX10_AGENT_ROOT"])
(p/"checkout/allowed").write_text("ok")
s = pathlib.Path(os.environ["GX10_SENTINEL"])
try:
    s.read_text()
    raise SystemExit("sibling read escaped")
except (PermissionError, FileNotFoundError):
    pass
# A hidden home directory is a private tmpfs: a shadow write may succeed.
# The host-side preflight must verify the real sentinel remains unchanged.
try:
    s.write_text("sandbox-private shadow")
except (PermissionError, FileNotFoundError):
    pass
try:
    (p/"agent/sandbox-fixture.json").write_text("bad")
    raise SystemExit("agent configuration write escaped")
except (PermissionError, OSError):
    pass
print("sandbox read/write boundary passed")`;
  const result = await runSandboxedCommand({ command: 'sandbox admission fixture', cwd: job.cwd,
    env: job.env, policy: policyFor(job), timeout: 30,
    directInvocation: { command: '/usr/bin/python3', args: ['-c', code] },
    review: async () => 'deny', reviewDomain: async () => 'deny',
    onData: (data) => process.stdout.write(data) });
  if (result.exitCode !== 0) { throw new Error('Sandbox admission failed: ' + result.exitCode); }
  save(spec.result, { outcome: 'passed', sandbox: 'outer-process', exit: result.exitCode });
} else if (spec.mode === 'finite-check') {
  const job = spec.jobs[0];
  const policy = policyFor(job);
  if (spec.evaluator) { policy.filesystem.allowRead.push(spec.evaluator); }
  const fd = fs.openSync(spec.output, 'w');
  try {
    const result = await runSandboxedCommand({ command: 'finite independent verification',
      cwd: job.cwd, env: job.env, policy, timeout: 120,
      directInvocation: { command: spec.command[0], args: spec.command.slice(1) },
      review: async () => 'deny', reviewDomain: async () => 'deny',
      onData: (data) => fs.writeSync(fd, data) });
    save(spec.result, { exit: result.exitCode });
  } finally { fs.closeSync(fd); }
} else {
  if (![1, 2, 4].includes(spec.jobs.length)) { throw new Error('Only one, two or four workers admitted'); }
  const manager = new ProcessBackedSubagentManager({ maxConcurrency: spec.jobs.length, maxDepth: 1 });
  let cancelled = false;
  const stop = async () => { cancelled = true; await manager.shutdown(); };
  process.on('SIGTERM', stop);
  process.on('SIGINT', stop);
  const results = [];
  try {
    await Promise.all(spec.jobs.map(async (job) => {
      const started = Date.now();
      let session;
      try {
        if (!Array.isArray(job.tools) || job.tools.length === 0) {
          throw new Error('Missing effective worker tool policy');
        }
        session = await manager.start({ cwd: job.cwd, task: job.task, model: job.model,
          tools: job.tools, env: job.env, policy: policyFor(job),
          invocation: job.invocation, review: async () => 'deny',
          reviewDomain: async (endpoint) => {
            let decision = 'deny';
            if (job.publicWeb) { decision = await reviewPublicEndpoint(endpoint); }
            fs.appendFileSync(path.join(job.root, 'artifacts/network.jsonl'), JSON.stringify({ endpoint, decision, epoch: Date.now() }) + '\n');
            return decision;
          } });
        save(path.join(job.root, 'started.json'), { id: session.id, started: Date.now() / 1000 });
        const target = await session.prompt(job.task);
        const terminal = await session.waitForSettled(target);
        const row = { lane: job.lane, outcome: 'completed', duration_s: (Date.now() - started) / 1000,
          exit: terminal.exitCode, text: terminal.text };
        results.push(row);
        save(path.join(job.root, 'terminal.json'), row);
      } catch (error) {
        const row = { lane: job.lane, outcome: 'runtime_error', error: String(error) };
        results.push(row);
        save(path.join(job.root, 'terminal.json'), row);
        await stop();
        throw error;
      } finally {
        if (session && !cancelled) { await manager.remove(session.id); }
      }
    }));
    if (cancelled) { throw new Error('Batch interrupted'); }
    save(spec.result, { outcome: 'completed', results });
  } finally {
    await manager.shutdown();
  }
}
