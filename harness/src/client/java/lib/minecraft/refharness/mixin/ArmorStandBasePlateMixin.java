package lib.minecraft.refharness.mixin;

import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.object.armorstand.ArmorStandModel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hides the stone base plate on every harness-rendered armor stand by forcing the
 * {@link ArmorStandModel} {@code basePlate} part invisible at model construction, gated on
 * {@code refharness.headless}.
 *
 * <h2>Why this exists</h2>
 * The asset-renderer's v2 armor-stand model toggles the base-plate bone off, so its render is the
 * bare stand without the slab. Vanilla shows the base plate by default, so {@code armor_stand.png}
 * lands a wider + taller silhouette (the slab extends past the feet) that diverges from the Java
 * render.
 *
 * <p>{@code ArmorStandModel.setupAnim} normally sets {@code basePlate.visible = state.showBasePlate},
 * but {@link SkipSetupAnimMixin} cancels {@code setupAnim} (the broad bind-pose freeze), so the
 * base plate keeps its constructed default of visible. Neither {@code ArmorStand.setNoBasePlate} nor
 * pinning {@code ArmorStandRenderState.showBasePlate} reaches it. Pin the part's {@code visible} flag
 * directly at {@code <init>} RETURN instead - it survives the {@code setupAnim} skip and is measured
 * by {@code EntityFrameRenderer.walkVisibleExtents} (so both the family-fit canvas and the render
 * exclude the plate).
 *
 * <h2>When to remove this mixin</h2>
 * <b>Delete when the asset-renderer renders the armor-stand base plate</b> - at that point vanilla's
 * default becomes the matching ground truth again.
 */
@Mixin(ArmorStandModel.class)
public abstract class ArmorStandBasePlateMixin {

    @Shadow @Final private ModelPart basePlate;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void refharness$hideBasePlate(ModelPart root, CallbackInfo ci) {
        if (!Boolean.getBoolean("refharness.headless")) return;
        this.basePlate.visible = false;
    }
}
