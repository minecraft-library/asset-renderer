package lib.minecraft.refharness.frame;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import lib.minecraft.refharness.GuiTarget;
import lib.minecraft.refharness.api.Canvas;
import lib.minecraft.refharness.mixin.GameRendererAccessor;
import lib.minecraft.refharness.pip.TextureReadback;
import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.WindowRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * The GUI capture every GUI sweep shares - a GUI drawn through the client's own extract-then-draw
 * into an offscreen target, then read back as a PNG.
 *
 * <p>This is the only frame renderer here that does not submit geometry itself. A GUI subject's
 * whole point is what the client composes - a menu's panel, labels and items through vanilla's GUI
 * item atlas, a tooltip's box and its text - so the capture drives vanilla's own extract-then-draw
 * and changes only where the draw lands. What that costs is one redirect the other renderers do not
 * need, held on {@link GuiTarget}.
 *
 * <p>The caller hands it the extract step - what its subject puts into the GUI render state - and
 * the capture keeps the rest: the window swap, the redirect, the clear, the draw and the read-back.
 * Three pieces of client state are moved for the duration of a capture and put back in a
 * {@code finally}, because they are global and the next tick's ordinary frame reads them: the window
 * extent the GUI projection is built from, the GUI render state the extract fills, and the target
 * the draw resolves. The extractor is built with {@code mouseX = mouseY = -1}, so nothing a step
 * extracts is hovered - for a menu that suppresses the slot highlight and the slot tooltip together,
 * being one test in vanilla rather than two.
 */
@Parity(claim = "harness-gui-capture", mode = Mode.DEMOTE)
public final class GuiFrameRenderer implements AutoCloseable {

    /**
     * Output pixels one Minecraft pixel occupies on a side. It is asset-renderer's
     * {@code MinecraftFont.MC_PIXEL_SCALE}, and the two must move together: a GUI is laid out in
     * Minecraft pixels on both sides and this is the only thing that turns them into a canvas.
     */
    public static final int GUI_SCALE = 2;

    /** the label the target and the read-back's staging buffer are tagged with */
    private final String debugName;

    private TextureTarget target;

    /**
     * Constructs a new capture whose target and read-back are tagged with one label.
     *
     * @param debugName the label, naming what the capture draws
     */
    public GuiFrameRenderer(String debugName) {
        this.debugName = debugName;
    }

    /**
     * Captures one GUI onto the canvas and writes its pixels to {@code out} as a PNG.
     *
     * @param client the running client, supplying the render states and the GUI renderer
     * @param canvas the canvas to draw onto, which is the subject's extent at {@link #GUI_SCALE}
     * @param out where to write the PNG; parent directories are created on demand
     * @param extract fills the GUI render state through the extractor it is handed, run with the window
     * extent swapped to the canvas and the redirect armed
     * @return whether a PNG was written
     * @throws IOException if the PNG's directory cannot be created
     */
    public boolean render(
        Minecraft client, Canvas canvas, Path out, Consumer<GuiGraphicsExtractor> extract
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
            // The GUI projection is built from these three, so they are what decides the subject lands
            // on the canvas at one Minecraft pixel per GUI_SCALE output pixels.
            window.width = canvas.width();
            window.height = canvas.height();
            window.guiScale = GUI_SCALE;

            guiState.reset();
            GuiGraphicsExtractor extractor = new GuiGraphicsExtractor(client, guiState, -1, -1);

            // Armed before the extract, not just before the draw: a screen's own extractBackground is
            // what blits its panel and it reaches that through super, so the backdrop suppression has
            // to be able to see that a capture is running while the state is being built.
            GuiTarget.override = this.target;

            extract.accept(extractor);

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

        TextureReadback.writeToPng(this.target.getColorTexture(), canvas.width(), canvas.height(), this.debugName, out);
        return true;
    }

    /**
     * Allocates the offscreen target, reusing it while the requested canvas keeps its size. A GUI
     * roster changes size subject to subject - a menu's panel height, a tooltip's measured box - so
     * this reallocates more often than the entity path does; the target is a plain vanilla one and
     * carries no per-subject state.
     */
    private void ensureTarget(int width, int height) {
        if (this.target != null && this.target.width == width && this.target.height == height) return;

        closeTarget();
        this.target = new TextureTarget("refharness " + this.debugName, width, height, /*useDepth*/ true);
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
