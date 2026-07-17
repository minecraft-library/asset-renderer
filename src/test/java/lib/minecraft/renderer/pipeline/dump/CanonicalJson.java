package lib.minecraft.renderer.pipeline.dump;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Canonical-form JSON writer for the pipeline-cleanup parity dump.
 * <p>
 * The dump's whole value rests on one property: two runs that loaded the same pipeline state must
 * produce byte-identical files. The loaded object graph does not offer that for free - it is spread
 * across hash-ordered maps, per-JVM-salted immutable collections, machine-absolute paths, and
 * megabyte pixel blobs. Every method here exists to kill one of those.
 * <p>
 * <b>Ordering.</b> {@link #write} recursively sorts every object's keys by {@link String#compareTo},
 * so a caller can build objects in whatever order reads best and still get canonical output. Arrays
 * are NEVER reordered: an array in this dump means the order is semantic (pack priority, CIT
 * first-match-wins, model elements, animation frames), and sorting one would hide a real regression
 * exactly as surely as leaving a map hash-ordered would manufacture a fake one. Callers turn
 * unordered sets into sorted arrays explicitly via {@link #strings}.
 * <p>
 * <b>Numbers.</b> Every float and double is printed through {@code (double)(float) value}, so a
 * field migrating between {@code float} and {@code double} - or a DTO re-parsing the same literal -
 * prints identically and the gate stays green across the planned DTO reshapes.
 * <p>
 * <b>Blobs.</b> {@code byte[]} is emitted as {@code {sha256, length}}: content identity without
 * putting a quarter-megabyte colormap into a diff.
 */
@UtilityClass
class CanonicalJson {

    private static final @NotNull Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .serializeSpecialFloatingPointValues()
        .disableHtmlEscaping()
        .create();

    private static final char @NotNull [] HEX = "0123456789abcdef".toCharArray();

    /**
     * Returns a number in canonical form, narrowed through {@code float} so that float-vs-double
     * field migrations print identically.
     *
     * @param value the number to emit
     * @return the canonical primitive
     */
    static @NotNull JsonPrimitive number(double value) {
        return new JsonPrimitive((double) (float) value);
    }

    /**
     * Returns an ARGB colour as a {@code 0xAARRGGBB} hex string. Loaded ARGB values are signed ints,
     * so a decimal dump prints {@code 0xFF000000} as {@code -16777216} - the exact unreadability the
     * bundled JSON avoids by storing hex in the first place.
     *
     * @param value the signed ARGB int
     * @return the canonical hex primitive
     */
    static @NotNull JsonPrimitive argb(int value) {
        return new JsonPrimitive(String.format(Locale.ROOT, "0x%08X", value));
    }

    /**
     * Returns a blob as {@code {"sha256": <hex>, "length": <n>}}. Pixel buffers and colormaps are
     * content-identified this way rather than inlined.
     *
     * @param value the bytes to digest
     * @return the digest object
     */
    static @NotNull JsonObject bytes(byte @NotNull [] value) {
        JsonObject digest = new JsonObject();
        digest.addProperty("sha256", sha256(value));
        digest.addProperty("length", value.length);
        return digest;
    }

    /**
     * Returns a path relativized against {@code base} with {@code /} separators, so a dump captured
     * on one checkout diffs against a dump captured on another. Falls back to the absolute form when
     * the path shares no root with {@code base} (a different drive letter on Windows) - that is
     * loudly visible in a diff rather than silently machine-specific.
     *
     * @param value the path to emit
     * @param base the directory to relativize against
     * @return the canonical path primitive
     */
    static @NotNull JsonPrimitive path(@NotNull Path value, @NotNull Path base) {
        Path absolute = value.toAbsolutePath().normalize();
        Path root = base.toAbsolutePath().normalize();
        try {
            return new JsonPrimitive(root.relativize(absolute).toString().replace('\\', '/'));
        } catch (IllegalArgumentException ex) {
            return new JsonPrimitive(absolute.toString().replace('\\', '/'));
        }
    }

    /**
     * Returns a string-keyed map as an object. Key order is irrelevant here because {@link #write}
     * sorts on the way out; this overload exists so callers never hand a raw hash-ordered map to the
     * writer by reflex.
     *
     * @param values the map to emit
     * @param mapper the per-value serializer
     * @param <V> the map's value type
     * @return the object
     */
    static <V> @NotNull JsonObject map(@NotNull Map<String, V> values, @NotNull Function<? super V, ? extends JsonElement> mapper) {
        return map(values, Function.identity(), mapper);
    }

    /**
     * Returns a map as an object, stringifying keys through {@code key}.
     *
     * @param values the map to emit
     * @param key the key stringifier
     * @param mapper the per-value serializer
     * @param <K> the map's key type
     * @param <V> the map's value type
     * @return the object
     */
    static <K, V> @NotNull JsonObject map(
        @NotNull Map<K, V> values,
        @NotNull Function<? super K, String> key,
        @NotNull Function<? super V, ? extends JsonElement> mapper
    ) {
        JsonObject object = new JsonObject();
        values.forEach((k, v) -> object.add(key.apply(k), mapper.apply(v)));
        return object;
    }

    /**
     * Returns a map whose ITERATION ORDER IS SEMANTIC as an array of entries, each carrying its own key
     * under {@code keyName}.
     * <p>
     * Such a map cannot be emitted as a JSON object at all: {@link #write} sorts every object's keys on
     * the way out, so an object would silently destroy the very order that has to be pinned. An array is
     * the only shape in this writer that survives with its order intact. Reach for this ONLY where the
     * order is load-bearing - a bone map whose sequence decides which face wins at tied depth - never as
     * a way to keep a map "looking like" its runtime iteration.
     *
     * @param values the order-semantic map
     * @param keyName the key under which each entry's own key is emitted
     * @param mapper the per-value serializer
     * @param <V> the map's value type
     * @return the entry array in iteration order
     */
    static <V> @NotNull JsonArray orderedMap(
        @NotNull Map<String, V> values,
        @NotNull String keyName,
        @NotNull Function<? super V, ? extends JsonObject> mapper
    ) {
        JsonArray array = new JsonArray(values.size());
        values.forEach((key, value) -> {
            JsonObject entry = mapper.apply(value);
            entry.addProperty(keyName, key);
            array.add(entry);
        });
        return array;
    }

    /**
     * Returns a collection of strings as a SORTED array. This is the explicit spelling for an
     * unordered source - a {@code Set}, or a list whose order is a hash artifact.
     *
     * @param values the strings to emit
     * @return the sorted array
     */
    static @NotNull JsonArray strings(@NotNull Collection<String> values) {
        JsonArray array = new JsonArray(values.size());
        values.stream().sorted().forEach(array::add);
        return array;
    }

    /**
     * Returns a collection as an array in ITERATION ORDER, for collections whose order carries
     * meaning - pack priority, first-match-wins rule lists, model elements, animation frames.
     *
     * @param values the collection to emit
     * @param mapper the per-element serializer
     * @param <T> the element type
     * @return the array in iteration order
     */
    static <T> @NotNull JsonArray ordered(@NotNull Collection<T> values, @NotNull Function<? super T, ? extends JsonElement> mapper) {
        JsonArray array = new JsonArray(values.size());
        values.forEach(value -> array.add(mapper.apply(value)));
        return array;
    }

    /**
     * Adds {@code key} only when the value is present. An absent {@link Optional} is an OMITTED key,
     * never a {@code null} - the dump reserves omission strictly for emptiness so that loaded
     * sentinels ({@code -1} for inherit, {@code ""} for absent) stay distinguishable from it.
     *
     * @param object the object to add to
     * @param key the key to add under
     * @param value the optional value
     * @param mapper the serializer applied when present
     * @param <T> the value type
     */
    static <T> void put(
        @NotNull JsonObject object,
        @NotNull String key,
        @NotNull Optional<T> value,
        @NotNull Function<? super T, ? extends JsonElement> mapper
    ) {
        value.ifPresent(present -> object.add(key, mapper.apply(present)));
    }

    /**
     * Writes the dump section: keys recursively sorted, pretty-printed, UTF-8, {@code \n} line
     * endings, trailing newline. Creates the parent directory when absent.
     *
     * @param file the file to write
     * @param root the section root
     * @throws IOException if the file cannot be written
     */
    static void write(@NotNull Path file, @NotNull JsonObject root) throws IOException {
        Files.createDirectories(file.getParent());
        String json = GSON.toJson(sortDeep(root)).replace("\r\n", "\n") + "\n";
        Files.writeString(file, json, StandardCharsets.UTF_8);
    }

    /**
     * Returns the SHA-256 of an element's own canonical form - a content identity for a value that is
     * shared by reference across many owners and would otherwise be inlined once per owner.
     * <p>
     * Content-derived rather than reference-derived on purpose: a phase that stops sharing an instance
     * (or starts) changes object identity without changing a single loaded value, and an identity-keyed
     * ref would report that as a diff. This one reports a diff only when the value itself moves.
     *
     * @param element the element to digest
     * @return the lowercase hex digest of its canonical form
     */
    static @NotNull String digest(@NotNull JsonElement element) {
        return sha256(text(element).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Returns a collection as an array sorted by each element's own CANONICAL CONTENT.
     * <p>
     * For the awkward third case: a source that is a {@code List} but whose order is neither semantic
     * nor stable - a rule list appended while iterating a hash-ordered key set, or a source list built
     * by walking a per-run-salted namespace set. Emitting it verbatim would flap; there is no natural
     * key to sort on, because the identity IS the whole record. Sorting by content is total,
     * deterministic, and independent of however the producer happened to order it.
     *
     * @param values the collection to emit
     * @param mapper the per-element serializer
     * @param <T> the element type
     * @return the content-sorted array
     */
    static <T> @NotNull JsonArray sortedByContent(
        @NotNull Collection<T> values,
        @NotNull Function<? super T, ? extends JsonElement> mapper
    ) {
        JsonArray array = new JsonArray(values.size());
        values.stream().map(mapper).sorted(Comparator.comparing(CanonicalJson::text)).forEach(array::add);
        return array;
    }

    /**
     * Returns an element's canonical text - the exact bytes {@link #write} would emit for it.
     *
     * @param element the element to render
     * @return the canonical text
     */
    private static @NotNull String text(@NotNull JsonElement element) {
        return GSON.toJson(sortDeep(element)).replace("\r\n", "\n");
    }

    /**
     * Returns the lowercase hex SHA-256 of the given bytes.
     *
     * @param value the bytes to digest
     * @return the lowercase hex digest
     */
    static @NotNull String sha256(byte @NotNull [] value) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required by every JDK but is unavailable", ex);
        }

        byte[] hash = digest.digest(value);
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            hex.append(HEX[(b >> 4) & 0xf]);
            hex.append(HEX[b & 0xf]);
        }
        return hex.toString();
    }

    /**
     * Returns the lowercase hex SHA-256 of a file's bytes.
     *
     * @param file the file to digest
     * @return the lowercase hex digest
     * @throws IOException if the file cannot be read
     */
    static @NotNull String sha256(@NotNull Path file) throws IOException {
        return sha256(Files.readAllBytes(file));
    }

    /**
     * Recursively rebuilds the tree with every object's keys in {@link String#compareTo} order,
     * leaving array order untouched.
     *
     * @param element the element to canonicalize
     * @return the key-sorted copy
     */
    private static @NotNull JsonElement sortDeep(@NotNull JsonElement element) {
        if (element.isJsonObject()) {
            JsonObject sorted = new JsonObject();
            element.getAsJsonObject().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> sorted.add(entry.getKey(), sortDeep(entry.getValue())));
            return sorted;
        }

        if (element.isJsonArray()) {
            JsonArray source = element.getAsJsonArray();
            JsonArray mapped = new JsonArray(source.size());
            for (JsonElement child : source)
                mapped.add(sortDeep(child));
            return mapped;
        }

        return element;
    }

}
