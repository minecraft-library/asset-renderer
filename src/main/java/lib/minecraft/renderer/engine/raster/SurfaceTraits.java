package lib.minecraft.renderer.engine.raster;

import org.jetbrains.annotations.NotNull;

/**
 * The four boolean surface concerns the rasterizer reads off each {@link VisibleTriangle}, grouped
 * into one value carried by the triangle.
 * <p>
 * A triangle's surface character is declared once at construction rather than threaded as four loose
 * flags. The {@code with*} helpers compose a new traits value for a derived triangle; they never
 * mutate a built {@code VisibleTriangle}.
 *
 * @param cullBackFaces when {@code true} the engine's back-face culling pass may discard this
 *     triangle; set to {@code false} for two-sided geometry such as leaves, glass panes, banners, and
 *     other semi-transparent or thin blocks that need both sides rendered
 * @param emissive when {@code true} the rasterizer skips ambient shading (full-bright) and composites
 *     with {@code BlendMode.ADD} instead of {@code BlendMode.NORMAL}, matching vanilla Java's
 *     {@code RenderType.eyes} additive emissive pass - used by overlay layers such as spider eyes and
 *     ender dragon eyes that brighten the underlying body texture
 * @param translucent when {@code true} the source cube has texels with {@code 0 < alpha < 255} on
 *     visible faces (slime outer shell, glass/ice-like shells), as distinct from alpha-cutout no-cull
 *     cubes whose texels are strictly {@code alpha == 0} or {@code alpha == 255}; the rasterizer sorts
 *     {@code translucent=true} triangles back-to-front so they blend in vanilla's painter's order
 * @param glinted when {@code true} this triangle is worn-armor (or armor-trim) geometry that should
 *     receive the enchantment foil; the rasterizer records a per-pixel glint mask wherever a glinted
 *     fragment wins the depth test, and the glint compositor applies the foil only on those pixels, so
 *     it lands on the armor rather than the whole silhouette. Always {@code false} for bare skin,
 *     blocks, items, and entity bodies
 */
public record SurfaceTraits(boolean cullBackFaces, boolean emissive,
                            boolean translucent, boolean glinted) {

    /**
     * Opaque, back-face-culled, non-emissive body geometry - the default for skin, blocks, and
     * entity bodies.
     */
    public static final SurfaceTraits OPAQUE_BODY = new SurfaceTraits(true, false, false, false);

    /**
     * Returns a copy of these traits with {@link #cullBackFaces()} set to the given value.
     *
     * @param value the cull-back-faces flag for the copy
     * @return a copy with the given cull-back-faces flag
     */
    public @NotNull SurfaceTraits withCullBackFaces(boolean value) {
        return new SurfaceTraits(value, this.emissive, this.translucent, this.glinted);
    }

    /**
     * Returns a copy of these traits with {@link #glinted()} set to the given value.
     *
     * @param value the glinted flag for the copy
     * @return a copy with the given glinted flag
     */
    public @NotNull SurfaceTraits withGlinted(boolean value) {
        return new SurfaceTraits(this.cullBackFaces, this.emissive, this.translucent, value);
    }

    /**
     * Returns a copy of these traits with {@link #emissive()} set to the given value.
     *
     * @param value the emissive flag for the copy
     * @return a copy with the given emissive flag
     */
    public @NotNull SurfaceTraits withEmissive(boolean value) {
        return new SurfaceTraits(this.cullBackFaces, value, this.translucent, this.glinted);
    }
}
