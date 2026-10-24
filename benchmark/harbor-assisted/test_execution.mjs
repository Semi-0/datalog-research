import test from 'node:test';
import assert from 'node:assert/strict';
import { execution } from './execution.mjs';

const complete = { details: { status: 'completed', head: 'exit 0 · lifecycle complete' } };
const message = (stopReason, output = 1, errorMessage) => ({ role: 'assistant', stopReason,
  usage: { output }, errorMessage });

test('normal completion requires lifecycle, a final stop and generated tokens', () => {
  assert.equal(execution([message('stop')], complete).outcome, 'completed');
  assert.equal(execution([message('stop', 0)], complete).outcome, 'incomplete_response');
  assert.equal(execution([], complete).outcome, 'incomplete_response');
  assert.equal(execution([message('stop')], { details: { status: 'completed', head: 'exit 0' } }).outcome,
    'incomplete_response');
});

test('authentication failures cannot become success through exit zero', () => {
  assert.equal(execution([message('error', 0, '401 invalid api key')], complete).outcome,
    'authentication_error');
});

test('request or token budget exhaustion remains an execution stop', () => {
  assert.equal(execution([message('error', 0, '429 request_or_token_budget_exhausted')], complete).outcome,
    'budget_exhausted');
});

test('aborted and truncated messages remain separate execution failures', () => {
  assert.equal(execution([message('aborted')], complete).outcome, 'interrupted');
  assert.equal(execution([message('length'), message('stop')], complete).outcome, 'incomplete_response');
  assert.equal(execution([message('error', 1, 'connection reset')], complete).outcome, 'provider_error');
});
