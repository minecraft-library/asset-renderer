package lib.minecraft.renderer.engine.kit;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.engine.raster.GlintMask;
import lib.minecraft.renderer.engine.raster.VisibleTriangle;
import lib.minecraft.renderer.engine.texture.Textures;
import lib.minecraft.renderer.face.BlockFace;
import lib.minecraft.renderer.face.SkinFace;
import lib.minecraft.renderer.request.ArmorMaterial;
import lib.minecraft.renderer.request.ArmorPiece;
import lib.minecraft.renderer.request.ArmorTrim;
import lib.minecraft.renderer.tensor.Vector3f;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * Generates 3D armor overlay geometry and 2D armor sprite layers for humanoid renders. Given the
 * body part positions used by the skin renderer, produces slightly inflated cubes (3D) or
 * composited face crops (2D) textured from the vanilla armor atlases with optional
 * paletted-permutation trim overlays.
 * <p>
 * The armor texture is a 64x32 atlas whose UV layout matches the base-layer half of the
 * vanilla 64x64 player skin - {@link SkinFace#cropAll(PixelBuffer, boolean) cropAll} and
 * {@link SkinFace#crop(PixelBuffer, BlockFace, boolean) crop} work directly on the armor
 * texture. Armor pieces whose texture region is transparent (e.g. the head area of a leggings
 * layer) produce invisible geometry that the depth buffer or alpha compositing discards
 * naturally.
 * <p>
 * Two texture layers correspond to the vanilla equipment paths:
 * <ul>
 * <li><b>Layer 1</b> ({@code entity/equipment/humanoid/{material}}) - helmet, chestplate,
 * arms, boots</li>
 * <li><b>Layer 2</b> ({@code entity/equipment/humanoid_leggings/{material}}) - leggings
 * (waist + legs)</li>
 * </ul>
 */
@UtilityClass
public class ArmorKit {

    /**
     * Per-side inflation in model units for the layer-1 pieces (helmet, chestplate, boots) so armor
     * sits visibly above the skin geometry.
     */
    private static final float ARMOR_INFLATE = 0.015f;

    /**
     * Per-side inflation for the layer-2 leggings. Smaller than {@link #ARMOR_INFLATE} so the
     * leggings sit <em>inside</em> the chestplate on the torso and inside the boots on the lower
     * legs - mirroring vanilla's armor layers (layer 1 inflated {@code 1.0px}, layer 2
     * {@code 0.5px}). Without the inset the two coplanar torso cubes z-fight and the leggings waist
     * shows through the chestplate.
     */
    private static final float LEGGINGS_INFLATE = 0.008f;

    /**
     * Additional inflate for trim so it sits above the armor base and avoids z-fighting.
     */
    private static final float TRIM_INFLATE = 0.003f;

    /**
     * Vanilla's default {@code minecraft:dye} colour for undyed leather armour ({@code #A06540}),
     * applied to the leather base layer when an {@link ArmorPiece} carries no explicit dye.
     */
    private static final int DEFAULT_LEATHER_COLOR = 0xFFA06540;

    // ---------------------------------------------------------------------------------------
    // 3D armor (triangles for ModelEngine rasterization).
    // ---------------------------------------------------------------------------------------

    /**
     * Builds all armor and trim triangles for a humanoid body.
     *
     * @param bodyPositions map from body part to its {@code [min, max]} bounding box corners
     * @param helmet equipped helmet, or empty
     * @param chestplate equipped chestplate, or empty
     * @param leggings equipped leggings, or empty
     * @param boots equipped boots, or empty
     * @param engine the texture engine for pack-aware texture resolution
     * @return the armor + trim triangles, empty when no armor is equipped
     */
    public static @NotNull ConcurrentList<VisibleTriangle> buildHumanoidArmor3D(
        @NotNull Map<SkinFace, Vector3f[]> bodyPositions,
        @NotNull Optional<ArmorPiece> helmet,
        @NotNull Optional<ArmorPiece> chestplate,
        @NotNull Optional<ArmorPiece> leggings,
        @NotNull Optional<ArmorPiece> boots,
        @NotNull Textures engine
    ) {
        ConcurrentList<VisibleTriangle> triangles = Concurrent.newList();

        helmet.ifPresent(piece ->
            addSlot3D(triangles, bodyPositions, piece, ArmorTrim.Slot.HELMET, engine));
        chestplate.ifPresent(piece ->
            addSlot3D(triangles, bodyPositions, piece, ArmorTrim.Slot.CHESTPLATE, engine));
        leggings.ifPresent(piece ->
            addSlot3D(triangles, bodyPositions, piece, ArmorTrim.Slot.LEGGINGS, engine));
        boots.ifPresent(piece ->
            addSlot3D(triangles, bodyPositions, piece, ArmorTrim.Slot.BOOTS, engine));

        return triangles;
    }

    // ---------------------------------------------------------------------------------------
    // 2D armor (composites south-facing face crops onto a canvas).
    // ---------------------------------------------------------------------------------------

    /**
     * Composites the 2D front-facing armor and trim sprites for a single body part onto the
     * canvas at the given position and scale. The slot determines whether to use the humanoid
     * (layer 1) or humanoid_leggings (layer 2) texture atlas.
     *
     * @param target the target buffer
     * @param part the body part whose south face to crop from the armor atlas
     * @param slot the armor slot that determines the texture layer
     * @param piece the armor piece to render
     * @param x the destination X on the buffer
     * @param y the destination Y on the buffer
     * @param w the destination width
     * @param h the destination height
     * @param engine the texture engine for pack-aware texture resolution
     * @param glintMask the per-pixel glint mask to stamp the armor / trim coverage into (so the
     *     enchantment foil lands on the armor, not the bare skin), or {@code null} to skip
     */
    public static void compositeSlot2D(
        @NotNull PixelBuffer target,
        @NotNull SkinFace part,
        @NotNull ArmorTrim.Slot slot,
        @NotNull ArmorPiece piece,
        int x, int y, int w, int h,
        @NotNull Textures engine,
        @Nullable GlintMask glintMask
    ) {
        boolean useLeggingsLayer = slot == ArmorTrim.Slot.LEGGINGS;
        Optional<PixelBuffer> armorTexture = resolveArmorTexture(engine, piece, useLeggingsLayer);
        armorTexture.ifPresent(tex -> {
            PixelBuffer face = part.crop(tex, BlockFace.SOUTH, false);
            target.blitScaled(face, x, y, w, h);
            stampMaskScaled(glintMask, face, x, y, w, h);
        });

        if (piece.trimColor().isPresent() && piece.trimPattern().isPresent()) {
            String trimLayer = useLeggingsLayer ? "humanoid_leggings" : "humanoid";
            resolveTrimTexture(engine, trimLayer, piece.trimPattern().get(), piece.trimColor().get())
                .ifPresent(trimTex -> {
                    PixelBuffer face = part.crop(trimTex, BlockFace.SOUTH, false);
                    target.blitScaled(face, x, y, w, h);
                    stampMaskScaled(glintMask, face, x, y, w, h);
                });
        }
    }

    /**
     * Marks the glint mask over the destination rectangle wherever the scaled source {@code face}
     * has a non-transparent texel, mirroring {@code blitScaled}'s nearest-neighbour mapping. This is
     * the 2D analogue of the 3D rasterizer's per-pixel glint marking - it records exactly the armor /
     * trim coverage so the foil never lands on the bare skin underneath. No-op when {@code mask} is
     * {@code null}.
     */
    private static void stampMaskScaled(@Nullable GlintMask mask, @NotNull PixelBuffer face, int x, int y, int w, int h) {
        if (mask == null) return;
        int fw = face.width();
        int fh = face.height();
        if (fw <= 0 || fh <= 0 || w <= 0 || h <= 0) return;
        for (int dy = 0; dy < h; dy++) {
            int sy = Math.min(fh - 1, dy * fh / h);
            for (int dx = 0; dx < w; dx++) {
                int sx = Math.min(fw - 1, dx * fw / w);
                if (ColorMath.alpha(face.getPixel(sx, sy)) != 0)
                    mask.mark(x + dx, y + dy);
            }
        }
    }

    /**
     * Returns whether any of the given armor pieces is enchanted.
     */
    public static boolean hasEnchantedArmor(
        @NotNull Optional<ArmorPiece> helmet,
        @NotNull Optional<ArmorPiece> chestplate,
        @NotNull Optional<ArmorPiece> leggings,
        @NotNull Optional<ArmorPiece> boots
    ) {
        return helmet.map(ArmorPiece::enchanted).orElse(false)
            || chestplate.map(ArmorPiece::enchanted).orElse(false)
            || leggings.map(ArmorPiece::enchanted).orElse(false)
            || boots.map(ArmorPiece::enchanted).orElse(false);
    }

    // ---------------------------------------------------------------------------------------
    // Entity armor (maps bone names to humanoid SkinFace parts).
    // ---------------------------------------------------------------------------------------

    /**
     * Maps a vanilla humanoid bone name to the {@link SkinFace} body part it drives for entity
     * armor. Accepts both the snake_case ({@code right_arm}) and camelCase ({@code rightArm})
     * spellings that appear across vanilla model classes so either bytecode-derived naming
     * resolves. Bones absent from this map are non-humanoid and carry no armor.
     */
    private static final @NotNull Map<String, SkinFace> HUMANOID_BONE_MAP = Map.ofEntries(
        Map.entry("head", SkinFace.HEAD),
        Map.entry("body", SkinFace.TORSO),
        Map.entry("right_arm", SkinFace.RIGHT_ARM),
        Map.entry("left_arm", SkinFace.LEFT_ARM),
        Map.entry("right_leg", SkinFace.RIGHT_LEG),
        Map.entry("left_leg", SkinFace.LEFT_LEG),
        Map.entry("rightArm", SkinFace.RIGHT_ARM),
        Map.entry("leftArm", SkinFace.LEFT_ARM),
        Map.entry("rightLeg", SkinFace.RIGHT_LEG),
        Map.entry("leftLeg", SkinFace.LEFT_LEG)
    );

    /**
     * Builds armor triangles for an entity by mapping its bone bounding boxes to humanoid
     * armor slots via {@link #HUMANOID_BONE_MAP} (the standard {@code head} / {@code body} /
     * {@code right_arm} / {@code left_arm} / {@code right_leg} / {@code left_leg} names, in either
     * snake_case or camelCase spelling). Bones with no humanoid mapping are silently skipped, so a
     * non-humanoid entity simply yields no armor.
     *
     * @param boneBounds map of bone name to {@code [min, max]} in normalized model space
     * @param helmet equipped helmet, or empty
     * @param chestplate equipped chestplate, or empty
     * @param leggings equipped leggings, or empty
     * @param boots equipped boots, or empty
     * @param engine the texture engine for pack-aware texture resolution
     * @return the armor + trim triangles
     */
    public static @NotNull ConcurrentList<VisibleTriangle> buildEntityArmor3D(
        @NotNull Map<String, Vector3f[]> boneBounds,
        @NotNull Optional<ArmorPiece> helmet,
        @NotNull Optional<ArmorPiece> chestplate,
        @NotNull Optional<ArmorPiece> leggings,
        @NotNull Optional<ArmorPiece> boots,
        @NotNull Textures engine
    ) {
        Map<SkinFace, Vector3f[]> bodyPositions = new EnumMap<>(SkinFace.class);
        for (var entry : boneBounds.entrySet()) {
            SkinFace part = HUMANOID_BONE_MAP.get(entry.getKey());
            if (part != null)
                bodyPositions.put(part, entry.getValue());
        }
        return buildHumanoidArmor3D(bodyPositions, helmet, chestplate, leggings, boots, engine);
    }

    // ---------------------------------------------------------------------------------------
    // Shared internals.
    // ---------------------------------------------------------------------------------------

    /**
     * Maps an armor slot to the {@link SkinFace} body parts it covers.
     */
    public static @NotNull SkinFace @NotNull [] partsForSlot(@NotNull ArmorTrim.Slot slot) {
        return switch (slot) {
            case HELMET -> new SkinFace[]{ SkinFace.HEAD };
            case CHESTPLATE -> new SkinFace[]{ SkinFace.TORSO, SkinFace.RIGHT_ARM, SkinFace.LEFT_ARM };
            case LEGGINGS -> new SkinFace[]{ SkinFace.TORSO, SkinFace.RIGHT_LEG, SkinFace.LEFT_LEG };
            case BOOTS -> new SkinFace[]{ SkinFace.RIGHT_LEG, SkinFace.LEFT_LEG };
        };
    }

    private static void addSlot3D(
        @NotNull ConcurrentList<VisibleTriangle> triangles,
        @NotNull Map<SkinFace, Vector3f[]> bodyPositions,
        @NotNull ArmorPiece piece,
        @NotNull ArmorTrim.Slot slot,
        @NotNull Textures engine
    ) {
        SkinFace[] parts = partsForSlot(slot);
        boolean useLeggingsLayer = slot == ArmorTrim.Slot.LEGGINGS;
        float baseInflate = useLeggingsLayer ? LEGGINGS_INFLATE : ARMOR_INFLATE;

        Optional<PixelBuffer> armorTexture = resolveArmorTexture(engine, piece, useLeggingsLayer);
        if (armorTexture.isEmpty()) return;

        for (SkinFace part : parts) {
            Vector3f[] bounds = bodyPositions.get(part);
            if (bounds == null) continue;
            triangles.addAll(buildPart3D(part, bounds[0], bounds[1], armorTexture.get(), baseInflate));
        }

        if (piece.trimColor().isPresent() && piece.trimPattern().isPresent()) {
            String trimLayer = useLeggingsLayer ? "humanoid_leggings" : "humanoid";
            Optional<PixelBuffer> trimTexture = resolveTrimTexture(
                engine, trimLayer, piece.trimPattern().get(), piece.trimColor().get());
            if (trimTexture.isPresent()) {
                for (SkinFace part : parts) {
                    Vector3f[] bounds = bodyPositions.get(part);
                    if (bounds == null) continue;
                    triangles.addAll(buildPart3D(part, bounds[0], bounds[1],
                        trimTexture.get(), baseInflate + TRIM_INFLATE));
                }
            }
        }
    }

    private static @NotNull ConcurrentList<VisibleTriangle> buildPart3D(
        @NotNull SkinFace part,
        @NotNull Vector3f min,
        @NotNull Vector3f max,
        @NotNull PixelBuffer texture,
        float inflate
    ) {
        Vector3f inflatedMin = new Vector3f(min.x() - inflate, min.y() - inflate, min.z() - inflate);
        Vector3f inflatedMax = new Vector3f(max.x() + inflate, max.y() + inflate, max.z() + inflate);
        // Build every armor / trim triangle already flagged glinted so the rasterizer's per-pixel
        // glint mask restricts the enchantment foil to the armor, not the whole body silhouette.
        return BlockGeometryKit.buildBoxTriangles(
            inflatedMin, inflatedMax, part.cropAll(texture, false), ColorMath.WHITE, true);
    }

    /**
     * Resolves the base armor texture for a slot, applying leather dye when the piece's material is
     * {@link ArmorMaterial#LEATHER}: the grayscale base layer is tinted by the piece's
     * {@link ArmorPiece#dyeColor() dye} (or {@link #DEFAULT_LEATHER_COLOR} when absent), then the
     * undyed {@code leather_overlay} is composited on top untinted. Non-dyeable materials resolve to
     * their flat base texture unchanged.
     *
     * @param engine the texture engine for pack-aware texture resolution
     * @param piece the armor piece
     * @param leggingsLayer whether to resolve the layer 2 leggings atlas instead of layer 1
     * @return the resolved (and, for leather, dye-tinted + overlaid) texture, or empty when the base
     *     texture is not present in the active pack
     */
    private static @NotNull Optional<PixelBuffer> resolveArmorTexture(
        @NotNull Textures engine,
        @NotNull ArmorPiece piece,
        boolean leggingsLayer
    ) {
        ArmorMaterial material = piece.material();
        String baseId = leggingsLayer ? material.leggingsTextureId() : material.humanoidTextureId();
        Optional<PixelBuffer> base = engine.tryResolveTexture(baseId);
        if (base.isEmpty() || !material.dyeable())
            return base;

        int dye = piece.dyeColor().orElse(DEFAULT_LEATHER_COLOR);
        PixelBuffer tinted = ColorMath.tint(base.get(), dye);

        String overlayId = leggingsLayer ? material.leggingsOverlayTextureId() : material.humanoidOverlayTextureId();
        Optional<PixelBuffer> overlay = engine.tryResolveTexture(overlayId);
        if (overlay.isEmpty())
            return Optional.of(tinted);

        PixelBuffer combined = PixelBuffer.create(tinted.width(), tinted.height());
        combined.blit(tinted, 0, 0);
        combined.blit(overlay.get(), 0, 0);
        return Optional.of(combined);
    }

    /**
     * Resolves and permutes a 3D entity-armor trim texture. Unlike {@link TrimKit}'s item-slot
     * path, this pulls the trim from the {@code trims/entity/{layer}} atlas ({@code humanoid} or
     * {@code humanoid_leggings}) and runs the same {@link TrimKit#permute paletted permutation}
     * against the shared {@code trim_palette} key and the material's colour strip.
     *
     * @param engine the texture engine for pack-aware texture resolution
     * @param layer the entity trim layer ({@code humanoid} or {@code humanoid_leggings})
     * @param pattern the trim pattern supplying the grayscale base texture key
     * @param color the trim material supplying the colour palette key
     * @return the permuted trim overlay, or empty when any of the three source textures is missing
     */
    static @NotNull Optional<PixelBuffer> resolveTrimTexture(
        @NotNull Textures engine,
        @NotNull String layer,
        @NotNull ArmorTrim.Pattern pattern,
        @NotNull ArmorTrim.Color color
    ) {
        String patternId = "minecraft:trims/entity/" + layer + "/" + pattern.getKey();
        String paletteKeyId = "minecraft:trims/color_palettes/trim_palette";
        String colorPaletteId = "minecraft:trims/color_palettes/" + color.getKey();

        Optional<PixelBuffer> base = engine.tryResolveTexture(patternId);
        Optional<PixelBuffer> paletteKey = engine.tryResolveTexture(paletteKeyId);
        Optional<PixelBuffer> colorPalette = engine.tryResolveTexture(colorPaletteId);

        if (base.isEmpty() || paletteKey.isEmpty() || colorPalette.isEmpty())
            return Optional.empty();

        return Optional.of(TrimKit.permute(base.get(), paletteKey.get(), colorPalette.get()));
    }

}
