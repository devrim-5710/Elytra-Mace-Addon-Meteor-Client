# Elytra Mace

Meteor Client addon for Minecraft **26.2** / Fabric.

**Author:** theflex5710

Adds the `elytra-mace` module (Combat category): flies up with an elytra, dive-bombs the
nearest target and hits it with a mace, then repeats.

## Requirements

- JDK **25**
- IntelliJ IDEA (or any other IDE)
- In game: Fabric Loader `0.19.3` + Meteor Client (`26.2-SNAPSHOT`) + Fabric API

## Installation

```powershell
.\gradlew.bat build
```

Output: `build/libs/elytra-mace-0.1.0.jar` → drop it into the `.minecraft\mods` folder
alongside Meteor Client.

To test from the IDE, run the `Minecraft Client` run configuration (starts the game with
Meteor Client and this addon loaded).

## Module

**Combat → Elytra Mace**

Requires an **elytra**, **firework rockets** and a **mace** in your inventory — if any of
them is missing, the module resets itself.

Settings:

| Group | Setting | Type | Default |
|---|---|---|---|
| Targets | `players` | Bool | `true` |
| Targets | `monsters` | Bool | `true` |
| Targets | `animals` | Bool | `false` |
| Targets | `neutrals` | Bool | `false` |
| Targets | `bosses` | Bool | `true` |
| Targets | `target-range` | Double (1–60) | `60.0` |
| Flight | `ascent-height` | Double (2–40) | `8.5` |
| Flight | `attack-cooldown` | Double (0.05–1.0) | `0.05` |

Behaviour: `IDLE → ASCENDING → DIVING → COOLDOWN` state machine. It equips the elytra,
ascends `ascent-height` blocks above the target using rockets, dives, unequips the elytra,
swaps to the mace and attacks within 4.5 blocks, then waits `attack-cooldown` seconds and
starts the next cycle.

## Project structure

```text
src/main/java/com/elytramace/addon/
│── ElytraMaceAddon.java         # Main class: module/command/HUD registration
│── commands/CommandExample.java
│── hud/HudExample.java
│── mixin/ExampleMixin.java
│── modules/
│   ├── ElytraMace.java          # The actual module
│   └── ModuleExample.java
src/main/resources/
│── fabric.mod.json
│── elytra-mace.mixins.json
│── assets/elytramace/icon.png
```

- Versions: `gradle/libs.versions.toml` (`minecraft`, `fabric-loader`, `meteor`, `loom`)
- Package / group / jar name: `gradle.properties`

## Updating to a newer Minecraft version

1. Check that a Meteor Client snapshot exists for the new Minecraft version.
2. Update `gradle/libs.versions.toml`: `minecraft`, `fabric-loader`, `meteor`, `loom`.
3. Refresh Gradle dependencies and rebuild.
4. Regenerate the wrapper if needed:
   `.\gradlew.bat wrapper --gradle-version <version> && .\gradlew.bat wrapper`
5. Fix any Minecraft API changes in the source code, then `.\gradlew.bat build`.

## Notes

- Ported from a 1.21.11 addon; the Minecraft calls were remapped to 26.2 mojmap
  (`ClickType` → `ContainerInput`, `handleInventoryMouseClick` → `handleContainerInput`,
  `Fireworks.comp_2391()` → `flightDuration()`).
- The project lives inside OneDrive, which makes Loom builds slower. If you run into file
  locking issues, move it to a non-synced folder (e.g. `C:\dev`).

## License

CC0.
