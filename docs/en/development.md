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

## Administrator player commands

Target-player administrator commands live in `SettingsCommands`: `/msb admin player <name|UUID> show|set|reset [option]` inherits the `COMMANDS_ADMIN` permission. Online names use the player list first, offline names use the vanilla identity cache/resolver, and UUIDs can address offline storage directly. Administrator edits read the complete overrides before changing or resetting them through `PlayerSettingsStore.save`; ordinary players still use `saveAllowed`. Runtime policy filtering in `resolve` is unchanged. Online targets receive `SettingsNetwork.acknowledge` to update their local preference file and invalidate existing editor drafts; offline edits retain the existing login upload behavior.

`AdminSettingsGameTests` cover target isolation, offline names/UUIDs, stored versus effective values, permissions, valid/invalid values, individual/full resets, policy locks, cache reload and corrupt-file preservation. `DedicatedClientGameTests` use a real TCP connection to check online name edits, UUID resets and client-file synchronization.

Server `admin show/set/reset/permission/permissions` commands use `ServerSettingsEdit` to validate a complete replacement before atomic `replaceConfig` and policy broadcast. `SettingsChat` emits vanilla `ClickEvent.SuggestCommand` with full-command hover text; clicking never submits. Administrator target buttons bind UUIDs. `CommandChatGameTests` cover every setting/permission button, read-only previews, individual resets, validation and OP3 permission. TCP acceptance clicks an actual chat button, checks its complete input and unchanged server value, then presses Enter and verifies persistence.

`SettingsKeybindings` dynamically register every boolean option as an initially unbound vanilla client key. Minecraft owns `options.txt`; bindings are absent from network payloads. `PreferenceToggle` flips one explicit or inherited boolean while retaining other choices. `ClientSettings` checks session state, field permissions and pending confirmation before reusing GUI save/recovery handling. TCP tests bind F8, toggle `craftRefill` twice, then revoke permission and verify the value is locked while the key remains bound. Default-on tests retain existing explicit `false`; behavior fixtures enable crafting explicitly and restore their configuration.

## Configuration versions and migration

Disk JSON now uses `configVersion: 2`. Metadata is excluded from `StorageConfig`, option lists, GUI drafts and network JSON. Unversioned files follow the 0.3.1 rule: copy exact bytes to `.pre-0.3.1.bak`, then reset to server defaults or empty personal overrides. Version 1 files are backed up to `.pre-0.3.2.bak`, validated, and rewritten atomically with `enabled` renamed to `pickupStorageEnabled`, retaining every other valid choice. An identical backup allows interrupted migration to resume; conflicting backups, invalid fields, unknown versions and unreadable files are never overwritten.

Server settings and client/server personal preferences use versioned `writeServer/writePreferences`; generic `write` is reserved for non-settings files such as recovery markers. Writes also inspect old destinations to preserve backups even before the first read. Client startup reads preferences; server `SERVER_STARTED` scans existing UUID player files and isolates individual failures. New choices preserve sparse inheritance and survive later loads. `pickupStorageEnabled` defaults to `false`, so behavior fixtures explicitly opt in; enable world/server pickup storage or a permitted personal override before manual pickup testing.

`ConfigMigrationTest` covers defaults, exact backups for both migrations, reset and rename, persistence, conflicting/unavailable backups, future versions and offline players. `ConfigMigrationGameTests` check real pickup, new GUI saves and absence of old settings receivers. Migration never touches items or world blocks.

## GitHub builds, releases and documentation

