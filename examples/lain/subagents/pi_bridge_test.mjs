// Offline contracts only. No SDK session, child, model, container or network is started.
import assert from 'node:assert/strict';
import test from 'node:test';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { Readable } from 'node:stream';
import childAdmission, { validateConfig, verifySecurity, decodeResult, createBridge, serve, scanTranscripts, MAX_FRAME } from './pi_bridge.mjs';
import { atomicJson } from '../../../benchmark/gx10-langgraph/control.mjs';

const config = { session: 's', plugin: 'pi-better-subagents', version: '0.19.1', mode: 'live', toolsAdmitted: true,
  cwd: '/app', tools: ['read'], provider: 'fixture', model: 'never-requested', modelBaseUrl: 'http://127.0.0.1:1/v1',
  root: '/logs/agent/lain-fixture', contextWindow: 4096, maxTokens: 128 };
const security = 'Uid:\t1000\t1000\t1000\t1000\nNoNewPrivs:\t1\nCapEff:\t0000000000000000\nCapBnd:\t0000000000000000\n';
const launch = { session: 's', 'effect-id': ['s', 'launch'], tool: 'subagent_spawn_batch',
  arguments: { tasks: [{ task: 'Read the fixture nonce' }] } };

function fixture(t, { cleanup = true, untracked = false } = {}) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'lain-pi-bridge-'));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const calls = []; let disposed = 0;
  const control = async (request) => {
    const file = path.join(root, 'control', request.id + '.json');
    if (fs.existsSync(file)) { return JSON.parse(fs.readFileSync(file)).result; }
    calls.push(request);
    let result;
    if (request.operation === 'subagent_spawn_batch') {
      result = { structuredContent: { launched: [{ id: 'owned-child' }], failed: [], skipped: [] } };
    } else if (request.operation === 'subagent_result') {
      result = { content: [{ type: 'text', text: 'bounded Pi result page' }], details: { status: 'completed' } };
    } else {
      result = { content: [{ type: 'text', text: 'Stopped subagent owned-child.' }] };
    }
    atomicJson(file, { state: 'completed', result });
    return result;
  };
  const bridge = createBridge({ session: 's', root, control,
    verifyStopped: async () => cleanup, disposeSession: async () => { disposed += 1; },
    discoverOwned: async () => { if (untracked) { return ['unacknowledged-live-child']; } return []; },
    transcript: async () => ({ text: 'NONCE', artifact: 'child.jsonl',
      admission: { admitted: true, tools: ['read'], provider: 'fixture', model: 'never-requested' } }),
  });
  return { root, bridge, calls, disposed: () => disposed };
}

test('live configuration and confinement fail closed', () => {
  assert.equal(validateConfig(config), config);
  assert.throws(() => validateConfig({ ...config, mode: 'replay' }));
  assert.throws(() => validateConfig({ ...config, toolsAdmitted: false }));
  assert.throws(() => validateConfig({ ...config, plugin: 'pi-subagents' }));
  assert.doesNotThrow(() => verifySecurity(security, 'gx10-harbor-bwrap-v1 (enforce)'));
  assert.throws(() => verifySecurity(security, 'unconfined'));
  assert.throws(() => verifySecurity(security.replace('NoNewPrivs:\t1', 'NoNewPrivs:\t0'), 'gx10-harbor-bwrap-v1 (enforce)'));
});

test('actual child loadout is checked and persisted before inference', async () => {
  const previous = process.env.LAIN_PI_CHILD_POLICY;
  process.env.LAIN_PI_CHILD_POLICY = JSON.stringify({ ...config, nonce: 'fixture-nonce' });
  const handlers = new Map(); const entries = [];
  const pi = { on: (kind, f) => handlers.set(kind, f), getActiveTools: () => ['read'], getAllTools: () => [],
    appendEntry: (...entry) => entries.push(entry) };
  try {
    childAdmission(pi);
    const ctx = { cwd: '/app', model: { provider: 'fixture', id: 'never-requested' } };
    await handlers.get('before_agent_start')({}, ctx);
    assert.equal(entries[0][0], 'lain-child-admission');
    assert.equal(entries[0][1].admitted, true);
    pi.getActiveTools = () => ['read', 'bash'];
    await assert.rejects(handlers.get('before_agent_start')({}, ctx), /admission mismatch/);
    assert.equal(entries[1][1].admitted, false);
  } finally {
    if (previous === undefined) { delete process.env.LAIN_PI_CHILD_POLICY; }
    else { process.env.LAIN_PI_CHILD_POLICY = previous; }
  }
});

test('pinned ToolResult progress, terminal answer and admission normalization', () => {
  assert.deepEqual(decodeResult('c', { details: { status: 'running' } }), { status: 'running', child: 'c' });
  assert.equal(decodeResult('c', { details: { status: 'failed' } }).status, 'failed');
  assert.throws(() => decodeResult('c', { details: { status: 'completed' } }), /admission/);
  const result = decodeResult('c', { details: { status: 'completed' } },
    { text: 'nonce', artifact: 'child.jsonl', admission: { admitted: true, tools: ['read'] } });
  assert.equal(result.result.text, 'nonce');
  assert.throws(() => decodeResult('c', { details: { status: 'new-unrecognized-state' } }));
});

