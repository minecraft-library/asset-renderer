package lib.minecraft.renderer.content.json;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import dev.simplified.annotations.NoArgsConstructor;
import lib.minecraft.renderer.math.Vector2f;
import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;

/**
 * Gson adapter that serializes a {@link Vector2f} as a two-element JSON array
 * {@code [x, y]} and deserializes from the same format.
 */
@NoArgsConstructor
@Parity(subject = Subject.ENGINE)
@Parity(claim = "tensor-math", mode = Mode.DEMOTE)
public final class Vector2fAdapter extends TypeAdapter<Vector2f> {

    /** {@inheritDoc} */
    @Override
    public void write(@NotNull JsonWriter out, @Nullable Vector2f value) throws IOException {
        if (value == null) {
            out.nullValue();
            return;
        }

        out.beginArray();
        out.value(value.x());
        out.value(value.y());
        out.endArray();
    }

    /** {@inheritDoc} */
    @Override
    public @Nullable Vector2f read(@NotNull JsonReader in) throws IOException {
        if (in.peek() == JsonToken.NULL) {
            in.nextNull();
            return null;
        }

        in.beginArray();
        float x = (float) in.nextDouble();
        float y = (float) in.nextDouble();
        in.endArray();

        return new Vector2f(x, y);
    }

}
