package lib.minecraft.refharness.frame;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import lib.minecraft.refharness.api.Canvas;
import lib.minecraft.refharness.api.FrameRenderer;
import lib.minecraft.refharness.pip.PipScope;
import lib.minecraft.refharness.pip.PipTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.TrackingItemStackRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Renders an {@link ItemStack} via the vanilla GUI item pipeline ({@link ItemModelResolver}
 * + {@link Lighting.Entry#ITEMS_3D ITEMS_3D} lighting + {@link FeatureRenderDispatcher})
 * to an offscreen RGBA8 texture, then reads it back as a PNG. Modeled on
 * {@code GuiItemAtlas.drawToSlot} and {@code OversizedItemRenderer.renderToTexture} - the
 * same code paths the vanilla inventory uses to draw block-as-item icons.
 *
 * <p>Why this exists: the block sweep's old approach placed blocks in-world and
 * captured the main framebuffer at the iso camera pose. That picks up vanilla's
 * <em>world</em>-rendering shading (Lambertian diffuse via {@link Lighting.Entry#LEVEL})
 * for block-entity renderers (chest, sign, banner, ...), which doesn't match
 * asset-renderer's inventory-style ground truth. Rendering through this PIP pipeline
 * uses the same lighting + pose ({@code [30, 225, 0]} GUI display transform) the actual
 * inventory uses, so BERs come out shaded the same way asset-renderer expects.
 *
 * <p>Shares allocations across calls through its {@link PipTarget}: the colour and depth textures
 * are reused while the requested canvas stays the same size.
 */
public final class ItemFrameRenderer implements FrameRenderer<ItemStack> {

    /**
     * Half-extent of the orthographic depth range. Item models are unit-scale, so the standard
     * range comfortably contains the posed model.
     *
     * <p>asset-renderer's {@code DepthMath.VANILLA_DEPTH_RANGE} holds this same value, and so
     * does every other {@link FrameRenderer} in this build. Changing it means editing all of them in
     * one commit.
     */
    private static final float DEPTH_RANGE = 1000.0f;

    private final PipTarget pip = new PipTarget("item", DEPTH_RANGE);

    /**
     * Renders the given stack onto the canvas and writes its pixels to {@code out} as a PNG.
     *
     * @param client the active client; supplies the {@link ItemModelResolver},
     *               {@link FeatureRenderDispatcher}, {@link Lighting}, and
     *               {@link MultiBufferSource.BufferSource}
     * @param stack the item to render
     * @param canvas the canvas to draw onto
     * @param out where to write the PNG; parent directories are created on demand
     * @return whether a PNG was written; an empty stack is declined
     * @throws IOException if the PNG file write fails
     */
    @Override
    public boolean render(Minecraft client, ItemStack stack, Canvas canvas, Path out) throws IOException {
        if (stack.isEmpty()) return false;

        Level level = client.level;
        ItemModelResolver resolver = client.getItemModelResolver();
        TrackingItemStackRenderState state = new TrackingItemStackRenderState();
        // ItemDisplayContext.GUI: applies the model's "gui" display transform ([30, 225, 0]
        // for vanilla blocks). Owner=null is fine for non-equipped items - matches what
        // GuiGraphicsExtractor.fakeItem passes.
        resolver.updateForTopItem(state, stack, ItemDisplayContext.GUI, level, /*owner*/ null, /*seed*/ 0);

        pip.draw(client, canvas, out, scope -> {
            // Item models are unit-scale (1 model unit = 1 inventory slot = 16 pixels in
            // GUI), so the GUI pose scales by the canvas edge to make the model fill it; the
            // GUI display transform on the model itself ([30, 225, 0] + ~0.625 scale) leaves a
            // small margin around the item, which matches the inventory icon's visible footprint.
            PoseStack poseStack = scope.guiPose();

            // ITEMS_3D = inventory iso pose lighting for block items (uses block light).
            // ITEMS_FLAT for 2D item icons (no block light). Matches GuiItemAtlas's branch.
            scope.lighting().setupFor(state.usesBlockLight() ? Lighting.Entry.ITEMS_3D : Lighting.Entry.ITEMS_FLAT);

            // Submit the item's geometry into the SubmitNodeStorage; the envelope then runs the
            // feature-renderer dispatcher (which actually draws to the bound textures via the
            // bufferSource) and flushes the bufferSource so all batched render-types are
            // committed to the texture before readback.
            state.submit(poseStack, scope.storage(), PipScope.FULL_BRIGHT_LIGHT, OverlayTexture.NO_OVERLAY, /*seed*/ 0);
            return true;
        });
        return true;
    }

    @Override
    public void close() {
        pip.close();
    }
}
