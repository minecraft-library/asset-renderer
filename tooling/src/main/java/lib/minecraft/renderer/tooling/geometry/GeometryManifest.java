package lib.minecraft.renderer.tooling.geometry;

import lib.minecraft.renderer.tooling.kernel.ToolingException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.RecordComponent;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The ordered, deduping {@link GeometryRequest} registry handed from a models walk to
 * {@link GeometryFlow} in the same session.
 *
 * <p>The dedupe identity is the minted key: two requests agreeing on factory coordinate
 * plus discriminators collapse onto one entry, with the FIRST request retained (its
 * {@code subjectId} is the provenance stamp). Registration order is emission order - the
 * backing insertion-ordered map preserves append order as a data-structure property, not
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
        if (existing == null) return key;
        if (!existing.factoryClass().equals(request.factoryClass()))
            throw new ToolingException(
                "Geometry key '%s' collides across factory classes '%s' and '%s'",
                key, existing.factoryClass(), request.factoryClass());
        String differing = firstDifference(existing, request);
        if (differing != null)
            throw new ToolingException(
                "Geometry key '%s' is minted by two requests that differ in '%s', so the key is not "
                    + "the identity every reader takes it for: the mesh of the second is dropped for "
                    + "the first and nothing says so. Encode '%s' in the key, or establish that the "
                    + "emitted mesh is not a function of it and name it in GeometryManifest",
                key, differing, differing);
        return key;
    }

    /**
     * The request members the emitted mesh is not a function of, which two requests minting one key
     * may therefore differ in.
     *
     * <p>{@code subjectId} is provenance - the first requester, named in a diagnostic - and deduping
     * ACROSS subjects is what the manifest is for. {@code yAxis} reaches neither the parse nor the
     * emitted entry; it is read by the block-entity emitter downstream of this.
     *
     * <p>Named as an exclusion rather than the comparison being a list of what to check, so a
     * component added to {@link GeometryRequest} is compared by default. The other way round, a new
     * member joins the record and the guard narrows in silence - which is the shape of the defect
     * this exists against.
     */
    private static final @NotNull Set<String> NOT_THE_MESH = Set.of("subjectId", "yAxis");

    /**
     * The first member two requests minting one key disagree on, or {@code null} where they are the
     * same request.
     *
     * <p>Compared with {@link Objects#deepEquals} because three components are arrays, which a
     * record compares by reference - so the generated {@code equals} answers "different" for every
     * legitimate re-registration and would refuse the whole corpus.
     *
     * @param held the request the key is already registered to
     * @param offered the request minting the same key
     * @return the differing component's name, or {@code null} when none differs
     */
    private static @Nullable String firstDifference(
        @NotNull GeometryRequest held, @NotNull GeometryRequest offered) {

        for (RecordComponent component : GeometryRequest.class.getRecordComponents()) {
            if (NOT_THE_MESH.contains(component.getName())) continue;
            if (!Objects.deepEquals(read(component, held), read(component, offered)))
                return component.getName();
        }
        return null;
    }

    /** One component's value off one request. */
    private static @Nullable Object read(
        @NotNull RecordComponent component, @NotNull GeometryRequest request) {

        try {
            return component.getAccessor().invoke(request);
        } catch (ReflectiveOperationException failure) {
            throw new ToolingException(failure,
                "Cannot read GeometryRequest.%s, so two requests minting one key cannot be told apart",
                component.getName());
        }
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
