package lib.minecraft.renderer.vanilla.mesh;

import dev.simplified.annotations.UtilityClass;
import lib.minecraft.renderer.engine.camera.Projection;
import lib.minecraft.renderer.engine.geometry.EulerRotation;
import lib.minecraft.renderer.engine.light.Lighting;
import lib.minecraft.renderer.engine.light.LightingFrame;
import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import org.jetbrains.annotations.NotNull;

/**
 * The lighting poses vanilla names for an entity preview.
 */
@UtilityClass
@Parity(claim = "engine-renders", mode = Mode.DEMOTE)
public class EntityLighting {

    /**
     * The vanilla entity-preview iso lighting frame - the harness's {@code ISO_ROTATION} {@code [210,
     * 45, 0]} as a {@link LightingFrame}, the default every entity render lights through. Distinct from
     * {@link Projection#VANILLA_ISO}'s camera pose ({@code [30, 225, 0]} + the renderer's facing
     * {@code Placement}): the light frame stays on the harness iso angle while the camera is a plain
     * display pose. {@link Lighting#resolveEntity} turns it into the per-face shading basis (view
     * direction + the two diffuse lights) for the one pass that lights a folded entity stack.
     */
    public static final @NotNull LightingFrame DEFAULT_ENTITY_LIGHTING =
        LightingFrame.fixed(new EulerRotation(210f, 45f, 0f));

}
