package com.restfulbee.coppergolemmod;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * {@code /cgp config} (list, get, set and reset settings in game) and {@code /cgp accept-consequences}. Changes are
 * applied at once and saved to the config file, and every change is reported to other operators and the server
 * log. Both commands sit under {@code /cgp}, so they need the same operator permission.
 */
final class ConfigCommands {
    static final String ACCEPT_COMMAND = "/cgp accept-consequences";
    private static final long CONFIRM_WINDOW_NANOS = TimeUnit.SECONDS.toNanos(60);
    /** Who has run accept-consequences once, by command source name, and until when they can confirm. */
    private static final Map<String, Long> PENDING_CONSENT = new HashMap<>();

    private ConfigCommands() {
    }

    static LiteralArgumentBuilder<ServerCommandSource> configCommand() {
        LiteralArgumentBuilder<ServerCommandSource> get = CommandManager.literal("get");
        LiteralArgumentBuilder<ServerCommandSource> set = CommandManager.literal("set");
        LiteralArgumentBuilder<ServerCommandSource> reset = CommandManager.literal("reset");
        for (ConfigSettings.Setting setting : ConfigSettings.ALL) {
            get.then(CommandManager.literal(setting.key()).executes(context -> get(context.getSource(), setting)));
            set.then(CommandManager.literal(setting.key()).then(valueArgument(setting)));
            reset.then(CommandManager.literal(setting.key()).executes(context -> reset(context.getSource(), setting)));
        }
        return CommandManager.literal("config")
            .executes(context -> list(context.getSource()))
            .then(CommandManager.literal("list").executes(context -> list(context.getSource())))
            .then(get)
            .then(set)
            .then(reset);
    }

    static LiteralArgumentBuilder<ServerCommandSource> acceptConsequencesCommand() {
        return CommandManager.literal("accept-consequences").executes(context -> acceptConsequences(context.getSource()));
    }

    /** The typed value argument for one setting; Brigadier rejects wrong types and out-of-range numbers. */
    private static RequiredArgumentBuilder<ServerCommandSource, ?> valueArgument(ConfigSettings.Setting setting) {
        return switch (setting) {
            case ConfigSettings.Switch toggle -> CommandManager.argument("value", BoolArgumentType.bool())
                .executes(context -> {
                    boolean value = BoolArgumentType.getBool(context, "value");
                    return set(context.getSource(), toggle, value, config -> toggle.set(config, value));
                });
            case ConfigSettings.Whole whole -> CommandManager.argument("value", IntegerArgumentType.integer(whole.min(), whole.max()))
                .suggests(numberSuggestions(whole, whole.min(), whole.max()))
                .executes(context -> {
                    int value = IntegerArgumentType.getInteger(context, "value");
                    return set(context.getSource(), whole, value, config -> whole.set(config, value));
                });
            case ConfigSettings.Decimal decimal -> CommandManager.argument("value", DoubleArgumentType.doubleArg(decimal.min(), decimal.max()))
                .suggests(numberSuggestions(decimal, decimal.min(), decimal.max()))
                .executes(context -> {
                    double value = DoubleArgumentType.getDouble(context, "value");
                    return set(context.getSource(), decimal, value, config -> decimal.set(config, value));
                });
        };
    }

    /** Suggests the current value, the default and the limits, each labelled in its tooltip. */
    private static SuggestionProvider<ServerCommandSource> numberSuggestions(ConfigSettings.Setting setting, Object min, Object max) {
        return (context, builder) -> {
            Map<String, String> values = new LinkedHashMap<>();
            values.put(String.valueOf(setting.get(CopperGolemMod.loadedConfig())), "current");
            values.merge(String.valueOf(setting.defaultValue()), "default", (a, b) -> a + ", " + b);
            values.merge(String.valueOf(min), "minimum", (a, b) -> a + ", " + b);
            values.merge(String.valueOf(max), "maximum", (a, b) -> a + ", " + b);
            values.forEach((value, label) -> suggest(builder, value, label));
            return builder.buildFuture();
        };
    }

