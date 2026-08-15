package lib.minecraft.refharness;

import com.mojang.blaze3d.pipeline.RenderTarget;
import lib.minecraft.refharness.mixin.MenuRenderTargetMixin;

/**
 * Holds the offscreen target the vanilla GUI pipeline draws into, so a screen can be captured
 * without the window's own framebuffer being read.
 *
 * <p>Every other frame renderer in this harness redirects through
 * {@code RenderSystem.outputColorTextureOverride}, which four vanilla classes read. {@code GuiRenderer}
 * is not one of them - it resolves {@code Minecraft.getMainRenderTarget()} at one call site inside its
 * private draw and hands that to each draw range - so a GUI capture needs the target swapped at that
 * site instead. {@link MenuRenderTargetMixin} is where it is swapped and this is what it reads.
 *
 * <p>{@code volatile} for the reason {@link GlintClock#overrideT} is: written on the client tick
 * thread before the draw and read inside a separately compiled mixin handler. Null restores vanilla
 * behaviour, so the redirect is inert for every frame that is not being captured.
 */
public final class GuiTarget {

    /**
     * The target the GUI pipeline draws into, or {@code null} to leave the client drawing to its own
     * main target.
     */
    public static volatile RenderTarget override;

    private GuiTarget() {}
}
