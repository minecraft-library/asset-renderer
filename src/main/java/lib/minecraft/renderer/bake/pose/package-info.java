/**
 * Playing a pose or a clip onto a decoded mesh - the step that turns a subject's authored bones into
 * the bones it holds at one instant.
 *
 * <p>{@link lib.minecraft.renderer.bake.pose.PosePlayer PosePlayer} writes the channel values
 * {@link lib.minecraft.renderer.engine.pose.PoseEvaluator PoseEvaluator} samples back onto the
 * bones they name, posing the body and every overlay pass together; a written channel sets the value
 * it names outright. Its
 * {@link lib.minecraft.renderer.bake.pose.PosePlayer.PosedFrames PosedFrames} memo poses each
 * subject once per tick for a whole render, and under the {@code bind} row hands back the very mesh
 * it was given. {@link lib.minecraft.renderer.bake.pose.ClipPlayer ClipPlayer} evaluates the clips a
 * model plays into what they displace each bone by at one instant, a displacement added onto whatever
 * the pose left rather than a place to put the bone.
 *
 * <p>The package also decides the strip a resolved row is drawn over.
 * {@link lib.minecraft.renderer.bake.pose.PoseStrips PoseStrips} holds each fitted strip for the
 * process, and its sampler reads the channels a fit is taken over - every one the row's posed frames
 * draw, through the same evaluation that poses them.
 *
 * <p>A type that neither yields a posed mesh nor decides the strip one is drawn over does not belong
 * here.
 *
 * <p><b>Parity.</b> Every member declares its own claims; the package declares none.
 */
package lib.minecraft.renderer.bake.pose;
