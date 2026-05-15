package lib.minecraft.refharness.mixin;

import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.EnderDragonRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Skips {@link EntityModel#setupAnim setupAnim} on every harness render path so the produced
 * vanilla reference PNGs use the authored {@code createBodyLayer} bind pose for all living
 * entities instead of vanilla's frame-0 animation pose. Asset-renderer doesn't yet animate,
 * so the bind pose is the only fair comparison target.
 *
 * <h2>Why this exists</h2>
 * Most {@code EntityModel.setupAnim} implementations rewrite {@code ModelPart} pivots / rotations
 * even at {@code ageInTicks = 0}: bats fold their wings via the resting keyframe animation,
 * ghasts wave tentacles via {@code Mth.sin(ageInTicks*0.3)} (lerps to a non-zero shape because
 * its frequency offset), withers tilt the ribcage to {@code (0.065 + 0.05*cos(0))*pi},
 * AbstractZombieModel rotates arms forward by {@code armDrop = -pi/2.25 ~ -80 degrees}
 * UNCONDITIONALLY (gating only on {@code isBaby}), skeleton's HumanoidModel arm-pose code
 * applies bow-holding rotations from {@code state.rightArmPose}, ... None of those poses
 * match what asset-renderer can statically reproduce from {@code createBodyLayer}.
 *
 * <h2>What this skips</h2>
 * Two redirects intercept the two call sites that drive bone mutation in the harness's render
 * pipeline:
 * <ol>
 *   <li>{@link LivingEntityRenderer#submit submit}'s pre-layer
 *       {@code this.model.setupAnim(state)} call. Layer iteration depends on the model being
 *       set up, but layers themselves are gated by equipment / state and bail at headless
 *       zero-state so the setupAnim is purely wasted bone mutation.</li>
 *   <li>{@link ModelFeatureRenderer ModelFeatureRenderer}'s
 *       {@code renderModel}'s {@code model.setupAnim(submit.state())} call - the
 *       <em>actual</em> deferred render path that reads bone state to rasterise. Vanilla
 *       queues a {@code ModelSubmit} containing a reference to the model, then later the
 *       feature dispatcher iterates queued submits and runs setupAnim on each before reading
 *       bone state. Without this redirect, the LER submit redirect above is half a fix: the
 *       primary body's setupAnim was suppressed, but the deferred render path then re-ran
 *       setupAnim and re-mutated bones anyway. Zombie arms-forward, skeleton bow-holding,
 *       and many other animation-driven poses leaked through this gap until 2026-05-15.</li>
 * </ol>
 * After both redirects, every {@link ModelPart ModelPart} keeps its authored {@code PartPose}
 * from {@code createBodyLayer}. {@link FreezeAnimationStateMixin} still pins the per-tick
 * animation state fields so any non-{@code LivingEntityRenderer} path (e.g.
 * {@link EnderDragonRenderer EnderDragonRenderer} which extends the base
 * {@code EntityRenderer}, not {@code LivingEntityRenderer}) gets a stable frame-0 input.
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
@Mixin({LivingEntityRenderer.class, ModelFeatureRenderer.class})
public abstract class SkipSetupAnimMixin {

    @Redirect(
        method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/model/EntityModel;setupAnim(Ljava/lang/Object;)V"),
        require = 0
    )
    private void refharness$skipLivingEntityRendererSetupAnim(EntityModel<?> model, Object state) {
        if (Boolean.getBoolean("refharness.headless")) return;
        @SuppressWarnings({"unchecked", "rawtypes"})
        EntityModel raw = model;
        raw.setupAnim(state);
    }

    @Redirect(
        method = "renderModel",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/model/Model;setupAnim(Ljava/lang/Object;)V"),
        require = 0
    )
    private void refharness$skipFeatureRendererSetupAnim(Model<?> model, Object state) {
        if (Boolean.getBoolean("refharness.headless")) return;
        @SuppressWarnings({"unchecked", "rawtypes"})
        Model raw = model;
        raw.setupAnim(state);
    }
}
