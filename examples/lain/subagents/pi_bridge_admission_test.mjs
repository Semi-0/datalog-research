import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { admittedPiCliPath, createBridge } from './pi_bridge.mjs';

function temporaryRoot(t) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'lain-bridge-admission-'));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  return root;
}

test('sandbox CLI resolves to the active pinned SDK before an older PATH entry', (t) => {
  const root = temporaryRoot(t);
  const bin = path.join(root, '.bin');
  const cli = path.join(root, '@earendil-works/pi-coding-agent/dist/bundle/cli.js');
  fs.mkdirSync(bin, { recursive: true });
  fs.mkdirSync(path.dirname(cli), { recursive: true });
  fs.writeFileSync(cli, '// admitted fixture CLI\n', { mode: 0o755 });
  fs.symlinkSync(cli, path.join(bin, 'pi'));
  const previous = path.join(root, 'older-bin');
  const entries = admittedPiCliPath(root, previous + path.delimiter).split(path.delimiter);
  assert.equal(entries[0], bin);
  assert.equal(fs.realpathSync(path.join(entries[0], 'pi')), fs.realpathSync(cli));
  assert.deepEqual(entries.slice(1), [previous]);
  const foreign = path.join(root, 'foreign-cli.js');
  fs.writeFileSync(foreign, '// foreign CLI\n');
  fs.unlinkSync(path.join(bin, 'pi'));
  fs.symlinkSync(foreign, path.join(bin, 'pi'));
  assert.throws(() => admittedPiCliPath(root, previous), /Pinned SDK CLI/);
});

function receiptFixture(t, structuredContent) {
  const root = temporaryRoot(t);
  let calls = 0;
  const bridge = createBridge({
    session: 'main', root,
    control: async (request) => {
      calls += 1;
      fs.mkdirSync(path.join(root, 'control'), { recursive: true });
      fs.writeFileSync(path.join(root, 'control', request.id + '-launch.json'),
        JSON.stringify({ structuredContent }));
      throw new Error('Unacknowledged controller delivery');
    },
    transcript: async () => { throw new Error('No child transcript expected'); },
    verifyStopped: async () => true,
    disposeSession: async () => {},
  });
  return { bridge, calls: () => calls };
}

const launch = { session: 'main', 'effect-id': ['trial', 'launch'],
  tool: 'subagent_spawn_batch', arguments: { tasks: [{ task: 'Read the fixture' }] } };
const reconcile = { session: 'main', 'effect-id': ['trial', 'reconcile'],
  operation: 'reconcile', 'launch-effect-id': launch['effect-id'] };

test('reconcile preserves a durable not-launched failure without another invocation', async (t) => {
  const f = receiptFixture(t, { status: 'not-launched', launched: [],
    failed: [{ reason: 'active SDK CLI rejected' }], skipped: [] });
  await assert.rejects(f.bridge.execute(launch), /Unacknowledged/);
  const result = await f.bridge.execute(reconcile);
  assert.equal(result.status, 'failed');
  assert.equal(result.failure.code, 'not-launched');
  assert.match(result.failure.message, /active SDK CLI rejected/);
  assert.equal(f.calls(), 1);
  assert.equal((await f.bridge.execute({ session: 'main', 'effect-id': ['dispose'],
    operation: 'dispose' }))['cleanup-verified'], true);
});

test('incomplete no-child evidence stays uncertain and never relaunches', async (t) => {
  const f = receiptFixture(t, { status: 'not-launched', launched: [], failed: [], skipped: [] });
  await assert.rejects(f.bridge.execute(launch), /Unacknowledged/);
  assert.equal((await f.bridge.execute(reconcile)).status, 'uncertain');
  assert.equal(f.calls(), 1);
});
