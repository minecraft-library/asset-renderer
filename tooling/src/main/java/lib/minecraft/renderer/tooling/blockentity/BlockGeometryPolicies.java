package lib.minecraft.renderer.tooling.blockentity;

import dev.simplified.annotations.AccessLevel;
import dev.simplified.annotations.RequiredArgsConstructor;
import lib.minecraft.renderer.tooling.policy.AsmContext;
import lib.minecraft.renderer.tooling.policy.Navigation;
import lib.minecraft.renderer.tooling.policy.NavigationPolicy;
import lib.minecraft.renderer.tooling.vanilla.LayerDefinitionIndex;
import org.jetbrains.annotations.NotNull;

import java.util.Set;

/**
 * The block-geometry escape hatches: the "which layer factory is THE whole-block geometry vs a
 * decorative sub-layer" allow-list the generic ModelLayers x
 * {@link LayerDefinitionIndex} join can not settle on
 * its own (conduit eye/wind/cage, copper-golem poses all resolve to real factories). Declared
 * here with provenance; never fetches ({@code PolicyPurityTest}).
 *
 * <p>Deriving primary-vs-decorative from {@code BlockEntityType.validBlocks} - inferring which
 * layer is the block's geometry - is brittle, so this stays a navigation policy with a diag-WARN
 * for any layer matched by neither the generic join nor this allow-list. Method names are not
 * vanilla type literals, so this is a genuine escape hatch rather than a stray-literal
 * quarantine.
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
enum BlockGeometryPolicies implements NavigationPolicy {

    /**
     * The 15-name primary-layer allow-list. A block-entity renderer references one or more
     * {@code ModelLayers} fields; the ones whose factory method appears here are the whole-block
     * geometry, the rest are decorative sub-layers that never emit a {@code models} entry.
     */
    PRIMARY_METHOD_NAMES(
        Set.of(
            "createSingleBodyLayer", "createBodyLayer", "createBoxLayer", "createShellLayer",
            "createShellMesh", "createBaseLayer", "createSidesLayer", "createHeadLayer",
            "createFootLayer", "createHeadModel", "createFlagLayer", "createSignLayer",
            "createHangingSignLayer", "createMobHeadLayer", "createHumanoidHeadLayer"),
        "the primary-vs-decorative layer allow-list: the validBlocks-derived alternative is brittle - conduit"
            + " eye / wind / cage and the copper-golem poses would misclassify - so this stays a declared escape"
            + " hatch, with a diag-WARN on any layer matched by neither path");

    private final @NotNull Object value;
    private final @NotNull String provenance;

    @Override
    public @NotNull Navigation navigate(@NotNull AsmContext context) {
        return new Navigation.Value<>(this.value, this.provenance);
    }

    /**
     * Whether a layer factory method is a whole-block primary (as opposed to a decorative
     * sub-layer).
     *
     * @param factoryMethod the layer factory method name
     * @return {@code true} when the method is in the primary allow-list
     */
    @SuppressWarnings("unchecked")
    static boolean isPrimary(@NotNull String factoryMethod) {
        return ((Set<String>) PRIMARY_METHOD_NAMES.value).contains(factoryMethod);
    }

}
