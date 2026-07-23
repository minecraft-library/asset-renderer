package lib.minecraft.refharness.pip;

/** The subject-specific half of a picture-in-picture draw - build the pose, choose the lighting, submit. */
@FunctionalInterface
public interface PipDraw {

    /**
     * Poses and submits one subject's geometry.
     *
     * @param scope the per-frame handles and canvas dimensions to draw with
     */
    void submit(PipScope scope);
}
