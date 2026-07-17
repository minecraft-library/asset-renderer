package lib.minecraft.renderer.tooling.entity;

import lib.minecraft.renderer.tooling.policy.AsmContext;
import lib.minecraft.renderer.tooling.policy.Navigation;
import lib.minecraft.renderer.tooling.policy.NavigationPolicy;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * Declared facts consulted by the axis resolvers only where generic detection cannot
 * decide; never fetches ({@code PolicyPurityTest}).
 */
enum EntityAxisPolicies implements NavigationPolicy {

    /**
     * The slime / magma_cube natural-size set {1, 2, 4} plus the size-axis membership of the
     * two entities. The set comes from server-side spawn logic ({@code 1 << rand(3)}) that
     * the client jar this pipeline reads cannot see, so it is declared here rather than
     * derived.
     */
    NATURAL_SIZE_SET(
        Map.of("minecraft:slime", List.of(1, 2, 4), "minecraft:magma_cube", List.of(1, 2, 4)),
        "P1: natural sizes 1<<rand(3) live in server world-entity code, not the client jar"
            + " (legacy ToolingEntityModels:524-528); scale-per-size is proportional per SlimeRenderer.scale at squish 0 [D5]"),

    /**
     * The render-axis name vocabulary shared with the runtime loaders. This is the
     * pipeline's own option vocabulary, not vanilla's - an axis-name registry so tooling
     * and pipeline never drift on spelling, consumed by the overlay / layer resolvers.
     */
    AXIS_NAME_VOCABULARY(
        List.of("wool_color", "collar_color", "pattern", "pattern_color", "type",
            "profession", "profession_level", "weathering", "markings", "sheared"),
        "P9: tooling<->runtime shared option vocabulary (legacy EntityOverlayResolver:490,520-524,876)"),

    /**
     * The state-key precedence selecting a variant option's default texture and the state
     * axis default: {@code wild} first, then the single-asset {@code primary}, then the
     * first walked key. This mirrors WolfRenderer's default branch and is declared
     * explicitly here rather than derived from bytecode.
     */
    STATE_PRECEDENCE(
        List.of("wild", "primary"),
        "P22: render-default state pick, wild->primary->first (legacy EntityVariantResolver:114-136)"),

    /**
     * The alpha-first-unconditional default-variant tiebreak used when a data-variant
     * holder class declares no {@code DEFAULT}: variants whose {@code spawn_conditions}
     * entries all lack a {@code condition} sub-object, ordered alphabetically, first wins.
     * This mirrors vanilla fresh-spawn selection at a zero state, where structure / moon /
     * biome gated variants drop out.
     */
    ALPHA_FIRST_UNCONDITIONAL_TIEBREAK(
        "alphabetical-unconditional",
        "P27: mirrors vanilla runtime selection at a fresh-spawn zero state - cat all_black"
            + " carries structure+moon conditions, black wins (legacy EntityRuntimeJsonWriter:158-176)"),

    /**
     * The size-axis option domain, in declared order, plus the default-pick rule: the
     * default is the option-less domain member (the mesh the family's base {@code geometry}
     * already renders - pufferfish {@code large}, salmon {@code medium}, slime {@code small}).
     * This is invented vocabulary; the emitted {@code options} key order follows this domain
     * order and IS the domain, with no per-family {@code values} list.
     */
    SIZE_DOMAIN(
        List.of("small", "medium", "large"),
        "P28: invented size vocabulary + default = option-less member (legacy EntityFamilyJsonWriter:261-264)"),

    /**
     * The shape / size axis membership plus option naming: which entities carry a
     * multi-mesh body axis and which axis it is. Detection of the extra body meshes is
     * generic (via renderer-constructor references); the axis classification and the
     * field-suffix-to-option naming ({@code PUFFERFISH_MEDIUM} to {@code medium}, matched
     * against the {@link #SIZE_DOMAIN} domain; shape domain fixed {@code [small, large]})
     * are declared here.
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
     * The declared string-list fact of a list-valued constant ({@link #AXIS_NAME_VOCABULARY},
     * {@link #STATE_PRECEDENCE}, {@link #SIZE_DOMAIN}).
     */
    @SuppressWarnings("unchecked")
    @NotNull List<String> strings() {
        return (List<String>) this.value;
    }

    /**
     * The natural-size set for an entity ({@link #NATURAL_SIZE_SET}), or {@code null} when
     * the entity is not a declared member.
     *
     * @param entityId the namespaced entity id
     * @return the ordered natural sizes, or {@code null}
     */
    @SuppressWarnings("unchecked")
    static @Nullable List<Integer> naturalSizesFor(@NotNull String entityId) {
        return ((Map<String, List<Integer>>) NATURAL_SIZE_SET.value).get(entityId);
    }

    /**
     * The axis membership for an entity ({@link #SHAPE_SIZE_MEMBERSHIP}): {@code "size"},
     * {@code "shape"}, or {@code null} when the entity carries neither.
     *
     * @param entityId the namespaced entity id
     * @return the declared axis name, or {@code null}
     */
    @SuppressWarnings("unchecked")
    static @Nullable String shapeSizeAxisFor(@NotNull String entityId) {
        return ((Map<String, String>) SHAPE_SIZE_MEMBERSHIP.value).get(entityId);
    }

}
