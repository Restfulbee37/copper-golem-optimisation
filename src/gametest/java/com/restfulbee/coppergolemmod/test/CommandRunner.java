package com.restfulbee.coppergolemmod.test;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestion;
import com.restfulbee.coppergolemmod.ConfigScreen;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Runs commands as an operator, exactly as typed in game, and records the replies. Command feedback that is
 * reported to operators also goes to the server log, as it would for a player.
 */
final class CommandRunner implements CommandSource {
    static final Path CONFIG_FILE = FabricLoader.getInstance().getConfigDir().resolve("copper_golem_mod.json");

    private final MinecraftServer server;
    private final CommandSourceStack source;
    private final List<String> replies = new ArrayList<>();

    CommandRunner(GameTestHelper helper) {
        server = helper.getLevel().getServer();
        source = server.createCommandSourceStack().withSource(this);
    }

    /** Runs a command (without the leading slash) and returns its result; replies replace the previous ones. */
    int run(String command) {
        try {
            return execute(command);
        } catch (CommandSyntaxException exception) {
            throw new IllegalStateException("/" + command + " was rejected: " + exception.getMessage(), exception);
        }
    }

    /** True when the command can't even be parsed, e.g. an unknown setting or a value out of range. */
    boolean rejects(String command) {
        try {
            execute(command);
            return false;
        } catch (CommandSyntaxException expected) {
            return true;
        }
    }

    private int execute(String command) throws CommandSyntaxException {
        replies.clear();
        return server.getCommands().getDispatcher().execute(command, source);
    }

    /** Presses Save on the settings screen with these values, as this operator. */
    void saveSettingsScreen(CompoundTag values) {
        replies.clear();
        ConfigScreen.save(source, Optional.of(values.copy()));
    }

    /** What tab completion offers for a partly typed command, as the chat box would show it. */
    List<String> suggestions(String partialCommand) {
        var dispatcher = server.getCommands().getDispatcher();
        return dispatcher.getCompletionSuggestions(dispatcher.parse(partialCommand, source)).join()
            .getList().stream().map(Suggestion::getText).toList();
    }

    String replies() {
        return String.join("\n", replies);
    }

    /**
     * Runs {@code body}, then puts the config file back as it was and reloads it, so a test that changes settings
     * leaves nothing behind for the other tests, even when it fails.
     */
    void restoringConfig(Runnable body) {
        String original = readConfigFile();
        try {
            body.run();
        } finally {
            try {
                Files.writeString(CONFIG_FILE, original, StandardCharsets.UTF_8);
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
            run("cgp reload");
        }
    }

    static String readConfigFile() {
        try {
            return Files.readString(CONFIG_FILE, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    @Override
    public void sendSystemMessage(Component message) {
        replies.add(message.getString());
    }

    @Override
    public boolean acceptsSuccess() {
        return true;
    }

    @Override
    public boolean acceptsFailure() {
        return true;
    }

    @Override
    public boolean shouldInformAdmins() {
        return true;
    }
}
