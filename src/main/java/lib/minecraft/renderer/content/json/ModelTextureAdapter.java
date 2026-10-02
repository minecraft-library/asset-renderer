package lib.minecraft.renderer.content.json;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import lib.minecraft.renderer.asset.model.ModelTexture;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

/**
 * Reads a {@link ModelTexture}'s string form ({@code "minecraft:block/stone"}) or its 26.1 object
 * form ({@code {"sprite": ..., "force_translucent": true}}) into one value. A non-boolean
 * {@code force_translucent} is read defensively as {@code false} so a malformed pack flag degrades
 * to opaque rather than failing the whole model load.
 */
@Parity(subject = Subject.ENGINE)
@Parity(claim = "asset-layer")
public final class ModelTextureAdapter extends TypeAdapter<ModelTexture> {

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
