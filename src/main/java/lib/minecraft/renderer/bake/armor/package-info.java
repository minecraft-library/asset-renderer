/**
 * Emitting the layers worn over a subject - the armour, equipment and wings a slot dresses a wearer
 * in, as triangles or as a flat composite.
 *
 * <p>Armour has two wearers and one walk.
 * {@link lib.minecraft.renderer.bake.armor.EntityArmorKit EntityArmorKit} dresses an entity in the
 * {@link lib.minecraft.renderer.asset.equipment.Shell Shell} it wears and measures what that shell adds
 * to the canvas; {@link lib.minecraft.renderer.bake.armor.PlayerArmorKit PlayerArmorKit} holds no shell
 * and dresses a player over its own body boxes. What the two share - the walk from rows to textured
 * triangles and the sheet resolution under it - is
 * {@link lib.minecraft.renderer.bake.armor.ArmorKit ArmorKit}.
 *
 * <p>The rows that walk reads are resolved before it starts.
 * {@link lib.minecraft.renderer.bake.armor.WornBox WornBox} is one box a slot's armour draws - which
 * slots draw it, the box it occupies once a slot grows it, and where it reads its faces from.
 * {@link lib.minecraft.renderer.bake.armor.ShellIndex ShellIndex} is a shell's walk, resolved once per
 * armour build: a row per cube and the bones each slot covers.
 * {@link lib.minecraft.renderer.bake.armor.ArmorInflate ArmorInflate} is the per-slot inflation a
 * player's worn box sits at in the skin renderer's frame.
 *
 * <p>{@link lib.minecraft.renderer.bake.armor.EquipmentKit EquipmentKit} composites an equipment asset's
 * layers into the one buffer a worn piece is textured with, for humanoid armour and mob equipment
 * alike. {@link lib.minecraft.renderer.bake.armor.ElytraKit ElytraKit} seats the elytra wings on the
 * body they hang from and textures them off the equipment model's wings layer.
 *
 * <p>The flat player is drawn here, and every worn slot reaches it through its 2D armour pass.
 * {@link lib.minecraft.renderer.bake.armor.PlayerSprite PlayerSprite} composites the armour of each
 * equipped slot front-on over the skin and its overlay, and
 * {@link lib.minecraft.renderer.bake.armor.PlayerLayout2D PlayerLayout2D} places the parts that
 * composite iterates, one canvas rectangle for each part a body scope draws.
 *
 * <p>A type no worn slot reaches does not belong here.
 *
 * <p><b>Parity.</b> Every member declares its own claims; the package declares none.
 */
package lib.minecraft.renderer.bake.armor;
