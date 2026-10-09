/**
 * What crosses a render call: what a caller supplies, in
 * {@link lib.minecraft.renderer.call.request request}; what the render hands back, in
 * {@link lib.minecraft.renderer.call.result result}; and the layer names a caller's
 * {@code layerDecorator} splices against, in {@link lib.minecraft.renderer.call.slot slot}.
 *
 * <p><b>Parity.</b> Every renderer entry point takes an options record, so a default or a resolution
 * rule here reaches whatever that renderer draws - the same population the engine reaches, for the
 * same reason. A slot is named by the bag whose decorator splices into it, so it reaches what that
 * bag reaches. A result type is named by the renderers that report through it, so it reaches what
 * they reach. The dump is blind to all of it: it serialises loaded content and never constructs an
 * options record. The vocabulary a bag names under {@code vanilla} declares this same claim beside its
 * own, so what a bag names keeps the reach the bag has.
 */
@Parity(claim = "option-surface")
package lib.minecraft.renderer.call;

import lib.minecraft.renderer.parity.Parity;
