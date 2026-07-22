package lib.minecraft.renderer.engine.kit;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.PixelBuffer;
import dev.simplified.image.pixel.PixelMask;
import lib.minecraft.renderer.asset.equipment.LayerType;
import lib.minecraft.renderer.asset.pack.rule.CitResult;
import lib.minecraft.renderer.asset.pack.rule.ItemContext;
import lib.minecraft.renderer.engine.RendererContext;
import lib.minecraft.renderer.engine.light.Lighting;
import lib.minecraft.renderer.engine.raster.VisibleTriangle;
import lib.minecraft.renderer.engine.texture.Textures;
import lib.minecraft.renderer.face.BlockFace;
import lib.minecraft.renderer.face.SkinFace;
import lib.minecraft.renderer.option.spec.ArmorPiece;
import lib.minecraft.renderer.option.spec.ArmorTrim;
import lib.minecraft.renderer.tensor.Vector3f;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

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
     * @param items the equipped item identity per slot, for the pack-rule (CIT) texture override; empty
     *     leaves each slot on its equipment-model texture
     * @param engine the texture engine for pack-aware texture resolution
     * @return the armor + trim triangles, empty when no armor is equipped
     */
    public static @NotNull ConcurrentList<VisibleTriangle> buildHumanoidArmor3D(
        @NotNull Map<SkinFace, Vector3f[]> bodyPositions,
        @NotNull Optional<ArmorPiece> helmet,
        @NotNull Optional<ArmorPiece> chestplate,
        @NotNull Optional<ArmorPiece> leggings,
        @NotNull Optional<ArmorPiece> boots,
        @NotNull Map<ArmorTrim.Slot, ItemContext> items,
        @NotNull Textures engine
    ) {
        ConcurrentList<VisibleTriangle> triangles = Concurrent.newList();

        helmet.ifPresent(piece ->
            addSlot3D(triangles, bodyPositions, piece, ArmorTrim.Slot.HELMET, itemFor(items, ArmorTrim.Slot.HELMET), engine));
        chestplate.ifPresent(piece ->
            addSlot3D(triangles, bodyPositions, piece, ArmorTrim.Slot.CHESTPLATE, itemFor(items, ArmorTrim.Slot.CHESTPLATE), engine));
        leggings.ifPresent(piece ->
            addSlot3D(triangles, bodyPositions, piece, ArmorTrim.Slot.LEGGINGS, itemFor(items, ArmorTrim.Slot.LEGGINGS), engine));
        boots.ifPresent(piece ->
            addSlot3D(triangles, bodyPositions, piece, ArmorTrim.Slot.BOOTS, itemFor(items, ArmorTrim.Slot.BOOTS), engine));

        return triangles;
    }

    /**
     * The equipped item context for a slot, or empty when the caller supplied none - the pack-rule
     * (CIT) override is consulted only for a present item, so an empty map keeps the override dormant.
     */
    private static @NotNull Optional<ItemContext> itemFor(
        @NotNull Map<ArmorTrim.Slot, ItemContext> items, @NotNull ArmorTrim.Slot slot) {
        return Optional.ofNullable(items.get(slot));
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
     * @param item the equipped item identity, for the pack-rule (CIT) texture override; empty leaves the
     *     slot on its equipment-model texture
     * @param engine the texture engine for pack-aware texture resolution
     */
    public static void compositeSlot2D(
        @NotNull PixelBuffer target,
        @NotNull SkinFace part,
        @NotNull ArmorTrim.Slot slot,
        @NotNull ArmorPiece piece,
        int x, int y, int w, int h,
        @NotNull Optional<ItemContext> item,
        @NotNull Textures engine
    ) {
        // The target buffer owns the coverage mask (enabled by the caller when the armor is enchanted);
        // stamp the armor / trim sprite coverage into it so the enchantment foil lands on the armor,
        // not the bare skin. Absent when the caller records no mask - then stampMaskScaled is a no-op.
        PixelMask mask = target.mask().orElse(null);
        boolean useLeggingsLayer = slot == ArmorTrim.Slot.LEGGINGS;
        Optional<PixelBuffer> armorTexture = resolveArmorTexture(engine, piece, useLeggingsLayer ? LayerType.HUMANOID_LEGGINGS : LayerType.HUMANOID, item);
        armorTexture.ifPresent(tex -> {
            PixelBuffer face = part.crop(tex, BlockFace.SOUTH, false);
            target.blitScaled(face, x, y, w, h);
            stampMaskScaled(mask, face, x, y, w, h);
        });

        if (piece.trimColor().isPresent() && piece.trimPattern().isPresent()) {
            String trimLayer = useLeggingsLayer ? "humanoid_leggings" : "humanoid";
            resolveTrimTexture(engine, trimLayer, piece.trimPattern().get(), piece.trimColor().get())
                .ifPresent(trimTex -> {
                    PixelBuffer face = part.crop(trimTex, BlockFace.SOUTH, false);
                    target.blitScaled(face, x, y, w, h);
                    stampMaskScaled(mask, face, x, y, w, h);
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
    private static void stampMaskScaled(@Nullable PixelMask mask, @NotNull PixelBuffer face, int x, int y, int w, int h) {
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
     * @param items the equipped item identity per slot, for the pack-rule (CIT) texture override; empty
     *     leaves each slot on its equipment-model texture
     * @param engine the texture engine for pack-aware texture resolution
     * @return the armor + trim triangles
     */
    public static @NotNull ConcurrentList<VisibleTriangle> buildEntityArmor3D(
        @NotNull Map<String, Vector3f[]> boneBounds,
        @NotNull Optional<ArmorPiece> helmet,
        @NotNull Optional<ArmorPiece> chestplate,
        @NotNull Optional<ArmorPiece> leggings,
        @NotNull Optional<ArmorPiece> boots,
        @NotNull Map<ArmorTrim.Slot, ItemContext> items,
        @NotNull Textures engine
    ) {
        // The armor is textured with the SkinFace skin unwrap, authored for the upright player frame
        // (the player applies a plain R_Y(180) facing). An entity's bone geometry lives in the Y-down
        // model frame and is turned upright by the renderer's ENTITY_FACING = R_Z(180), which also flips
        // Y - so the two frames differ by a 180-degree turn about X (Y and Z negated). Building the armor
        // in the upright player frame (bounds turned about X) and turning the result back into the entity
        // frame lands it correctly once ENTITY_FACING is applied, with the geometry, normals, and inventory
        // shading all resolved in the final frame.
        Map<SkinFace, Vector3f[]> bodyPositions = new EnumMap<>(SkinFace.class);
        for (var entry : boneBounds.entrySet()) {
            SkinFace part = HUMANOID_BONE_MAP.get(entry.getKey());
            if (part != null)
                bodyPositions.put(part, turnAboutXBounds(entry.getValue()));
        }
        ConcurrentList<VisibleTriangle> armor =
            buildHumanoidArmor3D(bodyPositions, helmet, chestplate, leggings, boots, items, engine);

        ConcurrentList<VisibleTriangle> entityArmor = Concurrent.newList();
        for (VisibleTriangle triangle : armor)
            entityArmor.add(turnAboutX(triangle));
        return entityArmor;
    }

    /**
     * Turns a point 180 degrees about the X axis - negating Y and Z. Its own inverse.
     */
    private static @NotNull Vector3f turnAboutX(@NotNull Vector3f point) {
        return new Vector3f(point.x(), -point.y(), -point.z());
    }

    /**
     * Turns a {@code [min, max]} bounding box 180 degrees about the X axis, re-sorting the negated Y and
     * Z extents so the result stays a valid {@code [min, max]} pair.
     */
    private static @NotNull Vector3f @NotNull [] turnAboutXBounds(@NotNull Vector3f @NotNull [] bounds) {
        Vector3f min = bounds[0];
        Vector3f max = bounds[1];
        return new Vector3f[]{
            new Vector3f(min.x(), -max.y(), -max.z()),
            new Vector3f(max.x(), -min.y(), -min.z())
        };
    }

    /**
     * Turns a built armor triangle 180 degrees about the X axis - the counterpart to
     * {@link #turnAboutXBounds} that maps the player-frame armor back into the entity's Y-down model
     * frame. Positions and the stored normal turn together (a pure rotation preserves winding, so
     * culling is unaffected), and the inventory shade is recomputed from the turned normal so lighting
     * resolves in the final frame.
     */
    private static @NotNull VisibleTriangle turnAboutX(@NotNull VisibleTriangle triangle) {
        Vector3f normal = turnAboutX(triangle.normal());
        return new VisibleTriangle(
            turnAboutX(triangle.position0()), turnAboutX(triangle.position1()), turnAboutX(triangle.position2()),
            triangle.uv0(), triangle.uv1(), triangle.uv2(),
            triangle.texture(), triangle.tintArgb(), normal,
            Lighting.inventory(normal), triangle.traits(), triangle.debugTag());
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
        @NotNull Optional<ItemContext> item,
        @NotNull Textures engine
    ) {
        SkinFace[] parts = partsForSlot(slot);
        boolean useLeggingsLayer = slot == ArmorTrim.Slot.LEGGINGS;
        float baseInflate = useLeggingsLayer ? LEGGINGS_INFLATE : ARMOR_INFLATE;

        Optional<PixelBuffer> armorTexture = resolveArmorTexture(engine, piece, useLeggingsLayer ? LayerType.HUMANOID_LEGGINGS : LayerType.HUMANOID, item);
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
     * Resolves the composited armor texture for a slot: the piece's {@link ArmorPiece#material()
     * material} asset composited under the given layer type by {@link EquipmentKit#composite}, dyed
     * by the piece's {@link ArmorPiece#dyeColor() dye}.
     *
     * <p>A present {@code item} first consults the pack-rule (CIT) override
     * ({@link RendererContext#resolveArmorTextureOverride}); a matching
     * rule replaces each layer's texture ({@code layer0} the base, {@code layerN} the overlays) before
     * the equipment-model path. On a vanilla stack with no item the override is {@link CitResult#NONE}
     * and every layer resolves through the model.
     *
     * @param engine the texture engine for pack-aware texture resolution
     * @param piece the armor piece
     * @param layerType the equipment layer the slot maps to ({@link LayerType#HUMANOID} or
     *     {@link LayerType#HUMANOID_LEGGINGS})
     * @param item the equipped item identity, for the pack-rule override; empty leaves the layers on
     *     the equipment model
     * @return the composited texture, or empty when the asset ships no layers or none resolve
     */
    private static @NotNull Optional<PixelBuffer> resolveArmorTexture(
        @NotNull Textures engine,
        @NotNull ArmorPiece piece,
        @NotNull LayerType layerType,
        @NotNull Optional<ItemContext> item
    ) {
        CitResult cit = item
            .map(context -> engine.getContext().resolveArmorTextureOverride(piece.material(), layerType, context))
            .orElse(CitResult.NONE);
        return EquipmentKit.composite(engine, piece.material().assetId(), layerType,
            piece.dyeColor(), cit, OptionalInt.empty());
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
