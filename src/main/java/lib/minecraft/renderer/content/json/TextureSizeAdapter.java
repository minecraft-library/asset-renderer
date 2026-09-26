package lib.minecraft.renderer.content.json;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import lib.minecraft.renderer.asset.mesh.TextureSize;
import lib.minecraft.renderer.parity.Parity;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

/**
 * Reads and writes a {@link TextureSize}'s {@code [w, h]} array form.
 */
@Parity(claim = "asset-layer")
public final class TextureSizeAdapter extends TypeAdapter<TextureSize> {

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
