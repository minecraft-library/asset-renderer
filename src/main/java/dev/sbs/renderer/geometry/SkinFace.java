package dev.sbs.renderer.geometry;

import dev.sbs.renderer.draw.GeometryKit;
import dev.sbs.renderer.tensor.Vector2f;
import dev.sbs.renderer.tensor.Vector3f;
import dev.simplified.image.PixelBuffer;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.experimental.Accessors;
import org.jetbrains.annotations.NotNull;

import java.awt.*;
import java.util.EnumMap;

/**
 * Catalogs the six body parts on a vanilla 64x64 player skin and exposes everything a 3D
 * renderer needs to draw them: per-face skin rectangles for both base and overlay layers, the
 * eight local-space cube vertices, a per-face vertex slice, and a per-face UV layout.
 * <p>
 * Each constant declares its box dimensions (width, height, depth in skin pixels) and the
 * {@code [baseX, baseY, overlayX, overlayY]} skin coordinates for its six faces in
 * {@link BlockFace} declaration order. The constructor converts those coordinates into
 * {@link Rectangle} mappings sized from the body part's dimensions, and a static block
 * resolves the eight box corners and the per-face vertex slices using
 * {@link BlockFace#vertexIndices()}.
 * <p>
 * The local coordinate system is normalized so that one Minecraft pixel equals {@code 1/16}
 * model units: HEAD (8x8x8) spans {@code 0.5} on each axis, TORSO (8x12x4) spans
 * {@code 0.5 x 0.75 x 0.25}, and so on. Consumers translate and scale the body part into the
 * final composition themselves.
 * <p>
 * Skin layout reference (vanilla 64x64):
 * <pre>
 * Head base        0..32, 0..16    Head overlay    32..64, 0..16
 * Torso base      16..40, 16..32   Torso overlay   16..40, 32..48
 * Right arm base  40..56, 16..32   Right arm overlay 40..56, 32..48
 * Left arm base   32..48, 48..64   Left arm overlay  48..64, 48..64
 * Right leg base   0..16, 16..32   Right leg overlay  0..16, 32..48
 * Left leg base   16..32, 48..64   Left leg overlay   0..16, 48..64
 * </pre>
 */
@Getter
@Accessors(fluent = true)
public enum SkinFace {

    HEAD(8, 8, 8, new int[][]{
        { 16, 0, 48, 0 },  // DOWN  (bottom of head)
        {  8, 0, 40, 0 },  // UP    (top of head)
        { 24, 8, 56, 8 },  // NORTH (back of head)
        {  8, 8, 40, 8 },  // SOUTH (front/face)
        {  0, 8, 32, 8 },  // WEST  (character's right, viewer's left)
        { 16, 8, 48, 8 }   // EAST  (character's left, viewer's right)
    }),

    TORSO(8, 12, 4, new int[][]{
        { 28, 16, 28, 32 },
        { 20, 16, 20, 32 },
        { 32, 20, 32, 36 },
        { 20, 20, 20, 36 },
        { 16, 20, 16, 36 },
        { 28, 20, 28, 36 }
    }),

    RIGHT_ARM(4, 12, 4, new int[][]{
        { 48, 16, 48, 32 },
        { 44, 16, 44, 32 },
        { 52, 20, 52, 36 },
        { 44, 20, 44, 36 },
        { 40, 20, 40, 36 },
        { 48, 20, 48, 36 }
    }),

    LEFT_ARM(4, 12, 4, new int[][]{
        { 40, 48, 56, 48 },
        { 36, 48, 52, 48 },
        { 44, 52, 60, 52 },
        { 36, 52, 52, 52 },
        { 32, 52, 48, 52 },
        { 40, 52, 56, 52 }
    }),

    RIGHT_LEG(4, 12, 4, new int[][]{
        {  8, 16,  8, 32 },
        {  4, 16,  4, 32 },
        { 12, 20, 12, 36 },
        {  4, 20,  4, 36 },
        {  0, 20,  0, 36 },
        {  8, 20,  8, 36 }
    }),

