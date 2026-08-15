package lib.minecraft.refharness.frame;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import lib.minecraft.refharness.GuiTarget;
import lib.minecraft.refharness.api.Canvas;
import lib.minecraft.refharness.mixin.GameRendererAccessor;
import lib.minecraft.refharness.pip.TextureReadback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.WindowRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Renders a container screen through the client's own GUI pipeline into an offscreen target, then
 * reads it back as a PNG.
 *
 * <p>This is the only frame renderer here that does not submit geometry itself. A menu's whole point
 * is what the client composes - the panel, its labels, and its items through vanilla's GUI item
 * atlas - so the capture drives vanilla's own extract-then-draw and changes only where the draw
 * lands. What that costs is one redirect the other renderers do not need, held on {@link GuiTarget}.
 *
 * <p>Three pieces of client state are moved for the duration of a capture and put back in a
 * {@code finally}, because they are global and the next tick's ordinary frame reads them: the window
 * extent the GUI projection is built from, the GUI render state the extract fills, and the target
 * the draw resolves. The screen is laid out at {@code init(imageWidth, imageHeight)}, which is what
 * puts its panel at the origin rather than centred in a window, and extraction is passed
 * {@code mouseX = mouseY = -1} so no slot is hovered - that suppresses the slot highlight and the
 * tooltip together, being one test in vanilla rather than two.
 */
public final class MenuFrameRenderer implements AutoCloseable {

    /**
     * Output pixels one Minecraft pixel occupies on a side. It is asset-renderer's
     * {@code MinecraftFont.MC_PIXEL_SCALE}, and the two must move together: the panel is laid out in
     * Minecraft pixels on both sides and this is the only thing that turns them into a canvas.
     */
    public static final int GUI_SCALE = 2;

    private TextureTarget target;

    /**
     * Renders one screen onto the canvas and writes its pixels to {@code out} as a PNG.
     *
     * @param client the running client, supplying the render states and the GUI renderer
     * @param screen the screen to draw, not yet laid out
     * @param canvas the canvas to draw onto, which is the screen's panel at {@link #GUI_SCALE}
     * @param out where to write the PNG; parent directories are created on demand
     * @param afterInit run once the screen is laid out, for a subject whose state lives on a widget
     * {@code init} is what builds
     * @return whether a PNG was written
     * @throws IOException if the PNG write fails
     */
    public boolean render(
        Minecraft client, AbstractContainerScreen<?> screen, Canvas canvas, Path out,
        Consumer<AbstractContainerScreen<?>> afterInit
    ) throws IOException {
        ensureTarget(canvas.width(), canvas.height());

        GameRenderState renderState = client.gameRenderer.getGameRenderState();
        GameRendererAccessor renderer = (GameRendererAccessor) client.gameRenderer;
        WindowRenderState window = renderState.windowRenderState;
        GuiRenderState guiState = renderState.guiRenderState;
        int width = window.width;
        int height = window.height;
        int guiScale = window.guiScale;

        try {
            // The GUI projection is built from these three, so they are what decides the panel lands
            // on the canvas at one Minecraft pixel per GUI_SCALE output pixels.
            window.width = canvas.width();
            window.height = canvas.height();
            window.guiScale = GUI_SCALE;

            // init sizes the screen to the panel itself, which is what makes leftPos and topPos zero.
            screen.init(canvas.width() / GUI_SCALE, canvas.height() / GUI_SCALE);

            // A screen builds its widgets in init, so anything a subject wants to say about one has
            // to be said here - before the extract reads them and after they exist.
            afterInit.accept(screen);

            guiState.reset();
            GuiGraphicsExtractor extractor = new GuiGraphicsExtractor(client, guiState, -1, -1);

            // Armed before the extract, not just before the draw: a screen's own extractBackground is
            // what blits its panel and it reaches that through super, so the backdrop suppression has
            // to be able to see that a capture is running while the state is being built.
            GuiTarget.override = this.target;

            // The panel is the screen's background, and it is the caller that draws one - the two
            // halves of a container screen's state are extracted through separate entry points and
            // extractRenderState is only the second of them.
            screen.extractBackground(extractor, -1, -1, 0.0f);
            screen.extractRenderState(extractor, -1, -1, 0.0f);

            RenderSystem.getDevice().createCommandEncoder()
                .clearColorAndDepthTextures(this.target.getColorTexture(), 0, this.target.getDepthTexture(), 1.0);
            renderer.refharness$guiRenderer()
                .render(renderer.refharness$fogRenderer().getBuffer(FogRenderer.FogMode.NONE));
        } finally {
            GuiTarget.override = null;
            guiState.reset();
            window.width = width;
            window.height = height;
            window.guiScale = guiScale;
        }

        TextureReadback.writeToPng(this.target.getColorTexture(), canvas.width(), canvas.height(), "menu", out);
        return true;
    }

    /**
     * Allocates the offscreen target, reusing it while the requested canvas keeps its size. A menu
     * roster changes size subject to subject, so this reallocates more often than the entity path
     * does; the target is a plain vanilla one and carries no per-subject state.
     */
    private void ensureTarget(int width, int height) {
        if (this.target != null && this.target.width == width && this.target.height == height) return;

        closeTarget();
        this.target = new TextureTarget("refharness menu", width, height, /*useDepth*/ true);
    }

    private void closeTarget() {
        if (this.target == null) return;

        RenderTarget closing = this.target;
        this.target = null;
        closing.destroyBuffers();
    }

    /** {@inheritDoc} */
    @Override
    public void close() {
        closeTarget();
    }
}
