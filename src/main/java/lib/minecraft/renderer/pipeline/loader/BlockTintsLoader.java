package lib.minecraft.renderer.pipeline.loader;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.exception.PipelineException;
import lib.minecraft.renderer.pipeline.load.ArgbHex;
import lib.minecraft.renderer.pipeline.load.V2Document;
import lib.minecraft.renderer.pipeline.load.V2Resources;
import lib.minecraft.renderer.tooling2.ToolingBlockTints;
import lib.minecraft.renderer.tooling2.kernel.Diagnostics;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

/**
 * A loader that reads the bundled vanilla block tint table from the {@code v2/block_tints.json}
 * classpath resource and produces a lookup map of block id to {@link Block.Tint}.
 * <p>
 * The JSON resource is a checked-in snapshot of MC 26.1's
 * {@code net.minecraft.client.color.block.BlockColors.createDefault()} as parsed by
 * {@link ToolingBlockTints} {@code .Parser}. To refresh it on a Minecraft version bump, run the
 * {@code blockTints2} Gradle task; the runtime pipeline never invokes the ASM walker directly. Older
 * Minecraft versions reuse the same 26.1 tint set - blocks that don't exist in their era simply never
 * match a lookup, which the renderer treats as untinted.
 * <p>
 * Constants are stored as {@code 0x}-prefixed hex strings in the JSON because Gson cannot round-trip
 * {@code 0x80000000}-class signed integers literally; the native read decodes them through
 * {@link ArgbHex}.
 *
 * @see Block.Tint
 */
@UtilityClass
public class BlockTintsLoader {

    /**
     * Bundled tint snapshot's resource file name.
     */
    private static final @NotNull String RESOURCE_NAME = "block_tints.json";

    /**
     * Loads the bundled vanilla tint table into a map of block id to {@link Block.Tint}, natively
     * from v2. Iteration order mirrors the on-disk JSON.
     *
     * @return a map keyed by namespaced block id, wrapped unmodifiable
     * @throws PipelineException if the resource is missing, has no {@code tints} array, or cannot be
     *     parsed
     */
    public static @NotNull ConcurrentMap<String, Block.Tint> load() {
        return loadNative(Diagnostics.root("blockTints", Diagnostics.Output.CONSOLE, null));
    }

    /**
     * Reads the tint table from {@code v2/block_tints.json} natively through the shared read layer,
     * decoding each row's {@code constant} through {@link ArgbHex}, resolving {@code target} through
     * {@link Block.TintTarget} with an unknown value skipped-with-diagnostic rather than aborting the
     * load, and ignoring the v2-only {@code source} provenance + top-level {@code dropped} list.
     * Exposed for tests.
     *
     * @param diagnostics the scope envelope, target, and colour warnings are recorded to
     * @return a map keyed by namespaced block id, wrapped unmodifiable
     * @throws PipelineException if the resource is missing or malformed
     */
    static @NotNull ConcurrentMap<String, Block.Tint> loadNative(@NotNull Diagnostics diagnostics) {
        V2Document document = V2Resources.read(RESOURCE_NAME, V2Resources.MissingPolicy.REQUIRED, diagnostics).orElseThrow();
        return toTints(document.as(V2BlockTints.class).tints(), diagnostics);
    }

    /**
     * Maps the parsed v2 tint rows into the runtime lookup map, decoding each {@code constant} through
     * {@link ArgbHex}, resolving {@code target} through {@link Block.TintTarget} with an unknown value
     * skipped-with-diagnostic. Exposed for tests.
     *
     * @param rows the parsed v2 tint rows, in on-disk order
     * @param diagnostics the scope target and colour warnings are recorded to
     * @return a map keyed by namespaced block id, wrapped unmodifiable
     */
    static @NotNull ConcurrentMap<String, Block.Tint> toTints(@NotNull List<V2TintRow> rows, @NotNull Diagnostics diagnostics) {
        // Linked map so the runtime lookup table mirrors the JSON's deterministic on-disk order.
        LinkedHashMap<String, Block.Tint> tints = new LinkedHashMap<>();
        for (V2TintRow row : rows) {
            Block.TintTarget target;
            try {
                target = Block.TintTarget.valueOf(row.target());
            } catch (IllegalArgumentException ex) {
                diagnostics.warn("unknown tint target '%s' for block '%s' - skipping row", row.target(), row.block());
                continue;
            }
            Optional<Integer> constant = row.constant() == null
                ? Optional.empty()
                : Optional.of(ArgbHex.parse(row.constant(), diagnostics));
            tints.put(row.block(), new Block.Tint(target, constant));
        }
        return Concurrent.adoptLinkedMap(tints).toUnmodifiable();
    }

    /** The v2 {@code block_tints.json} payload: the ordered tint rows ({@code dropped} is ignored). */
    record V2BlockTints(@NotNull List<V2TintRow> tints) {}

    /**
     * One v2 tint row ({@code source} provenance is ignored).
     *
     * @param block the namespaced block id
     * @param target the {@link Block.TintTarget} enum name
     * @param constant the {@code 0xAARRGGBB} constant colour, or {@code null} for a colormap-sourced tint
     */
    record V2TintRow(@NotNull String block, @NotNull String target, @Nullable String constant) {}

}
