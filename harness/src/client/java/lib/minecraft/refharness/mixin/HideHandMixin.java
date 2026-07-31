package lib.minecraft.refharness.mixin;

import net.minecraft.client.renderer.ItemInHandRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Suppresses player-hand rendering when {@code refharness.headless=true}. Without this
 * the player's main-hand item / arm appears in the lower-right of every block + entity
 * capture, which is one of the user-reported pollution sources.
 *
 * <p>HEAD + cancel because we want to skip the entire {@code renderHandsWithItems}
 * call, not just trim its output. The method has no return value so cancel is safe.
 */
@Mixin(ItemInHandRenderer.class)
public abstract class HideHandMixin {

    @Inject(method = "renderHandsWithItems", at = @At("HEAD"), cancellable = true)
    private void refharness$skipHand(CallbackInfo ci) {
        if (Boolean.getBoolean("refharness.headless")) {
            ci.cancel();
        }
    }
}
