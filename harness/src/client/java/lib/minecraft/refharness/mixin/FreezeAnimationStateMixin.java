package lib.minecraft.refharness.mixin;

import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.fish.AbstractFish;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Zeroes per-tick animation state on every {@link LivingEntityRenderState} after vanilla
 * extracts it, so transient zero-state entities rendered through the refharness produce
 * frame-0 silhouettes rather than drifting through partial-tick poses.
 *
 * <p>The refharness builds entities via {@code EntityType.create} (no world add, no tick),
 * which leaves most animation drivers at their default values - but a few derive from
 * {@code partialTick} interpolation between {@code o*} (previous-tick) and current fields,
 * and on a fresh entity those interpolations occasionally land on small non-zero values that
 * shift the pose visibly between runs. Hitting the render state at
 * {@link LivingEntityRenderer#extractRenderState extractRenderState}'s return point gives
 * us the post-vanilla-fill values to overwrite, regardless of which subclass override
 * populated them.
 *
 * <p>Frozen fields:
 * <ul>
 *   <li>{@link LivingEntityRenderState#walkAnimationPos walkAnimationPos} /
 *       {@link LivingEntityRenderState#walkAnimationSpeed walkAnimationSpeed} - leg/wing
 *       walk cycle. Multiplied into rotation in most {@code EntityModel.setupAnim} bodies;
 *       leaving non-zero produces mid-stride leg poses.</li>
 *   <li>{@link LivingEntityRenderState#deathTime deathTime} - death-fall rotation lerp
 *       on {@link LivingEntityRenderer#setupRotations setupRotations}.</li>
 *   <li>{@link LivingEntityRenderState#ticksSinceKineticHitFeedback ticksSinceKineticHitFeedback}
 *       - hit-stagger rotation overlay.</li>
 *   <li>{@link LivingEntityRenderState#wornHeadAnimationPos wornHeadAnimationPos} - bobbing
 *       offset for entities wearing helmets / pumpkins / mob heads.</li>
 *   <li>{@link net.minecraft.client.renderer.entity.state.EntityRenderState#ageInTicks ageInTicks}
 *       (inherited) - drives idle animations on most models (allay arm bob, ghast tentacle
 *       drift, fish swim wiggle, hoglin tail flick, ...).</li>
 * </ul>
 *
 * <p>Does <b>not</b> touch dimension fields ({@link LivingEntityRenderState#scale scale},
 * {@link LivingEntityRenderState#ageScale ageScale},
 * {@link net.minecraft.client.renderer.entity.state.EntityRenderState#boundingBoxWidth
 * boundingBoxWidth}, {@code boundingBoxHeight}, {@code eyeHeight},
 * {@link LivingEntityRenderState#bodyRot bodyRot}/{@code yRot}/{@code xRot}) - those are
 * geometric properties zeroed earlier in
 * {@code EntitySweeper.zeroRotations} or kept at vanilla's authored value.
 *
 * <p>Subclass-specific render states ({@code ChickenRenderState.flap},
 * {@code GuardianRenderState.spikesAnimation}, {@code SalmonRenderState.<...>}) carry
 * additional animation fields that {@code LivingEntityRenderer}'s base method does not
 * populate - those subclass overrides set the fields after our injection point's return
 * point on {@code LivingEntityRenderer.extractRenderState}, so they survive this mixin.
 * Add a per-subclass mixin (or extend this one with {@code @Inject} on the subclass's
 * {@code extractRenderState}) when a specific entity's animation drift becomes the next
 * blocker; the {@code refharness.headless} gate scopes the freeze to harness runs so
 * non-harness consumers of this jar are unaffected.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class FreezeAnimationStateMixin {

    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;F)V",
        at = @At("RETURN"))
    private void refharness$freezeAnimationState(LivingEntity entity, LivingEntityRenderState state, float partialTick, CallbackInfo ci) {
        if (!Boolean.getBoolean("refharness.headless")) return;
        state.ageInTicks = 0f;
        state.walkAnimationPos = 0f;
        state.walkAnimationSpeed = 0f;
        state.deathTime = 0f;
        state.ticksSinceKineticHitFeedback = 0f;
        state.wornHeadAnimationPos = 0f;
        // Force fish to render upright. Vanilla's fish renderers (Salmon/Cod/TropicalFish/...)
        // apply a 90° Z rotation in setupRotations when {@code isInWater} is false to mimic the
        // "fish flopping on land" behaviour - that's the right gameplay pose but the wrong
        // ground-truth shape for an inventory-style reference render. Our transient entity is
        // never added to a world, so vanilla's water check returns false and the fish lies
        // sideways. Pretending the fish is in water restores the upright swim pose. Scoped to
        // {@link AbstractFish} (Salmon/Cod/TropicalFish/Pufferfish) so dolphins, axolotls,
        // squids, and drowned - all of which use {@code isInWater} for different animations -
        // are unaffected.
        if (entity instanceof AbstractFish) {
            state.isInWater = true;
        }
        // Subclass-specific render-state fields like {@code PufferfishRenderState.puffState}
        // and {@code GuardianRenderState.spikesAnimation} are populated by the subclass
        // renderer's {@code extractRenderState} <em>after</em> its {@code super.extractRenderState}
        // call - i.e. after this injection point fires - so any value we'd set here would be
        // overwritten by vanilla a few instructions later. Those fields are pinned in
        // dedicated per-renderer mixins ({@code PufferfishStateMixin},
        // {@code GuardianStateMixin}) that inject at their respective subclass returns.
    }
}
