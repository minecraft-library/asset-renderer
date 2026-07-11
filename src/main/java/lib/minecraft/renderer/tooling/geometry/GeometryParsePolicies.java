package lib.minecraft.renderer.tooling.geometry;

import lib.minecraft.renderer.tooling.policy.AsmContext;
import lib.minecraft.renderer.tooling.policy.Navigation;
import lib.minecraft.renderer.tooling.policy.NavigationPolicy;
import org.jetbrains.annotations.NotNull;

/**
 * The geometry parser's declared facts (SPINE 2.1 roster rows P29 / P30) - consulted only
 * where generic detection cannot decide; never fetches ({@code PolicyPurityTest}).
 */
enum GeometryParsePolicies implements NavigationPolicy {

    /**
     * P29 - the package gate for following {@code invokestatic} calls out of a factory body:
     * shared mesh helpers live under the model package ({@code HumanoidModel.createMesh} and
     * friends); calls into geometry-primitive packages ({@code geom/}) are data constructors
     * the walk decodes in place, never follows.
     */
    INVOKESTATIC_FOLLOW_PACKAGE_GATE(
        "net/minecraft/client/model/",
        "P29: shared mesh factories live under client/model/ - following calls elsewhere"
            + " (geom/, util/) walks data constructors as if they were mesh code"),

    /**
     * P30 - the {@code PartNames}-style {@code name+i} synthesis fallback for procedural
     * bone naming when the string-concat recipe cannot be recovered. Doc-12 F3: kept behind
     * a Diagnostics WARN until the generic path is proven clean on 26.1, then DELETED - a
     * silent fallback masking a working walk is the X8 lesson.
     */
    PART_NAMES_INDEX_FALLBACK(
        "name+i",
        "P30: legacy PartNames synthesis - WARN-gated fallback, delete on a clean 26.1 proof run (doc-12 F3)");

    private final @NotNull String value;
    private final @NotNull String provenance;

    GeometryParsePolicies(@NotNull String value, @NotNull String provenance) {
        this.value = value;
        this.provenance = provenance;
    }

    /**
     * The declared fact, for the parser's in-package consult (the parser has no
     * {@code AsmContext} frame - it receives cache + request + diagnostics only).
     */
    @NotNull String value() {
        return this.value;
    }

    @Override
    public @NotNull Navigation navigate(@NotNull AsmContext context) {
        return new Navigation.Value<>(this.value, this.provenance);
    }

}
