package com.restfulbee.coppergolemmod;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
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

    /** The config as loaded from the file. */
    private static CopperGolemModConfig loadedConfig = new CopperGolemModConfig();
    /** The config the optimizations read: the loaded one, or an in-memory copy while a benchmark toggles the mod. */
    private static volatile CopperGolemModConfig config = loadedConfig;

    // Server thread only.
    private static Benchmark benchmark;
    private static CommandSourceStack benchmarkRequester;
    private static long tickStartNanos;

    @Override
    public void onInitialize() {
        loadedConfig = CopperGolemModConfig.load(CONFIG_PATH, LOGGER);
        config = loadedConfig;

        ServerTickEvents.START_SERVER_TICK.register(server -> tickStartNanos = System.nanoTime());
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            long tickNanos = System.nanoTime() - tickStartNanos;
            METRICS.serverTick();
            if (benchmark != null) {
                tickBenchmark(tickNanos);
            }
            if (METRICS.shouldLog(config.metrics.logIntervalSeconds)) {
                LOGGER.info(METRICS.formatStatus(config));
            }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (benchmark != null) {
                stopBenchmark("the server is stopping");
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
                if (benchmark != null) {
                    stopBenchmark("the config was reloaded");
                }
                loadedConfig = CopperGolemModConfig.load(CONFIG_PATH, LOGGER);
                config = loadedConfig;
                context.getSource().sendSuccess(() -> Component.literal("CopperGolemMod config reloaded (enabled=" + config.enabled
                    + ", stationarySorter=" + (config.isStationarySorterActive() ? "ON" : "off") + ")."), true);
                return 1;
            }))
            .then(Commands.literal("profile")
                .then(Commands.literal("on").executes(context -> setProfiling(context, true)))
                .then(Commands.literal("off").executes(context -> setProfiling(context, false))))
            .then(Commands.literal("bench")
                .then(Commands.literal("ab")
                    .executes(context -> startBenchmark(context, Benchmark.Kind.AB, 10, 30))
                    .then(Commands.argument("minutes", IntegerArgumentType.integer(2, 180))
                        .executes(context -> startBenchmark(context, Benchmark.Kind.AB, IntegerArgumentType.getInteger(context, "minutes"), 30))
                        .then(Commands.argument("phaseSeconds", IntegerArgumentType.integer(15, 600))
                            .executes(context -> startBenchmark(context, Benchmark.Kind.AB,
                                IntegerArgumentType.getInteger(context, "minutes"), IntegerArgumentType.getInteger(context, "phaseSeconds"))))))
                .then(Commands.literal("current")
                    .executes(context -> startBenchmark(context, Benchmark.Kind.CURRENT, 5, 30))
                    .then(Commands.argument("minutes", IntegerArgumentType.integer(1, 180))
                        .executes(context -> startBenchmark(context, Benchmark.Kind.CURRENT, IntegerArgumentType.getInteger(context, "minutes"), 30))))
                .then(Commands.literal("status").executes(context -> {
                    String message = benchmark == null ? "No CopperGolemMod benchmark is running." : benchmark.progress();
                    context.getSource().sendSuccess(() -> Component.literal(message), false);
                    return 1;
                }))
                .then(Commands.literal("stop").executes(context -> {
                    if (benchmark == null) {
                        context.getSource().sendFailure(Component.literal("No CopperGolemMod benchmark is running."));
                        return 0;
                    }
                    benchmarkRequester = context.getSource();
                    stopBenchmark("it was stopped with /cgp bench stop");
                    return 1;
                }))));
    }

    private static int status(CommandContext<CommandSourceStack> context) {
        String status = METRICS.formatStatus(config);
        String bench = benchmark == null ? "" : "\n  " + benchmark.progress();
        context.getSource().sendSuccess(() -> Component.literal(status + bench), false);
        return 1;
    }

    private static int startBenchmark(CommandContext<CommandSourceStack> context, Benchmark.Kind kind, int minutes, int phaseSeconds) {
        CommandSourceStack source = context.getSource();
        if (benchmark != null) {
            source.sendFailure(Component.literal("A CopperGolemMod benchmark is already running. " + benchmark.progress() + " Use /cgp bench stop to abort it."));
            return 0;
        }
        benchmark = new Benchmark(kind, minutes * 60 * 20, phaseSeconds * 20, loadedConfig.stationarySorter.minStationaryTicks);
        benchmarkRequester = source;
        METRICS.setBenchmarkTiming(true);
        applyBenchmarkOverride();

        String what = kind == Benchmark.Kind.AB
            ? "A/B benchmark: the mod is switched on and off in memory (the config file is not changed)"
            : "benchmark of the current config";
        String message = "CopperGolemMod " + what + ". " + benchmark.describe()
            + ". Keep the farm running as usual. Use /cgp bench status for progress.";
        LOGGER.info(message);
        source.sendSuccess(() -> Component.literal(message), true);
        return 1;
    }

    private static void tickBenchmark(long tickNanos) {
        if (benchmark.onServerTick(tickNanos, METRICS)) {
            if (benchmark.isFinished()) {
                finishBenchmark(null);
            } else {
                applyBenchmarkOverride();
            }
        }
    }

    private static void stopBenchmark(String reason) {
        benchmark.stop(METRICS);
        finishBenchmark(reason);
    }

    private static void finishBenchmark(String stoppedBecause) {
        Benchmark finished = benchmark;
        CommandSourceStack requester = benchmarkRequester;
        benchmark = null;
        benchmarkRequester = null;
        METRICS.setBenchmarkTiming(false);
        config = loadedConfig;

        String report;
        try {
            report = (stoppedBecause == null ? "" : "Benchmark stopped early because " + stoppedBecause + ". Partial results:\n")
                + finished.finishAndReport(loadedConfig, Benchmark.defaultOutputDir());
        } catch (RuntimeException exception) {
            // A benchmark must never take the server down.
            LOGGER.error("CopperGolemMod benchmark failed to produce a report", exception);
            report = "CopperGolemMod benchmark failed to produce a report; see the server log.";
        }
        LOGGER.info(report);
        if (requester != null) {
            String message = report;
            requester.sendSuccess(() -> Component.literal(message), true);
        }
    }

    private static void applyBenchmarkOverride() {
        Boolean enabled = benchmark.currentEnabledOverride();
        config = enabled == null ? loadedConfig : loadedConfig.withEnabled(enabled);
    }

    private static int setProfiling(CommandContext<CommandSourceStack> context, boolean enabled) {
        METRICS.setProfiling(enabled);
        context.getSource().sendSuccess(() -> Component.literal("CopperGolemMod golem tick profiling " + (enabled ? "on." : "off.")), false);
        return 1;
    }
}
