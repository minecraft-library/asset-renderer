package lib.minecraft.renderer.tooling.policy;

import org.jetbrains.annotations.NotNull;

/**
 * The policy SPI - consulted AFTER generic detection misses, never before, never instead
 * (the match-then-navigate calling convention, SPINE 5.5).
 *
 * <p>Concrete policies are flow-local package-private enums (decision 8); this package holds
 * the SPI only - zero concrete facts live here. Policies never fetch: no AsmKit, no
 * ClassNodeCache, no ASM imports ({@code PolicyPurityTest}, decision 9).
 */
public interface NavigationPolicy {

    /**
     * Consults this policy for the given frame.
     *
     * @param context the consultation frame
     * @return where to look, a declared fact, or {@link Navigation.None} when generic
     *     detection stands
     */
    @NotNull Navigation navigate(@NotNull AsmContext context);

}
