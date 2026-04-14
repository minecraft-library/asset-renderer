package dev.sbs.renderer.text.font;

import lombok.AccessLevel;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

import java.awt.*;

/**
 * A {@link FontMetrics} implementation backed by the {@link MinecraftFont} glyph atlas. All
 * values come from cached data captured at font initialization - no AWT rendering pipeline is
 * needed at query time.
 * <p>
 * Each {@link MinecraftFont} enum value holds a single instance, accessible via
 * {@link MinecraftFont#getFontMetrics()}.
 */
@Getter
public final class MinecraftFontMetrics extends FontMetrics {

    @Getter(AccessLevel.NONE)
    private final @NotNull MinecraftFont mcFont;
    private final int ascent;
    private final int descent;
    private final int height;

    MinecraftFontMetrics(@NotNull MinecraftFont mcFont, int ascent, int descent, int height) {
        super(mcFont.getActual());
        this.mcFont = mcFont;
        this.ascent = ascent;
        this.descent = descent;
        this.height = height;
    }

    @Override
    public int stringWidth(@NotNull String str) {
        int w = 0;

        for (int i = 0; i < str.length(); i++)
            w += this.mcFont.glyph(str.charAt(i)).advanceWidth();

        return w;
    }

    @Override
    public int charWidth(int ch) {
        return this.mcFont.glyph(ch).advanceWidth();
    }

    @Override
    public int charWidth(char ch) {
        return this.mcFont.glyph(ch).advanceWidth();
    }

}
