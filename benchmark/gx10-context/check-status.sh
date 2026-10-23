#!/usr/bin/env bash
set -euo pipefail
root="${1:?Usage: check-status.sh /absolute/run-root}"
python3 - "$root" <<'PY'
import json,sys
from pathlib import Path
root=Path(sys.argv[1])
for name in ['status.json','checkpoint.json','failure.json','summary.json','live-validation.json']:
    path=root/name
    if path.exists():
        value=json.loads(path.read_text())
        if name=='summary.json':
            value={'coding_record_count':value['coding_record_count'],'total_records':len(value['rows'])}
        print(name, json.dumps(value,indent=2))
for path in sorted(root.glob('cells/*/checkpoint.json')):
    value=json.loads(path.read_text())
    if value['stage']!='complete':
        print(str(path),json.dumps(value))
PY
