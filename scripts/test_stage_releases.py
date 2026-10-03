import hashlib
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from zipfile import ZipFile, ZipInfo

import stage_releases


def jar_bytes(mod_id, version, environment, minecraft="1.21.11"):
    stream = io.BytesIO()
    with ZipFile(stream, "w") as jar:
        jar.writestr(ZipInfo("fabric.mod.json", (2026, 1, 1, 0, 0, 0)), json.dumps({
            "id": mod_id, "version": version, "environment": environment,
            "depends": {"minecraft": minecraft},
        }))
        jar.writestr(ZipInfo("mod.class", (2026, 1, 1, 0, 0, 0)), b"fixture code")
    return stream.getvalue()


class ReleaseStagingTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.config = {"cache_dir": str(self.root / "cache"), "minecraft_version": "1.21.11", "modules": []}
        self.routes = {}
        self.requests = []
        self.msb = self.module("magic_shulker_boxes", "magic-shulker-boxes", "magic-shulker-boxes-fabric-", "*", "+mc1.21.11")
        self.cco = self.module("chest_count_overlay", "chest-count-overlay", "chest_count_overlay-", "client")
        self.release(self.msb, "0.6.1", 100)
        self.release(self.cco, "1.0.2", 200)

    def module(self, mod_id, repository, prefix, environment, suffix=""):
        installed = self.root / "installed" / mod_id
        installed.mkdir(parents=True)
        module = {
            "id": mod_id, "repository": "chenjicheng/" + repository,
            "archive_prefix": prefix, "version_suffix": suffix, "environment": environment,
            "targets": [{"name": mod_id + "-target", "installed_dir": str(installed)}],
        }
        self.config["modules"].append(module)
        return module

    def release(self, module, version, release_id, legacy_checksum=False, manifest=None):
        tag = "v" + version
        filename = module["archive_prefix"] + version + module["version_suffix"] + ".jar"
        data = manifest or jar_bytes(module["id"], version + module["version_suffix"], module["environment"],
                                     "=1.21.11" if module is self.cco else "1.21.11")
        checksum = hashlib.sha256(data).hexdigest()
        base = "https://github.com/" + module["repository"] + "/releases/download/" + tag + "/"
        checksum_name = filename[:-4] + ".sha256" if legacy_checksum else "SHA256SUMS"
        assets = []
        checksum_path = "release-artifacts/" + filename if legacy_checksum else filename
        for name, contents in [(filename, data), (checksum_name, (checksum + "  " + checksum_path + "\n").encode())]:
            url = base + name
            assets.append({"name": name, "size": len(contents), "browser_download_url": url,
                           "digest": "sha256:" + hashlib.sha256(contents).hexdigest()})
            self.routes[url] = contents
        release = {"id": release_id, "tag_name": tag, "draft": False, "prerelease": False, "assets": assets}
        self.routes[self.api(module)] = json.dumps(release).encode()
        return release, filename, data

    def api(self, module):
        return "https://api.github.com/repos/" + module["repository"] + "/releases/latest"

    def fetch(self, url, limit):
        self.requests.append(url)
        data = self.routes[url]
        if isinstance(data, Exception):
            raise data
        if len(data) > limit:
            raise ValueError("Response too large")
        return data

    def results(self):
        return stage_releases.check_updates(self.config, self.fetch)

    def staged(self, module, tag):
        return self.root / "cache/targets" / module["targets"][0]["name"] / module["id"] / tag

    def install(self, module, version):
        filename = module["archive_prefix"] + version + module["version_suffix"] + ".jar"
        path = Path(module["targets"][0]["installed_dir"]) / filename
        data = jar_bytes(module["id"], version + module["version_suffix"], module["environment"])
        path.write_bytes(data)
        return path, data

    def change_release(self, module, change):
        value = json.loads(self.routes[self.api(module)])
        change(value)
        self.routes[self.api(module)] = json.dumps(value).encode()

    def test_both_mods_stage_backups_and_leave_live_files_unchanged(self):
        originals = [self.install(self.msb, "0.6.0"), self.install(self.cco, "1.0.1")]
        result = self.results()
        self.assertEqual(["staged", "staged"], [item["status"] for item in result])
        for module, tag, original in [(self.msb, "v0.6.1", originals[0]), (self.cco, "v1.0.2", originals[1])]:
            directory = self.staged(module, tag)
            record = json.loads((directory / "manifest.json").read_text())
            self.assertEqual(module["environment"], record["environment"])
            self.assertEqual(original[1], (directory / "backups" / original[0].name).read_bytes())
            self.assertEqual(original[1], original[0].read_bytes())
            self.assertEqual([original[0]], list(original[0].parent.iterdir()))
            self.assertEqual(record["sha256"], hashlib.sha256((directory / record["asset"]).read_bytes()).hexdigest())

    def test_repeated_checks_do_not_redownload_or_replace_verified_bundles(self):
        self.results()
        self.requests.clear()
        path = self.staged(self.msb, "v0.6.1") / "manifest.json"
        before = (path.read_bytes(), path.stat().st_mtime_ns)
        result = self.results()
        self.assertEqual(["unchanged", "unchanged"], [item["status"] for item in result])
        self.assertEqual([self.api(self.msb), self.api(self.cco)], self.requests)
        self.assertEqual(before, (path.read_bytes(), path.stat().st_mtime_ns))

    def test_legacy_cco_checksum_and_current_manifest_are_supported(self):
        self.release(self.cco, "1.0.1", 201, legacy_checksum=True)
        result = self.results()
        self.assertEqual("staged", result[1]["status"])
        manifest = json.loads((self.staged(self.cco, "v1.0.1") / "manifest.json").read_text())
        self.assertEqual("chest_count_overlay-1.0.1.sha256", manifest["checksum_asset"])

    def test_bad_checksum_cannot_write_a_bundle_and_other_mod_still_updates(self):
        release = json.loads(self.routes[self.api(self.msb)])
        jar_url = release["assets"][0]["browser_download_url"]
        self.routes[jar_url] += b"changed after publication"
        result = self.results()
        self.assertEqual(["error", "staged"], [item["status"] for item in result])
        self.assertFalse(self.staged(self.msb, "v0.6.1").exists())
        self.assertFalse((self.root / "cache/latest/magic_shulker_boxes.json").exists())

    def test_wrong_id_game_version_environment_or_mod_version_is_rejected(self):
        for mod_id, version, environment, minecraft in [
            ("other_mod", "0.6.1+mc1.21.11", "*", "1.21.11"),
            ("magic_shulker_boxes", "0.6.1+mc1.21.11", "client", "1.21.11"),
            ("magic_shulker_boxes", "0.6.1+mc1.21.11", "*", "1.21.1"),
            ("magic_shulker_boxes", "0.6.0+mc1.21.11", "*", "1.21.11"),
        ]:
            with self.subTest(mod_id=mod_id, version=version, environment=environment, minecraft=minecraft):
                self.release(self.msb, "0.6.1", 100, manifest=jar_bytes(mod_id, version, environment, minecraft))
                self.assertEqual("error", self.results()[0]["status"])
                self.assertFalse(self.staged(self.msb, "v0.6.1").exists())

    def test_draft_prerelease_and_unsafe_tag_never_create_downloads(self):
        for key, value in [("draft", True), ("prerelease", True), ("tag_name", "../../escape")]:
            with self.subTest(key=key):
                self.release(self.msb, "0.6.1", 100)
                self.change_release(self.msb, lambda release: release.update({key: value}))
                self.assertEqual("error", self.results()[0]["status"])
                self.assertFalse(self.staged(self.msb, "v0.6.1").exists())

    def test_missing_checksum_duplicate_asset_and_foreign_url_are_rejected(self):
        changes = [
            lambda release: release.update(assets=release["assets"][:1]),
            lambda release: release["assets"].append(release["assets"][0].copy()),
            lambda release: release["assets"][0].update(browser_download_url="https://example.com/mod.jar"),
        ]
        for change in changes:
            with self.subTest(change=change):
                self.release(self.msb, "0.6.1", 100)
                self.change_release(self.msb, change)
                self.assertEqual("error", self.results()[0]["status"])
                self.assertFalse(self.staged(self.msb, "v0.6.1").exists())

    def test_no_downgrade_below_installed_or_staged_version(self):
        self.install(self.msb, "0.7.0")
        self.assertEqual("skipped", self.results()[0]["status"])
        self.assertFalse(self.staged(self.msb, "v0.6.1").exists())
        self.results()
        self.release(self.cco, "1.0.1", 201)
        self.assertEqual("skipped", self.results()[1]["status"])
        self.assertTrue(self.staged(self.cco, "v1.0.2").exists())

    def test_network_failure_retries_next_check_without_losing_other_mod(self):
        saved = self.routes[self.api(self.msb)]
        self.routes[self.api(self.msb)] = OSError("temporary failure")
        self.assertEqual(["error", "staged"], [item["status"] for item in self.results()])
        self.routes[self.api(self.msb)] = saved
        self.assertEqual(["staged", "unchanged"], [item["status"] for item in self.results()])

    def test_changed_published_jar_and_corrupt_cached_copy_are_not_silently_overwritten(self):
        self.results()
        original = (self.staged(self.msb, "v0.6.1") / "manifest.json").read_bytes()
        self.release(self.msb, "0.6.1", 100,
                     manifest=jar_bytes("magic_shulker_boxes", "0.6.1+mc1.21.11", "*", "=1.21.11"))
        self.assertEqual("error", self.results()[0]["status"])
        self.assertEqual(original, (self.staged(self.msb, "v0.6.1") / "manifest.json").read_bytes())
        self.release(self.msb, "0.6.1", 100)
        cached = self.root / "cache/bundles/magic_shulker_boxes/v0.6.1/magic-shulker-boxes-fabric-0.6.1+mc1.21.11.jar"
        cached.write_bytes(b"corrupt")
        self.assertEqual("error", self.results()[0]["status"])

    def test_new_target_uses_cached_jar_and_each_target_has_its_own_backup(self):
        self.results()
        second = self.root / "another-server/mods"
        second.mkdir(parents=True)
        old = second / "magic-shulker-boxes-fabric-0.6.0+mc1.21.11.jar"
        old.write_bytes(jar_bytes("magic_shulker_boxes", "0.6.0+mc1.21.11", "*"))
        self.msb["targets"].append({"name": "another-server", "installed_dir": str(second)})
        self.requests.clear()
        self.assertEqual("staged", self.results()[0]["status"])
        destination = self.root / "cache/targets/another-server/magic_shulker_boxes/v0.6.1"
        self.assertEqual(old.read_bytes(), (destination / "backups" / old.name).read_bytes())
        self.assertEqual([self.api(self.msb), self.api(self.cco)], self.requests)

    def test_partial_target_failure_retries_without_replacing_the_first_backup(self):
        original_path, original = self.install(self.msb, "0.6.0")
        second = self.root / "another-server/mods"
        second.mkdir(parents=True)
        self.msb["targets"].append({"name": "another-server", "installed_dir": str(second)})
        publish = stage_releases.publish_directory

        def fail_second(root, destination, files):
            if "another-server" in destination.parts:
                raise OSError("disk temporarily unavailable")
            return publish(root, destination, files)

        with patch.object(stage_releases, "publish_directory", side_effect=fail_second):
            self.assertEqual(["error", "staged"], [item["status"] for item in self.results()])
        first = self.staged(self.msb, "v0.6.1") / "backups" / original_path.name
        before = (first.read_bytes(), first.stat().st_mtime_ns)
        self.assertFalse((self.root / "cache/latest/magic_shulker_boxes.json").exists())
        self.assertEqual("staged", self.results()[0]["status"])
        self.assertEqual(before, (first.read_bytes(), first.stat().st_mtime_ns))
        self.assertEqual(original, original_path.read_bytes())

    def test_malformed_configuration_and_cached_manifest_report_expected_failures(self):
        original_root = self.config["cache_dir"]
        self.config["cache_dir"] = None
        with self.assertRaises(stage_releases.StagingError):
            self.results()
        self.config["cache_dir"] = original_root
        self.results()
        manifest = self.root / "cache/bundles/magic_shulker_boxes/v0.6.1/manifest.json"
        value = json.loads(manifest.read_text())
        value.pop("files")
        manifest.write_text(json.dumps(value))
        self.assertEqual("error", self.results()[0]["status"])

    def test_target_manifest_cannot_omit_checksum_or_backup_integrity(self):
        self.install(self.msb, "0.6.0")
        self.results()
        manifest = self.staged(self.msb, "v0.6.1") / "manifest.json"
        value = json.loads(manifest.read_text())
        value["files"].pop("SHA256SUMS")
        manifest.write_text(json.dumps(value))
        self.assertEqual("error", self.results()[0]["status"])

    @unittest.skipUnless(os.name == "posix", "Real symlink escape checks run on the Linux deployment host")
    def test_symlink_staging_paths_never_escape_the_cache(self):
        outside = self.root / "outside"
        outside.mkdir()
        cache = self.root / "cache"
        cache.mkdir()
        (cache / "targets").symlink_to(outside, target_is_directory=True)
        self.assertEqual("error", self.results()[0]["status"])
        self.assertEqual([], list(outside.iterdir()))

    @unittest.skipUnless(os.name == "posix", "Real symlink escape checks run on the Linux deployment host")
    def test_cached_manifest_symlink_is_rejected_before_reading_external_data(self):
        self.results()
        manifest = self.root / "cache/bundles/magic_shulker_boxes/v0.6.1/manifest.json"
        outside = self.root / "outside.json"
        outside.write_bytes(manifest.read_bytes())
        manifest.unlink()
        manifest.symlink_to(outside)
        self.assertEqual("error", self.results()[0]["status"])

    def test_only_github_https_origins_can_be_used_by_redirects(self):
        for url in ["http://github.com/file", "https://github.com.evil.example/file",
                    "https://user:secret@github.com/file", "https://example.com/file"]:
            with self.subTest(url=url), self.assertRaises(stage_releases.StagingError):
                stage_releases.trusted_url(url)
        stage_releases.trusted_url("https://release-assets.githubusercontent.com/file")

    def test_kernel_lock_releases_after_failure_and_rejects_overlapping_checks(self):
        root = self.root / "cache"
        with self.assertRaises(RuntimeError):
            with stage_releases.exclusive_check(root):
                with self.assertRaises(OSError):
                    with stage_releases.exclusive_check(root):
                        self.fail("Overlapping check acquired the same lock")
                raise RuntimeError("interrupted check")
        with stage_releases.exclusive_check(root):
            self.assertTrue((root / ".check.lock").exists())

    def test_checksum_paths_cannot_address_arbitrary_directories(self):
        expected = "a" * 64
        for name in ["../mod.jar", "/tmp/mod.jar", "release-artifacts/../mod.jar", "other/mod.jar"]:
            with self.subTest(name=name), self.assertRaises(stage_releases.StagingError):
                stage_releases.checksum_for((expected + "  " + name + "\n").encode(), "mod.jar")

    def test_matching_size_and_api_digest_do_not_bypass_the_checksum_receipt(self):
        release = json.loads(self.routes[self.api(self.msb)])
        receipt = release["assets"][1]
        data = ("0" * 64 + "  " + release["assets"][0]["name"] + "\n").encode()
        self.routes[receipt["browser_download_url"]] = data
        receipt["digest"] = "sha256:" + hashlib.sha256(data).hexdigest()
        self.routes[self.api(self.msb)] = json.dumps(release).encode()
        result = self.results()
        self.assertEqual("error", result[0]["status"])
        self.assertIn("Published SHA256 checksum mismatch", result[0]["error"])
        self.assertFalse(self.staged(self.msb, "v0.6.1").exists())

    def test_unchanged_receipt_cannot_bypass_an_incorrect_api_digest(self):
        self.change_release(self.msb, lambda release: release["assets"][0].update(digest="sha256:" + "0" * 64))
        result = self.results()
        self.assertEqual("error", result[0]["status"])
        self.assertIn("GitHub SHA256 digest mismatch", result[0]["error"])

    def test_pointer_write_failure_keeps_previous_pointer_and_removes_its_temporary_file(self):
        directory = self.root / "cache/latest"
        directory.mkdir(parents=True)
        pointer = directory / "magic_shulker_boxes.json"
        previous = b'{"tag":"v0.6.0"}\n'
        pointer.write_bytes(previous)
        with patch.object(stage_releases.os, "fsync", side_effect=OSError("disk unavailable")):
            with self.assertRaises(OSError):
                stage_releases.replace_json(pointer, {"tag": "v0.6.1"})
        self.assertEqual(previous, pointer.read_bytes())
        self.assertEqual([pointer], list(directory.iterdir()))


if __name__ == "__main__":
    unittest.main()
