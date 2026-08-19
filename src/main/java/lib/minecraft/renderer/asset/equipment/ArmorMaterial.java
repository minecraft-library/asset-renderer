package lib.minecraft.renderer.asset.equipment;

import dev.simplified.annotations.Getter;
import dev.simplified.annotations.RequiredArgsConstructor;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.parity.Parity;
import org.jetbrains.annotations.NotNull;

/**
 * The vanilla armor materials. Each names an equipment asset ({@link #assetId()}) whose
 * {@code equipment/*.json} model supplies the per-layer texture paths, dye tint, and overlay; the
 * enum carries only the stable material key.
 */
@Parity(claim = "option-surface")
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
     * The equipment-asset id (the {@code equipment/*.json} filename stem, equal to the texture stem)
     * feeding {@code RendererContext.resolveEquipmentLayers} - {@code minecraft:iron},
     * {@code minecraft:leather}, {@code minecraft:gold}. The equipment model supplies the texture
     * paths, dye, and overlay per layer, so the material carries only this key.
     *
     * @return the equipment-asset id in the {@code minecraft} namespace
     */
    public @NotNull ResourceId assetId() {
        return new ResourceId(ResourceId.DEFAULT_NAMESPACE, this.key);
    }

}
