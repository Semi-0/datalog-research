#!/usr/bin/env python3
"""Copy compact experiment progress into KIROSHI; leave raw sessions outside Git."""
import argparse
import json
from pathlib import Path
import time


def record(run_root):
    destination = Path(__file__).resolve().parent / "results"
    destination.mkdir(exist_ok=True)
    last = None
    while True:
        value = {"remote_run_root": str(run_root)}
        for name in ["status", "results", "failure"]:
            path = run_root / (name + ".json")
            if path.exists():
                value[name] = json.loads(path.read_text())
        text = json.dumps(value, indent=2) + "\n"
        if text != last:
            path = destination / (run_root.name + ".json")
            temporary = path.with_suffix(".json.tmp")
            temporary.write_text(text)
            temporary.replace(path)
            report = run_root / "REPORT.md"
            if report.exists():
                (destination / (run_root.name + ".md")).write_text(report.read_text())
            last = text
        if value.get("status", {}).get("stage") == "complete" or "failure" in value:
            return
        time.sleep(5)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-root", type=Path, required=True)
    record(parser.parse_args().run_root.resolve())
