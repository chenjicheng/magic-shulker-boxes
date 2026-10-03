"""Manually apply verified staged mods, preserving installed-file backups and server state."""
import argparse
from datetime import datetime, timezone
import hashlib
from itertools import islice
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import time
from uuid import uuid4

import stage_releases as staging

MODULES = {
    "msb": {"id": "magic_shulker_boxes", "archive_prefix": "magic-shulker-boxes-fabric-", "environment": "*"},
    "cco": {"id": "chest_count_overlay", "archive_prefix": "chest_count_overlay-", "environment": "client"},
}


class DeploymentError(RuntimeError):
    pass


def command(arguments, timeout=30):
    result = subprocess.run(arguments, capture_output=True, text=True, timeout=timeout)
    if result.returncode:
        # RCON and Docker diagnostics can contain environment details. Keep failures to fixed command names.
        raise DeploymentError("Command failed: " + " ".join(arguments[:4]))
    return result.stdout.strip()


class DockerBackend:
    def running(self, name):
        return command(["docker", "inspect", "--format", "{{.State.Running}}", name]) == "true"

    def loaded(self, server, version):
        if not self.running(server["name"]):
            return False
        try:
            command(["docker", "exec", server["name"], "rcon-cli", "list"], timeout=10)
            pattern = re.compile(r"\bmagic_shulker_boxes\s+" + re.escape(version) + r"(?=\s|$)")
            # Fabric prints the mod list near startup; do not reread a potentially huge live log on every retry.
            with Path(server["log_file"]).open(encoding="utf-8", errors="replace") as stream:
                return any(pattern.search(line) for line in islice(stream, 4096))
        except (DeploymentError, OSError, subprocess.TimeoutExpired):
            return False

    def timer_active(self, timer):
        return command(["systemctl", "show", timer, "--property=ActiveState", "--value"]) == "active"

    def pause(self, timer, service):
        command(["systemctl", "stop", timer, service], timeout=60)

    def resume(self, timer):
        command(["systemctl", "start", timer])

    def stop(self, name):
        command(["docker", "stop", "--timeout=-1", name], timeout=None)

    def start(self, name):
        command(["docker", "start", name])

    def wait_ready(self, server, version, timeout):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if self.loaded(server, version):
                return
            time.sleep(2)
        raise DeploymentError("Server did not load the expected MSB version: " + server["name"])

    def generate(self, client, timeout):
        command(["docker", "exec", client["server"], "rcon-cli", "automodpack", "generate"], timeout=timeout)


def validate_config(config):
    fields = {"staging_root", "minecraft_version", "backup_root", "timer", "check_service", "ready_timeout", "servers", "client"}
    if not isinstance(config, dict) or set(config) != fields:
        raise DeploymentError("Invalid deployment configuration fields")
    for key in ("staging_root", "backup_root"):
        if not isinstance(config[key], str) or not Path(config[key]).is_absolute() or Path(config[key]).parent == Path(config[key]):
            raise DeploymentError("Deployment paths must be absolute directories below the filesystem root")
    if (not isinstance(config["minecraft_version"], str) or not re.fullmatch(r"[0-9]+(?:\.[0-9]+)+", config["minecraft_version"])
            or type(config["ready_timeout"]) is not int or not 1 <= config["ready_timeout"] <= 600):
        raise DeploymentError("Invalid Minecraft version or readiness timeout")
    for key, suffix in [("timer", ".timer"), ("check_service", ".service")]:
        if not isinstance(config[key], str) or not re.fullmatch(r"[a-z0-9_-]+" + re.escape(suffix), config[key]):
            raise DeploymentError("Invalid systemd unit name")
    if not isinstance(config["servers"], list) or not config["servers"]:
        raise DeploymentError("At least one server must be configured")
    names = set()
    for server in config["servers"]:
        if set(server) != {"name", "target", "mods_dir", "log_file", "config_paths"}:
            raise DeploymentError("Invalid server configuration")
        if not staging.SAFE_ID.fullmatch(server["name"]) or server["name"] in names or not staging.SAFE_ID.fullmatch(server["target"]):
            raise DeploymentError("Unsafe or duplicate server name/target")
        names.add(server["name"])
        base = Path(server["mods_dir"]).resolve().parent
        for path in [Path(server["log_file"]), *(Path(value) for value in server["config_paths"])]:
            if not path.is_absolute() or base not in path.resolve().parents:
                raise DeploymentError("Server log/configuration must stay inside its data directory")
        for value in server["config_paths"]:
            path = Path(value).resolve()
            personal = any(parent.name == "magic_shulker_boxes" and parent.parent.name == "data"
                           and parent.parent.parent.parent == base for parent in [path, *path.parents])
            if path != base / "config/magic_shulker_boxes.json" and not personal:
                raise DeploymentError("Only MSB configuration/preferences may be backed up as settings")
    client = config["client"]
    if (set(client) != {"target", "server", "mods_dir", "manifest_path"}
            or not staging.SAFE_ID.fullmatch(client["target"]) or client["server"] not in names):
        raise DeploymentError("Invalid client-distribution configuration")
    pack_root = Path(client["mods_dir"]).resolve().parent.parent
    manifest_path = Path(client["manifest_path"])
    if not manifest_path.is_absolute() or pack_root not in manifest_path.resolve().parents or manifest_path.is_symlink():
        raise DeploymentError("Client manifest must stay inside the hosted pack")
    stage_root = Path(config["staging_root"]).resolve()
    backup_root = Path(config["backup_root"]).resolve()
    live_dirs = [Path(server["mods_dir"]) for server in config["servers"]] + [Path(client["mods_dir"])]
    for directory in live_dirs:
        if not directory.is_absolute() or not directory.is_dir() or directory.is_symlink():
            raise DeploymentError("Installed mod directories must already exist and cannot be symlinks")
        live = directory.resolve()
        for storage in (stage_root, backup_root):
            if live == storage or live in storage.parents or storage in live.parents:
                raise DeploymentError("Live mods and staging/backups must be separate")
    if stage_root == backup_root or stage_root in backup_root.parents or backup_root in stage_root.parents:
        raise DeploymentError("Deployment backups must be separate from the immutable staging cache")


