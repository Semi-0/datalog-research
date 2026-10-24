import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { atomicJson, createController } from './control.mjs';

function fixture(t, tools = ['read', 'write']) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'gx10-control-'));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const workspaces = ['one', 'two'].map((name) => {
    const file = path.join(root, name);
    fs.mkdirSync(file);
    return file;
  });
  const calls = [];
  const pi = { registerTool() {}, getAllTools: () => tools.map((name) => ({ name })),
    sendUserMessage() { throw new Error('Parent model must not wake'); },
    sendMessage() { throw new Error('Parent model must not wake'); } };
  const controller = createController(pi, { root, workspaces, tools: ['read', 'write'], workerModel: 'local/qwen' });
  for (const name of ['subagent_spawn_batch', 'subagent_list', 'subagent_result', 'subagent_stop']) {
    controller.facade.registerTool({ name, execute: async (_id, args) => {
      calls.push({ name, args });
      if (name === 'subagent_spawn_batch') {
        return { structuredContent: { launched: [{ id: 'one' }, { id: 'two' }], failed: [], skipped: [] } };
      }
      return { content: [{ type: 'text', text: 'completed' }], details: { status: 'completed' } };
    } });
  }
  return { root, workspaces, controller, calls, pi };
}

test('delegation, status and collection never call parent model APIs', async (t) => {
  const { controller, calls } = fixture(t);
  await controller.execute({ id: 'phase', operation: 'phase', phase: 'workers' }, {});
  await controller.execute({ id: 'spawn', operation: 'subagent_spawn_batch', jobs: [
    { worker: 0, prompt: 'implementation' }, { worker: 1, prompt: 'tests' }] }, {});
  await controller.execute({ id: 'list', operation: 'subagent_list' }, {});
  await controller.execute({ id: 'result', operation: 'subagent_result', runId: 'one' }, {});
  assert.equal(calls.length, 3);
  for (const job of calls[0].args.jobs) {
    assert.equal(job.callback, false);
    assert.equal(job.allow_nested, false);
    assert.equal(job.sandbox, true);
    assert.equal(job.approve, false);
    assert.equal(job.tools, 'read,write');
  }
  assert.notEqual(calls[0].args.jobs[0].cwd, calls[0].args.jobs[1].cwd);
  await assert.rejects(controller.execute({ id: 'early', operation: 'phase', phase: 'integration' }, {}), /Stop and collect/);
});

test('a durable completed launch is reused without duplicate work', async (t) => {
  const { controller, calls } = fixture(t);
  await controller.execute({ id: 'phase', operation: 'phase', phase: 'workers' }, {});
  const request = { id: 'spawn', operation: 'subagent_spawn_batch', jobs: [{ worker: 0, prompt: 'work' }] };
  const first = await controller.execute(request, {});
  assert.deepEqual(await controller.execute(request, {}), first);
  assert.equal(calls.length, 1);
  await assert.rejects(controller.execute({ ...request, jobs: [{ worker: 1, prompt: 'other' }] }, {}), /different request/);
});

test('missing required tools reject before any worker launch', async (t) => {
  const { controller, calls } = fixture(t, ['read']);
  await controller.execute({ id: 'phase', operation: 'phase', phase: 'workers' }, {});
  await assert.rejects(controller.execute({ id: 'spawn', operation: 'subagent_spawn_batch',
    jobs: [{ worker: 0, prompt: 'work' }] }, {}), /setup_declined/);
  assert.equal(calls.length, 0);
});

test('uncertain operations and foreign run access are rejected', async (t) => {
  const { root, controller } = fixture(t);
  atomicJson(path.join(root, 'control', 'uncertain.json'), { state: 'started', digest: 'unknown' });
  await assert.rejects(controller.execute({ id: 'uncertain', operation: 'inventory' }, {}));
  await assert.rejects(controller.execute({ id: 'foreign', operation: 'subagent_stop', runId: 'foreign' }, {}), /not owned/);
  await assert.rejects(controller.execute({ id: '../escape', operation: 'inventory' }, {}), /bounded receipt/);
});

test('overlapping worker workspaces are rejected', (t) => {
  const { root, workspaces, pi } = fixture(t);
  assert.throws(() => createController(pi, { root, workspaces: [workspaces[0], workspaces[0]], tools: [] }), /independent/);
});
