package dev.sbs.renderer.draw;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

/**
 * Constants for the Minecraft armor trim system: which armor slot the trim applies to, which
 * colour palette recolours the pattern, and which pattern shape is used for 3D entity rendering.
 */
public final class ArmorTrim {

    private ArmorTrim() {
    }

    /** The armor slot that determines which trim pattern texture to use. */
    @Getter
    @RequiredArgsConstructor
    public enum Slot {

        HELMET("helmet"),
        CHESTPLATE("chestplate"),
        LEGGINGS("leggings"),
        BOOTS("boots");

        /** The texture key used in trim pattern paths ({@code trims/items/{key}_trim}). */
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

        /** The palette file key ({@code trims/color_palettes/{key}}). */
        private final @NotNull String key;

    }

    /**
     * The trim pattern shape used for 3D entity armor rendering. Each pattern maps to a
     * grayscale texture at {@code trims/entity/humanoid/{key}.png} (layer 1) and
     * {@code trims/entity/humanoid_leggings/{key}.png} (layer 2).
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

        /** The pattern file key ({@code trims/entity/humanoid/{key}}). */
        private final @NotNull String key;

    }

}
