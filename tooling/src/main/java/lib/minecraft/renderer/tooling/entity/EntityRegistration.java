package lib.minecraft.renderer.tooling.entity;

import org.jetbrains.annotations.NotNull;

/**
 * One registered entity type the model table carries a row for, as {@link EntityRegistryDiscovery}
 * answers it in registry order - a living mob joined with its renderer, which the resolver chain
 * reads, or a type vanilla's renderer registry binds to its no-op renderer, which draws nothing.
 */
public sealed interface EntityRegistration permits EntitySubject, NoopRegistration {

    /**
     * Answers the namespaced registry id, the key of the row the model table carries for this type.
     *
     * @return the namespaced id, e.g. {@code minecraft:wolf}
     */
    @NotNull String entityId();

}
