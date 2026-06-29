package lib.minecraft.renderer.compose;

import org.jetbrains.annotations.NotNull;

/**
 * Per-surface rendering traits a layer assigns to the triangles it contributes.
 * <p>
 * Groups the four boolean concerns the rasterizer reads off each
 * {@code lib.minecraft.renderer.geometry.VisibleTriangle} into one value so a {@link GeometryLayer}
 * can declare a surface's character once instead of threading flags through the build path. This is
 * a construction-side convenience only - {@code VisibleTriangle} keeps its four flat boolean fields,
 * so the rasterizer hot loop is unaffected and output is byte-identical.
 *
 * @param cullBackFaces whether the engine's back-face cull pass may discard back-facing triangles;
 *     {@code false} for two-sided thin geometry (leaves, panes, banners)
 * @param emissive whether the surface is full-bright and composited additively (eyes, glow overlays)
 * @param translucent whether the surface has partial-alpha texels needing back-to-front sorting
 *     (slime outer shell, glass-like shells)
 * @param glinted whether the surface is worn-armor geometry receiving the enchantment foil
 */
public record SurfaceTraits(boolean cullBackFaces, boolean emissive,
                            boolean translucent, boolean glinted) {

    /**
     * Opaque, back-face-culled, non-emissive body geometry - the default for skin, blocks, and
     * entity bodies.
     */
    public static final SurfaceTraits OPAQUE_BODY = new SurfaceTraits(true, false, false, false);

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
