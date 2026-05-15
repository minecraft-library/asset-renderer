package lib.minecraft.refharness.mixin;

import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.EnderDragonRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Skips {@link EntityModel#setupAnim setupAnim} on every {@link LivingEntityRenderer} render so
 * the harness produces the authored {@code createBodyLayer} bind pose for all living entities
 * instead of vanilla's frame-0 animation pose. Asset-renderer doesn't yet animate, so the bind
 * pose is the only fair comparison target.
 *
 * <h2>Why this exists</h2>
 * Most {@code EntityModel.setupAnim} implementations rewrite {@code ModelPart} pivots / rotations
 * even at {@code ageInTicks = 0}: bats fold their wings via the resting keyframe animation,
 * ghasts wave tentacles via {@code Mth.sin(ageInTicks*0.3)} (lerps to a non-zero shape because
 * its frequency offset), withers tilt the ribcage to {@code (0.065 + 0.05*cos(0))*pi}. None of
 * those poses match what asset-renderer can statically reproduce from {@code createBodyLayer}.
 *
 * <h2>What this skips</h2>
 * The single {@code invokevirtual EntityModel.setupAnim(Ljava/lang/Object;)V} call inside
 * {@link LivingEntityRenderer#submit submit} is redirected to a no-op. Every model's
 * {@code setupAnim} override is therefore never invoked from the harness's render path; each
 * {@link ModelPart ModelPart} keeps its authored
 * {@code PartPose} from {@code createBodyLayer}. {@link FreezeAnimationStateMixin} still pins
 * the per-tick animation state fields so any non-{@code LivingEntityRenderer} path (e.g.
 * {@link EnderDragonRenderer EnderDragonRenderer} which
 * extends the base {@code EntityRenderer}, not {@code LivingEntityRenderer}) gets a stable
 * frame-0 input.
 *
 * <h2>Per-model mixins this generalises</h2>
 * {@link EnderDragonModelMixin} still required (its renderer doesn't go through this path).
 * The previously-added {@code WitherBossModelMixin} is now redundant but harmless - both this
 * redirect and the per-model HEAD cancel resolve to "skip {@code setupAnim}".
 *
 * <h2>When to remove this mixin</h2>
 * <b>If asset-renderer ever gains animation support, delete this mixin so the harness goes back
 * to producing the actual idle-pose vanilla shows in-game.</b> Asset-renderer would then need
 * to reproduce each {@code setupAnim} formula to match.
 *
 * <p>Same {@code refharness.headless} gate as the other harness mixins.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class SkipSetupAnimMixin {

    @Redirect(
        method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/model/EntityModel;setupAnim(Ljava/lang/Object;)V")
    )
    private void refharness$skipSetupAnim(EntityModel<?> model, Object state) {
        if (Boolean.getBoolean("refharness.headless")) return;
        @SuppressWarnings({"unchecked", "rawtypes"})
        EntityModel raw = model;
        raw.setupAnim(state);
    }
}
