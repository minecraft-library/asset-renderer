package lib.minecraft.refharness.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Freezes animated texture sprites at frame 0 so reference icons match asset-renderer's
 * static frame-0 sampling.
 *
 * <h2>Why this exists</h2>
 * Animated block faces - magma's flowing veins, sea_lantern's pulsing ripple, prismarine,
 * the command_block conduit, sculk / sculk_sensor tendrils, campfire fire, lanterns - drive
 * their texture through {@code SpriteContents.AnimationState.tick()}, which advances
 * {@code subFrame} each client tick and rolls {@code frame} over when a frame's {@code time}
 * elapses. The harness runs warmup ticks before capturing, so the atlas lands on whatever
 * (possibly interpolated) frame the capture tick corresponds to - a live mid-animation frame.
 * Asset-renderer renders a single static icon from frame 0
 * ({@code TextureEngine.resolveTextureAtTick(id, 0)}), so the two disagree on every animated
 * face even when the geometry is byte-identical.
 *
 * <p>This is the texture-animation analog of {@link SkipSetupAnimMixin} (which freezes entity
 * {@code setupAnim} to the bind pose for the same reason): asset-renderer does not animate, so
 * frame 0 is the fair, deterministic comparison target.
 *
 * <h2>What it pins</h2>
 * Forcing both {@code frame = 0} and {@code subFrame = 0} on every {@code tick()} (then
 * cancelling the advance) keeps non-interpolated sprites on frame 0 and - because the
 * interpolation blend factor is {@code subFrame / frameTime} - keeps {@code interpolate:true}
 * sprites (magma, prismarine, sea_lantern) at blend factor 0, i.e. pure frame 0 with no
 * frame-0&rarr;frame-1 bleed. That matches asset-renderer's {@code sampleFrame(tick=0)}
 * exactly. The constructor seeds {@code isDirty = true}, so the atlas is already drawn at
 * frame 0 before any tick; leaving it pinned there needs no extra upload.
 *
 * <p>Targets the private inner {@code SpriteContents$AnimationState} by JVM name. Same
 * {@code refharness.headless} gate as the other harness mixins - non-harness consumers keep
 * vanilla's live animation.
 */
@Mixin(targets = "net.minecraft.client.renderer.texture.SpriteContents$AnimationState")
public abstract class FreezeSpriteAnimationMixin {

    @Shadow
    private int frame;

    @Shadow
    private int subFrame;

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void refharness$freezeFrameZero(CallbackInfo ci) {
        if (!Boolean.getBoolean("refharness.headless")) return;
        this.frame = 0;
        this.subFrame = 0;
        ci.cancel();
    }
}
