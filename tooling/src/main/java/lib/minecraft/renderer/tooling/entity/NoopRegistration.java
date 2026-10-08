package lib.minecraft.renderer.tooling.entity;

import org.jetbrains.annotations.NotNull;

/**
 * A registered entity type vanilla's renderer registry binds to {@code NoopRenderer}, which declares no
 * draw of its own - a type vanilla draws nothing for.
 *
 * <p>It never joins the resolver chain, which resolves a row's mesh from the layers its renderer bakes:
 * this renderer bakes none, so the chain would fail the flow on it. {@link EntityRegistryWalk} writes
 * its row directly instead, naming the renderer and an adult form with no mesh and no texture.
 *
 * @param entityId the namespaced registry id, e.g. {@code minecraft:marker} - the row's key
 */
public record NoopRegistration(@NotNull String entityId) implements EntityRegistration {}
