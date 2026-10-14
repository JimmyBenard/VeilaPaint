import importlib.util
import io
from pathlib import Path
import tarfile
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("migrate", Path(__file__).parents[1] / "migrate_debug.py")
migrate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(migrate)

class BackupValidationTest(unittest.TestCase):
    def archive(self, root, entries):
        path = Path(root) / "backup.tar"
        with tarfile.open(path, "w") as out:
            for name, data, special in entries:
                entry = tarfile.TarInfo(name)
                if special:
                    entry.type = tarfile.SYMTYPE
                    entry.linkname = "/etc/passwd"
                    out.addfile(entry)
                else:
                    entry.size = len(data)
                    out.addfile(entry, io.BytesIO(data))
        return path

    def test_real_content_is_hashed_and_counted(self):
        with tempfile.TemporaryDirectory() as root:
            path = self.archive(root, [("files/projects/example/state.a", b"abc", False)])
            result = migrate.validate_archive(path)
            self.assertEqual(3, result["files/projects/example/state.a"]["bytes"])
            self.assertEqual("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", result["files/projects/example/state.a"]["sha256"])

    def test_unsafe_paths_links_and_duplicates_are_rejected(self):
        for entries in [[("../outside", b"abc", False)], [("/absolute", b"a", False)],
                        [("files/link", b"", True)], [("files/a", b"a", False), ("files/a", b"b", False)]]:
            with tempfile.TemporaryDirectory() as root:
                with self.assertRaises(ValueError): migrate.validate_archive(self.archive(root, entries))

    def test_truncated_file_payload_is_rejected(self):
        with tempfile.TemporaryDirectory() as root:
            path = self.archive(root, [("files/a", b"a" * 20000, False)])
            path.write_bytes(path.read_bytes()[:1000])
            with self.assertRaises((ValueError, tarfile.TarError)):
                migrate.validate_archive(path)

    def test_empty_archive_cannot_authorize_replacement(self):
        with tempfile.TemporaryDirectory() as root:
            with self.assertRaises(ValueError): migrate.validate_archive(self.archive(root, []))

if __name__ == "__main__": unittest.main()
