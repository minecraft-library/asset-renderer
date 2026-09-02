package lib.minecraft.renderer.option;

import dev.simplified.annotations.ClassBuilder;
import dev.simplified.annotations.Getter;
import dev.simplified.image.Background;
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
 *
 * @see lib.minecraft.renderer.PortalRenderer
 */
@Getter
@ClassBuilder
public class PortalOptions implements RenderOptions {

    /**
     * The portal variant - drives {@code PORTAL_LAYERS} count and per-layer colour table selection.
     */
    private final @NotNull Portal portal = Portal.END_PORTAL;

    /**
     * Render type - isometric 3D cube / slab or flat 2D top-face icon.
     */
    private final @NotNull Type type = Type.ISOMETRIC_3D;

    /**
     * Explicit ARGB multiplicative tint applied on top of the baked parallax output. {@code null}
     * leaves the raw shader output unmodified. Useful for debug variants and future pack-swap
     * hooks.
     */
    private final @Nullable Integer tintArgbOverride;

    /**
     * The default output frame for a portal render - neutral output size, {@code VANILLA_ISO}
     * projection, no supersampling and no FXAA.
     */
    public static final @NotNull OutputOptions DEFAULT_OUTPUT = OutputOptions.defaults();

    /** The shared output frame - output size, projection, facing, rotation, and SSAA / FXAA. */
    private final @NotNull OutputOptions output = DEFAULT_OUTPUT;

    /**
     * The default animation timing for a portal render - seed tick 0, 1 frame, 8 ticks per frame
     * (the game-time step the parallax shader advances per output frame).
     */
    public static final @NotNull AnimationOptions DEFAULT_ANIMATION =
            AnimationOptions.builder().ticksPerFrame(8).build();

    /** The animation timing (seed tick, frame count, ticks per frame). */
    private final @NotNull AnimationOptions animation = DEFAULT_ANIMATION;

    /**
     * Frames sampled per whole-tick step of the parallax bake, whose appearance is a continuous
     * function of time. One frame per tick caps output at 20 frames a second, which is visibly
     * coarse on continuous motion; a higher value samples between ticks, covering the same span of
     * game time at the same speed but more finely. Defaults to {@code 1} - whole ticks, the only
     * instants an offline bake sampled before. A portal knob rather than a shared one: a texture
     * flipbook has no state between its frames, so subdividing its ticks would bake duplicates.
     * <p>
     * Carried faithfully only by a container that stores delays in milliseconds. GIF stores
     * centiseconds, and the smallest delay players honour is two of them, so a 50 ms tick has room
     * for at most two sub-steps there and none at all at three - a GIF written from a subdivided
     * schedule declares a longer tick than intended. Write WebP for these, or drop back to
     * {@code 1} for a strip that has to be a GIF.
     */
    private final int subTickSteps = 1;

    /**
     * Fraction of the frame count used as a shifted-continuation crossfade for a seamless loop of
     * the parallax bake.
     */
    private final float loopFadeBridgePct = 0.2f;

    /**
     * Background fill composited behind the finished render (solid colour or checkerboard).
     * Defaults to {@link Background#TRANSPARENT}, a no-op that leaves the render's own alpha intact.
     */
    private final @NotNull Background background = Background.TRANSPARENT;

    /**
     * The default portal options - a static isometric 3D {@linkplain Portal#END_PORTAL end portal}
     * with the neutral {@link #getOutput() render} frame over a
     * {@linkplain Background#TRANSPARENT transparent} background.
     *
     * @return the default options
     */
    public static @NotNull PortalOptions defaults() {
        return builder().build();
    }

    /**
     * The portal variants supported by {@code PortalRenderer}.
     */
    public enum Portal {

        /**
         * End portal - {@code PORTAL_LAYERS = 15} in {@code RenderPipelines.END_PORTAL}.
         */
        END_PORTAL,

        /**
         * End gateway - {@code PORTAL_LAYERS = 16} in {@code RenderPipelines.END_GATEWAY}.
         */
        END_GATEWAY

    }

    /**
     * The supported render types for {@code PortalRenderer}.
     */
    public enum Type {

        /**
         * Full 3D isometric portal geometry - a unit cube for {@link Portal#END_GATEWAY}, or a thin
         * slab spanning {@code y = 6/16..12/16} for {@link Portal#END_PORTAL} whose top face sits at
         * {@code 12/16}, matching vanilla's {@code TheEndPortalRenderer.TOP} offset.
         */
        ISOMETRIC_3D,

        /**
         * Flat top-down portal-face icon - the parallax bake blitted onto a 2D quad.
         */
        PORTAL_FACE_2D

    }

}
