package lib.minecraft.renderer.pipeline.load;

import lib.minecraft.renderer.exception.PipelineException;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

/**
 * The single classpath-read site for the bundled {@code v2/*.json} asset resources.
 *
 * <p>{@link #read(String, MissingPolicy, Diagnostics)} opens
 * {@code /lib/minecraft/renderer/<name>} under try-with-resources - closing the stream on every
 * path, fixing the historical colormap-loader leak - and applies the caller-declared
 * {@link MissingPolicy} when the resource is absent. The graceful-empty vs required split is an
 * explicit per-file policy the caller declares, not an accident: {@code entity} and {@code colormap}
 * resources are {@link MissingPolicy#GRACEFUL_EMPTY} (their features are optional), while
 * {@code block} / {@code tints} / {@code potions} / {@code glint} are {@link MissingPolicy#REQUIRED}.
 */
public final class V2Resources {

    /** Classpath root under which every v2 resource is bundled. */
    private static final @NotNull String V2_DIR = "/lib/minecraft/renderer/";

    private V2Resources() {}

    /** How {@link #read(String, MissingPolicy, Diagnostics)} reacts to an absent resource. */
    public enum MissingPolicy {
        /** An absent resource raises {@link PipelineException} - the resource is load-bearing. */
        REQUIRED,
        /** An absent resource yields {@link Optional#empty()} - the feature it feeds is optional. */
        GRACEFUL_EMPTY
    }

    /**
     * Reads and envelope-validates a bundled v2 resource.
     *
     * @param name the resource file name (e.g. {@code potion_colors.json})
     * @param policy how an absent resource is handled
     * @param diagnostics the scope envelope warnings are recorded to, childed by {@code name}
     * @return the validated document, or {@link Optional#empty()} when the resource is absent under
     *     {@link MissingPolicy#GRACEFUL_EMPTY}
     * @throws PipelineException if the resource is absent under {@link MissingPolicy#REQUIRED}, or its
     *     stream cannot be read
     */
    public static @NotNull Optional<V2Document> read(@NotNull String name, @NotNull MissingPolicy policy, @NotNull Diagnostics diagnostics) {
        try (InputStream stream = V2Resources.class.getResourceAsStream(V2_DIR + name)) {
            if (stream == null) {
                if (policy == MissingPolicy.REQUIRED)
                    throw new PipelineException("Classpath resource '%s' not found - run its tooling Gradle task to generate it", name);
                return Optional.empty();
            }
            return Optional.of(V2Document.open(stream.readAllBytes(), diagnostics.child(name)));
        } catch (IOException ex) {
            throw new PipelineException(ex, "Failed to read classpath resource '%s'", name);
        }
    }
}
