/**
 * Layer-composition slot vocabularies - one {@code LayerSlot} enum per renderer, naming the fixed
 * paint / emission order of its layer stack and the splice points a caller's {@code layerDecorator}
 * targets. The slots sit together in one package rather than nested in each renderer's options
 * class, so the slot taxonomy reads as one vocabulary.
 *
 * <p><b>Parity.</b> A slot is named by the options bag whose decorator it splices into, and the stack
 * it orders is the one that renderer paints, so a change here reaches whatever that renderer draws.
 * The package sits under {@code call} and takes the option surface's claim declared there - the one
 * the bags naming these carry - so what a bag names keeps the reach the bag has.
 */
package lib.minecraft.renderer.call.slot;
