package lib.minecraft.renderer.asset.appearance;

import lib.minecraft.renderer.option.AppearanceOptions;
import org.jetbrains.annotations.NotNull;

/**
 * Body-size selection for entities whose renderer picks a mesh (or scale) by size. Pufferfish binds
 * one of three distinct baked meshes (deflated {@link #SMALL}, {@link #MEDIUM}, fully-puffed
 * {@link #LARGE}); the deferred salmon / slime / magma_cube size axes reuse the same enum for their
 * per-size scale. Each entity declares its own default (the {@code size} axis's {@code default} in the
 * family form), so an unset selection renders that entity's canonical mesh - which is why no option
 * here answers {@link #selectedIn} for an unset axis: the resting mesh is a per-entity fact the
 * option cannot see.
 */
public enum Size implements Axis {

    /** The small mesh (pufferfish deflated; salmon {@code SALMON_SMALL}; slime size 1). */
    SMALL,

    /** The medium mesh (pufferfish {@code PUFFERFISH_MEDIUM}; salmon default; slime size 2). */
    MEDIUM,

    /** The large mesh (pufferfish fully-puffed {@code PUFFERFISH_BIG}; salmon {@code SALMON_LARGE}; slime size 4). */
    LARGE;

    /** {@inheritDoc} */
    @Override
    public boolean selectedIn(@NotNull AppearanceOptions appearance) {
        return appearance.getSize().filter(this::equals).isPresent();
    }

}
