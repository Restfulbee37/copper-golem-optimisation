# Testing Copper Golem Optimisation

## Automated tests (every commit)

**Pull requests on GitHub** run `build` and `gametest:correctness` (below) with GitHub Actions ([`.github/workflows/ci.yml`](.github/workflows/ci.yml)).

**The maintainer's pipeline** runs all three jobs below, on every commit and on every pull request before it is merged. A change can only be merged when all three pass, and a release tag is only published when all three pass.

| Job | What it checks | Typical time |
| --- | --- | --- |
| `build` | Compiles, runs the JUnit tests (benchmark logic, config loading) and builds the jar | a few minutes |
| `gametest:correctness` | 28 in-game tests on a headless test server. Golems sort every item into the right chest (open area and trapped cell), wait while a chest is in use, skip chests that are blocked by a cat or already tried, look at nearby players, and keep vanilla timings, under the configs mod off, mod on, stationary sorter and look disabled. Also checks that the faster entity sort gives exactly vanilla's order, that the chest-in-use check and the chest search give exactly vanilla's answers, that the `/cgp config` commands and the settings screen work, and the workload benchmark's MSPT rules. | a few minutes |
| `gametest:workload` | The standard workload benchmark: a copy of the real stress-test farm (865 golems, 326 villagers, 774 chests) sorts a fixed item set four times, mod on, off, off, on. Fails if any item is lost or duplicated, if sorting is more than 5% slower (T90: time until 90% of items are sorted) or leaves more items unsorted with the mod on, or if the mod gives no measurable MSPT gain (see below). | 10-60 min, depending on the machine |

Results:

- Test results appear on each merge request and pipeline (the **Tests** tab).
- The workload job keeps its report as a downloadable artifact (`build/run/gameTest/copper_golem_mod/workload/workload-*.json`) for 90 days: T50/T90/T99 sorting times and a sorting curve, MSPT median/mean/p95, golem cost, item counts and where any unsorted items ended up, for every phase, plus the verdict. It also records the machine (CPU count, memory) the numbers came from, and the machine's load average and CPU temperature during each phase. Only compare numbers from the same machine.

How the workload judges MSPT, since the CI runner is shared with other projects' jobs:

- Phases are compared by their **median** MSPT. When another job runs at the same time, some ticks get much slower; that pushes the mean up but barely moves the median.
- A phase whose mean is more than 1.15 times its median is **disturbed** (clean phases measure 1.00 to 1.04). The on/off pair it belongs to (phases 1-2 or 3-4) runs once more, and the repeat counts instead.
- If the repeat is disturbed too and the mod shows no MSPT gain, the job passes **with a warning** (`PASSED WITH WARNINGS` in the job log, `"result": "passed with warnings"` in the report), because those numbers don't measure the mod. The exception: if the mod-on median is more than 10% **higher** than mod off, it fails even then, since medians stay reliable under spikes and that is far more than a busy runner explains.
- Lost or duplicated items and slower sorting are measured in game ticks, which other jobs don't affect, so they always fail.

### Running them locally

```bash
./gradlew runGameTest                                                         # everything (also part of ./gradlew build)
./gradlew runGameTest -Pgametest.filter='copper_golem_mod_test:correctness_tests_*'
./gradlew runGameTest -Pgametest.filter='copper_golem_mod_test:workload_benchmark_*'
./gradlew runGameTest -Pgametest.filter='copper_golem_mod_test:workload_benchmark_*' -Pworkload.stacksPerInput=8   # heavier workload
```

The test server is headless, needs no world or EULA file, and writes everything under `build/run/gameTest/`.

### The workload farm

The farm is stored in `src/gametest/resources/copper_golem_mod_test/workload/farm.json`. It was copied from the stress-test world with `tools/extract_farm.py`, which reads a world folder (it never modifies it) and writes every block, golem and villager in a box, with each chest classed as an input (copper chest) or an output (holding a marker item). To use a rebuilt or different farm:

