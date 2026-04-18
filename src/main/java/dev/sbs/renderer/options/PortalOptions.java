package dev.sbs.renderer.options;

import dev.sbs.renderer.geometry.EulerRotation;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.ImageFormat;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Configures a single {@code PortalRenderer} invocation.
 * <p>
 * Unlike {@link BlockOptions} there is no model lookup - portals are rendered via a CPU-baked
 * parallax loop over two synthetic textures ({@code environment/end_sky} +
 * {@code entity/end_portal/end_portal}) and a small set of layer parameters transcribed from
 * vanilla's {@code rendertype_end_portal.fsh}. Scene-aware concerns (camera facing, fog, translucent
 * blending) are deliberately out of scope; the renderer stays scene-agnostic so the atlas can bake
 * a deterministic tile.
 */
@Getter
@Builder(toBuilder = true, access = AccessLevel.PUBLIC)
public class PortalOptions {

    /** The portal variant - drives {@code PORTAL_LAYERS} count and per-layer colour table selection. */
    @lombok.Builder.Default
    private final @NotNull Portal portal = Portal.END_PORTAL;

    /** Render type - isometric 3D cube / slab or flat 2D top-face icon. */
    @lombok.Builder.Default
    private final @NotNull Type type = Type.ISOMETRIC_3D;

    /**
     * Explicit ARGB multiplicative tint applied on top of the baked parallax output. {@code null}
     * leaves the raw shader output unmodified. Useful for debug variants and future pack-swap
     * hooks.
     */
    private final @Nullable Integer tintArgbOverride;

    /** Model rotation applied before the camera transform, in degrees. No-op on the 2D face path. */
    @lombok.Builder.Default
    private final @NotNull EulerRotation rotation = EulerRotation.NONE;

    /** Output image dimensions in pixels (square). */
    @lombok.Builder.Default
    private final int outputSize = dev.sbs.renderer.Renderer.DEFAULT_OUTPUT_SIZE;

    /** Whether to apply FXAA post-processing. No-op on the 2D face path. */
    @lombok.Builder.Default
    private final boolean antiAlias = true;

    /**
     * Supersample scale factor for isometric 3D rendering. The cube / slab is rasterized at
     * {@code outputSize * supersample} resolution, then downsampled for sharper output. A value
     * of 1 disables supersampling. No-op on the 2D face path.
     */
    @lombok.Builder.Default
    private final int supersample = 2;

    /** Additional texture pack ids to layer on top of vanilla. */
    @lombok.Builder.Default
    private final @NotNull ConcurrentList<String> texturePackIds = Concurrent.newList();

    /** Output image format. */
    @lombok.Builder.Default
    private final @NotNull ImageFormat outputFormat = ImageFormat.PNG;

    /** Animation seed tick. Frame 0 feeds {@code GameTime} into the parallax layer transform at this tick. */
    @lombok.Builder.Default
    private final int startTick = 0;

    /**
     * Number of output frames. {@code 1} produces a static image; {@code >1} produces an
     * animated image with {@link #ticksPerFrame} ticks between successive frames.
     */
    @lombok.Builder.Default
    private final int frameCount = 1;

    /**
     * Vanilla ticks advanced between successive output frames. Drives the {@code GameTime} step
     * fed into the parallax shader; the visual speed of the animation scales linearly with this
     * value.
     * <p>
     * {@code 1} is vanilla-accurate (one tick's worth of {@code GameTime} per output frame) but
     * produces almost imperceptible motion since vanilla's in-game animation is inherently slow
     * (layer-15 UV drift is {@code ~7.5e-4} per tick). The default {@code 8} gives a calm
     * playback rate that reads as visible parallax without feeling rushed.
     */
    @lombok.Builder.Default
    private final int ticksPerFrame = 8;

    /**
     * Fraction of {@link #frameCount} used as a shifted-continuation crossfade at the start of
     * each animated output, producing a seamless loop without a visible static anchor.
     * <p>
     * With {@code K = round(loopFadeBridgePct * frameCount)}, the renderer bakes
     * {@code frameCount + K} raw shader frames. For output frame {@code i in [0, K)},
     * {@code output[i] = alpha * raw[i] + (1 - alpha) * raw[i + frameCount]} where
     * {@code alpha = i / K}. Both layers being blended are animated (the primary is the new
     * loop's opening content; the partner is what the shader WOULD produce if the previous loop
     * had continued past its natural end), so nothing looks static during the transition.
     * <p>
     * The loop seam is hidden because {@code output[frameCount - 1] = raw[frameCount - 1]} and
     * the next iteration's {@code output[0] = raw[frameCount]} - those are one shader-tick
     * apart, matching the smoothness of any within-loop adjacent pair. No separate fade-out
     * region is needed. Set to {@code 0} to disable and have raw frames play from tick
     * {@code startTick}.
     */
    @lombok.Builder.Default
    private final float loopFadeBridgePct = 0.2f;

    public @NotNull PortalOptionsBuilder mutate() {
        return this.toBuilder();
    }

    public static @NotNull PortalOptions defaults() {
        return builder().build();
    }

    /** The portal variants supported by {@code PortalRenderer}. */
    public enum Portal {

        /** End portal - {@code PORTAL_LAYERS = 15} in {@code RenderPipelines.END_PORTAL}. */
        END_PORTAL,

        /** End gateway - {@code PORTAL_LAYERS = 16} in {@code RenderPipelines.END_GATEWAY}. */
        END_GATEWAY

    }

    /** The supported render types for {@code PortalRenderer}. */
    public enum Type {

        /**
         * Full 3D isometric portal geometry - unit cube for {@link Portal#END_GATEWAY}, thin
         * top-face slab at {@code y = 12/16} for {@link Portal#END_PORTAL} matching vanilla's
         * {@code TheEndPortalRenderer.TOP} offset.
         */
        ISOMETRIC_3D,

        /** Flat top-down portal-face icon - the parallax bake blitted onto a 2D quad. */
        PORTAL_FACE_2D

    }

}
