package lib.minecraft.refharness.mixin;

import net.minecraft.client.renderer.entity.GuardianRenderer;
import net.minecraft.client.renderer.entity.state.GuardianRenderState;
import net.minecraft.world.entity.monster.Guardian;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Forces every refharness guardian / elder-guardian render to display extended spikes
 * and pins the tail to a fixed pose so cross-render output is byte-stable.
 *
 * <h2>{@link GuardianRenderState#spikesAnimation spikesAnimation} = 1</h2>
 * {@code GuardianModel.setupAnim} positions each spike via
 * {@code 1.0 - ((1 - spikesAnimation) * 0.55) - cos(...)*0.01}. The two endpoints:
 * <ul>
 *   <li>{@code 0} (vanilla default for an idle guardian) → spike offset ≈ {@code 0.45} →
 *       spikes retracted into the body, only the small surface bumps poke through.</li>
 *   <li>{@code 1} (alert / cave-guardian-glaring-at-you state) → offset ≈ {@code 1.0} →
 *       spikes fully extended outward, the iconic silhouette.</li>
 * </ul>
 * Our transient entity has {@code oSpikesAnimation == spikesAnimation == 0} (no tick has
 * run), so vanilla's {@code Guardian.getSpikesAnimation(partialTick)} lerps to {@code 0}
 * and the render lands on the retracted bumps. Pin {@code state.spikesAnimation = 1.0f}
 * so the reference render shows the recognisable extended-spike pose.
 *
 * <h2>{@link GuardianRenderState#tailAnimation tailAnimation} = 0</h2>
 * The {@link Guardian} constructor seeds {@code clientSideTailAnimation =
 * random.nextFloat()} (and copies it to the {@code O} field for the lerp), so every
 * fresh {@code EntityType.create} call hands back a guardian whose tail is in a
 * different random pose. {@code GuardianRenderer.extractRenderState} then forwards that
 * random value to {@code state.tailAnimation}, and {@code GuardianModel.setupAnim} feeds
 * it into the per-tail-segment rotation formula - net effect: the tail visibly snaps to
 * a different shape on every render, breaking byte-stability of guardian / elder_guardian
 * PNGs across runs. Pin {@code state.tailAnimation = 0.0f} so every render lands on the
 * same straight-back-tail rest pose. The tail-flop animation in the model is
 * {@code (Mth.PI / 6) * sin(0.75 * tailAnimation)} for tail0 and similar formulas down
 * the chain - {@code tailAnimation = 0} gives {@code sin(0) = 0}, i.e. zero rotation per
 * segment, which is the canonical "calm guardian floating in place" pose used by the
 * wiki / inventory portrait.
 *
 * <h2>{@link GuardianRenderState#lookAtPosition lookAtPosition} /
 * {@link GuardianRenderState#lookDirection lookDirection} = null</h2>
 * {@code GuardianRenderer.extractRenderState} resolves a look-at target via the private
 * {@code getEntityToLookAt} helper, which falls back to
 * {@code Minecraft.getInstance().getCameraEntity()} (the harness player) when no attack
 * target exists - so {@code state.lookAtPosition} ends up as the player's eye position.
 * The harness player still ticks while we render, so its position wobbles by a hair
 * each frame; {@code GuardianModel.setupAnim}'s eye-direction block then computes a
 * slightly different eye rotation per render and the eye texture pixels drift between
 * runs (1500-pixel diff localised to the eye region, no other change). Forcing both
 * fields to null routes setupAnim through its {@code if (lookAtPosition == null ||
 * lookDirection == null) skip} branch, leaving the eye in its authored rest pose at
 * {@code (0, 0)} - byte-stable across runs.
 *
 * <h2>Injection-point reasoning</h2>
 * Same as {@code PufferfishStateMixin}: both fields are populated in
 * {@link GuardianRenderer}'s subclass override <em>after</em> the call to
 * {@code super.extractRenderState(...)}, so a mixin on the base renderer would fire
 * before the subclass writes the fields and have its values overwritten. Injecting at
 * the subclass return point captures the post-vanilla-write state and overrides it.
 *
 * <p>Both regular {@link Guardian} and {@link net.minecraft.world.entity.monster.ElderGuardian}
 * use this single renderer + state class, so this mixin handles both targets.
 */
@Mixin(GuardianRenderer.class)
public abstract class GuardianStateMixin {

    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/monster/Guardian;Lnet/minecraft/client/renderer/entity/state/GuardianRenderState;F)V",
        at = @At("RETURN"))
    private void refharness$pinAnimationState(Guardian entity, GuardianRenderState state, float partialTick, CallbackInfo ci) {
        if (!Boolean.getBoolean("refharness.headless")) return;
        state.spikesAnimation = 1.0f;
        state.tailAnimation = 0.0f;
        state.lookAtPosition = null;
        state.lookDirection = null;
    }
}
