# CopperGolemMod testing

Always test on a copy of your world.

For every run, record: Minecraft/Fabric/Fabric API/Java versions, whether Lithium (or other performance mods) is installed, the golem count, the config used, the Spark profile link, and the `/cgp status` output.

## Baseline vs. mod

Use the same world copy, the same item input, and the same duration for each run.

1. Set `"enabled": false` in `config/copper_golem_mod.json` and run `/cgp reload`.
2. `/cgp reset`, `/cgp profile on`, `/spark profiler start --timeout 120 --only-ticks-over 50`.
3. Feed a known item stream for at least 2 minutes, then run `/cgp status` and save the Spark link.
4. Set `"enabled": true` (defaults), `/cgp reload`, and repeat steps 2–3.
5. Compare:
   - `stacks placed/min` should be about the same. The default optimizations don't change sorting.
   - `us per golem tick` and MSPT should drop.
   - `player-look scans: N skipped` should be large when no player stands inside the farm.

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
