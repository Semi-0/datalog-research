// A Pi control session and one plugin child. This file never prompts a parent model.
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { execution } from './execution.mjs';
import { createAgentSession, DefaultResourceLoader, SessionManager } from '/usr/local/lib/pilot/node_modules/@earendil-works/pi-coding-agent/dist/index.js';

const config = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
const root = '/logs/agent/parent';
const agentDir = path.join(root, 'agent');
const runtime = '/tmp/pilot-parent-runtime';
for (const directory of [root, agentDir, runtime, path.join(agentDir, 'extensions')]) {
  fs.mkdirSync(directory, { recursive: true });
}
Object.assign(process.env, { TMPDIR: runtime, HOME: runtime + '/home',
  PI_CODING_AGENT_DIR: agentDir, GORTEX_NATIVE_MCP: 'false',
  GORTEX_ENFORCE: 'true', GORTEX_HOOK_MODE: 'deny' });
fs.mkdirSync(process.env.HOME, { recursive: true });
const save = (file, value) => {
  const temporary = file + '.tmp';
  fs.writeFileSync(temporary, JSON.stringify(value, null, 2) + '\n');
  fs.renameSync(temporary, file);
};
const event = (kind, fields = {}) => fs.appendFileSync(root + '/timings.jsonl',
  JSON.stringify({ kind, monotonic_ns: process.hrtime.bigint().toString(), ...fields }) + '\n');
const modelFields = { name: 'Qwen3.6 NVFP4', reasoning: true, input: ['text'],
  cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 }, contextWindow: 131072, maxTokens: 16384 };
const workerModel = config.model ?? 'qwen36-35b-a3b-nvfp4';
const providers = {
  control: { baseUrl: config.proxy + '/parent/' + config.task + '/v1', api: 'openai-completions',
    apiKey: 'local-benchmark', models: [{ ...modelFields, id: 'forbidden' }] },
  pilot: { baseUrl: config.proxy + '/worker/' + config.task + '/v1', api: 'openai-completions',
    apiKey: 'local-benchmark', models: [{ ...modelFields, id: workerModel }] },
};
save(agentDir + '/models.json', { providers });
save(agentDir + '/settings.json', { retry: { enabled: false }, compaction: { enabled: true } });
let interrupted = false;
for (const signal of ['SIGTERM', 'SIGINT']) { process.on(signal, () => { interrupted = true; }); }
let session;
let runId;
let collection;
let cleanupVerified = false;
let serial = 0;

async function control(operation, fields = {}) {
  const id = 'control-' + String(++serial).padStart(6, '0');
  event('control_start', { id, operation });
  await session.prompt('/gx10-control ' + JSON.stringify({ id, operation, ...fields }));
  const receipt = JSON.parse(fs.readFileSync(root + '/control/' + id + '.json', 'utf8'));
  event('control_end', { id, operation, state: receipt.state });
  if (receipt.state !== 'completed') { throw new Error('Control failed: ' + JSON.stringify(receipt)); }
  return receipt.result;
}

function transcriptFiles(directory) {
  const files = [];
  for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
    const file = path.join(directory, entry.name);
    if (entry.isDirectory()) { files.push(...transcriptFiles(file)); }
    else if (entry.isFile() && file.endsWith('.jsonl')) { files.push(file); }
  }
  return files;
}
function messages(file) {
  const rows = [];
  for (const line of fs.readFileSync(file, 'utf8').split('\n')) {
    if (line.trim() === '') { continue; }
    try {
      const row = JSON.parse(line);
      if (row.type === 'message' && row.message) { rows.push(row.message); }
    } catch { /* A concurrently written final line is inspected after durable completion. */ }
  }
  return rows;
}
function workerTranscripts() {
  return [...transcriptFiles(root), ...transcriptFiles(runtime)]
    .filter((file) => messages(file).some((message) => message.role === 'assistant'));
}

