package lib.minecraft.refharness.mixin;

import net.minecraft.client.model.animal.equine.EquineSaddleModel;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Unstraps every harness-rendered equine saddle by forcing the {@link EquineSaddleModel}
 * {@code left_saddle_line} / {@code right_saddle_line} parts invisible at model construction,
 * gated on {@code refharness.headless}.
 *
 * <h2>Why this exists</h2>
 * The saddle lines are the reins a rider holds, and {@code setupAnim} writes
 * {@code part.visible = state.isRidden} over the {@code ridingParts} array holding both. Nothing
 * rides a transient equine, so a normally-rendered vanilla saddle draws its strap and no lines -
 * which is what the asset-renderer draws.
 *
 * <p>{@link SkipSetupAnimMixin} cancels {@code setupAnim}, so both parts keep {@link ModelPart}'s
 * constructed {@code visible = true} and the reference ships every saddled equine with reins
 * running to a rider who is not there.
 *
 * <h2>Per-variant scope</h2>
 * One class serves five subjects: horse, skeleton_horse and zombie_horse bake their saddle through
 * {@code EquineSaddleModel}'s own factory, while donkey and mule bake theirs through
 * {@code DonkeyModel.createSaddleLayer} and are still handed this class to pose it. So the model
 * the layer holds is what says which bones toggle, and the factory that baked the mesh does not.
 *
 * <p>Pinned at {@code <init>} RETURN rather than on the render, for the same reason
 * {@link TurtleEggBellyMixin} is: it survives the {@code setupAnim} skip and is measured by the
 * bounds walker, so the family-fit canvas and the render drop the lines together.
 *
 * <h2>When to remove this mixin</h2>
 * <b>Delete when the asset-renderer poses an equine</b> - at that point the ridden
 * ({@code isRidden = true}) state becomes ground truth a caller can ask for, and the freeze is
 * what would be standing in its way.
 *
 * @see CamelSaddleReinsMixin the same strap on the camel saddle, which holds one in a field rather
 *     than a pair in an array
 */
@Mixin(EquineSaddleModel.class)
public abstract class EquineSaddleLinesMixin {

    @Shadow @Final private ModelPart[] ridingParts;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void refharness$unstrapSaddleLines(ModelPart root, CallbackInfo ci) {
        if (!Boolean.getBoolean("refharness.headless")) return;
        for (ModelPart part : this.ridingParts) part.visible = false;
    }
}
