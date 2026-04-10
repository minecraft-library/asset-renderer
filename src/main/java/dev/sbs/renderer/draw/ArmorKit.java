package dev.sbs.renderer.draw;

import dev.sbs.renderer.engine.TextureEngine;
import dev.sbs.renderer.engine.VisibleTriangle;
import dev.sbs.renderer.math.Vector3f;
import dev.sbs.renderer.options.EntityOptions;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.PixelBuffer;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Optional;

/**
 * Generates 3D armor overlay geometry for humanoid entity renders. Given the body part
 * positions used by the skin renderer, produces slightly inflated cubes textured from the
 * vanilla armor atlases with optional paletted-permutation trim overlays.
 * <p>
 * The armor texture is a 64x32 atlas whose UV layout matches the base-layer half of the
 * vanilla 64x64 player skin - {@link SkinFace#cropAll(PixelBuffer, boolean) cropAll} works
 * directly on the armor texture. Armor pieces whose texture region is transparent (e.g. the
 * head area of a leggings layer) produce invisible triangles that the depth buffer discards
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
    private static final float INFLATE = 0.015f;

    /** Additional inflate for trim so it sits above the armor base and avoids z-fighting. */
    private static final float TRIM_INFLATE = 0.003f;

    /**
     * Builds all armor and trim triangles for a humanoid body, using the caller's body part
     * positions. The returned triangles are ready to be merged into the skin triangle list
     * before rasterization.
     *
     * @param bodyPositions map from body part to its {@code [min, max]} bounding box corners
     * @param options the entity options carrying equipped armor pieces
     * @param engine the texture engine for pack-aware texture resolution
     * @return the armor + trim triangles, empty when no armor is equipped
     */
    public static @NotNull ConcurrentList<VisibleTriangle> buildHumanoidArmor(
        @NotNull Map<SkinFace, Vector3f[]> bodyPositions,
        @NotNull EntityOptions options,
        @NotNull TextureEngine engine
    ) {
        ConcurrentList<VisibleTriangle> triangles = Concurrent.newList();

        options.getHelmet().ifPresent(piece ->
            addSlot(triangles, bodyPositions, piece, ArmorTrim.Slot.HELMET, engine));
        options.getChestplate().ifPresent(piece ->
            addSlot(triangles, bodyPositions, piece, ArmorTrim.Slot.CHESTPLATE, engine));
        options.getLeggings().ifPresent(piece ->
            addSlot(triangles, bodyPositions, piece, ArmorTrim.Slot.LEGGINGS, engine));
        options.getBoots().ifPresent(piece ->
            addSlot(triangles, bodyPositions, piece, ArmorTrim.Slot.BOOTS, engine));

        return triangles;
    }

    /**
     * Returns whether any equipped armor piece is enchanted, indicating the caller should
     * apply a glint post-process.
     */
    public static boolean hasEnchantedArmor(@NotNull EntityOptions options) {
        return options.getHelmet().map(ArmorPiece::enchanted).orElse(false)
            || options.getChestplate().map(ArmorPiece::enchanted).orElse(false)
            || options.getLeggings().map(ArmorPiece::enchanted).orElse(false)
            || options.getBoots().map(ArmorPiece::enchanted).orElse(false);
    }

    private static void addSlot(
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
            triangles.addAll(buildPart(part, bounds[0], bounds[1], armorTexture.get(), INFLATE));
        }

        // Trim overlay
        if (piece.trimColor().isPresent() && piece.trimPattern().isPresent()) {
            String trimLayer = useLeggingsLayer ? "humanoid_leggings" : "humanoid";
            Optional<PixelBuffer> trimTexture = resolveTrimTexture(
                engine, trimLayer, piece.trimPattern().get(), piece.trimColor().get());
            if (trimTexture.isPresent()) {
                for (SkinFace part : parts) {
                    Vector3f[] bounds = bodyPositions.get(part);
                    if (bounds == null) continue;
                    triangles.addAll(buildPart(part, bounds[0], bounds[1],
                        trimTexture.get(), INFLATE + TRIM_INFLATE));
                }
            }
        }
    }

    /**
     * Builds the triangles for one body part cube, inflated by the given amount and textured
     * from the base-layer UV region of the supplied 64x32 armor (or trim) atlas.
     */
    private static @NotNull ConcurrentList<VisibleTriangle> buildPart(
        @NotNull SkinFace part,
        @NotNull Vector3f min,
        @NotNull Vector3f max,
        @NotNull PixelBuffer texture,
        float inflate
    ) {
        Vector3f inflatedMin = new Vector3f(min.getX() - inflate, min.getY() - inflate, min.getZ() - inflate);
        Vector3f inflatedMax = new Vector3f(max.getX() + inflate, max.getY() + inflate, max.getZ() + inflate);
        PixelBuffer[] faces = part.cropAll(texture, false);
        return GeometryKit.box(inflatedMin, inflatedMax, faces, ColorKit.WHITE);
    }

    /**
     * Maps an armor slot to the {@link SkinFace} body parts it covers.
     */
    private static @NotNull SkinFace @NotNull [] partsForSlot(@NotNull ArmorTrim.Slot slot) {
        return switch (slot) {
            case HELMET -> new SkinFace[]{ SkinFace.HEAD };
            case CHESTPLATE -> new SkinFace[]{ SkinFace.TORSO, SkinFace.RIGHT_ARM, SkinFace.LEFT_ARM };
            case LEGGINGS -> new SkinFace[]{ SkinFace.TORSO, SkinFace.RIGHT_LEG, SkinFace.LEFT_LEG };
            case BOOTS -> new SkinFace[]{ SkinFace.RIGHT_LEG, SkinFace.LEFT_LEG };
        };
    }

    /**
     * Resolves and permutes a 3D entity trim texture. The base pattern is at
     * {@code trims/entity/{layer}/{pattern}.png} (64x32 grayscale) and is recoloured using
     * the same palette permutation as item trims.
     */
    private static @NotNull Optional<PixelBuffer> resolveTrimTexture(
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
