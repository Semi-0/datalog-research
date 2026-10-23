#!/usr/bin/env python3
"""Run required deterministic checks, retain raw outputs and bind evidence to code."""
import argparse
from pathlib import Path
import subprocess
import sys
import time
from lifecycle import save
from suite import HERE, NVFP4, harness_hashes


def validate(output):
    output.mkdir(parents=True, exist_ok=False)
    rows = []
    for directory, modules in [(HERE, ['test_lifecycle', 'test_context_probe', 'test_evaluators', 'test_pi_runtime', 'test_context_continuation', 'test_gortex_profile', 'test_queue', 'test_supervision', 'test_protocol6']),
                               (NVFP4, ['test_runner', 'test_nontermination', 'test_supervision_worker'])]:
        command = [sys.executable, '-m', 'unittest', '-v', *modules]
        completed = subprocess.run(command, cwd=directory, text=True, capture_output=True)
        name = directory.name + '.txt'
        (output / name).write_text(completed.stdout + completed.stderr)
        rows.append({'command': command, 'exit': completed.returncode, 'raw': str(output / name)})
    passed = all(row['exit'] == 0 for row in rows)
    save(output / 'deterministic.json', {'passed': passed, 'harness_hashes': harness_hashes(),
         'checks': rows, 'epoch': time.time(), 'python': sys.version})
    if not passed:
        raise SystemExit('Deterministic checks failed: ' + str(output))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    validate(parser.parse_args().output.resolve())
