#!/usr/bin/env python3
"""Unscored Pi smoke through the same guarded coding worker."""
import argparse
from pathlib import Path
import sys
sys.path.insert(0, str(Path(__file__).resolve().parent.parent / 'ornith-nvfp4-mtp'))
import coding
import outcomes

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--source', type=Path, default=Path.home() / 'projects/datalog-research')
    parser.add_argument('--task', default='smoke')
    parser.add_argument('--context', type=int, default=32768)
    parser.add_argument('--model', required=True)
    parser.add_argument('--base-url', required=True)
    parser.add_argument('--server-unit', required=True)
    previous = Path.home() / 'experiments/pi-gx10-capability-20261021'
    parser.add_argument('--web-extension', type=Path, default=previous / 'web-packages/node_modules/pi-web-access/dist/index.js')
    parser.add_argument('--web-guard', type=Path, default=previous / 'benchmark-web-policy.ts')
    coding.CONTRACTS = Path(__file__).resolve().parent / 'smoke-contract'
    outcomes.EXPECTED_ASSERTIONS['smoke'] = 1
    coding.run(parser.parse_args())
