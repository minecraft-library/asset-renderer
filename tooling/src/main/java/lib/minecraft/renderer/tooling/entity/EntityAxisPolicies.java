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
     * The coordinate of the natural-size set. The spawn finaliser draws one value below a literal
     * bound and shifts a literal base left by it, so the set is that base shifted by each value the
     * bound admits; the entities carrying it are the coordinate's owner and everything deriving from
     * it, which inherits the same finaliser.
     */
    NATURAL_SIZE_SET(
        new Navigation.At("net/minecraft/world/entity/monster/Slime", "finalizeSpawn", null),
        "the sizes are the bounded draw and the shift the spawn finaliser applies to it; scale-per-size is"
            + " proportional per SlimeRenderer.scale at squish 0"),

    /**
     * The render-axis name vocabulary shared with the runtime loaders. This is the
     * pipeline's own option vocabulary, not vanilla's - an axis-name registry so tooling
     * and pipeline never drift on spelling, consumed by the overlay / layer resolvers.
     */
    AXIS_NAME_VOCABULARY(
        List.of("wool_color", "collar_color", "pattern", "pattern_color", "type",
            "profession", "profession_level", "weathering", "markings", "sheared"),
        "tooling<->runtime shared option vocabulary"),

    /**
     * The state-key precedence selecting a variant option's default texture and the state
     * axis default: {@code wild} first, then the single-asset {@code primary}, then the
     * first walked key. This mirrors WolfRenderer's default branch and is declared
     * explicitly here rather than derived from bytecode.
     */
    STATE_PRECEDENCE(
        List.of("wild", "primary"),
        "render-default state pick, wild->primary->first"),

    /**
     * The coordinate the size-axis option domain is read at. The vocabulary is this pipeline's own
     * and the coordinate names one of its consumers - a vanilla enum spelling the same three ids as
     * its members' serialized names - so the walk reads those ids in the enum's declaration order.
     * That order carries the default-pick rule: the default is the option-less domain member, the
     * mesh the family's base {@code geometry} already renders (pufferfish {@code large}, salmon
     * {@code medium}, slime {@code small}). The emitted {@code options} key order follows the domain
     * order and IS the domain, with no per-family {@code values} list.
     */
    SIZE_DOMAIN(
        new Navigation.At("net/minecraft/world/entity/animal/fish/Salmon$Variant", "<clinit>", null),
        "the option vocabulary is this pipeline's own and the coordinate's enum spells the same three ids;"
            + " the walk reads each member's serialized id in declaration order, and a member bound by"
            + " aliasing another pushes no id and is no part of it"),

    /**
     * The shape / size axis membership plus option naming: which entities carry a
     * multi-mesh body axis and which axis it is. Detection of the extra body meshes is
     * generic (via renderer-constructor references); the axis classification and the
     * field-suffix-to-option naming ({@code PUFFERFISH_MEDIUM} to {@code medium},
     * {@code ARMOR_STAND_SMALL} to {@code small}, matched against the {@link #SIZE_DOMAIN}
     * domain; shape domain fixed {@code [small, large]}) are declared here.
     *
     * <p>The armor stand belongs here rather than under the age axis because vanilla's own word
     * for the selection is {@code Small} - the flag it persists, the accessor it reads and the
     * layer it registers all say so. Its renderer routes that flag through {@code isBaby} to reach
     * the armor layer and then carves itself back out of the aged-down sheets and trim, which is a
     * detail of vanilla's plumbing rather than a statement that a small stand is a young one.
     */
    SHAPE_SIZE_MEMBERSHIP(
        Map.of("minecraft:pufferfish", "size", "minecraft:salmon", "size",
            "minecraft:armor_stand", "size", "minecraft:tropical_fish", "shape"),
        "'the only vanilla entity with distinct body shapes/sizes' judgments; the detection is generic,"
            + " the membership and the naming are declared");

    private final @NotNull Object value;
    private final @NotNull String provenance;

    EntityAxisPolicies(@NotNull Object value, @NotNull String provenance) {
        this.value = value;
        this.provenance = provenance;
    }

    @Override
    public @NotNull Navigation navigate(@NotNull AsmContext context) {
        if (this.value instanceof Navigation coordinate) return coordinate;
        return new Navigation.Value<>(this.value, this.provenance);
    }

    /**
     * The declared string-list fact of a list-valued constant ({@link #AXIS_NAME_VOCABULARY},
     * {@link #STATE_PRECEDENCE}).
     */
    @SuppressWarnings("unchecked")
    @NotNull List<String> strings() {
        return (List<String>) this.value;
    }

    /**
     * The coordinate the size-axis option domain is read at ({@link #SIZE_DOMAIN}).
     */
    static Navigation.@NotNull At sizeDomainCoordinate() {
        return (Navigation.At) SIZE_DOMAIN.value;
    }

    /**
     * The coordinate the natural-size set and its membership are recovered at
     * ({@link #NATURAL_SIZE_SET}).
     */
    static Navigation.@NotNull At naturalSizeCoordinate() {
        return (Navigation.At) NATURAL_SIZE_SET.value;
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
