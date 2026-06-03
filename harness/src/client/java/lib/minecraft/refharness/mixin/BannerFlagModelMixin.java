package lib.minecraft.refharness.mixin;

import net.minecraft.client.model.object.banner.BannerFlagModel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Flattens the banner / wall-banner cloth so the harness reference matches asset-renderer's flat
 * flag geometry. Cancels {@link BannerFlagModel#setupAnim setupAnim}, which is the sole writer of
 * the flag bone's {@code xRot}.
 *
 * <h2>Why this exists</h2>
 * Vanilla's cloth billows via {@code flag.xRot = (-0.0125 + 0.01 * cos(2*PI * phase)) * PI}. The
 * lean is never zero - even at {@code phase = 0} it rests at {@code -0.0025 * PI ~ -0.45°}, and
 * {@code BannerRenderer.extractRenderState} seeds {@code phase} from block position + game time,
 * so the cloth leans by an arbitrary, non-reproducible amount (up to {@code ~4°}, ~2 model pixels
 * at the hem). asset-renderer's flag is a single flat box authored at {@code xRot = 0}, so the
 * waving cloth shows up as a ~1px progressive silhouette drift down the flag edge.
 *
 * <p>The flag's {@code createFlagLayer} bind pose is {@code PartPose.offset(...)} - no rotation -
 * so cancelling {@code setupAnim} leaves the bone at {@code xRot = 0}, a flat cloth that matches
 * the asset. Both the fit-pass extent walk ({@code BannerRenderer.getExtents}) and the submitted
 * render call {@code setupAnim}, so cancelling it keeps the measured bounds and the drawn cloth in
 * the same flat pose.
 *
 * <h2>When to remove this mixin</h2>
 * <b>If asset-renderer ever models the cloth wave, delete this mixin so the harness reproduces
 * the in-game billow.</b> Same {@code refharness.headless} gate as the other harness mixins so
 * non-harness consumers of this jar keep vanilla animation behaviour.
 */
@Mixin(BannerFlagModel.class)
public abstract class BannerFlagModelMixin {

    @Inject(method = "setupAnim(Ljava/lang/Float;)V",
        at = @At("HEAD"),
        cancellable = true)
    private void refharness$flattenFlag(Float phase, CallbackInfo ci) {
        if (!Boolean.getBoolean("refharness.headless")) return;
        ci.cancel();
    }
}
