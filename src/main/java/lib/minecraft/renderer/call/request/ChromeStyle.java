package lib.minecraft.renderer.call.request;

/**
 * The chrome a lore tooltip's background and border are drawn in.
 * <p>
 * A style names a chrome rather than holding one. The renderer resolves it at draw time, so a request
 * asking for the sprite chrome carries no sprites of its own - the pair is looked up through the pack
 * stack of the context the renderer holds.
 */
public enum ChromeStyle {

    /**
     * The pack's {@code tooltip/background} and {@code tooltip/frame} sprite pair, nine-sliced over the
     * canvas and resolved through the renderer's context.
     */
    SPRITE,

    /**
     * Vanilla's fill and gradient ring, drawn from constants and needing no context.
     */
    PROCEDURAL

}
