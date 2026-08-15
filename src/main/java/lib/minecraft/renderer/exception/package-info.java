/**
 * The one exception hierarchy this library throws, rooted so a caller can catch a single type.
 *
 * <p>{@link lib.minecraft.renderer.exception.RendererException RendererException} is the root and
 * extends {@link java.lang.RuntimeException}; the two below it name which side of the work failed -
 * {@link lib.minecraft.renderer.exception.PipelineException PipelineException} for a read that could
 * not be completed, {@link lib.minecraft.renderer.exception.RenderException RenderException} for a
 * draw that could not. Both are final, because what distinguishes a failure here is the message and
 * the cause rather than a further type.
 *
 * <p>What deliberately does not extend the root is client-jar acquisition, which raises its own
 * unchecked type: a batch renderer catches the root to skip one bad subject and carry on, and a
 * client that failed to acquire must abort that batch rather than be skipped 4000 times.
 *
 * <p><b>Parity.</b> These are message and constructor shapes on throwables. Nothing renders
 * differently because a detail message changed and no stored value records one, so the gate for an
 * edit here is the suite compiling and passing rather than anything this store holds. A rewiring of
 * the hierarchy that changed which catch block runs would surface as a sweep failing outright rather
 * than as a moved row.
 *
 * @see lib.minecraft.renderer.exception.RendererException
 */
@Parity(claim = "exception-types")
package lib.minecraft.renderer.exception;

import lib.minecraft.renderer.parity.Parity;
