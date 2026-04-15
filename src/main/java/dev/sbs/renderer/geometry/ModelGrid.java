package dev.sbs.renderer.geometry;

import lombok.experimental.UtilityClass;

/**
 * Constants for the vanilla Minecraft model-space grid.
 * <p>
 * Every vanilla {@code block/} and {@code item/} model JSON authors coordinates in a
 * {@code 0..16} range where one full block edge is 16 units wide. The renderer normalizes
 * geometry into {@code [-0.5, +0.5]} unit-cube space for projection, so any value pulled from
 * a model (element {@code from}/{@code to}, display transform translations, face UV
 * rectangles, texture atlas offsets) divides by {@link #VANILLA_PIXEL_UNITS_PER_BLOCK} first.
 * The reciprocal {@link #MODEL_UNIT_NORMALIZED} is pre-computed for call sites that prefer a
 * multiply over a divide in hot inner loops.
 */
@UtilityClass
public class ModelGrid {

    /**
     * Edge length of a full block in vanilla model authoring units. Every block and item model
     * JSON is authored against this grid - element {@code from}/{@code to} values of
     * {@code [0, 0, 0]} and {@code [16, 16, 16]} describe a full unit cube, face UVs run from
     * {@code 0} to {@code 16}, and {@code display.*.translation} values are in the same space.
     */
    public static final float VANILLA_PIXEL_UNITS_PER_BLOCK = 16f;

    /**
     * Reciprocal of {@link #VANILLA_PIXEL_UNITS_PER_BLOCK}. Multiplying a vanilla-space value by
     * this constant normalizes it into the engine's {@code [0, 1]} or {@code [-0.5, +0.5]}
     * model space without a runtime division.
     */
    public static final float MODEL_UNIT_NORMALIZED = 1f / VANILLA_PIXEL_UNITS_PER_BLOCK;

}
