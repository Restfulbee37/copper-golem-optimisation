# CopperGolemMod testing

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

Test with and without Lithium. This mod replaces `NearestLivingEntitySensor.doTick` for copper golems only (when `fastNearbyEntitySort` is on). If another mod also changes that method, turn `fastNearbyEntitySort` off and compare.
