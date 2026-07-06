package lib.minecraft.renderer.option.spec;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

/**
 * Constants for the Minecraft armor trim system: which armor slot the trim applies to, which
 * colour palette recolours the pattern, and which pattern shape is overlaid on the armor. A trim
 * is a {@link Pattern} texture recoloured by a {@link Color} palette (see the {@code TrimKit}
 * paletted-permutation step) and composited over the base {@link ArmorMaterial} layer.
 */
@UtilityClass
public final class ArmorTrim {

    /**
     * The armor slot that determines which trim pattern texture to use.
     * <p>
     * Declaration order is the back-to-front composite order: {@link #LEGGINGS} (vanilla armor
     * layer 2, the innermost layer) is declared first so iterating {@link #values()} paints it
     * before the layer-1 pieces ({@link #HELMET}, {@link #CHESTPLATE}, {@link #BOOTS}). The 2D
     * armor compositor relies on this so the chestplate paints over the leggings waist on the torso
     * and the boots over the leggings on the lower legs.
     */
    @Getter
    @RequiredArgsConstructor
    public enum Slot {

        /** Leggings - armor layer 2, painted first so layer-1 pieces composite over it. */
        LEGGINGS("leggings"),
        /** Helmet - armor layer 1. */
        HELMET("helmet"),
        /** Chestplate - armor layer 1, painted over the leggings waist on the torso. */
        CHESTPLATE("chestplate"),
        /** Boots - armor layer 1, painted over the leggings on the lower legs. */
        BOOTS("boots");

        /**
         * The vanilla slot name ({@code leggings} / {@code helmet} / {@code chestplate} /
         * {@code boots}), matching the item-trim path stem {@code trims/items/{key}_trim}. The 3D
         * and 2D compositors key off the enum constant itself rather than this string.
         */
        private final @NotNull String key;

    }

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
