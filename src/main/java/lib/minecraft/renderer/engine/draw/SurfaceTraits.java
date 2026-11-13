package lib.minecraft.renderer.engine.draw;

import org.jetbrains.annotations.NotNull;

/**
 * The surface concerns the rasterizer reads off each {@link VisibleTriangle}, grouped into one value
 * carried by the triangle: the four flags decided per cube by the builder, plus the
 * {@link PassDeclaration} the whole pass was submitted under.
 * <p>
 * A triangle's surface character is declared once at construction rather than threaded as loose
 * flags. {@link #withCullBackFaces} and {@link #withDirectionalLight} compose a new traits value for
 * a derived triangle; neither mutates a built {@code VisibleTriangle}. A block, item or translucent
 * body surface carries {@link PassDeclaration#DEFAULT}; a cutout body, an equipment layer, worn
 * armour, the wings and a block entity's bones carry {@link PassDeclaration#CUTOUT}; and an overlay
 * carries what its {@code pipeline} node in {@code entity_models.json} declares.
 *
 * @param cullBackFaces when {@code true} the engine's back-face culling pass may discard this
 *     triangle; set to {@code false} for two-sided geometry such as leaves, glass panes, banners, and
 *     other semi-transparent or thin blocks that need both sides rendered
 * @param translucent when {@code true} the source cube has texels with {@code 0 < alpha < 255} on
 *     visible faces (slime outer shell, glass/ice-like shells), as distinct from alpha-cutout no-cull
 *     cubes whose texels are strictly {@code alpha == 0} or {@code alpha == 255}; the rasterizer sorts
 *     {@code translucent=true} triangles back-to-front so they blend in vanilla's painter's order
 * @param glinted when {@code true} this triangle is worn-armor (or armor-trim) geometry that should
 *     receive the enchantment foil; the rasterizer records a per-pixel glint mask wherever a glinted
 *     fragment wins the depth test, and the glint compositor applies the foil only on those pixels, so
 *     it lands on the armor rather than the whole silhouette. Always {@code false} for bare skin,
 *     blocks, items, and entity bodies
 * @param directionalLight when {@code true} the face takes directional shading; {@code false} is
 *     vanilla's {@code getShade(direction, false)}, which answers {@code 1.0}, and is what a model
 *     element declaring {@code "shade": false} asks for (coral fans, cross/crop plants, ladder, vine,
 *     tripwire, redstone dust, torches). The kit bakes {@code 1.0} into the shade scalar of such a
 *     face, so the rasterizer needs no branch; the flag is what lets a GUI relight leave that face
 *     full-bright instead of recomputing a Lambertian for it
 * @param pass what the whole pass declared about how a surviving fragment is shaded, composited,
 *     scaled in opacity, depth-written and ordered - shared by every triangle one build emits, so it
 *     is one value rather than five repeated per triangle
 */
public record SurfaceTraits(boolean cullBackFaces, boolean translucent, boolean glinted,
                            boolean directionalLight, @NotNull PassDeclaration pass) {

    /**
     * Opaque, back-face-culled, directionally lit body geometry drawn through the
     * {@link PassDeclaration#DEFAULT default pass} - player skin boxes, fluids and the shield.
     */
    public static final SurfaceTraits OPAQUE_BODY =
        new SurfaceTraits(true, false, false, true, PassDeclaration.DEFAULT);

    /**
     * Worn-armour geometry - two-sided, glinted and drawn through the
     * {@link PassDeclaration#CUTOUT cutout pass}. It differs from {@link #OPAQUE_BODY} in exactly these
     * three, which is the whole of how armour geometry differs from block geometry.
     * <p>
     * <b>The cull flag is a coverage contract before it is a lighting one.</b> Vanilla submits worn
     * armour through a pipeline that binds no culling alongside an alpha cutout, so where a box's near
     * face is cut away by a transparent texel the far face of that same box shows through the hole.
     * Culling the far faces drops them whole, which reads as a missing wedge wherever the shell stands
     * clear of the body behind it. The flag then also selects the per-face lighting form in
     * {@code Lighting.EntityLighting#shade}, so a face the camera sees from behind is shaded by its
     * camera-facing orientation - the choice vanilla's shader makes per pixel - while a face seen from
     * the front shades identically either way. Both wearers reach that the same way, each lighting its
     * own folded stack in one pass; what differs is the frame the stored normal is read through, the
     * player's boxes being upright where an entity's are turned into the Y-down model frame.
     * <p>
     * The glint flag is what puts the enchantment foil on the armour rather than on the whole
     * silhouette: the rasterizer records a per-pixel mask wherever a glinted fragment wins the depth
     * test, and the compositor applies the foil only there.
     * <p>
     * The pass is vanilla's {@code armorCutoutNoCull}: every layer and trim of a worn piece is drawn
     * through a pipeline declaring no blend function, so a fragment surviving the alpha cutout is
     * written whole rather than blended at its alpha.
     */
    public static final SurfaceTraits WORN_SHELL =
        new SurfaceTraits(false, false, true, true, PassDeclaration.CUTOUT);

    /**
     * Returns a copy of these traits with {@link #cullBackFaces()} set to the given value.
     *
     * @param value the cull-back-faces flag for the copy
     * @return a copy with the given cull-back-faces flag
     */
    public @NotNull SurfaceTraits withCullBackFaces(boolean value) {
        return value == this.cullBackFaces
            ? this
            : new SurfaceTraits(value, this.translucent, this.glinted, this.directionalLight, this.pass);
    }

    /**
     * Returns a copy of these traits with {@link #directionalLight()} set to the given value.
     *
     * @param value the directional-light flag for the copy
     * @return a copy with the given directional-light flag
     */
    public @NotNull SurfaceTraits withDirectionalLight(boolean value) {
        return value == this.directionalLight
            ? this
            : new SurfaceTraits(this.cullBackFaces, this.translucent, this.glinted, value, this.pass);
    }

    /**
     * Returns the traits a re-framed copy of a triangle carries - its cull and directional-light flags
     * kept, neither translucent nor glinted, which block geometry never is, and its pass
     * {@link PassDeclaration#reframed() re-framed}. Asked by the block-side copies that move or refit
     * assembled geometry, which hold a block's own faces and a block entity's cutout bones in one list;
     * the directional-light flag has to survive because the block-icon relight runs after them, and it
     * is what tells the relight to leave a {@code "shade": false} face full-bright.
     *
     * @return the traits the copy carries
     */
    public @NotNull SurfaceTraits reframed() {
        return new SurfaceTraits(this.cullBackFaces, false, false, this.directionalLight, this.pass.reframed());
    }

}
