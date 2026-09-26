package lib.minecraft.renderer;

import lib.minecraft.renderer.asset.model.ModelTransform;
import lib.minecraft.renderer.bake.mesh.ShieldKit;
import lib.minecraft.renderer.engine.geometry.Box;
import lib.minecraft.renderer.engine.geometry.EulerRotation;
import lib.minecraft.renderer.engine.geometry.ModelUnits;
import lib.minecraft.renderer.math.Matrix4f;
import lib.minecraft.renderer.math.Quaternionf;
import lib.minecraft.renderer.math.Vector3f;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit coverage for {@link ItemRenderer.Held3D#displayMatrix} - a held item's display transform
 * composed the way vanilla's item transform applies it, so a vertex is scaled, then rotated, then
 * translated.
 * <p>
 * Each case carries a vanilla model's {@code thirdperson_righthand} values. The renderer centres its
 * held geometry on the model origin, so the origin is where the item's centre lands, and it lands on
 * the translation alone - neither scaled nor rotated. The spear's scale is the one non-uniform
 * {@code thirdperson_righthand} vanilla ships, which is what lets a slab corner tell rotating before
 * scaling from scaling before rotating.
 */
@DisplayName("Held item display transform")
class HeldDisplayTransformTest {

    /** The largest difference, in blocks, the fused matrix may show against the stepwise product. */
    private static final float STEPWISE_TOLERANCE = 1e-6f;

    /** {@code item/generated}, the flat sprite's slot. */
    private static final @NotNull ModelTransform GENERATED = transform(0f, 0f, 0f, 0f, 3f, 1f, 0.55f, 0.55f, 0.55f);

    /** {@code item/handheld}, the tool and weapon slot. */
    private static final @NotNull ModelTransform HANDHELD = transform(0f, -90f, 55f, 0f, 4f, 0.5f, 0.85f, 0.85f, 0.85f);

    /** {@code block/block}, the slot every full block item inherits. */
    private static final @NotNull ModelTransform BLOCK = transform(75f, 45f, 0f, 0f, 2.5f, 0f, 0.375f, 0.375f, 0.375f);

    /** {@code item/spear_in_hand}, the one non-uniform scale. */
    private static final @NotNull ModelTransform SPEAR_IN_HAND = transform(5f, 270f, -40f, 0f, 2f, 2f, 1.7f, 1.7f, 0.85f);

    @Test
    @DisplayName("item/generated puts the model origin on its translation")
    void generatedOriginLandsOnTranslation() {
        assertOriginOnTranslation(GENERATED);
    }

    @Test
    @DisplayName("item/handheld puts the model origin on its translation")
    void handheldOriginLandsOnTranslation() {
        assertOriginOnTranslation(HANDHELD);
    }

    @Test
    @DisplayName("block/block puts the model origin on its translation")
    void blockOriginLandsOnTranslation() {
        assertOriginOnTranslation(BLOCK);
    }

    @Test
    @DisplayName("item/spear_in_hand puts the model origin on its translation")
    void spearOriginLandsOnTranslation() {
        assertOriginOnTranslation(SPEAR_IN_HAND);
    }

    @Test
    @DisplayName("a spear slab corner is scaled, then rotated, then translated")
    void spearSlabCornerIsScaledThenRotatedThenTranslated() {
        Box slab = ShieldKit.FLAT_ITEM_SLAB;
        Vector3f corner = new Vector3f(slab.maxX(), slab.maxY(), slab.maxZ());
        EulerRotation angles = SPEAR_IN_HAND.getRotation();

        Vector3f scaled = new Vector3f(
            corner.x() * SPEAR_IN_HAND.getScaleX(),
            corner.y() * SPEAR_IN_HAND.getScaleY(),
            corner.z() * SPEAR_IN_HAND.getScaleZ());
        Vector3f rotated = scaled.transform(Matrix4f.IDENTITY.rotate(
            Quaternionf.rotationXYZ(angles.pitchRadians(), angles.yawRadians(), angles.rollRadians())));
        Vector3f expected = rotated.add(translationOf(SPEAR_IN_HAND));

        Vector3f actual = corner.transform(ItemRenderer.Held3D.displayMatrix(SPEAR_IN_HAND));

        assertEquals(expected.x(), actual.x(), STEPWISE_TOLERANCE, "corner x");
        assertEquals(expected.y(), actual.y(), STEPWISE_TOLERANCE, "corner y");
        assertEquals(expected.z(), actual.z(), STEPWISE_TOLERANCE, "corner z");
    }

    /**
     * Asserts that the model origin lands exactly on the transform's translation, in blocks.
     *
     * @param transform the display transform under test
     */
    private static void assertOriginOnTranslation(@NotNull ModelTransform transform) {
        Vector3f expected = translationOf(transform);
        Vector3f origin = Vector3f.ZERO.transform(ItemRenderer.Held3D.displayMatrix(transform));

        assertEquals(expected.x(), origin.x(), 0f, "origin x");
        assertEquals(expected.y(), origin.y(), 0f, "origin y");
        assertEquals(expected.z(), origin.z(), 0f, "origin z");
    }

    /**
     * Converts a transform's authored translation from sixteenths of a block to blocks.
     *
     * @param transform the display transform to read
     * @return the translation in blocks
     */
    private static @NotNull Vector3f translationOf(@NotNull ModelTransform transform) {
        return new Vector3f(
            transform.getTranslationX() / ModelUnits.PIXELS_PER_BLOCK,
            transform.getTranslationY() / ModelUnits.PIXELS_PER_BLOCK,
            transform.getTranslationZ() / ModelUnits.PIXELS_PER_BLOCK);
    }

    /**
     * Builds a display transform from its authored values, rotation in degrees and translation in
     * sixteenths of a block.
     *
     * @param pitch the rotation about X
     * @param yaw the rotation about Y
     * @param roll the rotation about Z
     * @param tx the X translation
     * @param ty the Y translation
     * @param tz the Z translation
     * @param sx the X scale
     * @param sy the Y scale
     * @param sz the Z scale
     * @return the display transform
     */
    private static @NotNull ModelTransform transform(
        float pitch, float yaw, float roll, float tx, float ty, float tz, float sx, float sy, float sz) {
        return new ModelTransform(new EulerRotation(pitch, yaw, roll), new float[]{ tx, ty, tz }, new float[]{ sx, sy, sz });
    }

}