    LEFT_LEG(4, 12, 4, new int[][]{
        { 24, 48,  8, 48 },
        { 20, 48,  4, 48 },
        { 28, 52, 12, 52 },
        { 20, 52,  4, 52 },
        { 16, 52,  0, 52 },
        { 24, 52,  8, 52 }
    });

    private final int width;
    private final int height;
    private final int depth;

    @Getter(AccessLevel.NONE)
    private final @NotNull EnumMap<BlockFace, Rectangle> baseMappings;

    @Getter(AccessLevel.NONE)
    private final @NotNull EnumMap<BlockFace, Rectangle> overlayMappings;

    @Getter(AccessLevel.NONE)
    private final @NotNull Vector3f @NotNull [] cornerVertices;

    @Getter(AccessLevel.NONE)
    private final @NotNull EnumMap<BlockFace, Vector3f[]> faceVertices;

    SkinFace(int width, int height, int depth, int @NotNull [] @NotNull [] faceCoords) {
        this.width = width;
        this.height = height;
        this.depth = depth;
        this.baseMappings = new EnumMap<>(BlockFace.class);
        this.overlayMappings = new EnumMap<>(BlockFace.class);
        this.faceVertices = new EnumMap<>(BlockFace.class);

        BlockFace[] order = BlockFace.values();
        for (int i = 0; i < order.length; i++) {
            BlockFace face = order[i];
            int[] xy = faceCoords[i];
            int[] size = faceSize(face, width, height, depth);
            this.baseMappings.put(face, new Rectangle(xy[0], xy[1], size[0], size[1]));
            this.overlayMappings.put(face, new Rectangle(xy[2], xy[3], size[0], size[1]));
        }

        float hx = width / 32f;
        float hy = height / 32f;
        float hz = depth / 32f;
        this.cornerVertices = new Vector3f[]{
            new Vector3f(-hx, -hy, -hz),
            new Vector3f( hx, -hy, -hz),
            new Vector3f( hx,  hy, -hz),
            new Vector3f(-hx,  hy, -hz),
            new Vector3f(-hx, -hy,  hz),
            new Vector3f( hx, -hy,  hz),
            new Vector3f( hx,  hy,  hz),
            new Vector3f(-hx,  hy,  hz)
        };
    }

    // Per-face UV layout shared by every body part - the skin unwrap orientation is the same
    // for every cube in the player model, only the box dimensions differ.
    private static final @NotNull EnumMap<BlockFace, Vector2f[]> UV_LAYOUTS;

    static {
        UV_LAYOUTS = new EnumMap<>(BlockFace.class);
        UV_LAYOUTS.put(BlockFace.DOWN, UvLayout.BOTTOM.uvMap);
        UV_LAYOUTS.put(BlockFace.UP, UvLayout.STANDARD.uvMap);
        UV_LAYOUTS.put(BlockFace.NORTH, UvLayout.BACK.uvMap);
        UV_LAYOUTS.put(BlockFace.SOUTH, UvLayout.STANDARD.uvMap);
        UV_LAYOUTS.put(BlockFace.WEST, UvLayout.STANDARD.uvMap);
        UV_LAYOUTS.put(BlockFace.EAST, UvLayout.STANDARD.uvMap);

        for (SkinFace part : values()) {
            for (BlockFace face : BlockFace.values()) {
                int[] indices = face.vertexIndices();
                part.faceVertices.put(face, new Vector3f[]{
                    part.cornerVertices[indices[0]],
                    part.cornerVertices[indices[1]],
                    part.cornerVertices[indices[2]],
                    part.cornerVertices[indices[3]]
                });
            }
        }
    }

    /**
     * Returns the skin texture rectangle for the given face on the base or overlay layer.
     *
     * @param face the cube face direction
     * @param overlayLayer {@code true} for the overlay (hat/jacket) layer,
     *     {@code false} for the base skin layer
     * @return the rectangle on the 64x64 skin image
     */
    public @NotNull Rectangle mapping(@NotNull BlockFace face, boolean overlayLayer) {
        return overlayLayer ? this.overlayMappings.get(face) : this.baseMappings.get(face);
    }

    /**
     * Returns the four local-space vertices for the given face on this body part's box, in the
     * TL, TR, BR, BL CCW order used by {@link dev.sbs.renderer.draw.GeometryKit}.
     *
     * @param face the cube face direction
     * @return the four face corner positions in local model space
     */
    public @NotNull Vector3f @NotNull [] vertices(@NotNull BlockFace face) {
        return this.faceVertices.get(face);
    }

