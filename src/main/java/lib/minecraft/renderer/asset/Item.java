package lib.minecraft.renderer.asset;

import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.model.ModelData;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import org.jetbrains.annotations.NotNull;

/**
 * A fully-parsed item definition backed by its vanilla model JSON.
 * <p>
 * Every field is populated once during {@code ClientAcquisition} bootstrap and stored verbatim. A
 * non-zero {@link #maxDurability()} gates the GUI damage bar overlay at render time. The
 * {@link #tints() tints} list carries the per-layer {@link LayerTint} rules parsed from the
 * MC 26.1 item definition's {@code model.tints[]} array (array index = layer {@code tintindex}),
 * empty for the majority of items that declare no tints.
 *
 * @param id the item's namespaced identifier (e.g. {@code minecraft:diamond_sword})
 * @param model the resolved model supplying the item's layered sprites or 3D geometry
 * @param textures the model's texture variable bindings, keyed by variable name (e.g.
 *     {@code layer0})
 * @param maxDurability the item's maximum durability, or {@code 0} for items that take no damage. A
 *     non-zero value gates the GUI damage-bar overlay at render time
 * @param tints ordered per-layer tint rules from the item definition's {@code model.tints[]} array,
 *     where index {@code N} applies to {@code layerN}. Empty for items that declare no tints
 * @param alwaysGlinted whether vanilla registers this item with
 *     {@code enchantment_glint_override = true}, making it intrinsically foil (enchanted_book,
 *     written_book, nether_star, etc.). When set, the renderer composites the enchantment glint
 *     automatically, independent of any per-stack enchantment flag
 */
public record Item(
    @NotNull ResourceId id,
    @NotNull ModelData model,
    @NotNull ConcurrentMap<String, String> textures,
    int maxDurability,
    @NotNull ConcurrentList<LayerTint> tints,
    boolean alwaysGlinted
) {

    /**
     * A single per-layer tint rule from an MC 26.1 item definition's {@code model.tints[]} array, where
     * the array index is the layer's {@code tintindex}.
     * <p>
     * Vanilla calculates each tint on every render - from an item component (a dyed-leather colour, a
     * potion's effect colour, a firework's explosion colour, a map's colour), falling back to the
     * {@code default} when the component is absent, or from the grass colormap - and multiplies it into
     * the matching {@code layerN} sprite. This renderer mirrors that: the sealed variants carry what
     * the JSON declares (a default, a fixed value or a climate point) and the item renderer resolves
     * the effective ARGB from the render options and the pack stack before multiplying.
     * <p>
     * Tint source types this renderer does not model ({@code minecraft:custom_model_data},
     * {@code minecraft:team}) parse to {@link Constant} of white so they render untinted rather than
     * guessing a colour.
     */
    public sealed interface LayerTint
        permits LayerTint.Dye, LayerTint.Potion, LayerTint.Firework, LayerTint.Grass, LayerTint.MapColor,
            LayerTint.Constant {

        /**
         * A {@code minecraft:dye} tint - the dyed-leather colour. Resolves from the render options'
         * leather / general tint override, else the JSON default ({@code #A06540} for vanilla leather).
         *
         * @param defaultColor the ARGB applied when no dye override is supplied
         */
        record Dye(int defaultColor) implements LayerTint {}

        /**
         * A {@code minecraft:potion} tint - the potion-contents colour. Resolves from the render
         * options' potion override, else the first potion effect's colour, else the JSON default
         * ({@code #385DC6}, water).
         *
         * @param defaultColor the ARGB applied when no potion colour is supplied
         */
        record Potion(int defaultColor) implements LayerTint {}

        /**
         * A {@code minecraft:firework} tint - the firework-star explosion colour. Resolves from the
         * render options' firework override, else the JSON default ({@code #8A8A8A}, vanilla's no-NBT
         * placeholder gray).
         *
         * @param defaultColor the ARGB applied when no firework colour is supplied
         */
        record Firework(int defaultColor) implements LayerTint {}

        /**
         * A {@code minecraft:grass} tint - the grass colormap sampled at the definition's own climate
         * point, with no biome modifier and no override. Resolves against the grass colormap the pack
         * stack carries.
         *
         * @param temperature the climate temperature the colormap is sampled at, in {@code [0, 1]}
         * @param downfall the climate downfall the colormap is sampled at, in {@code [0, 1]}
         */
        record Grass(float temperature, float downfall) implements LayerTint {

            /**
             * Refuses a climate point outside the {@code [0, 1]} range vanilla's codec admits.
             *
             * @throws IllegalArgumentException if either value lies outside {@code [0, 1]}
             */
            public Grass {
                if (!(temperature >= 0f && temperature <= 1f) || !(downfall >= 0f && downfall <= 1f))
                    throw new IllegalArgumentException(String.format(
                        "Grass tint climate ('%s', '%s') lies outside [0, 1]", temperature, downfall));
            }

        }

        /**
         * A {@code minecraft:map_color} tint - a filled map's markings colour. Resolves from the render
         * options' tint override, else the JSON default ({@code #46402E} for vanilla's filled map).
         *
         * @param defaultColor the ARGB applied when no map colour is supplied
         */
        record MapColor(int defaultColor) implements LayerTint {}

        /**
         * A {@code minecraft:constant} tint (or a source type this renderer does not model) - a fixed
         * ARGB applied verbatim. {@code 0xFFFFFFFF} (white) is a no-op tint.
         *
         * @param argb the fixed ARGB
         */
        record Constant(int argb) implements LayerTint {}

    }

}
