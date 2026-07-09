package lib.minecraft.renderer.tooling2.entity;

import lib.minecraft.renderer.tooling2.policy.AsmContext;
import lib.minecraft.renderer.tooling2.policy.Navigation;
import lib.minecraft.renderer.tooling2.policy.NavigationPolicy;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * The axis resolvers' declared facts (SPINE 2.1 roster rows P1 / P9 / P22 / P27 / P28 /
 * P37) - consulted only where generic detection cannot decide; never fetches
 * ({@code PolicyPurityTest}).
 */
enum EntityAxisPolicies implements NavigationPolicy {

    /**
     * P1 - the slime / magma_cube natural-size set {1, 2, 4} plus the size-axis membership
     * of the two entities. The set comes from server-side spawn logic ({@code 1 << rand(3)})
     * the client jar this pipeline stands on cannot see (doc-12 F1: kept as policy - the
     * one-jar invariant is worth more than one declared fact).
     */
    NATURAL_SIZE_SET(
        Map.of("minecraft:slime", List.of(1, 2, 4), "minecraft:magma_cube", List.of(1, 2, 4)),
        "P1: natural sizes 1<<rand(3) live in server world-entity code, not the client jar"
            + " (legacy ToolingEntityModels:524-528); scale-per-size is proportional per SlimeRenderer.scale at squish 0 [D5]"),

    /**
     * P9 - the render-axis name vocabulary shared with the runtime loaders. OUR option
     * vocabulary, not vanilla's - an axis-name registry so tooling and pipeline never drift
     * on spelling (consumed by the overlay / layer resolvers, roster rows 13-15).
     */
    AXIS_NAME_VOCABULARY(
        List.of("wool_color", "collar_color", "pattern", "pattern_color", "type",
            "profession", "profession_level", "weathering", "markings", "sheared"),
        "P9: tooling<->runtime shared option vocabulary (legacy EntityOverlayResolver:490,520-524,876)"),

    /**
     * P22 - the state-key precedence selecting a variant option's default texture and the
     * state axis default: {@code wild} first, then the single-asset {@code primary}, then
     * the first walked key. Derivable in principle from WolfRenderer's default branch;
     * declared for bring-up (doc-12 F2 - derivation attempt deferred past entity parity).
     */
    STATE_PRECEDENCE(
        List.of("wild", "primary"),
        "P22: render-default state pick, wild->primary->first (legacy EntityVariantResolver:114-136)"),

    /**
     * P27 - the alpha-first-unconditional default-variant tiebreak when a data-variant
     * holder class declares no {@code DEFAULT}: variants whose {@code spawn_conditions}
     * entries all lack a {@code condition} sub-object, ordered alphabetically, first wins.
     * A documented derivation RULE (mirrors vanilla fresh-spawn selection at zero state -
     * structure / moon / biome gated variants drop out), not a value.
     */
    ALPHA_FIRST_UNCONDITIONAL_TIEBREAK(
        "alphabetical-unconditional",
        "P27: mirrors vanilla runtime selection at a fresh-spawn zero state - cat all_black"
            + " carries structure+moon conditions, black wins (legacy EntityRuntimeJsonWriter:158-176)"),

    /**
     * P28 - the size-axis option domain, in declared order, plus the default-pick rule: the
     * default is the option-less domain member (the mesh the family's base {@code geometry}
     * already renders - pufferfish {@code large}, salmon {@code medium}, slime {@code small}).
     * Invented vocabulary; option JSON member order follows this domain order.
     */
    SIZE_DOMAIN(
        List.of("small", "medium", "large"),
        "P28: invented size vocabulary + default = option-less member (legacy EntityFamilyJsonWriter:261-264)"),

    /**
     * P37 - the shape / size axis membership plus option naming: which entities carry a
     * multi-mesh body axis and which axis it is. Detection of the extra body meshes is
     * generic ([D2] / [D3] renderer-ctor references); the axis CLASSIFICATION and the
     * field-suffix-to-option naming ({@code PUFFERFISH_MEDIUM} to {@code medium}, matched
     * against the P28 domain; shape domain fixed {@code [small, large]}) are declared.
     */
    SHAPE_SIZE_MEMBERSHIP(
        Map.of("minecraft:pufferfish", "size", "minecraft:salmon", "size", "minecraft:tropical_fish", "shape"),
        "P37: 'the only vanilla entity with distinct body shapes/sizes' judgments"
            + " (legacy ToolingEntityModels:414-448); detection generic per D2/D3, membership + naming declared");

    private final @NotNull Object value;
    private final @NotNull String provenance;

    EntityAxisPolicies(@NotNull Object value, @NotNull String provenance) {
        this.value = value;
        this.provenance = provenance;
    }

    @Override
    public @NotNull Navigation navigate(@NotNull AsmContext context) {
        return new Navigation.Value<>(this.value, this.provenance);
    }

    /**
     * The declared string-list fact of a list-valued row (P9 / P22 / P28).
     */
    @SuppressWarnings("unchecked")
    @NotNull List<String> strings() {
        return (List<String>) this.value;
    }

    /**
     * The P1 natural-size set for an entity, or {@code null} when the entity is not a
     * declared member.
     *
     * @param entityId the namespaced entity id
     * @return the ordered natural sizes, or {@code null}
     */
    @SuppressWarnings("unchecked")
    static @Nullable List<Integer> naturalSizesFor(@NotNull String entityId) {
        return ((Map<String, List<Integer>>) NATURAL_SIZE_SET.value).get(entityId);
    }

    /**
     * The P37 axis membership for an entity: {@code "size"}, {@code "shape"}, or
     * {@code null} when the entity carries neither.
     *
     * @param entityId the namespaced entity id
     * @return the declared axis name, or {@code null}
     */
    @SuppressWarnings("unchecked")
    static @Nullable String shapeSizeAxisFor(@NotNull String entityId) {
        return ((Map<String, String>) SHAPE_SIZE_MEMBERSHIP.value).get(entityId);
    }

}
