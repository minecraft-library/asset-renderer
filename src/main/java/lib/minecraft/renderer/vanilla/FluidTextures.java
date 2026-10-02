package lib.minecraft.renderer.vanilla;

import dev.simplified.annotations.UtilityClass;
import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import org.jetbrains.annotations.NotNull;

/**
 * The namespaced texture ids vanilla ships for the two fluids.
 * <p>
 * Each fluid carries a still frame, sampled for the source face and the flat top, and a flow frame,
 * sampled for the sides and any sloped top.
 */
@UtilityClass
@Parity(claim = "engine-renders", mode = Mode.DEMOTE)
public class FluidTextures {

    /** Namespaced still-frame texture id for water (source-face / top texture). */
    public static final @NotNull String WATER_STILL_TEXTURE_ID = "minecraft:block/water_still";
    /** Namespaced flow-frame texture id for water (side / sloped-top texture). */
    public static final @NotNull String WATER_FLOW_TEXTURE_ID = "minecraft:block/water_flow";
    /** Namespaced still-frame texture id for lava (source-face / top texture). */
    public static final @NotNull String LAVA_STILL_TEXTURE_ID = "minecraft:block/lava_still";
    /** Namespaced flow-frame texture id for lava (side / sloped-top texture). */
    public static final @NotNull String LAVA_FLOW_TEXTURE_ID = "minecraft:block/lava_flow";

}
