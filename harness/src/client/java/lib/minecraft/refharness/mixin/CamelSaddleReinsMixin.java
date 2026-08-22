package lib.minecraft.refharness.mixin;

import net.minecraft.client.model.animal.camel.CamelSaddleModel;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Unstraps every harness-rendered camel saddle by forcing the {@link CamelSaddleModel}
 * {@code reins} part invisible at model construction, gated on {@code refharness.headless}.
 *
 * <h2>Why this exists</h2>
 * A camel's reins are the strap a rider holds, and {@code setupAnim} writes
 * {@code reins.visible = state.isRidden}. Nothing rides a transient camel, so a normally-rendered
 * vanilla saddle draws the bridle and no reins - which is what the asset-renderer draws.
 *
 * <p>{@link SkipSetupAnimMixin} cancels {@code setupAnim}, so the part keeps {@link ModelPart}'s
 * constructed {@code visible = true} and the reference ships a saddle with its reins looped down
 * the neck of a camel nobody is riding.
 *
 * <p>Pinned at {@code <init>} RETURN rather than on the render, for the same reason
 * {@link TurtleEggBellyMixin} is: it survives the {@code setupAnim} skip and is measured by the
 * bounds walker, so the family-fit canvas and the render drop the strap together.
 *
 * <h2>When to remove this mixin</h2>
 * <b>Delete when the asset-renderer poses a camel</b> - at that point the ridden
 * ({@code isRidden = true}) state becomes ground truth a caller can ask for, and the freeze is
 * what would be standing in its way.
 *
 * @see EquineSaddleLinesMixin the same strap on the equine saddle, which holds a pair of them in
 *     an array rather than one in a field
 */
@Mixin(CamelSaddleModel.class)
public abstract class CamelSaddleReinsMixin {

    @Shadow @Final private ModelPart reins;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void refharness$unstrapReins(ModelPart root, CallbackInfo ci) {
        if (!Boolean.getBoolean("refharness.headless")) return;
        this.reins.visible = false;
    }
}
