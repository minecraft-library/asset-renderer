package lib.minecraft.renderer.content.json;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import dev.simplified.annotations.NoArgsConstructor;
import lib.minecraft.renderer.engine.geometry.EulerRotation;
import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;

/**
 * Gson adapter that serializes an {@link EulerRotation} as a three-element JSON array
 * {@code [pitch, yaw, roll]} (X/Y/Z) and deserializes from the same format - matching
 * vanilla's {@code display.*.rotation}, entity bone {@code rotation}, and every other
 * three-element Euler-angle array in the model JSON schema.
 */
@NoArgsConstructor
@Parity(claim = "tensor-math", mode = Mode.DEMOTE)
public final class EulerRotationAdapter extends TypeAdapter<EulerRotation> {

    /** {@inheritDoc} */
    @Override
    public void write(@NotNull JsonWriter out, @Nullable EulerRotation value) throws IOException {
        if (value == null) {
            out.nullValue();
            return;
        }

        out.beginArray();
        out.value(value.pitch());
        out.value(value.yaw());
        out.value(value.roll());
        out.endArray();
    }

    /** {@inheritDoc} */
    @Override
    public @Nullable EulerRotation read(@NotNull JsonReader in) throws IOException {
        if (in.peek() == JsonToken.NULL) {
            in.nextNull();
            return null;
        }

        in.beginArray();
        float pitch = (float) in.nextDouble();
        float yaw = (float) in.nextDouble();
        float roll = (float) in.nextDouble();
        in.endArray();

        return new EulerRotation(pitch, yaw, roll);
    }

}
