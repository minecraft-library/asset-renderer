package lib.minecraft.renderer.tooling.blockentity;

import lib.minecraft.renderer.tooling.policy.AsmContext;
import lib.minecraft.renderer.tooling.policy.Navigation;
import lib.minecraft.renderer.tooling.policy.NavigationPolicy;
import org.jetbrains.annotations.NotNull;

import java.util.Set;

/**
 * The block-geometry escape hatches (SPINE 2.1 roster P38): the "which layer factory is THE
 * whole-block geometry vs a decorative sub-layer" allow-list the generic ModelLayers x
 * {@link lib.minecraft.renderer.tooling.vanilla.LayerDefinitionIndex} join can not settle on
 * its own (conduit eye/wind/cage, copper-golem poses all resolve to real factories). Declared
 * here with provenance; never fetches ({@code PolicyPurityTest}).
 *
 * <p>The roster is the legacy {@code SourceDiscovery.PRIMARY_METHOD_NAMES} (:162-178). Its own
 * javadoc (:156-160) sketches a derivation - infer from {@code BlockEntityType.validBlocks}
 * which layer is the block's geometry - but calls it brittle; the audit disposition kept it a
 * navigation policy with a diag-WARN for any layer matched by neither the generic join nor this
 * allow-list (08 3 row 4). Method names are not vanilla type literals, so this is a genuine
 * escape hatch rather than a stray-literal quarantine.
 */
enum BlockGeometryPolicies implements NavigationPolicy {

    /**
     * P38 - the 15-name primary-layer allow-list. A block-entity renderer references one or more
     * {@code ModelLayers} fields; the ones whose factory method appears here are the whole-block
     * geometry, the rest are decorative sub-layers that never emit a {@code models} entry.
     */
    PRIMARY_METHOD_NAMES(
        Set.of(
            "createSingleBodyLayer", "createBodyLayer", "createBoxLayer", "createShellLayer",
            "createShellMesh", "createBaseLayer", "createSidesLayer", "createHeadLayer",
            "createFootLayer", "createHeadModel", "createFlagLayer", "createSignLayer",
            "createHangingSignLayer", "createMobHeadLayer", "createHumanoidHeadLayer"),
        "P38: the primary-vs-decorative layer allow-list - legacy SourceDiscovery.PRIMARY_METHOD_NAMES"
            + " :162-178; its own :156-160 javadoc sketches a validBlocks-derived alternative but calls"
            + " it brittle (conduit eye/wind/cage, copper-golem poses would misclassify), so it stays a"
            + " declared escape hatch with a diag-WARN on any layer matched by neither path");

    private final @NotNull Object value;
    private final @NotNull String provenance;

    BlockGeometryPolicies(@NotNull Object value, @NotNull String provenance) {
        this.value = value;
        this.provenance = provenance;
    }

    @Override
    public @NotNull Navigation navigate(@NotNull AsmContext context) {
        return new Navigation.Value<>(this.value, this.provenance);
    }

    /**
     * Whether a layer factory method is a whole-block primary (as opposed to a decorative
     * sub-layer).
     *
     * @param factoryMethod the layer factory method name
     * @return {@code true} when the method is in the P38 primary allow-list
     */
    @SuppressWarnings("unchecked")
    static boolean isPrimary(@NotNull String factoryMethod) {
        return ((Set<String>) PRIMARY_METHOD_NAMES.value).contains(factoryMethod);
    }

}
