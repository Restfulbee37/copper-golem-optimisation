package com.restfulbee.coppergolemmod;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.ObjDoubleConsumer;
import java.util.function.ObjIntConsumer;
import java.util.function.ToDoubleFunction;
import java.util.function.ToIntFunction;

/**
 * Every config option that {@code /cgp config} can read and change, keyed by its path in the JSON file
 * (e.g. {@code idleThrottle.sensorTickInterval}). Number limits live here so the commands and the config
 * loader always agree on them.
 */
public final class ConfigSettings {
    /** One config option. {@link #get} returns a Boolean, Integer or Double depending on the type. */
    public sealed interface Setting permits Switch, Whole, Decimal {
        String key();

        String description();

        Object get(CopperGolemModConfig config);

        default Object defaultValue() {
            return get(new CopperGolemModConfig());
        }

        default boolean isDefault(CopperGolemModConfig config) {
            return get(config).equals(defaultValue());
        }

        /** What values are allowed, for messages, e.g. {@code true or false} or {@code 1 to 20}. */
        String allowed();
    }

    public record Switch(String key, String description, Function<CopperGolemModConfig, Boolean> getter,
                         BiConsumer<CopperGolemModConfig, Boolean> setter) implements Setting {
        @Override
        public Boolean get(CopperGolemModConfig config) {
            return getter.apply(config);
        }

        public void set(CopperGolemModConfig config, boolean value) {
            setter.accept(config, value);
        }

        @Override
        public String allowed() {
            return "true or false";
        }
    }

    public record Whole(String key, String description, int min, int max, ToIntFunction<CopperGolemModConfig> getter,
                        ObjIntConsumer<CopperGolemModConfig> setter) implements Setting {
        @Override
        public Integer get(CopperGolemModConfig config) {
            return getter.applyAsInt(config);
        }

        public void set(CopperGolemModConfig config, int value) {
            setter.accept(config, value);
        }

        @Override
        public String allowed() {
            return min + " to " + max;
        }
    }

    public record Decimal(String key, String description, double min, double max, ToDoubleFunction<CopperGolemModConfig> getter,
                          ObjDoubleConsumer<CopperGolemModConfig> setter) implements Setting {
        @Override
        public Double get(CopperGolemModConfig config) {
            return getter.applyAsDouble(config);
        }

        public void set(CopperGolemModConfig config, double value) {
            setter.accept(config, value);
        }

        @Override
        public String allowed() {
            return min + " to " + max;
        }
    }

    public static final Switch ENABLED = new Switch("enabled",
        "Master switch. When false the mod changes nothing and only counts golem ticks.",
        c -> c.enabled, (c, v) -> c.enabled = v);
    public static final Switch FAST_PLAYER_LOOK_CHECK = new Switch("optimizations.fastPlayerLookCheck",
        "Skip the look-at-player scan when no player is close enough to be found. Vanilla-identical.",
        c -> c.optimizations.fastPlayerLookCheck, (c, v) -> c.optimizations.fastPlayerLookCheck = v);
    public static final Switch FAST_NEARBY_ENTITY_SORT = new Switch("optimizations.fastNearbyEntitySort",
        "Sort the nearby-entity list with precomputed distances. Vanilla-identical.",
        c -> c.optimizations.fastNearbyEntitySort, (c, v) -> c.optimizations.fastNearbyEntitySort = v);
    public static final Switch FAST_CHEST_IN_USE_CHECK = new Switch("optimizations.fastChestInUseCheck",
        "Answer \"is anyone using this chest?\" by stopping at the first viewer. Vanilla-identical.",
        c -> c.optimizations.fastChestInUseCheck, (c, v) -> c.optimizations.fastChestInUseCheck = v);
    public static final Switch FAST_CHEST_SEARCH = new Switch("optimizations.fastChestSearch",
        "Rule out wrong-type, visited and locked chests before building their inventories. Vanilla-identical.",
        c -> c.optimizations.fastChestSearch, (c, v) -> c.optimizations.fastChestSearch = v);
    public static final Switch IDLE_THROTTLE = new Switch("idleThrottle.enabled",
        "Run the sensors of golems with nothing to sort less often. Sorting speed is unchanged.",
        c -> c.idleThrottle.enabled, (c, v) -> c.idleThrottle.enabled = v);
    public static final Whole SENSOR_TICK_INTERVAL = new Whole("idleThrottle.sensorTickInterval",
        "While idle, run the sensors on 1 out of every N ticks. 1 disables the throttle.",
        1, 20, c -> c.idleThrottle.sensorTickInterval, (c, v) -> c.idleThrottle.sensorTickInterval = v);
    public static final Switch STATIONARY_SORTER = new Switch("stationarySorter.enabled",
        "BEHAVIOR-CHANGING. Skip movement physics and entity pushing for golems standing still in sorter cells. "
            + "Needs /cgp accept-consequences first.",
        c -> c.stationarySorter.enabled, (c, v) -> c.stationarySorter.enabled = v);
    public static final Switch STATIONARY_SORTER_CONSENT = new Switch("stationarySorter.iUnderstandThisChangesCopperGolemBehavior",
        "Set by /cgp accept-consequences. The stationary sorter only runs when this is true.",
        c -> c.stationarySorter.iUnderstandThisChangesCopperGolemBehavior,
        (c, v) -> c.stationarySorter.iUnderstandThisChangesCopperGolemBehavior = v);
    public static final Whole MIN_STATIONARY_TICKS = new Whole("stationarySorter.minStationaryTicks",
        "Ticks a golem must stay in the same block before it counts as stationary.",
        1, 20 * 60, c -> c.stationarySorter.minStationaryTicks, (c, v) -> c.stationarySorter.minStationaryTicks = v);
    public static final Switch SKIP_TRAVEL = new Switch("stationarySorter.skipTravelWhenStationary",
        "Skip movement physics while stationary and not trying to move.",
        c -> c.stationarySorter.skipTravelWhenStationary, (c, v) -> c.stationarySorter.skipTravelWhenStationary = v);
    public static final Switch SKIP_CRAMMING = new Switch("stationarySorter.skipCrammingWhenStationary",
        "Skip entity pushing and cramming checks while stationary and not trying to move.",
        c -> c.stationarySorter.skipCrammingWhenStationary, (c, v) -> c.stationarySorter.skipCrammingWhenStationary = v);
    public static final Whole FULL_PHYSICS_TICK_INTERVAL = new Whole("stationarySorter.fullPhysicsTickInterval",
        "Always run full physics on 1 out of every N ticks, so pushes and removed floors are noticed.",
        1, 200, c -> c.stationarySorter.fullPhysicsTickInterval, (c, v) -> c.stationarySorter.fullPhysicsTickInterval = v);
    public static final Decimal MAX_HORIZONTAL_VELOCITY_SQUARED = new Decimal("stationarySorter.maxHorizontalVelocitySquared",
        "Horizontal speed (squared, blocks/tick) above which a golem never counts as stationary.",
        0.0D, 1.0D, c -> c.stationarySorter.maxHorizontalVelocitySquared, (c, v) -> c.stationarySorter.maxHorizontalVelocitySquared = v);
    public static final Switch GOLEMS_LOOK_AT_PLAYERS = new Switch("behavior.golemsLookAtPlayers",
        "BEHAVIOR-CHANGING when false: golems stop turning their heads toward nearby players. Cosmetic only.",
        c -> c.behavior.golemsLookAtPlayers, (c, v) -> c.behavior.golemsLookAtPlayers = v);
    public static final Whole LOG_INTERVAL_SECONDS = new Whole("metrics.logIntervalSeconds",
        "Log a status summary every N seconds. 0 turns it off.",
        0, 3600, c -> c.metrics.logIntervalSeconds, (c, v) -> c.metrics.logIntervalSeconds = v);

