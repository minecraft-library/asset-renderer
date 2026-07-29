package lib.minecraft.renderer.asset.equipment;

import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.option.AppearanceGate;
import lib.minecraft.renderer.option.EntityAppearance;
import lib.minecraft.renderer.option.spec.ArmorSlot;
import lib.minecraft.renderer.tensor.Vector3f;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * One worn armour shell - the boxes a wearer is dressed in, plus everything that dresses it in them
 * asks about.
 *
 * <p>Vanilla never derives worn armour from the wearer's own model. It builds a handful of armour sets
 * and hands each renderer the one its subject wears, so a skeleton's narrow limbs and a giant's
 * scaled-up body dress in the very same boxes. Each set fans one base mesh out over the four equipment
 * slots at two deformations, which is the pair carried here: the leggings wear the inner one, the other
 * three slots the outer. The mesh itself is stored ungrown, so a cube's own
 * {@code CubeDeformation.extend} (a leg's {@code -0.1}, a helmet's second box) rides its {@code grow}
 * and is summed onto the slot's deformation at render.
 *
 * <p>Three of the sets are registered through a whole-mesh {@code MeshTransformer.scaling} - giant
 * {@code 6.0}, husk {@code 1.0625}, wither skeleton {@code 1.2} - and that factor is {@link #meshScale}.
 * It rides the shell rather than the mesh so the sets that differ only by it still share one geometry
 * entry, and it is what sizes and seats the shell in step with the body it dresses.
 *
 * <p>A wearer vanilla hands a second armour set is dressed in a shell of its own rather than in a
 * smaller copy of this one - its own mesh, its own two deformations, sometimes its own sheet - and that
 * shell rides {@link #alternate} together with the appearance selection that reaches it. Seven wearers
 * carry one; the rest answer {@link #forAppearance} with themselves, which is vanilla's own way of
 * saying a wearer has only the one shell. The selection is carried rather than assumed because vanilla
 * reaches both kinds through one flag and this pipeline through two axes: six wearers swap on
 * {@code age}, and the armour stand on {@code size}, whose {@code isBaby} is literally {@code isSmall}.
 *
 * <p>Everything that varies by <em>shell</em> is answered here and everything that varies by
 * <em>slot</em> is answered by {@link ArmorSlot}, which is why {@link #grow} reads one off the other
 * rather than either carrying both.
 *
 * <p>Two bones in the corpus are shipped oddities rather than defects to normalise, and both belong to
 * vanilla rather than to this pipeline. {@code inner_body} ships on both baby shells, is named by no
 * slot and is not a descendant of one, so no slot's armour ever draws it - and its cube is
 * byte-identical to {@code body}'s. And a baby shell's feet are <b>cross-parented</b>:
 * {@code right_foot} hangs off {@code left_leg} and {@code left_foot} off {@code right_leg}, which is
 * what vanilla's own {@code createBabyArmorMesh} bytecode builds. Pairing the sides up would be editing
 * shipped data, not fixing a bug, so {@link #walk} follows the crossed edge and each baby foot takes
 * the opposite leg's pivot.
 *
 * @param mesh the ungrown armour mesh, joined from the geometry store
 * @param innerGrow the per-side growth the leggings layer applies
 * @param outerGrow the per-side growth the helmet / chestplate / boots layer applies
 * @param meshScale the whole-mesh uniform scale the set is registered through, {@code 1} for the eleven
 *     wearers registered unscaled
 * @param form which of the two shells this is - what says which parts each slot covers, which equipment
 *     layer it draws through, and whether it is trimmed
 * @param alternate the shell this wearer's other form is dressed in, empty when it has none
 * @param walk what a walk of this shell resolves to - which bones each slot draws, and where each bone
 *     sits - answered once here rather than once per render in each of the two consumers
 */
public record Shell(
    @NotNull EntityModelData mesh,
    @NotNull Vector3f innerGrow,
    @NotNull Vector3f outerGrow,
    float meshScale,
    @NotNull ArmorForm form,
    @NotNull Optional<Alternate> alternate,
    @NotNull ShellWalk walk
) {

    /**
     * Constructs a shell, resolving its {@link #walk} from the mesh and the form it is built from - the
     * only entry point, so the two cannot disagree.
     *
     * @param mesh the ungrown armour mesh, joined from the geometry store
     * @param innerGrow the per-side growth the leggings layer applies
     * @param outerGrow the per-side growth the helmet / chestplate / boots layer applies
     * @param meshScale the whole-mesh uniform scale the set is registered through
     * @param form which of the two shells this is
     * @param alternate the shell this wearer's other form is dressed in, empty when it has none
     */
    public Shell(
        @NotNull EntityModelData mesh,
        @NotNull Vector3f innerGrow,
        @NotNull Vector3f outerGrow,
        float meshScale,
        @NotNull ArmorForm form,
        @NotNull Optional<Alternate> alternate
    ) {
        this(mesh, innerGrow, outerGrow, meshScale, form, alternate,
            ShellWalk.of(mesh, form, innerGrow, outerGrow));
    }

    /**
     * The entity feet anchor a whole-mesh scale is taken about, in model units - vanilla's
     * {@code MeshTransformer.scaling} expands to
     * {@code pose.scaled(F).translated(0, 24.016 * (1 - F), 0)}, and {@code 24.016} is {@code 1.501}
     * blocks at 16 units a block, the living-entity render chain's own {@code translate(0, -1.501, 0)}.
     */
    private static final float FEET_ANCHOR = 24.016f;

    /**
     * The offset the shell is seated at - the translate vanilla's whole-mesh transformer pairs with the
     * scale, so that scaling happens about the wearer's feet rather than its origin.
     *
     * <p>Derived rather than carried: the same expression in the same precision the tooling baked the
     * wearer's own bone pivots with, so the two agree bit for bit. A transcribed literal would not.
     *
     * @return the whole-mesh offset, zero at the identity scale
     */
    public @NotNull Vector3f meshOffset() {
        return new Vector3f(0f, FEET_ANCHOR * (1f - this.meshScale), 0f);
    }

    /**
     * The equipment layer a slot's armour texture is composited from.
     *
     * @param slot the armour slot
     * @return the layer the slot draws through on this shell
     */
    public @NotNull LayerType sheet(@NotNull ArmorSlot slot) {
        return this.form.layerType(slot);
    }

    /**
     * The {@code trims/entity/} atlas a slot's trim is permuted from, empty when this shell is never
     * trimmed.
     *
     * @param slot the armour slot
     * @return the trim atlas name, or empty when this shell draws no trim
     */
    public @NotNull Optional<String> trimLayer(@NotNull ArmorSlot slot) {
        return this.form.trimLayer(slot);
    }

    /**
     * Which of this shell's two deformations a slot wears. The choice is the slot's - vanilla registers
     * an armour set with exactly two - so the slot is asked and answers with one of the pair.
     *
     * @param slot the armour slot
     * @return the per-side growth that slot applies to this shell's cubes
     */
    public @NotNull Vector3f grow(@NotNull ArmorSlot slot) {
        return slot.grow(this.innerGrow, this.outerGrow);
    }

    /**
     * The shell this wearer is dressed in for a given appearance - its second one when the appearance
     * selects it, else this one.
     *
     * @param appearance the render-axis selections
     * @return the shell to dress the wearer in
     */
    public @NotNull Shell forAppearance(@NotNull EntityAppearance appearance) {
        return this.alternate
            .filter(shell -> shell.when().test(appearance))
            .map(Alternate::shell)
            .orElse(this);
    }

    /**
     * The second shell a wearer is dressed in, and the appearance selection that reaches it.
     *
     * @param when the selection that swaps to this shell
     * @param shell the shell itself
     */
    public record Alternate(@NotNull AppearanceGate when, @NotNull Shell shell) {}

}
