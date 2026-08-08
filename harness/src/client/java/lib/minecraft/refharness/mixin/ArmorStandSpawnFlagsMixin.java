package lib.minecraft.refharness.mixin;

import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.object.armorstand.ArmorStandModel;
import net.minecraft.world.entity.decoration.ArmorStand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Gives every harness-rendered armor stand the arm visibility a freshly spawned one has, by forcing
 * the {@link ArmorStandModel} arm parts invisible at model construction, gated on
 * {@code refharness.headless}.
 *
 * <h2>Why this exists</h2>
 * {@link ArmorStand} packs its client flags into one synched byte that defaults to zero, and the two
 * flags this model reads sit in it with opposite senses: {@code showArms()} is
 * {@code (flags & 4) != 0}, a plain bit, so a spawned stand has <b>no arms</b>; {@code showBasePlate()}
 * is {@code (flags & 8) == 0}, an inverted one - the stored bit means "no base plate" - so a spawned
 * stand <b>keeps its plate</b>.
 *
 * <p>{@code ArmorStandModel.setupAnim} is what applies both, writing
 * {@code leftArm.visible = state.showArms} and {@code basePlate.visible = state.showBasePlate}. But
 * {@link SkipSetupAnimMixin} cancels {@code setupAnim} (the broad bind-pose freeze), so every part
 * keeps the constructed default {@link ModelPart} gives it - visible. That is right for the base
 * plate and wrong for the arms, so only the arms are pinned here. The constructor hides {@code hat}
 * itself and needs no help.
 *
 * <p>Pinning at {@code <init>} RETURN is what survives the {@code setupAnim} skip; neither
 * {@code ArmorStand.setShowArms} nor a pinned {@code ArmorStandRenderState.showArms} reaches the
 * part. The flag is read by {@code EntityFrameRenderer.walkVisibleExtents} too, so the family-fit
 * canvas and the render agree on the silhouette.
 *
 * <p>The bones are reached through {@code root} rather than shadowed, because {@code leftArm} and
 * {@code rightArm} are declared on {@code HumanoidModel} rather than on the target class.
 *
 * <h2>When to remove this mixin</h2>
 * <b>Delete if the harness ever stops skipping {@code setupAnim}</b> - the flags byte would then
 * drive the parts on its own, which is what this reproduces.
 */
@Mixin(ArmorStandModel.class)
public abstract class ArmorStandSpawnFlagsMixin {

    @Inject(method = "<init>", at = @At("RETURN"))
    private void refharness$applySpawnFlags(ModelPart root, CallbackInfo ci) {
        if (!Boolean.getBoolean("refharness.headless")) return;
        root.getChild("left_arm").visible = false;
        root.getChild("right_arm").visible = false;
    }
}
