/**
 * The vanilla vocabulary that names what a wearer is dressed in - the slot, the shape and the render
 * layer. None of the three is parsed from a pack; they are the client's own fixed rosters, which is
 * why the model a pack declares reads them rather than declaring its own.
 *
 * <p>{@link lib.minecraft.renderer.vanilla.equipment.ArmorSlot ArmorSlot} is the back-to-front
 * composite order every armour walk iterates, and it answers which of a pair of per-layer values a
 * slot wears. {@link lib.minecraft.renderer.vanilla.equipment.ArmorForm ArmorForm} is the adult /
 * baby shape and carries the per-slot part table, base mesh and equipment layer that differ between
 * them. {@link lib.minecraft.renderer.vanilla.equipment.LayerType LayerType} mirrors vanilla's
 * {@code EquipmentClientInfo.LayerType} constant for constant, and is the key an
 * {@link lib.minecraft.renderer.asset.equipment.EquipmentModel EquipmentModel} maps its texture
 * layers under.
 *
 * <p>The calibration a slot's boxes are grown by is not vocabulary and is not here: it is the skin
 * composite's own figure, held on
 * {@link lib.minecraft.renderer.bake.armor.ArmorInflate ArmorInflate}.
 *
 * <p><b>Parity.</b> An equipment model keys its layers by these rosters and the armour walk iterates
 * them, so a change here reaches both what the pipeline loads - which the dump serialises - and what
 * an armour render draws. The package declares the asset layer's claim, the one the equipment records
 * keyed by these declare.
 */
@Parity(claim = "asset-layer")
package lib.minecraft.renderer.vanilla.equipment;

import lib.minecraft.renderer.parity.Parity;
