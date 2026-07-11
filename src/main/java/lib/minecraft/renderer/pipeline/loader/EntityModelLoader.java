package lib.minecraft.renderer.pipeline.loader;

import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.exception.PipelineException;
import lib.minecraft.renderer.pipeline.load.entity.EntityFamilyReader;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;

/**
 * The native entry point to the bundled entity definitions: delegates loading to
 * {@link EntityFamilyReader}, which reads {@code entity_models.json} (per-entity metadata: geometry
 * reference, texture reference, overlays, axes, layers) joined against {@code entity_geometry.json}
 * (the deduplicated bone/cube trees) directly into the {@link Entity} map the renderer consumes, and
 * derives the {@code variant_of} / {@code family_of} groupings.
 * <p>
 * The entity definition records themselves live on {@link Entity} (the asset-DTO altitude, beside
 * {@code Block} / {@code Item}); this class is the loader front-end plus the JVM-lifetime family cache.
 *
 * @see EntityFamilyReader
 */
@UtilityClass
public class EntityModelLoader {

    /**
     * Loads the bundled entity definitions, delegating to
     * {@link EntityFamilyReader}.
     *
     * @return definitions keyed by namespaced entity id (empty when the geometry resource is absent)
     * @throws PipelineException when a resource file is present but unparseable, or when an
     *     entity references a geometry id not in the geometry file
     */
    public static @NotNull ConcurrentMap<String, Entity> load() {
        return EntityFamilyReader.load(Diagnostics.root("entity_models", Diagnostics.Output.CONSOLE, null));
    }

    /**
     * Lazily-computed {@code entityId -> familyMembers} result of {@link #loadFamilies()}, cached
     * for the JVM lifetime (the underlying JSON never changes at runtime).
     */
    private static volatile Map<String, List<String>> FAMILIES_CACHE;

    /**
     * Returns {@code entityId -> familyMembers} keyed by every native entity id. Family membership is
     * derived from {@code variant_of} (variant siblings roll up to their base row) plus the
     * cross-entity {@code family_of} groupings (mooshroom -&gt; cow) by {@link EntityFamilyReader}.
     * Singletons return a single-element list containing themselves so callers can iterate uniformly
     * without special-casing. The result is cached on first call - the JSON is loaded once for the
     * lifetime of the JVM.
     */
    public static @NotNull Map<String, List<String>> loadFamilies() {
        Map<String, List<String>> cached = FAMILIES_CACHE;
        if (cached != null) return cached;
        synchronized (EntityModelLoader.class) {
            if (FAMILIES_CACHE != null) return FAMILIES_CACHE;
            Map<String, List<String>> result =
                EntityFamilyReader.loadFamilies(Diagnostics.root("entity_models", Diagnostics.Output.CONSOLE, null));
            FAMILIES_CACHE = result;
            return result;
        }
    }

}
