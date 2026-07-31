package lib.minecraft.refharness.pip;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;

/**
 * The per-frame handles and canvas dimensions a subject's submit step draws with.
 *
 * @param client the running client
 * @param width the canvas width in pixels
 * @param height the canvas height in pixels
 * @param features the feature dispatcher the draw is submitted through
 * @param storage the submit-node storage the geometry lands in
 * @param buffers the buffer source the batched render types are flushed from
 * @param lighting the lighting the subject's entry is selected on
 */
public record PipScope(Minecraft client, int width, int height,
                       FeatureRenderDispatcher features, SubmitNodeStorage storage,
                       MultiBufferSource.BufferSource buffers, Lighting lighting) {

    /**
     * Packed light value for fully lit - skylight 15 shifted 20, block light 15 shifted 4.
     *
     * <p>What vanilla's GUI item path passes: an inventory icon is always full-bright, so its visible
     * shade comes purely from the diffuse lighting and the per-face cardinal shade, never from a
     * per-block-position light value.
     */
    public static final int FULL_BRIGHT_LIGHT = 15728880;

    /**
     * Returns the canvas-centred, model-unit-scaled pose the GUI item and block paths draw from.
     *
     * <p>Item and block models are unit-scale - one model unit is one inventory slot - so scaling by
     * the canvas edge makes the model span it. Y is negated so screen-down maps to model-down, and Z
     * with it, matching what vanilla's picture-in-picture renderer does.
     *
     * @return a fresh pose stack at the GUI frame
     */
    public PoseStack guiPose() {
        PoseStack poseStack = new PoseStack();
        poseStack.translate(width / 2.0f, height / 2.0f, 0.0f);
        poseStack.scale(width, -height, width);
        return poseStack;
    }
}
