package lib.minecraft.renderer.content.json;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import dev.simplified.annotations.NoArgsConstructor;
import lib.minecraft.renderer.math.Vector4f;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;

/**
 * Gson adapter that serializes a {@link Vector4f} as a four-element JSON array
 * {@code [x, y, z, w]} and deserializes from the same format.
 */
@NoArgsConstructor
@Parity(subject = Subject.ENGINE)
public final class Vector4fAdapter extends TypeAdapter<Vector4f> {

    /** {@inheritDoc} */
    @Override
    public void write(@NotNull JsonWriter out, @Nullable Vector4f value) throws IOException {
        if (value == null) {
            out.nullValue();
            return;
        }

        out.beginArray();
        out.value(value.x());
        out.value(value.y());
        out.value(value.z());
        out.value(value.w());
        out.endArray();
    }

    /** {@inheritDoc} */
    @Override
    public @Nullable Vector4f read(@NotNull JsonReader in) throws IOException {
        if (in.peek() == JsonToken.NULL) {
            in.nextNull();
            return null;
        }

        in.beginArray();
        float x = (float) in.nextDouble();
        float y = (float) in.nextDouble();
        float z = (float) in.nextDouble();
        float w = (float) in.nextDouble();
        in.endArray();

        return new Vector4f(x, y, z, w);
    }

}