test('durable launch receipts deduplicate and reconcile original launch identity', async (t) => {
  const { bridge, calls } = fixture(t);
  assert.equal((await bridge.execute(launch)).child, 'owned-child');
  assert.equal((await bridge.execute(launch)).child, 'owned-child');
  assert.equal(calls.filter((request) => request.operation === 'subagent_spawn_batch').length, 1);
  await assert.rejects(bridge.execute({ ...launch, 'effect-id': ['s', 'forbidden-context'],
    'assignment-context': { 'grading-secret': 'must never reach child' } }), /public execution/);
  assert.deepEqual(await bridge.execute({ session: 's', 'effect-id': ['s', 'reconcile'],
    'launch-effect-id': launch['effect-id'], operation: 'reconcile' }), { status: 'reconciled', child: 'owned-child' });
  const result = await bridge.execute({ session: 's', 'effect-id': ['s', 'collect'], tool: 'subagent_result', arguments: { id: 'owned-child' } });
  assert.equal(result.result.text, 'NONCE');
  await assert.rejects(bridge.execute({ ...launch, session: 'foreign' }), /Foreign/);
  await assert.rejects(bridge.execute({ session: 's', 'effect-id': ['s', 'stop'], tool: 'subagent_stop', arguments: { id: 'foreign-child' } }), /Foreign child/);
});

test('partial launch receipt remains reconcilable without relaunch', async (t) => {
  const { root, bridge, calls } = fixture(t);
  const id = (await import('node:crypto')).default.createHash('sha256').update(JSON.stringify(launch['effect-id'])).digest('hex');
  atomicJson(path.join(root, 'control', id + '-launch.json'), { structuredContent: {
    launched: [{ id: 'partial-child' }], failed: [{}], skipped: [] } });
  const result = await bridge.execute({ session: 's', 'effect-id': ['s', 'reconcile'],
    'launch-effect-id': launch['effect-id'], operation: 'reconcile' });
  assert.equal(result.child, 'partial-child');
  assert.equal(calls.length, 0);
  atomicJson(path.join(root, 'controller.json'), { runs: ['partial-child', 'unacknowledged-child'] });
  await bridge.execute({ session: 's', 'effect-id': ['s', 'dispose'], operation: 'dispose' });
  assert.equal(calls.filter((request) => request.operation === 'subagent_stop').length, 2);
});

test('stop/disposal verifies child cleanup and enforces ownership ledger', async (t) => {
  const { root, bridge, disposed } = fixture(t);
  await bridge.execute(launch);
  assert.deepEqual(await bridge.execute({ session: 's', 'effect-id': ['s', 'dispose'], operation: 'dispose' }), { 'cleanup-verified': true });
  assert.equal((await bridge.execute({ session: 's', 'effect-id': ['s', 'dispose'], operation: 'dispose' }))['already-closed'], true);
  assert.equal(disposed(), 1);
  assert.deepEqual(JSON.parse(fs.readFileSync(path.join(root, 'bridge-owned.json'))).children, []);
  assert.throws(() => createBridge({ session: 'foreign', root }), /Foreign bridge ownership/);
  const failed = fixture(t, { cleanup: false });
  await failed.bridge.execute(launch);
  await assert.rejects(failed.bridge.execute({ session: 's', 'effect-id': ['s', 'dispose'], operation: 'dispose' }), /cleanup remains unverified/);
  assert.equal(failed.disposed(), 0);
  const uncertain = fixture(t, { untracked: true });
  await assert.rejects(uncertain.bridge.execute({ session: 's', 'effect-id': ['s', 'dispose'], operation: 'dispose' }), /container cleanup required/);
  assert.equal(uncertain.disposed(), 0);
});

test('concurrent disposal never claims cleanup while a launch is in flight', async (t) => {
  const { root } = fixture(t);
  let release;
  const bridge = createBridge({ session: 's', root, verifyStopped: async () => true,
    disposeSession: async () => {}, control: async (request) => {
      if (request.operation === 'subagent_spawn_batch') { return new Promise((resolve) => { release = resolve; }); }
      return {};
    } });
  const pending = bridge.execute(launch);
  await assert.rejects(bridge.execute({ session: 's', 'effect-id': ['s', 'dispose'], operation: 'dispose' }), /Launch in flight/);
  release({ structuredContent: { launched: [{ id: 'owned-child' }], failed: [], skipped: [] } });
  await pending;
  assert.equal((await bridge.execute({ session: 's', 'effect-id': ['s', 'dispose'], operation: 'dispose' }))['cleanup-verified'], true);
});

test('transcript discovery requires actual matching child admission and ignores copied artifacts', (t) => {
  const { root } = fixture(t);
  const rows = [{ type: 'session', id: 'child-session' },
    { type: 'custom', customType: 'lain-child-admission', data: { nonce: 'guard-nonce', admitted: true, tools: ['read'] } }];
  const source = path.join(root, 'session.jsonl');
  fs.writeFileSync(source, rows.map((row) => JSON.stringify(row)).join('\n') + '\n');
  fs.mkdirSync(path.join(root, 'bridge-collected'));
  fs.copyFileSync(source, path.join(root, 'bridge-collected', 'child-copy.jsonl'));
  assert.equal(scanTranscripts([root], 'child-session', 'guard-nonce').length, 1);
  assert.equal(scanTranscripts([root], 'other-session', 'guard-nonce').length, 0);
  assert.equal(scanTranscripts([root], 'child-session', 'wrong-nonce').length, 0);
});

test('JSON-lines stays bounded, correlated and marks lost launch acknowledgement uncertain', async () => {
  let output = '';
  await serve(Readable.from([JSON.stringify({ ...launch, id: 'frame-1' }) + '\n']),
    { write: (chunk) => { output += chunk; } }, { execute: async () => { throw new Error('private failure detail'); } });
  const frame = JSON.parse(output);
  assert.equal(frame.id, 'frame-1'); assert.equal(frame.result.status, 'uncertain');
  assert.equal(output.includes('private failure detail'), false);
  await assert.rejects(serve(Readable.from(['x'.repeat(MAX_FRAME + 1)]), { write() {} }, {}), /frame exceeds limit/);
  await assert.rejects(serve(Readable.from(['{}']), { write() {} }, {}), /Truncated/);
});
