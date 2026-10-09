package lib.minecraft.renderer.call.request;

/**
 * The ink a menu's drawn chrome is painted in, where the caller names no art.
 * <p>
 * A style names a palette rather than holding one. The geometry it inks is vanilla's own container
 * frame whichever style is chosen, so a style changes the colours of a panel and never its shape.
 */
public enum ThemeStyle {

    /**
     * The palette the shipped container textures are drawn in.
     */
    VANILLA,

    /**
     * A dark re-inking of vanilla's geometry.
     */
    DARK

}
