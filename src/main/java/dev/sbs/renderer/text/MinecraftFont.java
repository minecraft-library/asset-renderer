package dev.sbs.renderer.text;

import dev.sbs.renderer.exception.FontException;
import dev.simplified.util.SystemUtil;
import lombok.Cleanup;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

import java.awt.*;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/**
 * The Minecraft vanilla font family plus its two alternate-script companions, each wrapped in a
 * ready-to-render {@link Font} resolved from the classpath at enum initialization.
 * <p>
 * The four primary variants ({@link #REGULAR}, {@link #BOLD}, {@link #ITALIC}, {@link #BOLD_ITALIC})
 * are the pixel Minecraft font in its standard typographical forms and map one-to-one to
 * {@link Font#PLAIN}, {@link Font#BOLD}, {@link Font#ITALIC}, and {@code BOLD | ITALIC}.
 * <p>
 * The two alternate-script variants ({@link #GALACTIC} and {@link #ILLAGERALT}) are layered on top
 * of {@link #REGULAR} and are modeled as additional {@link Style} values.
 * <p>
 * Backing {@code .otf} files are produced by the {@code generateFonts} Gradle task and live
 * at {@code cache/fonts/} on disk; the module's {@code processResources} task copies them into
 * {@code build/resources/main/fonts/} so the classpath lookup in {@link #initFont(String, float)}
 * resolves them via {@code getResourceAsStream}. Bumping the Minecraft version is a one-command
 * operation: {@code ./gradlew :asset-renderer:generateFonts -PfontVersion=X}.
 */
@Getter
@RequiredArgsConstructor
public enum MinecraftFont {

    /**
     * The default pixel font used for every untinted, unstyled string in the vanilla GUI.
     * <p>
     * Maps to {@link Font#PLAIN} and is the canonical fallback when a consumer does not
     * specify a style.
     */
    REGULAR("Minecraft-Regular.otf", Style.REGULAR, 15.5f),

    /**
     * The bold variant of the pixel font, used for headings and emphasized text.
     * <p>
     * Maps to {@link Font#BOLD}.
     */
    BOLD("Minecraft-Bold.otf", Style.BOLD, 20.0f),

    /**
     * The italic variant of the pixel font, used for slanted text.
     * <p>
     * Maps to {@link java.awt.Font#ITALIC}.
     */
    ITALIC("Minecraft-Italic.otf", Style.ITALIC, 20.5f),

    /**
     * The combined bold+italic variant of the pixel font.
     * <p>
     * Maps to {@link Font#BOLD} | {@link Font#ITALIC}.
     */
    BOLD_ITALIC("Minecraft-BoldItalic.otf", Style.BOLD_ITALIC, 20.5f),

    /**
     * The Standard Galactic Alphabet, a constructed script originally created by Tom Hall for
     * id Software's Commander Keen series and adopted by Minecraft for the text rendered in
     * the enchanting table interface. The script maps one-for-one to the ASCII {@code A-Z}
     * range with identical uppercase and lowercase glyphs and is built on top of
     * {@link #REGULAR}'s pixel baseline.
     */
    GALACTIC("Minecraft-Galactic.otf", Style.GALACTIC, 15.5f),

    /**
     * Illageralt, a rune-like alphabet adapted from the script of the same name in Minecraft
     * Dungeons. The font is bundled in Java Edition but not used by any vanilla interface -
     * it can only be selected via the JSON text component {@code "font"} key. Like
     * {@link #GALACTIC} it is layered on top of {@link #REGULAR}'s pixel baseline.
     */
    ILLAGERALT("Minecraft-Illageralt.otf", Style.ILLAGERALT, 15.5f),

    /**
     * Sans-serif fallback backed by the host JRE's default logical font rather than a bundled
     * {@code .otf}. Useful as a last-resort anchor when a caller deliberately wants a
     * non-pixel appearance or when the classpath fonts have not yet been generated.
     */
    SANS_SERIF(new java.awt.Font("", java.awt.Font.PLAIN, 20));

    /** The underlying AWT font, already sized and registered with the local graphics environment. */
    private final @NotNull java.awt.Font actual;

