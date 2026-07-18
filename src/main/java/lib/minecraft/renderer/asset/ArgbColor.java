package lib.minecraft.renderer.asset;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import lombok.NoArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.OptionalInt;

/**
 * An immutable packed ARGB colour - one {@code 0xAARRGGBB} int - and the single home for the ARGB
 * hex-string policy.
 * <p>
 * {@link #parse(String)} accepts {@code 0x} / {@code #} / bare hex in 6- or 8-digit lengths: a value of
 * 6 or fewer digits is forced fully opaque (alpha {@code FF}), a longer value carries its own alpha. A
 * malformed value falls back to opaque {@link #WHITE} - the no-op {@code MULTIPLY} tint - so a typo
 * never tints a subject black. {@link #tryParse(String)} exposes the same parse without the fallback so
 * a caller that must warn on a malformed value can detect it.
 * <p>
 * Used as a reflective Gson map value (potion / block tints) and a scalar tint field, decoded through
 * {@link Adapter}.
 *
 * @param argb the packed {@code 0xAARRGGBB} value
 */
public record ArgbColor(int argb) {

    /** Opaque white - the malformed-value fallback and the no-op {@code MULTIPLY} tint. */
    public static final @NotNull ArgbColor WHITE = new ArgbColor(0xFFFFFFFF);

    /**
     * Parses an ARGB hex string, falling back to {@link #WHITE} when the value is malformed.
     *
     * @param hex the colour string in {@code 0xAARRGGBB} / {@code #RRGGBB} / bare form
     * @return the parsed colour, or {@link #WHITE} when {@code hex} is not valid hex
     */
    public static @NotNull ArgbColor parse(@NotNull String hex) {
        OptionalInt value = tryParse(hex);
        return value.isPresent() ? new ArgbColor(value.getAsInt()) : WHITE;
    }

    /**
     * Attempts to parse an ARGB hex string, returning empty rather than the {@link #WHITE} fallback
     * when the value is malformed - so a caller that must warn on a malformed value can detect it.
     *
     * @param hex the colour string in {@code 0xAARRGGBB} / {@code #RRGGBB} / bare form
     * @return the packed ARGB int, or empty when {@code hex} is not valid hex
     */
    public static @NotNull OptionalInt tryParse(@NotNull String hex) {
        String digits = hex.startsWith("0x") || hex.startsWith("0X") ? hex.substring(2)
            : hex.startsWith("#") ? hex.substring(1)
            : hex;
        try {
            long value = Long.parseLong(digits, 16);
            if (digits.length() <= 6) value |= 0xFF000000L;
            return OptionalInt.of((int) value);
        } catch (NumberFormatException ex) {
            return OptionalInt.empty();
        }
    }

    /**
     * Gson adapter reading an ARGB colour from its hex-string form (via {@link #parse(String)}) and
     * writing it back as {@code 0xAARRGGBB}. A malformed value silently decodes to {@link #WHITE}.
     */
    @NoArgsConstructor
    public static final class Adapter extends TypeAdapter<ArgbColor> {

        @Override
        public void write(@NotNull JsonWriter out, @Nullable ArgbColor value) throws IOException {
            if (value == null) {
                out.nullValue();
                return;
            }

            out.value(String.format("0x%08X", value.argb));
        }

        @Override
        public @Nullable ArgbColor read(@NotNull JsonReader in) throws IOException {
            if (in.peek() == JsonToken.NULL) {
                in.nextNull();
                return null;
            }

            return parse(in.nextString());
        }

    }

}
