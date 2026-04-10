package dev.sbs.renderer.draw;

/**
 * Per-pixel alpha compositing modes supported by {@link ColorKit} and the drawing helpers.
 * <p>
 * Mode semantics operate on ARGB pixel pairs where {@code src} is the incoming pixel and
 * {@code dst} is the pixel already on the canvas. Alpha is always premultiplied into the output.
 */
public enum BlendMode {

    /**
     * Standard source-over alpha compositing. The result equals
     * {@code src * src.a + dst * (1 - src.a)} per channel.
     */
    NORMAL,

    /**
     * Additive blend, clamped to byte range. The result equals
     * {@code min(255, src + dst)} per channel. Used by the enchantment glint.
     */
    ADD,

    /**
     * Multiplicative blend. The result equals {@code src * dst / 255} per channel, preserving
     * the destination alpha. Used by biome tint application and leather armor colouring.
     */
    MULTIPLY,

    /**
     * Photoshop-style overlay: {@code dst < 128 ? 2*src*dst/255 : 255 - 2*(255-src)*(255-dst)/255}.
     * Used for stylistic decals and badge overlays.
     */
    OVERLAY

}
