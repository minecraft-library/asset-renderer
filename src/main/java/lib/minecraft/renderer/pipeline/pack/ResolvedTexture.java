package lib.minecraft.renderer.pipeline.pack;

import lib.minecraft.renderer.asset.ResourceId;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;

/**
 * The outcome of a texture resolution: the winning pack, the resolved id, and the absolute on-disk
 * path of the PNG the caller decodes. Resolution has already applied the namespace/pack-id dispatch
 * and the within-pack root walk (last existing copy wins); the caller only decodes and memoises the
 * bytes, keyed by {@code (pack, id)}.
 *
 * @param pack the id of the pack that supplied the winning PNG
 * @param id the resolved namespaced texture id
 * @param file the absolute path of the PNG to decode
 */
public record ResolvedTexture(@NotNull PackId pack, @NotNull ResourceId id, @NotNull Path file) {}
