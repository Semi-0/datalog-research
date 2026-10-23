"""Execute unchanged independent evaluators against known correct implementations."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from outcomes import EXPECTED_ASSERTIONS, test_counts, tests_passed

HERE = Path(__file__).resolve().parent


class EvaluatorTests(unittest.TestCase):
    def test_correct_fixtures_pass_exact_unchanged_evaluators(self):
        manifest = json.loads((HERE / 'evaluator-manifest.json').read_text())
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            shutil.copytree(HERE / 'validation-fixtures/benchmark', root / 'benchmark')
            (root / 'deps.edn').write_text('{:paths ["."]}\n')
            for task, assertions in EXPECTED_ASSERTIONS.items():
                with self.subTest(task=task):
                    evaluator = HERE / 'fixtures' / (task + '-acceptance.clj')
                    self.assertEqual(manifest[evaluator.name], hashlib.sha256(evaluator.read_bytes()).hexdigest())
                    result = subprocess.run(['clojure', '-M', str(evaluator)], cwd=root, text=True,
                                            capture_output=True, timeout=90)
                    self.assertTrue(tests_passed(result.returncode, test_counts(result.stdout), assertions),
                                    result.stdout + result.stderr)

    def test_real_syntax_incorrect_and_zero_assertion_outputs(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'deps.edn').write_text('{:paths ["."]}\n')
            for label, source in [('syntax', '(let [x 1) x)'),
                ('wrong', "(require '[clojure.test :refer :all]) (deftest wrong (is (= 1 2))) (run-tests 'user)"),
                ('zero', "(require '[clojure.test :refer :all]) (run-tests 'user)")]:
                with self.subTest(label=label):
                    file = root / (label + '.clj')
                    file.write_text(source)
                    result = subprocess.run(['clojure', '-M', str(file)], cwd=root, text=True, capture_output=True, timeout=90)
                    self.assertFalse(tests_passed(result.returncode, test_counts(result.stdout)))


if __name__ == '__main__':
    unittest.main()
