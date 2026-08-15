package lib.minecraft.refharness.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import lib.minecraft.refharness.GuiTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.GuiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Sends the GUI pipeline's draw to {@link GuiTarget#override} when the harness has armed one, so a
 * container screen can be captured offscreen at its own size.
 *
 * <p>This is the harness's first mixin that redirects a render target rather than freezing state, and
 * it exists because {@code GuiRenderer} is the one drawing path that cannot be redirected from
 * outside: it resolves {@code Minecraft.getMainRenderTarget()} inside its own private draw and never
 * reads {@code RenderSystem.outputColorTextureOverride}, which is what every other offscreen render
 * here sets. A grep of the extracted client finds four classes holding that field - the item atlas,
 * the picture-in-picture renderer, the level renderer and the render types - and this is not one.
 *
 * <p>Inert whenever no target is armed, so an ordinary frame still draws to the window.
 */
@Mixin(GuiRenderer.class)
public abstract class MenuRenderTargetMixin {

    @Redirect(
        method = "draw",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/Minecraft;getMainRenderTarget()Lcom/mojang/blaze3d/pipeline/RenderTarget;"))
    private RenderTarget refharness$redirectGuiTarget(Minecraft client) {
        if (!Boolean.getBoolean("refharness.headless")) return client.getMainRenderTarget();

        RenderTarget armed = GuiTarget.override;
        return armed != null ? armed : client.getMainRenderTarget();
    }
}
