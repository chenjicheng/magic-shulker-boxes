import hashlib
import io
import json
from email import policy
from email.parser import BytesParser
from pathlib import Path
import tempfile
import unittest
from unittest.mock import MagicMock, Mock
from urllib.error import HTTPError, URLError

from modrinth import ModrinthClient, publish, release_payload


class ModrinthTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.properties = self.root / "gradle.properties"
        self.properties.write_text(
            "mod_version=0.6.1\nminecraft_version=1.21.11\n"
            "archives_base_name=magic-shulker-boxes-fabric\n", encoding="utf-8"
        )
        self.dist = self.root / "dist"
        self.dist.mkdir()
        self.jar = self.dist / "magic-shulker-boxes-fabric-0.6.1+mc1.21.11.jar"
        self.jar.write_bytes(b"tested release jar")
        self.sha256 = hashlib.sha256(self.jar.read_bytes()).hexdigest()
        (self.dist / "SHA256SUMS").write_text(f"{self.sha256}  {self.jar.name}\n")
        (self.dist / "release-notes.md").write_text("# Fix / 修复\n", encoding="utf-8")
        self.client = Mock()
        self.client.get.return_value = []

    def payload(self):
        return release_payload(self.root, "v0.6.1", self.dist, "Project1")

    def remote_version(self):
        payload, jar = self.payload()
        return {**payload, "id": "Version1", "files": [{
            "filename": jar.name, "primary": True,
            "hashes": {"sha512": hashlib.sha512(jar.read_bytes()).hexdigest()},
        }]}

    def test_payload_uses_validated_metadata_notes_and_dependency_ids(self):
        payload, jar = self.payload()
        self.assertEqual(jar, self.jar)
        self.assertEqual(payload["version_number"], "0.6.1+mc1.21.11")
        self.assertEqual(payload["game_versions"], ["1.21.11"])
        self.assertEqual(payload["loaders"], ["fabric"])
        self.assertEqual(payload["environment"], "server_only_client_optional")
        self.assertEqual(payload["changelog"], "# Fix / 修复\n")
        dependencies = {d["project_id"]: d["dependency_type"] for d in payload["dependencies"]}
        self.assertEqual(dependencies, {
            "P7dR8mSH": "required", "mOgUt4GM": "optional", "1eAoo2KR": "optional",
            "O7RBXm3n": "optional", "bEpr0Arc": "optional",
        })

    def test_stable_alpha_beta_and_other_prerelease_channels(self):
        for suffix, channel in [("", "release"), ("-alpha.1", "alpha"),
                                ("-beta.2", "beta"), ("-rc.1", "beta")]:
            with self.subTest(suffix=suffix):
                version = "0.6.1" + suffix
                self.properties.write_text(
                    f"mod_version={version}\nminecraft_version=1.21.11\n"
                    "archives_base_name=magic-shulker-boxes-fabric\n"
                )
                jar = self.dist / f"magic-shulker-boxes-fabric-{version}+mc1.21.11.jar"
                jar.write_bytes(self.jar.read_bytes())
                (self.dist / "SHA256SUMS").write_text(f"{self.sha256}  {jar.name}\n")
                payload, _ = release_payload(self.root, f"v{version}", self.dist, "Project1")
                self.assertEqual(payload["version_type"], channel)

    def test_relative_release_note_links_point_to_public_documentation(self):
        (self.dist / "release-notes.md").write_text(
            "[说明](../guide.md) [Guide](../en/guide.md#install) "
            "[External](https://example.com/page.md)\n", encoding="utf-8"
        )
        payload, _ = self.payload()
        self.assertIn("https://chenjicheng.github.io/magic-shulker-boxes/guide.html", payload["changelog"])
        self.assertIn("https://chenjicheng.github.io/magic-shulker-boxes/en/guide.html#install", payload["changelog"])
        self.assertIn("https://example.com/page.md", payload["changelog"])

    def test_corrupt_missing_unchecksummed_or_unsafe_input_never_contacts_api(self):
        for failure in ["corrupt", "missing", "checksum", "notes", "tag", "project"]:
            with self.subTest(failure=failure):
                original_jar = self.jar.read_bytes()
                checksum = self.dist / "SHA256SUMS"
                original_checksum = checksum.read_text()
                notes = self.dist / "release-notes.md"
                original_notes = notes.read_text(encoding="utf-8")
                tag, project = "v0.6.1", "Project1"
                if failure == "corrupt":
                    self.jar.write_bytes(b"corrupt")
                elif failure == "missing":
                    self.jar.unlink()
                elif failure == "checksum":
                    checksum.write_text(f"{self.sha256}  old.jar\n")
                elif failure == "notes":
                    notes.unlink()
                elif failure == "tag":
                    tag = "v0.6.2"
                else:
                    project = "../other"
                with self.assertRaises((ValueError, FileNotFoundError)):
                    publish(self.root, tag, self.dist, project, self.client)
                self.assertEqual(self.client.mock_calls, [])
                self.jar.write_bytes(original_jar)
                checksum.write_text(original_checksum)
                notes.write_text(original_notes, encoding="utf-8")

    def test_uploads_only_exact_release_jar_and_reads_back_version(self):
        (self.dist / "old.jar").write_bytes(b"old")
        (self.dist / (self.jar.stem + "-sources.jar")).write_bytes(b"sources")
        remote = self.remote_version()
        self.client.create_version.return_value = remote
        self.client.get.side_effect = [[], remote]
        self.assertEqual(publish(self.root, "v0.6.1", self.dist, "Project1", self.client),
                         {"id": "Version1", "created": True})
        payload, jar = self.client.create_version.call_args.args
        self.assertEqual(jar, self.jar)
        self.assertEqual(payload["file_parts"], ["file"])
        self.assertEqual(self.client.get.call_args.args, ("/version/Version1",))

    def test_repeated_publish_skips_only_an_identical_existing_version(self):
        self.client.get.return_value = [self.remote_version()]
        self.assertEqual(publish(self.root, "v0.6.1", self.dist, "Project1", self.client),
                         {"id": "Version1", "created": False})
        self.client.create_version.assert_not_called()

    def test_existing_version_different_hash_or_metadata_is_never_overwritten(self):
        for field in ["hash", "loaders", "game_versions", "project_id", "version_type",
                      "environment", "changelog", "dependencies"]:
            with self.subTest(field=field):
                remote = self.remote_version()
                if field == "hash":
                    remote["files"][0]["hashes"]["sha512"] = "wrong"
                elif isinstance(remote[field], list):
                    remote[field] = []
                else:
                    remote[field] = "wrong"
                self.client.get.return_value = [remote]
                with self.assertRaises(ValueError):
                    publish(self.root, "v0.6.1", self.dist, "Project1", self.client)
                self.client.create_version.assert_not_called()

    def test_server_readback_must_match_uploaded_file(self):
        remote = self.remote_version()
        self.client.create_version.return_value = remote
        bad = self.remote_version()
        bad["files"][0]["hashes"]["sha512"] = "wrong"
        self.client.get.side_effect = [[], bad]
        with self.assertRaises(ValueError):
            publish(self.root, "v0.6.1", self.dist, "Project1", self.client)
        self.client.create_version.assert_called_once()

    def test_timeout_does_not_blindly_repeat_post(self):
        self.client.create_version.side_effect = RuntimeError("timeout")
        with self.assertRaises(RuntimeError):
            publish(self.root, "v0.6.1", self.dist, "Project1", self.client)
        self.client.create_version.assert_called_once()

    def test_multipart_request_contains_json_and_installable_jar_only(self):
        opener = MagicMock()
        response = opener.open.return_value.__enter__.return_value
        response.read.return_value = b'{"id":"Version1"}'
        client = ModrinthClient("test-token", opener=opener)
        payload, jar = self.payload()
        client.create_version(payload, jar)
        request = opener.open.call_args.args[0]
        self.assertEqual(request.full_url, "https://api.modrinth.com/v2/version")
        self.assertEqual(request.get_header("Authorization"), "test-token")
        envelope = ("Content-Type: " + request.get_header("Content-type") + "\r\n\r\n").encode()
        parts = list(BytesParser(policy=policy.default).parsebytes(envelope + request.data).iter_parts())
        self.assertEqual(len(parts), 2)
        self.assertEqual(json.loads(parts[0].get_content()), payload)
        self.assertEqual(parts[1].get_filename(), jar.name)
        self.assertEqual(parts[1].get_payload(decode=True), jar.read_bytes())

    def test_http_errors_and_transport_errors_never_print_token_or_response(self):
        for error in [HTTPError("https://api.modrinth.com/v2/user", 401, "test-token", {}, None),
                      HTTPError("https://api.modrinth.com/v2/user", 302, "test-token", {}, None),
                      URLError("test-token")]:
            with self.subTest(error=type(error).__name__):
                opener = Mock()
                opener.open.side_effect = error
                with self.assertRaises(RuntimeError) as caught:
                    ModrinthClient("test-token", opener=opener).get("/user")
                self.assertNotIn("test-token", str(caught.exception))

    def test_empty_or_multiline_token_is_rejected(self):
        for token in ["", " ", "first\nsecond"]:
            with self.subTest(token=token), self.assertRaises(ValueError):
                ModrinthClient(token)

    def test_http_error_response_is_closed_without_leaking_its_body(self):
        body = io.BytesIO(b"reflected-secret")
        opener = Mock()
        opener.open.side_effect = HTTPError("https://api.modrinth.com/v2/user", 401, "Unauthorized", {}, body)
        with self.assertRaises(RuntimeError):
            ModrinthClient("test-token", opener=opener).get("/user")
        self.assertTrue(body.closed)


if __name__ == "__main__":
    unittest.main()
