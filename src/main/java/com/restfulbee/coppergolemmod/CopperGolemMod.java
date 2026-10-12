package com.restfulbee.coppergolemmod;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

public final class CopperGolemMod implements ModInitializer {
    public static final String MOD_ID = "copper_golem_mod";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("copper_golem_mod.json");
    private static final ModMetrics METRICS = new ModMetrics();

    private static volatile CopperGolemModConfig config = new CopperGolemModConfig();

    @Override
    public void onInitialize() {
        config = CopperGolemModConfig.load(CONFIG_PATH, LOGGER);

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            METRICS.serverTick();
            if (METRICS.shouldLog(config.metrics.logIntervalSeconds)) {
                LOGGER.info(METRICS.formatStatus(config));
            }
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> registerCommands(dispatcher));

        LOGGER.info("CopperGolemMod loaded (enabled={}, stationarySorter={})", config.enabled, config.isStationarySorterActive() ? "ON" : "off");
    }

    public static CopperGolemModConfig config() {
        return config;
    }

    public static ModMetrics metrics() {
        return METRICS;
    }

    private static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("cgp")
            .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
            .then(Commands.literal("status").executes(CopperGolemMod::status))
            .then(Commands.literal("reset").executes(context -> {
                METRICS.reset();
                context.getSource().sendSuccess(() -> Component.literal("CopperGolemMod metrics reset."), false);
                return 1;
            }))
            .then(Commands.literal("reload").executes(context -> {
                config = CopperGolemModConfig.load(CONFIG_PATH, LOGGER);
                context.getSource().sendSuccess(() -> Component.literal("CopperGolemMod config reloaded (enabled=" + config.enabled
                    + ", stationarySorter=" + (config.isStationarySorterActive() ? "ON" : "off") + ")."), true);
                return 1;
            }))
            .then(Commands.literal("profile")
                .then(Commands.literal("on").executes(context -> setProfiling(context, true)))
                .then(Commands.literal("off").executes(context -> setProfiling(context, false)))));
    }

    private static int status(CommandContext<CommandSourceStack> context) {
        String status = METRICS.formatStatus(config);
        context.getSource().sendSuccess(() -> Component.literal(status), false);
        return 1;
    }

    private static int setProfiling(CommandContext<CommandSourceStack> context, boolean enabled) {
        METRICS.setProfiling(enabled);
        context.getSource().sendSuccess(() -> Component.literal("CopperGolemMod golem tick profiling " + (enabled ? "on." : "off.")), false);
        return 1;
    }
}
