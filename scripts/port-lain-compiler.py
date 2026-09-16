#!/usr/bin/env python3
"""Port compiler-owned files from datalog-research into lain-compiler.

This script performs the path and namespace conversion used for the Compiler 2
split. It deliberately excludes runtime sessions, effects, bridges, TUI code,
and generic infrastructure. It never commits or pushes the destination.
"""

from __future__ import annotations

import argparse
from pathlib import Path
import re


FILE_MAP = {
    "propagators/compiler_2/compiler/basis.clj": "src/propagators/compiler/compiler/basis.clj",
    "propagators/compiler_2/compiler/declarations.clj": "src/propagators/compiler/compiler/declarations.clj",
    "propagators/compiler_2/compiler/handlers.clj": "src/propagators/compiler/compiler/handlers.clj",
    "propagators/compiler_2/cps_core.clj": "src/propagators/compiler/cps_core.clj",
    "propagators/compiler_2/main.clj": "src/propagators/compiler/main.clj",
    "propagators/compiler_2/model/context.clj": "src/propagators/compiler/model/context.clj",
    "propagators/compiler_2/model/env.clj": "src/propagators/compiler/model/env.clj",
    "propagators/compiler_2/model/env/access.clj": "src/propagators/compiler/model/env/access.clj",
    "propagators/compiler_2/model/env/binding.clj": "src/propagators/compiler/model/env/binding.clj",
    "propagators/compiler_2/model/env/index.clj": "src/propagators/compiler/model/env/index.clj",
    "propagators/compiler_2/model/env/topology.clj": "src/propagators/compiler/model/env/topology.clj",
    "propagators/compiler_2/model/operator_value.clj": "src/propagators/compiler/model/operator_value.clj",
    "propagators/compiler_2/operators/behavior.clj": "src/propagators/compiler/operators/behavior.clj",
    "propagators/compiler_2/operators/tms.clj": "src/propagators/compiler/operators/tms.clj",
    "propagators/compiler_2/operators/versioned_definition.clj": "src/propagators/compiler/operators/versioned_definition.clj",
    "propagators/compiler_2/runtime/sub_environment.clj": "src/propagators/compiler/lowering/sub_environment.clj",
    "propagators/compiler_behavior/application.clj": "src/propagators/compiler/behavior/application.clj",
    "propagators/compiler_behavior/core.clj": "src/propagators/compiler/behavior/core.clj",
    "propagators/compiler_common/core.clj": "src/propagators/compiler/common/core.clj",
    "test/propagators/compiler_2_call_graph_test.clj": "test/propagators/compiler/call_graph_test.clj",
    "test/propagators/compiler_2_closure_frame_test.clj": "test/propagators/compiler/closure_frame_test.clj",
    "test/propagators/compiler_2_composition_test.clj": "test/propagators/compiler/composition_test.clj",
    "test/propagators/compiler_2_cps_test.clj": "test/propagators/compiler/cps_test.clj",
    "test/propagators/compiler_2_live_environment_test.clj": "test/propagators/compiler/live_environment_test.clj",
    "test/propagators/behavior_compiler_test.clj": "test/propagators/compiler/behavior_compiler_test.clj",
    "test/propagators/gur_accumulating_test.clj": "test/propagators/compiler/gur_accumulating_test.clj",
    "propagators/doc/compiler-2-environment-api.md": "doc/compiler-environment-api.md",
}

DELETE_PATHS = [
    "src/propagators/compiler/deprecated/compiler_core.clj",
    "src/propagators/compiler/deprecated/core.clj",
    "src/propagators/compiler/deprecated/legacy_core.clj",
    "src/propagators/compiler/deprecated/synchronous.clj",
    "src/propagators/compiler/legacy.clj",
    "src/propagators/compiler/predicate_core.clj",
    "src/propagators/compiler/tms_behavior.clj",
    "test/propagators/compiler/gur_linked_list_test.clj",
    "test/propagators/compiler/compiler_test.clj",
    "test/propagators/compiler/organization_test.clj",
]

REPLACEMENTS = [
    ("propagators.compiler-2.runtime.application", "propagators.compiler.lowering.application"),
    ("propagators.compiler-2.runtime.activation", "propagators.compiler.lowering.activation"),
    ("propagators.compiler-2.runtime.lazy-topology", "propagators.compiler.lowering.lazy-topology"),
    ("propagators.compiler-2.runtime.sub-environment", "propagators.compiler.lowering.sub-environment"),
    ("propagators.compiler-2.runtime.topology-effects", "propagators.compiler.lowering.topology-effects"),
    ("propagators.compiler-common", "propagators.compiler.common"),
    ("propagators.compiler-behavior", "propagators.compiler.behavior"),
    ("propagators.compiler-2", "propagators.compiler"),
]

TEST_NAMESPACE_REPLACEMENTS = {
    "propagators.compiler-call-graph-test": "propagators.compiler.call-graph-test",
    "propagators.compiler-closure-frame-test": "propagators.compiler.closure-frame-test",
    "propagators.compiler-composition-test": "propagators.compiler.composition-test",
    "propagators.compiler-cps-test": "propagators.compiler.cps-test",
    "propagators.compiler-live-environment-test": "propagators.compiler.live-environment-test",
    "propagators.behavior-compiler-test": "propagators.compiler.behavior-compiler-test",
    "propagators.gur-accumulating-test": "propagators.compiler.gur-accumulating-test",
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=Path.cwd())
    parser.add_argument("--target", type=Path, required=True)
    parser.add_argument("--dry-run", action="store_true")
    return parser.parse_args()


def transform(text: str) -> str:
    for old, new in REPLACEMENTS:
        text = text.replace(old, new)
    for old, new in TEST_NAMESPACE_REPLACEMENTS.items():
        text = text.replace(old, new)
    return re.sub(r"propagators\.(?!compiler(?:\.|\b))", "propagators.infra.", text)


def validate(source: Path, target: Path) -> None:
    missing = [relative for relative in FILE_MAP if not (source / relative).is_file()]
    if missing:
        raise SystemExit(f"source is missing mapped files: {missing}")
    expected = target / "src/propagators/compiler"
    if not (target / ".git").exists() or not expected.is_dir():
        raise SystemExit(f"target is not a lain-compiler checkout: {target}")


def port(source: Path, target: Path, dry_run: bool) -> None:
    for source_name, target_name in FILE_MAP.items():
        print(f"copy {source_name} -> {target_name}")
        if dry_run:
            continue
        destination = target / target_name
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(
            transform((source / source_name).read_text(encoding="utf-8")),
            encoding="utf-8",
        )

    for relative in DELETE_PATHS:
        path = target / relative
        if path.exists():
            print(f"delete {relative}")
            if not dry_run:
                path.unlink()


def main() -> None:
    args = parse_args()
    source = args.source.resolve()
    target = args.target.resolve()
    validate(source, target)
    port(source, target, args.dry_run)


if __name__ == "__main__":
    main()