    /**
     * Returns the four UV corners for the given face, describing how the cropped texture maps
     * onto the cube face. Shared across every body part because skin unwrap orientation is
     * uniform across the vanilla player model.
     *
     * @param face the cube face direction
     * @return the four UV corners in normalized {@code [0, 1]} space
     */
    public @NotNull Vector2f @NotNull [] uvMap(@NotNull BlockFace face) {
        return UV_LAYOUTS.get(face);
    }

    /**
     * Crops a single face out of the given skin image using the {@link #mapping base or overlay}
     * rectangle for this body part's face.
     *
     * @param skin the source skin image
     * @param face the cube face to crop
     * @param overlayLayer whether to crop the overlay layer instead of the base layer
     * @return a new pixel buffer whose dimensions match the face's rectangle
     */
    public @NotNull PixelBuffer crop(
        @NotNull PixelBuffer skin,
        @NotNull BlockFace face,
        boolean overlayLayer
    ) {
        Rectangle rect = mapping(face, overlayLayer);
        int w = rect.width;
        int h = rect.height;
        int[] pixels = new int[w * h];

        for (int dy = 0; dy < h; dy++) {
            for (int dx = 0; dx < w; dx++) {
                int sx = rect.x + dx;
                int sy = rect.y + dy;
                if (sx < 0 || sx >= skin.getWidth() || sy < 0 || sy >= skin.getHeight()) continue;
                pixels[dy * w + dx] = skin.getPixel(sx, sy);
            }
        }
        return PixelBuffer.of(pixels, w, h);
    }

    /**
     * Crops all six faces of this body part out of the skin image in {@link BlockFace}
     * declaration order (DOWN, UP, NORTH, SOUTH, WEST, EAST), matching the array layout that
     * {@link GeometryKit#box} expects.
     *
     * @param skin the source skin image
     * @param overlayLayer whether to crop the overlay layer instead of the base layer
     * @return a six-element array of cropped faces ordered by {@code BlockFace.ordinal()}
     */
    public @NotNull PixelBuffer @NotNull [] cropAll(@NotNull PixelBuffer skin, boolean overlayLayer) {
        PixelBuffer[] result = new PixelBuffer[BlockFace.values().length];
        for (BlockFace face : BlockFace.values())
            result[face.ordinal()] = crop(skin, face, overlayLayer);
        return result;
    }

    /**
     * Resolves the {@code (width, height)} of a single face rectangle given the body part's box
     * dimensions. Top and bottom faces project width and depth; north and south faces project
     * width and height; east and west faces project depth and height.
     */
    private static int @NotNull [] faceSize(@NotNull BlockFace face, int width, int height, int depth) {
        return switch (face) {
            case DOWN, UP -> new int[]{ width, depth };
            case NORTH, SOUTH -> new int[]{ width, height };
            case WEST, EAST -> new int[]{ depth, height };
        };
    }

    /**
     * The three UV orientation patterns used by the vanilla player model skin unwrap. Every
     * face of every body part picks one of these three layouts; most faces use
     * {@link #STANDARD} but the back and bottom faces need mirrored or rotated samplings
     * because of how Minecraft lays the skin out flat.
     */
    private enum UvLayout {

        STANDARD(new Vector2f[]{
            new Vector2f(1f, 0f), new Vector2f(0f, 0f), new Vector2f(0f, 1f), new Vector2f(1f, 1f)
        }),
        BACK(new Vector2f[]{
            new Vector2f(0f, 1f), new Vector2f(1f, 1f), new Vector2f(1f, 0f), new Vector2f(0f, 0f)
        }),
        BOTTOM(new Vector2f[]{
            new Vector2f(1f, 1f), new Vector2f(0f, 1f), new Vector2f(0f, 0f), new Vector2f(1f, 0f)
        });

        final @NotNull Vector2f @NotNull [] uvMap;

        UvLayout(@NotNull Vector2f @NotNull [] uvMap) {
            this.uvMap = uvMap;
        }

    }

}