def current_files(directory, module):
    result = []
    for path in sorted(Path(directory).iterdir()):
        if not path.name.startswith(module["archive_prefix"]) or not path.name.endswith(".jar") or path.name.endswith("-sources.jar"):
            continue
        if (path.is_symlink() or not path.is_file() or not staging.SAFE_NAME.fullmatch(path.name)
                or path.stat().st_size > staging.MAX_JAR_BYTES):
            raise DeploymentError("Unsafe installed JAR")
        data = path.read_bytes()
        metadata = staging.jar_metadata(data)
        if metadata.get("id") != module["id"]:
            raise DeploymentError("Installed filename has an unexpected mod ID")
        attributes = path.stat()
        result.append({"path": str(path), "sha256": staging.digest(data), "version": metadata.get("version"),
                       "uid": attributes.st_uid, "gid": attributes.st_gid})
    return result


def plan(config, selection, backend):
    validate_config(config)
    if selection not in {"all", "msb", "cco"}:
        raise DeploymentError("Select all, msb or cco")
    root = Path(config["staging_root"]).resolve()
    entries = []
    for key in (["msb", "cco"] if selection == "all" else [selection]):
        module = MODULES[key]
        pointer = staging.read_json(staging.safe_path(root, "latest", module["id"] + ".json").read_bytes())
        tag = pointer.get("tag")
        if not isinstance(tag, str) or not staging.STABLE_TAG.fullmatch(tag):
            raise DeploymentError("Latest staging pointer has an invalid tag")
        version = tag[1:] + ("+mc" + config["minecraft_version"] if key == "msb" else "")
        target_specs = ([{"target": server["target"], "mods_dir": server["mods_dir"]} for server in config["servers"]]
                        if key == "msb" else []) + [config["client"]]
        for target in target_specs:
            bundle = staging.safe_path(root, "targets", target["target"], module["id"], tag)
            if str(bundle) not in pointer.get("targets", []):
                raise DeploymentError("Selected target is absent from the completed staging pointer")
            manifest = staging.read_json(staging.safe_path(root, str(bundle.relative_to(root)), "manifest.json").read_bytes())
            staging.verify_directory(root, bundle, manifest)
            asset = module["archive_prefix"] + version + ".jar"
            if (manifest.get("mod_id") != module["id"] or manifest.get("tag") != tag or manifest.get("asset") != asset
                    or manifest.get("sha256") != pointer.get("sha256") or manifest.get("target") != target["target"]):
                raise DeploymentError("Staged manifest does not match the pinned module/target")
            source = staging.safe_path(root, str(bundle.relative_to(root)), asset)
            data = source.read_bytes()
            receipt = staging.safe_path(root, str(bundle.relative_to(root)), manifest["checksum_asset"]).read_bytes()
            if staging.checksum_for(receipt, asset) != staging.digest(data) or staging.digest(data) != manifest["sha256"]:
                raise DeploymentError("Staged SHA256 receipt mismatch")
            staging.validate_jar(data, module, version, config["minecraft_version"])
            old = current_files(target["mods_dir"], module)
            if any(staging.version_tuple(item["version"]) > staging.version_tuple(version) for item in old):
                raise DeploymentError("Refusing a downgrade")
            destination = Path(target["mods_dir"]) / asset
            changed = len(old) != 1 or old[0]["path"] != str(destination) or old[0]["sha256"] != manifest["sha256"]
            entries.append({"mod_id": module["id"], "target": target["target"], "source": str(source),
                            "destination": str(destination), "sha256": manifest["sha256"], "version": version,
                            "old": old, "changed": changed, "client": target["target"] == config["client"]["target"]})
    restarts = []
    for server in config["servers"]:
        wanted = next((entry for entry in entries if entry["target"] == server["target"]), None)
        if wanted and (wanted["changed"] or not backend.loaded(server, wanted["version"])):
            restarts.append(server)
    changes = [entry for entry in entries if entry["changed"]]
    if (changes or restarts) and not backend.running(config["client"]["server"]):
        raise DeploymentError("Client pack host must be running before a coordinated deployment")
    if any(not backend.running(server["name"]) for server in restarts):
        raise DeploymentError("Selected servers must be running before coordinated deployment")
    return entries, restarts


