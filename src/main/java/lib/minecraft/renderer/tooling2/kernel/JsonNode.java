package lib.minecraft.renderer.tooling2.kernel;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.simplified.gson.GsonSettings;
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

/**
 * The unified tooling2 JSON self-builder - ONE type for building (append-as-you-go; insertion
 * order IS the byte-stability contract), null-safe reading (replacing the three legacy JSON
 * idioms), and the single write path (SPINE 5.3 / decision 36).
 *
 * <p><b>Float-only rule</b>: every fractional number is a Java {@code float} written via
 * {@link #put(String, float)} - NO {@code double}/{@code Number} overload exists, because a
 * {@code Float} and a {@code Double} serialise differently under Gson. Deliberately-integral
 * fields use the explicit {@link #putInt} channel; packed colours are {@code "0xAARRGGBB"}
 * strings via {@link #putHex} (Gson cannot round-trip {@code 0x80000000}-class ints).
 *
 * <p>Zero raw {@code new JsonObject()} exists anywhere in tooling2 flows; Gson imports are
 * legal only in the kernel and the bridge, and {@link #toGson()} is the bridge escape hatch
 * ONLY.
 */
public final class JsonNode {

    /**
     * Pretty-printing Gson with HTML escaping disabled - the single serialisation every v2
     * resource shares (same settings as the legacy shared writer, so formatting can never
     * drift between files).
     */
    private static final @NotNull Gson PRETTY =
        GsonSettings.defaults().mutate().isPrettyPrint().isHtmlEscaping(false).build().create();

    /** Plain Gson for parsing - member order preserved by Gson's LinkedTreeMap. */
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
     * A fresh v2 envelope root: the {@code //} header (generator, regen task, AND the file's
     * declared ordering source - decision 36), {@code format: 2}, and {@code source_version}
     * derived from the session's jar options [D47].
     *
     * @param session the live session (flow name + jar version)
     * @param orderingSource the declared ordering source stamped into the header
     * @return the envelope root, ready for its payload member
     */
    public static @NotNull JsonNode envelope(@NotNull ToolingSession session, @NotNull String orderingSource) {
        String flow = session.diagnostics().path();
        return object()
            .put("//", "tooling2." + flow + " · regen: ./gradlew " + flow + "2 · order: " + orderingSource)
            .putInt("format", 2)
            .put("source_version", session.options().getVersion());
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

    // ------------------------------------------------------------------------------------
    // read - replaces JsonOptional, incl. the nullable-string read it never had
    // ------------------------------------------------------------------------------------

    /**
     * Parses UTF-8 JSON bytes into a node (member order preserved).
     *
     * @param utf8 the raw JSON bytes
     * @return the parsed node
     * @throws ToolingException if the bytes are not valid JSON
     */
    public static @NotNull JsonNode parse(byte @NotNull [] utf8) {
        try {
            return new JsonNode(READ.fromJson(new String(utf8, StandardCharsets.UTF_8), JsonElement.class));
        } catch (RuntimeException ex) {
            throw new ToolingException(ex, "Failed to parse JSON (%d bytes)", utf8.length);
        }
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

    // ------------------------------------------------------------------------------------
    // io
    // ------------------------------------------------------------------------------------

    /**
     * Writes this node to {@code file} - THE single write path for every tooling2 JSON:
     * shared PRETTY Gson (HTML escaping off) terminated with the platform line separator,
     * parent directories created, path logged through the diagnostics scope.
     *
     * @param file the output path
     * @param diagnostics the scope the write is logged through
     * @throws ToolingException if the directory or file cannot be written
     */
    public void writeResource(@NotNull Path file, @NotNull Diagnostics diagnostics) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, PRETTY.toJson(this.element) + System.lineSeparator());
        } catch (IOException ex) {
            throw new ToolingException(ex, "Failed to write '%s'", file);
        }
        diagnostics.info("wrote %s", file.toAbsolutePath());
    }

    /**
     * The wrapped Gson element - the bridge escape hatch ONLY (loaders consume Gson types;
     * nothing else in tooling2 may unwrap a node).
     */
    public @NotNull JsonElement toGson() {
        return this.element;
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
