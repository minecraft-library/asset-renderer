package lib.minecraft.renderer.parity;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.simplified.annotations.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Typed access to a pinned value, by artifact id and key.
 *
 * <p>A façade over {@link ParityStore#read}, not a second reader: it adds the coercion and the
 * guards, and every failure message names the artifact, the key and the command that rewrites it. The
 * re-baselining ergonomics that only three of the old inline pins had become the property of every
 * pin at once.
 *
 * <p><b>Every failure is loud.</b> An absent artifact, an absent key, an empty vector and a vector of
 * the wrong length all throw. That is the migration's structural half: a pin read is now a call that
 * can throw, where a {@code private static final float[]} was a constant that could be emptied and
 * still report green - which is exactly what once turned a golden array into a no-op.
 */
@UtilityClass
public final class Pins {

    /**
     * What every re-baselining prescription opens with, and it is an instruction rather than a note.
     *
     * <p>These messages print because an edit nobody has committed moved a value, so the capture
     * they ask for records {@code asset_dirty} and the promotion at the end of them refuses it: a
     * baseline whose capture cannot be shown to have run on a committed tree is re-derivable from no
     * commit. The change therefore lands in its own commit and the promotion is the next one, which
     * is the same two-commit rule the gate documents. {@code -PallowDirty=true} is the way past it
     * and is deliberately not prescribed here - it records the exception into the promoted value,
     * and a message cannot know that the alternative was worse.
     */
    private static final @NotNull String COMMIT_FIRST =
        "commit the change first - a capture from a dirty tree is refused at promotion - then: ";

    /**
     * Returns a pinned CRC32.
     *
     * <p>Stored as an uppercase {@code 0x} hex string rather than a JSON number: the value is a bit
     * pattern rather than a quantity, it is greppable against both the source it replaced and the
     * failure message that prints it, and a 32-bit unsigned value written as a decimal is one
     * sign-extension away from being wrong.
     *
     * @param artifactId the pin-set artifact id
     * @param key the pin's own key
     * @return the CRC32 as an unsigned value in a long
     * @throws ParityStoreException if the artifact, the key or the value is absent or malformed
     */
    public static long crc32(@NotNull String artifactId, @NotNull String key) {
        String hex = string(artifactId, key, "crc32");
        try {
            return Long.parseLong(hex.startsWith("0x") || hex.startsWith("0X") ? hex.substring(2) : hex, 16);
        } catch (NumberFormatException ex) {
            throw new ParityStoreException(ex,
                "Pin '%s' key '%s' holds '%s', which is not a hex CRC32. Regenerate with %s",
                artifactId, key, hex, regenCommand(artifactId));
        }
    }

    /**
     * Returns a pinned digest.
     *
     * @param artifactId the digest-set artifact id
     * @param key the digested item's name
     * @return the lowercase hex digest
     * @throws ParityStoreException if the artifact, the key or the value is absent
     */
    public static @NotNull String digest(@NotNull String artifactId, @NotNull String key) {
        return string(artifactId, key, "sha256");
    }

    /**
     * Returns a pinned float vector.
     *
     * <p>The expected length is an argument rather than a property of the file, which is what carries
     * the old anti-vacuity guards across the migration verbatim: they exist because an emptied golden
     * array once returned instead of failing. The stored {@code length} is checked against the array
     * too, so the number is stated three times independently and any two disagreeing is a failure
     * rather than a silent narrowing.
     *
     * <p>Each element is parsed from its own literal text. Reading through {@code getAsDouble} and
     * narrowing would round twice, where {@code Float.toString} is shortest-round-trip exact and the
     * stored characters recover the identical float.
     *
     * @param artifactId the pin-set artifact id
     * @param key the pin's own key
     * @param expectedLength the length the caller requires
     * @return the pinned floats
     * @throws ParityStoreException if the artifact, the key or the vector is absent, empty or the
     *     wrong length
     */
    public static float @NotNull [] floats(@NotNull String artifactId, @NotNull String key, int expectedLength) {
        JsonObject entry = entry(artifactId, key);
        JsonElement raw = entry.get("values");
        if (raw == null || !raw.isJsonArray())
            throw new ParityStoreException(
                "Pin '%s' key '%s' carries no 'values' array. Regenerate with %s",
                artifactId, key, regenCommand(artifactId));

        JsonArray array = raw.getAsJsonArray();
        if (array.isEmpty())
            throw new ParityStoreException(
                "Pin '%s' key '%s' is an EMPTY vector - an emptied pin is a no-op that still reports "
                    + "green, so it fails here instead. Regenerate with %s",
                artifactId, key, regenCommand(artifactId));
        if (array.size() != expectedLength)
            throw new ParityStoreException(
                "Pin '%s' key '%s' holds %d values and the caller requires %d. Regenerate with %s",
                artifactId, key, array.size(), expectedLength, regenCommand(artifactId));

        if (entry.has("length") && entry.get("length").getAsInt() != array.size())
            throw new ParityStoreException(
                "Pin '%s' key '%s' declares length %d and holds %d values",
                artifactId, key, entry.get("length").getAsInt(), array.size());

        float[] values = new float[array.size()];
        for (int index = 0; index < values.length; index++)
            values[index] = Float.parseFloat(array.get(index).getAsJsonPrimitive().getAsString());
        return values;
    }

    /**
     * Returns a pinned exact count.
     *
     * @param artifactId the pin-set artifact id
     * @param key the counted thing's name
     * @return the pinned count
     * @throws ParityStoreException if the artifact, the key or the value is absent
     */
    public static int count(@NotNull String artifactId, @NotNull String key) {
        JsonObject entry = entry(artifactId, key);
        JsonElement raw = entry.get("count");
        if (raw == null)
            throw new ParityStoreException(
                "Pin '%s' key '%s' carries no 'count'. Regenerate with %s",
                artifactId, key, regenCommand(artifactId));
        return raw.getAsInt();
    }

    /**
     * Returns whether a pin-set already holds a value under a key.
     * <p>
     * The per-key counterpart of {@link SelfCapture#requireBaseline}, which answers for a whole
     * artifact. A key ADDED to a pin-set the store has already baselined is the one case neither
     * covers: the artifact is baselined, so the whole-artifact guard passes, and the key is absent, so
     * {@link #count} raises - which fails the very capture that would give the key its first value.
     * A producer asks this first and skips its assertion on the round that bootstraps the key.
     *
     * @param artifactId the pin-set artifact id
     * @param key the counted thing's name
     * @return {@code true} when the store holds a value under that key
     */
    public static boolean holds(@NotNull String artifactId, @NotNull String key) {
        if (!ParityStore.isBaselined(artifactId)) return false;
        JsonObject values = ParityStore.read(artifactId).getAsJsonObject("values");
        return values != null && values.has(key);
    }

    /**
     * Returns every key a pin-set or digest-set holds, in sorted order.
     *
     * @param artifactId the artifact id
     * @return its keys
     * @throws ParityStoreException if the artifact is absent
     */
    public static @NotNull List<String> keys(@NotNull String artifactId) {
        return payload(artifactId).keySet().stream().sorted().toList();
    }

    /**
     * Returns the command that recaptures an artifact.
     *
     * <p>Built from the id rather than read out of the file, because the message that names it is
     * most needed when the file is the thing that is missing.
     *
     * @param artifactId the artifact id
     * @return the capture command
     */
    public static @NotNull String regenCommand(@NotNull String artifactId) {
        return "./gradlew parityCapture -Partifacts=" + artifactId;
    }

    /**
     * Returns the command sequence that makes this run's value the artifact's baseline.
     *
     * <p>Three commands behind a commit. A capture erases the working root's run directory before
     * any producer writes into it, so the comparison a promotion requires can only be one taken
     * between the two - a capture followed directly by a promotion is refused every time. Spelled
     * once here so that every message prescribing it prescribes the same sequence.
     *
     * @param artifactId the artifact id
     * @return the commit, then the capture, the compare and the promotion in the order they run
     */
    public static @NotNull String rebaselineCommand(@NotNull String artifactId) {
        return COMMIT_FIRST + regenCommand(artifactId)
            + ", ./gradlew parityCompare -Partifacts=" + artifactId
            + ", ./gradlew parityPromote -Partifacts=" + artifactId + " -Preason=<why>";
    }

    /**
     * Returns the command sequence that gives an artifact its first baseline.
     *
     * <p>The same sequence under {@code -Pbootstrap=true}, which is what turns a missing baseline
     * from the compare's refusal into its expected answer. The promotion is exempt from needing a
     * comparison here - there is nothing to be diffed against yet - so the compare is prescribed
     * rather than required: it is where a value about to become the thing everything else is
     * measured against gets looked at once. The exemption is also per-invocation, which is why the
     * promotion carries {@code -Partifacts}. The tree still has to be committed.
     *
     * @param artifactId the artifact id
     * @return the commit, then the capture, the compare and the promotion in the order they run
     */
    public static @NotNull String bootstrapCommand(@NotNull String artifactId) {
        return COMMIT_FIRST + regenCommand(artifactId)
            + ", ./gradlew parityCompare -Partifacts=" + artifactId + " -Pbootstrap=true"
            + ", ./gradlew parityPromote -Partifacts=" + artifactId
            + " -Pbootstrap=true -Preason=<why>";
    }

    /**
     * Returns one entry of a pin-set or digest-set.
     *
     * @param artifactId the artifact id
     * @param key the entry's key
     * @return the entry object
     * @throws ParityStoreException if the artifact or the key is absent
     */
    private static @NotNull JsonObject entry(@NotNull String artifactId, @NotNull String key) {
        JsonObject payload = payload(artifactId);
        JsonElement entry = payload.get(key);
        if (entry == null || !entry.isJsonObject())
            throw new ParityStoreException(
                "Pin '%s' has no key '%s'. It holds: %s. Regenerate with %s",
                artifactId, key, String.join(", ", payload.keySet().stream().sorted().toList()),
                regenCommand(artifactId));
        return entry.getAsJsonObject();
    }

    /**
     * Returns a string field of one entry.
     *
     * @param artifactId the artifact id
     * @param key the entry's key
     * @param field the field to read
     * @return the field's value
     * @throws ParityStoreException if the field is absent
     */
    private static @NotNull String string(@NotNull String artifactId, @NotNull String key, @NotNull String field) {
        JsonObject entry = entry(artifactId, key);
        JsonElement value = entry.get(field);
        if (value == null)
            throw new ParityStoreException(
                "Pin '%s' key '%s' carries no '%s'. Regenerate with %s",
                artifactId, key, field, regenCommand(artifactId));
        return value.getAsString();
    }

    /**
     * Returns the payload object of a pin-set or a digest-set, whichever this artifact is.
     *
     * @param artifactId the artifact id
     * @return the {@code values} or {@code digests} object
     * @throws ParityStoreException if the artifact is absent or carries neither payload
     */
    private static @NotNull JsonObject payload(@NotNull String artifactId) {
        JsonObject envelope = ParityStore.read(artifactId);
        for (String name : List.of("values", "digests")) {
            JsonElement payload = envelope.get(name);
            if (payload != null && payload.isJsonObject()) return payload.getAsJsonObject();
        }
        throw new ParityStoreException(
            "Parity artifact '%s' carries neither a 'values' nor a 'digests' payload, so it is not a "
                + "pinned value. Regenerate with %s",
            artifactId, regenCommand(artifactId));
    }

}
