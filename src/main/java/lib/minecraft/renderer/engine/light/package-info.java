/**
 * The lighting subsystem: vanilla-parity inventory lighting and the shade application it feeds.
 *
 * <p>{@link lib.minecraft.renderer.engine.light.Lighting Lighting} holds the pre-rotated diffuse
 * light directions and the {@code light.glsl#minecraft_mix_light_separate} dual-light Lambertian
 * for vanilla's four {@code Lighting.Entry} setups - {@code ITEMS_3D} (block icon),
 * {@code ENTITY_IN_UI} (mob portrait), {@code ITEMS_FLAT} (3D special-model item), and the
 * four-cardinal-bucket block / fluid approximation. Kits bake a per-face or per-vertex shade scalar
 * into each {@link lib.minecraft.renderer.geometry.VisibleTriangle VisibleTriangle} at build time.
 *
 * <p>{@link lib.minecraft.renderer.engine.light.Shading Shading} applies that scalar to the
 * rasterized texel (round-half-up to match vanilla GLSL) and re-shades block-icon geometry for the
 * {@code Lighting.ITEMS_3D} GUI path ({@code relightForItems3d}). It owns the {@code SHADE_DISABLED}
 * sentinel for {@code "shade": false} elements.
 *
 * @see lib.minecraft.renderer.engine.light.Lighting
 * @see lib.minecraft.renderer.engine.light.Shading
 */
package lib.minecraft.renderer.engine.light;