    private static void suggest(SuggestionsBuilder builder, String value, String tooltip) {
        if (value.startsWith(builder.getRemaining())) {
            builder.suggest(value, Text.literal(tooltip));
        }
    }

    private static int list(ServerCommandSource source) {
        CopperGolemModConfig config = CopperGolemMod.loadedConfig();
        MutableText message = Text.literal("CopperGolemMod settings (hover for details, click to change):");
        for (ConfigSettings.Setting setting : ConfigSettings.ALL) {
            message.append("\n  ").append(settingLine(setting, config));
        }
        if (config.stationarySorter.enabled && !config.stationarySorter.iUnderstandThisChangesCopperGolemBehavior) {
            message.append(Text.literal("\nThe stationary sorter is enabled but stays off until " + ACCEPT_COMMAND + " is confirmed.")
                .formatted(Formatting.GOLD));
        }
        source.sendFeedback(() -> message, false);
        return ConfigSettings.ALL.size();
    }

    private static MutableText settingLine(ConfigSettings.Setting setting, CopperGolemModConfig config) {
        String command = "/cgp config set " + setting.key() + " ";
        MutableText hover = Text.literal(setting.description())
            .append(Text.literal("\nAllowed: " + setting.allowed() + ". Default: " + setting.defaultValue() + ".").formatted(Formatting.GRAY));
        MutableText line = Text.literal(setting.key())
            .styled(style -> style.withClickEvent(new ClickEvent.SuggestCommand(command)).withHoverEvent(new HoverEvent.ShowText(hover)))
            .append(Text.literal(" = "));
        if (setting.isDefault(config)) {
            return line.append(Text.literal(String.valueOf(setting.get(config))));
        }
        return line.append(Text.literal(String.valueOf(setting.get(config))).formatted(Formatting.YELLOW))
            .append(Text.literal(" (default " + setting.defaultValue() + ")").formatted(Formatting.GRAY));
    }

    private static int get(ServerCommandSource source, ConfigSettings.Setting setting) {
        CopperGolemModConfig config = CopperGolemMod.loadedConfig();
        String message = setting.key() + " = " + setting.get(config) + " (default " + setting.defaultValue()
            + ", allowed " + setting.allowed() + ")\n" + setting.description();
        source.sendFeedback(() -> Text.literal(message), false);
        return 1;
    }

    private static int reset(ServerCommandSource source, ConfigSettings.Setting setting) {
        CopperGolemModConfig defaults = new CopperGolemModConfig();
        Object value = setting.defaultValue();
        return set(source, setting, value, config -> {
            switch (setting) {
                case ConfigSettings.Switch toggle -> toggle.set(config, toggle.get(defaults));
                case ConfigSettings.Whole whole -> whole.set(config, whole.get(defaults));
                case ConfigSettings.Decimal decimal -> decimal.set(config, decimal.get(defaults));
            }
        });
    }

    private static int set(ServerCommandSource source, ConfigSettings.Setting setting, Object value, Consumer<CopperGolemModConfig> change) {
        if (refuseDuringBenchmark(source)) {
            return 0;
        }
        CopperGolemModConfig config = CopperGolemMod.loadedConfig();
        if (setting == ConfigSettings.STATIONARY_SORTER_CONSENT && Boolean.TRUE.equals(value)) {
            source.sendError(Text.literal("Use " + ACCEPT_COMMAND + " to accept this. It explains what changes and asks you to confirm."));
            return 0;
        }
        if (setting == ConfigSettings.STATIONARY_SORTER && Boolean.TRUE.equals(value)
            && !config.stationarySorter.iUnderstandThisChangesCopperGolemBehavior) {
            source.sendError(Text.literal("This setting changes copper golem behavior. Run " + ACCEPT_COMMAND + " first."));
            return 0;
        }

        Object old = setting.get(config);
        if (old.equals(value)) {
            source.sendFeedback(() -> Text.literal(setting.key() + " is already " + value + "."), false);
            return 1;
        }
        boolean saved = CopperGolemMod.updateConfig(change);
        String message = "CopperGolemMod: " + setting.key() + " set to " + value + " (was " + old + ")."
            + stationarySorterNote(setting)
            + (saved ? "" : " The config file couldn't be written (see the server log), so this only lasts until the server restarts.");
        source.sendFeedback(() -> Text.literal(message), true);
        return 1;
    }

