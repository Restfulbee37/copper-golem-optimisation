package com.restfulbee.coppergolemmod;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * {@code /cgp config} (the settings screen, and list, get, set, reset and apply in chat) and
 * {@code /cgp accept-consequences}. Changes are
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

    static LiteralArgumentBuilder<CommandSourceStack> configCommand() {
        LiteralArgumentBuilder<CommandSourceStack> get = Commands.literal("get");
        LiteralArgumentBuilder<CommandSourceStack> set = Commands.literal("set");
        LiteralArgumentBuilder<CommandSourceStack> reset = Commands.literal("reset");
        for (ConfigSettings.Setting setting : ConfigSettings.ALL) {
            get.then(Commands.literal(setting.key()).executes(context -> get(context.getSource(), setting)));
            set.then(Commands.literal(setting.key()).then(valueArgument(setting)));
            reset.then(Commands.literal(setting.key()).executes(context -> reset(context.getSource(), setting)));
        }
        return Commands.literal("config")
            .executes(context -> openOrList(context.getSource()))
            .then(Commands.literal("list").executes(context -> list(context.getSource())))
            .then(get)
            .then(set)
            .then(reset)
            .then(Commands.literal("apply").then(Commands.argument("settings", StringArgumentType.greedyString())
                .executes(context -> apply(context.getSource(), StringArgumentType.getString(context, "settings")))));
    }

    static LiteralArgumentBuilder<CommandSourceStack> acceptConsequencesCommand() {
        return Commands.literal("accept-consequences").executes(context -> acceptConsequences(context.getSource()));
    }

    /** The typed value argument for one setting; Brigadier rejects wrong types and out-of-range numbers. */
    private static RequiredArgumentBuilder<CommandSourceStack, ?> valueArgument(ConfigSettings.Setting setting) {
        return switch (setting) {
            case ConfigSettings.Switch toggle -> Commands.argument("value", BoolArgumentType.bool())
                .executes(context -> {
                    return set(context.getSource(), toggle, BoolArgumentType.getBool(context, "value"));
                });
            case ConfigSettings.Whole whole -> Commands.argument("value", IntegerArgumentType.integer(whole.min(), whole.max()))
                .suggests(numberSuggestions(whole, whole.min(), whole.max()))
                .executes(context -> {
                    return set(context.getSource(), whole, IntegerArgumentType.getInteger(context, "value"));
                });
            case ConfigSettings.Decimal decimal -> Commands.argument("value", DoubleArgumentType.doubleArg(decimal.min(), decimal.max()))
                .suggests(numberSuggestions(decimal, decimal.min(), decimal.max()))
                .executes(context -> {
                    return set(context.getSource(), decimal, DoubleArgumentType.getDouble(context, "value"));
                });
        };
    }

    /** Suggests the current value, the default and the limits, each labelled in its tooltip. */
    private static SuggestionProvider<CommandSourceStack> numberSuggestions(ConfigSettings.Setting setting, Object min, Object max) {
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
            builder.suggest(value, Component.literal(tooltip));
        }
    }

    /** Players get the settings screen; the server console (which has no screen) gets the list. */
    private static int openOrList(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return list(source);
        }
        ConfigScreen.open(player);
        return 1;
    }

    private static int list(CommandSourceStack source) {
        CopperGolemModConfig config = CopperGolemMod.loadedConfig();
        MutableComponent message = Component.literal("CopperGolemMod settings (hover for details, click to change):");
        for (ConfigSettings.Setting setting : ConfigSettings.ALL) {
            message.append("\n  ").append(settingLine(setting, config));
        }
        if (config.stationarySorter.enabled && !config.stationarySorter.iUnderstandThisChangesCopperGolemBehavior) {
            message.append(Component.literal("\nThe stationary sorter is enabled but stays off until " + ACCEPT_COMMAND + " is confirmed.")
                .withStyle(ChatFormatting.GOLD));
        }
        source.sendSuccess(() -> message, false);
        return ConfigSettings.ALL.size();
    }

    private static MutableComponent settingLine(ConfigSettings.Setting setting, CopperGolemModConfig config) {
        String command = "/cgp config set " + setting.key() + " ";
        MutableComponent hover = Component.literal(setting.description())
            .append(Component.literal("\nAllowed: " + setting.allowed() + ". Default: " + setting.defaultValue() + ".").withStyle(ChatFormatting.GRAY));
        MutableComponent line = Component.literal(setting.key())
            .withStyle(style -> style.withClickEvent(new ClickEvent.SuggestCommand(command)).withHoverEvent(new HoverEvent.ShowText(hover)))
            .append(Component.literal(" = "));
        if (setting.isDefault(config)) {
            return line.append(Component.literal(String.valueOf(setting.get(config))));
        }
        return line.append(Component.literal(String.valueOf(setting.get(config))).withStyle(ChatFormatting.YELLOW))
            .append(Component.literal(" (default " + setting.defaultValue() + ")").withStyle(ChatFormatting.GRAY));
    }

    private static int get(CommandSourceStack source, ConfigSettings.Setting setting) {
        CopperGolemModConfig config = CopperGolemMod.loadedConfig();
        String message = setting.key() + " = " + setting.get(config) + " (default " + setting.defaultValue()
            + ", allowed " + setting.allowed() + ")\n" + setting.description();
        source.sendSuccess(() -> Component.literal(message), false);
        return 1;
    }

    private static int reset(CommandSourceStack source, ConfigSettings.Setting setting) {
        return set(source, setting, setting.defaultValue());
    }

    private static int set(CommandSourceStack source, ConfigSettings.Setting setting, Object value) {
        if (refuseDuringBenchmark(source)) {
            return 0;
        }
        CopperGolemModConfig config = CopperGolemMod.loadedConfig();
        String refusal = consentRefusal(setting, value, config);
        if (refusal != null) {
            source.sendFailure(Component.literal(refusal));
            return 0;
        }

        Object old = setting.get(config);
        if (old.equals(value)) {
            source.sendSuccess(() -> Component.literal(setting.key() + " is already " + value + "."), false);
            return 1;
        }
        boolean saved = CopperGolemMod.updateConfig(updated -> ConfigSettings.setValue(setting, updated, value));
        String message = "CopperGolemMod: " + setting.key() + " set to " + value + " (was " + old + ")."
            + stationarySorterNote(List.of(setting)) + saveNote(saved);
        source.sendSuccess(() -> Component.literal(message), true);
        return 1;
    }

    /**
     * Changes several settings at once, all or nothing: {@code key=value key=value ...}. This is what Save on the
     * settings screen runs, and it can be typed too. Settings that already have the given value are left alone.
     */
    private static int apply(CommandSourceStack source, String assignments) {
        if (refuseDuringBenchmark(source)) {
            return 0;
        }
        CopperGolemModConfig config = CopperGolemMod.loadedConfig();
        Map<ConfigSettings.Setting, Object> changes = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        for (String assignment : assignments.trim().split("\\s+")) {
            int equals = assignment.indexOf('=');
            ConfigSettings.Setting setting = equals < 0 ? null : ConfigSettings.get(assignment.substring(0, equals));
            if (setting == null) {
                problems.add("'" + assignment + "' isn't <setting>=<value> for a known setting");
                continue;
            }
            try {
                Object value = ConfigSettings.parse(setting, assignment.substring(equals + 1));
                String refusal = consentRefusal(setting, value, config);
                if (refusal != null) {
                    problems.add(refusal);
                } else if (!setting.get(config).equals(value)) {
                    changes.put(setting, value);
                }
            } catch (IllegalArgumentException exception) {
                problems.add(exception.getMessage());
            }
        }
        if (!problems.isEmpty()) {
            source.sendFailure(Component.literal("No settings were changed: " + String.join("; ", problems) + "."));
            return 0;
        }
        if (changes.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No settings changed."), false);
            return 1;
        }

        List<String> described = new ArrayList<>();
        changes.forEach((setting, value) -> described.add(setting.key() + " " + setting.get(config) + " -> " + value));
        boolean saved = CopperGolemMod.updateConfig(updated -> changes.forEach((setting, value) -> ConfigSettings.setValue(setting, updated, value)));
        String message = "CopperGolemMod: changed " + String.join(", ", described) + "."
            + stationarySorterNote(changes.keySet()) + saveNote(saved);
        source.sendSuccess(() -> Component.literal(message), true);
        return changes.size();
    }

    /** Why this value may not be set without /cgp accept-consequences, or null if it may. */
    private static String consentRefusal(ConfigSettings.Setting setting, Object value, CopperGolemModConfig config) {
        if (setting == ConfigSettings.STATIONARY_SORTER_CONSENT && Boolean.TRUE.equals(value)) {
            return "Use " + ACCEPT_COMMAND + " to accept the stationary sorter's consequences. It explains what changes and asks you to confirm";
        }
        if (setting == ConfigSettings.STATIONARY_SORTER && Boolean.TRUE.equals(value)
            && !config.stationarySorter.iUnderstandThisChangesCopperGolemBehavior) {
            return "The stationary sorter changes copper golem behavior. Run " + ACCEPT_COMMAND + " first";
        }
        return null;
    }

    private static String saveNote(boolean saved) {
        return saved ? "" : " The config file couldn't be written (see the server log), so this only lasts until the server restarts.";
    }

    /** Says whether a change to the stationary sorter's switches actually turned it on or off. */
    private static String stationarySorterNote(Iterable<ConfigSettings.Setting> changed) {
        for (ConfigSettings.Setting setting : changed) {
            if (setting == ConfigSettings.STATIONARY_SORTER || setting == ConfigSettings.STATIONARY_SORTER_CONSENT || setting == ConfigSettings.ENABLED) {
                CopperGolemModConfig config = CopperGolemMod.loadedConfig();
                return config.stationarySorter.enabled ? " The stationary sorter is now " + (config.isStationarySorterActive() ? "active." : "off.") : "";
            }
        }
        return "";
    }

    private static int acceptConsequences(CommandSourceStack source) {
        if (refuseDuringBenchmark(source)) {
            return 0;
        }
        if (CopperGolemMod.loadedConfig().stationarySorter.iUnderstandThisChangesCopperGolemBehavior) {
            source.sendSuccess(() -> Component.literal("The consequences have already been accepted. Turn the stationary sorter on with ")
                .append(suggestion("/cgp config set stationarySorter.enabled true")), false);
            return 1;
        }

        String who = source.getTextName();
        Long confirmBy = PENDING_CONSENT.get(who);
        long now = System.nanoTime();
        if (confirmBy == null || now - confirmBy > 0) {
            PENDING_CONSENT.put(who, now + CONFIRM_WINDOW_NANOS);
            MutableComponent warning = Component.literal("The stationary sorter changes copper golem behavior. ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal("Golems that stand still in sorter cells skip movement physics and entity pushing while they "
                    + "aren't trying to move. They take much less cramming damage and don't push each other apart, and full physics "
                    + "(pistons, water, a removed floor) only runs on 1 out of every stationarySorter.fullPhysicsTickInterval ticks. "
                    + "Only use it for sorters where the golems are trapped and never need to walk, and test it on a copy of your "
                    + "world first.\nRun ").withStyle(ChatFormatting.WHITE))
                .append(suggestion(ACCEPT_COMMAND))
                .append(Component.literal(" again within 60 seconds to confirm.").withStyle(ChatFormatting.WHITE));
            source.sendSuccess(() -> warning, false);
            return 1;
        }

        PENDING_CONSENT.remove(who);
        boolean saved = CopperGolemMod.updateConfig(config -> config.stationarySorter.iUnderstandThisChangesCopperGolemBehavior = true);
        String message = "CopperGolemMod: " + who + " accepted that the stationary sorter changes copper golem behavior."
            + (CopperGolemMod.loadedConfig().isStationarySorterActive()
                ? " The stationary sorter is now active."
                : " Turn it on with /cgp config set stationarySorter.enabled true")
            + (saved ? "" : " The config file couldn't be written (see the server log), so this only lasts until the server restarts.");
        source.sendSuccess(() -> Component.literal(message), true);
        return 1;
    }

    private static boolean refuseDuringBenchmark(CommandSourceStack source) {
        if (CopperGolemMod.isBenchmarkRunning()) {
            source.sendFailure(Component.literal("A CopperGolemMod benchmark is running, so settings can't change until it finishes. "
                + "Stop it with /cgp bench stop first."));
            return true;
        }
        return false;
    }

    private static MutableComponent suggestion(String command) {
        return Component.literal(command).withStyle(style -> style.withColor(ChatFormatting.AQUA)
            .withClickEvent(new ClickEvent.SuggestCommand(command))
            .withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to fill in"))));
    }
}
