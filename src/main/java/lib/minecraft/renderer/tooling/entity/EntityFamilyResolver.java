package lib.minecraft.renderer.tooling.entity;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lib.minecraft.renderer.pipeline.util.VanillaSourcePaths;
import lib.minecraft.renderer.tooling.util.Diagnostics;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Derives the cross-entity family table from the per-entity rows emitted into
 * {@code entity_models.json}. Pairs siblings that share a {@code geometry_ref} with a
 * canonical root entity (the member whose id, after the {@code minecraft:} namespace strip,
 * matches the geometry stem after the {@code geometry.} prefix and any
 * {@link #GEOMETRY_NAME_PREFIXES} strip).
 *
 * <p>Example pairings vanilla 1.21+ produces:
 * <ul>
 *   <li>{@code geometry.cow} shared by (cow, mooshroom) - root is cow (matches geometry stem)</li>
 *   <li>{@code geometry.adultcamel} shared by (camel, camel_husk) - root is camel (matches
 *       after stripping {@code "adult"})</li>
 *   <li>{@code geometry.illager} shared by (evoker, illusioner, pillager, vindicator) - no
 *       member's id matches the stem, so no family is emitted</li>
 * </ul>
 *
 * <p>The "id matches geometry stem" rule cleanly separates the wanted families (where the
 * geometry was authored for one canonical entity and re-used by a derivative) from the
 * coincidence families (where multiple sibling entities share a generic-named model).
 *
 * <p>Variant rows ({@code variant_of} present) are pre-filtered out because they're already
 * grouped under their declared root via the per-entity {@code variant_of} field.
 */
@UtilityClass
public final class EntityFamilyResolver {

    /**
     * Common geometry-name prefixes that don't appear in the entity id.
     * {@code geometry.adultcamel} pairs with {@code minecraft:camel}; the resolver strips
     * these prefixes before matching the geometry stem against entity ids.
     */
    private static final @NotNull List<String> GEOMETRY_NAME_PREFIXES = List.of("adult", "baby");

    /**
     * Builds the families JSON object by clustering non-variant entities that share a
     * {@code geometry_ref} and selecting the canonical root per cluster.
     *
     * @param entitiesOut the {@code entities} JSON object from {@code entity_models.json}
     *     (one row per entity-id; rows with {@code variant_of} are skipped)
     * @param diagnostics the diagnostic sink; emits one {@code INFO} line per resolved or
     *     skipped family
     * @return a JSON object mapping each non-root sibling to its family root; empty when no
     *     family resolves
     */
    public static @NotNull JsonObject derive(@NotNull JsonObject entitiesOut, @NotNull Diagnostics diagnostics) {
        Map<String, List<String>> geometryToBaseEntities = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : entitiesOut.entrySet()) {
            if (!entry.getValue().isJsonObject()) continue;
            JsonObject row = entry.getValue().getAsJsonObject();
            if (row.has("variant_of")) continue;
            if (!row.has("geometry_ref")) continue;
            String geomRef = row.get("geometry_ref").getAsString();
            geometryToBaseEntities.computeIfAbsent(geomRef, k -> new ArrayList<>()).add(entry.getKey());
        }

        JsonObject families = new JsonObject();
        for (Map.Entry<String, List<String>> e : geometryToBaseEntities.entrySet()) {
            List<String> members = e.getValue();
            if (members.size() < 2) continue;
            String root = pickCanonicalRoot(e.getKey(), members);
            if (root == null) {
                diagnostics.info("cross-entity family skipped: '%s' shared by %s - no member id matches the geometry stem",
                    e.getKey(), members);
                continue;
            }
            for (String member : members) {
                if (member.equals(root)) continue;
                families.addProperty(member, root);
                diagnostics.info("cross-entity family: %s -> %s (shared %s)", member, root, e.getKey());
            }
        }
        return families;
    }

    /**
     * Returns the family root by matching the geometry stem (after stripping the
     * {@code geometry.} prefix and any {@link #GEOMETRY_NAME_PREFIXES} prefix) against each
     * candidate entity id (after the {@code minecraft:} namespace strip).
     */
    private static @Nullable String pickCanonicalRoot(@NotNull String geometryRef, @NotNull List<String> members) {
        String stem = geometryRef.startsWith("geometry.") ? geometryRef.substring("geometry.".length()) : geometryRef;
        for (String prefix : GEOMETRY_NAME_PREFIXES) {
            if (stem.startsWith(prefix)) stem = stem.substring(prefix.length());
        }
        String targetId = VanillaSourcePaths.MINECRAFT_NAMESPACE + stem;
        for (String member : members)
            if (member.equals(targetId)) return member;
        return null;
    }

}
