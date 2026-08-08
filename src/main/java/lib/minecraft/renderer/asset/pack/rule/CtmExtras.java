package lib.minecraft.renderer.asset.pack.rule;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * The parse-and-store tier of a CTM rule - keys kept typed and round-trippable but never evaluated,
 * because CTM renders nothing. Holding them keeps the rule store honest for future tooling without
 * wiring a resolution path.
 *
 * @param compactReplacements the {@code ctm.<i>=} compact tile replacements, keyed by index
 * @param innerSeams the {@code innerSeams} flag
 * @param tintIndex the {@code tintIndex}, or {@code -1} when unset
 * @param tintBlock the {@code tintBlock}, if any
 * @param layer the {@code layer} render-pass name, if any
 * @param width the {@code width} for repeat grids ({@code 0} when unset)
 * @param height the {@code height} for repeat grids ({@code 0} when unset)
 */
public record CtmExtras(
    @NotNull ConcurrentMap<Integer, String> compactReplacements,
    boolean innerSeams,
    int tintIndex,
    @NotNull Optional<String> tintBlock,
    @NotNull Optional<String> layer,
    int width,
    int height
) {

    /** The empty extras block - nothing stored. */
    public static final @NotNull CtmExtras EMPTY = new CtmExtras(
        Concurrent.newMap(), false, -1, Optional.empty(), Optional.empty(), 0, 0);

}
