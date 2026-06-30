package lib.minecraft.renderer.asset;

import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.model.ModelData;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * A fully-parsed item definition backed by its vanilla model JSON.
 * <p>
 * Every field is populated once during {@code Pipeline} bootstrap and stored verbatim. A
 * non-zero {@link #getMaxDurability()} gates the GUI damage bar overlay at render time. The
 * {@link #getTints() tints} list carries the per-layer {@link LayerTint} rules parsed from the
 * MC 26.1 item definition's {@code model.tints[]} array (array index = layer {@code tintindex}),
 * empty for the majority of items that declare no tints.
 */
@Getter
@AllArgsConstructor
@EqualsAndHashCode
public final class Item {

    /**
     * The item's namespaced identifier (e.g. {@code minecraft:diamond_sword}).
     */
    private final @NotNull ResourceId id;

    /**
     * The resolved model supplying the item's layered sprites or 3D geometry.
     */
    private final @NotNull ModelData model;

    /**
     * The model's texture variable bindings, keyed by variable name (e.g. {@code layer0}).
     */
    private final @NotNull ConcurrentMap<String, String> textures;

    /**
     * The item's maximum durability, or {@code 0} for items that take no damage. A non-zero value
     * gates the GUI damage-bar overlay at render time.
     */
    private final int maxDurability;

    /**
     * Ordered per-layer tint rules from the item definition's {@code model.tints[]} array, where
     * index {@code N} applies to {@code layerN}. Empty for items that declare no tints.
     */
    private final @NotNull List<LayerTint> tints;

    /**
     * Whether vanilla registers this item with {@code enchantment_glint_override = true}, making it
     * intrinsically foil (enchanted_book, written_book, nether_star, etc.). When set, the renderer
     * composites the enchantment glint automatically, independent of any per-stack enchantment flag.
     */
    private final boolean alwaysGlinted;

}
