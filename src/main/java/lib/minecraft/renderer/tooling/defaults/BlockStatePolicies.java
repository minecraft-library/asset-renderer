package lib.minecraft.renderer.tooling.defaults;

import lib.minecraft.renderer.tooling.policy.AsmContext;
import lib.minecraft.renderer.tooling.policy.Navigation;
import lib.minecraft.renderer.tooling.policy.NavigationPolicy;
import org.jetbrains.annotations.NotNull;

/**
 * The block-defaults flow's complete policy roster - the one genuinely undetectable
 * default-state fact. Never fetches ({@code PolicyPurityTest}).
 */
enum BlockStatePolicies implements NavigationPolicy {

    /**
     * The any()-default of a declared-but-unset boolean property is {@code false}. NOT
     * first-possible-value: {@code BooleanProperty.VALUES = List.of(true, false)} is TRUE-first
     * (javap); the observed {@code false} comes from loop-driven {@code setValue(..., false)}
     * shapes ({@code MultifaceBlock.getDefaultMultifaceState}) that a static bytewalk cannot
     * decode. Harness-verified.
     */
    BOOLEAN_DEFAULT(
        "false",
        "any()-default for declared-but-unset booleans = false, harness-verified. BooleanProperty.VALUES ="
            + " List.of(true, false) is TRUE-first (javap); the false comes from loop-driven setValue(..,false)"
            + " shapes (MultifaceBlock.getDefaultMultifaceState) a static bytewalk cannot decode");

    private final @NotNull String value;
    private final @NotNull String provenance;

    BlockStatePolicies(@NotNull String value, @NotNull String provenance) {
        this.value = value;
        this.provenance = provenance;
    }

    @Override
    public @NotNull Navigation navigate(@NotNull AsmContext context) {
        return new Navigation.Value<>(this.value, this.provenance);
    }

    /** The declared any()-default of a declared-but-unset boolean property ({@code "false"}). */
    static @NotNull String booleanDefault() {
        return BOOLEAN_DEFAULT.value;
    }

}