def backup_path(source, destination):
    source = Path(source)
    if not source.exists():
        return False
    if source.is_symlink() or (source.is_dir() and any(path.is_symlink() for path in source.rglob("*"))):
        raise DeploymentError("Configuration backups cannot contain symlinks")
    destination.parent.mkdir(parents=True, exist_ok=True)
    if source.is_dir():
        shutil.copytree(source, destination)
    else:
        shutil.copy2(source, destination)
    return True


def ownership(path):
    path = Path(path)
    if not path.exists():
        return {}
    paths = [path, *path.rglob("*")] if path.is_dir() else [path]
    return {str(item.relative_to(path)): {"uid": item.stat().st_uid, "gid": item.stat().st_gid} for item in paths}


def restore_ownership(path, attributes):
    if os.name == "posix":
        for relative, owners in attributes.items():
            os.chown(Path(path) / relative, owners["uid"], owners["gid"])


def make_backup(config, entries, directory):
    files = []
    saved_configs = []
    for index, entry in enumerate(entries):
        for old in entry["old"]:
            source = Path(old["path"])
            if staging.digest(source.read_bytes()) != old["sha256"]:
                raise DeploymentError("Installed JAR changed after preflight")
            name = "jars/" + str(index) + "/" + source.name
            backup_path(source, directory / name)
            files.append({**old, "backup": name})
    for server in config["servers"]:
        if not any(entry["target"] == server["target"] for entry in entries):
            continue
        for index, value in enumerate(server["config_paths"]):
            name = "config/" + server["name"] + "/" + str(index)
            existed = backup_path(value, directory / name)
            saved_configs.append({"path": value, "backup": name, "existed": existed, "ownership": ownership(value)})
    manifest = config["client"]["manifest_path"]
    saved_configs.append({"path": manifest, "backup": "client-manifest",
                          "existed": backup_path(manifest, directory / "client-manifest"), "ownership": ownership(manifest)})
    return {"files": files, "configuration": saved_configs}


def restore_files(receipt, entries, directory):
    for entry in entries:
        destination = Path(entry["destination"])
        if destination.exists():
            destination.unlink()
    for old in receipt["files"]:
        shutil.copy2(directory / old["backup"], old["path"])
        if os.name == "posix":
            os.chown(old["path"], old["uid"], old["gid"])
    for saved in receipt["configuration"]:
        path = Path(saved["path"])
        if not saved["existed"]:
            if path.exists():
                if path.is_dir():
                    shutil.rmtree(path)
                else:
                    path.unlink()
            continue
        snapshot = directory / saved["backup"]
        if snapshot.is_dir():
            # Replace only the explicitly configured, task-owned settings directory; world saves are outside it.
            if path.exists():
                shutil.rmtree(path)
            shutil.copytree(snapshot, path)
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(snapshot, path)
        restore_ownership(path, saved["ownership"])


def verify_pack(client, entries, timeout):
    wanted = [entry for entry in entries if entry["client"]]
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            manifest = staging.read_json(Path(client["manifest_path"]).read_bytes())
            rows = manifest.get("list", [])
            valid = True
            for entry in wanted:
                data = Path(entry["destination"]).read_bytes()
                candidates = [row for row in rows if str(row.get("file", "")).replace("\\", "/").split("/")[-1]
                              == Path(entry["destination"]).name]
                if (len(candidates) != 1 or str(candidates[0].get("size")) != str(len(data))
                        or candidates[0].get("sha1") != hashlib.sha1(data).hexdigest()):
                    valid = False
            if valid:
                return
        except (OSError, staging.StagingError):
            pass
        time.sleep(1)
    raise DeploymentError("AutoModpack manifest did not confirm the installed client bytes")


