package com.restfulbee.coppergolemmod;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CopperGolemModConfigTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("CopperGolemModConfigTest");

    @TempDir
    Path dir;

    @Test
    void golemsLookAtPlayersByDefault() {
        CopperGolemModConfig config = new CopperGolemModConfig();
        assertTrue(config.behavior.golemsLookAtPlayers);
        assertFalse(config.isPlayerLookDisabled());
    }

    @Test
    void oldConfigWithoutBehaviorSectionKeepsVanillaLookingAndGainsTheOption() throws IOException {
        Path file = dir.resolve("copper_golem_mod.json");
        Files.writeString(file, """
            {
              "enabled": true,
              "optimizations": { "fastPlayerLookCheck": true, "fastNearbyEntitySort": true }
            }
            """);

        CopperGolemModConfig config = CopperGolemModConfig.load(file, LOGGER);

        assertTrue(config.behavior.golemsLookAtPlayers);
        assertFalse(config.isPlayerLookDisabled());
        assertTrue(Files.readString(file).contains("\"golemsLookAtPlayers\": true"), "rewritten file should list the new option");
    }

    @Test
    void lookingCanBeSwitchedOffButOnlyWhileTheModIsEnabled() throws IOException {
        Path file = dir.resolve("copper_golem_mod.json");
        Files.writeString(file, """
            { "enabled": true, "behavior": { "golemsLookAtPlayers": false } }
            """);
        CopperGolemModConfig config = CopperGolemModConfig.load(file, LOGGER);
        assertTrue(config.isPlayerLookDisabled());

        // The master switch (and the "off" side of /cgp bench ab) must mean fully vanilla.
        assertFalse(config.withEnabled(false).isPlayerLookDisabled());
        assertTrue(config.withEnabled(true).isPlayerLookDisabled());
        assertTrue(config.isPlayerLookDisabled(), "withEnabled must not modify the original");
    }

    @Test
    void brokenConfigFallsBackToDefaultsAndKeepsTheBrokenFile() throws IOException {
        Path file = dir.resolve("copper_golem_mod.json");
        String broken = "{ \"enabled\": false, \"behavior\": ";
        Files.writeString(file, broken);

        CopperGolemModConfig config = CopperGolemModConfig.load(file, LOGGER);

        assertTrue(config.enabled);
        assertTrue(config.behavior.golemsLookAtPlayers);
        assertEquals(broken, Files.readString(dir.resolve("copper_golem_mod.json.bad")));
    }
}
