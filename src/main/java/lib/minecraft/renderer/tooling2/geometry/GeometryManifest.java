package lib.minecraft.renderer.tooling2.geometry;

import lib.minecraft.renderer.tooling2.kernel.ToolingException;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The ordered, deduping {@link GeometryRequest} registry handed from a models walk to
 * {@code GeometryFlow} in the same session (SPINE decision 12).
 *
 * <p>The dedupe identity IS the minted key (decision 15): two requests agreeing on factory
 * coordinate + discriminators collapse onto one entry, the FIRST request retained (its
 * {@code subjectId} is the provenance stamp). Registration order is emission order -
 * append-last is a data-structure property of the backing insertion-ordered map [P5], not
 * caller choreography. A key collision between two DIFFERENT factory classes (same simple
 * name in different packages) fails loudly rather than silently merging meshes.
 */
public final class GeometryManifest {

    private final @NotNull Map<String, GeometryRequest> entries = new LinkedHashMap<>();

    /**
     * Registers a request, deduping by key identity, and returns the minted key the caller
     * embeds as its {@code geometry} reference.
     *
     * @param request the parse request
     * @return the factory-coordinate key
     * @throws ToolingException if the key is already held by a DIFFERENT factory class
     *     (simple-name collision across packages)
     */
    public @NotNull String register(@NotNull GeometryRequest request) {
        String key = GeometryIds.of(request);
        GeometryRequest existing = this.entries.putIfAbsent(key, request);
        if (existing != null && !existing.factoryClass().equals(request.factoryClass()))
            throw new ToolingException(
                "Geometry key '%s' collides across factory classes '%s' and '%s'",
                key, existing.factoryClass(), request.factoryClass());
        return key;
    }

    /**
     * The registered entries, key to first request, in registration order (unmodifiable).
     */
    public @NotNull Map<String, GeometryRequest> entries() {
        return Collections.unmodifiableMap(this.entries);
    }

    /**
     * The number of deduped entries.
     */
    public int size() {
        return this.entries.size();
    }

}
