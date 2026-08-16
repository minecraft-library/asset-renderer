package lib.minecraft.renderer.pipeline.pack;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.parity.Parity;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a multipart {@code "when"} condition into a {@link Block.Multipart.When}. An {@code "AND"}
 * array becomes {@link Block.Multipart.When.All}, an {@code "OR"} array a {@link Block.Multipart.When.Any}
 * (each recursively deserialised through the context), and any other object a
 * {@link Block.Multipart.When.Match} whose values are {@code |}-split into their allowed sets.
 * {@code AND} is checked first and {@code OR} second, matching the render-time precedence: a
 * {@code when} carrying {@code AND} ignores any sibling {@code OR} or plain property.
 * <p>
 * Registered globally so {@code context.deserialize(element, }{@link Block.Multipart.When}{@code .class)}
 * resolves it both from the multipart-part read and from the recursive {@code AND} / {@code OR} terms.
 * Deliberately carries no static state (no {@code Gson} field) so instantiating it during
 * {@code GsonSettings.defaults()} assembly never re-enters the builder.
 */
@Parity(claim = "blockstate-multipart")
public final class MultipartWhenDeserializer implements JsonDeserializer<Block.Multipart.When> {

    @Override
    public @NotNull Block.Multipart.When deserialize(@NotNull JsonElement json, @NotNull Type type, @NotNull JsonDeserializationContext context) {
        JsonObject object = json.getAsJsonObject();

        if (object.has("AND")) return new Block.Multipart.When.All(terms(object.get("AND"), context));
        if (object.has("OR")) return new Block.Multipart.When.Any(terms(object.get("OR"), context));

        LinkedHashMap<String, List<String>> required = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : object.entrySet())
            required.put(entry.getKey(), List.of(string(entry.getValue()).split("\\|")));
        return new Block.Multipart.When.Match(required);
    }

    /**
     * Deserialises the sub-condition array of an {@code "AND"} / {@code "OR"} form into an ordered list
     * of {@link Block.Multipart.When}, recursing through the context. Non-object elements are skipped;
     * a non-array term yields an empty list.
     */
    private static @NotNull List<Block.Multipart.When> terms(@NotNull JsonElement terms, @NotNull JsonDeserializationContext context) {
        ArrayList<Block.Multipart.When> parsed = new ArrayList<>();
        if (terms.isJsonArray())
            for (JsonElement term : terms.getAsJsonArray())
                if (term.isJsonObject()) parsed.add(context.deserialize(term, Block.Multipart.When.class));
        return parsed;
    }

    /**
     * The primitive value as a string, or {@code ""} for a non-primitive - matching the former
     * {@code stringValue().orElse("")} hand parse, so a non-string match value splits to a single
     * empty allowed value.
     */
    private static @NotNull String string(@NotNull JsonElement value) {
        return value.isJsonPrimitive() ? value.getAsString() : "";
    }

}
