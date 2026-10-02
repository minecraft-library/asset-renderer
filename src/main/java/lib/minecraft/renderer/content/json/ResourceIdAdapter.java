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
 * Gson adapter reading a {@link ResourceId} from its {@code namespace:name} string form (via
 * {@link ResourceId#parse(String)}) and writing it back through {@link ResourceId#id()}. Registered
 * for scalar {@code ResourceId} fields on the asset DTOs - never map keys, which stay {@code String}.
 */
@NoArgsConstructor
@Parity(subject = Subject.ENGINE)
@Parity(claim = "asset-layer")
public final class ResourceIdAdapter extends TypeAdapter<ResourceId> {

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

        return ResourceId.parse(in.nextString());
    }

}
