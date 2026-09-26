package lib.minecraft.renderer.engine.geometry;

import dev.simplified.annotations.UtilityClass;
import lib.minecraft.renderer.parity.Parity;

/**
 * The authoring grid a vanilla model JSON states its coordinates in, and the divisor that carries
 * them into the engine's own space.
 */
@UtilityClass
@Parity(claim = "box-builder")
public class ModelUnits {

    /**
     * Edge length of a full block in vanilla model-authoring units. Every vanilla {@code block/}
     * and {@code item/} model JSON authors coordinates against this grid - element
     * {@code from} / {@code to} values of {@code [0, 0, 0]} and {@code [16, 16, 16]} describe a
     * full unit cube, face UVs run from {@code 0} to {@code 16}, and {@code display.*.translation}
     * values are in the same space. {@link Unwrap.Element#rect} and every renderer that reads a
     * model element or a display transform divide by this constant to normalise into the engine's
     * {@code [-0.5, +0.5]} unit-cube space before projection.
     */
    public static final float PIXELS_PER_BLOCK = 16f;

}
