package lib.minecraft.renderer.appearance;

/**
 * A single per-layer tint rule from an MC 26.1 item definition's {@code model.tints[]} array, where
 * the array index is the layer's {@code tintindex}.
 * <p>
 * Vanilla resolves each tint from item components (a dyed-leather colour, a potion's effect colour,
 * a firework's explosion colour) and multiplies it into the matching {@code layerN} sprite, falling
 * back to the {@code default} when the component is absent. This renderer mirrors that: the
 * sealed variants carry the JSON-declared default (or, for {@link Constant}, the fixed value) and
 * the item renderer resolves the effective ARGB from the render options before multiplying.
 * <p>
 * Tint source types this renderer does not resolve dynamically ({@code minecraft:grass},
 * {@code minecraft:map_color}, {@code minecraft:custom_model_data}, ...) parse to {@link Constant}
 * of white so they render untinted rather than guessing a colour.
 */
public sealed interface LayerTint
    permits LayerTint.Dye, LayerTint.Potion, LayerTint.Firework, LayerTint.Constant {

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
     * A {@code minecraft:constant} tint (or any unresolved dynamic source) - a fixed ARGB applied
     * verbatim. {@code 0xFFFFFFFF} (white) is a no-op tint.
     *
     * @param argb the fixed ARGB
     */
    record Constant(int argb) implements LayerTint {}

}
