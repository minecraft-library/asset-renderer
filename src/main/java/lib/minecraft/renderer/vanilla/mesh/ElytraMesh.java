package lib.minecraft.renderer.vanilla.mesh;

import dev.simplified.annotations.UtilityClass;
import lib.minecraft.renderer.engine.geometry.EulerRotation;
import lib.minecraft.renderer.engine.math.Vector2f;
import lib.minecraft.renderer.engine.math.Vector3f;
import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import org.jetbrains.annotations.NotNull;

/**
 * The two-wing elytra mesh vanilla declares in code - a left and a right wing box sharing one 64x32
 * atlas, and the scale a baby wearer draws them at.
 * <p>
 * The wings mirror {@code ElytraModel.createLayer} one for one - each a {@code 10x20x2} box at
 * {@code texOffs(22, 0)} inflated {@code 1.0}, offset {@code +-5} and rotated {@code +-15deg} - and are
 * authored in vanilla's model frame, shoulders at {@code y 0}.
 */
@UtilityClass
@Parity(claim = "engine-renders", mode = Mode.DEMOTE)
public class ElytraMesh {

    /** The elytra model's texture atlas width in pixels, vanilla {@code LayerDefinition} width {@code 64}. */
    public static final int TEXTURE_WIDTH = 64;

    /** The elytra model's texture atlas height in pixels, vanilla {@code LayerDefinition} height {@code 32}. */
    public static final int TEXTURE_HEIGHT = 32;

    /** The extent of each wing box in model pixels, vanilla {@code addBox} size {@code (10, 20, 2)}. */
    public static final @NotNull Vector3f BOX_SIZE = new Vector3f(10f, 20f, 2f);

    /** The atlas origin both wing boxes are cut from, vanilla {@code texOffs(22, 0)}. */
    public static final @NotNull Vector2f TEX_OFFSET = new Vector2f(22f, 0f);

    /** The per-axis {@code CubeDeformation(1.0)} the vanilla wings inflate their box by. */
    public static final @NotNull Vector3f INFLATE = new Vector3f(1f, 1f, 1f);

    /**
     * The whole-model back shift of both wings in model pixels - vanilla {@code WingsLayer.submit}
     * applies {@code PoseStack.translate(0, 0, 0.125)} in the entity's block frame before rendering the
     * elytra, which is {@code 0.125 * 16 = 2} pixels in this mesh's native pixel frame. It is each
     * wing's pivot z, which seats the wings behind the body rather than clipping them into the back.
     * The translate sits outside the model's root, so a baby's half-scale transform leaves it whole.
     */
    public static final float BACK_OFFSET = 2f;

    /**
     * The half-body scale vanilla {@code ElytraModel.BABY_TRANSFORMER} applies for a baby wearer,
     * {@code MeshTransformer.scaling(0.5)}.
     */
    public static final float BABY_SCALE = 0.5f;

    /** The left wing, box origin {@code (-10, 0, 0)} at pivot x {@code 5} rotated {@code (15, 0, -15)}. */
    public static final @NotNull Wing LEFT = new Wing(
        "left_wing", 5f, new EulerRotation(15f, 0f, -15f), new Vector3f(-10f, 0f, 0f), false);

    /** The right wing, mirrored: box origin {@code (0, 0, 0)} at pivot x {@code -5} rotated {@code (15, 0, 15)}. */
    public static final @NotNull Wing RIGHT = new Wing(
        "right_wing", -5f, new EulerRotation(15f, 0f, 15f), new Vector3f(0f, 0f, 0f), true);

    /**
     * One wing of the vanilla elytra model, as {@code addOrReplaceChild} names it, {@code PartPose}
     * poses it and {@code addBox} authors its box.
     *
     * @param bone the model part name the wing is declared under
     * @param pivotX the part's pivot offset along X in model pixels; its Y is the shoulder line and its
     *     Z the {@link #BACK_OFFSET}
     * @param rotation the part's rotation
     * @param origin the box's minimum corner in the part's frame, in model pixels
     * @param mirror whether the box's texture is mirrored left-to-right
     */
    public record Wing(
        @NotNull String bone, float pivotX, @NotNull EulerRotation rotation, @NotNull Vector3f origin, boolean mirror
    ) { }

}
