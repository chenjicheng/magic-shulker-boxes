# Magic Shulker Boxes

[简体中文](../guide.md) | **English**

Automatic shulker storage for **Minecraft Java 1.21.11 / Fabric / Java 21**.

When a player picks up a ground item, the normal inventory receives it first. Any overflow is stored in this order:

1. A box containing only the same item type.
2. An empty shulker box.
3. A mixed box containing two or more item types.
4. A box containing a different single type, if this optional fallback is enabled.

All eligible boxes in one category are tried before moving to the next. Within a category, inventory slot order applies. Storage can accept part of a pickup; the remainder stays on the ground.

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
- **In game**: the server must run a version of this mod supporting the GUI protocol and allow personal settings. A save waits for server acknowledgement before updating the local file. Rejections, rate limits and timeouts display feedback. Enforced server policy locks personal editing.
- **World settings / Local defaults**: available only at the title screen or to the singleplayer / LAN host. Edit this instance's shared defaults, including the master switch and permission to use personal settings. Saves apply immediately. Remote multiplayer clients cannot access this page.

The two pages save independently. Switching with unsaved edits prompts to discard or return to editing. Reopen the personal editor after a connection or server-policy change; stale drafts cannot be submitted. Descriptions show received server defaults, and a disabled master switch is clearly indicated. Older servers still support the existing `/msb` commands. With Mod Menu but without YACL, the configuration entry displays a missing-dependency message.

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
| `enabled` | `true` | Master switch; server `false` always disables storage for everyone |
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

Multiple stacks of cobblestone still count as one type. Actual stack merging always compares components, independently of the classification option, and respects each item's maximum stack size.

All options except `makeSpaceMode` require JSON booleans. Its value must be one of the three strings listed above. Omitted fields use defaults. Invalid JSON, unknown keys, invalid modes, and incorrect types are rejected without overwriting the file. Invalid server configuration disables automatic storage at startup; a failed runtime reload retains the previous active configuration.

Only ground-item pickup is handled. Chest transfers, crafting, item-giving commands, and manual dropping do not directly trigger storage. Ender chests and nested containers are not searched.

## Personal settings and languages

In-game messages support Simplified Chinese and English, following the player's Minecraft language. Players without the client mod receive readable localized fallback text from the server. Other languages fall back to English. Commands, JSON keys, and enum values use the same fixed English identifiers in both languages. The mod name and description include both languages.

Servers enforce their own settings by default. Administrators can change and persist the policy immediately:

```text
/msb admin player-settings true
/msb admin player-settings false
/msb admin reload
```

These commands require Minecraft's `COMMANDS_ADMIN` permission, normally OP level 3; the server console can also use them. Server `enabled=false` remains authoritative even when personal settings are allowed.

Once permitted, ordinary players can use these commands without a client installation:

```text
/msb
/msb show
/msb set enabled false
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
