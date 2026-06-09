package lib.minecraft.renderer.pipeline.loader;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import lib.minecraft.renderer.exception.PipelineException;
import lib.minecraft.renderer.tooling.ToolingGlintItems;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentSet;
import dev.simplified.gson.GsonSettings;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;

/**
 * A loader that reads the bundled "always glinted" vanilla item set from the
 * {@code /lib/minecraft/renderer/glint_items.json} classpath resource and produces a set of
 * namespaced item ids.
 * <p>
 * The JSON resource is a checked-in snapshot of MC 26.1's
 * {@code net.minecraft.world.item.Items} static initializer as parsed by
 * {@link ToolingGlintItems.Parser} - the items registered with the default data component
 * {@code minecraft:enchantment_glint_override = true}. To refresh it on a Minecraft version bump,
 * run the {@code glintItems} Gradle task; the runtime pipeline never invokes the ASM walker directly.
 * <p>
 * Membership gates the automatic foil sheen at render time: an item in this set renders with the
 * enchantment glint even when its render options carry no explicit enchantment flag.
 */
@UtilityClass
public class GlintItemsLoader {

    private static final @NotNull String RESOURCE_PATH = "/lib/minecraft/renderer/glint_items.json";
    private static final @NotNull Gson GSON = GsonSettings.defaults().create();

    /**
     * Loads the bundled always-glinted item set.
     *
     * @return the set of namespaced item ids that carry an always-on glint override, unmodifiable
     * @throws PipelineException if the classpath resource is missing or malformed
     */
    public static @NotNull ConcurrentSet<String> load() {
        try (InputStream stream = GlintItemsLoader.class.getResourceAsStream(RESOURCE_PATH)) {
            if (stream == null)
                throw new PipelineException("Classpath resource '%s' not found - run the 'glintItems' Gradle task to generate it", RESOURCE_PATH);

            String json = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            return parse(json);
        } catch (IOException ex) {
            throw new PipelineException(ex, "Failed to read classpath resource '%s'", RESOURCE_PATH);
        }
    }

    /**
     * Parses a {@code glint_items.json}-shaped string into the id set. Exposed for tests.
     *
     * @param json the JSON text to parse
     * @return the set of namespaced item ids, unmodifiable
     */
    static @NotNull ConcurrentSet<String> parse(@NotNull String json) {
        HashSet<String> ids = new HashSet<>();
        try {
            JsonObject root = GSON.fromJson(json, JsonObject.class);
            JsonArray items = root.getAsJsonArray("items");
            if (items == null) return Concurrent.adoptSet(ids).toUnmodifiable();

            for (JsonElement element : items)
                ids.add(element.getAsString());
        } catch (JsonSyntaxException | IllegalStateException ex) {
            throw new PipelineException(ex, "Malformed '%s' resource", RESOURCE_PATH);
        }
        return Concurrent.adoptSet(ids).toUnmodifiable();
    }

}
