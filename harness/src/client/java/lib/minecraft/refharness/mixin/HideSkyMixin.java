package lib.minecraft.refharness.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SkyRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Suppresses sky / sun / moon / stars / sunrise rendering when {@code refharness.headless=true}
 * so captured frames don't have a sky-blue gradient behind the subject. Each public draw
 * method on {@link SkyRenderer} is short-circuited individually to keep the suppression
 * narrow - any other consumer of {@code SkyRenderer} continues to work normally.
 */
@Mixin(SkyRenderer.class)
public abstract class HideSkyMixin {

    @Inject(method = "renderSkyDisc(I)V", at = @At("HEAD"), cancellable = true)
    private void refharness$skipSky(int skyColor, CallbackInfo ci) {
        if (Boolean.getBoolean("refharness.headless")) ci.cancel();
    }

    @Inject(method = "renderDarkDisc()V", at = @At("HEAD"), cancellable = true)
    private void refharness$skipDark(CallbackInfo ci) {
        if (Boolean.getBoolean("refharness.headless")) ci.cancel();
    }

    @Inject(method = "renderSunriseAndSunset(Lcom/mojang/blaze3d/vertex/PoseStack;FI)V", at = @At("HEAD"), cancellable = true)
    private void refharness$skipSunrise(PoseStack pose, float sunAngle, int color, CallbackInfo ci) {
        if (Boolean.getBoolean("refharness.headless")) ci.cancel();
    }

    @Inject(method = "renderEndSky()V", at = @At("HEAD"), cancellable = true)
    private void refharness$skipEndSky(CallbackInfo ci) {
        if (Boolean.getBoolean("refharness.headless")) ci.cancel();
    }

    @Inject(method = "renderSunMoonAndStars", at = @At("HEAD"), cancellable = true)
    private void refharness$skipSunMoonStars(CallbackInfo ci) {
        if (Boolean.getBoolean("refharness.headless")) ci.cancel();
    }

    @Inject(method = "renderEndFlash", at = @At("HEAD"), cancellable = true)
    private void refharness$skipEndFlash(CallbackInfo ci) {
        if (Boolean.getBoolean("refharness.headless")) ci.cancel();
    }
}
