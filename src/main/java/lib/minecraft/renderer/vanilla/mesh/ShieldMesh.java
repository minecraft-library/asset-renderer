package lib.minecraft.renderer.vanilla.mesh;

import dev.simplified.annotations.UtilityClass;
import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import org.jetbrains.annotations.NotNull;

/**
 * The two-cube shield mesh vanilla declares in code - a flat plate and a handle sharing one 64x64
 * atlas.
 * <p>
 * The cubes mirror {@code ShieldModel.createLayer} one for one and are authored in vanilla's entity
 * Y-down frame, in model pixels, before the model's own {@code scale(1, -1, -1)} transformation.
 */
@UtilityClass
@Parity(claim = "engine-renders", mode = Mode.DEMOTE)
public class ShieldMesh {

    /**
     * The shield model's texture atlas dimension in pixels (vanilla {@code ShieldModel} declares a
     * 64x64 {@code LayerDefinition}). Drives UV normalisation independent of the resolved texture's
     * actual resolution.
     */
    public static final float TEXTURE_SIZE = 64f;

    /**
     * The flat plate, vanilla {@code texOffs(0, 0).addBox(-6, -11, -2, 12, 22, 1)}.
     */
    public static final @NotNull Cube PLATE = new Cube(-6f, -11f, -2f, 12f, 22f, 1f, 0f, 0f);

    /**
     * The handle, vanilla {@code texOffs(26, 0).addBox(-1, -3, -1, 2, 6, 6)}.
     */
    public static final @NotNull Cube HANDLE = new Cube(-1f, -3f, -1f, 2f, 6f, 6f, 26f, 0f);

    /**
     * One cube of the vanilla shield model, as {@code addBox} plus {@code texOffs} author it.
     *
     * @param originX the cube origin X in vanilla's entity frame, in model pixels
     * @param originY the cube origin Y in vanilla's entity frame, in model pixels
     * @param originZ the cube origin Z in vanilla's entity frame, in model pixels
     * @param sizeX the cube extent along X, in model pixels
     * @param sizeY the cube extent along Y, in model pixels
     * @param sizeZ the cube extent along Z, in model pixels
     * @param texU the cube's atlas U origin
     * @param texV the cube's atlas V origin
     */
    public record Cube(
        float originX, float originY, float originZ,
        float sizeX, float sizeY, float sizeZ,
        float texU, float texV
    ) { }

}
