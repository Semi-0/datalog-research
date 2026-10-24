import fs from 'node:fs';
import subagents from './node_modules/pi-better-subagents/index.ts';
import { createController } from './control.mjs';

// This factory owns loading the pinned plugin so control calls use its actual
// registered definitions. No model prompt or sendUserMessage is involved.
export default async function companion(pi) {
  const policyPath = process.env.GX10_DELEGATION_POLICY;
  if (!policyPath) {
    throw new Error('GX10_DELEGATION_POLICY is required');
  }
  const policy = JSON.parse(fs.readFileSync(policyPath, 'utf8'));
  const controller = createController(pi, policy);
  // Explicitly enforce the user's requested workspace boundary. Trusted tools
  // stay disabled until their own path confinement has been verified.
  const permissions = { enabled: true, projectFiles: 'read-write', outsideProject: 'off',
    storedCredentials: 'off', commands: true, network: true, processAccess: 'off' };
  const publish = () => pi.events.emit('pi-better-sandbox:policy', {
    state: 'enabled', subagentPermissions: permissions,
    subagentTools: { applyPatch: false, trusted: [] },
  });
  pi.events.on('pi-better-sandbox:policy-request', publish);
  publish();
  await subagents(controller.facade);
  pi.registerCommand('gx10-control', {
    description: 'Local workflow control without a parent model request',
    handler: async (text, ctx) => {
      const result = await controller.execute(JSON.parse(text), ctx);
      ctx.ui.notify(JSON.stringify(result), 'info');
    },
  });
  // Idle prompt-cache refresh must never wake unloaded Flash weights.
  pi.on('cache_warming_decision', async () => ({ action: 'stop' }));
}
