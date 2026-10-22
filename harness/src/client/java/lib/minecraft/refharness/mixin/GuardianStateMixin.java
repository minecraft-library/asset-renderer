package lib.minecraft.refharness.mixin;

import lib.minecraft.refharness.AnimationClock;
import net.minecraft.client.renderer.entity.GuardianRenderer;
import net.minecraft.client.renderer.entity.state.GuardianRenderState;
import net.minecraft.world.entity.monster.ElderGuardian;
import net.minecraft.world.entity.monster.Guardian;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Pins every guardian's gaze and tail, and writes the tail and spikes of the guardian a posed frame
 * draws from the clocks its schedule carries.
 *
 * <h2>{@link GuardianRenderState#spikesAnimation spikesAnimation} follows the clock</h2>
 * {@code GuardianModel.setupAnim} places each spike at an offset of
 * {@code 1 + cos(ageInTicks * 1.5 + i) * 0.01 - (1 - spikesAnimation) * 0.55}. The two levels
 * vanilla settles the field at:
 * <ul>
 *   <li>{@code 1} - the spikes held out, the level {@code Guardian.aiStep} eases a guardian still in
 *       water toward.</li>
 *   <li>{@code 0} - each spike pulled in by {@code 0.55}, the level it eases one under way toward.</li>
 * </ul>
 * A posed frame writes the value the roster carries for the subject's gait - the spikes out in
 * {@code idle/}, in under way in {@code walk/} - into the guardian it draws, so both sides read one
 * table. A frozen frame leaves vanilla's never-ticked {@code 0}: {@code Guardian.getSpikesAnimation}
 * lerps two fields no tick has stepped, and {@code setupAnim}, the field's one reader, is skipped
 * there. A guardian no posed frame draws keeps the value vanilla's own extract gave it.
 *
 * <h2>{@link GuardianRenderState#tailAnimation tailAnimation} follows the clock, and is pinned
 * everywhere else</h2>
 * The {@link Guardian} constructor seeds {@code clientSideTailAnimation = random.nextFloat()} (and
 * copies it to the {@code O} field for the lerp), so every fresh {@code EntityType.create} call hands
 * back a guardian whose tail is in a different random pose, and {@code GuardianRenderer.extractRenderState}
 * forwards that value to {@code state.tailAnimation}. {@code GuardianModel.setupAnim} turns each tail
 * segment by {@code sin(tailAnimation) * PI} times {@code 0.05}, {@code 0.1} and {@code 0.15} down the
 * tail. A posed frame writes the roster's value into the guardian it draws - the tick times
 * {@code 0.125} still and times {@code 0.5} under way, vanilla's settled tail speeds - and every other
 * render, a frozen frame or a guardian no posed frame draws, pins {@code 0}, so a render state is never
 * a function of a run's random draw even where nothing reads it.
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
 * Same as {@code PufferfishStateMixin}: these fields are populated in
 * {@link GuardianRenderer}'s subclass override <em>after</em> the call to
 * {@code super.extractRenderState(...)}, so a mixin on the base renderer would fire
 * before the subclass writes the fields and have its values overwritten. Injecting at
 * the subclass return point captures the post-vanilla-write state and overrides it.
 *
 * <p>Both regular {@link Guardian} and {@link ElderGuardian}
 * use this single renderer + state class, so this mixin handles both targets.
 */
@Mixin(GuardianRenderer.class)
public abstract class GuardianStateMixin {

    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/monster/Guardian;Lnet/minecraft/client/renderer/entity/state/GuardianRenderState;F)V",
        at = @At("RETURN"))
    private void refharness$pinAnimationState(Guardian entity, GuardianRenderState state, float partialTick, CallbackInfo ci) {
        if (!Boolean.getBoolean("refharness.headless")) return;
        state.lookAtPosition = null;
        state.lookDirection = null;
        state.tailAnimation = 0.0f;
        if (!AnimationClock.isSubject(entity)) return;
        state.tailAnimation = AnimationClock.clock("tailAnimation");
        state.spikesAnimation = AnimationClock.clock("spikesAnimation");
    }
}
