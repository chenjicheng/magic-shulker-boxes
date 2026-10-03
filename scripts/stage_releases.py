"""Prepare verified public-release bundles. Installed mods and client distribution remain read-only."""
import argparse
from contextlib import contextmanager
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shutil
import tempfile
from urllib.error import HTTPError, URLError
from urllib.parse import unquote, urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener
from zipfile import BadZipFile, ZipFile

MAX_JAR_BYTES = 32 * 1024 * 1024
MAX_JSON_BYTES = 2 * 1024 * 1024
MAX_CHECKSUM_BYTES = 64 * 1024
SAFE_NAME = re.compile(r"[A-Za-z0-9][A-Za-z0-9_.+-]*")
SAFE_ID = re.compile(r"[a-z][a-z0-9_-]{0,63}")
STABLE_TAG = re.compile(r"v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)")
TRUSTED_HOSTS = {"api.github.com", "github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com"}


class StagingError(Exception):
    """An expected metadata, integrity or configuration failure; no installed file is modified."""


def trusted_url(url):
    if not isinstance(url, str):
        raise StagingError("Download URL must be a string")
    try:
        parts = urlsplit(url)
        allowed = (parts.scheme == "https" and parts.hostname in TRUSTED_HOSTS and parts.port in (None, 443)
                   and not parts.username and not parts.password)
    except ValueError as error:
        raise StagingError("Malformed download URL") from error
    if not allowed:
        raise StagingError("Download origin is not an allowed GitHub HTTPS host")


class GitHubRedirects(HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, new_url):
        trusted_url(new_url)
        return super().redirect_request(request, response, code, message, headers, new_url)


def fetch_github(url, limit):
    """Fetch without credentials, with bounded content, timeouts and origin-checked redirects."""
    trusted_url(url)
    request = Request(url, headers={"User-Agent": "minecraft-mod-stager", "Accept": "application/vnd.github+json"})
    try:
        with build_opener(GitHubRedirects()).open(request, timeout=30) as response:
            data = response.read(limit + 1)
    except HTTPError as error:
        raise StagingError(f"GitHub returned HTTP {error.code}") from error
    except URLError as error:
        # Redirect URLs can contain temporary signed query strings; do not include them in journal output.
        raise StagingError(f"GitHub connection failed: {type(error.reason).__name__}") from error
    if len(data) > limit:
        raise StagingError("Download exceeds its size limit")
    return data


def read_json(data):
    try:
        result = json.loads(data)
    except (UnicodeError, json.JSONDecodeError) as error:
        raise StagingError("Invalid JSON document") from error
    if not isinstance(result, dict):
        raise StagingError("JSON document must be an object")
    return result


def digest(data):
    return hashlib.sha256(data).hexdigest()


def safe_path(root, *parts):
    path = root.joinpath(*parts)
    resolved = path.resolve()
    if resolved == root or root not in resolved.parents:
        raise StagingError("Staging path escapes the configured cache directory")
    return path


