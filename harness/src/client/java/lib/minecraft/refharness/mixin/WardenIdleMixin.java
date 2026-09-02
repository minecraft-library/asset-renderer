package lib.minecraft.refharness.mixin;

import lib.minecraft.refharness.IdleFigures;
import net.minecraft.client.renderer.entity.WardenRenderer;
import net.minecraft.client.renderer.entity.state.WardenRenderState;
import net.minecraft.world.entity.monster.warden.Warden;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Runs the action clip a warden's selection names, and stops the five beside it.
 *
 * <p>Vanilla drives these from two places and both are exclusive: {@code onSyncedDataUpdated} starts
 * one of the roar, sniff, emerge and dig on a {@code Pose} change, and {@code handleEntityEvent}
 * stops the roar to start the attack, or starts the sonic boom. A warden nothing has ticked holds
 * all six stopped, which is the group's resting arm and the default here.
 *
 * <p><b>The selector spans three subjects, and that is forced rather than chosen.</b> A warden and a
 * creaking both spell an attack {@code attackAnimationState}, and a warden and a sniffer both spell
 * a dig {@code diggingAnimationState} - a field name is the whole of what the asset side's frame is
 * asked, so each pair is one member and a member belongs to one group.
 */
@Mixin(WardenRenderer.class)
public abstract class WardenIdleMixin {

    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/monster/warden/Warden;Lnet/minecraft/client/renderer/entity/state/WardenRenderState;F)V",
        at = @At("RETURN"))
    private void refharness$selectAction(
        Warden entity, WardenRenderState state, float partialTick, CallbackInfo ci) {

        if (!Boolean.getBoolean("refharness.headless")) return;
        IdleFigures.State selected = IdleFigures.selected(IdleFigures.Group.ACTION_CLIP);
        IdleFigures.play(selected, IdleFigures.State.ATTACKING, state.attackAnimationState);
        IdleFigures.play(selected, IdleFigures.State.DIGGING, state.diggingAnimationState);
        IdleFigures.play(selected, IdleFigures.State.ROARING, state.roarAnimationState);
        IdleFigures.play(selected, IdleFigures.State.WARDEN_SNIFFING, state.sniffAnimationState);
        IdleFigures.play(selected, IdleFigures.State.EMERGING, state.emergeAnimationState);
        IdleFigures.play(selected, IdleFigures.State.SONIC_BOOMING, state.sonicBoomAnimationState);
    }
}
