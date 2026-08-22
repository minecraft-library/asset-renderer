package lib.minecraft.refharness.mixin;

import net.minecraft.client.animation.AnimationDefinition;
import net.minecraft.client.model.animal.armadillo.ArmadilloModel;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Unrolls every harness-rendered armadillo by forcing the {@link ArmadilloModel} {@code cube} part
 * invisible at model construction, gated on {@code refharness.headless}.
 *
 * <h2>Why this exists</h2>
 * An armadillo has two whole bodies in one mesh - the walking one, and a {@code cube} it curls into
 * - and {@code setupAnim} picks between them from {@code isHidingInShell}. That field is a plain
 * boolean nothing initialises, so a freshly created armadillo is walking, which is what the
 * asset-renderer draws.
 *
 * <p>{@link SkipSetupAnimMixin} cancels {@code setupAnim}, so both bodies keep {@code ModelPart}'s
 * constructed {@code visible = true} and the shell draws over the walking one - closing the gap
 * between the legs and reading as a solid slab. It is not a shading difference or a pose
 * difference: the reference was rendering the other form of the animal, and the two disagreed on
 * 5916 pixels of silhouette that vanilla covered and the Java render did not.
 *
 * <p>Pinned at {@code <init>} RETURN rather than on the render, for the same reason
 * {@link TurtleEggBellyMixin} is: it survives the {@code setupAnim} skip and is measured by the
 * bounds walker, so the family-fit canvas and the render exclude the shell together.
 *
 * <h2>When to remove this mixin</h2>
 * <b>Delete when the asset-renderer poses an armadillo</b> - at that point the curled
 * ({@code isHidingInShell = true}) state becomes ground truth a caller can ask for, and the freeze
 * is what would be standing in its way.
 */
@Mixin(ArmadilloModel.class)
public abstract class ArmadilloShellMixin {

    @Shadow @Final private ModelPart cube;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void refharness$unrollShell(
        ModelPart root, AnimationDefinition rollOut, AnimationDefinition rollUp,
        AnimationDefinition peek, AnimationDefinition rolling, CallbackInfo ci) {

        if (!Boolean.getBoolean("refharness.headless")) return;
        this.cube.visible = false;
    }
}
