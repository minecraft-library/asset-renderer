/**
 * The data-driven equipment model parsed from a pack's {@code equipment/*.json} files - the shared
 * texture-resolution surface for worn player-style armor, the elytra, and mob equipment - together
 * with the vanilla vocabulary that names what is worn.
 *
 * <p>{@link lib.minecraft.renderer.asset.equipment.ArmorSlot ArmorSlot} is the back-to-front
 * composite order every walk here iterates, and
 * {@link lib.minecraft.renderer.asset.equipment.ArmorPiece ArmorPiece} is what a caller equips in
 * one - a {@link lib.minecraft.renderer.asset.equipment.ArmorMaterial ArmorMaterial} plus an
 * optional {@link lib.minecraft.renderer.asset.equipment.ArmorTrim ArmorTrim}, dye and glint. They
 * sit beside the {@link lib.minecraft.renderer.asset.equipment.Shell Shell} the geometry walk
 * builds rather than with the {@code ArmorOptions} bag that holds them, because the walk, the
 * screen-bounds pass and the compositor all read the slot and only one of the three is an option.
 *
 * <p>{@link lib.minecraft.renderer.asset.equipment.EquipmentModel EquipmentModel} mirrors vanilla's
 * {@code EquipmentClientInfo}: a map from {@link lib.minecraft.renderer.asset.equipment.LayerType LayerType}
 * to the ordered {@link lib.minecraft.renderer.asset.equipment.EquipmentModel.Layer Layer} list a slot
 * composites. The loaded index keyed by asset id is the pipeline's own - populated by
 * {@link lib.minecraft.renderer.pipeline.pack.EquipmentModelLoader EquipmentModelLoader} and served
 * through {@code RendererContext.resolveEquipmentLayers}, which is where an unresolvable id becomes
 * {@link lib.minecraft.renderer.asset.equipment.EquipmentModel#MISSING MISSING}. Layer texture stems resolve to
 * {@code entity/equipment/<layer>/<stem>} via
 * {@link lib.minecraft.renderer.asset.equipment.EquipmentModel.Layer#textureLocation(lib.minecraft.renderer.asset.equipment.LayerType) textureLocation}.
 */
package lib.minecraft.renderer.asset.equipment;
