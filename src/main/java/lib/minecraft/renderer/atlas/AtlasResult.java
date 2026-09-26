package lib.minecraft.renderer.atlas;

import dev.simplified.image.ImageData;
import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;
import org.jetbrains.annotations.NotNull;

/**
 * The full output of an atlas render: the composed grid image and the sidecar placing every
 * tile in it.
 *
 * @param image the composed atlas grid image
 * @param sidecar the grid layout, one row per tile in the order the tiles were laid down
 */
@Parity(subject = Subject.ATLAS)
@Parity(claim = "atlas-unhashable", mode = Mode.SUPPRESS)
@Parity(claim = "engine-renders", mode = Mode.DEMOTE)
public record AtlasResult(@NotNull ImageData image, @NotNull AtlasSidecar sidecar) {}
