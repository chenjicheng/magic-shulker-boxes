# Scheduled release staging

[简体中文](../release-staging.md) | **English**

`scripts/stage_releases.py` is a standalone server tool shared by **Magic Shulker Boxes** and the client-only **Chest Count Overlay**. A systemd timer checks both repositories every five minutes, validates public stable releases and retains installable JARs, checksum receipts and original backups in a separate directory. Administrators activate versions and restart servers separately.

## Scope

- Accepts published stable `vMAJOR.MINOR.PATCH` releases. Drafts and prereleases are refused.
- Defaults to Minecraft **1.21.11** and checks Fabric mod ID, complete version, environment and Minecraft requirement. Installed/cached newer versions prevent staging a downgrade.
- Verifies checksum receipts plus GitHub asset digests when available. Asset URLs must match the configured repository/release; redirects are restricted to GitHub asset hosts.
- Supports `SHA256SUMS` and CCO 1.0.1's `<JAR basename>.sha256`. Legacy entries may include the fixed `release-artifacts/` prefix, which is never used as a destination path.
- Installed server mods, live AutoModpack sources, configuration and worlds remain read-only. MSB may have server and client targets; CCO uses client targets.
- Each module is checked independently. Any failure produces a nonzero service exit and a journal entry, with retries on later timer activations. No external messages or user notifications are sent.

## Install

Requires Linux, systemd and Python **3.11+**, with no additional Python packages or GitHub token for public releases. Adapt the real source paths in `deploy/sources.example.conf` before installation. The example maps `mc-server`, `cmc-server` and `mc-client` into the service's private `/run/minecraft-mod-stager-sources/<name>` namespace. This preserves protected home-directory permissions. Sources must exist and JARs must be readable; do not add a client source for a server without AutoModpack.

These commands are for a first installation. If files already exist, inspect and back up the current script, configuration and units, preserving local source mappings before an update.

```sh
sudo install -d -m 755 /usr/local/lib/minecraft-mod-stager
sudo install -d -m 755 /etc/systemd/system/minecraft-mod-stager.service.d
sudo install -m 644 scripts/stage_releases.py /usr/local/lib/minecraft-mod-stager/stage_releases.py
sudo install -m 644 deploy/minecraft-mod-stager.example.json /etc/minecraft-mod-stager.json
# Install the copy whose source paths you have adapted to this machine.
sudo install -m 644 deploy/sources.example.conf /etc/systemd/system/minecraft-mod-stager.service.d/sources.conf
sudo install -m 644 deploy/minecraft-mod-stager.service deploy/minecraft-mod-stager.timer /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemd-analyze verify /etc/systemd/system/minecraft-mod-stager.service /etc/systemd/system/minecraft-mod-stager.timer
sudo systemctl start minecraft-mod-stager.service
sudo journalctl -u minecraft-mod-stager.service -n 20 --no-pager
# Enable scheduled checks after the first real check succeeds.
sudo systemctl enable --now minecraft-mod-stager.timer
```

`DynamicUser` grants writes only to the state directory; `BindReadOnlyPaths` exposes only configured installed sources. Downloaded code and Minecraft administration commands are never executed. `OnCalendar=*-*-* *:00/5:00` schedules five-minute boundaries with up to 15 seconds of scheduling slack. `Persistent=yes` catches up once after missed checks while the host was off.

## Configuration and bundles

Use `deploy/minecraft-mod-stager.example.json` as the schema for `/etc/minecraft-mod-stager.json`:

| Field | Meaning |
| --- | --- |
| `cache_dir` | Separate absolute state directory; the unit uses `/var/lib/minecraft-mod-stager` |
| `minecraft_version` | Target Minecraft version |
| `modules[].id` / `environment` | Expected Fabric ID and environment (`*` for MSB, `client` for CCO) |
| `repository` | Fixed GitHub `owner/name` |
| `archive_prefix` / `version_suffix` | Construct the single installable JAR name from its tag; sources are excluded |
| `targets[].name` / `installed_dir` | Target name and read-only source path inside the service namespace |

Each check makes one release API request per module. Verified assets are cached, with downloads only for new assets. API JSON, JAR and checksum limits are 2 MiB, 32 MiB and 64 KiB. An incompatible latest release reports an error and preserves existing bundles; there is no older-release fallback.

```text
bundles/<mod-id>/<tag>/              # One verified download
targets/<target>/<mod-id>/<tag>/     # JAR, receipt, manifest.json and backups/
latest/<mod-id>.json                # Pointer published after all configured targets succeed
```