    /** The style category this enum value belongs to. */
    private final @NotNull Style style;

    /** The size in points used when the font was loaded. */
    private final float size;

    MinecraftFont(@NotNull String fileName, @NotNull Style style, float size) {
        this(initFont(String.format("fonts/%s", fileName), size), style, size);
    }

    MinecraftFont(@NotNull java.awt.Font font) {
        this(font, Style.of(font.getStyle()), font.getSize2D());
    }

    /**
     * Returns the {@link MinecraftFont} whose {@link #getStyle() style} matches the given
     * {@link Style}, falling back to {@link #REGULAR} when no match exists.
     *
     * @param style the style to look up
     * @return the matching font, or {@link #REGULAR} when none is registered for the style
     */
    public static @NotNull MinecraftFont of(@NotNull Style style) {
        for (MinecraftFont font : values()) {
            if (font.getStyle() == style)
                return font;
        }
        return REGULAR;
    }

    /**
     * Loads an {@code .otf} or {@code .ttf} font from the given classpath resource, derives it
     * to the requested size, and registers it with the local graphics environment so Swing /
     * AWT rendering can refer to it by family name.
     *
     * @param resourcePath the classpath-relative resource path, e.g. {@code fonts/Minecraft-Regular.otf}
     * @param size the desired font size in points
     * @return the loaded and registered {@link java.awt.Font}
     * @throws FontException if the resource is missing or cannot be decoded as a font file
     */
    private static @NotNull java.awt.Font initFont(@NotNull String resourcePath, float size) throws FontException {
        try {
            @Cleanup InputStream inputStream = SystemUtil.getResource(resourcePath);
            Font font = Font.createFont(
                Font.TRUETYPE_FONT,
                Objects.requireNonNull(inputStream)
                )
                .deriveFont(size);
            GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(font);
            return font;
        } catch (IOException | FontFormatException | NullPointerException ex) {
            throw new FontException(ex, resourcePath);
        }
    }

    /**
     * The style category a {@link MinecraftFont} entry belongs to.
     * <p>
     * The four typographical values - {@link #REGULAR}, {@link #BOLD}, {@link #ITALIC},
     * {@link #BOLD_ITALIC} - carry {@link #getId() ids} that match the corresponding
     * {@link java.awt.Font} constants so {@link #of(int)} round-trips against
     * {@code awtFont.getStyle()} cleanly. The two Minecraft-specific script values
     * ({@link #GALACTIC}, {@link #ILLAGERALT}) use ids outside the AWT range because they are
     * not Java-typography styles.
     */
    @Getter
    @RequiredArgsConstructor
    public enum Style {

        /** Non-bold, non-italic text. Matches {@link Font#PLAIN}. */
        REGULAR(0),

        /** Bold text. Matches {@link Font#BOLD}. */
        BOLD(1),

        /** Italic text. Matches {@link Font#ITALIC}. */
        ITALIC(2),

        /** Combined bold and italic text. Matches {@link Font#BOLD} | {@link Font#ITALIC}. */
        BOLD_ITALIC(3),

        /** The Standard Galactic Alphabet script; see {@link MinecraftFont#GALACTIC}. */
        GALACTIC(4),

        /** The Illageralt runic script; see {@link MinecraftFont#ILLAGERALT}. */
        ILLAGERALT(5);

        /**
         * The numeric id matching {@link java.awt.Font#PLAIN}/{@link java.awt.Font#BOLD}/etc.
         * for the typographical values, or a Minecraft-specific id for the alternate scripts.
         */
        private final int id;

        /**
         * Returns the {@link Style} whose {@link #getId() id} matches the given value, or
         * {@link #REGULAR} when no match exists. Accepts both AWT style ints (0-3) and the
         * Minecraft-specific script ids (4-5).
         *
         * @param id the style id to look up
         * @return the matching style, or {@link #REGULAR} when none matches
         */
        public static @NotNull Style of(int id) {
            for (Style style : values()) {
                if (style.getId() == id)
                    return style;
            }
            return REGULAR;
        }

    }

}
