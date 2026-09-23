package lib.minecraft.renderer.port;

import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.asset.ColorMap;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.Item;
import lib.minecraft.renderer.asset.pack.Flipbook;
import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.vanilla.TintSource;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * The in-memory context {@link RendererContext#builder()} builds - every lookup answered out of what
 * the builder was handed, and empty for anything it was not.
 *
 * @param textures the texture source every resolve consults
 * @param blocks the block definitions keyed by namespaced id
 * @param items the item definitions keyed by namespaced id
 * @param entities the entity definitions keyed by namespaced id
 * @param colorMaps the colormaps keyed by the tint target each serves
 * @param colorOverrides the pack colour overrides keyed by their {@code color.properties} key
 */
@Parity(ignored = true)
@Parity(claim = "engine-renders", mode = Mode.DEMOTE)
record MapRendererContext(
    @NotNull Function<String, Optional<PixelBuffer>> textures,
    @NotNull Map<String, Block> blocks,
    @NotNull Map<String, Item> items,
    @NotNull Map<String, Entity> entities,
    @NotNull Map<TintSource, ColorMap> colorMaps,
    @NotNull Map<String, Integer> colorOverrides
) implements RendererContext {

    /** {@inheritDoc} */
    @Override
    public @NotNull Optional<Block> findBlock(@NotNull String id) {
        return Optional.ofNullable(this.blocks.get(id));
    }

    /** {@inheritDoc} */
    @Override
    public @NotNull Optional<ColorMap> findColorMap(@NotNull TintSource target) {
        return Optional.ofNullable(this.colorMaps.get(target));
    }

    /** {@inheritDoc} */
    @Override
    public @NotNull Optional<Integer> findColorOverride(@NotNull String key) {
        return Optional.ofNullable(this.colorOverrides.get(key));
    }

    /**
     * {@inheritDoc}
     * <p>
     * Empty for every id: this context holds no sidecars, so no texture it serves plays back an
     * animation.
     */
    @Override
    public @NotNull Optional<Flipbook> findFlipbook(@NotNull String textureId) {
        return Optional.empty();
    }

    /** {@inheritDoc} */
    @Override
    public @NotNull Optional<Entity> findEntity(@NotNull String id) {
        return Optional.ofNullable(this.entities.get(id));
    }

    /** {@inheritDoc} */
    @Override
    public @NotNull Optional<Item> findItem(@NotNull String id) {
        return Optional.ofNullable(this.items.get(id));
    }

    /** {@inheritDoc} */
    @Override
    public @NotNull Optional<PixelBuffer> resolveTexture(@NotNull String textureId) {
        return this.textures.apply(textureId);
    }

}
