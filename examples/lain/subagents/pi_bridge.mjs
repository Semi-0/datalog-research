// A deterministic JSON-lines controller inside the existing admitted pilot image.
// Importing this file is inert. Its default export is the child admission extension.
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { pathToFileURL, fileURLToPath } from 'node:url';
const repositoryController = new URL('../../../benchmark/gx10-langgraph/control.mjs', import.meta.url);
let controllerLocation = repositoryController;
if (!fs.existsSync(repositoryController)) {
  controllerLocation = pathToFileURL('/usr/local/lib/pilot/control.mjs');
}
const { atomicJson } = await import(controllerLocation);

export const MAX_FRAME = 1024 * 1024;
const hash = (value) => crypto.createHash('sha256').update(JSON.stringify(value)).digest('hex');
const bounded = (value, maximum = MAX_FRAME) => {
  if (Buffer.byteLength(JSON.stringify(value)) > maximum) { throw new Error('Bounded bridge value exceeded limit'); }
  return value;
};

export default function childAdmission(pi) {
  const encoded = process.env.LAIN_PI_CHILD_POLICY;
  if (!encoded || Buffer.byteLength(encoded) > 8192) { throw new Error('Missing bounded child admission policy'); }
  const policy = JSON.parse(encoded);
  pi.on('before_agent_start', async (_event, ctx) => {
    const active = [...pi.getActiveTools()].sort();
    const expected = [...policy.tools].sort();
    const extraCallable = pi.getAllTools().filter((tool) =>
      ['codemode', 'deferred'].includes(tool.exposure) && !expected.includes(tool.name));
    const admitted = JSON.stringify(active) === JSON.stringify(expected)
      && extraCallable.length === 0 && ctx.cwd === policy.cwd
      && ctx.model?.provider === policy.provider && ctx.model?.id === policy.model;
    pi.appendEntry('lain-child-admission', {
      nonce: policy.nonce, admitted, tools: active, cwd: ctx.cwd,
      provider: ctx.model?.provider, model: ctx.model?.id,
    });
    if (!admitted) { throw new Error('Actual child tool/model/cwd admission mismatch'); }
  });
  pi.on('cache_warming_decision', async () => ({ action: 'stop' }));
}

export function validateConfig(config) {
  if (config.plugin !== 'pi-better-subagents' || config.version !== '0.19.1'
      || config.mode !== 'live' || config.toolsAdmitted !== true
      || config.cwd !== '/app' || !Array.isArray(config.tools) || config.tools.length < 1
      || config.tools.some((tool) => typeof tool !== 'string' || !tool || tool.startsWith('subagent_'))
      || new Set(config.tools).size !== config.tools.length
      || !['session', 'provider', 'model', 'modelBaseUrl', 'root'].every((key) => typeof config[key] === 'string' && config[key])
      || !config.root.startsWith('/logs/agent/') || !path.isAbsolute(config.root)
      || !Number.isInteger(config.contextWindow) || config.contextWindow < 1024 || config.contextWindow > 131072
      || !Number.isInteger(config.maxTokens) || config.maxTokens < 1 || config.maxTokens > 32768) {
    throw new Error('Explicit admitted pinned Pi bridge configuration required');
  }
  bounded(config, 16384);
  return config;
}

export function verifySecurity(status, apparmor) {
  const fields = Object.fromEntries(status.split('\n').filter((line) => line.includes(':'))
    .map((line) => [line.slice(0, line.indexOf(':')), line.slice(line.indexOf(':') + 1).trim()]));
  if (apparmor.trim() !== 'gx10-harbor-bwrap-v1 (enforce)' || fields.NoNewPrivs !== '1'
      || fields.CapEff !== '0000000000000000' || fields.CapBnd !== '0000000000000000'
      || fields.Uid?.match(/[0-9]+/g)?.join(',') !== '1000,1000,1000,1000') {
    throw new Error('Existing pilot confinement profile/UID/capability admission required');
  }
}

