package com.restfulbee.coppergolemmod.test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The farm used by the workload benchmark, loaded from {@code copper_golem_mod_test/workload/farm.json}.
 * That file is produced by {@code tools/extract_farm.py} from a real world; positions are relative to the
 * test structure's corner. The format avoids Minecraft's own structure files so it loads on any version.
 */
final class FarmFixture {
    static final String RESOURCE = "copper_golem_mod_test/workload/farm.json";

    record ContainerSpec(BlockPos pos, boolean input, @Nullable String marker) {
    }

    record VillagerSpec(Vec3 pos, String profession, String type, int level, boolean baby) {
    }

    final JsonObject meta;
    final BlockPos size;
    final List<String> palette = new ArrayList<>();
    final List<int[]> blocks = new ArrayList<>();
    final List<ContainerSpec> containers = new ArrayList<>();
    final List<Vec3> golems = new ArrayList<>();
    final List<VillagerSpec> villagers = new ArrayList<>();

    private FarmFixture(JsonObject json) {
        meta = json.getAsJsonObject("meta");
        JsonArray size = json.getAsJsonArray("size");
        this.size = new BlockPos(size.get(0).getAsInt(), size.get(1).getAsInt(), size.get(2).getAsInt());
        json.getAsJsonArray("palette").forEach(state -> palette.add(state.getAsString()));
        for (JsonElement element : json.getAsJsonArray("blocks")) {
            JsonArray block = element.getAsJsonArray();
            blocks.add(new int[]{block.get(0).getAsInt(), block.get(1).getAsInt(), block.get(2).getAsInt(), block.get(3).getAsInt()});
        }
        for (JsonElement element : json.getAsJsonArray("containers")) {
            JsonObject container = element.getAsJsonObject();
            JsonElement marker = container.get("marker");
            containers.add(new ContainerSpec(
                blockPos(container.getAsJsonArray("pos")),
                "input".equals(container.get("role").getAsString()),
                marker == null || marker.isJsonNull() ? null : marker.getAsString()
            ));
        }
        json.getAsJsonArray("golems").forEach(golem -> golems.add(vec(golem.getAsJsonArray())));
        for (JsonElement element : json.getAsJsonArray("villagers")) {
            JsonObject villager = element.getAsJsonObject();
            villagers.add(new VillagerSpec(
                vec(villager.getAsJsonArray("pos")),
                villager.get("profession").getAsString(),
                villager.get("type").getAsString(),
                villager.get("level").getAsInt(),
                villager.has("baby") && villager.get("baby").getAsBoolean()
            ));
        }
    }

    static FarmFixture load() {
        Path path = FabricLoader.getInstance().getModContainer(CopperGolemModTestMod.MOD_ID)
            .flatMap(mod -> mod.findPath(RESOURCE))
            .orElseThrow(() -> new IllegalStateException("Missing test resource " + RESOURCE));
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return new FarmFixture(JsonParser.parseReader(reader).getAsJsonObject());
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read " + RESOURCE, exception);
        }
    }

    private static BlockPos blockPos(JsonArray array) {
        return new BlockPos(array.get(0).getAsInt(), array.get(1).getAsInt(), array.get(2).getAsInt());
    }

    private static Vec3 vec(JsonArray array) {
        return new Vec3(array.get(0).getAsDouble(), array.get(1).getAsDouble(), array.get(2).getAsDouble());
    }
}