The canonical name is **Magic Shulker Boxes**, the stable mod ID is `magic_shulker_boxes`, and the repository is `chenjicheng/magic-shulker-boxes`. The ID follows the [Fabric metadata specification](https://wiki.fabricmc.net/documentation:fabric_mod_json_spec). Artifacts use this project's name-loader-version-game convention: `magic-shulker-boxes-fabric-0.1.0-alpha+mc1.21.11.jar`. Fabric does not mandate that filename.

`mod_version` in `gradle.properties` starts at `0.1.0-alpha`. Build artifacts and mod metadata append `+mc1.21.11`; the matching Git tag is `v0.1.0-alpha`. Versions with a prerelease suffix become GitHub prereleases. The project is MIT licensed.

- **CI** runs for branch pushes, pull requests and manual dispatch, reusing `build.yml`. Java 21 builds, unit tests and dedicated-server GameTests run both without Carpet and with Carpet 1.4.194, whose download is verified by a pinned SHA256.
- **Release** runs only on `v*` tag pushes. It requires a matching version and both test environments to pass, then independent jobs publish to GitHub and Modrinth. GitHub receives the tested mod JAR, sources JAR and `SHA256SUMS`; Modrinth receives the same installable JAR. Notes come from `docs/releases/<mod_version>.md`. Only GitHub publishing has repository write permission; the Modrinth token is injected only into its upload step.
- **Documentation** uses VitePress 1.6.4, Node 24 and the npm lockfile. Its underlying Vite is pinned to 6.4.3 for security fixes; recheck builds, search and preview when updating. Pull requests only build. Pushes to `main` deploy `docs/.vitepress/dist` to GitHub Pages with base `/magic-shulker-boxes/`.

When settings options or groups change, run `.\gradlew.bat runClientGameTest -PclientSmoke -PwithConfigGui` before publishing. It opens personal/world settings and verifies permission-aware saving; builds, server GameTests, IPN replenishment and TCP command checks do not exercise this GUI entry point.

To release, update `mod_version`, add bilingual release notes, run checks and commit to `main`. Wait for CI, then push the matching annotated tag:

```sh
git tag -a v0.1.0-alpha -m "Magic Shulker Boxes 0.1.0-alpha"
git push origin v0.1.0-alpha
gh run list --workflow release.yml
```

Actions invokes `gh` to upload GitHub assets to a draft before publishing it. Do not upload stale local artifacts or overwrite a published version; fix it with a new release. On initial repository setup, select **GitHub Actions** as the Pages source. GitHub publishing uses the built-in `GITHUB_TOKEN` and needs no extra GitHub PAT.

### Automatic Modrinth publishing

The project is [Magic Shulker Boxes](https://modrinth.com/mod/magic-shulker-boxes). Configure these under the GitHub repository's **Settings → Secrets and variables → Actions**:

- Variable `MODRINTH_PROJECT_ID`: Modrinth project ID `omzSygsa`.
- Secret `MODRINTH_TOKEN`: a dedicated CI Modrinth PAT with read-project, read-version and create-version permissions. Use a separate token to create or edit the project; never put that setup token in CI. Keep tokens out of the repository and command-line arguments.

Both `publish-modrinth` and GitHub publishing depend on `verify`, independently. `scripts/modrinth.py` revalidates the tag and installable JAR's SHA256, uploading only that exact file. Minecraft and release versions come from `gradle.properties`. Stable versions use `release`, `-alpha` uses `alpha`, and other prereleases use `beta`. Relative documentation links in release notes become public documentation links. Fabric API is required; Mod Menu, YACL, IPN and Litematica are optional. The environment requires installation on the server and supports optional client installation; enhanced client features still need the mod on both sides.

After uploading, the script reads back the version and verifies its primary file's SHA512, metadata, notes and dependencies. Repeated runs skip an identical existing version. Conflicting files or metadata fail without overwriting anything. Failed network writes never blindly repeat POST; a rerun first inspects remote versions. Missing credentials, project ID or artifacts, and failed checksums produce explicit failures.

If only Modrinth fails, choose **Re-run failed jobs** in Actions to retain the successful GitHub release. A new project must separately be submitted for Modrinth review; creating a draft and uploading a version do not make it public.

```sh
python -m unittest discover -s scripts -p 'test_*.py' -v
npm ci
npm run docs:build
npm run docs:preview
```

`scripts/release.py` checks the tag, metadata and license, rejects bundled test classes and optional dependencies, and selects only the two current artifacts. VitePress builds the public documents in `docs` with dead-link checks enabled. Caches, logs, local worlds and build outputs are ignored. Full user documentation has one source per language: `docs/guide.md` and `docs/en/guide.md`.

## Menu storage, sources and field permissions

`MenuStorageMixin` wraps only server menu `clicked`, without collecting inside `moveItemStackTo`. The complete original click, source persistence and trade callbacks finish before `MenuStorage.collectDeposited` organizes actual receipts. Shift transfers, source hotbar swaps and tracked cursor deposits share this path. Inventory rearrangement, automatic trade-selection refunds and crafting menus are excluded. Quantities are bounded by both the slot increase and the whole inventory's net increase for identical components. Planning moves existing stock, verifies live inventory before committing, and never recreates an unreceived source from a snapshot.

`ContainerOwnership` verifies the actual owners of external menu containers. Block entities must be the current world's actual objects at their positions; entities must remain in that world; both halves of a compound must be independent, and a trade container must belong to the current live merchant. The player's own ender chest and vanilla temporary crafting buffers have explicit owners. Unknown or carried-item menus retain vanilla behavior and pause MSB carried-box storage writes while open. Matching contents or `slot.container != inventory` does not prove independent storage.

`ItemEntityMixin` observes inventory and the real remainder only where vanilla reaches `Inventory.add` after delay and owner checks, without changing its return value. `PickupStorage` runs after the complete `playerTouch` returns. Vanilla restores a removed entity's stack count for callbacks; that stack is not reusable stock. Only a live entity with the same stack reference and observed remainder count may supply additional storage. The real entity stack is decremented directly; no snapshot creates stock. Extra receipt feedback is emitted once for its actual quantity only when vanilla did not already emit pickup feedback.

`ItemBackedMenuGameTests` reproduces the old server-side 5→10 duplication with a generic menu retaining the original box object. It covers serialization round trips, reopening, cursor and hotbar extraction, pickup while editing, source/pickup callback order, old-item rearrangement and a storage-disabled control. Ordinary storage fixtures use `TestContainers` for real world-owned chests: a `SimpleContainer` alone does not establish independent ownership. Real client smoke tests also use native network clicks to verify that the five extracted items stay loose without duplication.

`RefillSources.View` combines real player inventory and own ender storage. Encoded slots 100–126 denote ender slots; inner slot -1 denotes a direct item. This avoids the 1.21.11 body and saddle equipment slots 41/42. Reserved gaps cannot receive items. Copy planning retains source areas and commits crafting changes together. Priority is vanilla/IPN inventory, carried boxes, direct ender items, then ender boxes. Stacked boxes split within their own storage area.

`EnderSourcesNetwork` sends only S2C `ender_sources_v1`, with exactly 27 slots and no client storage-write endpoint. Every 10 ticks it compares enabled players' actual storage with the last snapshot and sends only changes. Disabling access sends an empty projection. Projections inform client candidates and recipe statistics; server requests always re-read owned real storage.

`restock_v2` adds a whole-source-box fingerprint alongside counts, target, mask and request ID validation. Tool candidates use `swapToolForRestock` to exchange the source inner slot and hand together. Changed boxes or unsafe splitting are refused. Client equipment synchronization ends the wait without a second IPN swap. Consumables retain extraction followed by IPN clicks.

`playerEditableSettings` belongs only to `ServerConfig`. Effective resolution filters existing choices against the current policy on every call. `saveAllowed` rejects locked fields and replaces only editable overrides. Commands, Preferences and Editor saves share it. `editor_state_v7` carries permitted names alongside defaults; locked GUI values inherit the server, and policy revisions invalidate stale drafts. Older v3 settings/refill and v1 IPN channels are not registered. Update both sides to 0.9.0; disk schema remains version 2 with optional additive fields.

`MenuStorageGameTests` cover actual container/trade operations, cursor deposits, full inventory, partial capacity, denied slots and complete trade costs/uses/XP. `EnderSourcesGameTests` cover ownership, repeated/stale requests, priority, disabled/full storage, real recipe placement and continuous crafting. `SettingPermissionsGameTests` cover commands, malicious network/GUI requests, existing files and live revocation. Client smoke tests send actual container clicks; IPN tests cover direct ender potions and exact-slot tool returns; crafting client tests cover ender recipe statistics and real placement. Add `-PwithConfigGui -PclientSmoke` for the partial-permission screen.

### Junk slot state and protocol (0.10.0)

`JunkSlots` uses bits for native inventory/hotbar indices 0–35, independent of menu slot IDs. `JunkSlotStore` persists `{schemaVersion:1, slots:[...]}` under `world/data/magic_shulker_boxes/junk-slots/<UUID>.json`. Runtime revisions support compare-and-set. Writes recheck disk content; future versions, invalid/duplicate indices and malformed files stay untouched. Confirmation follows successful atomic replacement. The transient `StorageConfig.junkBoxSlots` is injected only into a player-specific copy and never enters existing schema2 preferences, permissions or v7 payloads.

The independent `junk_slots_save_v1`, `junk_slots_query_v1` and `junk_slots_state_v1` channels carry a positive request ID, expected/current revision, finite mask and response status. The authenticated connection owns identity. SAVED/SNAPSHOT confirm server data, STALE rejects changed choices, INVALID rejects bounds, BUSY coalesces a retry and FAILED retains the server selection. Revision -1 indicates unreadable data. Disconnect clears the client session; selections are not uploaded across servers. `JunkSlotSession` retains one pending save and a newer queued choice, rejects obsolete replies and queries after timeout instead of replaying snapshots blindly.

`JunkSlotsClient` handles Fabric screen keyboard/mouse events. `JunkSlotGesture` fixes assign/erase mode for one held-key stroke and visits each slot once. Editing roles never edits items. `JunkSlotTooltipMixin` draws corner markers and schedules empty-slot tooltips before `Screen.renderWithTooltipAndSubtitles` calls `GuiGraphics.renderDeferredElements`. Vanilla then renders tooltips on a new stratum, above both markers and selection outlines. Do not draw markers with Fabric's afterRender event, which runs after vanilla tooltips. Item tooltip additions and empty-slot tooltips explain active, inactive and pending states, including pending removal.

Storage and relocation classify assigned positions separately between matching and empty boxes. A split keeps the filled single box in its assigned position and conserves the remaining boxes and item components. Assigned slots are excluded from automatic eviction and empty split/extraction destinations. Drop recollection waits for native pickup, then routes only real received quantities into its reserved box. `IpnJunkSlots` projects read-only locks only during IPN's own sorting calculation, leaving configuration and normal refill matching untouched.

Run actual client input, marker, persistence and sorting verification with:

```powershell
.\gradlew.bat runClientGameTest -PjunkClient
.\gradlew.bat runClientGameTest -PjunkClient -PwithIpn
```

`JunkSlotsClientGameTests` checks keyboard/mouse strokes, revisits, clearing, server persistence, empty/filled markers, English/Chinese, window resizing and IPN sorting. Screenshot pixel checks verify that filled and empty-slot tooltips cover adjacent markers while uncovered markers stay visible, including pending changes and resized windows. Unit and server tests cover persisted choices, stale/invalid data, player isolation, both relocation modes, item conservation and unverified-menu protection. Editing requires both sides to support the dedicated channels.

## Test commands

See [Scheduled release staging](release-staging.md) for five-minute GitHub release checks, verification, backups and staging of both mods. The shared tool is `scripts/stage_releases.py`, with systemd units/configuration in `deploy/`; its regressions are included in the Python test command above.
### Local dedicated-server TCP acceptance

```powershell
.\gradlew.bat runClientGameTest -PwithIpn -PdedicatedClient -PacceptMinecraftEula
```

`-PacceptMinecraftEula` indicates acceptance of the [Minecraft EULA](https://aka.ms/MinecraftEULA), writing `eula=true` only in the isolated test directory. Omit it unless you agree.

`DedicatedClientGameTests` starts an actual `DedicatedServer` bound to 127.0.0.1 through Fabric's test framework and joins over loopback TCP. It verifies the client has no integrated server, reuses all IPN trigger/matcher flows, then checks container Shift transfers, trade costs/uses/XP, ender requests, recipe placement and continuous refill, plus network field permissions and revocation. It closes the connection and server on exit. Its isolated world is under `build/run/clientGameTest/msb-dedicated-test-world`; production worlds are not used. The dedicated implementation runs within the test JVM; separate vanilla/Carpet server-only GameTest processes also verify physical-server loading.

### Crafting sources and real client tests

`CraftingMenuMixin` scopes vanilla `AbstractCraftingMenu.handlePlacement` inside a `CraftingRecipeSources` transaction. `ServerPlaceRecipeMixin` augments material accounting and falls back to boxes after ordinary inventory lookup fails, retaining vanilla recipe selection, layout and batch quantities. A box used as an ingredient cannot also supply its contents. Component-aware capacity checks prove old grid inputs can return without dropping. Source changes are planned on copies and committed only after complete placement; component or splitting conflicts roll back inventory and grid. A `finally` block clears the scope.

`CraftingResultMixin` captures the input pattern and recipe remainders before vanilla takes the output. Vanilla handles output, consumption and remainders first; `CraftingRefill` then plans a complete refill on copies. Shift callbacks may contain an emptied old result stack, so the valid input recipe is authoritative. Player/menu validity is checked again afterward.

No custom extraction request is added. Vanilla validates recipe and active-menu requests; the server reads actual items. `CraftingInventoryClientMixin` augments recipe-book accounting only after the server advertises `craftRefill`, applying effective settings. Read-only outer-count/container-reference observation triggers vanilla recounting. The feature uses existing personal policy and requires neither IPN nor other refill switches.

```powershell
.\gradlew.bat runClientGameTest -PcraftClient
```

`CraftingRefillTest` covers atomic multi-ingredient planning, components, remainders, full inventories and stacked sources. `CraftingGameTests` cover real 2x2/3x3 placement, batches, ordinary/Shift crafting, rollback, cake buckets and personal overrides. `CraftingClientGameTests` send vanilla recipe and inventory-click requests and verify recipe-book accounting/refresh, both grids, continuous refills and server-confirmed output counts. Client prediction alone is not acceptance.

### Carpet and GCA compatibility

0.8.0 removes the fake-player tick/drop Mixins and `carpetRefill` from gameplay, permissions, commands, GUI and key bindings. Carpet remains an optional runtime neighbor for stacked boxes; MSB does not implement fake-player hand replenishment. `CarpetCompatibilityGameTests` runs the published Carpet fake class and continuous USE, verifying that disabling GCA leaves hands empty and source boxes untouched. With GCA enabled, it places exactly seven anvils from a partially used box (contents in inner slot 25) and a second box without MSB replenishment.

`ConfigFile.migrateRemovedSettings` accepts the retired field only in version-2 disk documents, validates its boolean type and all remaining choices/permissions, saves an exact `.pre-0.8.0.bak` backup for Carpet-only retirement or `.pre-single-type.bak` when storage settings also migrate and atomically removes the field and its permission. Server/client/UUID files share this path. Live command and network validation reject the field. Matching backups permit resuming; conflicting or unreadable backups and malformed values preserve the original. Disk version remains 2. Policy/preferences and GUI use v7; material refill protocols keep their formats.

```powershell
.\gradlew.bat build '-PcarpetJar=C:/path/to/fabric-carpet-1.21.11-1.4.194+v251223.jar' '-PgcaJar=C:/path/to/gugle-carpet-addition-mc1.21.11-v2.12.8+build.97.jar'
```

These dependencies are test-only and excluded from distribution. Without GCA the GCA-specific case skips; the Carpet test still proves that MSB does not refill fake players. `RemovedCarpetSettingsTest` covers preserved choices, sparse permissions, offline UUID files, old live-input rejection, invalid files, exact backups and interrupted migration.

### IPN source extension and client tests

The optional integration is checked against IPN 2.2.6 and libIPN 6.6.3 for Fabric 1.21.11. They and Kotlin are compile/test dependencies, never bundled in the distribution. `magic_shulker_boxes.ipn.mixins.json` applies only on clients with IPN; dedicated servers and clients without IPN retain their original behavior.

`IpnMonitorMixin` intercepts immediately before IPN calls `handle()`, after its own trigger checks and wait ticks. Ordinary backpack candidates always win. During scoped lookups of carried boxes, direct ender items and ender boxes, `IpnCandidatesMixin` supplies read-only box contents to IPN's original `findCorrespondingSlot` filtering and sorting. Virtual candidate IDs never reach inventory-click packets or player inventories. IPN's release omits nested-class metadata, so the Java adapter uses its actual binary class names.

`restock_v2` carries a correlated request ID, source box/content slots and counts, a main-hand/offhand target slot and count, a 27-bit eligible-backpack mask, and source-item/source-box/target component fingerprints bounded to 64 characters each. Empty targets use an empty fingerprint. The server verifies actual contents, counts, fingerprints, mode, menu, cursor, effective `ipnRefill` and rate limits; duplicates and subsequent requests within 10 ticks are refused. Atomic extraction, relocation and splitting use only eligible backpack destinations. `restock_result_v2` returns the request ID and success; ordinary inventory synchronization transfers item data. The client waits up to five seconds and returns control to IPN after cancellation, failure or timeout.

Run the released IPN matcher and real Mixins over an isolated client/server connection (requires graphics):

```powershell
.\gradlew.bat runClientGameTest -PwithIpn
.\gradlew.bat runClientGameTest -PclientSmoke
```

`src/ipnTest` is included only with `withIpn`. It covers backpack priority, potion effects, custom-name options, locked sources, disabled refill slots, main-hand/offhand refilling, retained empty bottles, tool durability thresholds and same-category replacements. `clientSmoke` starts a real client without IPN/Kotlin and joins an integrated server. `ShulkerRestockTest` and `RestockGameTests` cover atomic extraction, protected slots, stacked boxes, stale/duplicate requests, canceled hotbar selection and personal settings.

### Schematic refill protocol

`BoxOrder` ranks eligible boxes by the sum of count/stack-limit across their slots, breaking ties by inventory slot. Storage fills fuller boxes within the existing category order; `RefillSearch` and server-side `RefillNetwork` prefer emptier boxes and continue past unsafe sources. A box with 27 partial stacks is not treated as full. Nonstandard larger containers remain excluded.

`RefillSearch` performs read-only component-exact lookup. Optional mixins bracket Litematica's `WorldUtils.doEasyPlaceAction` and `EasyPlaceUtils.handleEasyPlace`, intercepting `InventoryUtils.schematicWorldPickBlock` only during Easy Place. Normal pick-block is unaffected. `LitematicaMixinPlugin` skips these client targets when Litematica is absent; neither builds nor dedicated servers require its JAR.

The `refill_v4` request contains a box slot, inner slot, bounded item ID and a 64-character SHA-256 fingerprint. `ItemFingerprint` uses vanilla `HashOps`, item codecs and registry context to produce a canonical, count-independent fingerprint. Unencodable or transient components are refused; clients never provide authoritative item data. Old v1/v2/v3 channels are no longer registered. `RefillNetwork` checks the source fingerprint, game mode, menu/cursor state and effective player settings on the server thread. Each player can make one request per 10 ticks; delayed duplicates stop if matching materials are already available. Within a box, candidates with identical components and counts are planned once, while different counts remain separate candidates. `ShulkerRefill` plans extraction, relocation and splitting on copies and commits only a complete valid plan. Failure leaves inventory unchanged. Success uses ordinary inventory synchronization; optional failure notices use a rate-limited action bar.

Refilling never places blocks directly or bypasses Litematica's hotbar protections and placement checks. While awaiting synchronization, it suppresses the generic missing-material warning for that attempt. Holding the placement key continues the original pick-and-place flow.

`RefillSearchTest` samples read-only lookup over 36 full boxes (972 slots), reporting median/P95 without a hardware-dependent pass threshold. These figures do not measure multiplayer load or network latency. `RefillGameTests` exercise real server requests, mode/config refusal and item preservation.

Copy `tests/schematics/MSB-Refill.litematic` into the test instance's `schematics` directory. Load it at `100,101,100`; it contains cobblestone, oak planks and glass. `/function msb_test:refill` prepares a full inventory and platform. Verify extraction and a subsequent placement consuming one item, while the pickaxe, 16 empty boxes and unrelated materials remain intact. With personal settings permitted, `refill_blocked`, `refill_silent`, `refill_disabled` and `refill_enabled` test refusal, notification suppression, disabling and resetting respectively. `refill_silent` turns off both `refillFailureMessages` and `spaceFailureMessages`; permission for both fields is required.

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

Unit reports are in `build/reports/tests/test/index.html`. GameTest results appear in the console and test-run logs. Tests use actual server players, inventories, and item entities. They cover matching-box-first pickup and optional empty-box priority, partial capacity, ownership/delay checks, stacked-box fallback, splitting into a free slot, configuration disabling, both space-making modes, partial stacks, repeated pickups of different types, and recursion prevention.

Additional tests cover per-player persistence and isolation, inheritance from server defaults, independent personal overrides for pickup storage and refilling, immediate pickup-policy changes, player command permissions, administrator policy revocation, client attempts to change server policy, bounded network payloads, and matching translation keys/placeholders.

### Manual game checks

`tests/manual-datapack` is a Minecraft 1.21.11 fixture. Copy its contents into a **new dedicated test world's** `datapacks/msb-manual-tests`, then run `/reload`. Commands and Carpet are required. These fixtures clear the invoking player's inventory, delete nearby item entities, and set peaceful difficulty. Do not run them in a normal gameplay world.

| Command | Expected behavior |
| --- | --- |
| `/function msb_test:matching` | Blue box changes from 63 cobblestone to 64 + 4; earlier empty boxes and boxes with unrelated contents remain unchanged |
| `/function msb_test:partial` | A box with one item of free capacity accepts one of five cobblestone; four stay on the ground |
| `/function msb_test:split` | Set `preferEmptyBoxesOverInventory=true` first; 16 named blue boxes become 15 empty boxes and one box containing five cobblestone |
| `/function msb_test:auto_space` | No free slot: consolidate stone stacks; keep 13 empty boxes plus separate stone, cobblestone and gravel boxes |

Run `auto_space` with both `MOVE_TO_BOX` and `DROP_AND_PICKUP`. Three separate boxes should hold stone 131, cobblestone 5 and gravel 7; 13 empty boxes remain stacked. With `DISABLED`, the box stack remains 16 and the pickups remain on the ground. Apply changed server configuration with `/msb admin reload` or restart, and restore the desired settings afterward.

For personal-policy verification, toggle `/msb admin player-settings true/false`, set your own `pickupStorageEnabled=true` while the server default is off, inspect `/msb show`, and repeat a pickup fixture. The personal on setting applies only while the server permits it. Switch Minecraft between Simplified Chinese and English to check messages.

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

Relocation reserves capacity for the entire displaced slot before accepting incoming items. Drop mode commits inventory changes only after the world accepts every spawned entity. Immediate recollection may write only into each stack's exact reserved single-type box; replacement of that box leaves the dropped items intact. A `finally` block clears temporary reservations, and unrecollected entities carry a persistent marker prohibiting another relocation attempt.

Inventory changes use vanilla synchronization. Optional settings use the `policy_v7` and `preferences_v7` channels, with capability checks before sending. Fabric's object-payload callbacks run on the game thread. A preference payload contains at most 4096 characters and no target UUID: the sender's identity comes from the connection. The server checks its policy, option allowlist, types, enum values, and size, and limits network updates to one per player per 20 ticks. Server-only installations do not require clients to support these channels.

`allowPlayerSettings` decides whether to read individual overrides. When allowed, `ConfigFile.apply` merges explicit player choices over server defaults; `pickupStorageEnabled` and `schematicRefill` remain independent, and players can enable either feature over an off server default. Otherwise the shared server configuration applies directly. Player files are isolated by world and UUID, cached, and written through a temporary file with atomic replacement when supported. Malformed files are retained; load failures fall back to server settings and are logged. Reload validates a replacement before changing active settings and clearing caches.

Language resources are in `assets/magic_shulker_boxes/lang`. `translatableWithFallback` provides readable text for clients without this mod, chosen from the language reported by each player. Other mods can still cancel pickup entirely. Mods altering the pickup path or container structure require separate compatibility checks.

## Optional GUI integration

`ModMenuIntegration` exposes the configuration factory and checks for YACL before referencing `SettingsGui`. Both libraries use `modCompileOnly` and are not bundled. Add `-PwithConfigGui` for a development client. Default GameTests omit GUI libraries to verify dedicated-server compatibility.

YACL bindings edit a detached `SettingsDraft`. Personal boolean fields have three states; Inherit removes the key. `SettingsSession` tracks connection/policy revisions and pending request IDs. Timeouts retain the request and start reconciliation; duplicate or old-connection replies cannot persist. Before sending a save, `PreferenceSync` writes a recovery marker under `config/magic_shulker_boxes-recovery/`, using a hash of the server address/world path and player UUID. Markers contain neither preference values nor plaintext addresses. Confirmation updates the in-memory snapshot before writing the personal file and clearing the marker. On failure, the current session still follows the server. Reconnecting or restarting with a marker queries the server before any upload of stale local preferences. `ClientSettings` coordinates feedback, timeouts and integrated-server saves.

`EditorNetwork` uses `editor_state_v7` (policy/defaults), `editor_save_v7` (request ID/overrides), `editor_query_v7` (read-only recovery request ID), and `editor_result_v7` (correlated success/snapshot/rejection). JSON is bounded to 4096 characters and identity comes from the connection. Saves check policy; saves and queries are separately limited to one per player per 20 ticks. A query only reads the sender's preferences, including when edits are locked. Legacy settings and GUI v1/v2/v3/v4/v5/v6 channels are no longer registered, preventing old clients from restoring the old key. The new GUI requires v7 query support for saves. No GUI payload can write administrator settings. Integrated-host edits execute on the server thread and compare the draft baseline before writing to avoid overwriting external changes.

`RefillRegressionGameTests` cover changed and unchanged named materials, timeout recovery queries, player isolation and rate limits, and repeated failed one-item extraction from a full inventory. On JVMs with thread allocation counters, the fixture must allocate less than 4 MiB per request; elapsed times are logged without a machine-dependent timing threshold. `PreferenceSyncTest` injects a local file replacement failure and checks active values, recovery markers and restart behavior.

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

`BoxRelocation` plans displaced stacks on inventory snapshots, using matching single-type boxes before empty boxes. When necessary it consolidates multiple inventory stacks of one type into a separate split box. Pickup commits only after it can accept incoming items; extraction and crafting commit only after the whole operation succeeds. IPN masks protect both displaced slots and carried destination boxes. Boxes containing unrelated types are skipped as storage destinations, even when they have space.

`BoxRelocation.sameType` is the type comparison entry point for storage and relocation. By default it compares the item ID and complete values of `POTION_CONTENTS`, `POTION_DURATION_SCALE`, `SUSPICIOUS_STEW_EFFECTS`, `STORED_ENCHANTMENTS`, `MAP_ID`, `FIREWORKS`, `FIREWORK_EXPLOSION`, `INSTRUMENT` and `OMINOUS_BOTTLE_AMPLIFIER`. These payloads define the item type and are never relaxed by `matchItemComponents=false`. Enabling the switch uses vanilla full-component equality; ordinary equipment `ENCHANTMENTS`/`DAMAGE`, custom names and other differences still follow this switch. No configuration fields or network payloads change.

`PickupRelocation.collectReserved` and `ShulkerStorage.collectRelocated` explicitly receive the effective configuration for the pickup. Recollection rechecks the reserved contents with the shared `acceptsType`, so another pickup hook editing the box cannot bypass intrinsic types or strict component matching. Actual stack merging always uses `ItemStack.isSameItemSameComponents`.

`PotionClassificationTest` covers potion/tipped-arrow types, ordinary/extended/enhanced variants, custom effects, duration scaling, mixed-box rejection, empty-box fallback, atomic relocation rollback and reserved recollection. `StorageTypeGameTests` uses real item pickup and chest Shift-clicks to cover every intrinsic type, enchanted-book levels, firework duration/contents and ordinary tool enchantments following the strict switch. `ClientSmokeGameTests` verifies the real client path; the no-IPN/YACL settings entry point still requires the checks above.

`ConfigFile` removes retired mixing options and permission entries only on disk, validates remaining choices, preserves exact original bytes in `.pre-single-type.bak`, and retains other sparse preferences. Commands, GUI options, key bindings and network input no longer expose these fields. Version 1 files remove retired choices as part of their existing `.pre-0.3.2.bak` migration; invalid files and backup conflicts are left intact. Historical release notes describe their respective released versions.

`ItemEntityMixin` and menu transfers call `ShulkerStorage.storeMatching` first, then vanilla inventory, then empty-box overflow storage. `preferEmptyBoxesOverInventory=true` moves the empty-box phase before inventory insertion. The old priority setting migrates to its inverse and renames its permission; conflicting old/new fields are rejected. `StorageFailure` sends localized action-bar notices for confirmed capacity failures with a per-player 40-tick cooldown, controlled by `spaceFailureMessages` independently of other refill failure notices. Crafting reports blocked source splitting or remainder placement without reporting missing ingredients or changed recipes as space failures.

The settings editor builds only nonempty option groups, preventing retired fields from leaving a group that YACL refuses to open. `ClientSmokeGameTests` opens personal and world settings, checks field permissions and captures screenshots; the new priority setting uses the existing editing and confirmation flow.
