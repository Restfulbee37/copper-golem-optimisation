# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/) with the target Minecraft version as
build metadata (`<mod version>+<Minecraft version>`).

## [Unreleased]

### Changed
- Ported to Minecraft 26.1 (accepts any 26.1.x). Minecraft 26.1 is unobfuscated,
  so the mod is now built against official Mojang names without Yarn mappings.
  Optimizations and config are unchanged.
- Requires Java 25, Fabric Loader 0.19.5+ and Fabric API for 26.1. Builds for
  Minecraft 1.21.11 continue on the `mc/1.21.11` branch.

## [0.2.0+1.21.11] - 2026-10-12

A rewrite that targets the code that actually makes copper golems expensive.

### Added
- **Fast player-look check**: copper golems skip their per-tick scan of every
  nearby entity when no player is close enough to be found. The result is
  identical to vanilla. This scan was what the server was running when the
  watchdog killed a 1,800-golem test world.
- **Fast nearby-entity sort**: the nearby-entity sensor sorts with precomputed
  distances and a stable sort. Same order as vanilla, about 2× faster.
- **Idle throttle**: golems in their "nothing to sort" cooldown run their
  sensors less often. The cooldown itself is unchanged, so sorting speed is too.
- `/cgp status` reports stacks taken/placed per minute so throughput can be
  compared between configurations.
- `/cgp profile on|off` measures the average cost of a golem tick.
- `/cgp reset` resets the counters.
- The mod also loads in singleplayer (integrated server).

### Changed
- The stationary sorter (opt-in) now only skips physics while a golem is not
  trying to move. It always runs full physics every 20 ticks and no longer
  throttles AI or brain tasks, so it no longer slows sorting or leaves golems
  stuck.
- Config moved to `config/copper_golem_mod.json`. A config file that can't be
  read is kept as `.bad` instead of being overwritten.
- `/cgp` uses the standard game-master permission level.
- Release jars include the Minecraft version, e.g.
  `copper_golem_mod-0.2.0+1.21.11.jar`.

### Removed
- The async planning and "unsafe parallel tick" modes, worker threads, lock
  manager and per-tick golem/inventory snapshots. They could not do useful
  work off the main thread and added per-tick overhead.

### Fixed
- Stationary golems could stop moving permanently because movement was
  skipped even when their AI wanted to walk.
- Per-golem stationary state was stored in a global map that was never
  cleared.
- The build referenced a JDK path from another machine.

## [0.1.x] - unreleased prototypes

Experimental builds that explored off-thread planning, lock-guarded "unsafe"
diagnostics and AI throttling for trapped golems. Superseded by 0.2.0.

