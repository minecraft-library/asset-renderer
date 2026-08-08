package lib.minecraft.renderer.parity;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.TreeMap;

/**
 * Accumulates one pin-set's observed values across a test class and writes it once it is whole.
 *
 * <p><b>The subject table declared up front is the completeness contract.</b> A pin-set's values
 * come from several {@code @Test} methods, so there is a window where the artifact is half
 * measured - and a half capture is the one thing worse than none, because it promotes as a smaller
 * population that then compares clean against itself for ever. Nothing is written until every
 * declared key has a value, which turns a {@code --tests}-filtered run into the capture step's
 * absent-file failure. That is the same backstop the design gives a class that never ran at all,
 * extended to a class that ran only part of itself.
 *
 * <p><b>Write-through rather than a lifecycle hook.</b> Each contribution rewrites the file when
 * the set is complete, so whichever test finishes last completes it and no {@code @AfterAll} has to
 * be remembered. If this build ever gains {@code maxParallelForks}, contributors would land in
 * different JVMs, no set would complete, and the capture would fail loudly rather than promote a
 * fragment - so the shape is safe under that change rather than merely untested against it.
 *
 * <p><b>Every value carries its subject</b>, which is what a re-baseliner needs and what no inline
 * {@code long} argument could carry: the options a render was taken under, the expression a pose was
 * read from, the file a count was taken over.
 */
public final class PinSet {

    private final @NotNull String artifactId;

    /** Key to subject, declared once. Its key set is what "complete" means. */
    private final @NotNull Map<String, String> subjects;

    private final @NotNull Map<String, JsonObject> observed = new TreeMap<>();

    private PinSet(@NotNull String artifactId, @NotNull Map<String, String> subjects) {
        this.artifactId = artifactId;
        this.subjects = Map.copyOf(subjects);
    }

    /**
     * Opens a pin-set for capture.
     *
     * @param artifactId the pin-set artifact id
     * @param subjects every key this artifact holds, mapped to what produced it
     * @return the accumulator
     */
    public static @NotNull PinSet of(@NotNull String artifactId, @NotNull Map<String, String> subjects) {
        return new PinSet(artifactId, subjects);
    }

    /**
     * Records an observed CRC32.
     *
     * @param key the pin's own key
     * @param value the CRC32 as an unsigned value in a long
     */
    public void crc32(@NotNull String key, long value) {
        JsonObject entry = entry(key, "crc32");
        entry.add("crc32", ParityJson.bits(value));
        put(key, entry);
    }

    /**
     * Records an observed exact count.
     *
     * @param key the counted thing's name
     * @param value the count
     */
    public void count(@NotNull String key, int value) {
        JsonObject entry = entry(key, "int");
        entry.addProperty("count", value);
        put(key, entry);
    }

    /**
     * Records an observed float vector.
     *
     * <p>Each element is stored in {@link Float#toString} form, which is shortest-round-trip exact,
     * so the stored characters recover the identical float. {@code length} is stored beside them and
     * checked against the array on read, which is what carries the old anti-vacuity guards across:
     * an emptied golden array once returned instead of failing.
     *
     * @param key the pin's own key
     * @param values the observed floats
     * @param order how to read the flattened vector back
     */
    public void floats(@NotNull String key, float @NotNull [] values, @NotNull String order) {
        JsonObject entry = entry(key, "float32[]");
        entry.addProperty("length", values.length);
        entry.addProperty("order", order);
        JsonArray array = new JsonArray(values.length);
        for (float value : values) array.add(ParityJson.float32(value));
        entry.add("values", array);
        put(key, entry);
    }

    /**
     * Aborts the calling test when this pin-set has no baseline yet.
     */
    public void requireBaseline() {
        SelfCapture.requireBaseline(artifactId);
    }

    /**
     * Starts one entry, refusing a key the subject table does not declare.
     *
     * @param key the pin's own key
     * @param type the value's type token
     * @return the entry, carrying its subject and type
     * @throws ParityStoreException if the key is not one this artifact declares
     */
    private @NotNull JsonObject entry(@NotNull String key, @NotNull String type) {
        String subject = subjects.get(key);
        if (subject == null)
            throw new ParityStoreException(
                "Pin '%s' declares no key '%s'; it declares: %s. A key captured under a name nothing "
                    + "reads is a value the gate never sees",
                artifactId, key, String.join(", ", subjects.keySet().stream().sorted().toList()));
        JsonObject entry = new JsonObject();
        entry.addProperty("subject", subject);
        entry.addProperty("type", type);
        return entry;
    }

    /**
     * Records one entry and writes the artifact when every declared key has a value.
     *
     * @param key the pin's own key
     * @param entry its value
     */
    private void put(@NotNull String key, @NotNull JsonObject entry) {
        observed.put(key, entry);
        if (observed.keySet().equals(subjects.keySet())) SelfCapture.write(artifactId, observed);
    }

}
