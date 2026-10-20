package com.restfulbee.coppergolemmod.test;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.restfulbee.coppergolemmod.CopperGolemMod;
import com.restfulbee.coppergolemmod.CopperGolemModConfig;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.server.level.ServerLevel;

/**
 * Test environment type {@code copper_golem_mod_test:mod_config}: runs a batch of tests with a specific mod config
 * (applied in memory with {@link CopperGolemMod#setConfigOverride}) and restores the normal config afterwards.
 * Tests that share an environment run as one batch, and batches run one after another, so each batch sees
 * exactly its own config.
 */
public record ModConfigEnvironment(boolean enabled, boolean stationarySorter, boolean golemsLookAtPlayers, boolean idleThrottle)
    implements TestEnvironmentDefinition<Boolean> {

    public static final MapCodec<ModConfigEnvironment> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
        Codec.BOOL.optionalFieldOf("enabled", true).forGetter(ModConfigEnvironment::enabled),
        Codec.BOOL.optionalFieldOf("stationary_sorter", false).forGetter(ModConfigEnvironment::stationarySorter),
        Codec.BOOL.optionalFieldOf("golems_look_at_players", true).forGetter(ModConfigEnvironment::golemsLookAtPlayers),
        Codec.BOOL.optionalFieldOf("idle_throttle", true).forGetter(ModConfigEnvironment::idleThrottle)
    ).apply(instance, ModConfigEnvironment::new));

    /** Builds a config with defaults for everything not listed. */
    public static CopperGolemModConfig config(boolean enabled, boolean stationarySorter, boolean golemsLookAtPlayers, boolean idleThrottle) {
        CopperGolemModConfig config = new CopperGolemModConfig();
        config.enabled = enabled;
        config.stationarySorter.enabled = stationarySorter;
        config.stationarySorter.iUnderstandThisChangesCopperGolemBehavior = stationarySorter;
        config.behavior.golemsLookAtPlayers = golemsLookAtPlayers;
        config.idleThrottle.enabled = idleThrottle;
        return config;
    }

    @Override
    public Boolean setup(ServerLevel level) {
        CopperGolemMod.setConfigOverride(config(enabled, stationarySorter, golemsLookAtPlayers, idleThrottle));
        return Boolean.TRUE;
    }

    @Override
    public void teardown(ServerLevel level, Boolean saveData) {
        CopperGolemMod.setConfigOverride(null);
    }

    @Override
    public MapCodec<ModConfigEnvironment> codec() {
        return CODEC;
    }
}
