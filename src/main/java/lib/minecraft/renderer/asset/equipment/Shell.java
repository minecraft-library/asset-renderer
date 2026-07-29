package lib.minecraft.renderer.asset.equipment;

import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.option.spec.ArmorSlot;
import lib.minecraft.renderer.tensor.Vector3f;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * One worn armour shell, as everything that dresses a wearer in it asks about it.
 *
 * <p>Vanilla never derives worn armour from the wearer's own mesh - it builds a handful of armour sets
 * and hands each renderer the one its subject wears, so a skeleton's narrow limbs and a giant's
 * scaled-up body dress in the very same boxes. A shell is one of those sets: the boxes, the two
 * deformations its four slots wear between them, the whole-mesh scale it is registered through, and the
 * sheet each slot composites from.
 *
 * <p>Everything that varies by <em>shell</em> is answered here and everything that varies by
 * <em>slot</em> is answered by {@link ArmorSlot}, which is why {@link #grow} reads one off the other
 * rather than either carrying both. What a walk of the shell resolves to - which bones a slot draws,
 * and where each bone sits - is {@link #walk}, answered once when the shell is indexed rather than once
 * per render in each of its two consumers.
 */
public interface Shell {

    /**
     * The shell's ungrown mesh. Each cube carries only its own deformation, so a slot's is summed onto
     * it at render in the order vanilla's mesh builder adds them.
     *
     * @return the mesh the shell's boxes come from
     */
    @NotNull EntityModelData mesh();

    /**
     * The per-side growth the innermost armour layer applies - vanilla's layer 2, which the leggings
     * alone wear.
     *
     * @return the inner layer's deformation
     */
    @NotNull Vector3f innerGrow();

    /**
     * The per-side growth the outer armour layer applies - vanilla's layer 1, worn by the helmet, the
     * chestplate and the boots.
     *
     * @return the outer layer's deformation
     */
    @NotNull Vector3f outerGrow();

    /**
     * The whole-mesh uniform scale the set is registered through, {@code 1} for a set registered
     * untransformed.
     *
     * @return the shell's own scale factor
     */
    float meshScale();

    /**
     * The offset the shell is seated at, so that scaling happens about the wearer's feet rather than
     * its origin. Zero at the identity scale.
     *
     * @return the whole-mesh offset
     */
    @NotNull Vector3f meshOffset();

    /**
     * What a walk of this shell resolves to.
     *
     * @return the shell's resolved coverage sets and bone anchors
     */
    @NotNull ShellWalk walk();

    /**
     * The equipment layer a slot's armour texture is composited from.
     *
     * @param slot the armour slot
     * @return the layer the slot draws through on this shell
     */
    @NotNull LayerType sheet(@NotNull ArmorSlot slot);

    /**
     * The {@code trims/entity/} atlas a slot's trim is permuted from, empty when this shell is never
     * trimmed.
     *
     * @param slot the armour slot
     * @return the trim atlas name, or empty when this shell draws no trim
     */
    @NotNull Optional<String> trimLayer(@NotNull ArmorSlot slot);

    /**
     * Which of this shell's two deformations a slot wears. The choice is the slot's - vanilla registers
     * an armour set with exactly two - so the slot is asked and answers with one of the pair.
     *
     * @param slot the armour slot
     * @return the per-side growth that slot applies to this shell's cubes
     */
    default @NotNull Vector3f grow(@NotNull ArmorSlot slot) {
        return slot.grow(this);
    }

}
