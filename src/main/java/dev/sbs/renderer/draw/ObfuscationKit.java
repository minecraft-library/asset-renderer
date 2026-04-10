package dev.sbs.renderer.draw;

import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.util.Random;

/**
 * Generates per-frame character substitutions for Minecraft's obfuscated text ({@code §k}) effect.
 * <p>
 * Vanilla Minecraft renders obfuscated runs by replacing every glyph with a random pick from a
 * fixed visible glyph pool on each client tick. For animated output we reproduce the effect by
 * seeding the random stream on the frame index so a given frame is fully deterministic and
 * reproducible across runs.
 */
@UtilityClass
public class ObfuscationKit {

    /**
     * Default glyph pool used when a caller does not provide its own. 64 printable ASCII characters
     * approximating vanilla's sampled glyph pool.
     */
    public static final @NotNull String DEFAULT_POOL =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$";

    /**
     * Substitutes every character in {@code text} with a deterministic pick from the default glyph
     * pool seeded on {@code frameSeed}. Whitespace is preserved so word boundaries survive.
     *
     * @param text the source text
     * @param frameSeed the frame-index seed
     * @return the scrambled text
     */
    public static @NotNull String substitute(@NotNull String text, long frameSeed) {
        return substitute(text, frameSeed, DEFAULT_POOL);
    }

    /**
     * Substitutes every character in {@code text} with a deterministic pick from a caller-provided
     * glyph pool.
     *
     * @param text the source text
     * @param frameSeed the frame-index seed
     * @param pool the glyph pool to sample from
     * @return the scrambled text
     */
    public static @NotNull String substitute(@NotNull String text, long frameSeed, @NotNull String pool) {
        if (text.isEmpty() || pool.isEmpty()) return text;

        Random random = new Random(frameSeed);
        char[] buffer = new char[text.length()];
        for (int i = 0; i < text.length(); i++) {
            char original = text.charAt(i);
            buffer[i] = Character.isWhitespace(original)
                ? original
                : pool.charAt(random.nextInt(pool.length()));
        }
        return new String(buffer);
    }

}
