"""Fixed task declarations and fail-closed handoff checks for the delegation pilot."""
import json
import re
import subprocess
from pathlib import Path

LANES = {
    'normalize': {'source': 'delegation/normalize.clj', 'test': 'test/delegation/normalize_test.clj',
        'namespace': 'delegation.normalize', 'function': 'normalize-events', 'args': 'events',
        'contract': 'normalize-events accepts nil or a finite sequence of event vectors. Return a vector, preserving order. Valid forms are [:put key value], [:delete key], [:increment key integer-delta]. Keys and put values may be any Clojure value including nil and false. Reject non-vectors, unknown operations, wrong arities and noninteger deltas with ex-info {:type :invalid-event :index i :event event} for the first invalid event. Do not mutate input.'},
    'project': {'source': 'delegation/project.clj', 'test': 'test/delegation/project_test.clj',
        'namespace': 'delegation.project', 'function': 'project-events', 'args': 'initial events',
        'contract': 'project-events accepts a Clojure map initial and already syntactically validated finite events. Reduce [:put k v], [:delete k], [:increment k delta]. Missing increment keys start at zero; existing values must be integers. A present nil/false is not missing. Reject nonmap initial with ex-info {:type :invalid-state}; reject invalid increment targets with ex-info {:type :invalid-event :index i :event event}. Return a map; never mutate input.'},
    'order': {'source': 'delegation/order.clj', 'test': 'test/delegation/order_test.clj',
        'namespace': 'delegation.order', 'function': 'dependency-order', 'args': 'nodes edges',
        'contract': 'dependency-order takes a vector of distinct arbitrary Clojure values and finite [before after] edges. Return every node once, choosing the earliest ORIGINAL node among ALL currently ready nodes at every step. Duplicate edges count once. Unknown endpoints throw ex-info {:type :unknown-node}; all cycles throw {:type :cycle}. nil and false nodes are valid. Empty nodes/edges returns []. Handle a 10000-node chain without quadratic scanning.'},
    'batch': {'source': 'delegation/batch.clj', 'test': 'test/delegation/batch_test.clj',
        'namespace': 'delegation.batch', 'function': 'execute-batch', 'args': 'initial jobs edges',
        'contract': 'execute-batch accepts initial map, jobs vector of {:id arbitrary-unique-value :events finite-events}, and dependency edges between job IDs. Use delegation.order/dependency-order for deterministic order, delegation.normalize/normalize-events for each job, and delegation.project/project-events for reduction. Return {:order ordered-job-ids :state final-map}. Forward dependency, validation and state exceptions. Empty jobs returns {:order [] :state initial}, rejecting nonmap initial even then. Do not reimplement the other three functions. Write integration tests, including the newly-ready ordering rule. The other modules are initially stubs; tests may fail until their patches are integrated. Report this explicitly.'},
}


def owned_paths(lane):
    return {LANES[lane]['source'], LANES[lane]['test']}


def validate_plan(value):
    if not isinstance(value, dict) or set(value) != {'assignments'}:
        raise ValueError('Plan requires exactly assignments')
    assignments = value['assignments']
    if not isinstance(assignments, list) or len(assignments) != 4:
        raise ValueError('Plan must assign exactly four workers')
    result = {}
    for item in assignments:
        if not isinstance(item, dict) or set(item) != {'lane', 'instructions'}:
            raise ValueError('Each assignment requires lane and instructions only')
        lane = item['lane']
        if lane not in LANES or lane in result:
            raise ValueError('Unknown or duplicate lane')
        text = item['instructions']
        if not isinstance(text, str) or not text.strip() or len(text) > 4000:
            raise ValueError('Instructions must contain 1..4000 characters')
        result[lane] = text
    return result


def seed_checkout(checkout):
    for lane, spec in LANES.items():
        source = checkout / spec['source']
        source.parent.mkdir(parents=True, exist_ok=True)
        source.write_text('(ns ' + spec['namespace'] + ')\n(defn ' + spec['function'] +
                          ' [' + spec['args'] + '] (throw (ex-info "Not implemented" {})))\n')
        test = checkout / spec['test']
        test.parent.mkdir(parents=True, exist_ok=True)
        test.write_text('(ns ' + spec['namespace'] + '-test (:require [clojure.test :refer :all] [' +
                        spec['namespace'] + ' :refer :all]))\n')
    (checkout / 'TASK.md').write_text('\n\n'.join(lane + ': ' + spec['contract'] for lane, spec in LANES.items()))


def collect_patch(checkout, lane, output):
    """Capture only owned files, refusing extra files, symlinks, and executable modes."""
    allowed = owned_paths(lane)
    status = subprocess.check_output(['git', 'status', '--porcelain=v1', '-z', '--untracked-files=all'], cwd=checkout)
    paths = []
    for entry in status.split(b'\0'):
        if not entry:
            continue
        if entry[:2] not in [b' M', b'M ', b'??']:
            raise ValueError('Unsupported Git change status')
        name = entry[3:].decode()
        if name not in allowed:
            raise ValueError('Worker changed unowned path: ' + name)
        path = checkout / name
        if path.is_symlink() or not path.is_file() or path.stat().st_mode & 0o111:
            raise ValueError('Unsafe worker artifact: ' + name)
        paths.append(name)
    if not paths:
        raise ValueError('Worker produced no changes')
    patch = subprocess.check_output(['git', 'diff', '--binary', 'HEAD', '--', *sorted(paths)], cwd=checkout)
    if not patch:
        raise ValueError('Empty patch')
    output.write_bytes(patch)
    return {'lane': lane, 'paths': sorted(paths), 'patch': str(output)}


def assertion_counts(text):
    match = re.search(r'Ran (\d+) tests containing (\d+) assertions\.\s*(\d+) failures, (\d+) errors\.', text)
    if match is None:
        return None
    return dict(zip(['tests', 'assertions', 'failures', 'errors'], map(int, match.groups())))


def tests_passed(exit_code, text):
    counts = assertion_counts(text)
    return (exit_code == 0 and counts is not None and counts['tests'] > 0 and
            counts['assertions'] > 0 and counts['failures'] == 0 and counts['errors'] == 0)
