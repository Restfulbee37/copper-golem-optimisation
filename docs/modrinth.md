<!--
Public text for the Modrinth project page. Everything below the line is pasted into the project's
description; the summary goes in the "Summary" field. Keep it free of anything private: no GitLab links,
branch names, runner details or personal names. Update the numbers and the version table on each release.

Summary (max 256 characters):
Cuts the server lag of copper golems in big sorting farms. Golems sort exactly as in vanilla; the work behind it is just done more cheaply. Server-side, with an in-game settings screen.
-->

# Copper Golem Optimisation

Copper golems are great sorters, but a farm with hundreds of them can drag a server's tick rate down. This mod makes each golem much cheaper to run **without changing how it sorts**: the same items go to the same chests at the same speed.

On a copy of a real 865-golem sorting farm, with the mod on:

| | Without the mod | With the mod |
| --- | --- | --- |
| Milliseconds per tick (MSPT) | 173.7 | 104.5 (−40%) |
| Time spent on golems per tick | 107.4 ms | 37.6 ms (−65%) |
| Sorting speed | | unchanged |

*Minecraft 26.3, measured by the mod's built-in benchmark on a small quad-core ARM computer. Your numbers depend on your farm and hardware; see [Measure it yourself](#measure-it-yourself).*

## What it does

Most of the cost in a dense golem farm isn't the sorting. It's the golems noticing each other and the chests around them. The mod answers the same questions vanilla asks, more cheaply. These are all on by default and give exactly vanilla's results:

- **Looking for players.** Golems scan every nearby mob each tick for a player to look at. When no player is close enough to be found, the scan is skipped.
- **Sorting nearby mobs.** The once-a-second list of nearby mobs is sorted about twice as fast, in exactly the same order.
- **"Is anyone using this chest?"** Golems at a chest ask this every tick. The mod stops at the first player or golem it finds instead of listing everything nearby.
- **Finding the next chest.** Chests of the wrong kind, chests the golem has already tried and locked chests are ruled out before their contents are looked at. The golem picks the same chest.
- **Idle golems.** Golems with nothing to sort run their senses less often while they wait. They start sorting again on exactly the same tick as in vanilla; they only notice players and mobs around them a moment later.

### Optional: stationary sorter (changes behaviour, off by default)

For farms where every golem is trapped in its own cell and never needs to walk. Golems that are standing still skip movement physics and pushing. It saves a lot more, but golems take less cramming damage and aren't pushed apart, so test it on a copy of your world first. Turn it on with `/cgp accept-consequences` (twice, to confirm), then in the settings.

There's also a purely cosmetic switch to stop golems turning their heads towards players.

## Installation

1. Install [Fabric Loader](https://fabricmc.net/use/) and [Fabric API](https://modrinth.com/mod/fabric-api) on your server.
2. Download the version of this mod for your Minecraft version and put it in the server's `mods` folder.
3. Start the server. The defaults are safe.

**Server-side only.** Players don't need to install anything, and the settings screen works with an unmodded client. It also works in singleplayer.

## Settings

Operators can run **`/cgp config`** to open a settings screen with a switch, slider or text box for every setting. Changes apply straight away and are saved. You can also edit `config/copper_golem_mod.json` and run `/cgp reload`.

| Command | What it does |
| --- | --- |
| `/cgp config` | Opens the settings screen (lists the settings in the server console) |
| `/cgp config set <setting> <value>` | Changes one setting, with tab completion |
| `/cgp config reset <setting>` | Puts a setting back to its default |
| `/cgp status` | Shows the settings and how often each optimisation kicked in |
| `/cgp accept-consequences` | Allows the stationary sorter to be turned on |
| `/cgp bench ab` | Benchmarks the mod on against off (see below) |

All commands need operator permissions.

## Measure it yourself

Run `/cgp bench ab` while your farm is working. For about 10 minutes it switches the mod on and off every 30 seconds, then reports MSPT, TPS, the time spent on golems and the sorting speed for each side. If anything got slower, it says so. The results are also saved as a JSON file in `copper_golem_mod/benchmarks/`.

## Compatibility

- **Lithium:** compatible and recommended. The improvements add up.
- Other mods that change copper golem AI may conflict. Each optimisation can be switched off on its own if you need to find out which one.

## Supported versions

Each Minecraft version gets its own download. New features come to the newest version first, then to older versions that still get feature updates.

| Minecraft | Latest version | Support |
| --- | --- | --- |
| 26.4 | coming soon | |
| 26.3 | 1.0.0 | Feature updates until 26.4 is supported |
| 26.2 | 1.0.0 | Feature updates until about December 2026, then bug fixes until about March 2027 |
| 26.1 | 1.0.0 | Feature updates until about December 2026, then bug fixes until about March 2027 |
| 1.21.11 | 0.6.0 | Bug fixes until about January 2027. No settings screen or chest optimisations |

## FAQ

**Does it change how my farm sorts?** No. Everything on by default gives vanilla's exact results, and the mod is tested on every build against vanilla, including a full copy of a real farm. Only the stationary sorter changes behaviour, and it's off unless you turn it on.

**Do my players need it?** No, it's server-side only.

**Can I turn it off without removing it?** Yes: `/cgp config set enabled false`.
