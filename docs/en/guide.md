# Magic Shulker Boxes

[简体中文](../guide.md) | **English**

Automatic shulker storage for **Minecraft Java 1.21.11 / Fabric / Java 21**.

**Pickup storage is off by default.** Singleplayer hosts can enable it in World settings. Multiplayer administrators can set `pickupStorageEnabled=true` in the server configuration and run `/msb admin reload`, or allow players to enable it individually. Material refilling has its own switch.

When a player picks up a ground item, the normal inventory receives it first. Any overflow is stored in this order:

1. A box containing only the same item type.
2. An empty shulker box.
3. A mixed box containing two or more item types.
4. A box containing a different single type, if this optional fallback is enabled.

All eligible boxes in one category are tried before moving to the next. Within a category, fuller boxes are filled first; boxes unable to accept the item are skipped. Fullness sums each slot's count relative to its stack limit, supporting 64-stack, 16-stack and unstackable items. Ties use inventory slot order. Storage can accept part of a pickup; the remainder stays on the ground.

## Upgrading to 0.4.0: IPN and crafting refilling

0.4.0 adds `ipnRefill=true` and `craftRefill=true`, independently controlling IPN box-source refilling and crafting refilling alongside `pickupStorageEnabled` and `schematicRefill`. When personal settings are allowed, all four server values are overridable defaults; otherwise the server values apply to everyone.

Existing 0.3.2 `configVersion: 2` files and choices are retained without resetting; omitted new switches default to enabled. Update both sides to 0.4.0 for the new GUI settings and IPN integration. A 0.4.0 server performs crafting extraction, while a 0.4.0 client also counts box contents in the recipe book. Earlier files retain the backup/migration rules below.

## Upgrading to 0.3.2: personal switches and the renamed setting

When the server allows personal settings (`allowPlayerSettings=true`), `pickupStorageEnabled` and `schematicRefill` are **server defaults** that players may independently override. With personal settings disabled, everyone uses the server values. The former `enabled` key is now `pickupStorageEnabled`; current files, commands and network settings accept only the new name.

On first read, a valid 0.3.1 `configVersion: 1` file is copied unchanged to `<original filename>.pre-0.3.2.bak` alongside it, then rewritten once with `configVersion: 2`. The original switch value and all other valid choices are retained. This covers shared server settings, online and offline player files, and client preferences. Failed or conflicting backups and invalid fields leave the original intact with an error. Personal settings and refilling use new channels; update both client and server to 0.3.2 for those features. Server-only pickup storage still needs no client mod.

## Upgrading to 0.3.1: back up and reset old settings

Configuration files from 0.3.0-alpha and earlier have no version marker. On first read, 0.3.1 makes an exact backup named `<original filename>.pre-0.3.1.bak` alongside each file, then resets all options. This covers `config/magic_shulker_boxes.json`, client `config/magic_shulker_boxes-client.json`, and world `data/magic_shulker_boxes/players/<UUID>.json`. Server startup processes existing player files, including offline players; client startup processes local preferences.

In 0.3.1, server settings returned to defaults: the old `enabled=false` and `allowPlayerSettings=false`. Personal overrides were cleared to inherit the server values. Refilling remained independent under `schematicRefill`, defaulting to `true`. That version wrote `configVersion: 1` files; 0.3.2 migrates them to version 2 as described above. Keep the version marker. Backups are neither automatically restored nor used at runtime. To recover selected choices, copy them into the current file while retaining its version marker.

Backup failures or an existing backup with different contents leave the original untouched and log an error. Malformed JSON, unknown configuration versions and invalid current-version options are also retained and reported. Update both sides to 0.3.2 for personal settings; old clients cannot re-upload old preferences. Players using server-only pickup storage still need no client installation.

## Installation

- Dedicated multiplayer server: install this mod and the matching **Fabric API** in the Fabric 1.21.11 server's `mods` folder. Players do not need this mod on their clients.
- Singleplayer or LAN host: install the same two mods in the client instance. Its integrated server performs storage.
- Installing only the client mod cannot enable storage on a remote server without the mod.
- Use Fabric Loader **0.18.4 or newer**. Development uses Loader 0.19.5 and Fabric API 0.141.6+1.21.11.

Build outputs are in `build/libs`. Install the regular JAR, not the `-sources.jar`. When upgrading, remove the previous version from the instance's `mods` folder so that only one version is installed.

### Graphical settings (Mod Menu + YACL)

