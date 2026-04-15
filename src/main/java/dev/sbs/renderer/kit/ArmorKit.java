package dev.sbs.renderer.kit;

import dev.sbs.renderer.asset.binding.ArmorPiece;
import dev.sbs.renderer.asset.binding.ArmorTrim;
import dev.sbs.renderer.engine.TextureEngine;
import dev.sbs.renderer.geometry.BlockFace;
import dev.sbs.renderer.geometry.SkinFace;
import dev.sbs.renderer.geometry.VisibleTriangle;
import dev.sbs.renderer.tensor.Vector3f;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.PixelBuffer;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

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

    /** Per-side inflation in model units so armor sits visibly above the skin geometry. */
    private static final float ARMOR_INFLATE = 0.015f;

    /** Additional inflate for trim so it sits above the armor base and avoids z-fighting. */
    private static final float TRIM_INFLATE = 0.003f;

    // ---------------------------------------------------------------------------------------
    // 3D armor (triangles for IsometricEngine / ModelEngine rasterization).
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
        @NotNull TextureEngine engine
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
     */
    public static void compositeSlot2D(
        @NotNull PixelBuffer target,
        @NotNull SkinFace part,
        @NotNull ArmorTrim.Slot slot,
        @NotNull ArmorPiece piece,
        int x, int y, int w, int h,
        @NotNull TextureEngine engine
    ) {
        boolean useLeggingsLayer = slot == ArmorTrim.Slot.LEGGINGS;
        String textureId = useLeggingsLayer
            ? piece.material().leggingsTextureId()
            : piece.material().humanoidTextureId();
        Optional<PixelBuffer> armorTexture = engine.tryResolveTexture(textureId);
        armorTexture.ifPresent(tex -> {
            PixelBuffer face = part.crop(tex, BlockFace.SOUTH, false);
            target.blitScaled(face, x, y, w, h);
        });

        if (piece.trimColor().isPresent() && piece.trimPattern().isPresent()) {
            String trimLayer = useLeggingsLayer ? "humanoid_leggings" : "humanoid";
            resolveTrimTexture(engine, trimLayer, piece.trimPattern().get(), piece.trimColor().get())
                .ifPresent(trimTex -> {
                    PixelBuffer face = part.crop(trimTex, BlockFace.SOUTH, false);
                    target.blitScaled(face, x, y, w, h);
                });
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
     * armor slots. Only bones whose names match the standard humanoid naming convention
     * ({@code head}, {@code body}, {@code right_arm}, {@code left_arm}, {@code right_leg},
     * {@code left_leg}) are considered; entities with non-humanoid bone names are silently
     * skipped.
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
        @NotNull TextureEngine engine
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
        @NotNull TextureEngine engine
    ) {
        SkinFace[] parts = partsForSlot(slot);
        boolean useLeggingsLayer = slot == ArmorTrim.Slot.LEGGINGS;

        String textureId = useLeggingsLayer
            ? piece.material().leggingsTextureId()
            : piece.material().humanoidTextureId();
        Optional<PixelBuffer> armorTexture = engine.tryResolveTexture(textureId);
        if (armorTexture.isEmpty()) return;

        for (SkinFace part : parts) {
            Vector3f[] bounds = bodyPositions.get(part);
            if (bounds == null) continue;
            triangles.addAll(buildPart3D(part, bounds[0], bounds[1], armorTexture.get(), ARMOR_INFLATE));
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
                        trimTexture.get(), ARMOR_INFLATE + TRIM_INFLATE));
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
        PixelBuffer[] faces = part.cropAll(texture, false);
        return GeometryKit.box(inflatedMin, inflatedMax, faces, ColorMath.WHITE);
    }

    /**
     * Resolves and permutes a 3D entity trim texture.
     */
    static @NotNull Optional<PixelBuffer> resolveTrimTexture(
        @NotNull TextureEngine engine,
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
