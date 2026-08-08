package lib.minecraft.renderer.asset.model;

import com.google.gson.TypeAdapter;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

/**
 * The texture atlas dimensions a model's cube UVs resolve against, carried as the geometry dialect's
 * {@code texture_size:[w, h]} array. The {@link Adapter} reads and writes that two-element array form
 * so the record deserialises directly, without a pre-split of the geometry document.
 *
 * @param width the atlas width in pixels
 * @param height the atlas height in pixels
 */
@JsonAdapter(TextureSize.Adapter.class)
public record TextureSize(int width, int height) {

    /** The vanilla default atlas dimensions ({@code 64 x 64}). */
    public static final @NotNull TextureSize DEFAULT = new TextureSize(64, 64);

    /** Reads and writes the {@code [w, h]} array form. */
    static final class Adapter extends TypeAdapter<TextureSize> {

        @Override
        public void write(@NotNull JsonWriter out, TextureSize value) throws IOException {
            if (value == null) {
                out.nullValue();
                return;
            }
            out.beginArray();
            out.value(value.width());
            out.value(value.height());
            out.endArray();
        }

        @Override
        public TextureSize read(@NotNull JsonReader in) throws IOException {
            if (in.peek() == JsonToken.NULL) {
                in.nextNull();
                return null;
            }
            in.beginArray();
            int width = in.nextInt();
            int height = in.nextInt();
            in.endArray();
            return new TextureSize(width, height);
        }
    }
}
