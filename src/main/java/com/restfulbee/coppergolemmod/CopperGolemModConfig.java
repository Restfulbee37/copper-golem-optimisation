package com.restfulbee.coppergolemmod;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * JSON config stored at {@code config/copper_golem_mod.json}. Missing fields fall back to defaults and
 * unknown fields are ignored, so old config files keep loading. The file is rewritten after every load
 * so newly added options show up in it.
 */
public final class CopperGolemModConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** Master switch. When false the mod changes nothing and only counts golem ticks. */
    public boolean enabled = true;
    public Optimizations optimizations = new Optimizations();
    public IdleThrottle idleThrottle = new IdleThrottle();
    public StationarySorter stationarySorter = new StationarySorter();
    public Behavior behavior = new Behavior();
    public Metrics metrics = new Metrics();

    /** Optimizations that produce the same result as vanilla, only computed more cheaply. */
    public static final class Optimizations {
        public String _comment = "Vanilla-identical optimizations. Golems behave exactly as in vanilla; the work is just done more cheaply.";
        /**
         * Vanilla scans every nearby living entity every tick looking for a player to stare at. When no
         * player in the world is within look range, the scan can never succeed, so it is skipped.
         */
        public boolean fastPlayerLookCheck = true;
        /** Sorts the nearby-entity sensor list with precomputed distances; the resulting order is identical. */
        public boolean fastNearbyEntitySort = true;
        /**
         * Golems near a chest ask every tick whether anyone is using it. Vanilla lists every entity near the chest
         * to answer; this stops at the first viewer and only looks at entities that can be viewers.
         */
        public boolean fastChestInUseCheck = true;
        /**
         * When searching for a chest, rules out wrong-type, visited and locked chests before building their
         * inventories (which includes a "is a cat sitting on it?" scan). The same chest is chosen.
         */
        public boolean fastChestSearch = true;
    }

    /** Throttling applied only while a golem is in its post-search cooldown (nothing to sort). */
    public static final class IdleThrottle {
        public String _comment = "Only affects golems in their 'nothing to sort' cooldown. Sorting speed is unchanged; idle golems notice nearby mobs/players a little later.";
        public boolean enabled = true;
        /** Run the brain sensors on 1 out of every N ticks while idle. 1 disables the throttle. */
        public int sensorTickInterval = 4;
    }

    /** Opt-in physics skipping for golems that sit still in trapped sorter cells. */
    public static final class StationarySorter {
        public String _warning = "BEHAVIOR-CHANGING. For sorters where golems are trapped and never need to walk. Skips movement physics and entity pushing for golems that are standing still with no intent to move. Reduces cramming damage.";
        public boolean enabled = false;
        public boolean iUnderstandThisChangesCopperGolemBehavior = false;
        /** Ticks a golem must stay in the same block before it counts as stationary. */
        public int minStationaryTicks = 40;
        /** Skip {@code travel()} (movement physics) while stationary and not trying to move. */
        public boolean skipTravelWhenStationary = true;
        /** Skip entity pushing and cramming checks while stationary and not trying to move. */
        public boolean skipCrammingWhenStationary = true;
        /** Always run full physics on 1 out of every N ticks so pushes, falling floors, etc. are still noticed. */
        public int fullPhysicsTickInterval = 20;
        /** Horizontal velocity (squared, blocks/tick) above which a golem is never treated as stationary. */
        public double maxHorizontalVelocitySquared = 0.0025D;
    }

    /** Opt-in changes to cosmetic golem behavior. Defaults are vanilla. */
    public static final class Behavior {
        public String _warning = "BEHAVIOR-CHANGING. Defaults match vanilla. golemsLookAtPlayers=false stops copper golems turning their heads toward nearby players (purely cosmetic; sorting is unaffected) and skips the work of looking for players.";
        /** Vanilla golems sometimes turn to look at a player within 6 blocks. False turns that off entirely. */
        public boolean golemsLookAtPlayers = true;
    }

    public static final class Metrics {
        /** Log a status summary every N seconds. 0 disables periodic logging. */
        public int logIntervalSeconds = 0;
    }

    /** A deep copy, so a changed config can be swapped in at once instead of edited while golems read it. */
    public CopperGolemModConfig copy() {
        return GSON.fromJson(GSON.toJson(this), CopperGolemModConfig.class);
    }

    /** An in-memory copy with only {@code enabled} changed. Used by benchmarks; never saved. */
    public CopperGolemModConfig withEnabled(boolean enabled) {
        CopperGolemModConfig copy = copy();
        copy.enabled = enabled;
        return copy;
    }

    /** True when golems should never look at players (mod enabled and the behavior switched off). */
    public boolean isPlayerLookDisabled() {
        return enabled && !behavior.golemsLookAtPlayers;
    }

    public boolean isStationarySorterActive() {
        return enabled && stationarySorter.enabled && stationarySorter.iUnderstandThisChangesCopperGolemBehavior;
    }

    public static CopperGolemModConfig load(Path path, Logger logger) {
        CopperGolemModConfig config;
        if (Files.notExists(path)) {
            config = new CopperGolemModConfig();
            logger.info("Creating default CopperGolemMod config at {}", path);
        } else {
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                config = GSON.fromJson(reader, CopperGolemModConfig.class);
                if (config == null) {
                    logger.warn("CopperGolemMod config at {} was empty. Using defaults.", path);
                    config = new CopperGolemModConfig();
                }
            } catch (IOException | JsonParseException exception) {
                // Keep the broken file around rather than silently overwriting the user's edits.
                logger.error("Failed to read CopperGolemMod config at {}. Using defaults; the broken file was kept as {}.bad", path, path.getFileName(), exception);
                backupBrokenFile(path, logger);
                config = new CopperGolemModConfig();
            }
        }

        config.validate(logger);
        config.save(path, logger);
        return config;
    }

    /** Writes the config atomically. Returns false (and logs why) if it couldn't be written. */
    public boolean save(Path path, Logger logger) {
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temp = path.resolveSibling(path.getFileName() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                GSON.toJson(this, writer);
            }
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException exception) {
            logger.error("Failed to save CopperGolemMod config at {}", path, exception);
            return false;
        }
    }

    private void validate(Logger logger) {
        CopperGolemModConfig defaults = new CopperGolemModConfig();
        if (optimizations == null) {
            optimizations = defaults.optimizations;
        }
        if (idleThrottle == null) {
            idleThrottle = defaults.idleThrottle;
        }
        if (stationarySorter == null) {
            stationarySorter = defaults.stationarySorter;
        }
        if (behavior == null) {
            behavior = defaults.behavior;
        }
        if (metrics == null) {
            metrics = defaults.metrics;
        }
        // Comment strings are informational; always write the current text back.
        optimizations._comment = defaults.optimizations._comment;
        idleThrottle._comment = defaults.idleThrottle._comment;
        stationarySorter._warning = defaults.stationarySorter._warning;
        behavior._warning = defaults.behavior._warning;

        // The same limits /cgp config enforces.
        for (ConfigSettings.Setting setting : ConfigSettings.ALL) {
            switch (setting) {
                case ConfigSettings.Whole whole -> whole.set(this, clamp(whole.get(this), whole.min(), whole.max()));
                case ConfigSettings.Decimal decimal -> {
                    double value = decimal.get(this);
                    if (!(value >= decimal.min())) {
                        // Negative or NaN: not a usable limit at all, so fall back to the default.
                        decimal.set(this, (Double) decimal.defaultValue());
                    } else if (value > decimal.max()) {
                        decimal.set(this, decimal.max());
                    }
                }
                case ConfigSettings.Switch ignored -> {
                }
            }
        }

        if (stationarySorter.enabled && !stationarySorter.iUnderstandThisChangesCopperGolemBehavior) {
            logger.warn("stationarySorter.enabled is true but iUnderstandThisChangesCopperGolemBehavior is false; the stationary sorter stays off.");
        } else if (isStationarySorterActive()) {
            logger.warn("CopperGolemMod stationary sorter is active. Stationary copper golems skip movement physics and entity pushing.");
        }
    }

    private static void backupBrokenFile(Path path, Logger logger) {
        try {
            Files.copy(path, path.resolveSibling(path.getFileName() + ".bad"), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            logger.error("Failed to back up broken CopperGolemMod config", exception);
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
