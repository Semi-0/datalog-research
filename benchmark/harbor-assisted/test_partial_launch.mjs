import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { createController } from '../gx10-langgraph/control.mjs';

test('partial launch preserves ownership, rejects receipt reuse and permits owned cleanup', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'assisted-partial-'));
  const workspace = path.join(root, 'workspace');
  fs.mkdirSync(workspace);
  let launches = 0;
  const pi = { registerTool() {}, getAllTools: () => [{ name: 'read' }],
    sendUserMessage: () => { throw new Error('parent model call'); } };
  const controller = createController(pi, { root, workspaces: [workspace], tools: ['read'], workerModel: 'fixture/model' });
  controller.facade.registerTool({ name: 'subagent_spawn_batch', execute: async () => {
    launches += 1;
    return { isError: true, structuredContent: { launched: [{ id: 'owned-partial' }],
      failed: [{ error: 'fixture launch failure' }], skipped: [] } };
  } });
  controller.facade.registerTool({ name: 'subagent_stop', execute: async () => ({ content: [] }) });
  try {
    await controller.execute({ id: 'phase', operation: 'phase', phase: 'workers' }, {});
    const request = { id: 'launch', operation: 'subagent_spawn_batch', jobs: [{ worker: 0, prompt: 'fixture' }] };
    await assert.rejects(controller.execute(request, {}), /Partial or failed batch launch/);
    assert.deepEqual(JSON.parse(fs.readFileSync(path.join(root, 'controller.json'))).runs, ['owned-partial']);
    assert.equal(JSON.parse(fs.readFileSync(path.join(root, 'control/launch.json'))).state, 'failed');
    await assert.rejects(controller.execute(request, {}), /Uncertain or failed operation/);
    assert.equal(launches, 1);
    await controller.execute({ id: 'stop', operation: 'subagent_stop', runId: 'owned-partial' }, {});
    assert.deepEqual(JSON.parse(fs.readFileSync(path.join(root, 'controller.json'))).runs, []);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});
