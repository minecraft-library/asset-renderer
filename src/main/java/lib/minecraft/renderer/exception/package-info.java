/**
 * The exception hierarchy this library throws, rooted so a caller can catch a single type.
 *
 * <p>{@link lib.minecraft.renderer.exception.RendererException RendererException} is the root, abstract
 * and extending {@link java.lang.RuntimeException}; the three below it name which side of the work
 * failed - {@link lib.minecraft.renderer.exception.ContentException ContentException} for a read that
 * could not be completed, {@link lib.minecraft.renderer.exception.RenderException RenderException} for
 * a draw that could not, and {@link lib.minecraft.renderer.exception.StyleException StyleException}
 * for an authored pose style the compiler or registrar refuses. What distinguishes a failure within
 * one of them is the message and the cause rather than a further type, so {@code RenderException} and
 * {@code StyleException} are final and {@code ContentException} is sealed, admitting a subtype only
 * where a catch answers it differently from every other failed read:
 * {@link lib.minecraft.renderer.exception.ColorMapException ColorMapException}, a biome colormap a pack
 * stack cannot supply, which a context load answers by loading the vanilla pack alone, and
 * {@link lib.minecraft.renderer.exception.RuleRejection RuleRejection}, a pack rule condition that does
 * not parse, which the rule parser that raised it answers by dropping that one rule - so it never
 * leaves the parse.
 *
 * <p>What deliberately does not extend the root is client-jar acquisition:
 * {@link lib.minecraft.renderer.exception.ClientException ClientException} for a jar that cannot be
 * found in the manifest, cached or extracted, and the Mojang API's own
 * {@link api.simplified.mojang.exception.MojangApiException MojangApiException} for a request that API
 * fails. A batch renderer catches the root to skip one bad subject and carry on, and a client that
 * failed to acquire must abort that batch rather than be skipped 4000 times.
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
