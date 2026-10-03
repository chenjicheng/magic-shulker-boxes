"""Publish the exact tested release JAR to Modrinth, with safe repeat runs."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import sys
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urljoin, urlsplit, urlunsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener
from uuid import uuid4

from release import metadata


API = "https://api.modrinth.com/v2"
USER_AGENT = "chenjicheng/magic-shulker-boxes (https://github.com/chenjicheng/magic-shulker-boxes)"
ENVIRONMENT = "server_only_client_optional"
DEPENDENCIES = [
    ("P7dR8mSH", "required"),  # Fabric API
    ("mOgUt4GM", "optional"),  # Mod Menu
    ("1eAoo2KR", "optional"),  # YACL
    ("O7RBXm3n", "optional"),  # Inventory Profiles Next
    ("bEpr0Arc", "optional"),  # Litematica
]


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


class ModrinthClient:
    def __init__(self, token, opener=None):
        if not token or any(character.isspace() for character in token):
            raise ValueError("MODRINTH_TOKEN must contain a nonempty token without whitespace")
        self.token = token
        self.opener = opener or build_opener(NoRedirect())

    def request(self, method, route, body=None, content_type=None):
        headers = {"Authorization": self.token, "User-Agent": USER_AGENT, "Accept": "application/json"}
        if content_type:
            headers["Content-Type"] = content_type
        request = Request(API + route, data=body, headers=headers, method=method)
        try:
            with self.opener.open(request, timeout=60) as response:
                data = response.read()
                return json.loads(data) if data else None
        except HTTPError as error:
            # Error bodies and exception details may reflect request headers. Never print them.
            error.close()
            raise RuntimeError(f"Modrinth {method} {route} failed: HTTP {error.code}") from None
        except (URLError, TimeoutError, OSError):
            raise RuntimeError(f"Modrinth {method} {route} failed: network error; inspect remote state before retrying") from None

    def get(self, route):
        return self.request("GET", route)

    def create_version(self, payload, jar):
        boundary = "modrinth-" + uuid4().hex
        body = (
            f'--{boundary}\r\nContent-Disposition: form-data; name="data"\r\n'
            'Content-Type: application/json; charset=utf-8\r\n\r\n'
        ).encode() + json.dumps(payload, ensure_ascii=False).encode("utf-8") + (
            f'\r\n--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{jar.name}"\r\n'
            'Content-Type: application/java-archive\r\n\r\n'
        ).encode() + jar.read_bytes() + f"\r\n--{boundary}--\r\n".encode()
        return self.request("POST", "/version", body, f"multipart/form-data; boundary={boundary}")


def changelog_links(notes, tag):
    base = f"https://chenjicheng.github.io/magic-shulker-boxes/releases/{tag[1:]}.html"

    def replace(match):
        parts = urlsplit(urljoin(base, match[2]))
        path = parts.path.removesuffix(".md") + ".html" if parts.path.endswith(".md") else parts.path
        return match[1] + urlunsplit(parts._replace(path=path)) + match[3]

    return re.sub(r"(\[[^\]\n]*\]\()(\.\.?/[^\s)]+)(\))", replace, notes)


def release_payload(root, tag, dist, project_id):
    info = metadata(root, tag)
    if not re.fullmatch(r"[A-Za-z0-9]{1,64}", project_id or ""):
        raise ValueError("MODRINTH_PROJECT_ID must be a Modrinth project ID")
    jar = dist / f"{info['stem']}.jar"
    checksums = {}
    for line in (dist / "SHA256SUMS").read_text(encoding="utf-8").splitlines():
        match = re.fullmatch(r"([a-f0-9]{64})  ([a-zA-Z0-9+_.-]+\.jar)", line)
        if not match or match[2] in checksums:
            raise ValueError("Invalid or duplicate release checksum entry")
        checksums[match[2]] = match[1]
    if checksums.get(jar.name) != hashlib.sha256(jar.read_bytes()).hexdigest():
        raise ValueError("Installable JAR is missing from SHA256SUMS or its checksum differs")
    notes = changelog_links((dist / "release-notes.md").read_text(encoding="utf-8-sig"), info["tag"])
    suffix = info["tag"].partition("-")[2]
    channel = "alpha" if re.match(r"alpha(?:[.-]|$)", suffix) else "beta" if suffix else "release"
    payload = {
        "project_id": project_id, "version_number": info["version"], "name": info["title"],
        "changelog": notes, "version_type": channel, "game_versions": [info["minecraft"]],
        "loaders": ["fabric"], "environment": ENVIRONMENT, "featured": channel == "release",
        "status": "listed", "file_parts": ["file"], "primary_file": "file",
        "dependencies": [{"project_id": project, "dependency_type": kind}
                         for project, kind in DEPENDENCIES],
    }
    return payload, jar


def verify_version(remote, payload, jar):
    fields = ("project_id", "version_number", "game_versions", "loaders", "version_type", "changelog")
    if any(remote.get(field) != payload[field] for field in fields):
        raise ValueError("Existing Modrinth version metadata differs; published versions are never overwritten")
    # Environment is present on current v2 responses; reject a conflicting reported value.
    if remote.get("environment", ENVIRONMENT) != payload["environment"]:
        raise ValueError("Modrinth version environment differs")
    dependencies = {(item.get("project_id"), item.get("dependency_type"))
                    for item in remote.get("dependencies", [])}
    expected_dependencies = {(item["project_id"], item["dependency_type"]) for item in payload["dependencies"]}
    if dependencies != expected_dependencies:
        raise ValueError("Modrinth version dependencies differ")
    primary = [file for file in remote.get("files", []) if file.get("primary")]
    if len(primary) != 1 or primary[0].get("filename") != jar.name or (
        primary[0].get("hashes", {}).get("sha512") != hashlib.sha512(jar.read_bytes()).hexdigest()
    ):
        raise ValueError("Modrinth version primary file differs; published files are never overwritten")


def publish(root, tag, dist, project_id, client):
    payload, jar = release_payload(root, tag, dist, project_id)
    versions = client.get(f"/project/{quote(project_id, safe='')}/version")
    existing = [version for version in versions if version.get("version_number") == payload["version_number"]]
    if len(existing) > 1:
        raise ValueError("Multiple Modrinth versions have the same version number")
    if existing:
        verify_version(existing[0], payload, jar)
        return {"id": existing[0]["id"], "created": False}
    created = client.create_version(payload, jar)
    verified = client.get(f"/version/{quote(created['id'], safe='')}")
    verify_version(verified, payload, jar)
    return {"id": verified["id"], "created": True}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--dist", type=Path, default=Path("dist"))
    parser.add_argument("--project-id", default=os.environ.get("MODRINTH_PROJECT_ID", ""))
    args = parser.parse_args()
    try:
        client = ModrinthClient(os.environ.get("MODRINTH_TOKEN", ""))
        result = publish(Path(__file__).resolve().parents[1], args.tag, args.dist, args.project_id, client)
    except (ValueError, OSError, RuntimeError) as error:
        print(f"Modrinth publishing failed: {error}", file=sys.stderr)
        sys.exit(1)
    print(json.dumps(result))
