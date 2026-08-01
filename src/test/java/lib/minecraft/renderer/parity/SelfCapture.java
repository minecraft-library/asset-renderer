package lib.minecraft.renderer.parity;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assumptions;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Writes the observed value of a self-captured artifact into the parity working root.
 *
 * <p>A self-captured row is one whose value only exists inside a test JVM - a digest over a
 * canonical form this repository defines, a CRC32 over pixels this renderer just produced. No
 * producer directory holds it, so the capture step does not read one: the test writes the artifact
 * at its own production-relative path under {@link ParityStore#WORKING}, and
 * {@code capture-normalize} validates that file where it stands, stamps provenance onto it and
 * fails loudly when it is absent.
 *
 * <p><b>The write happens before the assertion, always.</b> A capture records what the run
 * produced, and the run that most needs its value recorded is the one whose pin just drifted - so a
 * failing gate still leaves a capture, and re-baselining is a promote rather than a second
 * measurement.
 *
 * <p><b>Every name in the envelope follows from the artifact id.</b> The kind prefix decides the
 * directory (that rule is {@link ParityStore#pathOf}), and here it decides the three names the
 * envelope spells its payload with. One rule per direction, so a new row of an existing kind states
 * nothing and cannot state it differently.
 */
public final class SelfCapture {

    /** The three names an artifact kind spells its envelope with, by the id's kind prefix. */
    private static final @NotNull Map<String, Envelope> ENVELOPES = Map.of(
        "digest", new Envelope("digest-set", "name", "digests"),
        "pin", new Envelope("pin-set", "pin_key", "values"));

    /**
     * The producer's own provenance flags, popped by {@code capture-normalize} and never stored.
     *
     * <p>The one channel for a value only the measuring process knows. It rides the payload the way
     * the toolkit's own {@code _counts} and {@code _root} do, and it comes last on the way in, so an
     * observed value beats a {@code --flag} guessed on the build side.
     */
    private static final @NotNull String FLAGS = "_flags";

    private SelfCapture() {}

    /**
     * Aborts the calling test when the store index says this artifact has no baseline yet.
     *
     * <p>A self-captured value has to be capturable <em>before</em> it can be promoted, so its
     * reader cannot assert on the run that first produces it. That state is read from
     * {@link ParityStore#isBaselined} - a tracked file - rather than from a property somebody
     * remembers to pass, and it stops being reachable the moment the artifact is promoted.
     *
     * <p>An abort rather than a pass: a test reported as skipped, with this reason, is
     * distinguishable in the report from one that ran and agreed.
     *
     * @param artifactId the artifact the caller is about to read
     * @throws ParityStoreException if the index does not register the id at all
     */
    public static void requireBaseline(@NotNull String artifactId) {
        if (ParityStore.isBaselined(artifactId)) return;
        Assumptions.abort(artifactId + " has no baseline yet, so there is nothing to assert against. "
            + "The capture this run just wrote is the value to promote: "
            + Pins.regenCommand(artifactId) + " then ./gradlew parityPromote -Preason=<why>");
    }

    /**
     * Writes a self-captured artifact, with no producer-declared flags.
     *
     * @param artifactId the artifact id
     * @param entries its payload, keyed the way the artifact's envelope key names
     * @return the file written
     */
    public static @NotNull Path write(@NotNull String artifactId, @NotNull Map<String, JsonObject> entries) {
        return write(artifactId, entries, List.of());
    }

    /**
     * Writes a self-captured artifact.
     *
     * @param artifactId the artifact id
     * @param entries its payload, keyed the way the artifact's envelope key names
     * @param flags provenance flags this producer observed, each spelled {@code name=value}
     * @return the file written
     * @throws ParityStoreException if the id names no self-capturing kind, or the payload is empty
     */
    public static @NotNull Path write(
        @NotNull String artifactId,
        @NotNull Map<String, JsonObject> entries,
        @NotNull List<String> flags
    ) {
        int dot = artifactId.indexOf('.');
        Envelope envelope = dot < 0 ? null : ENVELOPES.get(artifactId.substring(0, dot));
        if (envelope == null)
            throw new ParityStoreException(
                "Artifact '%s' names no self-capturing kind; the kinds that self-capture are %s",
                artifactId, String.join(", ", ENVELOPES.keySet().stream().sorted().toList()));
        // An empty capture promotes as a zero-entry artifact that compares clean against itself for
        // ever, which is the exact false green the whole store is built against.
        if (entries.isEmpty())
            throw new ParityStoreException(
                "Refusing to capture '%s' with no entries - an empty artifact compares clean against "
                    + "anything, so it fails here instead", artifactId);

        JsonObject payload = new JsonObject();
        entries.forEach(payload::add);

        JsonObject root = new JsonObject();
        root.addProperty("//", "parity." + artifactId + " · regen: " + Pins.regenCommand(artifactId));
        root.addProperty("artifact", artifactId);
        root.addProperty("format", ParityStore.SUPPORTED_FORMAT);
        root.addProperty("key", envelope.key());
        root.addProperty("kind", envelope.kind());
        root.add(envelope.member(), payload);
        if (!flags.isEmpty()) {
            JsonArray declared = new JsonArray(flags.size());
            flags.forEach(declared::add);
            root.add(FLAGS, declared);
        }

        Path file = ParityStore.WORKING.resolve(ParityStore.pathOf(artifactId));
        try {
            ParityJson.write(file, root);
        } catch (IOException ex) {
            throw new ParityStoreException(new UncheckedIOException(ex),
                "Cannot write the capture of '%s' to '%s'", artifactId, file);
        }
        return file;
    }

    /**
     * The envelope names one artifact kind spells its payload with.
     *
     * @param kind the envelope's {@code kind}
     * @param key the field the comparator joins entries on
     * @param member the payload member the entries live under
     */
    private record Envelope(@NotNull String kind, @NotNull String key, @NotNull String member) {}

}
