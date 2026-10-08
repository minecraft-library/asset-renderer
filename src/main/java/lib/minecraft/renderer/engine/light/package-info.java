/**
 * The lighting subsystem: vanilla-parity inventory lighting and the shade application it feeds.
 *
 * <p>{@link lib.minecraft.renderer.engine.light.Lighting Lighting} holds the pre-rotated diffuse
 * light directions and the {@code light.glsl#minecraft_mix_light_separate} dual-light Lambertian
 * for vanilla's three {@code Lighting.Entry} setups - {@code ITEMS_3D} (block icon),
 * {@code ENTITY_IN_UI} (mob portrait), {@code ITEMS_FLAT} (3D special-model item) - plus the
 * four-cardinal-bucket block / fluid approximation (a pre-baked scalar lookup, not a real
 * {@code Lighting.Entry}). The block and fluid kits bake a per-face shade scalar into each
 * {@link lib.minecraft.renderer.engine.draw.VisibleTriangle VisibleTriangle} at build time; an
 * entity's producers emit {@code Shading.UNLIT} and leave the scalar to a later pass.
 *
 * <p>{@link lib.minecraft.renderer.engine.light.Shading Shading} applies that scalar to the
 * rasterized texel (round-half-up to match vanilla GLSL) and owns the relights that resolve one:
 * {@code relightForItems3d} for block-icon and side-lit item geometry under
 * {@code Lighting.ITEMS_3D}, {@code relightForItemsFlat} for front-lit item geometry under
 * {@code Lighting.ITEMS_FLAT}, and {@code relightForEntityInUi} for a folded entity or player stack
 * under {@code Lighting.ENTITY_IN_UI}. It also holds {@code ITEMS_3D_FACING}, the
 * {@code Lighting.ITEMS_3D} shade of a face pointing at the viewer, which a GUI slot's flat layer
 * takes across every texel through the buffer form of {@code apply}.
 *
 * <p>{@link lib.minecraft.renderer.engine.light.LightingFrame LightingFrame} is the orientation a relight
 * shades through, and {@link lib.minecraft.renderer.engine.light.LightingFrame#ENTITY_IN_UI ENTITY_IN_UI}
 * the fixed frame an entity render's relight over its folded stack shades through.
 *
 * <p><b>Parity.</b> Everything here is part of a render, and the pipeline dump serialises loaded data
 * without calling a renderer, so the package declares the {@code engine-renders} claim as a demotion
 * of the dump's verdict on a change made here.
 *
 * @see lib.minecraft.renderer.engine.light.Lighting
 * @see lib.minecraft.renderer.engine.light.Shading
 */
@Parity(claim = "engine-renders", mode = Mode.DEMOTE, scope = Scope.SUBTREE)
package lib.minecraft.renderer.engine.light;

import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Scope;
