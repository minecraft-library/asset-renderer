package lib.minecraft.renderer.tooling.entity;

import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lib.minecraft.renderer.tooling.kernel.JsonNode;
import lib.minecraft.renderer.tooling.kernel.VanillaSourceClasses;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Node {@code family_of} - the cross-entity grouping post-pass (mooshroom to cow, stray to
 * skeleton). Runs AFTER the walk loop over the completed {@code families} tree.
 *
 * <p>Clustering key = shared primary {@code GeometryRequest} identity, taken from the family's
 * embedded manifest key rather than any derived-id string. The canonical root is the member
 * whose entity id matches the factory-class stem, matched against the REQUEST coordinate
 * rather than an emitted id string; a stemless cluster is a coincidence family (illager) and
 * links nothing. A data-variant-registry base (zombie_nautilus) heads its own variant family
 * and is barred from NON-root membership, while variant bases stay eligible as roots
 * (mooshroom to cow).
 */
final class EntityFamilyLinker {

    /** The factory-class stem prefixes that never appear in an entity id (the adult / baby name strips). */
    private static final @NotNull List<String> STEM_PREFIXES = List.of("Adult", "Baby");

    /** The factory-class suffix every vanilla model class carries. */
    private static final @NotNull String STEM_SUFFIX = "Model";

    private EntityFamilyLinker() {
    }

    /**
     * Appends {@code family_of} to every linked non-root family (the put lands last in the
     * member order).
     *
     * @param root the envelope root owning the completed {@code families} node
     * @param variants the data-variant index (the non-root membership bar)
     * @param diagnostics the post-pass scope
     */
    static void link(@NotNull JsonNode root, @NotNull VariantIndex variants, @NotNull Diagnostics diagnostics) {
        JsonNode families = root.child("families");
        Map<String, List<String>> clusters = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> family : families.members()) {
            String geometry = primaryGeometry(family.getValue());
            if (geometry != null)
                clusters.computeIfAbsent(geometry, key -> new ArrayList<>()).add(family.getKey());
        }

        for (Map.Entry<String, List<String>> cluster : clusters.entrySet()) {
            List<String> members = cluster.getValue();
            if (members.size() < 2) continue;
            String rootId = pickCanonicalRoot(cluster.getKey(), members);
            if (rootId == null) {
                diagnostics.info("coincidence cluster '%s' shared by %s - no member matches the factory stem, no links",
                    cluster.getKey(), members);
                continue;
            }
            for (String member : members) {
                if (member.equals(rootId)) continue;
                if (variants.table(localId(member)) != null) {
                    diagnostics.info("variant-registry base '%s' kept out of '%s' family (heads its own variant family)",
                        member, rootId);
                    continue;
                }
                families.child(member).put("family_of", rootId);
                diagnostics.info("family link: %s -> %s (shared %s) [D40]", member, rootId, cluster.getKey());
            }
        }
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
     * The family's primary geometry manifest key, read from the mandatory age axis' {@code options.adult}
     * (the family baseline), or {@code null} when any node on that path is absent (an unresolvable
     * family links nothing).
     */
    private static @Nullable String primaryGeometry(@NotNull JsonNode family) {
        JsonNode axes = family.get("axes");
        JsonNode age = axes == null ? null : axes.get("age");
        JsonNode options = age == null ? null : age.get("options");
        JsonNode adult = options == null ? null : options.get("adult");
        return adult == null ? null : adult.getString("geometry");
    }

    /** The namespace-stripped local id of a family key. */
    private static @NotNull String localId(@NotNull String familyId) {
        return familyId.startsWith(VanillaSourceClasses.Paths.MINECRAFT_NAMESPACE)
            ? familyId.substring(VanillaSourceClasses.Paths.MINECRAFT_NAMESPACE.length())
            : familyId;
    }

}
