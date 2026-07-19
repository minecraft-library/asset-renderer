package lib.minecraft.renderer.json;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.simplified.gson.GsonSettings;
import dev.simplified.gson.exception.JsonException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The unified JSON self-builder - ONE type for building (append-as-you-go; insertion order IS the
 * byte-stability contract), null-safe total reading, and the single write path. It is the neutral
 * runtime home for JSON handling, shared by the tooling flows that emit resources and the pipeline
 * loaders that read them back.
 *
 * <p><b>Float-only rule</b>: every fractional number is a Java {@code float} written via
 * {@link #put(String, float)} - NO {@code double}/{@code Number} overload exists, because a
 * {@code Float} and a {@code Double} serialise differently under Gson. Deliberately-integral
 * fields use the explicit {@link #putInt} channel; packed colours are {@code "0xAARRGGBB"}
 * strings via {@link #putHex} (Gson cannot round-trip {@code 0x80000000}-class ints).
 *
 * <p>Reads are TOTAL: an absent key or a wrong-kind node yields an empty {@link Optional}, an empty
 * stream, or the supplied default - never a throw. {@link #toGson()} is the sole escape hatch for
 * consumers that still need a raw Gson element; {@link #as(Class)} deserialises the whole node into
 * a typed DTO through the project Gson.
 */
public final class JsonNode {

    /**
     * Pretty-printing Gson with HTML escaping disabled - the single serialisation every
     * resource shares (same settings as the legacy shared writer, so formatting can never
     * drift between files).
     */
    private static final @NotNull Gson PRETTY =
        GsonSettings.defaults().mutate().isPrettyPrint().isHtmlEscaping(false).build().create();

    /** Plain Gson for parsing and DTO deserialisation - member order preserved by Gson's LinkedTreeMap. */
    private static final @NotNull Gson READ = GsonSettings.defaults().create();

    private final @NotNull JsonElement element;

    private JsonNode(@NotNull JsonElement element) {
        this.element = element;
    }

    // ------------------------------------------------------------------------------------
    // build
    // ------------------------------------------------------------------------------------

    /**
     * A fresh object node.
     */
    public static @NotNull JsonNode object() {
        return new JsonNode(new JsonObject());
    }

    /**
     * A fresh array node.
     */
    public static @NotNull JsonNode array() {
        return new JsonNode(new JsonArray());
    }

    /**
     * Adds a string member.
     *
     * @param key the member name
     * @param value the string value
     * @return this node
     */
    public @NotNull JsonNode put(@NotNull String key, @NotNull String value) {
        asObject().addProperty(key, value);
        return this;
    }

    /**
     * Adds a float member - the ONLY fractional channel (float-only rule).
     *
     * @param key the member name
     * @param value the float value
     * @return this node
     */
    public @NotNull JsonNode put(@NotNull String key, float value) {
        asObject().addProperty(key, value);
        return this;
    }

    /**
     * Adds a boolean member.
     *
     * @param key the member name
     * @param value the boolean value
     * @return this node
     */
    public @NotNull JsonNode put(@NotNull String key, boolean value) {
        asObject().addProperty(key, value);
        return this;
    }

    /**
     * Adds a nested node member.
     *
     * @param key the member name
     * @param value the node to nest
     * @return this node
     */
    public @NotNull JsonNode put(@NotNull String key, @NotNull JsonNode value) {
        asObject().add(key, value.element);
        return this;
    }

    /**
     * Adds a deliberately-integral member (uv, texture_size, offsets, rotation, layer_index,
     * format, atlas ints).
     *
     * @param key the member name
     * @param value the int value
     * @return this node
     */
    public @NotNull JsonNode putInt(@NotNull String key, int value) {
        asObject().addProperty(key, value);
        return this;
    }

    /**
     * Adds a packed ARGB colour as the {@code 0xAARRGGBB} uppercase-hex string.
     *
     * @param key the member name
     * @param argb the packed ARGB colour
     * @return this node
     */
    public @NotNull JsonNode putHex(@NotNull String key, int argb) {
        asObject().addProperty(key, String.format("0x%08X", argb));
        return this;
    }

    /**
     * Adds a nested node member only when {@code value} is non-null (the empty-vs-absent
     * rule: null means the key is omitted).
     *
     * @param key the member name
     * @param value the node, or {@code null} to omit
     * @return this node
     */
    public @NotNull JsonNode putIf(@NotNull String key, @Nullable JsonNode value) {
        if (value != null) asObject().add(key, value.element);
        return this;
    }

    /**
     * Adds a string member only when {@code value} is non-null.
     *
     * @param key the member name
     * @param value the string, or {@code null} to omit
     * @return this node
     */
    public @NotNull JsonNode putIf(@NotNull String key, @Nullable String value) {
        if (value != null) asObject().addProperty(key, value);
        return this;
    }

    /**
     * Adds a float member only when it differs from its default (mirror / grow / scale
     * omit-at-default emission).
     *
     * @param key the member name
     * @param value the float value
     * @param dflt the default the member is omitted at
     * @return this node
     */
    public @NotNull JsonNode putUnless(@NotNull String key, float value, float dflt) {
        if (value != dflt) asObject().addProperty(key, value);
        return this;
    }

    /**
     * Adds a float array member.
     *
     * @param key the member name
     * @param values the float values in order
     * @return this node
     */
    public @NotNull JsonNode putFloats(@NotNull String key, float @NotNull ... values) {
        JsonArray array = new JsonArray(values.length);
        for (float value : values) array.add(value);
        asObject().add(key, array);
        return this;
    }

    /**
     * Adds an int array member (the integral channel's array form).
     *
     * @param key the member name
     * @param values the int values in order
     * @return this node
     */
    public @NotNull JsonNode putInts(@NotNull String key, int @NotNull ... values) {
        JsonArray array = new JsonArray(values.length);
        for (int value : values) array.add(value);
        asObject().add(key, array);
        return this;
    }

    /**
     * Adds a string array member.
     *
     * @param key the member name
     * @param values the string values in order
     * @return this node
     */
    public @NotNull JsonNode putStrings(@NotNull String key, @NotNull String @NotNull ... values) {
        JsonArray array = new JsonArray(values.length);
        for (String value : values) array.add(value);
        asObject().add(key, array);
        return this;
    }

    /**
     * Opens the nested object under {@code key}, creating it on first use - the
     * append-as-you-go hook.
     *
     * @param key the member name
     * @return the nested object node
     */
    public @NotNull JsonNode child(@NotNull String key) {
        JsonObject object = asObject();
        JsonElement existing = object.get(key);
        if (existing == null) {
            JsonObject created = new JsonObject();
            object.add(key, created);
            return new JsonNode(created);
        }
        return new JsonNode(existing);
    }

    /**
     * Opens the nested array under {@code key}, creating it on first use.
     *
     * @param key the member name
     * @return the nested array node
     */
    public @NotNull JsonNode childArray(@NotNull String key) {
        JsonObject object = asObject();
        JsonElement existing = object.get(key);
        if (existing == null) {
            JsonArray created = new JsonArray();
            object.add(key, created);
            return new JsonNode(created);
        }
        return new JsonNode(existing);
    }

    /**
     * Appends a node to this array node.
     *
     * @param entry the node to append
     * @return this node
     */
    public @NotNull JsonNode add(@NotNull JsonNode entry) {
        asArray().add(entry.element);
        return this;
    }

    /**
     * Appends a string to this array node.
     *
     * @param entry the string to append
     * @return this node
     */
    public @NotNull JsonNode add(@NotNull String entry) {
        asArray().add(entry);
        return this;
    }

    /**
     * Appends a float to this array node.
     *
     * @param entry the float to append
     * @return this node
     */
    public @NotNull JsonNode add(float entry) {
        asArray().add(entry);
        return this;
    }

    /**
     * Shallow-merges another object node's members into this object - each top-level key is added
     * later-wins, with the source value deep-copied so the two trees never alias.
     *
     * @param other the object node whose members to merge in
     * @return this node
     */
    public @NotNull JsonNode putAll(@NotNull JsonNode other) {
        JsonObject target = asObject();
        for (Map.Entry<String, JsonElement> entry : other.asObject().entrySet())
            target.add(entry.getKey(), entry.getValue().deepCopy());
        return this;
    }

    /**
     * A deep copy of this node, structurally independent of the original.
     *
     * @return the deep-copied node
     */
    public @NotNull JsonNode deepCopy() {
        return new JsonNode(this.element.deepCopy());
    }

    // ------------------------------------------------------------------------------------
    // read - total: absent / wrong-kind yields empty or the supplied default, never a throw
    // ------------------------------------------------------------------------------------

    /**
     * Wraps an existing Gson element as a node - the sanctioned bridge for callers that assemble
     * Gson trees directly (the geometry parser's output edge).
     *
     * @param element the element to wrap
     * @return the wrapping node
     */
    public static @NotNull JsonNode wrap(@NotNull JsonElement element) {
        return new JsonNode(element);
    }

    /**
     * Parses UTF-8 JSON bytes into a node (member order preserved).
     *
     * @param utf8 the raw JSON bytes
     * @return the parsed node
     * @throws JsonException if the bytes are not valid JSON
     */
    public static @NotNull JsonNode parse(byte @NotNull [] utf8) {
        try {
            return new JsonNode(READ.fromJson(new String(utf8, StandardCharsets.UTF_8), JsonElement.class));
        } catch (RuntimeException ex) {
            throw new JsonException(ex, "Failed to parse JSON (%d bytes)", utf8.length);
        }
    }

    /**
     * Whether this object node carries a member under {@code key}.
     *
     * @param key the member name
     * @return {@code true} when this is an object with that member
     */
    public boolean has(@NotNull String key) {
        return this.element instanceof JsonObject object && object.has(key);
    }

    /** Whether this node is a JSON object. */
    public boolean isObject() {
        return this.element instanceof JsonObject;
    }

    /** Whether this node is a JSON array. */
    public boolean isArray() {
        return this.element instanceof JsonArray;
    }

    /** Whether this node is a JSON primitive (string, number, or boolean). */
    public boolean isPrimitive() {
        return this.element.isJsonPrimitive();
    }

    /**
     * The element count - an array's length or an object's member count, {@code 0} for a primitive.
     *
     * @return the child count
     */
    public int size() {
        if (this.element instanceof JsonArray array) return array.size();
        if (this.element instanceof JsonObject object) return object.size();
        return 0;
    }

    /**
     * The string member under {@code key}, or {@code null} when absent.
     *
     * @param key the member name
     * @return the string value, or {@code null}
     */
    public @Nullable String getString(@NotNull String key) {
        JsonElement value = asObject().get(key);
        return value == null || !value.isJsonPrimitive() ? null : value.getAsString();
    }

    /**
     * The string member under {@code key}, or {@code dflt} when absent.
     *
     * @param key the member name
     * @param dflt the default
     * @return the string value, or {@code dflt}
     */
    public @NotNull String getString(@NotNull String key, @NotNull String dflt) {
        String value = getString(key);
        return value != null ? value : dflt;
    }

    /**
     * The float member under {@code key}, or {@code dflt} when absent.
     *
     * @param key the member name
     * @param dflt the default
     * @return the float value, or {@code dflt}
     */
    public float getFloat(@NotNull String key, float dflt) {
        JsonElement value = asObject().get(key);
        return value == null || !value.isJsonPrimitive() ? dflt : value.getAsFloat();
    }

    /**
     * The int member under {@code key}, or {@code dflt} when absent.
     *
     * @param key the member name
     * @param dflt the default
     * @return the int value, or {@code dflt}
     */
    public int getInt(@NotNull String key, int dflt) {
        JsonElement value = asObject().get(key);
        return value == null || !value.isJsonPrimitive() ? dflt : value.getAsInt();
    }

    /**
     * The boolean member under {@code key}, or {@code dflt} when absent.
     *
     * @param key the member name
     * @param dflt the default
     * @return the boolean value, or {@code dflt}
     */
    public boolean getBool(@NotNull String key, boolean dflt) {
        JsonElement value = asObject().get(key);
        return value == null || !value.isJsonPrimitive() ? dflt : value.getAsBoolean();
    }

    /**
     * The node member under {@code key}, or {@code null} when absent.
     *
     * @param key the member name
     * @return the nested node, or {@code null}
     */
    public @Nullable JsonNode get(@NotNull String key) {
        JsonElement value = asObject().get(key);
        return value == null ? null : new JsonNode(value);
    }

    /**
     * The member under {@code key} as a node, present only when this is an object carrying it.
     *
     * @param key the member name
     * @return the member node, or empty
     */
    public @NotNull Optional<JsonNode> find(@NotNull String key) {
        JsonElement member = memberOrNull(key);
        return member == null ? Optional.empty() : Optional.of(new JsonNode(member));
    }

    /**
     * The member under {@code key} as a node, present only when it is itself an object.
     *
     * @param key the member name
     * @return the object member node, or empty
     */
    public @NotNull Optional<JsonNode> findObject(@NotNull String key) {
        JsonElement member = memberOrNull(key);
        return member instanceof JsonObject ? Optional.of(new JsonNode(member)) : Optional.empty();
    }

    /**
     * The member under {@code key} as a node, present only when it is itself an array.
     *
     * @param key the member name
     * @return the array member node, or empty
     */
    public @NotNull Optional<JsonNode> findArray(@NotNull String key) {
        JsonElement member = memberOrNull(key);
        return member instanceof JsonArray ? Optional.of(new JsonNode(member)) : Optional.empty();
    }

    /**
     * The string member under {@code key}, present only when it is a primitive.
     *
     * @param key the member name
     * @return the string value, or empty
     */
    public @NotNull Optional<String> findString(@NotNull String key) {
        JsonElement member = memberOrNull(key);
        return member != null && member.isJsonPrimitive() ? Optional.of(member.getAsString()) : Optional.empty();
    }

    /**
     * The float member under {@code key}, present only when it is a numeric primitive.
     *
     * @param key the member name
     * @return the float value, or empty
     */
    public @NotNull Optional<Float> findFloat(@NotNull String key) {
        JsonElement member = memberOrNull(key);
        return isNumber(member) ? Optional.of(member.getAsFloat()) : Optional.empty();
    }

    /**
     * The int member under {@code key}, present only when it is a numeric primitive.
     *
     * @param key the member name
     * @return the int value, or empty
     */
    public @NotNull Optional<Integer> findInt(@NotNull String key) {
        JsonElement member = memberOrNull(key);
        return isNumber(member) ? Optional.of(member.getAsInt()) : Optional.empty();
    }

    /**
     * The boolean member under {@code key}, present only when it is a boolean primitive.
     *
     * @param key the member name
     * @return the boolean value, or empty
     */
    public @NotNull Optional<Boolean> findBool(@NotNull String key) {
        JsonElement member = memberOrNull(key);
        return member != null && member.isJsonPrimitive() && member.getAsJsonPrimitive().isBoolean()
            ? Optional.of(member.getAsBoolean()) : Optional.empty();
    }

    /**
     * This primitive node as a string, present only when this is a primitive.
     *
     * @return the string value, or empty
     */
    public @NotNull Optional<String> stringValue() {
        return this.element.isJsonPrimitive() ? Optional.of(this.element.getAsString()) : Optional.empty();
    }

    /**
     * This primitive node as an int, present only when this is a numeric primitive.
     *
     * @return the int value, or empty
     */
    public @NotNull Optional<Integer> intValue() {
        return isNumber(this.element) ? Optional.of(this.element.getAsInt()) : Optional.empty();
    }

    /**
     * This primitive node as a boolean, present only when this is a boolean primitive.
     *
     * @return the boolean value, or empty
     */
    public @NotNull Optional<Boolean> boolValue() {
        return this.element.isJsonPrimitive() && this.element.getAsJsonPrimitive().isBoolean()
            ? Optional.of(this.element.getAsBoolean()) : Optional.empty();
    }

    /**
     * The array element at {@code index}, or {@code null} when this node is not an array or the
     * index is out of range.
     *
     * @param index the zero-based element index
     * @return the element node, or {@code null}
     */
    public @Nullable JsonNode at(int index) {
        if (!(this.element instanceof JsonArray array) || index < 0 || index >= array.size()) return null;
        return new JsonNode(array.get(index));
    }

    /**
     * This primitive node as a float, or {@code dflt} when it is not a JSON number.
     *
     * @param dflt the default
     * @return the float value, or {@code dflt}
     */
    public float floatValue(float dflt) {
        return this.element.isJsonPrimitive() && this.element.getAsJsonPrimitive().isNumber()
            ? this.element.getAsFloat() : dflt;
    }

    /**
     * The int elements of the array member under {@code key}, non-numeric entries skipped; empty
     * when the member is absent or not an array.
     *
     * @param key the member name
     * @return the int values in order
     */
    public @NotNull List<Integer> getInts(@NotNull String key) {
        List<Integer> out = new ArrayList<>();
        if (memberOrNull(key) instanceof JsonArray array)
            for (JsonElement entry : array) if (isNumber(entry)) out.add(entry.getAsInt());
        return out;
    }

    /**
     * The float elements of the array member under {@code key}, non-numeric entries skipped; empty
     * when the member is absent or not an array.
     *
     * @param key the member name
     * @return the float values in order
     */
    public @NotNull List<Float> getFloats(@NotNull String key) {
        List<Float> out = new ArrayList<>();
        if (memberOrNull(key) instanceof JsonArray array)
            for (JsonElement entry : array) if (isNumber(entry)) out.add(entry.getAsFloat());
        return out;
    }

    /**
     * The string elements of the array member under {@code key}, non-primitive entries skipped;
     * empty when the member is absent or not an array.
     *
     * @param key the member name
     * @return the string values in order
     */
    public @NotNull List<String> getStrings(@NotNull String key) {
        List<String> out = new ArrayList<>();
        if (memberOrNull(key) instanceof JsonArray array)
            for (JsonElement entry : array) if (entry.isJsonPrimitive()) out.add(entry.getAsString());
        return out;
    }

    /**
     * This array node's elements, wrapped, in order.
     */
    public @NotNull Iterable<JsonNode> elements() {
        List<JsonNode> out = new ArrayList<>();
        for (JsonElement entry : asArray()) out.add(new JsonNode(entry));
        return out;
    }

    /**
     * This object node's members, wrapped, in insertion order.
     */
    public @NotNull Iterable<Map.Entry<String, JsonNode>> members() {
        List<Map.Entry<String, JsonNode>> out = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : asObject().entrySet())
            out.add(new AbstractMap.SimpleImmutableEntry<>(entry.getKey(), new JsonNode(entry.getValue())));
        return out;
    }

    /**
     * This array node's elements as a stream, empty when this node is not an array (skip-not-abort).
     *
     * @return the element nodes in order
     */
    public @NotNull Stream<JsonNode> stream() {
        if (!(this.element instanceof JsonArray array)) return Stream.empty();
        List<JsonNode> out = new ArrayList<>(array.size());
        for (JsonElement entry : array) out.add(new JsonNode(entry));
        return out.stream();
    }

    /**
     * This object node's members as a stream, in insertion order, empty when this node is not an
     * object (skip-not-abort).
     *
     * @return the member entries in insertion order
     */
    public @NotNull Stream<Map.Entry<String, JsonNode>> memberStream() {
        if (!(this.element instanceof JsonObject object)) return Stream.empty();
        List<Map.Entry<String, JsonNode>> out = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : object.entrySet())
            out.add(new AbstractMap.SimpleImmutableEntry<>(entry.getKey(), new JsonNode(entry.getValue())));
        return out.stream();
    }

    /**
     * This object node's keys, in insertion order, empty when this node is not an object.
     *
     * @return the member keys in insertion order
     */
    public @NotNull Stream<String> keys() {
        if (!(this.element instanceof JsonObject object)) return Stream.empty();
        return new ArrayList<>(object.keySet()).stream();
    }

    // ------------------------------------------------------------------------------------
    // io
    // ------------------------------------------------------------------------------------

    /**
     * Writes this node to {@code file} - THE single write path for every emitted JSON: shared PRETTY
     * Gson (HTML escaping off) terminated with the platform line separator, parent directories
     * created. Callers that want a wrote-log emit it themselves.
     *
     * @param file the output path
     * @throws JsonException if the directory or file cannot be written
     */
    public void write(@NotNull Path file) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, PRETTY.toJson(this.element) + System.lineSeparator());
        } catch (IOException ex) {
            throw new JsonException(ex, "Failed to write '%s'", file);
        }
    }

    /**
     * Deserialises this whole node into a typed DTO through the project Gson.
     *
     * @param type the DTO class
     * @param <T> the DTO type
     * @return the deserialised DTO
     */
    public <T> T as(@NotNull Class<T> type) {
        return READ.fromJson(this.element, type);
    }

    /**
     * The wrapped Gson element - the escape hatch for consumers that need Gson types directly.
     */
    public @NotNull JsonElement toGson() {
        return this.element;
    }

    private @Nullable JsonElement memberOrNull(@NotNull String key) {
        return this.element instanceof JsonObject object ? object.get(key) : null;
    }

    private static boolean isNumber(@Nullable JsonElement element) {
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber();
    }

    private @NotNull JsonObject asObject() {
        if (!(this.element instanceof JsonObject object))
            throw new IllegalStateException("Not an object node: " + this.element.getClass().getSimpleName());
        return object;
    }

    private @NotNull JsonArray asArray() {
        if (!(this.element instanceof JsonArray array))
            throw new IllegalStateException("Not an array node: " + this.element.getClass().getSimpleName());
        return array;
    }

}
