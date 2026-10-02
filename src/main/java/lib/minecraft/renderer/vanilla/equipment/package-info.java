/**
 * The vanilla vocabulary that names what a wearer is dressed in - the slot, the shape, the render
 * layer and the armour material. None of the four is parsed from a pack; they are the client's own
 * fixed rosters, which is why the model a pack declares is found and keyed by them rather than
 * declaring its own.
 *
 * <p>{@link lib.minecraft.renderer.vanilla.equipment.ArmorSlot ArmorSlot} is the back-to-front
 * composite order every armour walk iterates, and it answers which of a pair of per-layer values a
 * slot wears. {@link lib.minecraft.renderer.vanilla.equipment.ArmorForm ArmorForm} is the adult /
 * baby shape: it holds the per-slot part table that differs between them, and answers the equipment
 * layer and trim atlas each slot draws through.
 * {@link lib.minecraft.renderer.vanilla.equipment.LayerType LayerType} mirrors vanilla's
 * {@code EquipmentClientInfo.LayerType} constant for constant, and is the key an
 * {@link lib.minecraft.renderer.asset.equipment.EquipmentModel EquipmentModel} maps its texture
 * layers under. {@link lib.minecraft.renderer.vanilla.equipment.ArmorMaterial ArmorMaterial} is the
 * roster of vanilla's armour materials, each carrying only the key of the equipment asset it names -
 * the model that key resolves to supplies the textures, dye and overlay.
 *
 * <p>The calibration a slot's boxes are grown by is not vocabulary and is not here: it is the skin
 * composite's own figure, held on
 * {@link lib.minecraft.renderer.bake.armor.ArmorInflate ArmorInflate}.
 *
 * <p><b>Parity.</b> An equipment model keys its layers by these rosters and the armour walk iterates
 * them, so a change here reaches both what the pipeline loads - which the dump serialises - and what
 * an armour render draws. The package declares the asset layer's claim, the one the equipment records
 * keyed by these declare. The slot and the material are also named by the armour a caller requests,
 * so each declares the option surface's claim as well: it reaches what that request reaches, and the
 * union of two select claims is what answers for it.
 */
@Parity(claim = "asset-layer")
package lib.minecraft.renderer.vanilla.equipment;

import lib.minecraft.renderer.parity.Parity;