    /** In the order they appear in the config file. */
    public static final List<Setting> ALL = List.of(
        ENABLED, FAST_PLAYER_LOOK_CHECK, FAST_NEARBY_ENTITY_SORT, FAST_CHEST_IN_USE_CHECK, FAST_CHEST_SEARCH,
        IDLE_THROTTLE, SENSOR_TICK_INTERVAL,
        STATIONARY_SORTER, STATIONARY_SORTER_CONSENT, MIN_STATIONARY_TICKS, SKIP_TRAVEL, SKIP_CRAMMING,
        FULL_PHYSICS_TICK_INTERVAL, MAX_HORIZONTAL_VELOCITY_SQUARED, GOLEMS_LOOK_AT_PLAYERS, LOG_INTERVAL_SECONDS
    );

    private static final Map<String, Setting> BY_KEY = new LinkedHashMap<>();

    static {
        for (Setting setting : ALL) {
            if (BY_KEY.put(setting.key(), setting) != null) {
                throw new IllegalStateException("Duplicate config setting " + setting.key());
            }
        }
    }

    private ConfigSettings() {
    }

    public static Setting get(String key) {
        return BY_KEY.get(key);
    }

    /** Sets a value of the setting's own type (as returned by {@link Setting#get} or {@link #parse}). */
    public static void setValue(Setting setting, CopperGolemModConfig config, Object value) {
        switch (setting) {
            case Switch toggle -> toggle.set(config, (Boolean) value);
            case Whole whole -> whole.set(config, (Integer) value);
            case Decimal decimal -> decimal.set(config, (Double) value);
        }
    }

    /**
     * Reads a value as typed in a command, e.g. {@code true}, {@code 6} or {@code 0.0025}.
     *
     * @throws IllegalArgumentException with a message for the player when the value isn't allowed
     */
    public static Object parse(Setting setting, String raw) {
        String text = raw.trim();
        try {
            Object value = switch (setting) {
                case Switch ignored -> switch (text) {
                    case "true" -> Boolean.TRUE;
                    case "false" -> Boolean.FALSE;
                    default -> throw new NumberFormatException();
                };
                case Whole whole -> {
                    int number = Integer.parseInt(text);
                    yield number >= whole.min() && number <= whole.max() ? number : null;
                }
                case Decimal decimal -> {
                    double number = Double.parseDouble(text);
                    yield number >= decimal.min() && number <= decimal.max() ? number : null;
                }
            };
            if (value != null) {
                return value;
            }
        } catch (NumberFormatException ignored) {
            // Reported below.
        }
        throw new IllegalArgumentException(setting.key() + " can't be " + (text.isEmpty() ? "empty" : "'" + text + "'")
            + " (allowed: " + setting.allowed() + ")");
    }
}
