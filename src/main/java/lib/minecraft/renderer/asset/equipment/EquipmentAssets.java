package lib.minecraft.renderer.asset.equipment;

import lib.minecraft.renderer.asset.ResourceId;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

/**
 * The loaded equipment-asset index: every {@code equipment/*.json} the pack stack ships, keyed by its
 * asset id (the json filename stem as a {@link ResourceId}). Mirrors vanilla's
 * {@code EquipmentAssetManager} result; an unresolvable asset id yields {@link EquipmentModel#MISSING}
 * rather than a throw.
 *
 * @param models the equipment model per asset id
 */
public record EquipmentAssets(@NotNull Map<ResourceId, EquipmentModel> models) {

    /** The empty index - no equipment assets, every lookup {@link EquipmentModel#MISSING} (stub contexts). */
    public static final @NotNull EquipmentAssets EMPTY = new EquipmentAssets(Map.of());

    /**
     * Resolves the equipment model for an asset id, {@link EquipmentModel#MISSING} when the stack
     * ships no such asset.
     *
     * @param assetId the equipment asset id (e.g. {@code minecraft:iron})
     * @return the model, or {@link EquipmentModel#MISSING}
     */
    public @NotNull EquipmentModel get(@NotNull ResourceId assetId) {
        return this.models.getOrDefault(assetId, EquipmentModel.MISSING);
    }

}
