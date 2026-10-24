// Registration smoke only: never prompts a model or launches a worker.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const root = path.dirname(fileURLToPath(import.meta.url));
const variants = {
  'pi-subagents': { version: '0.81.0', tools: ['subagents_enable', 'subagent'] },
  'pi-better-subagents': { version: '0.19.1', tools: ['subagent_spawn_batch', 'subagent_result', 'subagent_stop'] },
};

if (process.argv[2] === '--probe') {
  const name = process.argv[3];
  assert.ok(Object.hasOwn(variants, name), 'Known isolated variant');
  process.env.PI_OFFLINE = '1';
  process.env.PI_TELEMETRY = 'false';
  const temporary = fs.mkdtempSync(path.join(os.tmpdir(), 'pi-subagent-smoke-'));
  const agentDir = path.join(temporary, 'agent');
  fs.mkdirSync(agentDir);
  process.env.PI_CODING_AGENT_DIR = agentDir;
  let session;
  try {
    const manifest = JSON.parse(fs.readFileSync(path.join(root, 'node_modules', name, 'package.json')));
    assert.equal(manifest.version, variants[name].version);
    const { createAgentSession, DefaultResourceLoader, SessionManager } = await import('@earendil-works/pi-coding-agent');
    const loader = new DefaultResourceLoader({ cwd: temporary, agentDir,
      additionalExtensionPaths: manifest.pi.extensions.map((entry) => path.resolve(root, 'node_modules', name, entry)),
      noExtensions: true, noSkills: true, noPromptTemplates: true, noThemes: true, noContextFiles: true });
    await loader.reload();
    assert.deepEqual(loader.getExtensions().errors, []);
    const model = { id: 'never-requested', name: 'Offline registration test', provider: 'offline',
      api: 'openai-completions', baseUrl: 'http://127.0.0.1:1/v1', reasoning: false,
      input: ['text'], contextWindow: 4096, maxTokens: 128,
      cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 } };
    ({ session } = await createAgentSession({ cwd: temporary, agentDir, resourceLoader: loader,
      model, sessionManager: SessionManager.inMemory() }));
    await session.bindExtensions({});
    const tools = session.getAllTools().map((tool) => tool.name);
    for (const expected of variants[name].tools) { assert.ok(tools.includes(expected), expected); }
    const other = name === 'pi-subagents' ? 'subagent_spawn_batch' : 'subagents_enable';
    assert.ok(!tools.includes(other), 'Other variant must not be loaded');
    console.log(JSON.stringify({ package: name, version: manifest.version,
      verifiedTools: variants[name].tools, modelRequests: 0, workersLaunched: 0 }));
  } finally {
    if (session) { session.dispose(); }
    fs.rmSync(temporary, { recursive: true, force: true });
  }
  process.exit(0);
} else {
  for (const name of Object.keys(variants)) {
    test(`${name}: isolated registration without inference`, () => {
      const result = spawnSync(process.execPath, [fileURLToPath(import.meta.url), '--probe', name],
        { cwd: root, encoding: 'utf8', timeout: 30000 });
      assert.ifError(result.error);
      assert.equal(result.status, 0, result.stdout + result.stderr);
      const receipt = JSON.parse(result.stdout.trim().split('\n').at(-1));
      assert.equal(receipt.package, name);
      assert.equal(receipt.modelRequests, 0);
      assert.equal(receipt.workersLaunched, 0);
    });
  }
}
