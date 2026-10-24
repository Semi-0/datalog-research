import fs from 'node:fs';
import { spawnSync } from 'node:child_process';
import { createAgentSession, DefaultResourceLoader, SessionManager } from '/usr/local/lib/pilot/node_modules/@earendil-works/pi-coding-agent/dist/index.js';

const checks = [];
function record(name, result) {
  checks.push({ name, ...result });
  console.log(JSON.stringify({ check: name, ...result }));
}
function command(name, argv) {
  const result = spawnSync(argv[0], argv.slice(1), { cwd: '/app', encoding: 'utf8', timeout: 110000 });
  record(name, { exit_code: result.status, stdout: result.stdout, stderr: result.stderr,
    error: String(result.error ?? '') });
  if (result.status !== 0) { throw new Error(name + ' failed'); }
}
let session;
try {
  command('clojure', ['clojure', '-M', '-e', '(println (+ 1 2))']);
  command('clj-kondo', ['clj-kondo', '--version']);
  command('cljform', ['cljform', '--version']);
  command('clojure-lsp', ['clojure-lsp', '--version']);
  command('gortex', ['gortex', 'version']);
  const agentDir = process.env.PI_CODING_AGENT_DIR;
  const globalLens = process.env.HOME + '/.pi-lens';
  fs.mkdirSync(globalLens, { recursive: true });
  fs.writeFileSync(globalLens + '/config.json', JSON.stringify({ lsp: { servers: {
    clojure: { extensions: ['.clj', '.cljs', '.cljc', '.edn'], command: ['clojure-lsp'] }
  } } }));
  const extensions = ['/usr/local/lib/pilot/tool-environment.ts', '/usr/local/lib/pilot/cljform/clojure-forms.ts',
    '/usr/local/lib/pilot/agent-tools/node_modules/pi-lens',
    '/usr/local/lib/pilot/web/node_modules/pi-web-access',
    '/usr/local/lib/pilot/pi-gortex'];
  const loader = new DefaultResourceLoader({ cwd: '/app', agentDir,
    additionalExtensionPaths: extensions, noExtensions: true,
    noSkills: true, noPromptTemplates: true, noThemes: true, noContextFiles: true });
  await loader.reload();
  const loaded = loader.getExtensions();
  record('extension_load', { errors: loaded.errors });
  if (loaded.errors.length > 0) { throw new Error('Required extension failed to load'); }
  const model = { id: 'no-inference', name: 'No inference admission', provider: 'fixture',
    api: 'openai-completions', baseUrl: 'http://127.0.0.1:1', reasoning: true,
    input: ['text'], contextWindow: 131072, maxTokens: 8192,
    cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 } };
  ({ session } = await createAgentSession({ cwd: '/app', agentDir, model,
    resourceLoader: loader, sessionManager: SessionManager.inMemory() }));
  await session.bindExtensions({});
  command('gortex_private_index', ['gortex', 'track', '/app', '--wait', '--wait-timeout', '90s']);
  session.setActiveToolsByName([...session.getAllTools().map((tool) => tool.name), 'lsp_navigation']);
  const tools = session.agent.state.tools;
  record('inventory', { tools: session.getAllTools().map((tool) => ({ name: tool.name, sourceInfo: tool.sourceInfo })),
    lsp_schema: session.getToolDefinition('lsp_navigation')?.parameters });
  async function invoke(name, args) {
    const tool = session.agent.state.tools.find((entry) => entry.name === name);
    if (!tool) { throw new Error('Required tool missing: ' + name); }
    const result = await tool.execute('admit-' + name, args, AbortSignal.timeout(110000), () => {});
    record(name, { arguments: args, result });
    if (result.isError) { throw new Error(name + ' returned an error'); }
    return result.content.filter((entry) => entry.type === 'text').map((entry) => entry.text).join('\n');
  }
  const fixture = '/app/pilot_tool_fixture.clj';
  fs.writeFileSync(fixture, '(ns pilot-tool-fixture)\n(defn original-name [x] (inc x))\n');
  const forms = await invoke('clj_forms', { path: fixture });
  if (!forms.includes('original-name')) { throw new Error('Structural form discovery failed'); }
  await invoke('clj_edit', { path: fixture, mode: 'append', content: '(defn added-name [] 7)' });
  if (!fs.readFileSync(fixture, 'utf8').includes('added-name')) { throw new Error('Structural edit did not persist'); }
  command('clj_kondo_lint', ['clj-kondo', '--lint', fixture]);
  const symbols = await invoke('lsp_navigation', { operation: 'documentSymbol', path: fixture });
  if (!symbols.includes('original-name')) { throw new Error('Lens did not return actual LSP symbols'); }
  await invoke('lens_diagnostics', { source: 'lsp', paths: [fixture], waitMs: 30000 });
  const search = await invoke('web_search', { query: 'site:clojure.org reference contains Clojure',
    provider: 'duckduckgo', numResults: 2, workflow: 'none' });
  if (!search.includes('clojure.org')) { throw new Error('Web search returned no official reference'); }
  const page = await invoke('fetch_content', { url: 'https://clojure.org/reference/other_functions', mode: 'readable' });
  if (!page.includes('clojure.core')) { throw new Error('Official reference fetch returned no reference content'); }
  const graph = await invoke('gortex_read', { operation: 'file', target: { file: '/app/benchmark/stable_distinct.clj' },
    options: { new_user_task: true } });
  if (!graph.includes('stable-distinct')) { throw new Error('Private Gortex returned no task source'); }
  let rejected = false;
  try {
    await invoke('clj_edit', { path: '/home/agent/pilot-outside.clj', mode: 'append', content: '(def escaped true)' });
  } catch { rejected = true; }
  if (!rejected) { throw new Error('Outside structural edit was accepted'); }
  fs.rmSync(fixture, { force: true });
  console.log(JSON.stringify({ outcome: 'tools_verified', model_requests: 0,
    required_tools_exercised: ['clj_forms', 'clj_edit', 'clj-kondo', 'lsp_navigation', 'lens_diagnostics',
      'web_search', 'fetch_content', 'gortex_read'], outside_structural_edit_rejected: true }));
  session.dispose();
  process.exit(0);
} catch (error) {
  console.log(JSON.stringify({ outcome: 'setup_declined', error: String(error), checks,
    model_requests: 0 }));
  if (session) { session.dispose(); }
  process.exit(1);
}
