package dev.sbs.renderer.pipeline;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import dev.sbs.renderer.biome.BiomeTintTarget;
import dev.sbs.renderer.exception.AssetPipelineException;
import dev.sbs.renderer.model.BlockTint;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.gson.GsonSettings;
import dev.simplified.reflection.Reflection;
import dev.simplified.reflection.accessor.FieldAccessor;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Loads the bundled vanilla block tint table from the {@code renderer/vanilla_tints.json}
 * resource and produces persistable {@link BlockTint} JpaModel entities.
 * <p>
 * The JSON resource is a checked-in snapshot of MC 26.1's
 * {@code net.minecraft.client.color.block.BlockColors$createDefault()} as parsed by
 * {@link dev.sbs.renderer.pipeline.asm.BlockColorsParser BlockColorsParser}. To refresh it on
 * a Minecraft version bump, run the {@code generateVanillaTints} Gradle task; the runtime
 * pipeline never invokes the ASM walker directly. Older Minecraft versions reuse the same
 * 26.1 tint set - blocks that don't exist in their era simply never match a lookup, which the
 * renderer treats as untinted. The slight inaccuracy for old-version-only blocks is an
 * accepted tradeoff against the brittleness of per-version bytecode parsing and remapping.
 * <p>
 * Constants are stored as {@code 0x}-prefixed hex strings in the JSON because Gson cannot
 * round-trip {@code 0x80000000}-class signed integers literally. They round-trip via
 * {@link Integer#parseUnsignedInt(String, int)}.
 */
@UtilityClass
public class VanillaTintsLoader {

    private static final @NotNull String RESOURCE_PATH = "/renderer/vanilla_tints.json";
    private static final @NotNull Gson GSON = GsonSettings.defaults().create();

    private static final @NotNull Reflection<BlockTint> BLOCK_TINT_REFLECTION = new Reflection<>(BlockTint.class);
    private static final @NotNull FieldAccessor<String> BLOCK_TINT_BLOCK_ID = BLOCK_TINT_REFLECTION.getField("blockId");
    private static final @NotNull FieldAccessor<String> BLOCK_TINT_PACK_ID = BLOCK_TINT_REFLECTION.getField("packId");
    private static final @NotNull FieldAccessor<BiomeTintTarget> BLOCK_TINT_TARGET = BLOCK_TINT_REFLECTION.getField("target");
    private static final @NotNull FieldAccessor<Optional<Integer>> BLOCK_TINT_CONSTANT = BLOCK_TINT_REFLECTION.getField("tintConstant");

    /**
     * Loads the bundled vanilla tint table into a list of {@link BlockTint} entities tagged with
     * the supplied pack id (typically {@code "vanilla"}).
     *
     * @param packId the pack id every emitted entity is stamped with
     * @return a fresh list of block tint entities
     * @throws AssetPipelineException if the resource is missing or cannot be parsed
     */
    public static @NotNull ConcurrentList<BlockTint> load(@NotNull String packId) {
        ConcurrentList<BlockTint> tints = Concurrent.newList();

        try (InputStream stream = VanillaTintsLoader.class.getResourceAsStream(RESOURCE_PATH)) {
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
                BiomeTintTarget target = BiomeTintTarget.valueOf(entry.get("target").getAsString());
                Optional<Integer> constant = entry.has("constant")
                    ? Optional.of(Integer.parseUnsignedInt(entry.get("constant").getAsString().substring(2), 16))
                    : Optional.empty();
                tints.add(buildEntity(blockId, packId, target, constant));
            }
        } catch (IOException | JsonSyntaxException ex) {
            throw new AssetPipelineException(ex, "Failed to load vanilla tints resource '%s'", RESOURCE_PATH);
        }

        return tints;
    }

    /**
     * Materialises a single {@link BlockTint} JpaModel via cached reflection. The renderer's
     * JPA entities expose only {@code @Getter} accessors so the loader reaches through with
     * the Simplified-Dev {@link Reflection} wrapper rather than widening the entity API.
     */
    private static @NotNull BlockTint buildEntity(
        @NotNull String blockId,
        @NotNull String packId,
        @NotNull BiomeTintTarget target,
        @NotNull Optional<Integer> constant
    ) {
        BlockTint tint = new BlockTint();
        BLOCK_TINT_BLOCK_ID.set(tint, blockId);
        BLOCK_TINT_PACK_ID.set(tint, packId);
        BLOCK_TINT_TARGET.set(tint, target);
        if (constant.isPresent())
            BLOCK_TINT_CONSTANT.set(tint, constant);
        return tint;
    }

}
