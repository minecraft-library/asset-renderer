package lib.minecraft.renderer.tooling.geometry;

import lib.minecraft.renderer.tooling.policy.AsmContext;
import lib.minecraft.renderer.tooling.policy.Navigation;
import lib.minecraft.renderer.tooling.policy.NavigationPolicy;
import org.jetbrains.annotations.NotNull;

/**
 * The geometry parser's declared facts - consulted only where generic detection cannot
 * decide; never fetches ({@code PolicyPurityTest}).
 */
enum GeometryParsePolicies implements NavigationPolicy {

    /**
     * The package gate for following {@code invokestatic} calls out of a factory body:
     * shared mesh helpers live under the model package ({@code HumanoidModel.createMesh} and
     * friends); calls into geometry-primitive packages ({@code geom/}) are data constructors
     * the walk decodes in place, never follows.
     */
    INVOKESTATIC_FOLLOW_PACKAGE_GATE(
        "net/minecraft/client/model/",
        "shared mesh factories live under client/model/ - following calls elsewhere (geom/, util/) walks data"
            + " constructors as if they were mesh code"),

    /**
     * The {@code PartNames}-style {@code name+i} synthesis fallback for procedural bone
     * naming when the string-concat recipe cannot be recovered. Kept behind a Diagnostics
     * WARN until the generic path is proven clean on 26.1, then deleted - a silent fallback
     * masking a working walk hides real regressions.
     */
    PART_NAMES_INDEX_FALLBACK(
        "name+i",
        "vanilla's PartNames names procedural bones name+i; kept WARN-gated so a silent fallback cannot"
            + " mask a working generic walk, and deleted once that walk proves clean");

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
