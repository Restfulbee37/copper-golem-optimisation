# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/) with the target Minecraft version as
build metadata (`<mod version>+<Minecraft version>`).

## [Unreleased]

### Added
- `optimizations.fastChestInUseCheck` (on by default, vanilla-identical):
  golems near a chest ask every tick whether anyone is using it. The mod
  gives the same yes or no as vanilla, but stops at the first viewer and only
  looks at players and copper golems, and only checks a double chest's other
  half for sitting cats when someone is using that half. Golems still wait
  while a chest is in use.
- `optimizations.fastChestSearch` (on by default, vanilla-identical): when a
  golem searches for a chest, chests of the wrong kind, chests it has already
  tried and locked chests are ruled out before their inventories are built.
  The golem picks the same chest as in vanilla, and a cat on a chest still
  blocks it.
- `/cgp status` shows how often each of these kicked in.

## [0.7.0+26.2] - 2026-11-01

Adds the settings screen from 0.7.0+26.3: `/cgp config` opens a screen with
every setting, so operators can change them without typing commands. Players
don't need the mod for it. Golem performance is unchanged from 0.6.0.

Minecraft 26.2 keeps getting feature updates until about 1 December 2026, then
bug fixes until about 1 March 2027.

### Added
- Settings screen: `/cgp config` now opens a screen with a switch, slider or
  text box for every setting. It uses vanilla dialogs, so players don't need
  the mod. Save applies and saves the changed settings by running
  `/cgp config apply` as the player who pressed it, so the same permissions,
  limits and logging apply. The stationary sorter only appears once its
  consequences have been accepted. From the server console, `/cgp config`
  still lists the settings, as does `/cgp config list`.
- `/cgp config apply <setting>=<value> ...` changes several settings at once,
  all or nothing.

## [0.6.0+26.2] - 2026-10-26

Adds in-game configuration: operators can see and change every setting with
`/cgp config` (with tab completion) instead of editing the config file, and
turn on the stationary sorter with `/cgp accept-consequences`. Golem
performance is unchanged from 0.5.0.

### Added
- `/cgp config` to see and change settings in game: `list`, `get <setting>`,
  `set <setting> <value>` and `reset <setting>`. Changes apply at once, are
  saved to the config file, and are reported to other operators and the server
  log. Setting names and values tab-complete, and values outside the allowed
  range are rejected. Settings can't be changed while a benchmark runs.
- `/cgp accept-consequences`: accepts that the stationary sorter changes golem
  behavior. Run it twice to confirm. `stationarySorter.enabled` can't be turned
  on in game until it has been accepted.

### Changed
- The config file now caps `stationarySorter.maxHorizontalVelocitySquared` at
  1.0 (values above are lowered to 1.0), the same limit `/cgp config` uses.

## [0.5.0+26.2] - 2026-10-22

First release for Minecraft 26.2, with the same features as 0.5.0+26.1.

Measured by the workload benchmark on the CI runner, with the mod on: MSPT down
24% and golem time per tick down 41%, with sorting speed unchanged.

### Changed
- Ported to Minecraft 26.2 (Fabric API 0.161.0+26.2). Builds for Minecraft
  26.1 continue on the `mc/26.1` branch.

## [0.5.0+26.1] - 2026-10-22

Adds automated testing: in-game tests and a standard workload benchmark now run
on every commit. The mod itself behaves exactly like 0.4.0, and the config is
unchanged.

Measured by the new workload benchmark on the CI runner (a copy of a real
865-golem farm), with the mod on: MSPT 189.5 → 137.0 ms (−28%) and golem time
per tick 120.2 → 67.2 ms (−44%), with sorting speed unchanged.

### Added
- Automated in-game tests (Fabric GameTest) that run on every commit. They
  check that golems still sort every item into the right chest, look at nearby
  players and keep vanilla timings under every config, and that the faster
  entity sort gives exactly vanilla's order.
- Standard workload benchmark on every commit: a copy of a real 865-golem
  farm with its villager floor sorts a fixed item set with the mod on and off.
  The pipeline fails on lost or duplicated items, slower sorting, or no MSPT
  gain.
- `tools/extract_farm.py` to copy a farm from any world into the benchmark.
- `CopperGolemMod.setConfigOverride` for applying a config in memory (used by
  the tests).
- Supported versions section in the README: which Minecraft versions get
  feature updates or bug fixes, and for how long.

## [0.4.0+26.1] - 2026-10-19

Adds an opt-in switch to stop copper golems looking at players.

### Added
- `behavior.golemsLookAtPlayers` config option (default `true`, vanilla). Set it
  to `false` to stop copper golems turning to look at nearby players. This is
  cosmetic only and doesn't affect sorting. `/cgp status` shows the setting and
  how often looking was skipped because of it.
- JUnit tests for config loading (old files, the new option, broken files).

## [0.3.0+26.1] - 2026-10-19

First release for Minecraft 26.1, and the release that adds the `/cgp bench`
benchmark.

Measured with `/cgp bench ab` (864 copper golems, Lithium installed): MSPT
41.6 → 36.0 ms (−13.5%) and golem time per tick 18.4 → 12.4 ms (−33%) with the
mod on, with no change in sorting speed.

### Added
- `/cgp bench ab [minutes] [phaseSeconds]`: A/B benchmark that switches the mod
  on and off in memory (on, off, off, on, …) and reports MSPT, TPS, golem tick
  cost and stacks placed per 1,000 game ticks for each side, with a verdict
  that warns if the mod made ticks or sorting slower. Results are saved as JSON
  in `copper_golem_mod/benchmarks/`.
- `/cgp bench current [minutes]`, `/cgp bench status` and `/cgp bench stop`.
- JUnit tests (run in CI) for the benchmark logic.
- README: Lithium compatibility notes and example results.

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

