package lib.minecraft.renderer.option;

/**
 * Body-size selection for entities whose renderer picks a mesh (or scale) by size. Pufferfish binds
 * one of three distinct baked meshes (deflated {@link #SMALL}, {@link #MEDIUM}, fully-puffed
 * {@link #LARGE}); the deferred salmon / slime / magma_cube size axes reuse the same enum for their
 * per-size scale. Each entity declares its own default (the {@code size} axis's {@code default} in the
 * family form), so an unset selection renders that entity's canonical mesh - byte-identical.
 */
public enum Size {

    /** The small mesh (pufferfish deflated; salmon {@code SALMON_SMALL}; slime size 1). */
    SMALL,

    /** The medium mesh (pufferfish {@code PUFFERFISH_MEDIUM}; salmon default; slime size 2). */
    MEDIUM,

    /** The large mesh (pufferfish fully-puffed {@code PUFFERFISH_BIG}; salmon {@code SALMON_LARGE}; slime size 4). */
    LARGE

}
