package lib.minecraft.renderer.vanilla.mesh;

import dev.simplified.annotations.AccessLevel;
import dev.simplified.annotations.EnumLookup;
import dev.simplified.annotations.Getter;
import dev.simplified.annotations.NamingStyle;
import dev.simplified.image.PixelBuffer;
import lib.minecraft.renderer.PlayerRenderer;
import lib.minecraft.renderer.engine.draw.SampledTexture;
import lib.minecraft.renderer.engine.geometry.AxisSigns;
import lib.minecraft.renderer.engine.geometry.Box;
import lib.minecraft.renderer.engine.geometry.CornerPhase;
import lib.minecraft.renderer.engine.geometry.Face;
import lib.minecraft.renderer.engine.geometry.Unwrap;
import lib.minecraft.renderer.engine.math.Vector2f;
import lib.minecraft.renderer.engine.math.Vector3f;
import lib.minecraft.renderer.engine.math.Vector4f;
import lib.minecraft.renderer.engine.mesh.FaceTextures;
import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import org.jetbrains.annotations.NotNull;

import java.awt.*;
import java.util.EnumMap;

/**
 * The six boxes a vanilla humanoid player model is built from - each one's pixel extent, the bone
 * name it answers to, and the region of a skin sheet its faces are read out of.
 * <p>
 * Each constant declares an integer pixel box in the vanilla player lattice, where the body spans
 * {@code -16..+16} on Y - the 32-pixel vanilla player height - plus the two atlas origins its base
 * and overlay layers are unwrapped from. <b>Everything else is derived.</b> The six skin rectangles
 * per layer are {@link Unwrap.Atlas#rect} evaluated at that origin under the {@link AxisSigns#HALF_X
 * model frame turn}, which is the same unwrap the entity cube path resolves its UVs from - the player
 * skin unwrap and the entity cube unwrap are one function, and the overlay column is that same
 * function against a different shipped cube (the head's is vanilla's own {@code hat} cube, the
 * torso's its {@code jacket}).
 * <p>
 * <b>Each endpoint is derived from its own integer pixel offset</b>, never as an origin plus an
 * extent. At the body lattice's {@code 0.03} units per pixel that is not a stylistic choice: every
 * limb is 12 pixels long and {@code 12 * 0.03f} is one ULP below the {@code 0.36f} the shipped
 * constants spelled, while every endpoint offset the lattice uses - 2, 4, 8 and 16 pixels -
 * multiplies bit-exactly.
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
@Parity(as = PlayerRenderer.class)
@EnumLookup
@Getter(style = NamingStyle.FLUENT)
@Parity(claim = "face-vocabulary", mode = Mode.DEMOTE)
public enum HumanoidPart {

    //        bone name      min px          max px        base uv   overlay uv
    HEAD     ("head",       -4,   8, -4,     4, 16,  4,     0,  0,    32,  0),
    TORSO    ("body",       -4,  -4, -2,     4,  8,  2,    16, 16,    16, 32),
    RIGHT_ARM("right_arm",  -8,  -4, -2,    -4,  8,  2,    40, 16,    40, 32),
    LEFT_ARM ("left_arm",    4,  -4, -2,     8,  8,  2,    32, 48,    48, 48),
    RIGHT_LEG("right_leg",  -4, -16, -2,     0, -4,  2,     0, 16,     0, 32),
    LEFT_LEG ("left_leg",    0, -16, -2,     4, -4,  2,    16, 48,     0, 48);

    /**
     * The width of the skin sheet vanilla's player model declares, and the height of the full square
     * layout, in texels.
     */
    public static final int SKIN_SIZE = 64;

    /**
     * The height of the legacy skin layout, the top half of the square sheet, in texels.
     */
    public static final int LEGACY_SKIN_HEIGHT = 32;

    /**
     * The armor-mesh bone this part is the player's counterpart of. The shell vanilla dresses a
     * humanoid in names its boxes with these, so a bone resolves to the body box it dresses.
     */
    private final @NotNull String boneName;

    /**
     * Lower corner of this part's box on X, in vanilla player-lattice pixels.
     */
    private final int minPixelX;

    /**
     * Lower corner of this part's box on Y, in vanilla player-lattice pixels.
     */
    private final int minPixelY;

    /**
     * Lower corner of this part's box on Z, in vanilla player-lattice pixels. Read by {@link #box}
     * and {@link #centred} alone - the depth extent is never asked for on its own.
     */
    @Getter(AccessLevel.NONE)
    private final int minPixelZ;

    /**
     * Upper corner of this part's box on X, in vanilla player-lattice pixels.
     */
    private final int maxPixelX;

    /**
     * Upper corner of this part's box on Y, in vanilla player-lattice pixels.
     */
    private final int maxPixelY;

    /**
     * Upper corner of this part's box on Z, in vanilla player-lattice pixels. Read by {@link #box}
     * and {@link #centred} alone - the depth extent is never asked for on its own.
     */
    @Getter(AccessLevel.NONE)
    private final int maxPixelZ;

    /**
     * Base-layer skin rectangle per {@link Face}, derived from the base atlas origin and this
     * part's pixel extent.
     */
    @Getter(AccessLevel.NONE)
    private final @NotNull EnumMap<Face, Rectangle> baseRects;

    /**
     * Overlay-layer (hat / jacket) skin rectangle per {@link Face}, derived from the overlay
     * atlas origin and this part's pixel extent.
     */
    @Getter(AccessLevel.NONE)
    private final @NotNull EnumMap<Face, Rectangle> overlayRects;

    HumanoidPart(
        @NotNull String boneName,
        int minPixelX, int minPixelY, int minPixelZ,
        int maxPixelX, int maxPixelY, int maxPixelZ,
        int baseU, int baseV, int overlayU, int overlayV
    ) {
        this.boneName = boneName;
        this.minPixelX = minPixelX;
        this.minPixelY = minPixelY;
        this.minPixelZ = minPixelZ;
        this.maxPixelX = maxPixelX;
        this.maxPixelY = maxPixelY;
        this.maxPixelZ = maxPixelZ;

        Vector3f size = new Vector3f(
            maxPixelX - minPixelX, maxPixelY - minPixelY, maxPixelZ - minPixelZ);
        this.baseRects = unwrap(new Vector2f(baseU, baseV), size);
        this.overlayRects = unwrap(new Vector2f(overlayU, overlayV), size);
    }

    /**
     * This part's extent along the horizontal axis, in pixels.
     *
     * @return the pixel width
     */
    public int pixelWidth() {
        return this.maxPixelX - this.minPixelX;
    }

    /**
     * This part's extent along the vertical axis, in pixels.
     *
     * @return the pixel height
     */
    public int pixelHeight() {
        return this.maxPixelY - this.minPixelY;
    }

    /**
     * This part's box seated in the player lattice, at the given scale.
     * <p>
     * Each endpoint is its own pixel offset times the scale, never an origin plus a scaled extent -
     * the second form is a full ULP low on every 12-pixel limb at the body scale, while this one is
     * bit-exact on every endpoint the lattice uses.
     *
     * @param unitsPerPixel the model units one skin pixel spans in the target frame
     * @return this part's axis-aligned box
     */
    public @NotNull Box box(float unitsPerPixel) {
        return new Box(
            this.minPixelX * unitsPerPixel, this.minPixelY * unitsPerPixel, this.minPixelZ * unitsPerPixel,
            this.maxPixelX * unitsPerPixel, this.maxPixelY * unitsPerPixel, this.maxPixelZ * unitsPerPixel);
    }

    /**
     * This part's box centred on the origin, at the given scale.
     * <p>
     * <b>Not the same box as {@link #box} in a different frame.</b> A scope that draws one part alone
     * centres it rather than seating it in the body lattice, so the head - whose lattice box spans
     * {@code y 8..16} - becomes a cube about the origin instead.
     *
     * @param unitsPerPixel the model units one skin pixel spans in the target frame
     * @return this part's box, centred on the origin
     */
    public @NotNull Box centred(float unitsPerPixel) {
        float halfX = pixelWidth() * 0.5f * unitsPerPixel;
        float halfY = pixelHeight() * 0.5f * unitsPerPixel;
        float halfZ = (this.maxPixelZ - this.minPixelZ) * 0.5f * unitsPerPixel;
        return new Box(-halfX, -halfY, -halfZ, halfX, halfY, halfZ);
    }

    /**
     * Crops a single face out of the given skin image using the base or overlay rectangle for this
     * part's face - the picture the flat front-facing composite blits, which lays no box. A box reads
     * its faces as regions of the whole sheet instead, through {@link #textures}.
     * <p>
     * Legacy 64x32 skins and the 64x32 equipment atlases carry only the top half of the modern
     * layout - head, body, the <b>right</b> arm and the <b>right</b> leg - with no dedicated
     * left-limb (or overlay) regions; vanilla draws the left limb as the right limb mirrored across
     * the sagittal plane. When a base-layer rectangle falls past the bottom of the texture (the
     * tell-tale of that format) the left arm / leg is therefore sampled from the mirrored right
     * limb. A full 64x64 atlas keeps its own left regions and is cropped directly.
     * <p>
     * <b>That branch is the production armor path, not a legacy corner</b> - the armor atlas is
     * 64x32, so every armored player render takes it on the left arm and the left leg. It also
     * cannot be folded into a turn or a mirror flag: it substitutes a <em>different part's</em> atlas
     * origin, which no permutation of this part's own strip can reach.
     *
     * @param skin the source skin image
     * @param face the cube face to crop
     * @param overlayLayer whether to crop the overlay layer instead of the base layer
     * @return a new pixel buffer whose dimensions match the face's rectangle
     */
    public @NotNull PixelBuffer crop(
        @NotNull PixelBuffer skin,
        @NotNull Face face,
        boolean overlayLayer
    ) {
        Strip strip = strip(face, overlayLayer, skin.height());
        return cropRect(skin, strip.rect(), strip.mirrored());
    }

    /**
     * Returns this part's six rectangles of a sheet as a per-face supplier, ready to feed a box builder.
     * <p>
     * Every face is its rectangle of the whole sheet, normalised by the sheet size the model declares
     * and read through the sheet's own sampler, as the client lays a cube face - so a pixel past a
     * face's edge reads the sheet's neighbouring texel, and a sheet of another size than the one
     * declared is read whole. Where the declared sheet stops above a base-layer rectangle - a legacy
     * 64x32 skin, or the 64x32 equipment sheet the armour reads - a left limb reads its right limb's
     * opposite strip right to left, the mirror {@link #crop} makes.
     * <p>
     * {@link Face#DOWN} is read bottom up. A box builder lays a region with {@link CornerPhase#BAKERY}'s
     * walk, which puts {@code DOWN}'s top row on the box's max-Z edge, while vanilla's cube puts that
     * strip's top row on its model-frame max-Z edge, which the {@link AxisSigns#HALF_X upright turn}
     * carries to min Z. A face map picks a strip and cannot turn it in its own plane, so the turn is
     * made on the region's coordinates.
     *
     * @param sheet the whole sheet, with the sampler it is read through
     * @param sheetWidth the sheet width the model declares, in texels
     * @param sheetHeight the sheet height the model declares, in texels
     * @param overlayLayer whether to read the overlay layer instead of the base layer
     * @return a supplier answering this part's region of {@code sheet} for each face, {@code DOWN} read
     *     bottom up
     */
    public @NotNull FaceTextures textures(
        @NotNull SampledTexture sheet,
        int sheetWidth,
        int sheetHeight,
        boolean overlayLayer
    ) {
        return face -> {
            Strip strip = strip(face, overlayLayer, sheetHeight);
            Rectangle rect = strip.rect();
            Vector4f pixels = new Vector4f(rect.x, rect.y, rect.x + rect.width, rect.y + rect.height);
            if (strip.mirrored()) pixels = pixels.reversedU();
            if (face == Face.DOWN) pixels = pixels.reversedV();
            return new FaceTextures.Region(sheet, pixels, sheetWidth, sheetHeight);
        };
    }

    /**
     * Returns this part's six rectangles of a player skin as a per-face supplier, at the layout the skin
     * declares - {@link #textures} over a sheet {@link #SKIN_SIZE} wide and
     * {@link #skinSheetHeight its layout's} height.
     *
     * @param skin the whole skin, with the sampler it is read through
     * @param overlayLayer whether to read the overlay layer instead of the base layer
     * @return a supplier answering this part's region of {@code skin} for each face
     */
    public @NotNull FaceTextures skinTextures(@NotNull SampledTexture skin, boolean overlayLayer) {
        return this.textures(skin, SKIN_SIZE, skinSheetHeight(skin.pixels()), overlayLayer);
    }

    /**
     * Returns the rectangle one face reads and whether it reads it mirrored. Where the sheet stops above
     * a base-layer rectangle - the tell-tale of a legacy 64x32 layout - a left limb reads its right
     * limb's opposite strip, right to left; every other face reads its own rectangle.
     *
     * @param face the cube face
     * @param overlayLayer whether to read the overlay layer instead of the base layer
     * @param sheetHeight the height the sheet stops at, in texels
     * @return the face's strip
     */
    private @NotNull Strip strip(@NotNull Face face, boolean overlayLayer, int sheetHeight) {
        Rectangle rect = rect(face, overlayLayer);

        if (!overlayLayer && rect.y + rect.height > sheetHeight) {
            HumanoidPart mirror = legacyMirrorSource();
            if (mirror != this) return new Strip(mirror.rect(AxisSigns.MIRROR_X.apply(face), false), true);
        }

        return new Strip(rect, false);
    }

    /**
     * The rectangle of a sheet one face reads, and whether it reads it right to left.
     *
     * @param rect the rectangle, in the sheet's texels
     * @param mirrored whether the face reads it right to left, as a left limb reads its right limb's
     */
    private record Strip(@NotNull Rectangle rect, boolean mirrored) {}

    /**
     * The height of the skin sheet a player model declares for a skin, in texels.
     * <p>
     * A skin half as tall as it is wide is the legacy layout, which declares the top half alone and
     * carries no left-limb or overlay regions; every other skin declares the full square sheet.
     *
     * @param skin the skin to measure
     * @return {@link #LEGACY_SKIN_HEIGHT} for a legacy skin, {@link #SKIN_SIZE} otherwise
     */
    public static int skinSheetHeight(@NotNull PixelBuffer skin) {
        return 2 * skin.height() <= skin.width() ? LEGACY_SKIN_HEIGHT : SKIN_SIZE;
    }

    /**
     * The skin texture rectangle for the given face on the base or overlay layer.
     */
    private @NotNull Rectangle rect(@NotNull Face face, boolean overlayLayer) {
        return overlayLayer ? this.overlayRects.get(face) : this.baseRects.get(face);
    }

    /**
     * Unwraps one cube onto a sheet, face by face, through the entity-atlas unwrap read in the model
     * frame - the same derivation every entity cube's UVs come from.
     */
    private static @NotNull EnumMap<Face, Rectangle> unwrap(
        @NotNull Vector2f origin, @NotNull Vector3f size) {
        EnumMap<Face, Rectangle> rects = new EnumMap<>(Face.class);
        Unwrap.Atlas cube = new Unwrap.Atlas(origin, size, false);

        Face.forEach(face -> {
            Vector4f strip = cube.rect(AxisSigns.HALF_X.apply(face));
            rects.put(face, new Rectangle((int) strip.x(), (int) strip.y(),
                (int) (strip.z() - strip.x()), (int) (strip.w() - strip.y())));
        });

        return rects;
    }

    /**
     * Copies a rectangle out of the skin image, optionally mirrored across its vertical centre line
     * (a sagittal-plane flip used to derive a left limb from the right limb on legacy atlases).
     * Out-of-bounds source pixels are left transparent.
     */
    private static @NotNull PixelBuffer cropRect(@NotNull PixelBuffer skin, @NotNull Rectangle rect, boolean mirrorX) {
        int w = rect.width;
        int h = rect.height;
        int[] pixels = new int[w * h];

        for (int dy = 0; dy < h; dy++) {
            for (int dx = 0; dx < w; dx++) {
                int sx = rect.x + dx;
                int sy = rect.y + dy;
                if (sx < 0 || sx >= skin.width() || sy < 0 || sy >= skin.height()) continue;
                int tx = mirrorX ? w - 1 - dx : dx;
                pixels[dy * w + tx] = skin.getPixel(sx, sy);
            }
        }

        return PixelBuffer.ofStraight(pixels, w, h);
    }

    /**
     * The right-limb part whose texture a left limb mirrors on a legacy 64x32 atlas, or this part
     * itself when no left-limb mirroring applies.
     */
    private @NotNull HumanoidPart legacyMirrorSource() {
        return switch (this) {
            case LEFT_ARM -> RIGHT_ARM;
            case LEFT_LEG -> RIGHT_LEG;
            default -> this;
        };
    }

}
