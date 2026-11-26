"""Run a CI stage with normal stdio and append its timing to LAIN_CI_TIMINGS."""
import json
import os
from pathlib import Path
import subprocess
import sys
import time
from datetime import datetime, timezone


def record(path, stage, event, **fields):
    entry = dict(stage=stage, event=event, utc=datetime.now(timezone.utc).isoformat(), **fields)
    with path.open("a", encoding="utf-8") as stream:
        stream.write(json.dumps(entry, separators=(",", ":")) + "\n")


def main(stage, command):
    path = Path(os.environ["LAIN_CI_TIMINGS"])
    path.parent.mkdir(parents=True, exist_ok=True)
    started = time.monotonic_ns()
    record(path, stage, "start")
    try:
        result = subprocess.run(command).returncode
    except FileNotFoundError:
        result = 127
        print(f"CI executable missing: {command[0]}", file=sys.stderr)
    except PermissionError:
        result = 126
        print(f"Cannot execute: {command[0]}", file=sys.stderr)
    elapsed_ms = (time.monotonic_ns() - started) // 1_000_000
    code = result if result >= 0 else 128 - result
    record(path, stage, "finish", elapsed_ms=elapsed_ms, exit_code=code,
           status="passed" if code == 0 else "failed")
    print(f"[CI] {stage} {elapsed_ms} ms (exit {code})", file=sys.stderr, flush=True)
    return code


if __name__ == "__main__":
    if len(sys.argv) < 3:
        sys.exit("Usage: ci_timing.py STAGE COMMAND [ARG ...]")
    sys.exit(main(sys.argv[1], sys.argv[2:]))