def validate_config(config):
    if not isinstance(config, dict) or set(config) != {"cache_dir", "minecraft_version", "modules"}:
        raise StagingError("Expected cache_dir, minecraft_version and modules")
    if not isinstance(config["cache_dir"], str):
        raise StagingError("cache_dir must be a path string")
    root = Path(config["cache_dir"])
    if not root.is_absolute() or root.parent == root:
        raise StagingError("cache_dir must be an absolute directory below the filesystem root")
    root = root.resolve()
    minecraft = config["minecraft_version"]
    if not isinstance(minecraft, str) or not re.fullmatch(r"[0-9]+(?:\.[0-9]+)+", minecraft):
        raise StagingError("Invalid Minecraft version")
    modules = config["modules"]
    if not isinstance(modules, list) or not modules:
        raise StagingError("At least one module is required")
    ids = set()
    for module in modules:
        required = {"id", "repository", "archive_prefix", "version_suffix", "environment", "targets"}
        if not isinstance(module, dict) or set(module) != required:
            raise StagingError("Invalid module fields")
        if not isinstance(module["id"], str) or not SAFE_ID.fullmatch(module["id"]) or module["id"] in ids:
            raise StagingError("Module IDs must be safe and unique")
        ids.add(module["id"])
        if not isinstance(module["repository"], str) or not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", module["repository"]):
            raise StagingError("Repository must be an owner/name pair")
        if not isinstance(module["archive_prefix"], str) or not SAFE_NAME.fullmatch(module["archive_prefix"]):
            raise StagingError("Invalid archive prefix")
        if module["version_suffix"] not in ("", "+mc" + minecraft) or module["environment"] not in ("*", "client", "server"):
            raise StagingError("Invalid module version suffix or environment")
        names = set()
        if not isinstance(module["targets"], list) or not module["targets"]:
            raise StagingError("A module needs at least one staging target")
        for target in module["targets"]:
            if not isinstance(target, dict) or set(target) != {"name", "installed_dir"}:
                raise StagingError("Invalid target fields")
            if not isinstance(target["name"], str) or not SAFE_ID.fullmatch(target["name"]) or target["name"] in names:
                raise StagingError("Target names must be safe and unique within a module")
            names.add(target["name"])
            if not isinstance(target["installed_dir"], str):
                raise StagingError("installed_dir must be a path string")
            installed = Path(target["installed_dir"])
            if not installed.is_absolute() or not installed.is_dir():
                raise StagingError("An installed source directory must already exist")
            installed = installed.resolve()
            if installed == root or installed in root.parents or root in installed.parents:
                raise StagingError("Installed sources and the writable staging root must be separate")
    return root


def version_tuple(version):
    match = re.fullmatch(r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:[-+][A-Za-z0-9.+-]+)?", version)
    if not match:
        raise StagingError("Unsupported mod version")
    return tuple(int(value) for value in match.groups())


def jar_metadata(data):
    try:
        with ZipFile(io.BytesIO(data)) as jar:
            entries = jar.infolist()
            if (len(entries) > 5000 or sum(entry.file_size for entry in entries) > 128 * 1024 * 1024
                    or sum(entry.filename == "fabric.mod.json" for entry in entries) != 1
                    or jar.getinfo("fabric.mod.json").file_size > 128 * 1024):
                raise StagingError("Invalid or oversized JAR metadata")
            return read_json(jar.read("fabric.mod.json"))
    except (BadZipFile, KeyError) as error:
        raise StagingError("Not an installable Fabric JAR") from error


def source_snapshots(module):
    snapshots = {}
    highest = (0, 0, 0)
    for target in module["targets"]:
        sources = []
        for path in sorted(Path(target["installed_dir"]).iterdir()):
            if not path.name.startswith(module["archive_prefix"]) or not path.name.endswith(".jar") or path.name.endswith("-sources.jar"):
                continue
            if path.is_symlink() or not path.is_file() or not SAFE_NAME.fullmatch(path.name) or path.stat().st_size > MAX_JAR_BYTES:
                raise StagingError("Unsafe installed JAR source")
            data = path.read_bytes()
            metadata = jar_metadata(data)
            if metadata.get("id") != module["id"] or not isinstance(metadata.get("version"), str):
                raise StagingError("Installed source has an unexpected mod ID or version")
            highest = max(highest, version_tuple(metadata["version"]))
            sources.append((path, data))
        snapshots[target["name"]] = sources
    return snapshots, highest


