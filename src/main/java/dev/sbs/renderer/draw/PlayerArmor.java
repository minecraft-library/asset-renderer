package dev.sbs.renderer.draw;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

/**
 * Armor slot used to resolve the trim pattern overlay texture. Each value carries the texture
 * key that maps to the corresponding trim pattern file under
 * {@code trims/items/{key}_trim}.
 */
@Getter
@RequiredArgsConstructor
public enum PlayerArmor {

    HELMET("helmet"),
    CHESTPLATE("chestplate"),
    LEGGINGS("leggings"),
    BOOTS("boots");

    /** The texture key used in trim pattern paths ({@code trims/items/{key}_trim}). */
    private final @NotNull String key;

    /**
     * The vanilla trim materials, each backed by a colour palette in the pack. Use the
     * {@code _DARKER} variants when the trim material matches the armor material (e.g.
     * {@link #IRON_DARKER} for an iron trim on iron armor) so the pattern stays visible.
     */
    @Getter
    @RequiredArgsConstructor
    public enum Trim {

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

}
