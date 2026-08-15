package lib.minecraft.refharness.mixin;

import lib.minecraft.refharness.GuiTarget;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drops the screen backdrop from a captured GUI, leaving the panel on transparency.
 *
 * <p>A container screen's own {@code extractBackground} is what blits its panel, and it reaches that
 * blit through {@code super} - so the base implementation runs first and, with a level loaded, draws
 * the blurred world behind the panel and a dark tint over it. Cancelling the base leaves the
 * subclass's blits untouched, because the cancel returns from {@code Screen}'s own body rather than
 * from the override that called it.
 *
 * <p>Gated on a capture being armed as well as on headless, so an ordinary frame in a headless run
 * still draws its backdrop and only the frames this harness reads back lose one.
 */
@Mixin(Screen.class)
public abstract class SuppressScreenBackdropMixin {

    @Inject(method = "extractBackground", at = @At("HEAD"), cancellable = true)
    private void refharness$dropBackdrop(
        GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick, CallbackInfo ci
    ) {
        if (!Boolean.getBoolean("refharness.headless")) return;
        if (GuiTarget.override == null) return;

        ci.cancel();
    }
}
