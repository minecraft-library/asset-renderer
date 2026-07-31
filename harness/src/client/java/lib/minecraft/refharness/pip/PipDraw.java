package lib.minecraft.refharness.pip;

/** The subject-specific half of a picture-in-picture draw - build the pose, choose the lighting, submit. */
@FunctionalInterface
public interface PipDraw {

    /**
     * Poses and submits one subject's geometry.
     *
     * <p>Returning {@code false} abandons the frame: the batched render types are left unflushed and
     * no PNG is written, which is what a subject whose submit ladder fails part-way needs - a
     * half-submitted subject must not be read back as though it were complete.
     *
     * @param scope the per-frame handles and canvas dimensions to draw with
     * @return whether the frame should be completed and written
     */
    boolean submit(PipScope scope);
}
