import hashlib
import json
import os
from pathlib import Path
import unittest
from unittest.mock import patch
from zipfile import ZipFile

import deploy_releases
import test_stage_releases as staging_test_support
from test_stage_releases import jar_bytes


class FakeBackend:
    def __init__(self, config):
        self.config = config
        self.events = []
        self.states = {server["name"]: True for server in config["servers"]}
        self.timer = True
        self.fail_ready = None
        self.fail_generate_once = False

    def running(self, name):
        return self.states[name]

    def loaded(self, server, version):
        if not self.states[server["name"]]:
            return False
        matches = list(Path(server["mods_dir"]).glob("magic-shulker-boxes*.jar"))
        if len(matches) != 1:
            return False
        with ZipFile(matches[0]) as jar:
            return json.loads(jar.read("fabric.mod.json"))["version"] == version

    def timer_active(self, timer):
        return self.timer

    def pause(self, timer, service):
        self.events.append(("pause",))
        self.timer = False

    def resume(self, timer):
        self.events.append(("resume",))
        self.timer = True

    def stop(self, name):
        self.events.append(("stop", name))
        self.states[name] = False

    def start(self, name):
        self.events.append(("start", name))
        self.states[name] = True

    def wait_ready(self, server, version, timeout):
        self.events.append(("ready", server["name"]))
        if self.fail_ready == server["name"]:
            self.fail_ready = None
            raise RuntimeError("Server readiness failed")
        if not self.loaded(server, version):
            raise RuntimeError("Wrong loaded version")

    def generate(self, client, timeout):
        self.events.append(("generate",))
        if self.fail_generate_once:
            self.fail_generate_once = False
            raise RuntimeError("Pack generation failed")
        rows = []
        for path in Path(client["mods_dir"]).glob("*.jar"):
            data = path.read_bytes()
            rows.append({"file": "/mods/" + path.name, "sha1": hashlib.sha1(data).hexdigest(), "size": str(len(data))})
        Path(client["manifest_path"]).write_text(json.dumps({"list": rows}))


