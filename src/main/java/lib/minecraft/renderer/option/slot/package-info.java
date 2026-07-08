/**
 * Layer-composition slot vocabularies - one {@code LayerSlot} enum per renderer, naming the fixed
 * paint / emission order of its layer stack and the splice points a caller's {@code layerDecorator}
 * targets. Relocated out of the individual {@code *Options} classes so the slot taxonomy is one
 * cohesive package rather than nested boilerplate.
 */
package lib.minecraft.renderer.option.slot;
