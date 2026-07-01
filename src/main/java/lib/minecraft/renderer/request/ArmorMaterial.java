package lib.minecraft.renderer.request;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

/**
 * The vanilla armor materials, each backed by a 64x32 texture atlas in the pack under
 * {@code entity/equipment/humanoid/} (helmet, chestplate, arms, boots) and
 * {@code entity/equipment/humanoid_leggings/} (leggings).
 */
@Getter
@RequiredArgsConstructor
public enum ArmorMaterial {

    /** Leather - the only dye-tinted material (grayscale base plus an undyed overlay). */
    LEATHER("leather"),
    /** Chainmail. */
    CHAINMAIL("chainmail"),
    /** Iron. */
    IRON("iron"),
    /** Gold - texture stem {@code gold} rather than the constant name {@code golden}. */
    GOLDEN("gold"),
    /** Diamond. */
    DIAMOND("diamond"),
    /** Copper. */
    COPPER("copper"),
    /** Netherite. */
    NETHERITE("netherite"),
    /** Turtle scute (the turtle-shell helmet). */
    TURTLE_SCUTE("turtle_scute");

    /**
     * The texture file stem shared by both layer paths.
     */
    private final @NotNull String key;

    /**
     * Resolves the namespaced texture id for the humanoid layer 1 atlas (helmet, chestplate, arms,
     * boots).
     *
     * @return the layer 1 texture id under {@code entity/equipment/humanoid/}
     */
    public @NotNull String humanoidTextureId() {
        return "minecraft:entity/equipment/humanoid/" + this.key;
    }

    /**
     * Resolves the namespaced texture id for the humanoid leggings layer 2 atlas.
     *
     * @return the layer 2 texture id under {@code entity/equipment/humanoid_leggings/}
     */
    public @NotNull String leggingsTextureId() {
        return "minecraft:entity/equipment/humanoid_leggings/" + this.key;
    }

    /**
     * Resolves the namespaced texture id for the layer 1 undyed overlay (vanilla
     * {@code leather_overlay}), composited untinted on top of the dye-tinted base. Only
     * {@link #LEATHER} ships this texture.
     *
     * @return the layer 1 overlay texture id ({@link #humanoidTextureId()} with an
     *     {@code _overlay} suffix)
     */
    public @NotNull String humanoidOverlayTextureId() {
        return humanoidTextureId() + "_overlay";
    }

    /**
     * Resolves the namespaced texture id for the layer 2 leggings undyed overlay, composited
     * untinted on top of the dye-tinted base. Only {@link #LEATHER} ships this texture.
     *
     * @return the layer 2 overlay texture id ({@link #leggingsTextureId()} with an
     *     {@code _overlay} suffix)
     */
    public @NotNull String leggingsOverlayTextureId() {
        return leggingsTextureId() + "_overlay";
    }

    /**
     * Reports whether this material is dye-tinted (a grayscale base recoloured by an ARGB dye plus
     * an undyed overlay). Vanilla dyes leather only.
     *
     * @return {@code true} if this material accepts a dye tint - only {@link #LEATHER}
     */
    public boolean dyeable() {
        return this == LEATHER;
    }

}