def deploy(config, module="all", dry_run=False, backend=None):
    backend = backend or DockerBackend()
    entries, restarts = plan(config, module, backend)
    changes = [entry for entry in entries if entry["changed"]]
    summary = {"changes": [{key: entry[key] for key in ("mod_id", "target", "version", "destination")} for entry in changes],
               "restarts": [server["name"] for server in restarts]}
    if dry_run:
        return {"status": "preview", **summary}
    if not changes and not restarts:
        return {"status": "unchanged", **summary}
    backup_root = Path(config["backup_root"]).resolve()
    with staging.exclusive_check(backup_root):
        timer_was_active = backend.timer_active(config["timer"])
        directory = backup_root / (datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ-") + uuid4().hex[:8])
        directory.mkdir(mode=0o700)
        receipt = None
        touched = False
        stopped = []
        try:
            backend.pause(config["timer"], config["check_service"])
            for server in restarts:
                backend.stop(server["name"])
                stopped.append(server)
            receipt = make_backup(config, entries, directory)
            staging.replace_json(directory / "receipt.json", {**receipt, "status": "prepared", **summary})
            for entry in changes:
                source = Path(entry["source"])
                if staging.digest(source.read_bytes()) != entry["sha256"]:
                    raise DeploymentError("Staged JAR changed after preflight")
            touched = True
            for entry in changes:
                for old in entry["old"]:
                    Path(old["path"]).unlink()
                destination = Path(entry["destination"])
                temporary = destination.with_name(destination.name + ".deploy-tmp")
                if temporary.exists():
                    raise DeploymentError("Unexpected deployment temporary file")
                try:
                    shutil.copyfile(entry["source"], temporary)
                    temporary.chmod(0o644)
                    if os.name == "posix":
                        owners = destination.parent.stat()
                        os.chown(temporary, owners.st_uid, owners.st_gid)
                    temporary.replace(destination)
                finally:
                    temporary.unlink(missing_ok=True)
            for server in restarts:
                backend.start(server["name"])
                version = next(entry["version"] for entry in entries if entry["target"] == server["target"])
                backend.wait_ready(server, version, config["ready_timeout"])
            backend.generate(config["client"], config["ready_timeout"])
            verify_pack(config["client"], entries, config["ready_timeout"])
            staging.replace_json(directory / "receipt.json", {**receipt, "status": "deployed", **summary})
            return {"status": "deployed", "backup": str(directory), **summary}
        except BaseException as error:
            rollback_errors = []
            if receipt is not None and touched:
                for server in stopped:
                    try:
                        if backend.running(server["name"]):
                            backend.stop(server["name"])
                    except Exception as rollback_error:
                        rollback_errors.append(str(rollback_error))
                try:
                    restore_files(receipt, entries, directory)
                except Exception as rollback_error:
                    rollback_errors.append(str(rollback_error))
            for server in stopped:
                try:
                    if not backend.running(server["name"]):
                        backend.start(server["name"])
                    if receipt is not None and touched:
                        previous = next((item["version"] for item in receipt["files"]
                                         if Path(item["path"]).parent == Path(server["mods_dir"])), None)
                        if previous:
                            backend.wait_ready(server, previous, config["ready_timeout"])
                except Exception as rollback_error:
                    rollback_errors.append(str(rollback_error))
            if receipt is not None and touched and not rollback_errors:
                try:
                    backend.generate(config["client"], config["ready_timeout"])
                except Exception as rollback_error:
                    rollback_errors.append(str(rollback_error))
            if receipt is not None:
                staging.replace_json(directory / "receipt.json", {**receipt, "status": "failed", "error": str(error),
                                                               "rollback_errors": rollback_errors, **summary})
            if rollback_errors:
                raise DeploymentError("Deployment failed; rollback needs attention: " + str(directory)) from error
            raise
        finally:
            if timer_was_active:
                backend.resume(config["timer"])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("module", nargs="?", choices=["all", "msb", "cco"], default="all")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--config", type=Path, default=Path("/etc/minecraft-mod-deployer.json"))
    args = parser.parse_args()
    if os.name != "posix" or os.geteuid() != 0:
        parser.error("Run through sudo on the deployment host")
    if args.config.is_symlink() or args.config.stat().st_uid != 0 or args.config.stat().st_mode & 0o022:
        parser.error("Deployment configuration must be root-owned and not writable by other users")
    try:
        result = deploy(staging.read_json(args.config.read_bytes()), args.module, args.dry_run)
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 0
    except (DeploymentError, staging.StagingError, OSError, subprocess.SubprocessError) as error:
        print(json.dumps({"status": "error", "error": str(error)}, ensure_ascii=False))
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