def select_release(module, release):
    tag = release.get("tag_name")
    if (release.get("draft") is not False or release.get("prerelease") is not False
            or not isinstance(tag, str) or not STABLE_TAG.fullmatch(tag)
            or type(release.get("id")) is not int or release["id"] <= 0):
        raise StagingError("Expected a published stable semantic-version release")
    filename = module["archive_prefix"] + tag[1:] + module["version_suffix"] + ".jar"
    assets = release.get("assets")
    if not isinstance(assets, list) or not all(isinstance(asset, dict) for asset in assets):
        raise StagingError("Release assets are missing")
    candidates = [asset for asset in assets if asset.get("name") == filename]
    checksums = [asset for asset in assets if asset.get("name") == "SHA256SUMS"]
    if not checksums:
        # CCO 1.0.1 predates the common SHA256SUMS release workflow.
        checksums = [asset for asset in assets if asset.get("name") == filename[:-4] + ".sha256"]
    if len(candidates) != 1 or len(checksums) != 1:
        raise StagingError("Release must contain one expected JAR and one supported checksum asset")
    for asset, limit in [(candidates[0], MAX_JAR_BYTES), (checksums[0], MAX_CHECKSUM_BYTES)]:
        url = asset.get("browser_download_url", "")
        trusted_url(url)
        parts = urlsplit(url)
        expected = "/" + module["repository"] + "/releases/download/" + tag + "/" + asset["name"]
        if (parts.scheme != "https" or parts.netloc != "github.com" or unquote(parts.path) != expected
                or parts.query or parts.fragment or type(asset.get("size")) is not int or not 0 < asset["size"] <= limit):
            raise StagingError("Asset URL or size does not match the selected release")
        if asset.get("digest") is not None and (not isinstance(asset["digest"], str)
                                                or not re.fullmatch(r"sha256:[0-9a-f]{64}", asset["digest"])):
            raise StagingError("Unsupported GitHub asset digest")
    return candidates[0], checksums[0]


def checked_asset(asset, data):
    if len(data) != asset["size"] or (asset.get("digest") and asset["digest"] != "sha256:" + digest(data)):
        raise StagingError("Asset size or GitHub SHA256 digest mismatch")


def checksum_for(data, filename):
    try:
        lines = data.decode("utf-8").splitlines()
    except UnicodeError as error:
        raise StagingError("Checksum file is not UTF-8") from error
    matching = []
    for line in lines:
        if not line.strip():
            continue
        match = re.fullmatch(r"([0-9a-fA-F]{64}) [ *]((?:release-artifacts/)?[A-Za-z0-9][A-Za-z0-9_.+-]*)", line)
        if not match:
            raise StagingError("Invalid SHA256 checksum entry")
        # Older CCO receipts include this fixed build-directory prefix; it is never used as a filesystem path.
        if match[2].removeprefix("release-artifacts/") == filename:
            matching.append(match[1].lower())
    if len(matching) != 1:
        raise StagingError("Checksum file must contain exactly one selected-JAR entry")
    return matching[0]


def validate_jar(data, module, version, minecraft):
    metadata = jar_metadata(data)
    dependencies = metadata.get("depends", {})
    if (metadata.get("id") != module["id"] or metadata.get("version") != version
            or metadata.get("environment") != module["environment"] or not isinstance(dependencies, dict)
            or dependencies.get("minecraft") not in (minecraft, "=" + minecraft)):
        raise StagingError("JAR mod ID, version, environment or Minecraft requirement mismatch")


def publish_directory(root, destination, files):
    """Publish only a complete immutable bundle; interrupted writes remain outside the final directory."""
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = Path(tempfile.mkdtemp(prefix=".pending-", dir=destination.parent))
    try:
        for name, data in files.items():
            path = safe_path(root, str(temporary.relative_to(root)), name)
            path.parent.mkdir(parents=True, exist_ok=True)
            with path.open("xb") as stream:
                stream.write(data)
                stream.flush()
                os.fsync(stream.fileno())
        if destination.exists():
            raise StagingError("Refusing to overwrite an existing release bundle")
        temporary.rename(destination)
    finally:
        # This directory was created by this call and checked to stay under the staging root.
        if temporary.exists():
            safe_path(root, str(temporary.relative_to(root)))
            shutil.rmtree(temporary)


