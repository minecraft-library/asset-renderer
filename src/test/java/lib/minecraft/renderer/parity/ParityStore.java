package lib.minecraft.renderer.parity;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Reads one artifact file out of the parity store.
 *
 * <p>Two roots and one path rule. {@link #PRODUCTION} is tracked and holds the last known value of
 * every artifact; {@link #WORKING} is gitignored and holds what a measurement run just produced.
 * They mirror each other path for path, which is what lets a compare, a promotion and an A/B all be
 * walks with no judgement in them.
 *
 * <p><b>The store is read by filesystem path, never through the classpath</b>, and that pairs with
 * {@code processTestResources} excluding the store directory. The two are one decision: a classpath
 * read taken alongside that exclude does not fail loudly - {@code getResourceAsStream} answers
 * {@code null}, every migrated pin reads as absent, and a whole suite goes silently vacuous. Reading
 * by path also means a value promoted seconds ago is the value the next read sees, rather than
 * whatever {@code build/resources/test} last copied.
 */
public final class ParityStore {

    /** The tracked production store: the last known value of every parity artifact. */
    public static final @NotNull Path PRODUCTION = Path.of("src/test/resources/lib/minecraft/renderer/parity");

    /** The gitignored working root a measurement writes into, overridable by {@code -Dasset.parity.root}. */
    public static final @NotNull Path WORKING = resolveWorking();

    /** The one reserved directory inside a working root; everything that is not an artifact lives here. */
    public static final @NotNull String RUN_DIR = "_run";

    /** The envelope schema version this reader understands. A stored file above it fails rather than half-reads. */
    public static final int SUPPORTED_FORMAT = 1;

    /** Kind prefix of an artifact id to the directory it lives in. */
    private static final @NotNull Map<String, String> KIND_DIRECTORIES = Map.of(
        "sweep", "sweeps",
        "manifest", "manifests",
        "digest", "digests",
        "pin", "pins");

    /** The two artifacts read by name rather than through the id rule, because their names predate it. */
    private static final @NotNull Map<String, String> ROOT_FILES = Map.of(
        "report.oracle-index", "index.json",
        "roster.blindness-rules", "blindness.json");

    private ParityStore() {}

    /**
     * Returns the store-relative path an artifact id maps to.
     *
     * <p>The kind prefix names a directory and the rest of the id becomes the file stem, with any
     * remaining dots turned into hyphens - {@code manifest.dump.vanilla} is
     * {@code manifests/dump-vanilla.json}. One rule in each direction, so no sub-path is ever a
     * literal and no table has to be kept in step with the ids.
     *
     * @param artifactId the artifact id
     * @return its path relative to a store root
     * @throws ParityStoreException if the id names no kind this store holds
     */
    public static @NotNull Path pathOf(@NotNull String artifactId) {
        String rootFile = ROOT_FILES.get(artifactId);
        if (rootFile != null) return Path.of(rootFile);

        int dot = artifactId.indexOf('.');
        String kind = dot < 0 ? artifactId : artifactId.substring(0, dot);
        String rest = dot < 0 ? "" : artifactId.substring(dot + 1);
        String directory = KIND_DIRECTORIES.get(kind);
        if (directory == null || rest.isEmpty())
            throw new ParityStoreException("No store path for artifact id '%s'", artifactId);
        return Path.of(directory, rest.replace('.', '-') + ".json");
    }

    /**
     * Returns whether the production store holds this artifact.
     *
     * @param artifactId the artifact id
     * @return whether its file exists
     */
    public static boolean exists(@NotNull String artifactId) {
        return Files.isRegularFile(PRODUCTION.resolve(pathOf(artifactId)));
    }

    /**
     * Returns whether this artifact is one of the store's own two root files.
     *
     * <p>The index and the blindness roster sit at the store root under names that predate the id
     * grammar, and they are the store's scaffolding rather than measured values: no producer writes
     * one, no capture holds one and no promotion baselines one. A rule about baselines has to say
     * so, because both files exist unconditionally and would otherwise read as promoted values that
     * nobody promoted.
     *
     * @param artifactId the artifact id
     * @return whether it is a root file
     */
    public static boolean isRootFile(@NotNull String artifactId) {
        return ROOT_FILES.containsKey(artifactId);
    }

    /**
     * Returns whether the index says this artifact has a last known value.
     *
     * <p>The index is the authority rather than the file's presence, because the two answer
     * different questions: a file can be half-promoted or hand-dropped, and {@code baselined} is
     * what {@code promote-apply} writes in the same act as the file. A reader that has to behave
     * differently before its first promotion - a self-captured pin, which has to be capturable
     * before it can be promoted - keys on this rather than on a flag of its own, so the bootstrap
     * state lives in one tracked file instead of in whoever remembers to pass a property.
     *
     * @param artifactId the artifact id
     * @return whether the index marks it baselined
     * @throws ParityStoreException if the index does not register the id at all
     */
    public static boolean isBaselined(@NotNull String artifactId) {
        JsonObject artifacts = read("report.oracle-index").getAsJsonObject("artifacts");
        JsonElement row = artifacts == null ? null : artifacts.get(artifactId);
        if (row == null || !row.isJsonObject())
            throw new ParityStoreException(
                "Parity artifact '%s' is not registered in the store index, so nothing can say "
                    + "whether it has a baseline - coining an artifact is an edit to index.json and "
                    + "to ParityArtifacts together",
                artifactId);
        JsonObject entry = row.getAsJsonObject();
        return entry.has("baselined") && entry.get("baselined").getAsBoolean();
    }

    /**
     * Reads an artifact out of the production store, checking its envelope.
     *
     * @param artifactId the artifact id
     * @return the parsed artifact
     * @throws ParityStoreException if it is absent, malformed, or declares a newer schema
     */
    public static @NotNull JsonObject read(@NotNull String artifactId) {
        return readFrom(PRODUCTION, artifactId);
    }

    /**
     * Reads an artifact out of the named store root, checking its envelope.
     *
     * @param root the store root to read from
     * @param artifactId the artifact id
     * @return the parsed artifact
     * @throws ParityStoreException if it is absent, malformed, or declares a newer schema
     */
    public static @NotNull JsonObject readFrom(@NotNull Path root, @NotNull String artifactId) {
        Path file = root.resolve(pathOf(artifactId));
        if (!Files.isRegularFile(file))
            // The sequence rather than a summary of it, and built where every other message
            // prescribing one builds it: a first baseline takes -Pbootstrap=true on two of its
            // three commands and a committed tree under all of them, and each is a refusal rather
            // than a convention.
            throw new ParityStoreException(
                "Parity artifact '%s' is absent from the store at '%s' - give it a first baseline: %s",
                artifactId, file, Pins.bootstrapCommand(artifactId));

        JsonObject envelope;
        try {
            envelope = JsonParser.parseString(readNormalized(file)).getAsJsonObject();
        } catch (RuntimeException ex) {
            throw new ParityStoreException(ex, "Parity artifact '%s' at '%s' is not a JSON object", artifactId, file);
        }

        String declared = envelope.has("artifact") ? envelope.get("artifact").getAsString() : "";
        if (!artifactId.equals(declared))
            throw new ParityStoreException(
                "Parity artifact at '%s' declares itself '%s' but was read as '%s' - a store file "
                    + "copied from its neighbour cannot silently claim to be it",
                file, declared, artifactId);

        int format = envelope.has("format") ? envelope.get("format").getAsInt() : 0;
        if (format > SUPPORTED_FORMAT)
            throw new ParityStoreException(
                "Parity artifact '%s' declares format %d and this reader understands %d - a reader "
                    + "never half-reads a newer schema",
                artifactId, format, SUPPORTED_FORMAT);

        return envelope;
    }

    /**
     * Returns a file's text with line endings normalized.
     *
     * <p>The single read path, and the half that makes a CRLF working tree inert rather than merely
     * discouraged. `.gitattributes` guarantees the committed blob is LF and guarantees nothing about
     * the checkout, so a store that digested raw bytes would answer differently on two checkouts of
     * one commit with git reporting neither.
     *
     * @param file the file to read
     * @return its UTF-8 text with every CRLF collapsed to LF
     * @throws ParityStoreException if the file cannot be read
     */
    public static @NotNull String readNormalized(@NotNull Path file) {
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException ex) {
            throw new ParityStoreException(new UncheckedIOException(ex), "Cannot read '%s'", file);
        }
    }

    /**
     * Resolves the working root and refuses one a `cache/` clean would not reach.
     *
     * <p>The Gradle side applies the same rule at configuration time, but a `-Dasset.parity.root`
     * can reach a fork by another route, so the guard is repeated where the value is actually used.
     * A root that is absolute, or outside `cache/`, could hold a capture that outlives a clean and
     * could be committed; both are what single-slot exists to make unrepresentable.
     *
     * @return the resolved working root
     * @throws ParityStoreException if the root escapes `cache/`
     */
    private static @NotNull Path resolveWorking() {
        String root = System.getProperty("asset.parity.root", "cache/parity/current");
        String normalized = root.replace('\\', '/');
        boolean absolute = normalized.startsWith("/") || normalized.matches("^[A-Za-z]:/.*");
        if (absolute || !normalized.startsWith("cache/"))
            throw new ParityStoreException(
                "asset.parity.root must be a relative path under cache/ (got '%s')", root);
        return Path.of(root);
    }

}
