package lib.minecraft.renderer.tooling.blockentity;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import dev.simplified.collection.ConcurrentList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * Slow parity test: runs {@link TintDiscovery} against the cached 26.1 client jar and
 * compares the result set against {@code baseline/tinted_model_ids.json}.
 */
@DisplayName("TintDiscovery parity")
@Tag("slow")
class TintDiscoveryParityTest {

    private static final Path JAR = Path.of("cache/asset-renderer/vanilla/26.1/client.jar");
    private static final Path BASELINE = Path.of("src/test/resources/renderer/baseline/tinted_model_ids.json");

    @Test
    @DisplayName("TintDiscovery matches baseline/tinted_model_ids.json")
    void parity() throws IOException {
        try (ZipFile zip = new ZipFile(JAR.toFile())) {
            Diagnostics diag = new Diagnostics();
            ConcurrentList<Source> all = SourceDiscovery.discover(zip, diag);
            Map<String, BlockListDiscovery.EntityBlockMapping> blockList = BlockListDiscovery.discover(zip, diag);
            ConcurrentList<Source> filtered = dev.simplified.collection.Concurrent.newList();
            for (Source s : all) if (blockList.containsKey(s.entityId())) filtered.add(s);

            Map<String, String> entityIdToRenderer = new LinkedHashMap<>();
            for (Source s : filtered) entityIdToRenderer.put(s.entityId(), rendererFor(s));

            Set<String> discovered = TintDiscovery.discover(zip, filtered, entityIdToRenderer, diag);

            JsonArray expectedArr = new Gson().fromJson(Files.readString(BASELINE), JsonArray.class);
            Set<String> expected = new LinkedHashSet<>();
            for (JsonElement e : expectedArr) expected.add(e.getAsString());

            assertThat("tinted entity id set matches baseline", discovered, equalTo(expected));
        }
    }

    private static String rendererFor(Source s) {
        return switch (s.entityId()) {
            case "minecraft:chest" -> "net/minecraft/client/renderer/blockentity/ChestRenderer";
            case "minecraft:banner", "minecraft:banner_flag", "minecraft:wall_banner", "minecraft:wall_banner_flag" -> "net/minecraft/client/renderer/blockentity/BannerRenderer";
            case "minecraft:shulker_box" -> "net/minecraft/client/renderer/blockentity/ShulkerBoxRenderer";
            case "minecraft:bell_body" -> "net/minecraft/client/renderer/blockentity/BellRenderer";
            case "minecraft:copper_golem_statue" -> "net/minecraft/client/renderer/blockentity/CopperGolemStatueBlockRenderer";
            case "minecraft:skull_head", "minecraft:skull_humanoid_head", "minecraft:skull_dragon_head", "minecraft:skull_piglin_head" -> "net/minecraft/client/renderer/blockentity/SkullBlockRenderer";
            default -> s.classEntry().replace(".class", "");
        };
    }
}
