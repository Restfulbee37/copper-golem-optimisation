package com.restfulbee.coppergolemmod;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
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

    private static void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("cgp")
            .requires(CommandManager.requirePermissionLevel(CommandManager.GAMEMASTERS_CHECK))
            .then(CommandManager.literal("status").executes(CopperGolemMod::status))
            .then(CommandManager.literal("reset").executes(context -> {
                METRICS.reset();
                context.getSource().sendFeedback(() -> Text.literal("CopperGolemMod metrics reset."), false);
                return 1;
            }))
            .then(CommandManager.literal("reload").executes(context -> {
                config = CopperGolemModConfig.load(CONFIG_PATH, LOGGER);
                context.getSource().sendFeedback(() -> Text.literal("CopperGolemMod config reloaded (enabled=" + config.enabled
                    + ", stationarySorter=" + (config.isStationarySorterActive() ? "ON" : "off") + ")."), true);
                return 1;
            }))
            .then(CommandManager.literal("profile")
                .then(CommandManager.literal("on").executes(context -> setProfiling(context, true)))
                .then(CommandManager.literal("off").executes(context -> setProfiling(context, false)))));
    }

    private static int status(CommandContext<ServerCommandSource> context) {
        String status = METRICS.formatStatus(config);
        context.getSource().sendFeedback(() -> Text.literal(status), false);
        return 1;
    }

    private static int setProfiling(CommandContext<ServerCommandSource> context, boolean enabled) {
        METRICS.setProfiling(enabled);
        context.getSource().sendFeedback(() -> Text.literal("CopperGolemMod golem tick profiling " + (enabled ? "on." : "off.")), false);
        return 1;
    }
}
