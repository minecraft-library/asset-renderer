package lib.minecraft.refharness.mixin;

import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Opens the two private collaborators a GUI capture has to drive - the renderer that consumes a
 * frame's GUI state, and the fog renderer whose buffer that draw takes.
 *
 * <p>Vanilla exposes neither, because nothing outside a frame is meant to draw the GUI. Every other
 * handle this harness needs off {@code GameRenderer} - the feature dispatcher, the lighting, the game
 * render state - already has a public getter, so this is the whole of what a menu capture adds and it
 * reads state rather than changing any.
 */
@Mixin(GameRenderer.class)
public interface GameRendererAccessor {

    /**
     * The renderer that draws a frame's extracted GUI state.
     *
     * @return the GUI renderer
     */
    @Accessor("guiRenderer")
    GuiRenderer refharness$guiRenderer();

    /**
     * The fog renderer, for the buffer slice the GUI draw takes.
     *
     * @return the fog renderer
     */
    @Accessor("fogRenderer")
    FogRenderer refharness$fogRenderer();
}
