package lib.minecraft.renderer.asset.equipment;

import lib.minecraft.renderer.asset.pack.rule.CitType;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The vanilla equipment layer slots that key a {@link EquipmentModel}'s layer map - the render slot
 * whose texture subdir an equipment texture sits under ({@code textures/entity/equipment/<id>/}). The
 * 19 constants and their serialized ids mirror the vanilla {@code EquipmentClientInfo.LayerType} enum
 * exactly, in declaration order, so a parsed model round-trips against the client's own vocabulary.
 *
 * <p>The humanoid subset ({@link #HUMANOID}, {@link #HUMANOID_LEGGINGS}, {@link #HUMANOID_BABY}) drives
 * worn player-style armor; {@link #WINGS} drives the elytra; the remaining body / saddle slots drive
 * mob equipment. A single shared enum gives every consumer - the humanoid armor path, the mob-equipment
 * tooling, and the elytra kit - one validatable slot vocabulary.
 */
@Getter
@RequiredArgsConstructor
public enum LayerType {

    /** Player-style layer 1 armor (helmet, chestplate, arms, boots). */
    HUMANOID("humanoid"),
    /** Player-style layer 2 armor (leggings). */
    HUMANOID_LEGGINGS("humanoid_leggings"),
    /** Player-style layer 1 armor on a baby body. */
    HUMANOID_BABY("humanoid_baby"),
    /** Elytra wings. */
    WINGS("wings"),
    /** Wolf body armor. */
    WOLF_BODY("wolf_body"),
    /** Horse body armor. */
    HORSE_BODY("horse_body"),
    /** Llama decor (carpet / harness). */
    LLAMA_BODY("llama_body"),
    /** Pig saddle. */
    PIG_SADDLE("pig_saddle"),
    /** Strider saddle. */
    STRIDER_SADDLE("strider_saddle"),
    /** Camel saddle. */
    CAMEL_SADDLE("camel_saddle"),
    /** Camel husk saddle. */
    CAMEL_HUSK_SADDLE("camel_husk_saddle"),
    /** Horse saddle. */
    HORSE_SADDLE("horse_saddle"),
    /** Donkey saddle. */
    DONKEY_SADDLE("donkey_saddle"),
    /** Mule saddle. */
    MULE_SADDLE("mule_saddle"),
    /** Zombie horse saddle. */
    ZOMBIE_HORSE_SADDLE("zombie_horse_saddle"),
    /** Skeleton horse saddle. */
    SKELETON_HORSE_SADDLE("skeleton_horse_saddle"),
    /** Happy ghast body harness. */
    HAPPY_GHAST_BODY("happy_ghast_body"),
    /** Nautilus saddle. */
    NAUTILUS_SADDLE("nautilus_saddle"),
    /** Nautilus body armor. */
    NAUTILUS_BODY("nautilus_body");

    private static final @NotNull Map<String, LayerType> BY_ID =
        Arrays.stream(values()).collect(Collectors.toUnmodifiableMap(LayerType::getId, Function.identity()));

    /**
     * The serialized json map key, doubling as the {@code textures/entity/equipment/<id>/} subdir
     * segment.
     */
    private final @NotNull String id;

    /**
     * Resolves the layer type for a serialized id, empty when the id names no known layer.
     *
     * @param id the serialized layer-type id (e.g. {@code humanoid_leggings})
     * @return the matching layer type, or empty for an unknown id
     */
    public static @NotNull Optional<LayerType> fromId(@NotNull String id) {
        return Optional.ofNullable(BY_ID.get(id));
    }

    /**
     * The CIT retexture subject a resource pack addresses this layer through - {@code type=elytra} for
     * the wings and {@code type=armor} for the other eighteen, which is the only split OptiFine's own
     * {@code type=} vocabulary makes across these constants.
     *
     * @return the CIT subject a pack retextures this layer as
     */
    public @NotNull CitType citType() {
        return this == WINGS ? CitType.ELYTRA : CitType.ARMOR;
    }

    /**
     * The path a texture of this layer sits under, joined to a bare stem - so {@code iron} under
     * {@link #HUMANOID} becomes {@code entity/equipment/humanoid/iron}. Namespace-free: the caller
     * supplies that.
     *
     * <p>The convention is total over all 19 constants and it is stated here, where the {@code id} that
     * names the subdir is declared, rather than at whichever consumer happens to be building a path.
     *
     * @param name the bare texture stem name
     * @return the namespace-relative texture path
     */
    public @NotNull String texturePath(@NotNull String name) {
        return "entity/equipment/" + this.id + "/" + name;
    }

}
