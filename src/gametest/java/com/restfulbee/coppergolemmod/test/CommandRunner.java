package com.restfulbee.coppergolemmod.test;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestion;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandOutput;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.test.TestContext;
import net.minecraft.text.Text;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs commands as an operator, exactly as typed in game, and records the replies. Command feedback that is
 * reported to operators also goes to the server log, as it would for a player.
 */
final class CommandRunner implements CommandOutput {
    static final Path CONFIG_FILE = FabricLoader.getInstance().getConfigDir().resolve("copper_golem_mod.json");

    private final MinecraftServer server;
    private final ServerCommandSource source;
    private final List<String> replies = new ArrayList<>();

    CommandRunner(TestContext helper) {
        server = helper.getWorld().getServer();
        source = server.getCommandSource().withOutput(this);
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
        return server.getCommandManager().getDispatcher().execute(command, source);
    }

    /** What tab completion offers for a partly typed command, as the chat box would show it. */
    List<String> suggestions(String partialCommand) {
        var dispatcher = server.getCommandManager().getDispatcher();
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
    public void sendMessage(Text message) {
        replies.add(message.getString());
    }

    @Override
    public boolean shouldReceiveFeedback() {
        return true;
    }

    @Override
    public boolean shouldTrackOutput() {
        return true;
    }

    @Override
    public boolean shouldBroadcastConsoleToOps() {
        return true;
    }
}
