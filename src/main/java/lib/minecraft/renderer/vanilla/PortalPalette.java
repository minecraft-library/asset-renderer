package lib.minecraft.renderer.vanilla;

import dev.simplified.annotations.UtilityClass;
import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import org.jetbrains.annotations.NotNull;

/**
 * The vanilla table behind the end-portal star-field: the two sampler ids, the per-layer colour
 * roster, the per-pipeline layer counts, and the slab the end portal occupies inside its block.
 * <p>
 * Every value is transcribed from the client at Minecraft 26.1 - the colours and layer counts from
 * {@code rendertype_end_portal.fsh} and {@code RenderPipelines}, the slab bounds from
 * {@code TheEndPortalRenderer}.
 */
@UtilityClass
@Parity(claim = "engine-renders", mode = Mode.DEMOTE)
public class PortalPalette {

    /**
     * Resource id of {@code assets/minecraft/textures/environment/end_sky.png} (Sampler0 in the vanilla shader).
     */
    public static final @NotNull String END_SKY_TEXTURE_ID = "minecraft:environment/end_sky";

    /**
     * Resource id of {@code assets/minecraft/textures/entity/end_portal/end_portal.png} (Sampler1 in the vanilla shader).
     */
    public static final @NotNull String END_PORTAL_NOISE_TEXTURE_ID = "minecraft:entity/end_portal/end_portal";

    /**
     * Vanilla's {@code GameTime} uniform period - one day cycle in ticks. Matches
     * {@code GlobalSettingsUniform.update}'s {@code (gameTick % 24000L + partialTick) / 24000f}
     * bytecode at offsets 97-116.
     */
    public static final int GAME_TIME_PERIOD_TICKS = 24000;

    /**
     * {@code PORTAL_LAYERS} for the end portal (from {@code RenderPipelines.END_PORTAL}).
     */
    public static final int LAYER_COUNT_END_PORTAL = 15;

    /**
     * {@code PORTAL_LAYERS} for the end gateway (from {@code RenderPipelines.END_GATEWAY}).
     */
    public static final int LAYER_COUNT_END_GATEWAY = 16;

    /**
     * {@code COLORS[16]} table transcribed from {@code rendertype_end_portal.fsh} at Minecraft 26.1.
     * Indexed by loop iteration {@code i} in the shader's body; entry 0 also scales the base
     * {@code Sampler0} draw.
     */
    public static final float[][] COLORS = {
        { 0.022087f, 0.098399f, 0.110818f },
        { 0.011892f, 0.095924f, 0.089485f },
        { 0.027636f, 0.101689f, 0.100326f },
        { 0.046564f, 0.109883f, 0.114838f },
        { 0.064901f, 0.117696f, 0.097189f },
        { 0.063761f, 0.086895f, 0.123646f },
        { 0.084817f, 0.111994f, 0.166380f },
        { 0.097489f, 0.154120f, 0.091064f },
        { 0.106152f, 0.131144f, 0.195191f },
        { 0.097721f, 0.110188f, 0.187229f },
        { 0.133516f, 0.138278f, 0.148582f },
        { 0.070006f, 0.243332f, 0.235792f },
        { 0.196766f, 0.142899f, 0.214696f },
        { 0.047281f, 0.315338f, 0.321970f },
        { 0.204675f, 0.390010f, 0.302066f },
        { 0.080955f, 0.314821f, 0.661491f }
    };

    // --- end_portal slab dimensions (vanilla TheEndPortalRenderer.BOTTOM / .TOP) ---

    /**
     * End portal slab bottom Y in unit-cube model space. Matches {@code TheEndPortalRenderer.BOTTOM}.
     */
    public static final float END_PORTAL_SLAB_BOTTOM_Y = 0.375f;

    /**
     * End portal slab top Y in unit-cube model space. Matches {@code TheEndPortalRenderer.TOP}.
     */
    public static final float END_PORTAL_SLAB_TOP_Y = 0.75f;

}
