package lib.minecraft.refharness.mixin;

import net.minecraft.client.model.monster.witch.WitchModel;
import net.minecraft.client.renderer.entity.WitchRenderer;
import net.minecraft.client.renderer.entity.state.WitchRenderState;
import net.minecraft.world.entity.monster.Witch;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Pins the witch's nose-bob frequency, which vanilla derives from the entity's own network id.
 *
 * <h2>Why this exists</h2>
 * {@link WitchModel#setupAnim setupAnim} computes {@code f = 0.01 * (entityId % 10)} and turns the
 * nose by {@code sin(ageInTicks * f) * 4.5 degrees} and {@code cos(ageInTicks * f) * 2.5 degrees},
 * so that a crowd of witches does not bob in lockstep. An entity's id comes off a counter the client
 * increments for every entity it has ever built, so which of the ten frequencies a harness-built
 * witch gets is a function of how many subjects were constructed before it - and the sweep builds a
 * fresh entity per frame. Two runs of the same schedule drew two different noses on every tick but
 * the first, which is the whole of what stopped the animated reference set reproducing.
 *
 * <p>Zero rather than a value picked to look right: the asset-renderer's pose evaluator answers a
 * render-state input nothing supplies at its resting value, and an {@code int} field no constructor
 * writes rests at zero. So the pin is what the two sides already agree on rather than a constant one
 * of them was fitted to.
 *
 * <h2>Where the pin sits</h2>
 * On {@link WitchRenderer} rather than on the base, for the subclass-ordering reason every pin here
 * has: {@code entityId} is declared on {@link WitchRenderState} and written by this renderer's own
 * {@code extractRenderState} after its {@code super} call, so a pin on {@code LivingEntityRenderer}
 * would be overwritten a few instructions later.
 *
 * <h2>When to remove this mixin</h2>
 * <b>Never.</b> This is a determinism pin rather than a freeze: it takes an input that varies with
 * the client's own history out of the render, the way the guardian's random tail and the phantom's
 * flap offset are taken out. The frozen sub-trees cannot see it at all - {@code entityId} is read by
 * {@code setupAnim} and by nothing else, and that call is cancelled there.
 */
@Mixin(WitchRenderer.class)
public abstract class WitchNoseMixin {

    @Inject(
        method = "extractRenderState(Lnet/minecraft/world/entity/monster/Witch;"
            + "Lnet/minecraft/client/renderer/entity/state/WitchRenderState;F)V",
        at = @At("RETURN")
    )
    private void refharness$pinNoseFrequency(
        Witch entity, WitchRenderState state, float partialTick, CallbackInfo ci) {

        if (!Boolean.getBoolean("refharness.headless")) return;
        state.entityId = 0;
    }
}
