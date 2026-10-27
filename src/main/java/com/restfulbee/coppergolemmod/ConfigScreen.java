package com.restfulbee.coppergolemmod;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.dialog.ActionButton;
import net.minecraft.server.dialog.CommonButtonData;
import net.minecraft.server.dialog.CommonDialogData;
import net.minecraft.server.dialog.Dialog;
import net.minecraft.server.dialog.DialogAction;
import net.minecraft.server.dialog.Input;
import net.minecraft.server.dialog.MultiActionDialog;
import net.minecraft.server.dialog.action.CustomAll;
import net.minecraft.server.dialog.body.DialogBody;
import net.minecraft.server.dialog.body.PlainMessage;
import net.minecraft.server.dialog.input.BooleanInput;
import net.minecraft.server.dialog.input.InputControl;
import net.minecraft.server.dialog.input.NumberRangeInput;
import net.minecraft.server.dialog.input.TextInput;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The settings screen that {@code /cgp config} opens for a player. It is a vanilla dialog, so players don't need
 * the mod. Save doesn't change anything itself: it turns the values into a {@code /cgp config apply} command and runs
 * it as the player who pressed it, so permissions, limits, the benchmark check, consent and logging are exactly those
 * of the command.
 *
 * <p>Save sends a custom click action rather than running the command from the player's game: vanilla would ask
 * the player to confirm every operator command a dialog runs. {@code ServerCommonPacketListenerImplMixin} hands the
 * action to {@link #save}.
 */
public final class ConfigScreen {
    /** The custom click action Save sends. */
    public static final Identifier SAVE_ACTION = Identifier.fromNamespaceAndPath(CopperGolemMod.MOD_ID, "save_settings");
    /** Number settings with at most this many steps get a slider; wider ones (e.g. seconds up to an hour) a text box. */
    private static final int MAX_SLIDER_STEPS = 200;
    private static final int WIDTH = 300;

    private ConfigScreen() {
    }

    static void open(ServerPlayer player) {
        player.openDialog(Holder.direct(build(CopperGolemMod.loadedConfig())));
    }

    /**
     * The settings on the screen: everything except the consent flag, which only {@code /cgp accept-consequences}
     * grants, and the stationary sorter switch until that consent has been given.
     */
    public static List<ConfigSettings.Setting> shownSettings(CopperGolemModConfig config) {
        List<ConfigSettings.Setting> shown = new ArrayList<>();
        for (ConfigSettings.Setting setting : ConfigSettings.ALL) {
            boolean needsConsent = setting == ConfigSettings.STATIONARY_SORTER
                && !config.stationarySorter.iUnderstandThisChangesCopperGolemBehavior;
            if (setting != ConfigSettings.STATIONARY_SORTER_CONSENT && !needsConsent) {
                shown.add(setting);
            }
        }
        return shown;
    }

    /** Dialog input names may only use letters, digits and underscores. */
    public static String inputKey(ConfigSettings.Setting setting) {
        return setting.key().replace('.', '_');
    }

    public static Dialog build(CopperGolemModConfig config) {
        List<Input> inputs = new ArrayList<>();
        for (ConfigSettings.Setting setting : shownSettings(config)) {
            inputs.add(new Input(inputKey(setting), control(setting, config)));
        }

        List<DialogBody> body = new ArrayList<>();
        body.add(new PlainMessage(Component.literal("Changes apply when you press Save, are saved to the config file, and are "
            + "logged with your name. For what each setting does, run /cgp config list and hover over it."), WIDTH));
        if (!config.stationarySorter.iUnderstandThisChangesCopperGolemBehavior) {
            body.add(new PlainMessage(Component.literal("The stationary sorter changes golem behavior, so it isn't on this screen until "
                    + "its consequences have been accepted with ")
                .append(Component.literal(ConfigCommands.ACCEPT_COMMAND).withStyle(style -> style.withColor(ChatFormatting.AQUA)
                    .withClickEvent(new ClickEvent.SuggestCommand(ConfigCommands.ACCEPT_COMMAND))
                    .withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to fill in")))))
                .append(Component.literal(".")), WIDTH));
        }

        CommonDialogData common = new CommonDialogData(Component.literal("CopperGolemMod settings"), Optional.empty(),
            true, false, DialogAction.CLOSE, body, inputs);
        ActionButton save = new ActionButton(
            new CommonButtonData(Component.literal("Save"), Optional.of(Component.literal("Apply and save the changed settings")), 150),
            Optional.of(new CustomAll(SAVE_ACTION, Optional.empty())));
        ActionButton cancel = new ActionButton(new CommonButtonData(Component.literal("Cancel"), 150), Optional.empty());
        return new MultiActionDialog(common, List.of(save), Optional.of(cancel), 1);
    }

    private static InputControl control(ConfigSettings.Setting setting, CopperGolemModConfig config) {
        Component label = Component.literal(setting.key());
        return switch (setting) {
            case ConfigSettings.Switch toggle -> new BooleanInput(label, toggle.get(config), "true", "false");
            case ConfigSettings.Whole whole when whole.max() - whole.min() <= MAX_SLIDER_STEPS -> new NumberRangeInput(WIDTH, label,
                "options.generic_value", new NumberRangeInput.RangeInfo(whole.min(), whole.max(), Optional.of((float) whole.get(config)), Optional.of(1.0F)));
            case ConfigSettings.Whole whole -> textBox(label, String.valueOf(whole.get(config)));
            case ConfigSettings.Decimal decimal -> textBox(label, String.valueOf(decimal.get(config)));
        };
    }

    private static TextInput textBox(Component label, String initial) {
        return new TextInput(WIDTH, label, true, initial, 24, Optional.empty());
    }

    /**
     * Handles Save: runs {@code /cgp config apply} with the settings whose values differ from the current config,
     * as {@code source} (the player who pressed Save).
     */
    public static void save(CommandSourceStack source, Optional<Tag> payload) {
        CompoundTag values = payload.orElse(null) instanceof CompoundTag compound ? compound : new CompoundTag();
        String command = applyCommand(values, CopperGolemMod.loadedConfig());
        if (command == null) {
            source.sendSuccess(() -> Component.literal("No settings changed."), false);
            return;
        }
        source.getServer().getCommands().performPrefixedCommand(source, command);
    }

    /** The {@code cgp config apply ...} command for the screen's values, or null when nothing changed. */
    public static String applyCommand(CompoundTag values, CopperGolemModConfig config) {
        List<String> assignments = new ArrayList<>();
        for (ConfigSettings.Setting setting : shownSettings(config)) {
            String value = value(setting, values.get(inputKey(setting)));
            if (value != null && !value.equals(String.valueOf(setting.get(config)))) {
                assignments.add(setting.key() + "=" + value);
            }
        }
        return assignments.isEmpty() ? null : "cgp config apply " + String.join(" ", assignments);
    }

    /** A screen value as command text, or null if the screen didn't send one. Toggles send 1/0, sliders a number. */
    private static String value(ConfigSettings.Setting setting, Tag tag) {
        return switch (tag) {
            case null -> null;
            case StringTag(String text) -> text.trim().isEmpty() ? null : text.trim().replaceAll("\\s+", "");
            case NumericTag number -> switch (setting) {
                case ConfigSettings.Switch ignored -> String.valueOf(number.byteValue() != 0);
                case ConfigSettings.Whole ignored -> String.valueOf(Math.round(number.floatValue()));
                case ConfigSettings.Decimal ignored -> String.valueOf(number.doubleValue());
            };
            default -> null;
        };
    }
}
