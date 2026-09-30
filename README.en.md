# Magic Shulker Boxes

[简体中文](README.md) | **English**

Automatic shulker storage and refilling for **Minecraft Java 1.21.11 · Fabric · Java 21**, licensed under MIT. The current version is **0.4.0**.

After enabling pickup storage, store overflow in matching, empty, then mixed boxes, with an optional single-type fallback. Pickup storage is off by default. Supports Carpet shulker stacking, safe box splitting, automatic space-making, partial stacks and mixed-box reuse.

- Server-only installation works for multiplayer; singleplayer and LAN storage runs on the host.
- Pickup storage and schematic refilling have independent switches. Servers can permit personal preferences, letting players override either server default.
- Optional Mod Menu + YACL settings in English and Simplified Chinese.
- IPN consumable and tool refilling can use backpack shulker boxes, retaining IPN's triggers, matching and sorting, independently controlled by `ipnRefill`.
- Inventory/crafting-table ingredients can refill from carried boxes for recipe placement, output clicks and Shift crafting, independently controlled by `craftRefill` without IPN.
- 0.2.0-alpha adds Litematica Easy Place refilling from inventory shulker boxes, with optional space-making and failure notices. Both client and server need the new version for this feature.

[Download](https://github.com/chenjicheng/magic-shulker-boxes/releases) · [User guide](https://chenjicheng.github.io/magic-shulker-boxes/en/guide.html) · [Development](https://chenjicheng.github.io/magic-shulker-boxes/en/development.html)

## Install

Install `magic-shulker-boxes-fabric-0.4.0+mc1.21.11.jar` and Fabric API with Fabric Loader 0.18.4 or newer. Do not install the sources JAR. Remove older mod JARs before upgrading.

0.4.0 retains 0.3.2 settings and choices; new `ipnRefill` and `craftRefill` switches default to enabled. See the [0.4.0 release notes](docs/releases/0.4.0.md) for features and installation requirements.

Upgrading to 0.3.2 backs up 0.3.1 settings, renames `enabled` to `pickupStorageEnabled`, and preserves its value. New files use `configVersion: 2`. When the server permits personal settings, a player may independently enable pickup storage and material refilling even if the server defaults are off. Update both sides for personal settings and refilling. See the [0.3.2 release notes](docs/releases/0.3.2.md) for migration details and backup locations.

The client settings screen additionally needs [Mod Menu 17.0.1](https://modrinth.com/mod/modmenu/version/17.0.1) and [YACL 3.8.2](https://modrinth.com/mod/yacl/version/3.8.2+1.21.11-fabric). Dedicated servers do not need either library. Carpet is optional; enable its stacking rule separately.

Read the [user guide](docs/en/guide.md) for all behavior and configuration details, or the [development guide](docs/en/development.md) for building, testing and releases.

## Build

With JDK 21, run `./gradlew build` (Windows: `.\gradlew.bat build`). Documentation uses VitePress: `npm ci && npm run docs:build`.

CI tests both normal and Carpet environments. Pushing a `v*` tag invokes the Release workflow to validate, build and publish artifacts. Actions deploys documentation from `main` to GitHub Pages.
