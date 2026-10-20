package com.restfulbee.coppergolemmod.test;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.registry.Registry;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

/** Test-only mod: registers the mod_config test environment type and times server ticks for the workload benchmark. */
public final class CopperGolemModTestMod implements ModInitializer {
    public static final String MOD_ID = "copper_golem_mod_test";

    private static long tickStartNanos;
    private static long lastTickNanos;

    @Override
    public void onInitialize() {
        Registry.register(Registries.TEST_ENVIRONMENT_DEFINITION_TYPE, Identifier.of(MOD_ID, "mod_config"), ModConfigEnvironment.CODEC);
        ServerTickEvents.START_SERVER_TICK.register(server -> tickStartNanos = System.nanoTime());
        ServerTickEvents.END_SERVER_TICK.register(server -> lastTickNanos = System.nanoTime() - tickStartNanos);
    }

    /** Duration of the most recently completed server tick (the same measure as MSPT). */
    public static long lastTickNanos() {
        return lastTickNanos;
    }
}
