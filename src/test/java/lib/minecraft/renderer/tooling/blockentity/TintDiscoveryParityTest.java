package lib.minecraft.renderer.tooling.blockentity;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.gson.GsonSettings;
import lib.minecraft.renderer.tooling.util.Diagnostics;
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
 * Slow parity test pinning {@link TintDiscovery#discover} against the cached 26.1 client jar,
 * compared to the {@code baseline/tinted_model_ids.json} set of entity ids that carry a
 * {@code tintindex=0} marker.
 *
 * <p>The wire-up mirrors the real tooling: {@link SourceDiscovery} produces the source list,
 * {@link BlockListDiscovery} supplies the atlas membership filter, and a {@link #rendererFor}
 * map reconstructs the {@code entityId -> renderer internal name} binding that
 * {@code TintDiscovery} scans for {@code DyeColor} / {@code BannerPattern} tint-accessor calls.
 * The expected outcome is that only the {@code Flag}-family banner sub-models surface as tinted.
 */
@DisplayName("TintDiscovery parity")
@Tag("slow")
class TintDiscoveryParityTest {

    /** The cached deobfuscated 26.1 client jar discovery walks. */
    private static final Path JAR = Path.of("cache/asset-renderer/vanilla/26.1/client.jar");
    /** The hand-curated ground-truth set of tinted entity ids discovery is diffed against. */
    private static final Path BASELINE = Path.of("src/test/resources/lib/minecraft/renderer/baseline/tinted_model_ids.json");
    /** Project-standard Gson, used to parse the baseline JSON array. */
    private static final Gson GSON = GsonSettings.defaults().create();

    @Test
    @DisplayName("TintDiscovery matches baseline/tinted_model_ids.json")
    void parity() throws IOException {
        try (ZipFile zip = new ZipFile(JAR.toFile())) {
            Diagnostics diag = new Diagnostics();
            ConcurrentList<Source> all = SourceDiscovery.discover(zip, diag);
            Map<String, BlockListDiscovery.EntityBlockMapping> blockList = BlockListDiscovery.discover(zip, diag);
            ConcurrentList<Source> filtered = Concurrent.newList();
            for (Source s : all) if (blockList.containsKey(s.entityId())) filtered.add(s);

            Map<String, String> entityIdToRenderer = new LinkedHashMap<>();
            for (Source s : filtered) entityIdToRenderer.put(s.entityId(), rendererFor(s));

            Set<String> discovered = TintDiscovery.discover(zip, filtered, entityIdToRenderer, diag);

            JsonArray expectedArr = GSON.fromJson(Files.readString(BASELINE), JsonArray.class);
            Set<String> expected = new LinkedHashSet<>();
            for (JsonElement e : expectedArr) expected.add(e.getAsString());

            assertThat("tinted entity id set matches baseline", discovered, equalTo(expected));
        }
    }

    /**
     * Reconstructs the {@code entityId -> renderer internal name} binding that production feeds
     * {@link TintDiscovery#discover} from {@link SourceDiscovery}'s registry walk. Multi-source
     * renderers (chest, banner family, shulker box, bell, copper golem statue, the four skull
     * variants) are mapped explicitly since their entity ids don't match their model classEntry;
     * everything else falls back to the source's own classEntry (minus the {@code .class} suffix),
     * which for single-model block entities is also the renderer scanned for tint-accessor calls.
     */
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
