package lib.minecraft.refharness.mixin;

import net.minecraft.client.gui.screens.inventory.tooltip.TooltipRenderUtil;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Opens the client's mapping from a tooltip style to the sprite pair its tooltip box draws.
 *
 * <p>The two helpers are private to {@code TooltipRenderUtil}, there being no reason for anything
 * outside the box to ask. A pack run's handshake has one: it names the sprites the client's tooltip
 * draws for every style the run reaches, and asset-renderer holds its own resolution of the same style
 * to that answer. Reading the client's helpers rather than restating the rule is what lets that
 * comparison refuse a disagreement - a second restatement beside the asset side's own could agree
 * with it and not with the client. The invokers read and change nothing.
 */
@Mixin(TooltipRenderUtil.class)
public interface TooltipRenderUtilAccessor {

    /**
     * Answers the background sprite the client's tooltip box draws for a style.
     *
     * @param style the tooltip style, {@code null} for vanilla's own box
     * @return the background sprite's id
     */
    @Invoker("getBackgroundSprite")
    static Identifier refharness$backgroundSprite(@Nullable Identifier style) {
        throw new AssertionError();
    }

    /**
     * Answers the frame sprite the client's tooltip box draws for a style.
     *
     * @param style the tooltip style, {@code null} for vanilla's own box
     * @return the frame sprite's id
     */
    @Invoker("getFrameSprite")
    static Identifier refharness$frameSprite(@Nullable Identifier style) {
        throw new AssertionError();
    }
}
