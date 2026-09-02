package lib.minecraft.renderer.tooling.entity;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lib.minecraft.renderer.tooling.kernel.VanillaSourceClasses;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Node {@code members} - the cross-entity grouping post-pass (stray beside skeleton, zoglin beside
 * hoglin). Runs AFTER the walk loop over the completed {@code models} tree and writes the resolved
 * canvas-group membership onto every member, the same self-inclusive list on each, so the reader
 * joins nothing.
 *
 * <p>Clustering key = shared primary {@code GeometryRequest} identity, taken from the model's
 * embedded manifest key rather than any derived-id string. The canonical root is the member
 * whose entity id matches the factory-class stem, matched against the REQUEST coordinate
 * rather than an emitted id string; a stemless cluster is a coincidence group (illager) and
 * links nothing. A data-variant-registry base (zombie_nautilus) heads its own variant family
 * and is barred from NON-root membership - and a variant FAMILY (mooshroom, trader_llama) joins
 * no group at all, its canvas being measured by its own coats' union, so a group it would have
 * joined is emitted without it and dissolves where it was the only other member.
 */
@UtilityClass
final class EntityGroupLinker {

    /** The factory-class stem prefixes that never appear in an entity id (the adult / baby name strips). */
    private static final @NotNull List<String> STEM_PREFIXES = List.of("Adult", "Baby");

    /** The factory-class suffix every vanilla model class carries. */
    private static final @NotNull String STEM_SUFFIX = "Model";

    /**
     * Appends {@code members} to every model of every resolved group (the put lands last in the
     * member order).
     *
     * @param root the envelope root owning the completed {@code models} node
     * @param variants the data-variant index (the non-root membership bar)
     * @param diagnostics the post-pass scope
     */
    static void link(@NotNull JsonTree root, @NotNull VariantIndex variants, @NotNull Diagnostics diagnostics) {
        JsonTree models = root.child("models");
        Map<String, List<String>> clusters = models.members()
            .flatMapToObj((id, model) -> primaryGeometry(model).stream().map(geometry -> Map.entry(geometry, id)))
            .collect(Collectors.groupingBy(Map.Entry::getKey, LinkedHashMap::new,
                Collectors.mapping(Map.Entry::getValue, Collectors.toList())));

        for (Map.Entry<String, List<String>> cluster : clusters.entrySet()) {
            List<String> reached = cluster.getValue();
            if (reached.size() < 2) continue;
            String rootId = pickCanonicalRoot(cluster.getKey(), reached);
            if (rootId == null) {
                diagnostics.info("coincidence cluster '%s' shared by %s - no member matches the factory stem, no links",
                    cluster.getKey(), reached);
                continue;
            }
            List<String> group = new ArrayList<>();
            for (String member : reached) {
                if (variants.table(localId(member)) != null && !member.equals(rootId)) {
                    diagnostics.info("variant-registry base '%s' kept out of '%s' group (heads its own variant family)",
                        member, rootId);
                    continue;
                }
                if (isVariantFamily(models.child(member)) && !member.equals(rootId)) {
                    diagnostics.info("variant family '%s' kept out of '%s' group (its coats measure their own union)",
                        member, rootId);
                    continue;
                }
                group.add(member);
            }
            if (group.size() < 2) continue;
            for (String member : group)
                models.child(member).putStrings("members", group.toArray(String[]::new));
            diagnostics.info("group: %s <- %s (shared %s)", rootId, group, cluster.getKey());
        }
    }

    /** Whether a model row carries a variant axis, which is what keeps it out of any group. */
    private static boolean isVariantFamily(@NotNull JsonTree model) {
        return model.findPath("axes", "variant").isPresent();
    }

    /**
     * The member whose normalized entity id equals the geometry key's normalized
     * factory-class stem, or {@code null} for a coincidence cluster.
     */
    private static @Nullable String pickCanonicalRoot(@NotNull String geometryKey, @NotNull List<String> members) {
        int hash = geometryKey.indexOf('#');
        String stem = hash < 0 ? geometryKey : geometryKey.substring(0, hash);
        for (String prefix : STEM_PREFIXES)
            if (stem.startsWith(prefix)) stem = stem.substring(prefix.length());
        if (stem.endsWith(STEM_SUFFIX)) stem = stem.substring(0, stem.length() - STEM_SUFFIX.length());
        String normalizedStem = stem.toLowerCase(Locale.ROOT);
        for (String member : members)
            if (localId(member).replace("_", "").equals(normalizedStem)) return member;
        return null;
    }

    /**
     * The model's primary geometry manifest key, read from the mandatory age axis'
     * {@code options.adult} (the model baseline), or empty when any node on that path is absent (an
     * unresolvable model links nothing).
     */
    private static @NotNull Optional<String> primaryGeometry(@NotNull JsonTree model) {
        return model.findPath("axes", "age", "options", "adult")
            .flatMap(adult -> adult.findString("geometry"));
    }

    /** The namespace-stripped local id of a model key. */
    private static @NotNull String localId(@NotNull String modelId) {
        return VanillaSourceClasses.Paths.stripNamespace(modelId);
    }

}
