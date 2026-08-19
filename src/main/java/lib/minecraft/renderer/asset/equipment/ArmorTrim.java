package lib.minecraft.renderer.asset.equipment;

import dev.simplified.annotations.Getter;
import dev.simplified.annotations.RequiredArgsConstructor;
import lib.minecraft.renderer.parity.Parity;
import org.jetbrains.annotations.NotNull;

/**
 * A trim overlaid on an armor piece - a grayscale {@link Pattern} texture recoloured through a
 * {@link Color} palette and composited over the base {@link ArmorMaterial} layer.
 * <p>
 * The two halves travel together because neither draws without the other. Carrying them as one value
 * rather than as two independent options is what makes the half-present state unrepresentable, so no
 * compositor has to ask whether both are there before reading either.
 *
 * @param pattern the pattern shape overlaid on the armor
 * @param color the palette the grayscale pattern is recoloured through
 */
@Parity(claim = "option-surface")
public record ArmorTrim(
    @NotNull Pattern pattern,
    @NotNull Color color
) {

    /**
     * The trim colour palette that recolours a grayscale trim pattern via paletted permutation.
     * Use the {@code _DARKER} variants when the trim material matches the armor material (e.g.
     * {@link #IRON_DARKER} for an iron trim on iron armor) so the pattern stays visible.
     */
    @Getter
    @RequiredArgsConstructor
    public enum Color {

        AMETHYST("amethyst"),
        COPPER("copper"),
        COPPER_DARKER("copper_darker"),
        DIAMOND("diamond"),
        DIAMOND_DARKER("diamond_darker"),
        EMERALD("emerald"),
        GOLD("gold"),
        GOLD_DARKER("gold_darker"),
        IRON("iron"),
        IRON_DARKER("iron_darker"),
        LAPIS("lapis"),
        NETHERITE("netherite"),
        NETHERITE_DARKER("netherite_darker"),
        QUARTZ("quartz"),
        REDSTONE("redstone"),
        RESIN("resin");

        /**
         * The palette file key ({@code trims/color_palettes/{key}}).
         */
        private final @NotNull String key;

    }

    /**
     * The trim pattern shape overlaid on armor (both 2D compositing and 3D entity rendering). Each
     * pattern maps to a grayscale texture at {@code trims/entity/humanoid/{key}.png} (layer 1) and
     * {@code trims/entity/humanoid_leggings/{key}.png} (layer 2), recoloured through a {@link Color}
     * palette before compositing.
     */
    @Getter
    @RequiredArgsConstructor
    public enum Pattern {

        BOLT("bolt"),
        COAST("coast"),
        DUNE("dune"),
        EYE("eye"),
        FLOW("flow"),
        HOST("host"),
        RAISER("raiser"),
        RIB("rib"),
        SENTRY("sentry"),
        SHAPER("shaper"),
        SILENCE("silence"),
        SNOUT("snout"),
        SPIRE("spire"),
        TIDE("tide"),
        VEX("vex"),
        WARD("ward"),
        WAYFINDER("wayfinder"),
        WILD("wild");

        /**
         * The pattern file key ({@code trims/entity/humanoid/{key}}).
         */
        private final @NotNull String key;

    }

}
