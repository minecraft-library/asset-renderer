package lib.minecraft.renderer.pipeline.pack;

import org.jetbrains.annotations.NotNull;

/**
 * The renderer's evaluation target for a {@code catharsis:version} overlay condition - the pack
 * format and Minecraft version an overlay's {@code PACK_FORMAT} / {@code MINECRAFT} predicate
 * is tested against.
 *
 * @param packFormat the renderer's target pack format major number
 * @param minecraftVersion the renderer's target Minecraft version (e.g. {@code 26.1})
 */
public record CatharsisTarget(int packFormat, @NotNull String minecraftVersion) {
}
