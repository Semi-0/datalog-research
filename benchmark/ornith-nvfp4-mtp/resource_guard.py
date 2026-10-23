#!/usr/bin/env python3
"""Supervise an experiment-owned server and preserve the physical RAM reserve."""
import argparse
import json
import os
from pathlib import Path
import signal
import subprocess
import time

from coding import RESERVE, memory, save


def supervise(command_file, output):
    output.mkdir(parents=True, exist_ok=True)
    command = json.loads(command_file.read_text())
    if memory()["available"] < RESERVE:
        raise RuntimeError("Memory below reserve before server startup")
    process = subprocess.Popen(command, start_new_session=True)
    minimum = memory()["available"]
    stopped = False
    try:
        with (output / "server-memory.jsonl").open("w") as log:
            while process.poll() is None:
                sample = {"epoch": time.time(), **memory()}
                minimum = min(minimum, sample["available"])
                log.write(json.dumps(sample) + "\n")
                log.flush()
                if sample["available"] < RESERVE:
                    stopped = True
                    break
                time.sleep(0.2)
    finally:
        if process.poll() is None:
            try:
                os.killpg(process.pid, signal.SIGTERM)
                process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait()
            except ProcessLookupError:
                pass
        save(output / "server-exit.json", {"exit": process.returncode,
            "resource_stopped": stopped, "minimum_available_bytes": minimum,
            "reserve_bytes": RESERVE})
    if stopped:
        return 91
    return process.returncode


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--command", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    raise SystemExit(supervise(args.command, args.output))