class ManualDeploymentTests(unittest.TestCase):
    def setUp(self):
        self.fixture = staging_test_support.ReleaseStagingTests()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.root = self.fixture.root
        servers = []
        for name in ["mc", "cmc"]:
            base = self.root / "live" / name
            mods = base / "mods"
            mods.mkdir(parents=True)
            config = base / "config/magic_shulker_boxes.json"
            config.parent.mkdir()
            config.write_bytes(b'{"configVersion":2,"craftRefill":false}\n')
            servers.append({"name": name, "target": name + "-server", "mods_dir": str(mods),
                            "log_file": str(base / "logs/latest.log"), "config_paths": [str(config)]})
        client = self.root / "live/mc/automodpack/main/mods"
        client.mkdir(parents=True)
        self.config = {"staging_root": self.fixture.config["cache_dir"], "minecraft_version": "1.21.11",
                       "backup_root": str(self.root / "deploy-backups"), "timer": "minecraft-mod-stager.timer",
                       "check_service": "minecraft-mod-stager.service", "ready_timeout": 2,
                       "servers": servers, "client": {"target": "mc-client", "server": "mc", "mods_dir": str(client),
                                                       "manifest_path": str(client.parent / "automodpack-content.json")}}
        self.fixture.msb["targets"] = [{"name": server["target"], "installed_dir": server["mods_dir"]} for server in servers]
        self.fixture.msb["targets"].append({"name": "mc-client", "installed_dir": str(client)})
        self.fixture.cco["targets"] = [{"name": "mc-client", "installed_dir": str(client)}]
        self.old = {}
        for target in self.fixture.msb["targets"]:
            path = Path(target["installed_dir"]) / "magic-shulker-boxes-fabric-0.6.1+mc1.21.11.jar"
            path.write_bytes(jar_bytes("magic_shulker_boxes", "0.6.1+mc1.21.11", "*"))
            self.old[path] = path.read_bytes()
        cco = client / "chest_count_overlay-1.0.1.jar"
        cco.write_bytes(jar_bytes("chest_count_overlay", "1.0.1", "client"))
        self.old[cco] = cco.read_bytes()
        self.fixture.release(self.fixture.msb, "0.7.0", 300)
        self.fixture.results()
        self.backend = FakeBackend(self.config)
        self.backend.generate(self.config["client"], 2)
        self.backend.events.clear()

    def assert_old(self):
        for path, data in self.old.items():
            self.assertEqual(data, path.read_bytes())

    def test_preview_validates_bundles_without_commands_or_live_writes(self):
        result = deploy_releases.deploy(self.config, dry_run=True, backend=self.backend)
        self.assertEqual("preview", result["status"])
        self.assertEqual(4, len(result["changes"]))
        self.assertEqual([], self.backend.events)
        self.assert_old()
        self.assertFalse(Path(self.config["backup_root"]).exists())

    def test_apply_updates_both_servers_and_clients_and_retains_exact_backups(self):
        result = deploy_releases.deploy(self.config, backend=self.backend)
        self.assertEqual("deployed", result["status"])
        self.assertTrue(self.backend.timer)
        self.assertTrue(all(self.backend.states.values()))
        for path in self.old:
            self.assertFalse(path.exists())
        for server in self.config["servers"]:
            self.assertTrue((Path(server["mods_dir"]) / "magic-shulker-boxes-fabric-0.7.0+mc1.21.11.jar").exists())
        receipt = json.loads((Path(result["backup"]) / "receipt.json").read_text())
        for entry in receipt["files"]:
            self.assertEqual(entry["sha256"], hashlib.sha256((Path(result["backup"]) / entry["backup"]).read_bytes()).hexdigest())
        events = self.backend.events
        self.assertLess(events.index(("stop", "mc")), events.index(("start", "mc")))
        self.assertIn(("generate",), events)

    def test_cco_only_never_restarts_or_changes_server_mods(self):
        result = deploy_releases.deploy(self.config, module="cco", backend=self.backend)
        self.assertEqual("deployed", result["status"])
        self.assertFalse(any(event[0] in {"stop", "start"} for event in self.backend.events))
        for path, data in self.old.items():
            if "magic-shulker" in path.name:
                self.assertEqual(data, path.read_bytes())

    def test_repeat_apply_is_unchanged_and_does_not_restart_again(self):
        deploy_releases.deploy(self.config, backend=self.backend)
        self.backend.events.clear()
        result = deploy_releases.deploy(self.config, backend=self.backend)
        self.assertEqual("unchanged", result["status"])
        self.assertEqual([], self.backend.events)

    def test_startup_failure_restores_current_files_configs_and_running_state(self):
        self.backend.fail_ready = "cmc"
        with self.assertRaises(RuntimeError):
            deploy_releases.deploy(self.config, backend=self.backend)
        self.assert_old()
        self.assertTrue(self.backend.timer)
        self.assertTrue(all(self.backend.states.values()))
        for server in self.config["servers"]:
            self.assertIn(b'"craftRefill":false', Path(server["config_paths"][0]).read_bytes())

    def test_failed_client_manifest_generation_rolls_back_both_modules(self):
        self.backend.fail_generate_once = True
        with self.assertRaises(RuntimeError):
            deploy_releases.deploy(self.config, backend=self.backend)
        self.assert_old()
        self.assertTrue(self.backend.timer)

    def test_corrupt_staged_jar_fails_before_pause_or_restart(self):
        staged = self.fixture.staged(self.fixture.msb, "v0.7.0")
        (staged / "magic-shulker-boxes-fabric-0.7.0+mc1.21.11.jar").write_bytes(b"corrupt")
        with self.assertRaises(Exception):
            deploy_releases.deploy(self.config, backend=self.backend)
        self.assertEqual([], self.backend.events)
        self.assert_old()

    def test_an_originally_inactive_timer_is_not_enabled_by_deployment(self):
        self.backend.timer = False
        deploy_releases.deploy(self.config, backend=self.backend)
        self.assertFalse(self.backend.timer)

    def test_backups_record_ownership_needed_to_restore_writable_player_settings(self):
        players = Path(self.config["servers"][0]["mods_dir"]).parent / "world/data/magic_shulker_boxes/players"
        players.mkdir(parents=True)
        preference = players / "fixture.json"
        preference.write_bytes(b'{"configVersion":2}')
        self.config["servers"][0]["config_paths"].append(str(players))
        result = deploy_releases.deploy(self.config, backend=self.backend)
        receipt = json.loads((Path(result["backup"]) / "receipt.json").read_text())
        for entry in receipt["files"]:
            self.assertIn("uid", entry)
            self.assertIn("gid", entry)
        record = next(item for item in receipt["configuration"] if item["path"] == str(players))
        self.assertEqual(preference.stat().st_uid, record["ownership"]["fixture.json"]["uid"])

    def test_client_manifest_path_cannot_point_outside_the_pack(self):
        self.config["client"]["manifest_path"] = str(self.root / "unrelated/private.json")
        with self.assertRaises(deploy_releases.DeploymentError):
            deploy_releases.deploy(self.config, dry_run=True, backend=self.backend)
        self.assertEqual([], self.backend.events)

    @unittest.skipUnless(os.name == "posix" and getattr(os, "geteuid", lambda: -1)() == 0,
                         "Real ownership rollback is verified under sudo on the Linux deployment host")
    def test_failed_deployment_restores_original_player_directory_owners(self):
        personal = Path(self.config["servers"][0]["mods_dir"]).parent / "world/data/magic_shulker_boxes/players"
        personal.mkdir(parents=True)
        preference = personal / "fixture.json"
        preference.write_text('{"configVersion":2}')
        os.chown(personal, 1000, 1000)
        os.chown(preference, 1000, 1000)
        self.config["servers"][0]["config_paths"].append(str(personal))
        self.backend.fail_ready = "cmc"
        with self.assertRaises(RuntimeError):
            deploy_releases.deploy(self.config, backend=self.backend)
        self.assertEqual((1000, 1000), (personal.stat().st_uid, personal.stat().st_gid))
        self.assertEqual((1000, 1000), (preference.stat().st_uid, preference.stat().st_gid))


if __name__ == "__main__":
    unittest.main()
