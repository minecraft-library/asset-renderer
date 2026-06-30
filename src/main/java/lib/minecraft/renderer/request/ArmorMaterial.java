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

    LEATHER("leather"),
    CHAINMAIL("chainmail"),
    IRON("iron"),
    GOLDEN("gold"),
    DIAMOND("diamond"),
    COPPER("copper"),
    NETHERITE("netherite"),
    TURTLE_SCUTE("turtle_scute");

    /**
     * The texture file stem shared by both layer paths.
     */
    private final @NotNull String key;

    /**
     * The namespaced texture id for the humanoid layer 1 atlas (helmet, chestplate, arms, boots).
     */
    public @NotNull String humanoidTextureId() {
        return "minecraft:entity/equipment/humanoid/" + this.key;
    }

    /**
     * The namespaced texture id for the humanoid leggings layer 2 atlas.
     */
    public @NotNull String leggingsTextureId() {
        return "minecraft:entity/equipment/humanoid_leggings/" + this.key;
    }

    /**
     * The namespaced texture id for the layer 1 undyed overlay (vanilla {@code leather_overlay}),
     * composited untinted on top of the dye-tinted base. Only {@link #LEATHER} ships this texture.
     */
    public @NotNull String humanoidOverlayTextureId() {
        return humanoidTextureId() + "_overlay";
    }

    /**
     * The namespaced texture id for the layer 2 leggings undyed overlay, composited untinted on top
     * of the dye-tinted base. Only {@link #LEATHER} ships this texture.
     */
    public @NotNull String leggingsOverlayTextureId() {
        return leggingsTextureId() + "_overlay";
    }

    /**
     * Whether this material is dye-tinted (a grayscale base recoloured by an ARGB dye plus an
     * undyed overlay). Vanilla dyes leather only.
     */
    public boolean dyeable() {
        return this == LEATHER;
    }

}
