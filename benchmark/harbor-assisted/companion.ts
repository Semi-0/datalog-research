import fs from 'node:fs';
import subagents from '/usr/local/lib/pilot/node_modules/pi-better-subagents/index.ts';
import { discoverTrustedTools } from '/usr/local/lib/pilot/node_modules/pi-better-subagents/shared-task-tools.ts';
import { createController } from './control.mjs';

export default async function companion(pi) {
  const policy = JSON.parse(fs.readFileSync(process.env.GX10_DELEGATION_POLICY, 'utf8'));
  if (policy.toolsAdmitted !== true) {
    throw new Error('Required-tool confinement admission is missing');
  }
  const controller = createController(pi, policy);
  let trusted = [];
  const publish = () => pi.events.emit('pi-better-sandbox:policy', {
    state: 'enabled', subagentPermissions: { enabled: true, projectFiles: 'read-write',
      outsideProject: 'off', storedCredentials: 'off', commands: true, network: true,
      processAccess: 'off' }, subagentTools: { applyPatch: false, trusted },
  });
  pi.events.on('pi-better-sandbox:policy-request', publish);
  pi.on('session_start', async () => {
    trusted = discoverTrustedTools(pi.getAllTools())
      .filter((tool) => policy.tools.includes(tool.name))
      .map((tool) => ({ name: tool.name, package: tool.package }));
    const admitted = new Set(trusted.map((tool) => tool.name));
    const missing = policy.tools.filter((name) => !['read', 'write', 'edit', 'bash'].includes(name) && !admitted.has(name));
    if (missing.length > 0) { throw new Error('Required trusted tools missing: ' + missing.join(',')); }
    publish();
  });
  publish();
  await subagents(controller.facade);
  pi.registerCommand('gx10-control', {
    description: 'Durable local control; never sends a parent model request',
    handler: async (text, ctx) => {
      const result = await controller.execute(JSON.parse(text), ctx);
      ctx.ui.notify(JSON.stringify(result), 'info');
    },
  });
  pi.on('before_agent_start', async () => { throw new Error('Parent inference is prohibited'); });
  pi.on('cache_warming_decision', async () => ({ action: 'stop' }));
}
