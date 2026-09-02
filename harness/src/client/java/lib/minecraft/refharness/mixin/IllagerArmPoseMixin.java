package lib.minecraft.refharness.mixin;

import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.IllagerRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.IllagerRenderState;
import net.minecraft.world.entity.monster.illager.AbstractIllager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Gives every harness-rendered illager the one pair of arms its own arm pose draws, gated on
 * {@code refharness.headless}.
 *
 * <h2>Why this exists</h2>
 * An illager has two whole arrangements of arms in one mesh - a crossed pair on the {@code arms}
 * bone, and a {@code left_arm} / {@code right_arm} that hang - and {@code IllagerModel.setupAnim}
 * picks between them from {@code state.armPose}, showing exactly one:
 * {@code arms.visible = armPose == CROSSED} and each hanging arm the negation. Which one that is
 * belongs to the subject rather than to the model: an evoker and a pillager idle {@code NEUTRAL}
 * and hang their arms, while an illusioner and a vindicator idle {@code CROSSED}.
 *
 * <p>{@link SkipSetupAnimMixin} cancels {@code setupAnim}, so all three keep {@link ModelPart}'s
 * constructed {@code visible = true} and the reference ships every illager wearing both
 * arrangements at once - a shape vanilla never draws, whichever pose the subject is in.
 *
 * <h2>Where the pin sits</h2>
 * On the renderer rather than on the model, because one {@code IllagerModel} class serves every
 * illager and the answer differs per subject. {@code IllagerRenderer.extractRenderState} is where
 * vanilla reads {@code getArmPose()} off the entity, and it is the base's own body rather than a
 * subclass's, so the field is settled by the time this returns. The bones are reached through the
 * model root by name, the way {@link DonkeyModelMixin} reaches its chests, so the parts written
 * here are the ones the model itself holds.
 *
 * <h2>When to remove this mixin</h2>
 * <b>Delete when the asset-renderer poses an illager</b> - at that point the arm pose becomes
 * ground truth a caller can ask for, and the freeze is what would be standing in its way.
 */
@Mixin(IllagerRenderer.class)
public abstract class IllagerArmPoseMixin {

    @Inject(
        method = "extractRenderState(Lnet/minecraft/world/entity/monster/illager/AbstractIllager;"
            + "Lnet/minecraft/client/renderer/entity/state/IllagerRenderState;F)V",
        at = @At("RETURN")
    )
    private void refharness$showOneArmArrangement(
        AbstractIllager entity, IllagerRenderState state, float partialTick, CallbackInfo ci) {

        if (!Boolean.getBoolean("refharness.headless")) return;
        @SuppressWarnings("rawtypes")
        LivingEntityRenderer renderer = (LivingEntityRenderer) (Object) this;
        ModelPart root = renderer.getModel().root();
        boolean crossed = state.armPose == AbstractIllager.IllagerArmPose.CROSSED;
        root.getChild("arms").visible = crossed;
        root.getChild("left_arm").visible = !crossed;
        root.getChild("right_arm").visible = !crossed;
    }
}
