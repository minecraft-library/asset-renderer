/**
 * The equipment model a pack declares - the decoded {@code equipment/*.json} surface for worn
 * player-style armor, the elytra, and mob equipment, plus the shell of boxes a wearer is dressed in.
 *
 * <p>{@link lib.minecraft.renderer.asset.equipment.EquipmentModel EquipmentModel} mirrors vanilla's
 * {@code EquipmentClientInfo}: a map from {@link lib.minecraft.renderer.vanilla.equipment.LayerType LayerType}
 * to the ordered {@link lib.minecraft.renderer.asset.equipment.EquipmentModel.Layer Layer} list a slot
 * composites. The loaded index keyed by asset id is this module's own - populated by
 * {@link lib.minecraft.renderer.content.pack.EquipmentModelLoader EquipmentModelLoader} and served
 * through {@code RendererContext.resolveEquipmentLayers}, which is where an unresolvable id becomes
 * {@link lib.minecraft.renderer.asset.equipment.EquipmentModel#MISSING MISSING}. Layer texture stems resolve to
 * {@code entity/equipment/<layer>/<stem>} via
 * {@link lib.minecraft.renderer.asset.equipment.EquipmentModel.Layer#textureLocation(lib.minecraft.renderer.vanilla.equipment.LayerType) textureLocation}.
 *
 * <p>{@link lib.minecraft.renderer.asset.equipment.ArmorMaterial ArmorMaterial} is the key that names
 * one of those assets, and {@link lib.minecraft.renderer.asset.equipment.Shell Shell} is the geometry
 * the walk dresses a wearer in. They sit here rather than with the {@code ArmorOptions} bag that holds
 * them, because the walk, the screen-bounds pass and the compositor all read them and none of the
 * three is an option. The vocabulary that names what is worn -
 * {@link lib.minecraft.renderer.vanilla.equipment.ArmorSlot ArmorSlot},
 * {@link lib.minecraft.renderer.vanilla.equipment.ArmorForm ArmorForm} and {@code LayerType} - is
 * vanilla's rather than the pack's and lives in
 * {@link lib.minecraft.renderer.vanilla.equipment vanilla.equipment}.
 */
package lib.minecraft.renderer.asset.equipment;
