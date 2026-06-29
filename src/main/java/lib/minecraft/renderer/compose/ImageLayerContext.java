package lib.minecraft.renderer.compose;

import lib.minecraft.renderer.asset.Item;
import lib.minecraft.renderer.engine.RasterEngine;
import lib.minecraft.renderer.engine.RendererContext;
import lib.minecraft.renderer.options.ItemOptions;
import org.jetbrains.annotations.NotNull;

/**
 * Per-render state passed to every {@link ImageLayer} in a 2D item composite stack.
 *
 * @param context renderer context for texture and override resolution
 * @param engine raster engine for 2D blit/draw operations
 * @param item resolved item definition being rendered
 * @param options caller-supplied item render options
 */
public record ImageLayerContext(
    @NotNull RendererContext context,
    @NotNull RasterEngine engine,
    @NotNull Item item,
    @NotNull ItemOptions options
) {}
