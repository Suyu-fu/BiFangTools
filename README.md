<p align="center">
  <a href="README.zh-CN.md">简体中文</a> · <strong>English</strong>
</p>

<p align="center">
  <img src="artwork/bifangs-bag-of-curiosities-comic.png" alt="Chibi BiFang rummaging through a satchel" width="224">
</p>

<h1 align="center">BiFang Tools</h1>

<p align="center">
  <img src="https://img.shields.io/badge/Minecraft%20Java-1.21.1-397348" alt="Minecraft Java Edition 1.21.1">
  <img src="https://img.shields.io/badge/Minecraft%20Java-26.1-397348" alt="Minecraft Java Edition 26.1">
  <img src="https://img.shields.io/badge/NeoForge-Client-b4502d" alt="NeoForge client-side mod">
  <a href="#license"><img src="https://img.shields.io/badge/license-MIT%20%7C%20CC%20BY--NC--SA%204.0-green" alt="Code: MIT. Artwork: CC BY-NC-SA 4.0"></a>
</p>

<p align="center">
  <a href="#controls">Controls</a> ·
  <a href="#configuration">Configuration</a> ·
  <a href="CHANGELOG.md">Changelog</a> ·
  <a href="https://github.com/Suyu-fu/BiFangTools/releases">Releases</a> ·
  <a href="https://github.com/Suyu-fu/BiFangTools/issues">Report an issue</a>
</p>

