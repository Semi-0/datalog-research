"""Build a frozen, curated runtime artifact; never deploy the release repository."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile

REPO = Path(__file__).resolve().parents[2]
PREFIXES = ("propagators", "graph", "differential_dataflow", "differential_leapfrog",
            "leapfrog", "reference", "deprecated", "deploy/session_sandbox")
SOURCE_SUFFIXES = {".clj", ".cljc", ".cljs", ".lain", ".edn", ".py"}
ROOT_FILES = ("as_messages.clj",)


def classpath():
    environment = dict(os.environ)
    result = subprocess.run(["clojure", "-A:test", "-Spath"], cwd=REPO, env=environment,
                            check=True, capture_output=True, text=True)
    return [Path(part).resolve() for part in result.stdout.strip().split(os.pathsep)]


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def verify_runtime(stage, entries):
    """Require and run from the artifact alone, with no repository classpath."""
    code = r'''
(require '[deploy.session-sandbox.runtime :as sandbox])
(let [owner (sandbox/create-owner)
      checks (volatile! 0)
      check! (fn [condition message]
               (when-not condition (throw (ex-info message {})))
               (vswap! checks inc))]
  (try
    (let [result (sandbox/execute! owner
                   {:op "run" :generation 0 :requestId (str (java.util.UUID/randomUUID))
                    :source "(define result (+ 2 3))"})
          observed (sandbox/execute! owner
                     {:op "inspect" :generation 1 :pane "artifact"
                      :display {:cell "result" :mode "graph" :direction "both"}})]
      (check! (= 1 (:generation result)) "Frozen runtime generation mismatch")
      (check! (= "5" (:value observed)) "Frozen runtime value mismatch")
      (check! (= "ready" (:status observed)) "Frozen runtime inspection is pending")
      (when-not (= 3 @checks) (throw (ex-info "Zero or incomplete preflight assertions" {})))
      (println "FROZEN_RUNTIME_PREFLIGHT:3:PASS"))
    (finally (sandbox/close-owner! owner))))
(shutdown-agents)
'''
    java = Path(os.environ["JAVA_HOME"]) / "bin/java" if os.environ.get("JAVA_HOME") else Path(shutil.which("java") or "java")
    command = [str(java), "-Xmx1024m", "-cp", os.pathsep.join(str(stage / item) for item in entries),
               "clojure.main", "-e", code]
    result = subprocess.run(command, cwd=stage / "source", capture_output=True, text=True, timeout=120)
    if result.returncode != 0 or "FROZEN_RUNTIME_PREFLIGHT:3:PASS" not in result.stdout:
        raise RuntimeError("Frozen runtime failed preflight:\n" + result.stderr[-8000:] + result.stdout[-2000:])


def build(destination):
    with tempfile.TemporaryDirectory(prefix="lain-sandbox-bundle-") as temporary:
        stage = Path(temporary)
        source = stage / "source"
        jars = stage / "deps"
        source.mkdir()
        jars.mkdir()
        entries = ["source"]
        shadowed = []
        for root in classpath():
            if root.suffix == ".jar":
                name = digest(root) + ".jar"
                if not (jars / name).exists():
                    shutil.copyfile(root, jars / name)
                entries.append("deps/" + name)
                continue
            if not root.is_dir():
                continue
            candidates = []
            if root == REPO:
                candidates.extend(root / name for name in ROOT_FILES)
                for prefix in PREFIXES:
                    candidates.extend((root / prefix).rglob("*"))
            else:
                candidates = root.rglob("*")
            for item in sorted(candidates):
                if item.is_symlink() or not item.is_file():
                    continue
                relative = item.relative_to(root)
                if any(part.startswith(".") or part in {"node_modules", "__pycache__"} for part in relative.parts):
                    continue
                asset = relative.as_posix().startswith("public/lain-editor/") and item.suffix in {".html", ".css", ".js", ".map"}
                runtime_config = relative.as_posix() in {"deploy/session_sandbox/hosts",
                                                         "deploy/session_sandbox/nsswitch.conf"}
                if not asset and not runtime_config and item.suffix not in SOURCE_SUFFIXES:
                    continue
                target = source / relative
                if target.exists():
                    if digest(item) != digest(target):
                        shadowed.append(relative.as_posix())
                    continue  # Preserve actual classpath precedence.
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(item, target)
        if not (source / "public/lain-editor/compiled/app.js").is_file():
            raise RuntimeError("Build and verify the pinned editor frontend before packaging")
        verify_runtime(stage, entries)
        files = {item.relative_to(stage).as_posix(): digest(item)
                 for item in sorted(stage.rglob("*")) if item.is_file()}
        manifest = {"version": 1, "classpath": entries, "files": files,
                    "shadowedPaths": sorted(set(shadowed))}
        (stage / "manifest.json").write_text(json.dumps(manifest, sort_keys=True))
        destination = Path(destination)
        with tarfile.open(destination, "w:gz") as archive:
            for item in sorted(stage.rglob("*")):
                if item.is_file():
                    archive.add(item, arcname=item.relative_to(stage).as_posix(), recursive=False)
        print(json.dumps({"archive": str(destination), "sha256": digest(destination),
                          "files": len(files), "bytes": destination.stat().st_size}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("destination")
    build(parser.parse_args().destination)
