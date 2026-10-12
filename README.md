# CopperGolemMod

A Fabric mod that cuts the server tick cost of copper golems, especially in dense sorting farms, without changing how they sort.

- Minecraft `1.21.11`, Fabric Loader `0.19.2`, Fabric API `0.141.4+1.21.11`, Java 21
- Server-side logic only. Clients don't need it, and it also works in singleplayer.

## Install

1. Download `copper_golem_mod-<version>.jar` from the project's **Releases** page. Pick the release whose version ends in your Minecraft version (e.g. `0.2.0+1.21.11` is for Minecraft 1.21.11).
2. Put it in your server's `mods` folder alongside [Fabric API](https://modrinth.com/mod/fabric-api), and remove any older CopperGolemMod jar.
3. Start the server. The defaults are safe; see [Config](#config) for options.

Each release also has a `.sha256` file for checking the download.

## Why golem farms lag

In a farm with hundreds of copper golems packed together, most of the cost is not the sorting itself. It's the golems noticing each other:

1. **Looking for players.** Every tick, each golem without a look target walks its whole list of nearby living entities (everything within 16 blocks) looking for a player within 6 blocks to stare at. With 1,800 golems packed together that's about 3.2 million checks per tick, and the answer is almost always "no player here". A crash report from a 1,800-golem test world showed the server thread stuck inside this scan (`LookAtMobWithIntervalTask` → `LivingTargetCache.findFirst`) when the watchdog killed the server.
2. **Sorting the nearby-entity list.** Once a second each golem collects every living entity within 16 blocks and sorts it by distance. Vanilla recomputes two distances on every comparison.
3. **Physics and pushing.** Golems trapped in sorter cells still run full movement physics and push against each other every tick.

## What the mod does

### Vanilla-identical optimizations (on by default)

These give exactly the same result as vanilla; they just compute it more cheaply.

| Option | What it does |
| --- | --- |
| `optimizations.fastPlayerLookCheck` | Before the player-look scan, checks whether any player in the world is within 6 blocks. If none is, the scan can't find anything, so it's skipped. If a player is close, the normal vanilla scan runs. |
| `optimizations.fastNearbyEntitySort` | Computes each distance once and uses a stable sort, which gives the same list in the same order as vanilla. About 2× faster per sensor update. |

### Idle throttle (on by default)

When a golem searches and finds nothing to move (or has visited 10 chests), vanilla puts it in a 140-tick "transport cooldown". During that cooldown it cannot pick up or deliver items; it just wanders and looks around.

While a golem is in that cooldown, `idleThrottle` runs its sensors only 1 tick in every `sensorTickInterval` (default 4). The cooldown timer is ticked by a brain task, not a sensor, so it still runs at normal speed and golems start sorting again on the same tick they would in vanilla. The only visible difference is that idle golems notice nearby players and mobs slightly later. Golems that were just hurt are never throttled.

### Stationary sorter (off by default, behavior-changing)

For sorters where golems are deliberately trapped and never need to walk. Once a golem has stayed in the same block for `minStationaryTicks`, is on the ground, and is barely moving:

- `skipTravelWhenStationary` skips movement physics on ticks where the golem isn't trying to move. If its AI wants to walk, physics runs as normal.
- `skipCrammingWhenStationary` skips entity pushing and cramming checks on ticks where the golem isn't trying to move.
- Full physics still runs every `fullPhysicsTickInterval` ticks (default 20), so pistons, water, broken floors, and pushes are still picked up.

This does change behavior. Stationary golems take much less cramming damage and don't get pushed apart by each other. It is fine for trapped-golem cells, but test it on a copy of your world first. It needs two flags:

```json
"stationarySorter": {
  "enabled": true,
  "iUnderstandThisChangesCopperGolemBehavior": true
}
```

## Config

`config/copper_golem_mod.json` is created on first start. Missing options get their defaults, and the file is rewritten on every load so new options appear. If the file can't be parsed, the mod falls back to defaults and keeps the broken file as `copper_golem_mod.json.bad`.

```json
{
  "enabled": true,
  "optimizations": {
    "fastPlayerLookCheck": true,
    "fastNearbyEntitySort": true
  },
  "idleThrottle": {
    "enabled": true,
    "sensorTickInterval": 4
  },
  "stationarySorter": {
    "enabled": false,
    "iUnderstandThisChangesCopperGolemBehavior": false,
    "minStationaryTicks": 40,
    "skipTravelWhenStationary": true,
    "skipCrammingWhenStationary": true,
    "fullPhysicsTickInterval": 20,
    "maxHorizontalVelocitySquared": 0.0025
  },
  "metrics": {
    "logIntervalSeconds": 0
  }
}
```

`enabled: false` turns every optimization off at runtime (after `/cgp reload`) without removing the mod.

Upgrading from 0.1.x: the old `config/copper_golem_parallel.json` is no longer read and can be deleted.

## Commands

Operator only (permission level 2):

| Command | Effect |
| --- | --- |
| `/cgp status` | Current settings, golems ticked per server tick, stacks taken/placed per minute, and how often each optimization kicked in |
| `/cgp reset` | Reset the counters (start of a benchmark) |
| `/cgp reload` | Reload the config file |
| `/cgp profile on\|off` | Time every golem tick and report the average cost in `/cgp status` (adds a little overhead while on) |

## Benchmarking

Compare sorting throughput and tick time, not just one of them. See [TESTING.md](TESTING.md).

```text
/cgp reset
/cgp profile on
/spark profiler start --timeout 120 --only-ticks-over 50
... wait 2 minutes ...
/cgp status
```

Run once with `"enabled": false` and once with the defaults on the same world copy. `stacks placed/min` should stay the same, and golem tick time and MSPT should drop.

## Versioning

Versions are `<mod version>+<Minecraft version>`, for example `0.2.0+1.21.11`. The mod version follows [Semantic Versioning](https://semver.org/); the part after `+` is the Minecraft version the jar was built for. See [CHANGELOG.md](CHANGELOG.md) for what changed in each release.

## Build from source

Requires JDK 21.

```bash
./gradlew build
```

The jar is written to `build/libs/copper_golem_mod-<version>.jar`. See [CONTRIBUTING.md](CONTRIBUTING.md) for the development workflow, branches and the release process.

## History

0.1.x tried to parallelize golem ticking with worker threads, snapshots, and lock managers. None of it could do real work off the main thread, because Minecraft's world, entity, and brain objects aren't thread-safe. It only added overhead: every tick it copied each golem's target chest contents, queued jobs, and validated no-op decisions. The unsafe mode could also stall the main thread on lock timeouts. 0.2.0 removes all of that and targets the code that actually costs time.
