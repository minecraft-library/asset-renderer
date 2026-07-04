package lib.minecraft.renderer.tooling.blockentity;

/**
 * The Y axis orientation used by a Java block-entity model's source bytecode.
 *
 * <p>Most Java {@code ModelPart}-derived block entities are Y-down (the standard mob-style
 * convention - the smallest-Y vertex sits at the highest visible spot on the model). A few
 * block-native models, notably {@code ChestModel} (pivot {@code (0, 9, 1)}) and
 * {@code BellModel} (pivot {@code (8, 12, 8)}), author their cubes in Y-up block-space
 * directly; {@code SourceDiscovery.inferYAxisFromMethod} detects them by a
 * {@code PartPose.offset} pivot {@code y} in the {@code [8, 16)} half-block band and marks
 * them {@link #UP}. The marker travels with the emitted bones in their native frame -
 * nothing is pre-flipped at tooling time - and the render-time presentation applies the
 * source Y-up to block-space orientation.
 */
public enum YAxis {

    /**
     * Y-up source - cubes authored in block-space with positive Y pointing up; the marker
     * travels with the bones and the render presentation applies the source-to-block Y
     * orientation (no tooling-time pre-flip)
     */
    UP,

    /**
     * Y-down source - the standard Java {@code ModelPart} convention, requiring no
     * pre-processing
     */
    DOWN

}
