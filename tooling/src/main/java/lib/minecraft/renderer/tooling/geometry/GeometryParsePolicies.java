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
            + " constructors as if they were mesh code");

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
