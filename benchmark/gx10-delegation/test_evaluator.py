import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

from contracts import LANES, tests_passed
from reference import SOURCES

HERE = Path(__file__).resolve().parent
CLOJURE = os.environ.get('GX10_CLOJURE')


@unittest.skipUnless(CLOJURE, 'Set GX10_CLOJURE to the real installed CLI; no implicit dependency downloads')
class EvaluatorTests(unittest.TestCase):
    def test_correct_fixture_passes_and_wrong_or_syntax_error_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / 'deps.edn').write_text('{:paths ["."] :deps {org.clojure/clojure {:mvn/version "1.12.4"}}}')
            for lane, source in SOURCES.items():
                p = root / LANES[lane]['source']; p.parent.mkdir(exist_ok=True); p.write_text(source)
            def evaluate():
                result = subprocess.run([CLOJURE, '-M', str(HERE / 'acceptance.clj')], cwd=root,
                                        capture_output=True, text=True, timeout=120)
                return result.returncode, result.stdout + result.stderr
            code, text = evaluate()
            self.assertTrue(tests_passed(code, text), text)
            print('Independent delegation evaluator known-good fixture:', text.strip())
            target = root / LANES['normalize']['source']
            target.write_text('(ns delegation.normalize) (defn normalize-events [events] [])')
            code, text = evaluate()
            self.assertFalse(tests_passed(code, text))
            self.assertNotEqual(0, code)
            target.write_text('(ns delegation.normalize))')
            code, text = evaluate()
            self.assertFalse(tests_passed(code, text))
            self.assertNotEqual(0, code)


if __name__ == '__main__':
    unittest.main()
