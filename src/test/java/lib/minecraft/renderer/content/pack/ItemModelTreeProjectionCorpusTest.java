package lib.minecraft.renderer.content.pack;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.gson.GsonSettings;
import lib.minecraft.renderer.asset.Item.LayerTint;
import lib.minecraft.renderer.asset.pack.MCMeta;
import lib.minecraft.renderer.asset.pack.PackCapability;
import lib.minecraft.renderer.asset.pack.PackRoot;
import lib.minecraft.renderer.asset.pack.ResourcePack;
import lib.minecraft.renderer.content.container.PackContainer;
import lib.minecraft.renderer.vanilla.VanillaPaths;
import lib.minecraft.renderer.vanilla.id.PackId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A whole-corpus run of both {@link ItemModelTreeLoader} projections - the block-item inventory map
 * and the fallback-descent tint capture - over the real extracted vanilla 26.1 item tree, each
 * compared against an independent implementation of the same algorithm inlined below. Neither sweep
 * reaches enough of the corpus to cover these entries, which is why the whole tree is walked here.
 *
 * <p>The reference algorithms exist nowhere but in this file, so a mismatch says a projection
 * <b>changed</b> and never that it is <b>wrong</b>: read both sides before deciding which one moved.
 *
 * <p>Reads the extracted vanilla assets at {@code cache/asset-renderer/vanilla/26.1/} and skips when
 * they are absent (no network / no pipeline run in this environment).
 */
@DisplayName("ItemModelTreeLoader projections over the whole vanilla item corpus")
class ItemModelTreeProjectionCorpusTest {

    private static final Path VANILLA_ROOT = Path.of("cache/asset-renderer/vanilla/26.1");
    private static final Gson GSON = GsonSettings.defaults().create();

    @Test
    @DisplayName("block-item projection matches an independent neutral walk, the select-rooted icons included")
    void blockItemProjectionParity() {
        Path itemsDir = VANILLA_ROOT.resolve("assets/minecraft/items");
        assumeTrue(Files.isDirectory(itemsDir), "extracted vanilla items tree required");

        ConcurrentMap<String, String> actual = ItemModelTreeLoader.deriveBlockItemModels(ItemModelTreeLoader.load(vanillaStack()));
        Map<String, String> expected = neutralBlockItemModels(itemsDir);

        assertThat("block-item projection agrees with the reference walk", new HashMap<>(actual), is(expected));
        assertThat("vanilla projects 707 block items", actual.size(), is(707));
        assertThat(actual.get("minecraft:beehive"), is("minecraft:block/beehive_empty"));
        assertThat(actual.get("minecraft:bee_nest"), is("minecraft:block/bee_nest_empty"));
        assertThat(actual.get("minecraft:test_block"), is("minecraft:block/test_block_start"));
    }

    @Test
    @DisplayName("tint capture matches the former fallback-descent")
    void tintCaptureParity() {
        Path itemsDir = VANILLA_ROOT.resolve("assets/minecraft/items");
        assumeTrue(Files.isDirectory(itemsDir), "extracted vanilla items tree required");

        ConcurrentMap<String, ConcurrentList<LayerTint>> actual = ItemModelTreeLoader.deriveTints(ItemModelTreeLoader.load(vanillaStack()));
        Map<String, List<LayerTint>> expected = legacyTints(itemsDir);

        assertThat("tint capture is byte-identical to the former loader",
            new HashMap<String, List<LayerTint>>(actual), is(expected));
    }

    private static PackStack vanillaStack() {
        return PackStack.of(Concurrent.newList(new ResourcePack(
            PackId.VANILLA, new PackContainer.Directory(VANILLA_ROOT), MCMeta.EMPTY,
            Concurrent.newList(PackRoot.BASE).toUnmodifiable(), Concurrent.newUnmodifiableSet("minecraft"),
            Concurrent.newUnmodifiableSet(PackCapability.VANILLA_CORE))));
    }

    // --- independent reference walks ---

    /**
     * The block items the neutral walk projects, walked over the raw JSON. A condition takes
     * {@code on_false}, where the neutral context sends every condition vanilla ships; a select takes
     * its {@code display_context} {@code gui} case or its {@code context_dimension} overworld case,
     * else its fallback; a range dispatch takes the first entry of the highest threshold at or below
     * {@code 0}, else its fallback; a composite refuses. An item projects when the walk ends on a
     * {@code minecraft:model} naming a block model.
     */
    private static Map<String, String> neutralBlockItemModels(Path itemsDir) {
        Map<String, String> models = new HashMap<>();
        forEachItem(itemsDir, (id, json) -> {
            if (!json.has("model") || !json.get("model").isJsonObject()) return;
            neutralLeaf(json.getAsJsonObject("model"))
                .filter(VanillaPaths::isBlockModelRef)
                .ifPresent(ref -> models.put(id, ref));
        });
        return models;
    }

    private static Optional<String> neutralLeaf(JsonObject node) {
        String type = node.has("type") ? node.get("type").getAsString() : "";
        return switch (type) {
            case "minecraft:model" -> node.has("model") ? Optional.of(node.get("model").getAsString()) : Optional.empty();
            case "minecraft:condition" -> Optional.ofNullable(childObject(node, "on_false"))
                .flatMap(ItemModelTreeProjectionCorpusTest::neutralLeaf);
            case "minecraft:select" -> Optional.ofNullable(neutralCase(node))
                .flatMap(ItemModelTreeProjectionCorpusTest::neutralLeaf);
            case "minecraft:range_dispatch" -> Optional.ofNullable(neutralEntry(node))
                .flatMap(ItemModelTreeProjectionCorpusTest::neutralLeaf);
            default -> Optional.empty();
        };
    }

    private static JsonObject neutralCase(JsonObject select) {
        String property = select.has("property") ? select.get("property").getAsString() : "";
        String key = switch (property) {
            case "minecraft:display_context" -> "gui";
            case "minecraft:context_dimension" -> "minecraft:overworld";
            default -> null;
        };
        if (key != null && select.has("cases")) {
            for (JsonElement option : select.getAsJsonArray("cases")) {
                JsonElement when = option.getAsJsonObject().get("when");
                List<JsonElement> values = when.isJsonArray() ? when.getAsJsonArray().asList() : List.of(when);
                if (values.stream().anyMatch(value -> value.getAsString().equals(key)))
                    return childObject(option.getAsJsonObject(), "model");
            }
        }
        return childObject(select, "fallback");
    }

    private static JsonObject neutralEntry(JsonObject range) {
        JsonObject best = null;
        float bestThreshold = 0f;
        if (range.has("entries")) {
            for (JsonElement element : range.getAsJsonArray("entries")) {
                JsonObject entry = element.getAsJsonObject();
                float threshold = entry.get("threshold").getAsFloat();
                if (threshold <= 0f && (best == null || threshold > bestThreshold)) {
                    best = entry;
                    bestThreshold = threshold;
                }
            }
        }
        return best != null ? childObject(best, "model") : childObject(range, "fallback");
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
            case "minecraft:grass" ->
                new LayerTint.Grass(tint.get("temperature").getAsFloat(), tint.get("downfall").getAsFloat());
            case "minecraft:map_color" -> new LayerTint.MapColor(toArgb(tint, "default"));
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
            throw new UncheckedIOException(ex);
        }
    }

}
