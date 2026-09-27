# Building and testing

[简体中文](../development.md) | **English**

This standalone Java/Fabric project targets Minecraft 1.21.11. Storage runs on the logical server. The same JAR supports dedicated servers and singleplayer clients. Optional local-preference synchronization uses a separate client entrypoint; a dedicated server does not load client classes.

## Build

Install JDK 21, set `JAVA_HOME`, and run:

```powershell
.\gradlew.bat build
```

On Linux/macOS:

```sh
sh gradlew build
```

Gradle Wrapper pins version 9.2.1 and verifies the distribution SHA256. Minecraft, Fabric Loader, Fabric API, and Loom versions are pinned in `gradle.properties`. The first build downloads dependencies.

`build` runs unit tests and dedicated-server GameTests, producing the installable and sources JARs in `build/libs`. Test worlds live in isolated directories under `build`; existing player worlds are not used.

## GitHub builds, releases and documentation

The canonical name is **Magic Shulker Boxes**, the stable mod ID is `magic_shulker_boxes`, and the repository is `chenjicheng/magic-shulker-boxes`. The ID follows the [Fabric metadata specification](https://wiki.fabricmc.net/documentation:fabric_mod_json_spec). Artifacts use this project's name-loader-version-game convention: `magic-shulker-boxes-fabric-0.1.0-alpha+mc1.21.11.jar`. Fabric does not mandate that filename.

`mod_version` in `gradle.properties` starts at `0.1.0-alpha`. Build artifacts and mod metadata append `+mc1.21.11`; the matching Git tag is `v0.1.0-alpha`. Versions with a prerelease suffix become GitHub prereleases. The project is MIT licensed.

- **CI** runs for branch pushes, pull requests and manual dispatch, reusing `build.yml`. Java 21 builds, unit tests and dedicated-server GameTests run both without Carpet and with Carpet 1.4.194, whose download is verified by a pinned SHA256.
- **Release** runs only on `v*` tag pushes. It requires a matching version and both test environments to pass, then publishes the tested mod JAR, sources JAR and `SHA256SUMS`. Notes come from `docs/releases/<mod_version>.md`. Only the separate publishing job has write permission.
- **Documentation** uses VitePress 1.6.4, Node 24 and the npm lockfile. Its underlying Vite is pinned to 6.4.3 for security fixes; recheck builds, search and preview when updating. Pull requests only build. Pushes to `main` deploy `docs/.vitepress/dist` to GitHub Pages with base `/magic-shulker-boxes/`.

To release, update `mod_version`, add bilingual release notes, run checks and commit to `main`. Wait for CI, then push the matching annotated tag:

```sh
git tag -a v0.1.0-alpha -m "Magic Shulker Boxes 0.1.0-alpha"
git push origin v0.1.0-alpha
gh run list --workflow release.yml
```

Actions invokes `gh` to upload assets to a draft before publishing it. Do not upload stale local artifacts or overwrite a published version; fix it with a new release. On initial repository setup, select **GitHub Actions** as the Pages source. No extra PAT or mod-hosting token is needed.

```sh
python -m unittest discover -s scripts -p 'test_*.py' -v
npm ci
npm run docs:build
npm run docs:preview
```

`scripts/release.py` checks the tag, metadata and license, rejects bundled test classes and optional dependencies, and selects only the two current artifacts. VitePress builds the public documents in `docs` with dead-link checks enabled. Caches, logs, local worlds and build outputs are ignored. Full user documentation has one source per language: `docs/guide.md` and `docs/en/guide.md`.

## Test commands

### Schematic refill protocol

`BoxOrder` ranks eligible boxes by the sum of count/stack-limit across their slots, breaking ties by inventory slot. Storage fills fuller boxes within the existing category order; `RefillSearch` and server-side `RefillNetwork` prefer emptier boxes and continue past unsafe sources. A box with 27 partial stacks is not treated as full. Nonstandard larger containers remain excluded.

`RefillSearch` performs read-only component-exact lookup. Optional mixins bracket Litematica's `WorldUtils.doEasyPlaceAction` and `EasyPlaceUtils.handleEasyPlace`, intercepting `InventoryUtils.schematicWorldPickBlock` only during Easy Place. Normal pick-block is unaffected. `LitematicaMixinPlugin` skips these client targets when Litematica is absent; neither builds nor dedicated servers require its JAR.

The `refill_v1` request contains only a box slot, inner slot and bounded item ID. `RefillNetwork` runs on the server thread, checks game mode, menu/cursor state, server switches and effective preferences, then reads actual items. Each player can make one request per 10 server ticks; delayed duplicates stop if matching materials are already available. `ShulkerRefill` plans extraction, relocation and splitting on copies and commits only a complete valid plan. Failure leaves inventory unchanged. Success uses ordinary inventory synchronization; optional failure notices use a rate-limited action bar.

Refilling never places blocks directly or bypasses Litematica's hotbar protections and placement checks. While awaiting synchronization, it suppresses the generic missing-material warning for that attempt. Holding the placement key continues the original pick-and-place flow.

`RefillSearchTest` samples read-only lookup over 36 full boxes (972 slots), reporting median/P95 without a hardware-dependent pass threshold. These figures do not measure multiplayer load or network latency. `RefillGameTests` exercise real server requests, mode/config refusal and item preservation.

Copy `tests/schematics/MSB-Refill.litematic` into the test instance's `schematics` directory. Load it at `100,101,100`; it contains cobblestone, oak planks and glass. `/function msb_test:refill` prepares a full inventory and platform. Verify extraction and a subsequent placement consuming one item, while the pickaxe, 16 empty boxes and unrelated materials remain intact. With personal settings permitted, `refill_blocked`, `refill_silent`, `refill_disabled` and `refill_enabled` test refusal, notification suppression, disabling and resetting respectively.

Run unit tests only:

```powershell
.\gradlew.bat test -x runGameTest
```

Run real server pickup tests:

```powershell
.\gradlew.bat runGameTest
```

Run with an official Carpet 1.21.11 release JAR:

```powershell
.\gradlew.bat build -PcarpetJar="C:/path/to/fabric-carpet-1.21.11-1.4.194+v251223.jar"
```

`carpetJar` adds a local development/test runtime dependency and never bundles Carpet into the mod. During each game test, Carpet's real stacking limit is set to 64 and its mixin is checked; the previous setting is restored afterward.

Unit reports are in `build/reports/tests/test/index.html`. GameTest results appear in the console and test-run logs. Tests use actual server players, inventories, and item entities. They cover vanilla-first pickup, partial capacity, ownership/delay checks, stacked-box fallback, splitting into a free slot, configuration disabling, both space-making modes, partial stacks, repeated mixed pickups, and recursion prevention.

Additional tests cover per-player persistence and isolation, inheritance from server defaults, independent server restrictions on pickup storage and refilling, immediate pickup-policy changes, player command permissions, administrator policy revocation, client attempts to change server policy, bounded network payloads, and matching translation keys/placeholders.

### Manual game checks

`tests/manual-datapack` is a Minecraft 1.21.11 fixture. Copy its contents into a **new dedicated test world's** `datapacks/msb-manual-tests`, then run `/reload`. Commands and Carpet are required. These fixtures clear the invoking player's inventory, delete nearby item entities, and set peaceful difficulty. Do not run them in a normal gameplay world.

| Command | Expected behavior |
| --- | --- |
| `/function msb_test:matching` | Blue box changes from 63 cobblestone to 64 + 4; earlier empty/mixed boxes remain unchanged |
| `/function msb_test:mixed` | Reuse the red mixed box; the stack of 16 empty boxes remains unchanged |
| `/function msb_test:partial` | A box with one item of free capacity accepts one of five cobblestone; four stay on the ground |
| `/function msb_test:split` | Set `onlyWhenInventoryFull=false` first; 16 named blue boxes become 15 empty boxes and one box containing five cobblestone |
| `/function msb_test:auto_space` | No free slot: three stones are displaced; stones, cobblestone, and gravel enter one box, with box counts 15 + 1 |
| `/function msb_test:fallback` | Cobblestone stays on the ground with `allowOtherSingleTypeBoxes=false`; enabling it allows storage in a dirt-only box |

Run `auto_space` with both `MOVE_TO_BOX` and `DROP_AND_PICKUP`. The first main-inventory slot should contain a box holding stone 3, cobblestone 5, and gravel 7. With `DISABLED`, the box stack remains 16 and the pickups remain on the ground. Apply changed server configuration with `/msb admin reload` or restart, and restore the desired settings afterward.

For personal-policy verification, toggle `/msb admin player-settings true/false`, set your own `enabled=false`, inspect `/msb show`, and repeat a pickup fixture. The personal off setting applies only while the server permits it. Switch Minecraft between Simplified Chinese and English to check messages.

## Code map

All Java files below are under `src/main/java/dev/magicshulkerboxes`.

| File | Responsibility |
| --- | --- |
| `MagicShulkerBoxes.java` | Initialization, active server config, effective player config and lifecycle |
| `ConfigFile.java` | Strict configuration validation, serialization, atomic writes |
| `StorageConfig.java` | Storage behavior and defaults |
| `ServerConfig.java` | Server-only personal-settings policy, disabled by default |
| `ShulkerStorage.java` | Classification, ordering, capacity, splitting and relocation transactions |
| `PickupRelocation.java` | Synchronous reservation for dropped-item recollection |
| `PlayerSettingsStore.java` | World-scoped UUID overrides, caching and inheritance |
| `SettingsCommands.java` | Player commands and administrator authorization |
| `SettingsNetwork.java` | Policy notification and optional preference synchronization |
| `client/MagicShulkerBoxesClient.java` | Client-only local preference synchronization |
| `Messages.java` | Bilingual resources and fallback text for unmodded clients |
| `mixin/ItemEntityMixin.java` | Wrap the `Inventory.add` call inside `ItemEntity.playerTouch` |

Unit tests live in `src/test/java/dev/magicshulkerboxes`; actual pickup tests are in `src/gametest/java/dev/magicshulkerboxes`.

## Invariants and compatibility

The mixin runs after vanilla server-side, pickup-delay, and ownership checks. Vanilla still handles pickup animation, statistics, and entity removal. The generic `Inventory.add` method is not intercepted, avoiding side effects on crafting and unrelated container operations.

Storage plans changes on copied contents and commits only accepted items. A stacked box loses exactly one item; the remaining boxes retain their original contents. A full box does not occupy a free slot or get split. Containers with more than the vanilla 27 slots are skipped intact instead of truncated.

Relocation reserves capacity for the entire displaced slot before accepting incoming items. Drop mode commits inventory changes only after the world accepts the spawned entity. Immediate recollection may write only into the exact reserved single box; replacement of that box leaves the dropped items intact. A `finally` block clears temporary reservations, and unrecollected entities carry a persistent marker prohibiting another relocation attempt.

Inventory changes use vanilla synchronization. Optional settings use the `policy_v1` and `preferences_v1` channels, with capability checks before sending. Fabric's object-payload callbacks run on the game thread. A preference payload contains at most 4096 characters and no target UUID: the sender's identity comes from the connection. The server checks its policy, option allowlist, types, enum values, and size, and limits network updates to one per player per 20 ticks. Server-only installations do not require clients to support these channels.

Effective settings first use `allowPlayerSettings` to select enforced defaults or individual field overrides, then apply the server pickup (`enabled`) and refill (`schematicRefill`) restrictions independently. Both sides share `ConfigFile.apply`. Disabling pickup never bypasses personal refill preferences, and neither server restriction can be overridden by a personal on setting. Player files are isolated by world and UUID, cached, and written through a temporary file with atomic replacement when supported. Malformed files are retained; load failures fall back to server settings and are logged. Reload validates a replacement before changing active settings and clearing caches.

Language resources are in `assets/magic_shulker_boxes/lang`. `translatableWithFallback` provides readable text for clients without this mod, chosen from the language reported by each player. Other mods can still cancel pickup entirely. Mods altering the pickup path or container structure require separate compatibility checks.

## Optional GUI integration

`ModMenuIntegration` exposes the configuration factory and checks for YACL before referencing `SettingsGui`. Both libraries use `modCompileOnly` and are not bundled. Add `-PwithConfigGui` for a development client. Default GameTests omit GUI libraries to verify dedicated-server compatibility.

YACL bindings edit a detached `SettingsDraft`. Personal boolean fields have three states; Inherit removes the key. `SettingsSession` tracks connection/policy revisions and pending request IDs, allowing only a matching acknowledgement to update the local file. `ClientSettings` handles persistence, feedback, timeouts and integrated-server saves.

`EditorNetwork` adds optional channels: `editor_state_v1` (policy/defaults), `editor_save_v1` (request ID/overrides), and `editor_result_v1` (correlated success/rejection). JSON is bounded to 4096 characters, identity comes from the connection, and the server checks policy and limits GUI saves to one per player per 20 ticks with an explicit busy response. Legacy synchronization remains available. No GUI payload can write administrator settings. Integrated-host edits execute on the server thread and compare the draft baseline before writing to avoid overwriting external changes.

Besides unit tests and Carpet GameTests, verify the title-screen Mod Menu entry, bilingual YACL layout, save/cancel/inheritance, enforced/revoked policy, and local host settings. `SettingsEditorTest` covers draft isolation, inheritance and stale replies. The GUI GameTest checks actual effective player settings, rejected policy fields and explicit rate limiting.

## Official references

- [Official Mod Menu integration API](https://github.com/TerraformersMC/ModMenu#java-api)
- [Official YACL documentation](https://docs.isxander.dev/yet-another-config-lib)

- [Fabric 1.21.11 setup and Java 21](https://docs.fabricmc.net/1.21.11/develop/getting-started/setting-up)
- [Fabric 1.21.11 release notes](https://fabricmc.net/2025/12/05/12111.html)
- [Fabric Loader JUnit and GameTest](https://docs.fabricmc.net/1.21.11/develop/automatic-testing)
- [Fabric commands and permissions](https://docs.fabricmc.net/1.21.11/develop/commands/basics)
- [Fabric networking](https://docs.fabricmc.net/1.21.11/develop/networking)
- [Carpet 1.21.11 shulker stacking implementation](https://github.com/gnembon/fabric-carpet/blob/1.21.11/src/main/java/carpet/mixins/ItemStack_stackableShulkerBoxesMixin.java)
- [Official Carpet 1.4.194 release](https://github.com/gnembon/fabric-carpet/releases/tag/1.4.194)
