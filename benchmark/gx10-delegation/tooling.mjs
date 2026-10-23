import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { createRequire } from 'node:module';

const require = createRequire(path.join(process.env.HOME, 'experiments/gx10-agent-tools-1/package.json'));
const { Type } = require('typebox');

export default function tooling(pi) {
  const root = path.resolve(process.env.PI_CODING_AGENT_DIR, '..');
  const audit = (row) => fs.appendFileSync(path.join(root, 'artifacts/tooling.jsonl'), JSON.stringify(row) + '\n');
  const binary = path.join(process.env.HOME, '.local/opt/clj-kondo-2026.10.20/clj-kondo');
  const lint = (target, cwd) => {
    const result = spawnSync(binary, ['--lint', target, '--cache', 'false', '--config', '{:output {:format :json}}'],
      { cwd, env: process.env, encoding: 'utf8', timeout: 120000, maxBuffer: 2 * 1024 * 1024 });
    if (result.error || result.status === null) { throw new Error('clj-kondo failed: ' + String(result.error)); }
    const report = JSON.parse(result.stdout);
    audit({ kind: 'clj_kondo', target, exit: result.status, report });
    return report;
  };
  const policy = JSON.parse(fs.readFileSync(path.join(root, 'agent/benchmark-policy.json'), 'utf8'));
  const structuralTools = policy.structural_tools ?? [];
  pi.on('tool_result', event => {
    if (structuralTools.includes(event.toolName)) {
      audit({ kind: 'structural_tool', tool: event.toolName, failed: Boolean(event.isError),
        target: event.input?.path, epoch: Date.now() });
    }
  });
  const implementor = process.env.GX10_TOOL_ROLE === 'implementor';
  if (implementor) {
  pi.registerTool({ name: 'clj_kondo', label: 'clj-kondo', description: 'Lint a Clojure file or directory. Reports syntax errors and unresolved symbols.',
    parameters: Type.Object({ path: Type.String() }),
    async execute(_id, input, _signal, _update, ctx) {
      return { content: [{ type: 'text', text: JSON.stringify(lint(input.path, ctx.cwd)) }], details: {} };
    } });
  pi.on('tool_result', (event, ctx) => {
    if (!['write', 'edit', 'clj_edit'].includes(event.toolName) || event.isError) { return; }
    const target = event.input?.path;
    if (typeof target !== 'string' || !/\.(clj|cljs|cljc|edn)$/.test(target)) { return; }
    const report = lint(target, ctx.cwd);
    return { content: [...event.content, { type: 'text', text: 'clj-kondo diagnostics: ' + JSON.stringify(report) }] };
  });
  }
  pi.on('before_agent_start', () => {
    const names = pi.getAllTools().map(tool => tool.name);
    const wanted = ['web_search', 'fetch_content', 'get_search_content', 'clj_kondo',
      'lens_diagnostics', 'lsp_navigation', 'symbol_search', 'read_symbol', 'module_report',
      'effective_config', 'pi_lens_activate_tools'];
    wanted.push(...structuralTools);
    const required = ['web_search', 'fetch_content', ...structuralTools];
    if (implementor) { required.push('clj_kondo', 'lens_diagnostics', 'lsp_navigation'); }
    for (const name of required) {
      if (!names.includes(name)) { throw new Error('Required tool unavailable: ' + name); }
    }
    pi.setActiveTools([...new Set([...pi.getActiveTools(), ...wanted.filter(name => names.includes(name))])]);
    audit({ kind: 'ready', tools: pi.getActiveTools(), temperature: 1.0,
      pi_linter: 'RPC mode: prompt-editor linting inactive', epoch: Date.now() });
  });
  pi.on('tool_result', event => {
    if (event.toolName !== 'lens_diagnostics') { return; }
    const details = event.details ?? {};
    audit({ kind: 'lsp_probe', ready: details.source === 'lsp' && !details.unconfirmed &&
      !details.unavailable && !details.primaryUnavailable && !event.isError &&
      details.primaryServerId === 'clojure' && typeof details.primaryDiagnosticsCount === 'number', details });
  });
  pi.on('before_provider_request', event => {
    audit({ kind: 'sampling', temperature: event.payload.temperature });
    if (event.payload.temperature !== 1.0) { throw new Error('Effective model temperature must equal 1.0'); }
  });
}