def verify_directory(root, directory, expected):
    if directory.is_symlink():
        raise StagingError("Bundle directory cannot be a symlink")
    files = expected.get("files")
    if (not isinstance(files, dict) or not files
            or not all(isinstance(name, str) and isinstance(value, str) and re.fullmatch(r"[0-9a-f]{64}", value)
                       for name, value in files.items())
            or not isinstance(expected.get("sha256"), str) or not re.fullmatch(r"[0-9a-f]{64}", expected["sha256"])):
        raise StagingError("Bundle integrity metadata is missing or malformed")
    actual = safe_path(root, str(directory.relative_to(root)), "manifest.json").read_bytes()
    if read_json(actual) != expected:
        raise StagingError("Published release or bundle metadata changed; existing files preserved")
    for filename, checksum in expected["files"].items():
        data = safe_path(root, str(directory.relative_to(root)), filename).read_bytes()
        if digest(data) != checksum:
            raise StagingError("Stored bundle is corrupt; existing files preserved")


def json_bytes(value):
    return (json.dumps(value, indent=2, sort_keys=True) + "\n").encode("utf-8")


def stage_target(root, target, manifest, artifacts, sources):
    """Retain each target's first backup, independently of later manual installations."""
    destination = safe_path(root, "targets", target["name"], manifest["mod_id"], manifest["tag"])
    if not destination.exists():
        backups = {"backups/" + path.name: value for path, value in sources}
        files = {**artifacts, **backups}
        target_manifest = {
            **manifest, "target": target["name"], "installed_dir": target["installed_dir"],
            "backups": [str(path) for path, _ in sources],
            "files": {name: digest(value) for name, value in files.items()},
        }
        publish_directory(root, destination, {**files, "manifest.json": json_bytes(target_manifest)})
        return destination, True

    manifest_path = safe_path(root, str(destination.relative_to(root)), "manifest.json")
    existing = read_json(manifest_path.read_bytes())
    backups = existing.get("backups")
    stored_files = existing.get("files")
    if not isinstance(backups, list) or not isinstance(stored_files, dict):
        raise StagingError("Target backup metadata is missing")
    expected_files = manifest["files"].copy()
    for source in backups:
        if not isinstance(source, str):
            raise StagingError("Invalid recorded backup source")
        path = Path(source)
        if path.parent != Path(target["installed_dir"]) or not SAFE_NAME.fullmatch(path.name):
            raise StagingError("Recorded backup is outside the installed source directory")
        key = "backups/" + path.name
        if key in expected_files:
            raise StagingError("Duplicate backup source")
        expected_files[key] = stored_files.get(key)
    if set(stored_files) != set(expected_files):
        raise StagingError("Target must describe all artifact and backup files")
    expected = {
        **manifest, "target": target["name"], "installed_dir": target["installed_dir"],
        "backups": backups, "files": expected_files,
    }
    verify_directory(root, destination, expected)
    return destination, False


