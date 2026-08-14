/**
 * The lighting subsystem: vanilla-parity inventory lighting and the shade application it feeds.
 *
 * <p>{@link lib.minecraft.renderer.engine.light.Lighting Lighting} holds the pre-rotated diffuse
 * light directions and the {@code light.glsl#minecraft_mix_light_separate} dual-light Lambertian
 * for vanilla's three {@code Lighting.Entry} setups - {@code ITEMS_3D} (block icon),
 * {@code ENTITY_IN_UI} (mob portrait), {@code ITEMS_FLAT} (3D special-model item) - plus the
 * four-cardinal-bucket block / fluid approximation (a pre-baked scalar lookup, not a real
 * {@code Lighting.Entry}). The block and fluid kits bake a per-face shade scalar into each
 * {@link lib.minecraft.renderer.engine.raster.VisibleTriangle VisibleTriangle} at build time; an
 * entity's producers emit {@code Shading.UNLIT} and leave the scalar to a later pass.
 *
 * <p>{@link lib.minecraft.renderer.engine.light.Shading Shading} applies that scalar to the
 * rasterized texel (round-half-up to match vanilla GLSL) and owns the two relights that resolve one:
 * {@code relightForItems3d} for block-icon geometry under {@code Lighting.ITEMS_3D}, and
 * {@code relightForEntityInUi} for a folded entity or player stack under
 * {@code Lighting.ENTITY_IN_UI}.
 *
 * @see lib.minecraft.renderer.engine.light.Lighting
 * @see lib.minecraft.renderer.engine.light.Shading
 */
package lib.minecraft.renderer.engine.light;
