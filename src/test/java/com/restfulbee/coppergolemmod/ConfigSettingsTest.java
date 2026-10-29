package com.restfulbee.coppergolemmod;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigSettingsTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("ConfigSettingsTest");

    @TempDir
    Path dir;

    /** A new config option must get a /cgp config setting too. */
    @Test
    void everyConfigOptionHasASetting() {
        Set<String> options = new TreeSet<>();
        for (Field field : optionFields(CopperGolemModConfig.class)) {
            if (isValue(field.getType())) {
                options.add(field.getName());
            } else {
                for (Field nested : optionFields(field.getType())) {
                    if (isValue(nested.getType())) {
                        options.add(field.getName() + "." + nested.getName());
                    }
                }
            }
        }
        Set<String> settings = new TreeSet<>();
        ConfigSettings.ALL.forEach(setting -> settings.add(setting.key()));
        assertEquals(options, settings);
        ConfigSettings.ALL.forEach(setting -> assertSame(setting, ConfigSettings.get(setting.key())));
    }

    @Test
    void eachSettingChangesOnlyItsOwnFieldInTheFile() {
        Gson gson = new Gson();
        for (ConfigSettings.Setting setting : ConfigSettings.ALL) {
            CopperGolemModConfig config = new CopperGolemModConfig();
            Object value = nonDefault(setting);
            apply(setting, config, value);

            assertEquals(value, setting.get(config), setting.key());
            for (ConfigSettings.Setting other : ConfigSettings.ALL) {
                if (other != setting) {
                    assertTrue(other.isDefault(config), setting.key() + " also changed " + other.key());
                }
            }
            JsonElement inFile = gson.toJsonTree(config);
            for (String part : setting.key().split("\\.")) {
                inFile = ((JsonObject) inFile).get(part);
            }
            assertEquals(String.valueOf(value), inFile.getAsString(), "JSON path of " + setting.key());
        }
    }

    @Test
    void defaultsAreWithinTheirLimits() {
        for (ConfigSettings.Setting setting : ConfigSettings.ALL) {
            switch (setting) {
                case ConfigSettings.Whole whole ->
                    assertTrue(whole.get(new CopperGolemModConfig()) >= whole.min() && whole.get(new CopperGolemModConfig()) <= whole.max(), whole.key());
                case ConfigSettings.Decimal decimal ->
                    assertTrue(decimal.get(new CopperGolemModConfig()) >= decimal.min() && decimal.get(new CopperGolemModConfig()) <= decimal.max(), decimal.key());
                case ConfigSettings.Switch ignored -> {
                }
            }
        }
    }

    /** The file loader and the commands must accept the same values. */
    @Test
    void loaderAppliesTheSameLimitsAsTheCommands() throws IOException {
        Path file = dir.resolve("copper_golem_mod.json");
        for (boolean tooHigh : new boolean[]{false, true}) {
            CopperGolemModConfig written = new CopperGolemModConfig();
            List<ConfigSettings.Setting> numbers = new ArrayList<>();
            for (ConfigSettings.Setting setting : ConfigSettings.ALL) {
                switch (setting) {
                    case ConfigSettings.Whole whole -> whole.set(written, tooHigh ? whole.max() + 1 : whole.min() - 1);
                    case ConfigSettings.Decimal decimal -> decimal.set(written, tooHigh ? decimal.max() + 1 : decimal.min() - 1);
                    case ConfigSettings.Switch ignored -> {
                        continue;
                    }
                }
                numbers.add(setting);
            }
            Files.writeString(file, new Gson().toJson(written));

            CopperGolemModConfig loaded = CopperGolemModConfig.load(file, LOGGER);

            for (ConfigSettings.Setting setting : numbers) {
                Object expected = switch (setting) {
                    case ConfigSettings.Whole whole -> tooHigh ? whole.max() : whole.min();
                    // Below the minimum isn't a usable value for a decimal limit, so it falls back to the default.
                    case ConfigSettings.Decimal decimal -> tooHigh ? decimal.max() : decimal.defaultValue();
                    case ConfigSettings.Switch toggle -> toggle.defaultValue();
                };
                assertEquals(expected, setting.get(loaded), setting.key() + (tooHigh ? " above maximum" : " below minimum"));
            }
        }
    }

    /** What /cgp config apply (and so the settings screen) accepts must match the limits. */
    @Test
    void parseAcceptsExactlyTheAllowedValues() {
        for (ConfigSettings.Setting setting : ConfigSettings.ALL) {
            assertEquals(setting.defaultValue(), ConfigSettings.parse(setting, String.valueOf(setting.defaultValue())), setting.key());
            List<String> rejected = new ArrayList<>(List.of("", "abc", "1=2"));
            switch (setting) {
                case ConfigSettings.Switch ignored -> rejected.addAll(List.of("yes", "1", "TRUE"));
                case ConfigSettings.Whole whole -> {
                    assertEquals(whole.min(), ConfigSettings.parse(whole, String.valueOf(whole.min())), whole.key());
                    assertEquals(whole.max(), ConfigSettings.parse(whole, " " + whole.max() + " "), whole.key());
                    rejected.addAll(List.of(String.valueOf(whole.min() - 1), String.valueOf(whole.max() + 1), "4.5"));
                }
                case ConfigSettings.Decimal decimal -> {
                    assertEquals(decimal.max(), ConfigSettings.parse(decimal, String.valueOf(decimal.max())), decimal.key());
                    rejected.addAll(List.of("-0.1", String.valueOf(decimal.max() + 0.5), "NaN"));
                }
            }
            for (String value : rejected) {
                IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> ConfigSettings.parse(setting, value),
                    setting.key() + " accepted '" + value + "'");
                assertTrue(error.getMessage().contains(setting.allowed()), error.getMessage());
            }
        }
    }

    private static Object nonDefault(ConfigSettings.Setting setting) {
        Object value = switch (setting) {
            case ConfigSettings.Switch toggle -> !toggle.defaultValue().equals(true);
            case ConfigSettings.Whole whole -> whole.get(new CopperGolemModConfig()) == whole.max() ? whole.min() : whole.max();
            case ConfigSettings.Decimal decimal -> decimal.get(new CopperGolemModConfig()) == decimal.max() ? decimal.min() : decimal.max();
        };
        assertNotEquals(setting.defaultValue(), value, setting.key());
        return value;
    }

    private static void apply(ConfigSettings.Setting setting, CopperGolemModConfig config, Object value) {
        switch (setting) {
            case ConfigSettings.Switch toggle -> toggle.set(config, (Boolean) value);
            case ConfigSettings.Whole whole -> whole.set(config, (Integer) value);
            case ConfigSettings.Decimal decimal -> decimal.set(config, (Double) value);
        }
    }

    private static List<Field> optionFields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        for (Field field : type.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (Modifier.isPublic(modifiers) && !Modifier.isStatic(modifiers) && field.getType() != String.class) {
                fields.add(field);
            }
        }
        return fields;
    }

    private static boolean isValue(Class<?> type) {
        return type == boolean.class || type == int.class || type == double.class;
    }
}
