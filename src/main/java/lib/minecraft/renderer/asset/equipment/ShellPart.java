package lib.minecraft.renderer.asset.equipment;

import lib.minecraft.renderer.face.Unwrap;
import lib.minecraft.renderer.option.spec.ArmorSlot;
import lib.minecraft.renderer.tensor.Box;
import lib.minecraft.renderer.tensor.Vector3f;
import org.jetbrains.annotations.NotNull;

import java.util.Set;

/**
 * One box of an armour shell, resolved into the shell's own frame when the shell is indexed.
 *
 * <p>A row is one cube, not one bone. Which slots draw it, where its bone sits and how big its bone
 * builds it are all answered here, so a render walks a flat list and asks each row for a box rather
 * than walking a bone hierarchy, resolving a parent chain and re-reading a coverage rule once per
 * equipped slot.
 *
 * <p><b>The row carries operands, not a finished box, and that is arithmetic rather than taste.</b>
 * The upper corner is {@code ((origin - g) + size) + g + g} where {@code g} is the slot's deformation
 * summed with this cube's own and scaled - which does not separate into a box plus an expansion,
 * because float addition does not associate. What can be resolved ahead of the slot is what rides
 * here: the anchored, scaled {@link #origin} and the scaled {@link #size}. The rest is the same three
 * expressions on the same operands in the same order.
 *
 * @param bone the shell bone this cube belongs to, for the per-pixel trace
 * @param unwrap where the cube reads its faces from on the shell's sheet
 * @param slots the slots whose armour draws this cube
 * @param origin the cube's lower corner in shell space, its bone's anchor plus its own scaled origin
 * @param size the cube's extent, scaled by its bone
 * @param grow the cube's own deformation, which a slot's is summed onto
 * @param scale the bone's uniform scale, which the summed deformation is taken through
 */
public record ShellPart(
    @NotNull String bone,
    @NotNull Unwrap.Atlas unwrap,
    @NotNull Set<ArmorSlot> slots,
    @NotNull Vector3f origin,
    @NotNull Vector3f size,
    @NotNull Vector3f grow,
    float scale
) {

    /**
     * Whether a slot's armour draws this cube.
     *
     * @param slot the armour slot
     * @return {@code true} when that slot's armour draws it
     */
    public boolean coveredBy(@NotNull ArmorSlot slot) {
        return this.slots.contains(slot);
    }

    /**
     * This cube grown by the deformation one slot wears it at, in shell space.
     *
     * <p>The slot picks which of the shell's two deformations applies, that is summed with the cube's
     * own the way vanilla's mesh builder sums them, and the bone's scale is taken through the sum
     * rather than through the assembled corners - so a shell registered untransformed multiplies by
     * one and cannot round.
     *
     * @param slot the armour slot
     * @param shell the shell this row belongs to, which carries the two deformations
     * @return the grown box
     */
    public @NotNull Box boxFor(@NotNull ArmorSlot slot, @NotNull Shell shell) {
        Vector3f deformation = shell.grow(slot).add(this.grow).multiply(this.scale);
        Vector3f min = this.origin.subtract(deformation);
        Vector3f max = min.add(this.size).add(deformation).add(deformation);
        return new Box(min.x(), min.y(), min.z(), max.x(), max.y(), max.z());
    }

}
