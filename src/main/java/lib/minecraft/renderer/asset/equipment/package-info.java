/**
 * The data-driven equipment model parsed from a pack's {@code equipment/*.json} files - the shared
 * texture-resolution surface for worn player-style armor, the elytra, and mob equipment.
 *
 * <p>{@link lib.minecraft.renderer.asset.equipment.EquipmentModel EquipmentModel} mirrors vanilla's
 * {@code EquipmentClientInfo}: a map from {@link lib.minecraft.renderer.asset.equipment.LayerType LayerType}
 * to the ordered {@link lib.minecraft.renderer.asset.equipment.EquipmentModel.Layer Layer} list a slot
 * composites. {@link lib.minecraft.renderer.asset.equipment.EquipmentAssets EquipmentAssets} is the
 * loaded index keyed by asset id, populated by
 * {@link lib.minecraft.renderer.pipeline.pack.EquipmentModelLoader EquipmentModelLoader} and served
 * through {@code RendererContext.resolveEquipmentLayers}. Layer texture stems resolve to
 * {@code entity/equipment/<layer>/<stem>} via
 * {@link lib.minecraft.renderer.asset.equipment.EquipmentModel.Layer#textureLocation(lib.minecraft.renderer.asset.equipment.LayerType) textureLocation}.
 */
package lib.minecraft.renderer.asset.equipment;
