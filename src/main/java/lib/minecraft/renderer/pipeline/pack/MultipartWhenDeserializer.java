package lib.minecraft.renderer.pipeline.pack;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Type;
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
 *
 * <p><b>Parity.</b> Registered by a service file and reached only through the contributor that
 * names it, so no constant pool carries an edge to it. It parses the multipart condition every
 * block variant is selected by.
 */
@Parity(claim = "blockstate-multipart")
@Parity(subject = {Subject.BLOCK, Subject.ENTITY, Subject.ITEM, Subject.MENU})
public final class MultipartWhenDeserializer implements JsonDeserializer<Block.Multipart.When> {

    @Override
    public @NotNull Block.Multipart.When deserialize(@NotNull JsonElement json, @NotNull Type type, @NotNull JsonDeserializationContext context) {
        JsonObject object = json.getAsJsonObject();

        if (object.has("AND")) return new Block.Multipart.When.All(terms(object.get("AND"), context));
        if (object.has("OR")) return new Block.Multipart.When.Any(terms(object.get("OR"), context));

        return new Block.Multipart.When.Match(object.entrySet()
            .stream()
            .collect(Concurrent.toUnmodifiableLinkedMap(
                Map.Entry::getKey,
                entry -> Concurrent.newUnmodifiableList(string(entry.getValue()).split("\\|")))));
    }

    /**
     * Deserialises the sub-condition array of an {@code "AND"} / {@code "OR"} form into an ordered list
     * of {@link Block.Multipart.When}, recursing through the context. Non-object elements are skipped;
     * a non-array term yields an empty list.
     */
    private static @NotNull ConcurrentList<Block.Multipart.When> terms(@NotNull JsonElement terms, @NotNull JsonDeserializationContext context) {
        if (!terms.isJsonArray()) return Concurrent.newUnmodifiableList();
        return terms.getAsJsonArray()
            .asList()
            .stream()
            .filter(JsonElement::isJsonObject)
            .map(term -> context.<Block.Multipart.When>deserialize(term, Block.Multipart.When.class))
            .collect(Concurrent.toWideUnmodifiableList());
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
