#!/usr/bin/env python3
"""Build three Harbor task packages from the existing frozen GX10 contracts."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shlex
import shutil

HERE = Path(__file__).resolve().parent
EXPECTED = {'distinct': 14, 'order': 17, 'events': 42}
CLI_VERSION = '1.12.4.1618'
CLOJURE_VERSION = '1.12.4'
OWNER = 'gx10-harbor-tasks-v1'
EMPTY_CHECK = {
    'distinct': '(is (= [] (stable-distinct-by identity [])))',
    'order': '(is (= [] (dependency-order [] [])))',
    'events': '(is (= {} (project-events {} [])))',
}
RUNNER = '''(ns benchmark.harbor-test-runner (:require [clojure.test :as t]))
(defn -main [& names]
  (when (empty? names) (throw (ex-info "Test namespace required" {})))
  (let [targets (mapv symbol names)]
    (doseq [target targets] (require target))
    (let [result (apply t/run-tests targets)
          assertions (+ (:pass result) (:fail result) (:error result))]
      (System/exit (if (and (pos? assertions) (zero? (:fail result)) (zero? (:error result))) 0 1)))))
'''


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write(path, content):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content)


def save(path, value):
    write(path, json.dumps(value, indent=2) + '\n')


def relative_file(root, name):
    path = Path(name)
    if path.is_absolute() or '..' in path.parts:
        raise ValueError('Task paths must be relative: ' + name)
    return root / path


def runtime_files(prefix):
    paths = ['bin/clojure', 'lib/clojure/deps.edn', 'lib/clojure/example-deps.edn',
             'lib/clojure/tools.edn', 'lib/clojure/libexec/exec.jar',
             'lib/clojure/libexec/clojure-tools-' + CLI_VERSION + '.jar']
    for name in paths:
        if not (prefix / name).is_file():
            raise ValueError('Missing pinned Clojure CLI distribution file: ' + name)
    return paths


def dockerfile(java_image):
    return f'''FROM {java_image}
RUN apt-get update && apt-get install -y --no-install-recommends bash ca-certificates curl git procps python3 util-linux && rm -rf /var/lib/apt/lists/*
COPY clojure/lib/clojure/ /opt/clojure/lib/clojure/
COPY clojure/bin/clojure /usr/local/bin/clojure
RUN chmod 755 /usr/local/bin/clojure && useradd --create-home agent && useradd --create-home grader
WORKDIR /app
COPY project/deps.edn /app/deps.edn
RUN clojure -P && mkdir -p /opt/clojure/runtime && find /opt/m2/org/clojure -name '*.jar' -exec cp '{{}}' /opt/clojure/runtime/ \\; && sha256sum /opt/clojure/runtime/*.jar > /opt/clojure/runtime.sha256 && rm -rf /app/.cpcache
COPY project/ /app/
RUN git init /app && git -C /app -c user.name=benchmark -c user.email=benchmark@localhost add . && git -C /app -c user.name=benchmark -c user.email=benchmark@localhost commit -m 'Frozen task scaffold' && chown -R agent:agent /app
'''


def task_config():
    return '''version = "1.0"
artifacts = [{source = "/app", destination = "project", exclude = [".git", ".cpcache"]}]
[metadata]
cohort = "gx10-harbor-tasks-v1"
[agent]
user = "agent"
# No overall agent deadline; command/evaluator limits remain finite.
[verifier]
timeout_sec = 300.0
environment_mode = "separate"
user = "root"
[environment]
build_timeout_sec = 900.0
cpus = 2
memory_mb = 2048
storage_mb = 4096
'''


def build(source, output, prefix, java_image):
    if not re.fullmatch(r'eclipse-temurin@sha256:[a-f0-9]{64}', java_image):
        raise ValueError('A digest-pinned eclipse-temurin Java image is required')
    tasks = json.loads((source / 'tasks.json').read_text())
    if len(tasks) != 3 or {task['id'] for task in tasks} != set(EXPECTED):
        raise ValueError('Expected exactly the original Distinct, Ordering and Events tasks')
    evaluators = json.loads((source / 'evaluator-manifest.json').read_text())
    protocol = json.loads((source / 'config.json').read_text())
    distribution = runtime_files(prefix)
    for task in tasks:
        evaluator = relative_file(source / 'fixtures', task['acceptance'])
        if sha(evaluator) != evaluators[task['acceptance']]:
            raise ValueError('Original evaluator hash drift: ' + task['id'])
        for key in ['path', 'test_path']:
            relative_file(Path('/app'), task[key])
        if not relative_file(source / 'validation-fixtures', task['path']).is_file():
            raise ValueError('Missing existing known-correct fixture: ' + task['id'])
    output.mkdir(parents=True, exist_ok=False)
    records = []
    for task in tasks:
        directory = output / task['id']
        project = directory / 'environment/project'
        write(project / 'deps.edn', '{:paths ["." "test"] :mvn/local-repo "/opt/m2" '
              ':deps {org.clojure/clojure {:mvn/version "' + CLOJURE_VERSION + '"}} '
              ':aliases {:test {:main-opts ["-m" "benchmark.harbor-test-runner"]}}}\n')
        write(project / 'benchmark/harbor_test_runner.clj', RUNNER)
        write(project / task['path'], '(ns ' + task['namespace'] + ')\n\n(defn ' + task['function'] +
              ' [' + task['args'] + ']\n  (throw (ex-info "Implement this function" {})))\n')
        test_header = '(ns ' + task['test_namespace'] + '\n (:require [clojure.test :refer [deftest is]]\n           [' + task['namespace'] + ' :refer [' + task['function'] + ']]))\n'
        write(project / task['test_path'], test_header)
        for name in distribution:
            target = directory / 'environment/clojure' / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(prefix / name, target)
        launcher = directory / 'environment/clojure/bin/clojure'
        script = launcher.read_text()
        original = str(prefix / 'lib/clojure')
        if original not in script:
            raise ValueError('Unexpected installed Clojure CLI prefix')
        launcher.write_text(script.replace(original, '/opt/clojure/lib/clojure'))
        write(directory / 'environment/Dockerfile', dockerfile(java_image))
        write(directory / 'environment/docker-compose.yaml', 'services:\n  main:\n    network_mode: host\n    networks: !reset []\n    labels:\n      gx10.benchmark.owner: ' + OWNER + '\n')
        write(directory / 'task.toml', task_config())
        prompt = source / 'prompts' / (task['id'] + '.txt')
        workflow = source / 'prompts/agent-workflow.txt'
        instruction = prompt.read_text().replace(' You have ten minutes.', '')
        instruction += '\n' + workflow.read_text()
        instruction += '\n\nHarbor project root: /app. Modify only ' + task['path'] + ' and ' + task['test_path'] + '. Each shell command must use a finite timeout of at most 120 seconds. The independent evaluator is withheld until agent completion. No overall coding deadline.\n'
        write(directory / 'instruction.md', instruction)
        solution = directory / 'solution'
        solution.mkdir()
        reference = source / 'validation-fixtures' / task['path']
        shutil.copyfile(reference, solution / 'implementation.clj')
        write(solution / 'unit_test.clj', test_header + '\n(deftest empty-input\n  ' + EMPTY_CHECK[task['id']] + ')\n')
        write(solution / 'solve.sh', '#!/usr/bin/env bash\nset -euo pipefail\ncp /solution/implementation.clj ' + shlex.quote('/app/' + task['path']) + '\ncp /solution/unit_test.clj ' + shlex.quote('/app/' + task['test_path']) + '\n')
        tests = directory / 'tests'
        tests.mkdir()
        shutil.copyfile(source / 'fixtures' / task['acceptance'], tests / task['acceptance'])
        shutil.copyfile(HERE / 'verifier.py', tests / 'verify.py')
        write(tests / 'test.sh', '#!/usr/bin/env bash\nset -euo pipefail\nchown -R root:grader /tests\nchmod 750 /tests\nfind /tests -type f -exec chmod 640 {} +\nexec python3 /tests/verify.py\n')
        metadata = {'task': task, 'expected_assertions': EXPECTED[task['id']],
            'evaluator_sha256': evaluators[task['acceptance']],
            'source_commit': protocol['pi_phase']['source_commit'], 'original_prompt_sha256': sha(prompt),
            'original_workflow_sha256': sha(workflow), 'reference_sha256': sha(reference),
            'runtime': {'java_image': java_image, 'clojure_cli': CLI_VERSION, 'clojure': CLOJURE_VERSION},
            'legacy_deadline_phrase_removed': ' You have ten minutes.' in prompt.read_text(),
            'pass_policy': 'independent-correctness-v1; workflow and execution recorded separately'}
        save(tests / 'metadata.json', metadata)
        frozen = {path.relative_to(project).as_posix(): sha(path) for path in project.rglob('*')
                  if path.is_file() and path.relative_to(project).as_posix() not in [task['path'], task['test_path']]}
        save(tests / 'scaffold.json', frozen)
        records.append(metadata)
    hashes = {path.relative_to(output).as_posix(): sha(path) for path in output.rglob('*') if path.is_file()}
    manifest = {'schema_version': 1, 'cohort': OWNER, 'tasks': records, 'package_hashes': hashes,
        'source_task_manifest_sha256': sha(source / 'tasks.json'),
        'source_evaluator_manifest_sha256': sha(source / 'evaluator-manifest.json'),
        'clojure_distribution_hashes': {name: sha(prefix / name) for name in distribution}}
    save(output / 'migration.json', manifest)
    return manifest


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, default=HERE.parent / 'gx10-context')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--clojure-prefix', type=Path, required=True)
    parser.add_argument('--java-image', required=True)
    args = parser.parse_args()
    result = build(args.source.resolve(), args.output.resolve(), args.clojure_prefix.resolve(), args.java_image)
    print(json.dumps({'tasks': [task['task']['id'] for task in result['tasks']], 'output': str(args.output)}))
