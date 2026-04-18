package dev.sbs.renderer.tooling.blockentity;

/**
 * The Y axis orientation used by a Java block entity model's source data.
 * <p>
 * Most Java {@code ModelPart}-derived block entities are Y-down (standard mob-style
 * convention - a vertex with the smallest Y points at the highest spot on the model).
 * A few block-native models, notably {@code ChestModel}, author their cubes in Y-up
 * block-space directly; those are marked {@link #UP} so the parser post-processes them
 * back into the canonical Y-down form before emission.
 */
public enum YAxis {
    UP,
    DOWN
}
