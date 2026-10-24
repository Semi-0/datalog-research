import fs from 'node:fs';
import { spawnSync } from 'node:child_process';
import { createJiti } from '/usr/local/lib/pilot/node_modules/jiti/lib/jiti.mjs';
const { buildSandboxCommand } = await createJiti(import.meta.url).import('/usr/local/lib/pilot/node_modules/pi-better-subagents/sandbox.ts');

const runtimeDir = '/tmp/pilot-tools-runtime';
const home = runtimeDir + '/home';
fs.mkdirSync(home, { recursive: true });
fs.mkdirSync(runtimeDir + '/agent', { recursive: true });
const environment = { ...process.env, HOME: home, PI_CODING_AGENT_DIR: runtimeDir + '/agent',
  XDG_CACHE_HOME: runtimeDir + '/cache', XDG_CONFIG_HOME: runtimeDir + '/config',
  XDG_DATA_HOME: runtimeDir + '/data', TMPDIR: runtimeDir,
  GORTEX_BIN: '/usr/local/lib/pilot/bin/gortex', GORTEX_ENFORCE: 'true',
  GORTEX_NATIVE_MCP: 'false', GORTEX_HOOK_MODE: 'deny' };
const wrapper = buildSandboxCommand({ profilePath: runtimeDir + '/profile',
  writableDir: '/app', home: '/home/agent', runtimeDir, piBin: '/usr/local/lib/pilot/bin/node',
  piArgs: ['/usr/local/lib/pilot/tool-probe.mjs'], permissions: {
    projectFiles: 'read-write', outsideProject: 'off', storedCredentials: 'off',
    commands: true, network: true, processAccess: 'off' } });
const outside = '/home/agent/pilot-outside.clj';
fs.writeFileSync(outside, '(ns pilot.outside)\n');
const result = spawnSync(wrapper.file, wrapper.fileArgs, {
  cwd: '/app', env: environment, encoding: 'utf8', timeout: 220000,
  maxBuffer: 2 * 1024 * 1024 });
const outsideUnchanged = fs.readFileSync(outside, 'utf8') === '(ns pilot.outside)\n';
fs.rmSync(outside);
console.log(JSON.stringify({ wrapper, exit_code: result.status, stdout: result.stdout,
  stderr: result.stderr, outside_unchanged: outsideUnchanged, error: String(result.error ?? '') }));
if (result.status !== 0 || !outsideUnchanged) { process.exitCode = 1; }
