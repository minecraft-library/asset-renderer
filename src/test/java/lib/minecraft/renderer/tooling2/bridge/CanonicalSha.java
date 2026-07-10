package lib.minecraft.renderer.tooling2.bridge;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import dev.simplified.gson.GsonSettings;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * The decision-28 canonical-SHA recipe, re-implemented as a tooling2 test util: parse with the
 * same {@code GsonSettings.defaults()} semantics as the legacy gate, compact re-serialise,
 * SHA-256, lowercase hex. The old package's package-visible {@code canonicalSha256} is never
 * referenced (reuse would enter the old package's namespace).
 *
 * <p>Canonicalisation kills whitespace/EOL drift but PRESERVES member order (Gson keeps insertion
 * order through parse-&gt;re-serialise), so the proof is order-sensitive by design (10-bridge SS3.2).
 */
final class CanonicalSha {

    /** Same parse + compact-serialise semantics as the legacy {@code JsonResourceShaTest} gate. */
    private static final @NotNull Gson GSON = GsonSettings.defaults().create();

    private CanonicalSha() {
    }

    /**
     * The canonical SHA-256 of a parsed tree.
     *
     * @param tree the JSON tree
     * @return the lowercase-hex SHA-256 of its compact serialisation
     */
    static @NotNull String of(@NotNull JsonElement tree) {
        byte[] canonical = GSON.toJson(tree).getBytes(StandardCharsets.UTF_8);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /**
     * The canonical SHA-256 of a classpath JSON resource.
     *
     * @param classpath the resource path
     * @return the lowercase-hex SHA-256 of its canonical form
     */
    static @NotNull String ofResource(@NotNull String classpath) {
        try (InputStream in = CanonicalSha.class.getResourceAsStream(classpath)) {
            Objects.requireNonNull(in, classpath);
            return of(GSON.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonElement.class));
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read " + classpath, ex);
        }
    }

}
