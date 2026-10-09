package lib.minecraft.renderer.vanilla.mesh;

import dev.simplified.annotations.UtilityClass;
import lib.minecraft.renderer.engine.math.Vector2f;
import lib.minecraft.renderer.engine.math.Vector3f;
import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import org.jetbrains.annotations.NotNull;

/**
 * The one-cube cape mesh vanilla declares in code - a 10x16x1 box cut from the cape sheet.
 * <p>
 * The cube mirrors {@code PlayerCapeModel.createCapeLayer}, which authors it as
 * {@code texOffs(0, 0).addBox(-5, 0, -1, 10, 16, 1)} on a {@code 64x64} layer whose cube scales its V
 * by {@code 0.5}, so its faces are read from a {@code 64x32} sheet. Only the box's extent and atlas
 * origin are held here; its origin and pose are not, the box being seated on the torso it hangs behind.
 */
@UtilityClass
@Parity(claim = "engine-renders", mode = Mode.DEMOTE)
@Parity(claim = "player-geometry")
public class CapeMesh {

    /** The cape cube's atlas origin on a cape sheet, vanilla {@code texOffs(0, 0)}. */
    public static final @NotNull Vector2f CAPE_UV = Vector2f.ZERO;

    /** The cape cube's extent in texture pixels, vanilla {@code addBox} size {@code (10, 16, 1)}. */
    public static final @NotNull Vector3f CAPE_SIZE = new Vector3f(10f, 16f, 1f);

    /** The width of the sheet the cape's faces are read from, in texels. */
    public static final int SHEET_WIDTH = 64;

    /**
     * The height of the sheet the cape's faces are read from, in texels - the {@code 64} the layer
     * declares under the cube's halved V.
     */
    public static final int SHEET_HEIGHT = 32;

}
