"""Validate and stage exactly the artifacts produced for one public release."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
from zipfile import ZipFile


def metadata(root, tag=""):
    properties = dict(
        line.split("=", 1) for line in (root / "gradle.properties").read_text(encoding="utf-8").splitlines()
        if "=" in line and not line.lstrip().startswith("#")
    )
    version = properties["mod_version"]
    minecraft = properties["minecraft_version"]
    base = properties["archives_base_name"]
    if not re.fullmatch(r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:-[a-z][a-z0-9.-]*)?", version):
        raise ValueError("mod_version must be a semantic release version")
    if not re.fullmatch(r"[0-9]+(?:\.[0-9]+)+", minecraft) or not re.fullmatch(r"[a-z][a-z0-9-]+", base):
        raise ValueError("Unsafe Minecraft version or archive base name")
    if tag and tag != f"v{version}":
        raise ValueError(f"Tag {tag!r} does not match mod_version v{version}")
    return {
        "tag": f"v{version}", "version": f"{version}+mc{minecraft}",
        "minecraft": minecraft, "stem": f"{base}-{version}+mc{minecraft}",
        "title": f"Magic Shulker Boxes {version} (Fabric {minecraft})",
        "prerelease": str("-" in version).lower(),
    }


def package(root, tag, output):
    info = metadata(root, tag)
    paths = [root / "build/libs" / f"{info['stem']}{suffix}.jar" for suffix in ("", "-sources")]
    notes = root / "docs/releases" / f"{info['tag'][1:]}.md"
    # Validate everything before creating an upload directory. Old build outputs are never globbed.
    for path in [*paths, notes]:
        if not path.is_file():
            raise FileNotFoundError(path)
    with ZipFile(paths[0]) as jar:
        manifest = json.loads(jar.read("fabric.mod.json"))
        expected = {"id": "magic_shulker_boxes", "name": "Magic Shulker Boxes",
                    "version": info["version"], "license": "MIT", "environment": "*"}
        if any(manifest.get(key) != value for key, value in expected.items()):
            raise ValueError("Built mod metadata does not match the release")
        if manifest.get("depends", {}).get("minecraft") != info["minecraft"]:
            raise ValueError("Built mod targets a different Minecraft version")
        if not any(name.startswith("LICENSE") for name in jar.namelist()):
            raise ValueError("MIT license is missing from the installable JAR")
        forbidden = ("carpet/", "com/terraformersmc/modmenu/", "dev/isxander/yacl3/", "META-INF/jars/")
        if any(name.startswith(forbidden) or name.endswith(("Tests.class", "Test.class")) for name in jar.namelist()):
            raise ValueError("Test code or optional dependencies were bundled")
    with ZipFile(paths[1]) as jar:
        if not any(name.endswith(".java") for name in jar.namelist()):
            raise ValueError("Sources JAR contains no Java source")
    if output.exists() and any(output.iterdir()):
        raise ValueError("Release staging directory must be empty")
    output.mkdir(parents=True, exist_ok=True)
    checksums = []
    for path in paths:
        shutil.copyfile(path, output / path.name)
        checksums.append(f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.name}\n")
    (output / "SHA256SUMS").write_text("".join(checksums), encoding="utf-8", newline="\n")
    shutil.copyfile(notes, output / "release-notes.md")
    return info


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["metadata", "package"])
    parser.add_argument("--tag", default="")
    parser.add_argument("--output", type=Path, default=Path("build/release"))
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    result = package(root, args.tag, args.output) if args.command == "package" else metadata(root, args.tag)
    print(json.dumps(result, indent=2))
    if os.environ.get("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as stream:
            for key, value in result.items():
                stream.write(f"{key}={value}\n")