Manifests record repository, release ID, version, environment, SHA256 and per-file hashes. Target bundles also record backup origins. Backups describe the first staging moment and are never replaced by repeat checks. If one target fails, completed targets remain and later checks resume from verified cached bytes. A complete temporary directory is renamed into place; kernel-managed locks prevent concurrent checks and release automatically on process exit.

With `DynamicUser`, systemd normally stores persistence under `/var/lib/private`; administrators use `/var/lib/minecraft-mod-stager` with `sudo`. `/run` source mounts exist only during the service invocation and refer to physical paths in `sources.conf`.

## Inspect and activate

```sh
systemctl list-timers minecraft-mod-stager.timer
sudo systemctl show minecraft-mod-stager.service -p Result -p ExecMainStatus
sudo journalctl -u minecraft-mod-stager.service -n 30 --no-pager
sudo cat /var/lib/minecraft-mod-stager/latest/magic_shulker_boxes.json
sudo cat /var/lib/minecraft-mod-stager/latest/chest_count_overlay.json
sudo systemctl start minecraft-mod-stager.service
```

Journal statuses are `staged`, `unchanged`, `skipped` and `error`. Conflicting publication metadata or corrupt stored bundles are preserved. Verify original release assets first; back up and move aside a confirmed corrupt staging directory before retrying. Keep prior backups and do not clean shared caches or live worlds.

`sudo systemctl disable --now minecraft-mod-stager.timer` stops scheduled checks without deleting bundles or changing installed mods. Stop `minecraft-mod-stager.service` separately if a check is still running.

Staging does not activate an update. The manual command below applies the selected server/client JARs together, restarts servers requiring activation, checks their actual loaded versions and publishes the client manifest. CCO stays client-only; administrators update packwiz separately.

## Manual deployment command

`scripts/deploy_releases.py` and `deploy/minecraft-mod-deploy` implement an administrator-invoked Docker deployment. Adapt real paths in `deploy/minecraft-mod-deployer.example.json` for installed mods, logs, MSB configuration/preferences and the AutoModpack manifest. Configuration must be root-owned and not writable by others.

```sh
sudo install -m 644 scripts/deploy_releases.py /usr/local/lib/minecraft-mod-stager/deploy_releases.py
sudo install -m 755 deploy/minecraft-mod-deploy /usr/local/bin/minecraft-mod-deploy
sudo install -m 600 deploy/minecraft-mod-deployer.example.json /etc/minecraft-mod-deployer.json
```

Add to the administrator's `~/.bash_aliases`:

```sh
alias mod-deploy='sudo /usr/local/bin/minecraft-mod-deploy'
```

New shells load it; use `. ~/.bash_aliases` in an existing shell.

```sh
mod-deploy --dry-run   # Read-only validation/plan; no stop, replacement, backup or manifest publication
mod-deploy            # Apply both mods
mod-deploy msb        # MSB only
mod-deploy cco        # Client distribution only, without restarting server mods
```

The command pins completed staging pointers, verifies all bundle hashes and JAR metadata, then pauses checking. Affected MSB servers stop gracefully; current JARs, MSB settings/preferences and client manifest are backed up again. Server/client JARs are replaced together, with old versions removed only after backup and new files retaining the directory's user/group and readable permissions. Readiness requires RCON `list` and the complete expected MSB version in the current startup log. `automodpack generate` is followed by manifest filename/size/SHA1 verification against installed client bytes. An already-matching deployment returns `unchanged`.

Selected servers and the client-pack host must be running beforehand. Graceful stop waits without force-killing on timeout. Interruptions or startup/manifest failures attempt to restore modified JARs, settings, owners/groups, client sources and previous running states, plus the previously active check timer. Backups and receipts remain under `/var/lib/minecraft-mod-deploy/backups/<time-unique-id>/`. Failed recovery is reported explicitly.

Rollback restores prior disk files and process state, not worlds. If loaded versions already differed from disk before deployment, a rollback start loads the prior disk version. Continue existing world-backup arrangements for maintenance. The command does not push Git, publish an application or update packwiz.

## Verification

```sh
python3 -m unittest discover -s scripts -p 'test_*.py' -v
python3 -m py_compile scripts/stage_releases.py
```

Tests use constructed real JARs/checksum receipts and isolated filesystems for both mods, legacy CCO checksums, original backups, invalid metadata/hashes/sizes, idempotency, downgrade prevention, independent failures, partial-target recovery and locks. Real symlink escape checks require POSIX; run on Linux with no skips. Also verify real public assets, staged SHA256, the next timer activation and unchanged installed-file hashes/Minecraft start times after installation.
