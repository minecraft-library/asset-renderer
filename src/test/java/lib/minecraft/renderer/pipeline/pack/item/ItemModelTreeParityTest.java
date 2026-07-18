package lib.minecraft.renderer.pipeline.pack.item;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.gson.GsonSettings;
import lib.minecraft.renderer.asset.Item.LayerTint;
import lib.minecraft.renderer.asset.pack.MCMeta;
import lib.minecraft.renderer.asset.PackStack;
import lib.minecraft.renderer.asset.pack.PackCapability;
import lib.minecraft.renderer.asset.pack.PackContainer;
import lib.minecraft.renderer.asset.pack.PackId;
import lib.minecraft.renderer.asset.pack.PackRoot;
import lib.minecraft.renderer.asset.pack.ResourcePack;
import lib.minecraft.renderer.pipeline.pack.VanillaSourcePaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Pins the {@link ItemModelTreeLoader} projections byte-identical to the former
 * {@code ItemDefinitionLoader} over the real extracted vanilla 26.1 item tree - the parity guarantee
 * behind the block sweep (which only renders 35 blocks) and the item sweep (which renders none of the
 * 27 tinted items). The former loader's block-item-projection and fallback-descent-tint algorithms
 * are inlined here as the frozen reference, so this test survives that loader's deletion.
 *
 * <p>Reads the extracted vanilla assets at {@code cache/asset-renderer/vanilla/26.1/} and skips when
 * they are absent (no network / no pipeline run in this environment).
 */
@Tag("slow")
@DisplayName("ItemModelTreeLoader vanilla parity vs former ItemDefinitionLoader")
class ItemModelTreeParityTest {

    private static final Path VANILLA_ROOT = Path.of("cache/asset-renderer/vanilla/26.1");
    private static final Gson GSON = GsonSettings.defaults().create();

    @Test
    @DisplayName("block-item projection matches the former root-only scan")
    void blockItemProjectionParity() {
        Path itemsDir = VANILLA_ROOT.resolve("assets/minecraft/items");
        assumeTrue(Files.isDirectory(itemsDir), "extracted vanilla items tree required");

        ConcurrentMap<String, String> actual = ItemModelTreeLoader.deriveBlockItemModels(ItemModelTreeLoader.load(vanillaStack()));
        Map<String, String> expected = legacyBlockItemModels(itemsDir);

        assertThat("block-item projection is byte-identical to the former loader", new HashMap<>(actual), is(expected));
    }

    @Test
    @DisplayName("tint capture matches the former fallback-descent")
    void tintCaptureParity() {
        Path itemsDir = VANILLA_ROOT.resolve("assets/minecraft/items");
        assumeTrue(Files.isDirectory(itemsDir), "extracted vanilla items tree required");

        ConcurrentMap<String, List<LayerTint>> actual = ItemModelTreeLoader.deriveTints(ItemModelTreeLoader.load(vanillaStack()));
        Map<String, List<LayerTint>> expected = legacyTints(itemsDir);

        assertThat("tint capture is byte-identical to the former loader", new HashMap<>(actual), is(expected));
    }

    private static PackStack vanillaStack() {
        return PackStack.of(Concurrent.newList(new ResourcePack(
            PackId.VANILLA, new PackContainer.Directory(VANILLA_ROOT), MCMeta.EMPTY,
            Concurrent.newList(PackRoot.BASE).toUnmodifiable(), Set.of("minecraft"), Set.of(PackCapability.VANILLA_CORE))));
    }

    // --- frozen former-loader reference ---

    private static Map<String, String> legacyBlockItemModels(Path itemsDir) {
        Map<String, String> models = new HashMap<>();
        forEachItem(itemsDir, (id, json) -> {
            if (!json.has("model") || !json.get("model").isJsonObject()) return;
            JsonObject model = json.getAsJsonObject("model");
            if (!model.has("type") || !model.has("model")) return;
            if (!"minecraft:model".equals(model.get("type").getAsString())) return;
            String ref = model.get("model").getAsString();
            if (VanillaSourcePaths.isBlockModelRef(ref)) models.put(id, ref);
        });
        return models;
    }

    private static Map<String, List<LayerTint>> legacyTints(Path itemsDir) {
        Map<String, List<LayerTint>> tintMap = new HashMap<>();
        forEachItem(itemsDir, (id, json) -> {
            if (!json.has("model") || !json.get("model").isJsonObject()) return;
            JsonObject model = descendToTintedModel(json.getAsJsonObject("model"));
            if (model == null || !model.has("tints") || !model.get("tints").isJsonArray()) return;
            List<LayerTint> tints = new ArrayList<>();
            for (JsonElement element : model.getAsJsonArray("tints"))
                if (element.isJsonObject()) tints.add(parseTint(element.getAsJsonObject()));
            if (!tints.isEmpty()) tintMap.put(id, List.copyOf(tints));
        });
        return tintMap;
    }

    private static JsonObject descendToTintedModel(JsonObject model) {
        JsonObject current = model;
        for (int depth = 0; depth < 16 && current != null; depth++) {
            if (current.has("tints")) return current;
            String type = current.has("type") ? current.get("type").getAsString() : "";
            JsonObject next = switch (type) {
                case "minecraft:select", "minecraft:range_dispatch" -> childObject(current, "fallback");
                case "minecraft:condition" -> childObject(current, "on_false");
                default -> null;
            };
            if (next == null) return current;
            current = next;
        }
        return current;
    }

    private static JsonObject childObject(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonObject() ? object.getAsJsonObject(key) : null;
    }

    private static LayerTint parseTint(JsonObject tint) {
        String type = tint.has("type") ? tint.get("type").getAsString() : "";
        return switch (type) {
            case "minecraft:dye" -> new LayerTint.Dye(toArgb(tint, "default"));
            case "minecraft:potion" -> new LayerTint.Potion(toArgb(tint, "default"));
            case "minecraft:firework" -> new LayerTint.Firework(toArgb(tint, "default"));
            case "minecraft:constant" -> new LayerTint.Constant(toArgb(tint, "value"));
            default -> new LayerTint.Constant(0xFFFFFFFF);
        };
    }

    private static int toArgb(JsonObject tint, String key) {
        if (!tint.has(key)) return 0xFFFFFFFF;
        return 0xFF000000 | (tint.get(key).getAsInt() & 0xFFFFFF);
    }

    private interface ItemConsumer {
        void accept(String itemId, JsonObject json);
    }

    private static void forEachItem(Path itemsDir, ItemConsumer consumer) {
        try (Stream<Path> stream = Files.walk(itemsDir)) {
            stream.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".json")).forEach(p -> {
                String relative = itemsDir.relativize(p).toString().replace('\\', '/');
                String id = "minecraft:" + relative.substring(0, relative.length() - ".json".length());
                try {
                    JsonObject json = GSON.fromJson(Files.readString(p), JsonObject.class);
                    if (json != null) consumer.accept(id, json);
                } catch (IOException | JsonSyntaxException ignored) {
                    // Malformed / unreadable: the former loader skipped these too.
                }
            });
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }

}
