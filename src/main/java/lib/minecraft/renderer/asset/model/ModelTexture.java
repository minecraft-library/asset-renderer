package lib.minecraft.renderer.asset.model;

import com.google.gson.TypeAdapter;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

/**
 * One value of a model's {@code textures} map - a sprite reference plus the 26.1 sampler flags it
 * may carry in the object form {@code {"sprite": ..., "force_translucent": true}}.
 * <p>
 * The {@link Adapter} reads both authored shapes: a bare string flattens to
 * {@code ModelTexture(sprite, false)}, the object form to {@code {sprite, force_translucent}}. A
 * model's {@code textures} map is therefore a single {@code Map<String, ModelTexture>} whichever
 * shape each slot was written in, with no side channel.
 * <p>
 * {@link #forceTranslucent} is consumed by {@link ModelData#resolveForceTranslucentRefs}, which forces
 * the flagged face refs into the translucent pass.
 *
 * @param sprite the resolved sprite id (the object form's {@code sprite} value, or the bare string)
 * @param forceTranslucent whether the object form flagged {@code force_translucent}
 */
@JsonAdapter(ModelTexture.Adapter.class)
public record ModelTexture(@NotNull String sprite, boolean forceTranslucent) {

    /**
     * Reads the string form ({@code "minecraft:block/stone"}) or the 26.1 object form
     * ({@code {"sprite": ..., "force_translucent": true}}) into one value. A non-boolean
     * {@code force_translucent} is read defensively as {@code false} so a malformed pack flag
     * degrades to opaque rather than failing the whole model load.
     */
    static final class Adapter extends TypeAdapter<ModelTexture> {

        @Override
        public void write(@NotNull JsonWriter out, ModelTexture value) throws IOException {
            if (value == null) {
                out.nullValue();
                return;
            }
            if (!value.forceTranslucent()) {
                out.value(value.sprite());
                return;
            }
            out.beginObject();
            out.name("sprite").value(value.sprite());
            out.name("force_translucent").value(true);
            out.endObject();
        }

        @Override
        public ModelTexture read(@NotNull JsonReader in) throws IOException {
            if (in.peek() == JsonToken.NULL) {
                in.nextNull();
                return null;
            }
            if (in.peek() == JsonToken.STRING)
                return new ModelTexture(in.nextString(), false);

            String sprite = null;
            boolean forceTranslucent = false;
            in.beginObject();
            while (in.hasNext()) {
                switch (in.nextName()) {
                    case "sprite" -> sprite = in.nextString();
                    case "force_translucent" -> {
                        if (in.peek() == JsonToken.BOOLEAN) forceTranslucent = in.nextBoolean();
                        else in.skipValue();
                    }
                    default -> in.skipValue();
                }
            }
            in.endObject();
            return new ModelTexture(sprite, forceTranslucent);
        }
    }
}