    /** Says whether a change to the stationary sorter's switches actually turned it on or off. */
    private static String stationarySorterNote(ConfigSettings.Setting setting) {
        if (setting != ConfigSettings.STATIONARY_SORTER && setting != ConfigSettings.STATIONARY_SORTER_CONSENT && setting != ConfigSettings.ENABLED) {
            return "";
        }
        CopperGolemModConfig config = CopperGolemMod.loadedConfig();
        return config.stationarySorter.enabled ? " The stationary sorter is now " + (config.isStationarySorterActive() ? "active." : "off.") : "";
    }

    private static int acceptConsequences(ServerCommandSource source) {
        if (refuseDuringBenchmark(source)) {
            return 0;
        }
        if (CopperGolemMod.loadedConfig().stationarySorter.iUnderstandThisChangesCopperGolemBehavior) {
            source.sendFeedback(() -> Text.literal("The consequences have already been accepted. Turn the stationary sorter on with ")
                .append(suggestion("/cgp config set stationarySorter.enabled true")), false);
            return 1;
        }

        String who = source.getName();
        Long confirmBy = PENDING_CONSENT.get(who);
        long now = System.nanoTime();
        if (confirmBy == null || now - confirmBy > 0) {
            PENDING_CONSENT.put(who, now + CONFIRM_WINDOW_NANOS);
            MutableText warning = Text.literal("The stationary sorter changes copper golem behavior. ").formatted(Formatting.GOLD)
                .append(Text.literal("Golems that stand still in sorter cells skip movement physics and entity pushing while they "
                    + "aren't trying to move. They take much less cramming damage and don't push each other apart, and full physics "
                    + "(pistons, water, a removed floor) only runs on 1 out of every stationarySorter.fullPhysicsTickInterval ticks. "
                    + "Only use it for sorters where the golems are trapped and never need to walk, and test it on a copy of your "
                    + "world first.\nRun ").formatted(Formatting.WHITE))
                .append(suggestion(ACCEPT_COMMAND))
                .append(Text.literal(" again within 60 seconds to confirm.").formatted(Formatting.WHITE));
            source.sendFeedback(() -> warning, false);
            return 1;
        }

        PENDING_CONSENT.remove(who);
        boolean saved = CopperGolemMod.updateConfig(config -> config.stationarySorter.iUnderstandThisChangesCopperGolemBehavior = true);
        String message = "CopperGolemMod: " + who + " accepted that the stationary sorter changes copper golem behavior."
            + (CopperGolemMod.loadedConfig().isStationarySorterActive()
                ? " The stationary sorter is now active."
                : " Turn it on with /cgp config set stationarySorter.enabled true")
            + (saved ? "" : " The config file couldn't be written (see the server log), so this only lasts until the server restarts.");
        source.sendFeedback(() -> Text.literal(message), true);
        return 1;
    }

    private static boolean refuseDuringBenchmark(ServerCommandSource source) {
        if (CopperGolemMod.isBenchmarkRunning()) {
            source.sendError(Text.literal("A CopperGolemMod benchmark is running, so settings can't change until it finishes. "
                + "Stop it with /cgp bench stop first."));
            return true;
        }
        return false;
    }

    private static MutableText suggestion(String command) {
        return Text.literal(command).styled(style -> style.withColor(Formatting.AQUA)
            .withClickEvent(new ClickEvent.SuggestCommand(command))
            .withHoverEvent(new HoverEvent.ShowText(Text.literal("Click to fill in"))));
    }
}
