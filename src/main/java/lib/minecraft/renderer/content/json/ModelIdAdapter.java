package lib.minecraft.renderer.content.json;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import dev.simplified.annotations.NoArgsConstructor;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;

/**
 * Gson adapter reading a {@link ResourceId} from a namespaced model id (via
 * {@link ResourceId#ofModelId(String)}), collapsing {@code namespace:block/name} to
 * {@code (namespace, name)}. Applied per field with {@code @JsonAdapter} where a DTO carries a
 * model-id-dialect id; it is not registered globally, so a plain {@code ResourceId} field goes
 * through {@link ResourceIdAdapter}.
 */
@NoArgsConstructor
@Parity(subject = Subject.ENGINE)
@Parity(claim = "asset-layer")
public final class ModelIdAdapter extends TypeAdapter<ResourceId> {

    @Override
    public void write(@NotNull JsonWriter out, @Nullable ResourceId value) throws IOException {
        if (value == null) {
            out.nullValue();
            return;
        }

        out.value(value.id());
    }

    @Override
    public @Nullable ResourceId read(@NotNull JsonReader in) throws IOException {
        if (in.peek() == JsonToken.NULL) {
            in.nextNull();
            return null;
        }

        return ResourceId.ofModelId(in.nextString());
    }

}