try {
  event('parent_start');
  const statusText = fs.readFileSync('/proc/self/status', 'utf8');
  const apparmor = fs.readFileSync('/proc/self/attr/current', 'utf8').trim();
  save(root + '/runtime-security.json', { apparmor, status: statusText });
  const fields = Object.fromEntries(statusText.split('\n').filter((line) => line.includes(':'))
    .map((line) => [line.slice(0, line.indexOf(':')), line.slice(line.indexOf(':') + 1).trim()]));
  if (apparmor !== 'gx10-harbor-bwrap-v1 (enforce)' || fields.NoNewPrivs !== '1'
      || fields.CapEff !== '0000000000000000' || fields.CapBnd !== '0000000000000000'
      || fields.Uid.match(/[0-9]+/g).join(',') !== '1000,1000,1000,1000') {
    throw new Error('Agent UID, enforcing profile, capabilities or no-new-privileges mismatch');
  }
  const paths = config.extensions ?? ['/usr/local/lib/pilot/tool-environment.ts',
    '/usr/local/lib/pilot/cljform/clojure-forms.ts',
    '/usr/local/lib/pilot/agent-tools/node_modules/pi-lens',
    '/usr/local/lib/pilot/web/node_modules/pi-web-access', '/usr/local/lib/pilot/pi-gortex'];
  const tools = config.tools;
  const policy = { root, workspaces: ['/app'], workerModel: 'pilot/' + workerModel,
    tools, toolsAdmitted: config.toolsAdmitted === true };
  save(root + '/policy.json', policy);
  process.env.GX10_DELEGATION_POLICY = root + '/policy.json';
  const loader = new DefaultResourceLoader({ cwd: '/app', agentDir,
    additionalExtensionPaths: [...paths, '/usr/local/lib/pilot/companion.ts'],
    noExtensions: true, noSkills: true, noPromptTemplates: true, noThemes: true, noContextFiles: true });
  await loader.reload();
  if (loader.getExtensions().errors.length > 0) { throw new Error(JSON.stringify(loader.getExtensions().errors)); }
  ({ session } = await createAgentSession({ cwd: '/app', agentDir, resourceLoader: loader,
    model: { ...modelFields, id: 'forbidden', provider: 'control', api: 'openai-completions',
      baseUrl: providers.control.baseUrl }, thinkingLevel: 'high', sessionManager: SessionManager.inMemory() }));
  await session.bindExtensions({});
  const inventory = await control('inventory');
  const missing = tools.filter((name) => !inventory.tools.includes(name));
  if (missing.length > 0) { throw new Error('Required tools missing: ' + missing.join(',')); }
  const mapping = {};
  for (const tool of session.getAllTools()) {
    if (!tools.includes(tool.name) || ['read', 'bash', 'edit', 'write'].includes(tool.name)) { continue; }
    mapping[tool.name] = ['/usr/local/lib/pilot/tool-environment.ts', tool.sourceInfo.path];
  }
  save(agentDir + '/extensions/pi-better-subagents-config.json', { defaultModel: policy.workerModel,
    defaultTools: tools.join(','), maxConcurrent: 1, inheritExtensions: false,
    toolExtensions: mapping, providerExtensions: {} });
  await control('phase', { phase: 'workers' });
  event('parent_ready');
  const launch = await control('subagent_spawn_batch', { jobs: [{ worker: 0, prompt: config.instruction }] });
  runId = launch.structuredContent.launched[0].id;
  event('worker_launched', { run_id: runId });
  let lastProgress = performance.now();
  const evidence = new Set();
  let bestAssertions = 0;
  let bestFailures = Infinity;
  let stage = 0;
  while (true) {
    if (interrupted) { throw new Error('interrupted'); }
    collection = await control('subagent_result', { runId });
    const status = collection.details?.status;
    if (['completed', 'failed', 'stopped', 'aborted', 'error', 'cancelled'].includes(status)) { break; }
    for (const file of workerTranscripts()) {
      for (const message of messages(file)) {
        if (message.role !== 'toolResult' || message.isError) { continue; }
        const text = (message.content ?? []).filter((item) => item.type === 'text').map((item) => item.text).join('\n');
        const report = /containing\s+(\d+)\s+assertions[\s\S]*?(\d+)\s+failures?,\s*(\d+)\s+errors?/.exec(text);
        if (report) {
          const assertions = Number(report[1]);
          const failures = Number(report[2]) + Number(report[3]);
          if (assertions > 0 && (assertions > bestAssertions || failures < bestFailures)) {
            bestAssertions = Math.max(bestAssertions, assertions);
            bestFailures = Math.min(bestFailures, failures);
            lastProgress = performance.now(); stage = 0;
            event('assertion_progress', { assertions, failures });
          }
        } else if (['web_search', 'fetch_content', 'gortex_read', 'clj_forms', 'lsp_navigation'].includes(message.toolName)) {
          const hash = crypto.createHash('sha256').update(text).digest('hex');
          if (evidence.size < 12 && !evidence.has(hash)) {
            evidence.add(hash); lastProgress = performance.now(); stage = 0;
            event('reference_progress', { tool: message.toolName, sha256: hash });
          }
        }
      }
    }
    const idle = performance.now() - lastProgress;
    if (idle >= 1800000) { throw new Error('progress_budget_exhausted'); }
    if (idle >= 900000 && stage < 2) {
      stage = 2; event('progress_steering_threshold', { no_live_coaching: true });
    } else if (idle >= 600000 && stage < 1) {
      stage = 1; event('progress_warning');
    }
    await new Promise((resolve) => setTimeout(resolve, 1000));
  }
  event('worker_terminal', { run_id: runId, status: collection.details?.status });
  const transcripts = workerTranscripts();
  if (transcripts.length !== 1) { throw new Error('Expected one inspectable worker transcript: ' + JSON.stringify(transcripts)); }
  fs.copyFileSync(transcripts[0], root + '/worker-session.jsonl');
  const result = execution(messages(transcripts[0]), collection);
  save(root + '/execution.json', { ...result, transcript: 'worker-session.jsonl', parent_model_requests: 0 });
  if (result.outcome !== 'completed') {
    throw new Error('Worker execution did not complete normally: ' + result.outcome);
  }
} catch (error) {
  save(root + '/failure.json', { error: String(error), runId, collection });
  process.exitCode = 1;
} finally {
  try {
    const owned = JSON.parse(fs.readFileSync(root + '/controller.json', 'utf8')).runs;
    for (const id of owned) { await control('subagent_stop', { runId: id }); }
    cleanupVerified = JSON.parse(fs.readFileSync(root + '/controller.json', 'utf8')).runs.length === 0;
  } catch (error) {
    save(root + '/cleanup-error.json', { error: String(error) });
    process.exitCode = 1;
  }
  save(root + '/collection.json', { runId, cleanup_verified: cleanupVerified, collection });
  event('parent_collection_finished', { cleanup_verified: cleanupVerified });
  if (session) { session.dispose(); }
  process.exit(process.exitCode ?? 0);
}