```bash
python3 tools/extract_farm.py --world /path/to/world --box 46,-61,-48,72,-33,-15 \
    --out src/gametest/resources/copper_golem_mod_test/workload/farm.json
```

Each run fills the input chest of every cell the farm actually uses with 8 stacks of the item types its neighbouring output chests already hold. Cells whose chests are all empty aren't fed (the real farm doesn't use them either), but their golems are still there and still cost tick time. Golems are respawned at their original positions for every phase.

## Manual testing on a server

Always test on a copy of your world.

For every run, keep the `/cgp bench` JSON file. It records the Minecraft, mod and Java versions, other installed mods (e.g. Lithium), the config and every measurement. Add the golem count, farm design and any Spark profile links.

## Baseline vs. mod: `/cgp bench ab`

1. Start the server with the config you want to test (`enabled` can stay `true`; the benchmark switches it).
2. Start feeding the farm items, and keep a player nearby so the chunks stay loaded, but not inside the farm.
3. Run `/cgp bench ab` (10 minutes, 30 s phases). For noisy worlds use a longer run, e.g. `/cgp bench ab 20`.
4. Wait for the report, or check `/cgp bench status`. Don't run `/cgp reload` during the run; it stops the benchmark.
5. Check the verdict lines:
   - `OK: MSPT is X% lower with the mod on` means the optimizations work.
   - `OK: sorting speed per game tick is not lower` means sorting isn't harmed.
   - Any `WARNING` means an optimization is broken or slowing golems down on this version. Open an issue with the JSON file.
   - `INCONCLUSIVE` or `NOT ENOUGH SORTING` means run longer, or feed more items.
6. Keep the JSON file from `copper_golem_mod/benchmarks/`.

To compare **versions or other mods** (a new Minecraft version, a new mod build, Lithium on/off), run `/cgp bench current` once per setup on the same world copy and compare the JSON files. For a quick mod on vs off check, `/cgp bench ab` is better because both sides run in the same session.

If you also use Spark, profile **without** `--only-ticks-over` when comparing runs. That option records different subsets of ticks in each run and makes comparisons misleading.

Suggested golem counts: 18, 72, 144, 432, 800+.

## Behavior checks

1. **Idle farm.** Empty input chests. Golems should wander and idle normally, and `idle sensor ticks skipped` should climb.
2. **Restart sorting.** Put items into an input chest in an idle farm. Golems should pick them up just as quickly as without the mod.
3. **Player nearby.** Stand next to golems. They should still turn to look at you (`player-look scans: run` increases).
4. **Hurt a golem.** It should still panic and flee immediately.
5. **Item count.** Count items before and after a run (source, destination, and golem-held). Totals must match. The mod never touches inventories, so any mismatch is a vanilla or farm-design issue, but it's worth confirming.
6. **Server stop/start and chunk unload/reload** during sorting: no errors in the log.

## Stationary sorter

Test separately, only for designs where golems are trapped:

1. Enable it (both `enabled` and `iUnderstandThisChangesCopperGolemBehavior`), then `/cgp reload`.
2. After a couple of seconds, `stationary: N travel skipped, N cramming skipped` should climb.
3. Compare `stacks placed/min` with the default-only run. It should be unchanged.
4. Break the floor under a trapped golem. It should fall within `fullPhysicsTickInterval` ticks.
5. Push golems with a piston. They should move.
6. If the counters stay at zero, the golems aren't settling in one block. Check that they're on solid ground and not in water, in vehicles, or leashed.

## Compatibility

Test with and without Lithium. This mod replaces `NearestLivingEntitySensor.doTick` for copper golems only (when `fastNearbyEntitySort` is on), and wraps `TransportItemsBetweenContainers.isAnotherMobInteractingWithTarget` and `isTargetValidToPick` for copper golems (when `fastChestInUseCheck` and `fastChestSearch` are on). If another mod also changes one of those methods, turn the matching setting off and compare.
