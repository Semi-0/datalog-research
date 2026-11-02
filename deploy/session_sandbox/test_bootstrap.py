"""Installer unit checks, separate from live OS containment acceptance."""
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import tarfile
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("sandbox_bootstrap", Path(__file__).with_name("bootstrap.py"))
bootstrap = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(bootstrap)


class BootstrapTests(unittest.TestCase):
    def setUp(self):
        self.directory = Path(tempfile.mkdtemp(prefix="lain-installer-test-"))
        self.addCleanup(self.cleanup)
        self.patch = patch.object(bootstrap.policy, "ROOT", self.directory / "installed")
        self.patch.start()
        self.addCleanup(self.patch.stop)

    def cleanup(self):
        for directory, _, _ in os.walk(self.directory):
            Path(directory).chmod(0o700)
        shutil.rmtree(self.directory)

    def archive(self, members):
        path = self.directory / "artifact.tar.gz"
        with tarfile.open(path, "w:gz") as archive:
            for name, data in members.items():
                entry = tarfile.TarInfo(name)
                entry.size = len(data)
                archive.addfile(entry, io.BytesIO(data))
        return path, bootstrap.digest(path)

    def valid_members(self):
        members = {"source/deploy/session_sandbox/" + name: b"# trusted test fixture\n"
                   for name in ("manager.py", "inside.py", "runtime.clj", "network.py", "hosts", "nsswitch.conf")}
        members["source/deploy/session_sandbox/policy.py"] = Path(bootstrap.policy.__file__).read_bytes()
        manifest = {"version": 1, "classpath": ["source"],
                    "files": {name: hashlib.sha256(data).hexdigest() for name, data in members.items()}}
        members["manifest.json"] = json.dumps(manifest).encode()
        return members

    def test_verified_release_is_frozen_and_repeatable(self):
        path, digest = self.archive(self.valid_members())
        release = bootstrap.extract_verified(path, digest)
        self.assertEqual(release.name, digest)
        self.assertEqual(release.stat().st_mode & 0o222, 0)
        self.assertEqual(bootstrap.extract_verified(path, digest), release)
        self.assertEqual(bootstrap.verify_manifest(release)["version"], 1)

    def test_archive_digest_mismatch_writes_no_release(self):
        path, _ = self.archive(self.valid_members())
        with self.assertRaisesRegex(ValueError, "SHA256 mismatch"):
            bootstrap.extract_verified(path, "0" * 64)
        self.assertFalse((bootstrap.policy.ROOT / "releases").exists())

    def test_manifest_digest_and_unlisted_files_fail(self):
        members = self.valid_members()
        members["source/deploy/session_sandbox/manager.py"] = b"changed"
        path, digest = self.archive(members)
        with self.assertRaisesRegex(ValueError, "digest mismatch"):
            bootstrap.extract_verified(path, digest)
        members = self.valid_members()
        members["source/unlisted.py"] = b"unlisted"
        path, digest = self.archive(members)
        with self.assertRaisesRegex(ValueError, "file set"):
            bootstrap.extract_verified(path, digest)

    def test_traversal_and_links_never_extract(self):
        for name in ("../escape", "/tmp/escape", "source/../escape", "source//escape", "source\\escape"):
            with self.subTest(name=name), self.assertRaises(ValueError):
                bootstrap.safe_name(name)
        path = self.directory / "link.tar.gz"
        with tarfile.open(path, "w:gz") as archive:
            entry = tarfile.TarInfo("source/link")
            entry.type, entry.linkname = tarfile.SYMTYPE, "/etc/passwd"
            archive.addfile(entry)
        with self.assertRaisesRegex(ValueError, "Unsafe"):
            bootstrap.extract_verified(path, bootstrap.digest(path))

    def test_active_session_prevents_broker_replacement(self):
        directory = bootstrap.policy.ROOT / "instances" / ("a" * 32)
        directory.mkdir(parents=True)
        (directory / "record.json").write_text(json.dumps({"state": "running"}))
        with self.assertRaisesRegex(RuntimeError, "sandbox is active"):
            bootstrap.install_broker(self.directory / "unused-release")


if __name__ == "__main__":
    unittest.main()