Optionally install [Mod Menu 17.0.1](https://modrinth.com/mod/modmenu/version/17.0.1) and [YACL 3.8.2 for Fabric 1.21.11](https://modrinth.com/mod/yacl/version/3.8.2+1.21.11-fabric) on the client. Dedicated servers do not need either library.

Open **Mods → Magic Shulker Boxes → Configure**. Labels and descriptions follow the Minecraft language (Simplified Chinese or English).

- **Personal**: boolean options offer Inherit / On / Off; the space-making mode also offers Inherit. Resetting to Inherit removes that override. Cancel or leaving without saving does not write the file.
- **Title screen**: edit local preferences, which upload when joining a server that allows them.
- **In game**: the server must support the GUI and recovery-query protocols and allow personal settings. A save waits for server acknowledgement before updating the local file. Rejections, rate limits and timeouts display feedback. Enforced server policy locks personal editing.
- **World settings / Local defaults**: available only at the title screen or to the singleplayer / LAN host. Edit this instance's shared defaults, including independent pickup and refill switches and permission to use personal settings. Saves apply immediately. Remote multiplayer clients cannot access this page.

The two pages save independently. Switching with unsaved edits prompts to discard or return to editing. Reopen the personal editor after a connection or server-policy change; stale drafts cannot be submitted. Option names are fixed labels, with a separate value button on the right. Click to cycle values, right-click to cycle backward, and use the adjacent reset button to restore the option default. Personal preferences include inheritance; world settings select explicit values. Inherited options show the received value directly, such as “Inherit server (On)”. Offline or unavailable defaults are marked “unknown”; values refresh when the server synchronizes its settings, while stale drafts still require reopening. Descriptions also show received server defaults. An off server default for either feature is indicated separately. With Mod Menu but without YACL, the configuration entry displays a missing-dependency message.

**Store picked-up items in boxes** (`pickupStorageEnabled`) and **Refill materials for Litematica** (`schematicRefill`) are independent: use either feature, both, or neither. `pickupStorageEnabled=false` disables pickup storage only. To disable both features for everyone, disallow personal settings and also set `schematicRefill=false`.

After a ten-second confirmation timeout, further saves pause while the client queries the server's saved preferences. Late confirmations remain valid. If writing the local file fails, gameplay and the editor still use confirmed server values and explicitly report the local failure. Unresolved saves leave recovery markers scoped to the server and player. Reconnecting or restarting queries the server before uploading any old local file. Reopen the editor after synchronization completes.

## Schematic material refilling

Install **Litematica 0.26.16 / MaLiLib 0.27.20 for Minecraft 1.21.11** on the client, and Magic Shulker Boxes **0.4.0** on both sides. Older refill protocols are no longer accepted; personal settings require compatible synchronization channels. A single-player instance supplies both sides. Dedicated servers do not need Litematica or MaLiLib.

Enable Litematica Easy Place, aim at a schematic block and use its placement key. Existing inventory/offhand materials retain the original behavior. Missing materials are extracted from inventory shulker boxes; placement continues after the server synchronizes the inventory. Keep holding the placement key to continue. A single click may only refill; click again to place. Both legacy and rewritten Easy Place are supported. Normal pick-block, creative mode, open containers and cursor-held items do not trigger refilling.

When full, space-making moves a backpack stack directly into the source box without dropping items. Hotbar slots are protected by default; `useHotbarForSpace`, `allowPartialStacksForSpace` and `allowMixedItemsWhenMakingSpace` also apply. Shulker boxes are never nested. A stacked source needs a separate free slot for one modified box. With no safe space, nothing changes. Carpet normally stacks only empty boxes, which contain no materials to extract.

Refilling prefers non-full boxes, starting with the least filled to help empty one box before opening another. Full boxes remain fallback sources; unsafe sources are skipped. Client lookup and server extraction enforce the same order, with inventory slot order breaking ties.

The menu has a **Schematic materials** group:

| Option | Default | Behavior |
| --- | --- | --- |
| `schematicRefill` | `true` | Enable refilling independently; players can override the server default when personal settings are allowed |
| `refillFullStack` | `true` | Take up to one stack from one box slot; off takes one item |
| `refillMakeSpace` | `true` | Direct relocation when full, even with pickup storage disabled; independent of pickup drop mode |
| `refillFailureMessages` | `true` | **Only failed refills** produce an action-bar notice, at most once every two seconds; success is silent |

Notices distinguish missing matching materials, unsafe inventory space, disabled refilling and unsupported servers. Turning notices off does not affect refilling. Personal overrides still require `allowPlayerSettings`.

Client material lookup compares complete item components, including custom names. Requests also carry a component fingerprint; changed sources and transient components that cannot be reliably encoded are rejected. Both sides must support the new refill protocol; the old protocol cannot bypass validation. Only ordinary inventory and optionally offhand boxes are searched, excluding ender chests and nested containers. The client searches during missing-material attempts and sends at most two requests per second. The server verifies actual contents, game mode and menu state. Clients never provide authoritative item data or edit the inventory ahead of confirmation.

## IPN consumable and tool refilling

Available since 0.4.0. Install [Inventory Profiles Next 2.2.6 for Fabric 1.21.11](https://modrinth.com/mod/inventory-profiles-next/version/fabric-1.21.11-2.2.6) and its required libIPN and Fabric Language Kotlin on the client. Both sides need Magic Shulker Boxes 0.4.0. The server does not need IPN; singleplayer supplies both sides.

IPN first looks for its normal backpack candidates when refilling main-hand or offhand consumables or replacing tools. If none qualify, this mod offers backpack shulker-box contents to **IPN's original filtering and sorting method**. IPN still controls triggers, wait ticks, potion effects, food alternatives, name/component matching, tool categories, durability thresholds, custom sorting and disabled refill slots. This integration does not extend armor refilling.

`ipnRefill=true` enables the source extension by default, independently of `pickupStorageEnabled` and `schematicRefill`. When personal settings are allowed, `/msb set ipnRefill true` overrides the server default. The settings GUI has an **Inventory Profiles Next** group.

Only boxes in IPN's backpack storage area (Minecraft inventory indices 9–35) are sources, respecting IPN's locked-slot settings. Hotbar and offhand boxes, ender chests and nested containers are excluded. Offhand can be a refill target. The server extracts the chosen stack into IPN-visible backpack storage; after synchronization, IPN performs its original hand/tool swap and preserves empty bottles and old tools.

Extraction follows `refillMakeSpace`, `splitStackedBoxes`, `allowPartialStacksForSpace`, `allowMixedItemsWhenMakingSpace` and `matchItemComponents` for source-box relocation. Slots excluded by IPN's locked-slot settings are also excluded as extraction and splitting destinations. If IPN permits using locked slots, this integration follows that choice. IPN takes up to one complete candidate stack; the schematic-only `refillFullStack` one-item mode does not affect it. Unsafe extraction changes nothing and leaves IPN's normal failure handling in control. On servers without the new protocol, IPN retains its original backpack behavior.

## Crafting ingredient refilling

Available since 0.4.0 for **2x2 inventory** and **3x3 crafting-table** refilling. IPN is not required. A 0.4.0 server performs extraction. A 0.4.0 client also counts box contents in the vanilla recipe book and refreshes when those contents change.

The **Crafting** settings group contains `craftRefill`, enabled by default and independent of pickup storage, schematic refilling and IPN. When personal settings are allowed, use `/msb set craftRefill false` or `/msb set craftRefill true`.

- **Placing a recipe:** vanilla controls recipe permissions, matching, layout and Shift batch quantities. Missing inventory materials can come from carried boxes and go directly into the grid, without a temporary material slot. Previous grid inputs must fit safely back into the inventory.
- **Taking an output:** ordinary clicks and Shift crafting refill empty cells with one item of the same type and complete components as the observed input pattern. Remaining grid stacks retain their counts. Ordinary inventory comes first, followed by boxes. An incomplete set of materials or unsafe remainder relocation stops the refill without partial extraction.

Vanilla handles crafting remainders first. When refilling an emptied cell now occupied by a bucket, bottle or other remainder, the refill tries ordinary inventory first. When full, `refillMakeSpace`, `allowPartialStacksForSpace`, `allowMixedItemsWhenMakingSpace` and `matchItemComponents` govern storing that remainder in the single source box just used. Vanilla still places outputs on the cursor or in the inventory.

Sources are main-inventory and hotbar boxes, plus offhand boxes when `includeOffhand=true`, preferring less filled boxes. Stacked sources follow `splitStackedBoxes` and require a separate empty slot. Ender chests, nested containers, furnaces, brewing stands and stonecutters are excluded; creative and spectator modes do not extract automatically. Recipe-book placement retains vanilla restrictions on named, damaged and enchanted items. Continuous refilling of a manual pattern preserves exact input components.

Source changes are planned on copies. Component conflicts, unavailable splitting space or incomplete placement roll back. A box cannot both supply its contents and be consumed as an ingredient in the same placement.

## Carpet fake-player refilling (current source)

Current source builds add `carpetRefill=true`, independently of pickup storage, schematic, IPN and crafting refilling. The published 0.4.0 release does not include it. Install a build with this feature and Carpet on the server; fake players need no client or IPN. Client settings and preference synchronization require matching settings protocol builds.

When a fake player's main-hand/offhand item runs out or its tool breaks, loose backpack supplies come first, followed by carried shulker boxes. Consumables match all components, including potion effects and names. Tools match the same item and all components except damage; replacement happens after breaking, without changing food types, tool materials or enchantments. Carpet's original actions continue with the replenished hand.

Searches main-inventory and hotbar boxes, least filled first, plus offhand boxes when `includeOffhand=true`. `refillFullStack=false` supplies one item; otherwise up to one stack. Stacked boxes follow `splitStackedBoxes` and need a separate empty slot for the modified box; the target hand is reserved. Bottles, buckets and other use remainders go back into ordinary inventory first. With a full inventory, source-box storage follows `refillMakeSpace`, `allowPartialStacksForSpace`, `allowMixedItemsWhenMakingSpace` and `matchItemComponents`. If a remainder cannot be preserved, the entire refill leaves inventory unchanged.

Only survival/adventure players with no other container open and no cursor item refill. Hotbar changes, hand swaps and explicit Carpet drops do not trigger refilling. Armor, ender chests, nested containers and chests placed in the world are outside this feature. Failure does not drop remainders.

Administrators can disable `carpetRefill` in server settings. When personal settings are allowed, a fake player's UUID preference file can override the default using the player-settings path below; fake players need no network request.

## Carpet stacking

The mod works with Carpet's `stackableShulkerBoxes` rule. It does not enable that rule for you, and Carpet is optional.

Before changing the contents of a stacked box, the mod separates exactly **one** box into its own inventory slot. Existing usable boxes are preferred by default. If no space remains, automatic space-making may free a slot. Armor slots, the cursor, and the offhand are never split destinations. Shulker boxes themselves are never displaced to make space.

With default settings, a free inventory slot normally receives the ground item through vanilla pickup. Set `onlyWhenInventoryFull=false` to try shulker boxes before the inventory; this also allows a stacked box to be split into an existing free slot.

All vanilla shulker colors are supported. Names, colors, other box components, and stored item components are preserved. Shulker boxes cannot be nested.

### Making space automatically

`makeSpaceMode` supports:

- `MOVE_TO_BOX` (default): move the selected inventory slot's items directly into the separated box, which replaces that slot. Nothing is dropped.
- `DROP_AND_PICKUP`: create the displaced items at the player's feet, separate a box, and immediately attempt to collect the displaced items through the game's pickup path into that reserved box. If spawning fails, the inventory is unchanged. If another mod blocks collection, remaining items stay on the ground with a marker preventing another space-making attempt.
- `DISABLED`: do not free occupied slots. Skip stacked boxes when no free slot exists and try other boxes.

A stack need not be full: a slot containing three stones can be used. All three must leave that slot to free it. By default, only the main inventory is considered; hotbar use is optional. Items matching the incoming type are preferred, followed by other items that can be stored. The displaced slot must fit completely, leaving room for at least one incoming item, or nothing changes.

When mixed items are allowed, three displaced stones plus five incoming cobblestones create a mixed box. Later gravel pickups reuse that box by default instead of splitting another empty one for each type. With `allowMixedItemsWhenMakingSpace=false`, only items matching the incoming type can be displaced. An existing matching single-type box is never mixed with unrelated items during space-making.

## Configuration

The side performing storage creates `config/magic_shulker_boxes.json`. Edit the server file for multiplayer, or the client instance's file for singleplayer. Apply edits with `/msb admin reload`, or restart the server/client.

The [complete server example](https://github.com/chenjicheng/magic-shulker-boxes/blob/main/examples/magic_shulker_boxes.json) contains all defaults.

| Option | Default | Behavior |
| --- | --- | --- |
| `allowPlayerSettings` | `false` | Server policy only: allow individual player overrides; otherwise enforce this file for everyone |
| `pickupStorageEnabled` | `false` | Off by default; enables pickup storage independently of refilling; players can override the server default when personal settings are allowed |
| `onlyWhenInventoryFull` | `true` | Store only vanilla inventory overflow; `false` tries boxes first |
| `useMatchingBoxes` | `true` | Use matching single-type boxes |
| `useEmptyBoxes` | `true` | Use empty boxes |
| `useMixedBoxes` | `true` | Use boxes already containing multiple types |
| `allowOtherSingleTypeBoxes` | `false` | Allow other single-type boxes as the final fallback |
| `splitStackedBoxes` | `true` | Allow splitting, including automatic space-making; otherwise skip all stacked boxes |
| `makeSpaceMode` | `"MOVE_TO_BOX"` | Direct relocation; alternatives are `"DROP_AND_PICKUP"` and `"DISABLED"` |
| `useHotbarForSpace` | `false` | Allow moving hotbar items to free a slot |
| `allowPartialStacksForSpace` | `true` | Allow stacks below their item stack limit to be displaced in either mode |
| `allowMixedItemsWhenMakingSpace` | `true` | Allow unrelated displaced items to create a mixed box |
| `preferExistingBoxesBeforeMakingSpace` | `true` | Try all currently usable boxes before freeing a slot; `false` makes space as each stacked box is encountered in category order |
| `includeOffhand` | `false` | Also search the offhand for boxes; split destinations remain main inventory/hotbar slots |
| `matchItemComponents` | `false` | Classify types by item ID; `true` also compares names, enchantments, and other components |
| `ipnRefill` | `true` | Allow IPN to refill from backpack boxes using its own matching and triggers |
| `craftRefill` | `true` | Allow carried-box crafting ingredients |
| `carpetRefill` | `true` | Current source: server-side replenishment of exhausted Carpet fake-player hands |

Multiple stacks of cobblestone still count as one type. Actual stack merging always compares components, independently of the classification option, and respects each item's maximum stack size.

All options except `makeSpaceMode` require JSON booleans. Its value must be one of the three strings listed above. Omitted fields use defaults. Invalid JSON, unknown keys, invalid modes, and incorrect types are rejected without overwriting the file. Invalid server configuration disables all automatic pickup storage and refill features at startup; a failed runtime reload retains the previous active configuration.

Pickup storage handles only ground-item pickup. Chest transfers, crafting, item-giving commands, and manual dropping do not directly trigger storage. Ender chests and nested containers are not searched.

## Personal settings and languages

In-game messages support Simplified Chinese and English, following the player's Minecraft language. Players without the client mod receive readable localized fallback text from the server. Other languages fall back to English. Commands, JSON keys, and enum values use the same fixed English identifiers in both languages. The mod name and description include both languages.

Servers enforce their own settings by default. Administrators can change and persist the policy immediately:

```text
/msb admin player-settings true
/msb admin player-settings false
/msb admin reload
```

These commands require Minecraft's `COMMANDS_ADMIN` permission, normally OP level 3; the server console can also use them. With `allowPlayerSettings=false`, the server pickup and all refill switches apply to everyone. With it set to `true`, each server value becomes a default that players may override independently. A failed reload leaves the previous active settings in place.

Once permitted, ordinary players can use these commands without a client installation:

```text
/msb
/msb show
/msb set pickupStorageEnabled false
/msb set schematicRefill true
/msb set makeSpaceMode MOVE_TO_BOX
/msb set allowPartialStacksForSpace true
/msb reset
```

`show` reports the **effective** settings. `set` takes effect immediately and supports every storage option except the server-only policy. `reset` clears personal overrides. Option names and values have command suggestions. Each player can change only their own settings. The server rejects `set/reset` while personal settings are disallowed.

Overrides are saved by UUID in the current world's `data/magic_shulker_boxes/players/<UUID>.json`, persist across restarts, and are isolated between worlds. Only explicitly changed fields are stored; all others inherit the current server defaults. Administrators can reload after manually repairing player files to clear the cache.

### Optional client synchronization

An installed client creates `config/magic_shulker_boxes-client.json`, initially `{}` to inherit every server default. See the [personal settings example](https://github.com/chenjicheng/magic-shulker-boxes/blob/main/examples/magic_shulker_boxes-client.json).

When joining a compatible server that allows personal settings, the client submits this file automatically. Enabling or reloading the server policy also requests synchronization. A submission replaces that player's saved overrides on that server. Nothing is uploaded when the policy is disabled, and the local file is retained. Rejoin after editing the local file to submit changes.

Successful `/msb set/reset` changes are also saved back to the installed client's personal file, keeping subsequent joins consistent. This file belongs to the client instance and is reused on other servers that allow personal settings. Players without the client mod use only the records stored in each server world. Synchronization cannot change server policy.

## Development

See [building, testing, and implementation](development.md).
