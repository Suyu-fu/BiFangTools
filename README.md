<p align="center">
  <a href="README.zh-CN.md">简体中文</a> · <strong>English</strong>
</p>

<p align="center">
  <img src="artwork/bifangs-bag-of-curiosities-comic.png" alt="Chibi BiFang rummaging through a satchel" width="224">
</p>

<h1 align="center">BiFang Tools</h1>

<p align="center">
  <img src="https://img.shields.io/badge/Minecraft%20Java-1.21.1-397348" alt="Minecraft Java Edition 1.21.1">
  <img src="https://img.shields.io/badge/NeoForge-Client-b4502d" alt="NeoForge client-side mod">
  <a href="#license"><img src="https://img.shields.io/badge/license-MIT%20%7C%20CC%20BY--NC--SA%204.0-green" alt="Code: MIT. Artwork: CC BY-NC-SA 4.0"></a>
</p>

<p align="center">
  <a href="#controls">Controls</a> ·
  <a href="#configuration">Configuration</a> ·
  <a href="CHANGELOG.md">Changelog</a> ·
  <a href="https://github.com/Suyu-fu/BiFangTools/issues">Report an issue</a>
</p>

BiFang Tools is a client-side Minecraft mod by BiFang that adds practical tools for everyday play.

## Tools

| Tool | What it does |
| --- | --- |
| Waythread | Record your route and follow a particle trail back to the start. |

## Controls

| Key | Action |
| --- | --- |
| **J** (tap) | Mark the start and begin recording your route. |
| **K** | Switch between recording and return mode. |
| **J** (hold for 2 seconds) | Clear the trail. |
| **Ctrl+J** | Show or hide trail particles. |

You can still record and retrace a route while the particles are hidden. The trail is visible by default after a client restart. Rebind the shortcuts under **Options → Controls → Key Binds**.

## Configuration

From the main menu, open **Mods → BiFang Tools → Config → Waythread**, or edit `config/waythread-client.toml` in your Minecraft directory.

The interface has three tabs, **Trail**, **Start Marker**, and **Record & Return**. Display distance, particle spacing, and the particle limit are grouped together on the **Trail** tab.

The in-game interface supports Simplified Chinese, Traditional Chinese (Taiwan), English (US), Japanese, Korean, Spanish (Spain and Mexico), and Brazilian Portuguese. Switch languages in Minecraft under **Options → Language**.

<details>
<summary>Full configuration reference</summary>

| Setting | Default | Description |
| --- | --- | --- |
| `loadDistance` | 40 blocks | How far from the player to generate and display trail particles. Range 4–64 blocks. |
| `recordPointSpacing` | 5 blocks | Distance traveled along the route before recording the next point. Range 2–10 blocks. Older settings outside this range are adjusted to the nearest bound. |
| `maxRecordedPoints` | 8,192 points | Includes the starting point and counts shared endpoints once. On reaching the limit, the mod pauses recording and alerts you once. Range 1,024–16,384 points. Lowering the limit keeps the current trail until you trim or clear it. |
| `returnEraseDistance` | 2 blocks | During the return trip, trim the trail back to the nearest recorded point within this range. Reaching the start ends the return trip. Range 1–16 blocks. |
| `displayMode` | Trail Line | `LINE` connects the trail. `WAYPOINTS` marks only recorded points. Both show the start marker and support the moving direction highlight. |
| `particleStyle` | Glow | Choose Fine Dust (`DUST`), Electric Sparks (`SPARK`), Glow (`GLOW`), or Mist (`MIST`). The Particles start marker uses the same style. |
| `trailParticleSpacing` | 0.7 blocks | Distance between particles along the trail line. Range 0.1–2 blocks. |
| `trailParticleLimit` | 90 particles | Maximum trail particles per refresh. Range 1–2,048. Slide all the way right for Unlimited, which is stored as `0`. |
| `trailColor` | `#38E3FF` | Color of the trail and direction highlight in `#RRGGBB` format. |
| `anchorFollowsTrailColor` | On | Use the trail color for the start marker. |
| `anchorColor` | `#FFB036` | Start marker color when color matching is off. Use `#RRGGBB` format. |
| `anchorAppearance` | Floating Cube | Choose Particles (`PARTICLES`), Floating Orb (`ORB`), or Floating Cube (`CUBE`). The floating shapes have a thin outline. |
| `anchorSeeThrough` | Off | Show the start marker through blocks. |
| `anchorSeeThroughDistance` | 512 blocks | Start marker display range when see-through is on. Range 4–4,096 blocks. The trail uses its own display distance. The marker stays recognizable at long distances. |
| `showRecordingDirection` | On | While recording, show a moving highlight that points toward the end of the trail. |
| `showReturnProgress` | On | Show a progress bar during the return trip. |
| `showReturnPercentage` | On | Show the percentage of recorded points cleared on the current return trip. |
| `returnProgressFontSize` | 7 px | Return percentage text size. Range 5–16 GUI pixels and follows Minecraft's GUI scale. |
| `showArrivalMessage` | On | Show an arrival message and play the experience orb sound when you get back to the start. |
| `clearOnArrival` | On | Clear the start marker at the end of the return trip. With this off, the marker stays visible. Tap **J** to start recording from your current position. |

</details>

## Credits

Created by **BiFang**. Waythread was inspired by [AtomicStryker's Magic Yarn](https://github.com/AtomicStryker/atomicstrykers-minecraft-mods/tree/1.7.10/MagicYarn). The mod ID is `bifangs_bag_of_curiosities`.

## License

- The code is licensed under MIT. You may use, modify, and redistribute it, including for commercial purposes. Keep the copyright and license notices with redistributed copies. [Code license](LICENSE)
- The cover and mod icon are licensed under CC BY-NC-SA 4.0. You may share and adapt them for noncommercial purposes with attribution. If you share adapted artwork, indicate your changes and use the same license. Copies of the mod that include the artwork are also subject to the artwork license. [Artwork license](LICENSE-ASSETS)