export function decodeResult(child, raw, transcript) {
  if (raw?.isError) { return { status: 'failed', child, failure: { message: 'Pi tool returned an error' } }; }
  const status = raw?.details?.status;
  if (['running', 'orphaned'].includes(status)) { return { status: 'running', child }; }
  if (status === 'completed') {
    if (!transcript?.admission?.admitted) { throw new Error('Completed child lacks actual inventory admission'); }
    return bounded({ status: 'completed', child, result: {
      text: transcript.text, transcript: transcript.artifact,
      tools: transcript.admission.tools, provider: transcript.admission.provider,
      model: transcript.admission.model,
    } });
  }
  if (['failed', 'killed', 'lost', 'stopped', 'aborted', 'error', 'cancelled'].includes(status)) {
    return { status: 'failed', child, failure: { message: `Pi execution terminal: ${status}` } };
  }
  throw new Error('Unsupported pinned Pi result status');
}

export function createBridge({ session, root, control, transcript, verifyStopped, disposeSession, discoverOwned = async () => [] }) {
  let closed = false;
  let launching = false;
  const owned = new Set();
  const ownedPath = path.join(root, 'bridge-owned.json');
  if (fs.existsSync(ownedPath)) {
    const previous = JSON.parse(fs.readFileSync(ownedPath, 'utf8'));
    if (previous.session !== session) { throw new Error('Foreign bridge ownership ledger'); }
    for (const child of previous.children) { owned.add(child); }
  }
  const persist = () => atomicJson(ownedPath, { session, children: [...owned] });
  function check(request) {
    bounded(request);
    if (closed || request.session !== session || !Array.isArray(request['effect-id'])) {
      throw new Error('Foreign/disposed/invalid bridge request');
    }
  }
  async function stop(child, id) {
    await control({ id, operation: 'subagent_stop', runId: child });
    if (!await verifyStopped(child)) { throw new Error('Owned child process-group cleanup remains unverified'); }
    owned.delete(child); persist();
    return { status: 'stopped', child, 'cleanup-verified': true };
  }
  return {
    async execute(request) {
      if (closed && request.session === session && request.operation === 'dispose') {
        return { 'cleanup-verified': true, 'already-closed': true };
      }
      check(request);
      const id = hash(request['effect-id']);
      if (request.operation === 'reconcile') {
        const launchId = hash(request['launch-effect-id']);
        for (const file of [path.join(root, 'control', `${launchId}.json`), path.join(root, 'control', `${launchId}-launch.json`)]) {
          if (!fs.existsSync(file)) { continue; }
          const receipt = JSON.parse(fs.readFileSync(file, 'utf8'));
          const launch = (receipt.result ?? receipt)?.structuredContent;
          const children = launch?.launched;
          if (Array.isArray(children) && children.length === 1 && typeof children[0].id === 'string') {
            const child = children[0].id; owned.add(child); persist();
            return { status: 'reconciled', child };
          }
        }
        return { status: 'uncertain', failure: { message: 'No unique durable launch receipt; never relaunch' } };
      }
      if (request.operation === 'dispose') {
        if (launching) { throw new Error('Launch in flight; verified container cleanup required'); }
        // A launch may have reached Pi before our acknowledgement/ledger write.
        // The existing controller persists partial launches before throwing.
        const controllerState = path.join(root, 'controller.json');
        if (fs.existsSync(controllerState)) {
          const runs = JSON.parse(fs.readFileSync(controllerState, 'utf8')).runs;
          if (!Array.isArray(runs) || runs.some((child) => typeof child !== 'string' || !child)) {
            throw new Error('Invalid durable controller ownership');
          }
          for (const child of runs) { owned.add(child); }
          persist();
        }
        for (const child of await discoverOwned()) {
          if (!owned.has(child)) {
            throw new Error('Unacknowledged live Pi child lacks controller ownership; verified container cleanup required');
          }
        }
        for (const child of [...owned]) { await stop(child, hash(['dispose', child])); }
        if (owned.size !== 0) { throw new Error('Owned children remain'); }
        await disposeSession(); closed = true;
        return { 'cleanup-verified': true };
      }
      if (request.tool === 'subagent_spawn_batch') {
        const task = request.arguments?.tasks;
        if (!Array.isArray(task) || task.length !== 1 || typeof task[0].task !== 'string'
            || !task[0].task || Buffer.byteLength(task[0].task) > 65536) {
          throw new Error('Exactly one bounded task is required');
        }
        const context = request['assignment-context'] ?? {};
        if (Object.keys(context).some((key) => !['instructions', 'trial-id'].includes(key))
            || (context.instructions !== undefined && typeof context.instructions !== 'string')) {
          throw new Error('Only bounded public execution instructions/trial identity may cross the child boundary');
        }
        let prompt = task[0].task;
        if (context.instructions) { prompt = context.instructions + '\n\n' + prompt; }
        if (Buffer.byteLength(prompt) > 65536) { throw new Error('Combined child prompt exceeds task limit'); }
        let receipt;
        launching = true;
        try { receipt = await control({ id, operation: 'subagent_spawn_batch', jobs: [{ worker: 0, prompt }] }); }
        finally { launching = false; }
        const launch = receipt?.structuredContent;
        if (!Array.isArray(launch?.launched) || launch.launched.length !== 1
            || launch.failed?.length || launch.skipped?.length) { throw new Error('Uncertain or partial launch; reconcile original receipt'); }
        const child = launch.launched[0].id;
        if (typeof child !== 'string' || !child) { throw new Error('Missing concrete child id'); }
        owned.add(child); persist();
        return { status: 'launched', child };
      }
      const child = request.arguments?.id;
      if (typeof child !== 'string' || !owned.has(child)) { throw new Error('Foreign child'); }
      if (request.tool === 'subagent_stop') { return stop(child, id); }
      if (request.tool === 'subagent_result') {
        const raw = await control({ id, operation: 'subagent_result', runId: child });
        let recorded = null;
        if (raw?.details?.status === 'completed') { recorded = await transcript(child); }
        return decodeResult(child, raw, recorded);
      }
      throw new Error('Unsupported bridge operation');
    },
  };
}

