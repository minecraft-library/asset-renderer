package lib.minecraft.renderer.tooling.policy;

import org.jetbrains.annotations.NotNull;

/**
 * The policy SPI - consulted AFTER generic detection misses, never before, never instead.
 *
 * <p>Concrete policies are flow-local package-private enums; this package holds the SPI
 * only - zero concrete facts live here. Policies never fetch: no ClassKit, no ClassNodeCache,
 * no ASM imports.
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