def replace_json(path, value):
    """Replace a validated staging pointer only after all target bundles have been verified."""
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(dir=path.parent, prefix=".latest-", delete=False) as stream:
            temporary = Path(stream.name)
            stream.write(json_bytes(value))
            stream.flush()
            os.fsync(stream.fileno())
        temporary.replace(path)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def stage_module(root, minecraft, module, fetch):
    release = read_json(fetch("https://api.github.com/repos/" + module["repository"] + "/releases/latest", MAX_JSON_BYTES))
    jar, checksum = select_release(module, release)
    tag = release["tag_name"]
    sources, highest = source_snapshots(module)
    module_cache = safe_path(root, "bundles", module["id"])
    if module_cache.exists():
        for directory in module_cache.iterdir():
            if STABLE_TAG.fullmatch(directory.name):
                highest = max(highest, version_tuple(directory.name[1:]))
    if version_tuple(tag[1:]) < highest:
        return {"mod": module["id"], "status": "skipped", "tag": tag, "reason": "Would stage an older version"}
    identity = {"repository": module["repository"], "release_id": release["id"], "tag": tag,
                "asset": jar["name"], "checksum_asset": checksum["name"], "mod_id": module["id"],
                "version": tag[1:] + module["version_suffix"], "environment": module["environment"], "minecraft": minecraft,
                "asset_size": jar["size"], "asset_digest": jar.get("digest"), "checksum_digest": checksum.get("digest")}
    bundle = safe_path(root, "bundles", module["id"], tag)
    if bundle.exists():
        saved = read_json(safe_path(root, "bundles", module["id"], tag, "manifest.json").read_bytes())
        if not isinstance(saved.get("files"), dict) or set(saved["files"]) != {jar["name"], checksum["name"]}:
            raise StagingError("Cached bundle must describe both JAR and checksum file")
        expected = {**identity, "sha256": saved.get("sha256"), "files": saved.get("files")}
        verify_directory(root, bundle, expected)
        data = safe_path(root, "bundles", module["id"], tag, jar["name"]).read_bytes()
        checksums = safe_path(root, "bundles", module["id"], tag, checksum["name"]).read_bytes()
        checked_asset(jar, data)
        checked_asset(checksum, checksums)
    else:
        checksums = fetch(checksum["browser_download_url"], MAX_CHECKSUM_BYTES)
        checked_asset(checksum, checksums)
        data = fetch(jar["browser_download_url"], MAX_JAR_BYTES)
        checked_asset(jar, data)
    sha256 = checksum_for(checksums, jar["name"])
    if digest(data) != sha256:
        raise StagingError("Published SHA256 checksum mismatch")
    validate_jar(data, module, identity["version"], minecraft)
    artifact_files = {jar["name"]: data, checksum["name"]: checksums}
    manifest = {**identity, "sha256": sha256, "files": {name: digest(value) for name, value in artifact_files.items()}}
    if not bundle.exists():
        publish_directory(root, bundle, {**artifact_files, "manifest.json": json_bytes(manifest)})
    changed = False
    targets = []
    for target in module["targets"]:
        destination, created = stage_target(root, target, manifest, artifact_files, sources[target["name"]])
        changed = changed or created
        targets.append(str(destination))
    latest = safe_path(root, "latest", module["id"] + ".json")
    pointer = {"tag": tag, "sha256": sha256, "targets": targets}
    if not latest.exists() or read_json(latest.read_bytes()) != pointer:
        replace_json(latest, pointer)
    return {"mod": module["id"], "status": "staged" if changed else "unchanged", "tag": tag, "sha256": sha256, "targets": targets}


def check_updates(config, fetch=fetch_github):
    """Check both modules independently; failed modules retry at the next timer activation."""
    root = validate_config(config)
    root.mkdir(parents=True, exist_ok=True)
    results = []
    for module in config["modules"]:
        try:
            results.append(stage_module(root, config["minecraft_version"], module, fetch))
        except (StagingError, OSError) as error:
            results.append({"mod": module["id"], "status": "error", "error": str(error)})
    return results


@contextmanager
def exclusive_check(root):
    """Kernel-managed locks are released even if a process crashes; simultaneous checks never publish twice."""
    root.mkdir(parents=True, exist_ok=True)
    with safe_path(root, ".check.lock").open("a+b") as stream:
        if os.name == "nt":
            import msvcrt
            if stream.tell() == 0:
                stream.write(b"0")
                stream.flush()
            stream.seek(0)
            msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
            try:
                yield
            finally:
                stream.seek(0)
                msvcrt.locking(stream.fileno(), msvcrt.LK_UNLCK, 1)
        else:
            import fcntl
            fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
            try:
                yield
            finally:
                fcntl.flock(stream, fcntl.LOCK_UN)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, required=True)
    args = parser.parse_args()
    try:
        config = read_json(args.config.read_bytes())
        root = validate_config(config)
        with exclusive_check(root):
            results = check_updates(config)
        for result in results:
            print(json.dumps(result, ensure_ascii=False), flush=True)
        return int(any(result["status"] == "error" for result in results))
    except (StagingError, OSError) as error:
        print(json.dumps({"status": "error", "error": str(error)}), flush=True)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
