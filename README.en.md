# BiFang Tools

[简体中文](README.md) · **English**

<img src="artwork/bifangs-bag-of-curiosities-comic.png" alt="Chibi BiFang rummaging through a satchel" width="256">

Little tools for everyday Minecraft.

BiFang Tools is a client-side NeoForge mod for Minecraft Java Edition. The current build supports **Minecraft 1.21.1**.

The in-game interface supports Simplified Chinese, Traditional Chinese (Taiwan), English (US), Japanese, Korean, Spanish (Spain and Mexico), and Brazilian Portuguese. Switch languages in Minecraft under **Options → Language**.

Created by **BiFang**. The mod ID is `bifangs_bag_of_curiosities`.

The code is licensed under MIT. You may use, modify, and redistribute it, including for commercial purposes. Keep the copyright and license notices with redistributed copies. [Code license](LICENSE)

The cover and other original BiFang artwork are licensed under CC BY-NC-SA 4.0. You may share and adapt them for noncommercial purposes with attribution. If you share adapted artwork, indicate your changes and use the same license. Copies of the mod that include the artwork are also subject to the artwork license. [Artwork license](LICENSE-ASSETS)

## Installation

Place `build/libs/bifang-tools-0.5.3.jar` in your client's `mods` folder. When upgrading, remove the previous JAR from `mods` first.

## Waythread

Record the route you travel and follow a particle trail back to where you started. Inspired by [AtomicStryker's Magic Yarn](https://github.com/AtomicStryker/atomicstrykers-minecraft-mods/tree/1.7.10/MagicYarn).

1. Tap **J** to mark the starting point. As you travel, the mod marks your route with a trail line or waypoints.
2. Press **K** to switch to return mode. A moving highlight points toward the start. When you get close to a recorded point, the trail is trimmed back to the nearest point within the configured range. Press **K** again to resume recording and attach the route you actually walked from the last point you rejoined. If a short connection is blocked or a teleport occurred, the trail stays split.
3. Hold **J** for two seconds to clear the trail.
4. Press **Ctrl+J** to toggle trail particles. You can still record and retrace a route while the particles are hidden. The trail is visible by default after a client restart. Rebind the shortcuts under **Options → Controls → Key Binds**.

Waythread samples the route you actually travel. It records a point every five blocks by default, and marks sharp turns sooner to end the current segment. In Trail Line mode, A* searches for a walkable route between each nearby segment's endpoints, close to the recorded samples. If it cannot find one, the particles connect the sampled points directly. Teleporting starts a separate segment. Trails are cleared when you leave the world, change dimensions, or respawn.

## Configuration

From the main menu, open **Mods → BiFang Tools → Config → Waythread**, or edit `config/waythread-client.toml` in your Minecraft directory.

The interface has three tabs, **Trail**, **Start Marker**, and **Record & Return**. Display distance, particle spacing, and the particle limit are grouped together on the **Trail** tab.

| Setting | Default | Description |
| --- | --- | --- |
| `loadDistance` | 18 blocks | How far from the player to generate and display trail particles. Range 4–64 blocks. |
| `recordPointSpacing` | 5 blocks | Distance traveled along the route before recording the next point. Range 2–10 blocks. Older settings outside this range are adjusted to the nearest bound. |
| `maxRecordedPoints` | 20,000 points | Includes the starting point and counts shared endpoints once. On reaching the limit, the mod pauses recording and alerts you once. Range 2–200,000 points. Lowering the limit keeps the current trail until you trim or clear it. |
| `returnEraseDistance` | 2 blocks | During the return trip, trim the trail back to the nearest recorded point within this range. Reaching the start ends the return trip. Range 0.5–6 blocks. |
| `displayMode` | Trail Line | `LINE` connects the trail. `WAYPOINTS` marks only recorded points. Both show the start marker and support the moving direction highlight. |
| `particleStyle` | Fine Dust | Choose Fine Dust (`DUST`), Electric Sparks (`SPARK`), Glow (`GLOW`), or Mist (`MIST`). The Particles start marker uses the same style. |
| `trailParticleSpacing` | 0.5 blocks | Distance between particles along the trail line. Range 0.1–2 blocks. |
| `trailParticleLimit` | 90 particles | Maximum trail particles per refresh. Range 1–2,048. Slide all the way right for Unlimited, which is stored as `0`. |
| `trailColor` | `#38E3FF` | Color of the trail and direction highlight in `#RRGGBB` format. |
| `anchorFollowsTrailColor` | On | Use the trail color for the start marker. |
| `anchorColor` | `#FFB036` | Start marker color when color matching is off. Use `#RRGGBB` format. |
| `anchorAppearance` | Particles | Choose Particles (`PARTICLES`), Floating Orb (`ORB`), or Floating Cube (`CUBE`). The floating shapes have a thin outline. |
| `anchorSeeThrough` | Off | Show the start marker through blocks. |
| `anchorSeeThroughDistance` | 512 blocks | Start marker display range when see-through is on. Range 4–4,096 blocks. The trail uses its own display distance. The marker stays recognizable at long distances. |
| `showRecordingDirection` | On | While recording, show a moving highlight that points toward the end of the trail. |
| `showReturnProgress` | On | Show a progress bar during the return trip. |
| `showReturnPercentage` | On | Show the percentage of recorded points cleared on the current return trip. |
| `returnProgressFontSize` | 7 px | Return percentage text size. Range 5–16 GUI pixels and follows Minecraft's GUI scale. |
| `showArrivalMessage` | On | Show an arrival message and play the experience orb sound when you get back to the start. |
| `clearOnArrival` | On | Clear the start marker at the end of the return trip. With this off, the marker stays visible. Tap **J** to start recording from your current position. |

## Building

Use JDK 21. From the project root, run `.\gradlew.bat build` on Windows or `./gradlew build` on other platforms. Use the `runClient` task to launch a development client.

See the [development log (Chinese)](DEVLOG.md) for version history and unreleased changes.
