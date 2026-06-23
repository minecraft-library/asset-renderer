package lib.minecraft.renderer.layer;

import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.engine.IsometricEngine;
import lib.minecraft.renderer.engine.RendererContext;
import lib.minecraft.renderer.tensor.Vector3f;
import org.jetbrains.annotations.NotNull;

/**
 * Immutable per-render scene state shared by every {@link GeometryLayer} in a 3D render.
 * <p>
 * Computed once by the renderer (canvas fit, centring anchor, scale, resolved base texture, engine)
 * and read by each layer, replacing the positional parameter threading that previously fanned these
 * values into every geometry-build call.
 *
 * @param baseTexture resolved base entity/model texture the layers sample from
 * @param modelAnchor model-space point the rasterizer maps to canvas centre
 * @param ndcScale normalized-device scale from the auto-fit window
 * @param modelScale per-entity render scale (vanilla renderer-scale combined with state-scale)
 * @param engine isometric engine carrying the baked camera pose and texture resolution
 * @param context renderer context for overlay-texture and block lookups
 */
public record SceneContext(
    @NotNull PixelBuffer baseTexture,
    @NotNull Vector3f modelAnchor,
    float ndcScale,
    float modelScale,
    @NotNull IsometricEngine engine,
    @NotNull RendererContext context
) {}
