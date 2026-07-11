package lib.minecraft.renderer.visual;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lib.minecraft.renderer.pipeline.load.ResourceDocument;
import lib.minecraft.renderer.pipeline.load.BundledResources;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reconciles the vanilla-reference-harness per-variant PNG names (byte-stable ground truth, one file per
 * pseudo-id like {@code minecraft:cow_cold}) with the Java pipeline's entity keyset for the entity parity
 * sweep, so the sweep enumerates the SAME subjects whether {@code variant} is id-encoded (a first-class
 * render id) or option-encoded ({@code minecraft:cow} + an {@link lib.minecraft.renderer.option.EntityAppearance}
 * variant selection). This is the axis-unification #3 parity-ref mapping: without it, flipping
 * {@code variant} to option-encoded silently drops every variant family from the sweep's
 * {@code java_keys ∩ vanilla_keys} enumeration.
 *
 * <p>The variant-family structure ({@code family -> {default, options}}) is read from
 * {@code entity_models.json}'s {@code axes.variant} node, whose {@code default} and {@code options}
 * key-order are unchanged by the id-encoding flip - only the {@code id_encoded} flag flips. Each ref is
 * resolved to a render target by <b>probing the actual Java keyset</b> rather than the flag, so one mapping
 * spans both states:
 *
 * <ol>
 *   <li>{@link #classifyVariant} interprets the ref as a variant selection. A {@code minecraft:cow_cold}
 *       names an explicit option (matching the longest family prefix so {@code zombie_nautilus_warm} does
 *       not mis-bind); a plain {@code minecraft:axolotl} names a family with no per-variant refs and
 *       resolves to the default coat. If the pseudo-id {@code minecraft:cow_cold} is a Java key, drive it
 *       directly (id-encoded state); else if the base {@code minecraft:cow} is a Java key, drive it with
 *       {@code variant=cold} (option-encoded state).</li>
 *   <li>Otherwise a ref that is itself a Java key is a plain (non-variant) subject driven by its id.</li>
 *   <li>A ref with no Java render target (a mob the pipeline does not model) is dropped, surfacing in
 *       {@link #unresolved}.</li>
 * </ol>
 *
 * <p>Relative to the legacy {@code java_keys ∩ vanilla_keys} enumeration this ADDS the variant-superset
 * families whose only harness ref is the plain base (axolotl / llama / panda / rabbit / trader_llama) -
 * previously silently un-compared because the harness ships no per-variant ref and the Java side had no
 * plain base key. The mapping drives their default coat against that plain ref. A plain family ref for a
 * family that ALSO ships per-variant refs (mooshroom's {@code mooshroom} alongside {@code mooshroom_red} /
 * {@code mooshroom_brown}) is a redundant re-render of the default coat on a different harness canvas; it
 * is {@link #deduplicated} (skipped, not dropped) since that coat is already compared under its own
 * per-variant ref.
 */
public final class ParityRefMapping {

    private static final @NotNull String NAMESPACE = "minecraft:";
    private static final @NotNull String MODELS_RESOURCE = "entity_models.json";

    /** The five variant families whose only harness reference is the plain base (default coat). */
    static final @NotNull List<String> SUPERSET_FAMILIES = List.of(
        "minecraft:axolotl", "minecraft:llama", "minecraft:panda", "minecraft:rabbit", "minecraft:trader_llama");

    /** A variant family's render-selectable options and its default option. */
    record VariantFamily(@NotNull String defaultOption, @NotNull List<String> options) {}

    /**
     * One parity subject: the harness reference to compare against, and how to drive the renderer for it.
     *
     * @param refId the vanilla reference entity id (e.g. {@code minecraft:cow_cold})
     * @param entityId the Java entity id to render (the pseudo-id when id-encoded, else the base id)
     * @param variant the option to select via {@code EntityAppearance.variant}, empty when the entity id
     *     already encodes the variant (id-encoded state) or the subject is not a variant
     */
    record Subject(@NotNull String refId, @NotNull String entityId, @NotNull Optional<String> variant) {}

    private final @NotNull Map<String, VariantFamily> variantFamilies;

    private ParityRefMapping(@NotNull Map<String, VariantFamily> variantFamilies) {
        this.variantFamilies = variantFamilies;
    }

    /**
     * Builds a mapping from the variant-family structure in {@code entity_models.json}.
     *
     * @return the mapping, or an empty mapping when the resource is absent
     */
    public static @NotNull ParityRefMapping load() {
        Optional<ResourceDocument> doc = BundledResources.read(MODELS_RESOURCE, BundledResources.MissingPolicy.GRACEFUL_EMPTY,
                                                               Diagnostics.root("parity_ref_mapping", Diagnostics.Output.NONE, null));
        if (doc.isEmpty()) return new ParityRefMapping(Map.of());
        JsonObject root = doc.get().payload().toGson().getAsJsonObject();
        if (!root.has("families")) return new ParityRefMapping(Map.of());
        Map<String, VariantFamily> families = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("families").entrySet()) {
            if (!entry.getValue().isJsonObject()) continue;
            JsonObject family = entry.getValue().getAsJsonObject();
            if (!family.has("axes")) continue;
            JsonObject axes = family.getAsJsonObject("axes");
            if (!axes.has("variant")) continue;
            JsonObject variant = axes.getAsJsonObject("variant");
            List<String> options = new ArrayList<>(variant.getAsJsonObject("options").keySet());
            families.put(entry.getKey(), new VariantFamily(variant.get("default").getAsString(), options));
        }
        return new ParityRefMapping(families);
    }

    /**
     * Resolves every harness ref that has a Java render target into a parity subject, sorted by ref id.
     * Variant families are matched before the plain direct-key path, so a ref is always driven through the
     * variant fold when its family is option-encoded even if a stale pseudo-id key lingers.
     *
     * @param javaKeys the entity ids the Java pipeline currently loads
     * @param vanillaRefIds the harness reference entity ids (from the reference PNG filenames)
     * @return the parity subjects with a resolvable Java render target
     */
    public @NotNull List<Subject> resolve(@NotNull Set<String> javaKeys, @NotNull Set<String> vanillaRefIds) {
        List<Subject> subjects = new ArrayList<>();
        for (String ref : sorted(vanillaRefIds))
            resolveRef(ref, javaKeys, vanillaRefIds).ifPresent(subjects::add);
        return subjects;
    }

    /**
     * The harness refs with NO Java render target and NOT a deduplicated plain family ref - a mob the
     * pipeline does not model. Distinct from {@link #deduplicated}: a dropped ref means a coat is
     * genuinely un-compared, a deduplicated ref means its coat is compared under a per-variant ref.
     */
    public @NotNull List<String> unresolved(@NotNull Set<String> javaKeys, @NotNull Set<String> vanillaRefIds) {
        List<String> out = new ArrayList<>();
        for (String ref : sorted(vanillaRefIds))
            if (resolveRef(ref, javaKeys, vanillaRefIds).isEmpty() && classifyVariant(ref, vanillaRefIds).kind() != Kind.DEDUP_PLAIN)
                out.add(ref);
        return out;
    }

    /**
     * The plain family refs skipped as redundant (their default coat is compared under a per-variant ref).
     * Each entry's family HAS per-variant refs, so no coat is dropped by skipping the plain re-render.
     */
    public @NotNull List<String> deduplicated(@NotNull Set<String> vanillaRefIds) {
        List<String> out = new ArrayList<>();
        for (String ref : sorted(vanillaRefIds))
            if (classifyVariant(ref, vanillaRefIds).kind() == Kind.DEDUP_PLAIN) out.add(ref);
        return out;
    }

    private @NotNull Optional<Subject> resolveRef(@NotNull String ref, @NotNull Set<String> javaKeys, @NotNull Set<String> vanillaRefIds) {
        Match match = classifyVariant(ref, vanillaRefIds);
        return switch (match.kind()) {
            case OPTION -> {
                String pseudoId = match.family() + "_" + match.option();
                if (javaKeys.contains(pseudoId)) yield Optional.of(new Subject(ref, pseudoId, Optional.empty()));
                if (javaKeys.contains(match.family())) yield Optional.of(new Subject(ref, match.family(), Optional.of(match.option())));
                yield Optional.empty();
            }
            case DEDUP_PLAIN -> Optional.empty();
            case NONE -> javaKeys.contains(ref) ? Optional.of(new Subject(ref, ref, Optional.empty())) : Optional.empty();
        };
    }

    /** How a ref relates to a variant family. */
    private enum Kind { OPTION, DEDUP_PLAIN, NONE }

    /** A ref's variant classification: an option pick, a redundant plain ref, or unrelated. */
    private record Match(@NotNull Kind kind, @NotNull String family, @NotNull String option) {
        static final Match NONE = new Match(Kind.NONE, "", "");
        static @NotNull Match option(@NotNull String family, @NotNull String option) { return new Match(Kind.OPTION, family, option); }
        static final Match DEDUP = new Match(Kind.DEDUP_PLAIN, "", "");
    }

    /**
     * Classifies a ref against the variant families. A {@code <family>_<option>} id is an explicit option
     * pick (longest family prefix wins so {@code zombie_nautilus_warm} does not mis-bind to a shorter
     * family). A bare family id is the family's default coat - but ONLY when the family ships no
     * per-variant ref; when it does (mooshroom), the plain ref is a redundant re-render and is
     * deduplicated. Every other ref is unrelated to any variant family.
     */
    private @NotNull Match classifyVariant(@NotNull String ref, @NotNull Set<String> vanillaRefIds) {
        VariantFamily plain = this.variantFamilies.get(ref);
        if (plain != null)
            return hasPerVariantRef(ref, plain, vanillaRefIds) ? Match.DEDUP : Match.option(ref, plain.defaultOption());
        Match best = Match.NONE;
        int bestLen = -1;
        for (Map.Entry<String, VariantFamily> entry : this.variantFamilies.entrySet()) {
            String prefix = entry.getKey() + "_";
            if (!ref.startsWith(prefix)) continue;
            String option = ref.substring(prefix.length());
            if (!entry.getValue().options().contains(option)) continue;
            if (entry.getKey().length() > bestLen) {
                best = Match.option(entry.getKey(), option);
                bestLen = entry.getKey().length();
            }
        }
        return best;
    }

    /** Whether any of a family's options ships a per-variant harness ref ({@code <family>_<option>}). */
    private static boolean hasPerVariantRef(@NotNull String family, @NotNull VariantFamily vf, @NotNull Set<String> vanillaRefIds) {
        for (String option : vf.options())
            if (vanillaRefIds.contains(family + "_" + option)) return true;
        return false;
    }

    private static @NotNull List<String> sorted(@NotNull Set<String> ids) {
        List<String> out = new ArrayList<>(ids);
        out.sort(String::compareTo);
        return out;
    }

    /**
     * The legacy sweep enumeration this mapping must never shrink: the plain intersection of Java keys and
     * harness ref ids, one subject per shared id.
     *
     * @param javaKeys the entity ids the Java pipeline loads
     * @param vanillaRefIds the harness reference entity ids
     * @return the ref ids in {@code javaKeys ∩ vanillaRefIds}
     */
    public static @NotNull Set<String> legacyIntersection(@NotNull Set<String> javaKeys, @NotNull Set<String> vanillaRefIds) {
        Set<String> out = new java.util.TreeSet<>();
        for (String ref : vanillaRefIds)
            if (javaKeys.contains(ref)) out.add(ref);
        return out;
    }

    /** Whether the mapping loaded any variant families (false only when the bundled resource is absent). */
    boolean hasVariantFamilies() {
        return !this.variantFamilies.isEmpty();
    }

    /** Prefixes the {@code minecraft:} namespace onto a bare entity local id. */
    static @NotNull String namespaced(@NotNull String localId) {
        return NAMESPACE + localId;
    }
}
