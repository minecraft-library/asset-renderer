/**
 * What a wearer is dressed in - the equipment model a pack declares and the worn shell an entity
 * carries. A member no entity wears does not belong here.
 *
 * <p>{@link lib.minecraft.renderer.asset.equipment.EquipmentModel EquipmentModel} is the decoded
 * {@code equipment/*.json} surface for worn player-style armor, the elytra, and mob equipment. It
 * mirrors vanilla's {@code EquipmentClientInfo}: a map from
 * {@link lib.minecraft.renderer.vanilla.equipment.LayerType LayerType} to the ordered
 * {@link lib.minecraft.renderer.asset.equipment.EquipmentModel.Layer Layer} list a slot composites. The
 * loaded index keyed by asset id is this module's own - populated by
 * {@link lib.minecraft.renderer.content.pack.EquipmentModelLoader EquipmentModelLoader} and served
 * through {@code RendererContext.resolveEquipmentLayers}, which is where an unresolvable id becomes
 * {@link lib.minecraft.renderer.asset.equipment.EquipmentModel#MISSING MISSING}. Layer texture stems resolve to
 * {@code entity/equipment/<layer>/<stem>} via
 * {@link lib.minecraft.renderer.asset.equipment.EquipmentModel.Layer#textureLocation(lib.minecraft.renderer.vanilla.equipment.LayerType) textureLocation}.
 *
 * <p>{@link lib.minecraft.renderer.asset.equipment.Shell Shell} is the worn mesh - the boxes a wearer's
 * armour is drawn on, joined from the entity's armour row and the geometry store, and held by the
 * {@link lib.minecraft.renderer.asset.Entity Entity} it dresses. It is applied to an entity rather than
 * being a subject of its own, which is why it sits with the equipment model rather than beside
 * {@code Entity}.
 *
 * <p>The vocabulary that names what is worn -
 * {@link lib.minecraft.renderer.vanilla.equipment.ArmorSlot ArmorSlot},
 * {@link lib.minecraft.renderer.vanilla.equipment.ArmorForm ArmorForm}, {@code LayerType} and the
 * {@link lib.minecraft.renderer.vanilla.equipment.ArmorMaterial ArmorMaterial} whose key names an
 * equipment model - is vanilla's rather than the pack's and lives in
 * {@link lib.minecraft.renderer.vanilla.equipment vanilla.equipment}.
 */
package lib.minecraft.renderer.asset.equipment;