export function scanTranscripts(directories, expectedSession, nonce) {
  const visited = new Set(); let files = 0;
  const walk = (directory) => {
    if (!fs.existsSync(directory)) { return []; }
    const real = fs.realpathSync(directory);
    if (visited.has(real)) { return []; } visited.add(real);
    const found = [];
    for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
      const file = path.join(directory, entry.name);
      if (entry.isDirectory() && entry.name !== 'bridge-collected') { found.push(...walk(file)); }
      if (!entry.isFile() || !file.endsWith('.jsonl')) { continue; }
      files += 1; if (files > 256) { throw new Error('Transcript discovery limit exceeded'); }
      if (fs.statSync(file).size > 16 * 1024 * 1024) { throw new Error('Transcript size limit exceeded'); }
      const rows = fs.readFileSync(file, 'utf8').split('\n').filter(Boolean).map((line) => JSON.parse(line));
      const header = rows.find((row) => row.type === 'session');
      const admission = rows.find((row) => row.type === 'custom' && row.customType === 'lain-child-admission'
        && row.data?.nonce === nonce)?.data;
      if (header?.id === expectedSession && admission) { found.push({ file, rows, admission }); }
    }
    return found;
  };
  return directories.flatMap(walk);
}

export async function openConfined(config) {
  validateConfig(config);
  verifySecurity(fs.readFileSync('/proc/self/status', 'utf8'), fs.readFileSync('/proc/self/attr/current', 'utf8'));
  const packageRoot = '/usr/local/lib/pilot/node_modules';
  for (const [name, version] of [['pi-better-subagents', '0.19.1'], ['@earendil-works/pi-coding-agent', '1.3.0']]) {
    if (JSON.parse(fs.readFileSync(path.join(packageRoot, name, 'package.json'), 'utf8')).version !== version) {
      throw new Error('Installed pinned package version mismatch');
    }
  }
  fs.mkdirSync(config.root, { recursive: true, mode: 0o700 });
  const agentDir = path.join(config.root, 'agent');
  const runtime = path.join('/tmp', `lain-pi-${hash(config.session).slice(0, 20)}`);
  fs.mkdirSync(agentDir, { recursive: true, mode: 0o700 }); fs.mkdirSync(runtime, { recursive: true, mode: 0o700 });
  const nonce = crypto.randomUUID();
  process.env.LAIN_PI_CHILD_POLICY = JSON.stringify({ nonce, cwd: config.cwd, tools: config.tools,
    provider: config.provider, model: config.model });
  Object.assign(process.env, { PI_CODING_AGENT_DIR: agentDir, TMPDIR: runtime, HOME: path.join(runtime, 'home') });
  fs.mkdirSync(process.env.HOME, { recursive: true, mode: 0o700 });
  const model = { id: config.model, name: 'Explicit admitted local child', reasoning: false, input: ['text'],
    contextWindow: config.contextWindow, maxTokens: config.maxTokens,
    cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 } };
  atomicJson(path.join(agentDir, 'models.json'), { providers: { [config.provider]: {
    baseUrl: config.modelBaseUrl, api: 'openai-completions', apiKey: process.env.LAIN_PI_API_KEY ?? 'local-benchmark', models: [model],
  } } });
  atomicJson(path.join(agentDir, 'settings.json'), { retry: { enabled: false }, compaction: { enabled: true } });
  const policy = { root: config.root, workspaces: [config.cwd], workerModel: `${config.provider}/${config.model}`,
    tools: config.tools, toolsAdmitted: true };
  atomicJson(path.join(config.root, 'policy.json'), policy);
  process.env.GX10_DELEGATION_POLICY = path.join(config.root, 'policy.json');
  const sdk = await import(pathToFileURL(path.join(packageRoot, '@earendil-works/pi-coding-agent/dist/index.js')));
  const loader = new sdk.DefaultResourceLoader({ cwd: config.cwd, agentDir,
    additionalExtensionPaths: [...(config.extensions ?? []), '/usr/local/lib/pilot/companion.ts'],
    noExtensions: true, noSkills: true, noPromptTemplates: true, noThemes: true, noContextFiles: true });
  await loader.reload();
  if (loader.getExtensions().errors.length) { throw new Error('Admitted extensions did not load'); }
  const { session } = await sdk.createAgentSession({ cwd: config.cwd, agentDir, resourceLoader: loader,
    model: { ...model, id: 'forbidden', provider: 'control', api: 'openai-completions', baseUrl: 'http://127.0.0.1:1/v1' },
    sessionManager: sdk.SessionManager.inMemory() });
  await session.bindExtensions({});
  const control = async (request) => {
    await session.prompt('/gx10-control ' + JSON.stringify(request));
    const receipt = JSON.parse(fs.readFileSync(path.join(config.root, 'control', request.id + '.json'), 'utf8'));
    if (receipt.state !== 'completed') { throw new Error('Uncertain/failed durable controller operation'); }
    return receipt.result;
  };
  const inventory = await control({ id: 'bridge-inventory', operation: 'inventory' });
  if (config.tools.some((tool) => !inventory.tools.includes(tool))) { throw new Error('Parent required tool missing'); }
  const mapping = {};
  for (const tool of session.getAllTools()) {
    if (config.tools.includes(tool.name) && !['read', 'write', 'edit', 'bash'].includes(tool.name)) {
      mapping[tool.name] = ['/usr/local/lib/pilot/tool-environment.ts', tool.sourceInfo.path];
    }
  }
  atomicJson(path.join(agentDir, 'extensions', 'pi-better-subagents-config.json'), {
    defaultModel: policy.workerModel, defaultTools: config.tools.join(','), maxConcurrent: 1,
    inheritExtensions: false, toolExtensions: mapping,
    providerExtensions: { [config.provider]: [fileURLToPath(import.meta.url)] },
  });
  await control({ id: 'bridge-workers', operation: 'phase', phase: 'workers' });
  const { createJiti } = await import(pathToFileURL(path.join(packageRoot, 'jiti/lib/jiti.mjs')));
  const jiti = createJiti(import.meta.url);
  const registry = await jiti.import(path.join(packageRoot, 'pi-better-subagents/registry.ts'));
  const { realProcessProbe } = await jiti.import(path.join(packageRoot, 'pi-better-subagents/health.ts'));
  const verifyStopped = async (child) => {
    const meta = registry.readMeta(child);
    if (!meta || !Number.isInteger(meta.pid) || !Number.isInteger(meta.pgid)) { return false; }
    for (let attempt = 0; attempt < 30; attempt += 1) {
      const samePid = realProcessProbe.pidExists(meta.pid)
        && (!meta.pidStartTime || realProcessProbe.startToken(meta.pid) === meta.pidStartTime);
      if (!samePid && !realProcessProbe.groupAlive(meta.pgid)) { return true; }
      await new Promise((resolve) => setTimeout(resolve, 100));
    }
    return false;
  };
  return createBridge({ session: config.session, root: config.root, control, verifyStopped,
    discoverOwned: async () => {
      const records = registry.listRunRecords();
      if (records.indexError || records.unreadable.length) { throw new Error('Pi ownership registry is unreadable; container cleanup required'); }
      return records.metas.filter((meta) => meta.spawnPid === process.pid)
        .filter((meta) => realProcessProbe.pidExists(meta.pid) || realProcessProbe.groupAlive(meta.pgid ?? meta.pid))
        .map((meta) => meta.id);
    },
    disposeSession: async () => session.dispose(),
    transcript: async (child) => {
      const meta = registry.readMeta(child);
      if (!meta) { throw new Error('Missing child provenance'); }
      const found = scanTranscripts([config.root, runtime, registry.sessionsDir()], meta.sessionId, nonce);
      if (found.length !== 1 || !found[0].admission.admitted) { throw new Error('Unique admitted child transcript missing'); }
      const selected = found[0];
      const artifact = `bridge-collected/child-${hash(child).slice(0, 16)}.jsonl`;
      fs.mkdirSync(path.join(config.root, 'bridge-collected'), { recursive: true, mode: 0o700 });
      fs.copyFileSync(selected.file, path.join(config.root, artifact));
      const answers = selected.rows.filter((row) => row.type === 'message' && row.message?.role === 'assistant');
      const text = (answers.at(-1)?.message.content ?? []).filter((part) => part.type === 'text').map((part) => part.text).join('\n');
      return { text, artifact, admission: selected.admission };
    },
  });
}

