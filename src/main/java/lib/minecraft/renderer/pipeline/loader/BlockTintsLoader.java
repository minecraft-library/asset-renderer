package lib.minecraft.renderer.pipeline.loader;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.gson.GsonSettings;
import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.exception.AssetPipelineException;
import lib.minecraft.renderer.geometry.Biome;
import lib.minecraft.renderer.tooling.ToolingBlockTints;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * A loader that reads the bundled vanilla block tint table from the
 * {@code /lib/minecraft/renderer/block_tints.json} classpath resource and produces a lookup map of block
 * id to {@link Block.Tint}.
 * <p>
 * The JSON resource is a checked-in snapshot of MC 26.1's
 * {@code net.minecraft.client.color.block.BlockColors$createDefault()} as parsed by
 * {@link ToolingBlockTints.Parser}. To refresh it on a Minecraft version bump, run the
 * {@code generateBlockTints} Gradle task; the runtime pipeline never invokes the ASM walker
 * directly. Older Minecraft versions reuse the same 26.1 tint set - blocks that don't exist
 * in their era simply never match a lookup, which the renderer treats as untinted. The slight
 * inaccuracy for old-version-only blocks is an accepted tradeoff against the brittleness of
 * per-version bytecode parsing and remapping.
 * <p>
 * Constants are stored as {@code 0x}-prefixed hex strings in the JSON because Gson cannot
 * round-trip {@code 0x80000000}-class signed integers literally. They round-trip via
 * {@link Integer#parseUnsignedInt(String, int)}.
 *
 * @see ToolingBlockTints.Parser
 * @see Block.Tint
 */
@UtilityClass
public class BlockTintsLoader {

    private static final @NotNull String RESOURCE_PATH = "/lib/minecraft/renderer/block_tints.json";
    private static final @NotNull Gson GSON = GsonSettings.defaults().create();

    /**
     * Loads the bundled vanilla tint table into a map of block id to {@link Block.Tint}.
     *
     * @return a map keyed by namespaced block id
     * @throws AssetPipelineException if the resource is missing or cannot be parsed
     */
    public static @NotNull ConcurrentMap<String, Block.Tint> load() {
        ConcurrentMap<String, Block.Tint> tints = Concurrent.newMap();

        try (InputStream stream = BlockTintsLoader.class.getResourceAsStream(RESOURCE_PATH)) {
            if (stream == null)
                throw new AssetPipelineException("Vanilla tints resource '%s' not found on the classpath", RESOURCE_PATH);

            String json = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            JsonObject root = GSON.fromJson(json, JsonObject.class);
            if (root == null || !root.has("tints"))
                throw new AssetPipelineException("Vanilla tints resource '%s' has no 'tints' array", RESOURCE_PATH);

            JsonArray entries = root.getAsJsonArray("tints");
            for (JsonElement element : entries) {
                JsonObject entry = element.getAsJsonObject();
                String blockId = entry.get("block").getAsString();
                Biome.TintTarget target = Biome.TintTarget.valueOf(entry.get("target").getAsString());
                Optional<Integer> constant = entry.has("constant")
                    ? Optional.of(Integer.parseUnsignedInt(entry.get("constant").getAsString().substring(2), 16))
                    : Optional.empty();
                tints.put(blockId, new Block.Tint(target, constant));
            }
        } catch (IOException | JsonSyntaxException ex) {
            throw new AssetPipelineException(ex, "Failed to load vanilla tints resource '%s'", RESOURCE_PATH);
        }

        return tints;
    }

}