**BiFang Tools** is a client-side quality-of-life mod for **Minecraft Java 1.21.1 and 26.1** on NeoForge. Download the JAR for your Minecraft version from [Releases](https://github.com/Suyu-fu/BiFangTools/releases) and place it in your client `mods` folder. The 26.1 source is maintained on the [`26.x` branch](https://github.com/Suyu-fu/BiFangTools/tree/26.x).

## Tools

| Tool | What it does |
| --- | --- |
| **Waythread** | Leave a glowing breadcrumb trail while exploring and retrace your steps back to the start, even across dimensions. |

### How Waythread works

- **Record & Retrace**: Mark your start point (a bright-outlined Floating Cube by default) and leave a glowing particle trail as you travel. Switch to return mode to follow the path back and clear points as you go.
- **Cross-Dimension Routes**: Records segments across dimensions and retraces them in reverse travel order. Rendering and pathfinding only run in your current dimension, while the recorded point limit and return progress are shared across all dimensions.
- A haloed relay orb matching your trail color marks the active dimension transition. If a normal return cannot reconnect automatically after a dimension change, move near the recorded trail and press **K**.
- After respawning, press **K** near a recorded waypoint to follow the trail from there to your death location. The range uses `returnEraseDistance` and defaults to two blocks. If the trip stalls after crossing dimensions, approach the relay orb at the next linked segment's entrance and press **K** to continue.
- **Singleplayer Persistence**: Routes and return progress in singleplayer worlds are saved to your client configuration directory and restored when you reopen the same world.

## Controls

| Key | Action |
| --- | --- |
| **J** (tap) | Mark the start and begin recording. While waiting to rejoin, start a new segment from your current position. |
| **K** | Switch between recording and return mode. While waiting to rejoin, press near the trail to reconnect. |
| **J** (hold 2s) | Clear the entire trail. |
| **Ctrl+J** | Show or hide trail particles. |

You can still record and retrace a route while particles are hidden. Trail visibility resets to shown after restarting the client. Rebind shortcuts anytime under **Options → Controls → Key Binds**.

## Configuration

From the main menu, open **Mods → BiFang Tools → Config → Waythread**, or edit `config/waythread-client.toml` in your Minecraft directory.

The settings screen has three tabs: **Trail**, **Start Marker**, and **Record & Return**. Display distance, particle spacing, and the particle limit are grouped on the **Trail** tab.

The in-game UI supports Simplified Chinese, Traditional Chinese (Taiwan), English (US), Japanese, Korean, Spanish (Spain and Mexico), and Brazilian Portuguese. Switch languages under **Options → Language**.

<details>
<summary>Full configuration reference</summary>

| Setting | Default | Description |
| --- | --- | --- |
| `loadDistance` | 40 blocks | How far from the player to generate and display trail particles. Range 4–64 blocks. |
| `recordingMode` | Travel Distance | Record by distance traveled (`TRAVELED_DISTANCE`) or straight-line distance from the last point (`DIRECT_DISTANCE`). |
| `recordPointSpacing` | 5 blocks | Distance between new points, measured according to the recording mode. Range 2–10 blocks. |
| `maxRecordedPoints` | 8,192 points | Shared across all dimensions. Includes the starting point and counts shared endpoints once. On reaching the limit, recording pauses with a one-time alert. Range 1,024–16,384 points. Lowering the limit keeps the current trail until trimmed or cleared. |
| `returnEraseDistance` | 2 blocks | During the return trip, trim the trail back to the nearest recorded point within this range. Reaching the start ends the return trip. Range 1–16 blocks. |
| `displayMode` | Trail Line | `LINE` connects the trail. `WAYPOINTS` marks only recorded points. Both show the start marker and support the moving direction highlight. |
| `particleStyle` | Glow | Choose Fine Dust (`DUST`), Electric Sparks (`SPARK`), Glow (`GLOW`), or Mist (`MIST`). The Particles start marker uses the same style. |
| `trailParticleSpacing` | 0.7 blocks | Distance between particles along the trail line. Range 0.1–2 blocks. |
| `trailParticleLimit` | Unlimited | Maximum trail particles per refresh. Range 1–2,048. Slide all the way right for Unlimited, stored as `0`. |
| `trailColor` | `#38E3FF` | Color of the trail and direction highlight in `#RRGGBB` format. |
| `anchorFollowsTrailColor` | On | Use the trail color for the start marker. |
| `anchorColor` | `#FFB036` | Start marker color when color matching is off. Use `#RRGGBB` format. |
| `anchorAppearance` | Floating Cube | Choose Particles (`PARTICLES`), Floating Orb (`ORB`), or Floating Cube (`CUBE`). Floating shapes feature an outline (bright outline on the cube). |
| `anchorSeeThrough` | Off | Show the start marker through blocks. |
| `anchorSeeThroughDistance` | 512 blocks | Start marker display range when see-through is on. Range 4–4,096 blocks. The trail uses its own display distance, and the marker stays recognizable at long range. |
| `showRecordingDirection` | On | While recording, show a moving highlight pointing toward the end of the trail. |
| `showReturnProgress` | On | Show a progress bar during the return trip. |
| `showReturnPercentage` | On | Show the percentage of recorded points cleared on the current return trip. |
| `returnProgressFontSize` | 7 px | Return percentage text size. Range 5–16 GUI pixels, scaling with Minecraft's GUI scale. |
| `showArrivalMessage` | On | Show an arrival message and play the experience orb chime when you reach the start. |
| `clearOnArrival` | On | Clear the start marker at the end of the return trip. With this off, the marker stays visible; tap **J** to start recording from your current position. |

</details>

## Credits

Created by **BiFang**. Waythread was inspired by [AtomicStryker's Magic Yarn](https://github.com/AtomicStryker/atomicstrykers-minecraft-mods/tree/1.7.10/MagicYarn). The mod ID is `bifangtools`.

## License

- The code is licensed under MIT. You may use, modify, and redistribute it, including for commercial purposes. Keep the copyright and license notices with redistributed copies. [Code license](LICENSE)
- The cover and mod icon are licensed under CC BY-NC-SA 4.0. You may share and adapt them for noncommercial purposes with attribution. If you share adapted artwork, indicate your changes and use the same license. Copies of the mod that include the artwork are also subject to the artwork license. [Artwork license](LICENSE-ASSETS)