export async function serve(input, output, bridge) {
  let pending = Buffer.alloc(0);
  for await (const chunk of input) {
    pending = Buffer.concat([pending, Buffer.from(chunk)]);
    if (pending.length > MAX_FRAME && !pending.includes(10)) { throw new Error('Bridge input frame exceeds limit'); }
    let newline;
    while ((newline = pending.indexOf(10)) !== -1) {
      const frame = pending.subarray(0, newline); pending = pending.subarray(newline + 1);
      if (!frame.length || frame.length > MAX_FRAME) { throw new Error('Invalid bridge input frame'); }
      const request = JSON.parse(frame.toString('utf8'));
      let result;
      try { result = await bridge.execute(request); }
      catch {
        let status = 'failed';
        if (request.tool === 'subagent_spawn_batch' || request.operation === 'reconcile') { status = 'uncertain'; }
        result = { status, failure: { message: 'Bridge operation failed; inspect private receipts and reconcile before retry' } };
      }
      output.write(JSON.stringify(bounded({ id: request.id, result })) + '\n');
    }
  }
  if (pending.length) { throw new Error('Truncated bridge input'); }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  // Reserve stdout for framed responses even if a loaded extension logs.
  const protocolWrite = process.stdout.write.bind(process.stdout);
  process.stdout.write = () => true;
  let bridge; let config;
  const dispose = async () => {
    if (bridge) { await bridge.execute({ session: config.session, 'effect-id': ['stdin-close'], operation: 'dispose' }); }
  };
  for (const signal of ['SIGTERM', 'SIGINT']) {
    process.once(signal, async () => {
      try { await dispose(); process.exit(0); }
      catch { process.stderr.write('Pi bridge cleanup unverified; remove and verify the owned container.\n'); process.exit(1); }
    });
  }
  try {
    if (process.argv[2] !== '--serve' || !process.argv[3]) { throw new Error('Usage: pi_bridge.mjs --serve private-config.json'); }
    config = JSON.parse(fs.readFileSync(process.argv[3], 'utf8'));
    bridge = await openConfined(config);
    await serve(process.stdin, { write: protocolWrite }, bridge);
    await dispose();
  } catch {
    process.stderr.write('Pi bridge stopped; inspect private admission/receipt artifacts.\n');
    try { await dispose(); } catch { process.stderr.write('Owned container cleanup remains required.\n'); }
    process.exitCode = 1;
  }
  process.exit(process.exitCode ?? 0);
}
