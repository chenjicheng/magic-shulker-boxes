import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from zipfile import ZipFile

from release import metadata, package


class ReleaseTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.properties = self.root / "gradle.properties"
        self.properties.write_text(
            "mod_version=0.1.0-alpha\nminecraft_version=1.21.11\n"
            "archives_base_name=magic-shulker-boxes-fabric\n", encoding="utf-8"
        )
        self.info = {
            "id": "magic_shulker_boxes", "version": "0.1.0-alpha+mc1.21.11",
            "name": "Magic Shulker Boxes", "license": "MIT",
            "environment": "*", "depends": {"minecraft": "1.21.11"},
        }
        self.libs = self.root / "build/libs"
        self.libs.mkdir(parents=True)
        self.notes = self.root / "docs/releases/0.1.0-alpha.md"
        self.notes.parent.mkdir(parents=True)
        self.notes.write_text("# First alpha / 首个预览版\n", encoding="utf-8")

    def jars(self, extra=None):
        stem = "magic-shulker-boxes-fabric-0.1.0-alpha+mc1.21.11"
        with ZipFile(self.libs / f"{stem}.jar", "w") as jar:
            jar.writestr("fabric.mod.json", json.dumps(self.info))
            jar.writestr("LICENSE_magic-shulker-boxes-fabric", "MIT")
            jar.writestr("dev/magicshulkerboxes/MagicShulkerBoxes.class", b"code")
            if extra:
                jar.writestr(extra, b"unwanted")
        with ZipFile(self.libs / f"{stem}-sources.jar", "w") as jar:
            jar.writestr("dev/magicshulkerboxes/MagicShulkerBoxes.java", "class Source {}")

    def test_alpha_and_stable_release_classification(self):
        self.assertEqual(metadata(self.root, "v0.1.0-alpha")["prerelease"], "true")
        self.properties.write_text(self.properties.read_text().replace("0.1.0-alpha", "0.1.0"))
        self.assertEqual(metadata(self.root, "v0.1.0")["prerelease"], "false")

    def test_mismatched_or_unsafe_tag_fails_before_packaging(self):
        for tag in ["v1.2.0", "0.1.0-alpha", "v0.1.0-alpha\nother=value"]:
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                metadata(self.root, tag)

    def test_only_current_artifacts_are_packaged_with_checksums(self):
        self.jars()
        (self.libs / "old-version.jar").write_bytes(b"stale")
        out = self.root / "dist"
        package(self.root, "v0.1.0-alpha", out)
        self.assertEqual(len(list(out.iterdir())), 4)
        self.assertFalse((out / "old-version.jar").exists())
        for line in (out / "SHA256SUMS").read_text().splitlines():
            digest, filename = line.split("  ")
            self.assertEqual(hashlib.sha256((out / filename).read_bytes()).hexdigest(), digest)

    def test_metadata_must_match_release(self):
        for key, wrong in [("version", "1.2.0"), ("id", "other_mod"), ("license", "unknown")]:
            with self.subTest(key=key):
                original = self.info[key]
                self.info[key] = wrong
                self.jars()
                with self.assertRaises(ValueError):
                    package(self.root, "v0.1.0-alpha", self.root / "dist")
                self.info[key] = original

    def test_test_code_or_bundled_optional_mods_are_rejected(self):
        for extra in ["dev/magicshulkerboxes/PickupGameTests.class", "carpet/CarpetServer.class", "META-INF/jars/modmenu.jar"]:
            with self.subTest(extra=extra):
                self.jars(extra)
                with self.assertRaises(ValueError):
                    package(self.root, "v0.1.0-alpha", self.root / "dist")

    def test_missing_sources_or_notes_prevents_release(self):
        self.jars()
        self.notes.unlink()
        with self.assertRaises(FileNotFoundError):
            package(self.root, "v0.1.0-alpha", self.root / "dist")

    def test_external_refill_libraries_must_not_be_bundled(self):
        extras = ["org/anti_ad/mc/ipnext/Init.class", "org/anti_ad/mc/common/Vanilla.class",
                  "kotlin/jvm/internal/Intrinsics.class", "kotlinx/coroutines/Job.class"]
        for index, extra in enumerate(extras):
            with self.subTest(extra=extra):
                self.jars(extra)
                with self.assertRaises(ValueError):
                    package(self.root, "v0.1.0-alpha", self.root / f"dist-{index}")


if __name__ == "__main__":
    unittest.main()
