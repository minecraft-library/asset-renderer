package lib.minecraft.renderer.content.json;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import lib.minecraft.renderer.engine.math.Vector3f;
import lib.minecraft.renderer.parity.Parity;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

/**
 * Reads the geometry {@code grow} value into a cube's grow vector: a scalar {@code g} broadcasts to
 * {@code (g, g, g)}, an {@code [x, y, z]} array is read per-axis. Writes the uniform form back as a
 * scalar so a round-trip stays byte-compatible with the tooling emit.
 */
@Parity(claim = "asset-layer")
public final class CubeGrowAdapter extends TypeAdapter<Vector3f> {

    @Override
    public void write(@NotNull JsonWriter out, Vector3f value) throws IOException {
        if (value == null) {
            out.nullValue();
            return;
        }
        if (value.x() == value.y() && value.y() == value.z()) {
            out.value(value.x());
            return;
        }
        out.beginArray();
        out.value(value.x());
        out.value(value.y());
        out.value(value.z());
        out.endArray();
    }

    @Override
    public @NotNull Vector3f read(@NotNull JsonReader in) throws IOException {
        if (in.peek() == JsonToken.NULL) {
            in.nextNull();
            return Vector3f.ZERO;
        }
        if (in.peek() == JsonToken.BEGIN_ARRAY) {
            in.beginArray();
            float x = (float) in.nextDouble();
            float y = (float) in.nextDouble();
            float z = (float) in.nextDouble();
            in.endArray();
            return new Vector3f(x, y, z);
        }
        float g = (float) in.nextDouble();
        return new Vector3f(g, g, g);
    }

}
