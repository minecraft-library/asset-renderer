package lib.minecraft.renderer.parity;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.internal.LazilyParsedNumber;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;

/**
 * The store's canonical writer, and the only one.
 *
 * <p><b>store-canonical</b> is nine clauses: LF only; UTF-8 with no BOM and exactly one trailing
 * newline; every formatted number under {@code Locale.ROOT}; every object's keys sorted recursively
 * by {@link String#compareTo}; no array ever reordered; every key a frozen schema constant rather
 * than a Java field name or an enum's {@code name()}; absence an omitted key rather than a
 * {@code null}; two-space pretty printing; HTML escaping off.
 *
 * <p>An array is never reordered because an array means the order is semantic. A map whose iteration
 * order is semantic is emitted as an array of entries, never as an object - an object would have its
 * keys sorted on the way out and the very order being pinned would be destroyed silently.
 *
 * <p><b>Numbers are spelled by kind of quantity, not by call site.</b> A metric is fixed scale 4, so
 * it is greppable against every number ever quoted for it; a count is a plain integer; a captured
 * float is {@link Float#toString}, which is shortest-round-trip exact so the stored characters
 * recover the identical float; a CRC or an ARGB is an uppercase {@code 0x} string, because it is a
 * bit pattern rather than a quantity; a digest is lowercase hex. Deciding this per accessor instead
 * is what produced one dump printing {@code 0.4000000059604645} where another printed {@code 30.0}.
 */
public final class ParityJson {

    private static final @NotNull Gson PRETTY = new GsonBuilder()
        .setPrettyPrinting()
        .serializeSpecialFloatingPointValues()
        .disableHtmlEscaping()
        .create();

    private static final @NotNull Gson COMPACT = new GsonBuilder()
        .serializeSpecialFloatingPointValues()
        .disableHtmlEscaping()
        .create();

    /** The scale every parity metric has ever been quoted at. */
    private static final int METRIC_SCALE = 4;

    private ParityJson() {}

    /**
     * Writes a store file in canonical form: keys recursively sorted, pretty-printed, UTF-8, LF, one
     * trailing newline. Creates the parent directory when absent.
     *
     * @param file the file to write
     * @param root the artifact envelope
     * @throws IOException if the file cannot be written
     */
    public static void write(@NotNull Path file, @NotNull JsonObject root) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text(root) + "\n", StandardCharsets.UTF_8);
    }

    /**
     * Writes a store file whose one array payload is emitted compact, one element per line.
     *
     * <p>The single variant on {@link #write}, and it exists for two measured reasons: a 1055-row
     * sweep is about a third the size one-row-per-line as pretty, and one row per line makes
     * {@code git diff} on the promoted file <em>be</em> the mover list rather than a wall of
     * re-indentation. The result is still ordinary JSON that any parser reads without knowing
     * anything about the layout.
     *
     * @param file the file to write
     * @param root the artifact envelope, which must carry the payload array under {@code arrayKey}
     * @param arrayKey the payload key whose elements go one per line
     * @throws IOException if the file cannot be written
     */
    public static void writeTable(@NotNull Path file, @NotNull JsonObject root, @NotNull String arrayKey) throws IOException {
        JsonElement payload = root.get(arrayKey);
        if (payload == null || !payload.isJsonArray())
            throw new ParityStoreException("'%s' is not an array in the envelope being written to '%s'", arrayKey, file);

        JsonObject envelope = new JsonObject();
        root.entrySet().stream()
            .filter(entry -> !entry.getKey().equals(arrayKey))
            .forEach(entry -> envelope.add(entry.getKey(), entry.getValue()));

        // Drop the envelope's closing "\n}" so the payload array can be appended inside it.
        String head = text(envelope);
        StringBuilder out = new StringBuilder(head.substring(0, head.length() - 2));
        out.append(",\n  \"").append(arrayKey).append("\": [");
        JsonArray rows = payload.getAsJsonArray();
        for (int index = 0; index < rows.size(); index++) {
            out.append(index == 0 ? "\n    " : ",\n    ");
            out.append(COMPACT.toJson(sortDeep(rows.get(index))));
        }
        out.append(rows.isEmpty() ? "]" : "\n  ]").append("\n}\n");
        Files.createDirectories(file.getParent());
        Files.writeString(file, out.toString().replace("\r\n", "\n"), StandardCharsets.UTF_8);
    }

    /**
     * Returns an element's canonical text - the exact bytes {@link #write} emits, without the
     * trailing newline.
     *
     * @param element the element to render
     * @return the canonical text
     */
    public static @NotNull String text(@NotNull JsonElement element) {
        return PRETTY.toJson(sortDeep(element)).replace("\r\n", "\n");
    }

    /**
     * Returns a parity metric at the one scale it has ever been quoted at.
     *
     * <p>Trailing zeros are kept, so {@code 0.0000} and {@code 60.0047} are both four places and a
     * stored number is greppable against the ledger and against {@code CLAUDE.md} verbatim.
     *
     * @param value the metric
     * @return the fixed-scale primitive
     */
    public static @NotNull JsonPrimitive metric(double value) {
        return new JsonPrimitive(new LazilyParsedNumber(
            BigDecimal.valueOf(value).setScale(METRIC_SCALE, RoundingMode.HALF_UP).toPlainString()));
    }

    /**
     * Returns a captured float in shortest-round-trip form, so the stored characters recover the
     * identical float. Widening to a double first would print more digits and invite a
     * double-rounding read back.
     *
     * @param value the float to store
     * @return the primitive carrying its literal text
     */
    public static @NotNull JsonPrimitive float32(float value) {
        return new JsonPrimitive(new LazilyParsedNumber(Float.toString(value)));
    }

    /**
     * Returns a CRC32 or an ARGB as an uppercase {@code 0x} string - a bit pattern rather than a
     * quantity, and one sign-extension bug further from being wrong than a decimal would be.
     *
     * @param value the bit pattern, of which the low 32 bits are stored
     * @return the hex string primitive
     */
    public static @NotNull JsonPrimitive bits(long value) {
        return new JsonPrimitive(String.format(Locale.ROOT, "0x%08X", value & 0xFFFFFFFFL));
    }

    /**
     * Returns the lowercase hex SHA-256 of the given bytes. The one hex formatter, replacing the
     * three spellings that existed across the test tree.
     *
     * @param value the bytes to digest
     * @return the lowercase hex digest
     */
    public static @NotNull String sha256(byte @NotNull [] value) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required by every JDK but is unavailable", ex);
        }
    }

    /**
     * Returns the lowercase hex SHA-256 of a file's NORMALIZED text.
     *
     * <p>Not the raw bytes: a store file is read back after git checked it out, and this repository's
     * working tree is guaranteed nothing about line endings even though the committed blob is always
     * LF. A raw digest would answer differently on two checkouts of one commit, with git reporting
     * neither.
     *
     * @param file the store file to digest
     * @return the lowercase hex digest of its normalized bytes
     */
    public static @NotNull String sha256Normalized(@NotNull Path file) {
        return sha256(ParityStore.readNormalized(file).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Recursively rebuilds the tree with every object's keys in {@link String#compareTo} order,
     * leaving array order untouched.
     *
     * @param element the element to canonicalize
     * @return the key-sorted copy
     */
    public static @NotNull JsonElement sortDeep(@NotNull JsonElement element) {
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
