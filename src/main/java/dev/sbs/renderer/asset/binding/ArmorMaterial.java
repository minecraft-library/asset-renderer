package dev.sbs.renderer.asset.binding;

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

    /** The texture file stem shared by both layer paths. */
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

}
