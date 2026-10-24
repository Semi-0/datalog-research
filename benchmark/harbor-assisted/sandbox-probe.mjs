// Exercise the exact pinned plugin wrapper inside an unprivileged Harbor container.
import fs from 'node:fs';
import { spawnSync } from 'node:child_process';
import { buildSandboxCommand } from '/opt/pilot/plugin/sandbox.ts';

const outside = '/home/agent/pilot-outside.txt';
const inside = '/app/.pilot-inside.txt';
const link = '/app/.pilot-outside-link';
fs.writeFileSync(outside, 'unchanged');
fs.symlinkSync(outside, link);
const permissions = { projectFiles: 'read-write', outsideProject: 'off',
  storedCredentials: 'off', commands: true, network: true, processAccess: 'off' };
let receipt;
try {
  const wrapper = buildSandboxCommand({ profilePath: '/tmp/pilot-profile',
    writableDir: '/app', home: '/home/agent', piBin: '/usr/local/bin/pilot-node',
    piArgs: ['-e', `const fs = require('node:fs');
      const { spawnSync } = require('node:child_process');
      fs.writeFileSync(${JSON.stringify(inside)}, 'inside');
      const failures = [];
      for (const path of [${JSON.stringify(outside)}, ${JSON.stringify(link)}, '../home/agent/pilot-outside.txt']) {
        let rejected = false;
        try { fs.writeFileSync(path, 'escaped'); }
        catch { rejected = true; }
        if (!rejected) { failures.push(path); }
      }
      const shell = spawnSync('/bin/sh', ['-c', 'printf escaped > /home/agent/pilot-outside.txt']);
      if (shell.error || shell.status === null || shell.status === 0) { failures.push('shell confinement'); }
      fs.renameSync(${JSON.stringify(inside)}, ${JSON.stringify(inside + '.renamed')});
      fs.unlinkSync(${JSON.stringify(inside + '.renamed')});
      fs.writeFileSync(${JSON.stringify(inside)}, 'inside');
      console.log(JSON.stringify({ inside_write_rename_delete: true,
        absolute_relative_symlink_shell_rejected: failures.length === 0, failures }));
      if (failures.length > 0) { process.exit(91); }
    `], permissions });
  const result = spawnSync(wrapper.file, wrapper.fileArgs, {
    cwd: '/app', encoding: 'utf8', timeout: 30000 });
  receipt = { sandbox_passed: result.status === 0 && fs.existsSync(inside)
      && fs.readFileSync(outside, 'utf8') === 'unchanged',
    wrapper, exit_code: result.status, signal: result.signal,
    stdout: result.stdout, stderr: result.stderr, error: String(result.error ?? '') };
} catch (error) {
  receipt = { sandbox_passed: false, error: String(error) };
} finally {
  for (const path of [outside, inside, inside + '.renamed', link]) {
    fs.rmSync(path, { force: true });
  }
}
process.stdout.write(JSON.stringify(receipt) + '\n');
if (!receipt.sandbox_passed) { process.exitCode = 1; }
