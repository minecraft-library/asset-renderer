package lib.minecraft.renderer.pipeline.util;

import lib.minecraft.renderer.exception.PipelineException;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

/**
 * The single classpath-read site for the bundled {@code *.json} asset resources - reads exactly one
 * bundled resource per call.
 *
 * <p>Both entry points open {@code /lib/minecraft/renderer/<name>} under try-with-resources, closing
 * the stream on every path, and differ only in what an absent resource means.
 * {@link #require(String, Diagnostics)} treats absence as fatal, {@link #read(String, Diagnostics)} as
 * an empty answer. That split is a per-file decision the caller states by picking a method rather than
 * by passing a value: the two {@code entity_*} resources are optional, because the features they feed
 * degrade to an empty index, while {@code block} / {@code tints} / {@code potions} / {@code glint} are
 * load-bearing and their absence is a build error.
 */
@UtilityClass
public final class BundledResource {

    /** Classpath root under which every resource is bundled. */
    private static final @NotNull String RESOURCE_DIR = "/lib/minecraft/renderer/";

    /**
     * Reads and envelope-validates a load-bearing bundled resource.
     *
     * @param name the resource file name (e.g. {@code potion_colors.json})
     * @param diagnostics the scope envelope warnings are recorded to, childed by {@code name}
     * @return the validated document
     * @throws PipelineException if the resource is absent, or its stream cannot be read
     */
    public static @NotNull ResourceDocument require(@NotNull String name, @NotNull Diagnostics diagnostics) {
        return read(name, diagnostics).orElseThrow(() -> new PipelineException(
            "Classpath resource '%s' not found - run its tooling Gradle task to generate it", name));
    }

    /**
     * Reads and envelope-validates a bundled resource whose absence is tolerated.
     *
     * @param name the resource file name (e.g. {@code entity_models.json})
     * @param diagnostics the scope envelope warnings are recorded to, childed by {@code name}
     * @return the validated document, or {@link Optional#empty()} when the resource is absent
     * @throws PipelineException if the resource is present but its stream cannot be read
     */
    public static @NotNull Optional<ResourceDocument> read(@NotNull String name, @NotNull Diagnostics diagnostics) {
        try (InputStream stream = BundledResource.class.getResourceAsStream(RESOURCE_DIR + name)) {
            if (stream == null) return Optional.empty();
            return Optional.of(ResourceDocument.open(stream.readAllBytes(), diagnostics.child(name)));
        } catch (IOException ex) {
            throw new PipelineException(ex, "Failed to read classpath resource '%s'", name);
        }
    }
}
