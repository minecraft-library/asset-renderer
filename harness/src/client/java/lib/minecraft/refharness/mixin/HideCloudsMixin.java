package lib.minecraft.refharness.mixin;

import net.minecraft.client.renderer.CloudRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Suppresses cloud rendering when {@code refharness.headless=true}. Without this,
 * white cloud quads appear as bright squares in the upper portion of every block /
 * entity capture.
 */
@Mixin(CloudRenderer.class)
public abstract class HideCloudsMixin {

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void refharness$skipClouds(CallbackInfo ci) {
        if (Boolean.getBoolean("refharness.headless")) {
            ci.cancel();
        }
    }
}
