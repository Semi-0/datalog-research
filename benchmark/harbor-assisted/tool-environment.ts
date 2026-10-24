import fs from 'node:fs';
import path from 'node:path';
import { createBashTool } from '/usr/local/lib/pilot/node_modules/@earendil-works/pi-coding-agent/dist/index.js';

export default function environment(pi) {
  const runtime = process.env.TMPDIR;
  if (!runtime || !runtime.startsWith('/tmp/')) { throw new Error('Private tool runtime is required'); }
  const home = path.join(runtime, 'tool-home');
  fs.mkdirSync(home, { recursive: true });
  process.env.HOME = home;
  process.env.PI_LENS_HOME = path.join(runtime, 'lens-global');
  process.env.PILENS_DATA_DIR = path.join(runtime, 'lens-data');
  process.env.PI_LENS_CONFIG_PATH = path.join(runtime, 'lens-config.json');
  process.env.GORTEX_NATIVE_MCP = 'false';
  process.env.GORTEX_ENFORCE = 'true';
  process.env.GORTEX_HOOK_MODE = 'deny';
  fs.writeFileSync(process.env.PI_LENS_CONFIG_PATH, JSON.stringify({ lsp: { servers: {
    clojure: { extensions: ['.clj', '.cljs', '.cljc', '.edn'], command: ['clojure-lsp'] }
  } } }));
  const bash = createBashTool('/app');
  pi.registerTool({ ...bash, label: 'bash', execute: async (id, args, signal, update) => {
    const timeout = args.timeout ?? 120;
    if (!Number.isFinite(timeout) || timeout <= 0 || timeout > 120) {
      throw new Error('Every shell command needs a positive timeout of at most 120 seconds');
    }
    return bash.execute(id, { ...args, timeout }, signal, update);
  } });
  let indexed = false;
  pi.on('before_agent_start', async (_event, ctx) => {
    if (!indexed) {
      const result = await pi.exec('gortex', ['track', ctx.cwd, '--wait', '--wait-timeout', '90s'], { timeout: 110000 });
      if (result.code !== 0) { throw new Error('Private Gortex indexing failed: ' + result.stderr); }
      indexed = true;
    }
  });
  pi.on('cache_warming_decision', async () => ({ action: 'stop' }));
}
