package lib.minecraft.renderer.engine.kit;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.PixelBuffer;
import dev.simplified.image.pixel.PixelMask;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.equipment.LayerType;
import lib.minecraft.renderer.asset.model.EntityModelData;
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

import java.util.ArrayList;
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
 * The armor texture is a 64x32 atlas whose UV layout matches the top half of the vanilla 64x64
 * player skin - the base layer plus the head's overlay, which the helmet's second box reads -
 * so {@link SkinFace#cropAll(PixelBuffer, boolean) cropAll} and
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
     * The body part and skin layer one armor-mesh bone is textured from.
     *
     * @param face the body part whose unwrap the box is cropped through
     * @param overlay whether the box reads the overlay half of that part's unwrap rather than the base
     */
    private record MeshPart(@NotNull SkinFace face, boolean overlay) {}

    /**
     * One armor box ready to build: the unwrap it is textured through and its corners in the render
     * frame.
     *
     * @param part the body part and skin layer the box is cropped from
     * @param min the box's lower corner
     * @param max the box's upper corner
     */
    private record ArmorBox(@NotNull MeshPart part, @NotNull Vector3f min, @NotNull Vector3f max) {}

    /**
     * The body part each armor-mesh bone is textured from. The helmet's second box - vanilla's
     * {@code hat} part - reads the head's overlay half, the same region the skin renderer draws a
     * player's hat layer from.
     */
    private static final @NotNull Map<String, MeshPart> ARMOR_MESH_PARTS = Map.of(
        "head", new MeshPart(SkinFace.HEAD, false),
        "hat", new MeshPart(SkinFace.HEAD, true),
        "body", new MeshPart(SkinFace.TORSO, false),
        "right_arm", new MeshPart(SkinFace.RIGHT_ARM, false),
        "left_arm", new MeshPart(SkinFace.LEFT_ARM, false),
        "right_leg", new MeshPart(SkinFace.RIGHT_LEG, false),
        "left_leg", new MeshPart(SkinFace.LEFT_LEG, false)
    );

    /**
     * The armor-mesh parts each equipment slot keeps, matching vanilla's own per-slot part sets. The
     * helmet keeps its part <em>and that part's children</em> - which is what puts the {@code hat} box
     * on a helmet - where the other three keep exactly the parts they name.
     */
    private static final @NotNull Map<ArmorTrim.Slot, List<String>> SLOT_PARTS = Map.of(
        ArmorTrim.Slot.HELMET, List.of("head"),
        ArmorTrim.Slot.CHESTPLATE, List.of("body", "right_arm", "left_arm"),
        ArmorTrim.Slot.LEGGINGS, List.of("body", "right_leg", "left_leg"),
        ArmorTrim.Slot.BOOTS, List.of("right_leg", "left_leg")
    );

    /**
     * Guard on the bone parent chain - the armor meshes are two deep, so anything beyond this is a
     * cycle rather than a hierarchy.
     */
    private static final int MAX_BONE_DEPTH = 8;

    /**
     * Trim separation in model units - the model-unit equivalent of {@link #TRIM_INFLATE} in the
     * skin renderer's normalized frame.
     */
    private static final float TRIM_GROW = 0.1f;

    /**
     * The render frame an entity's armor is built into: the same anchor and scales the entity's own
     * geometry was built with, so the armor mesh lands in step with the body it dresses.
     *
     * <p>{@code meshScale} / {@code meshOffset} carry the whole-mesh transform the wearer's own body
     * was built through, so a scaled-up humanoid wears a scaled-up shell - vanilla maps the very same
     * transform over the shared armor set rather than giving those wearers a distinct mesh. That is
     * also why the emitted meshes deliberately do <em>not</em> carry it: read here off the wearer, it
     * would otherwise be applied twice.
     *
     * @param baby whether the wearer renders in its baby form
     * @param armor the armor shell the wearer is dressed in, or empty when it wears none
     * @param meshScale the wearer's whole-mesh uniform scale
     * @param meshOffset the wearer's whole-mesh offset, the anchor its scale is taken about
     * @param modelAnchor the model-space point mapped to the canvas centre
     * @param ndcScale the model-units-to-NDC scale
     * @param modelScale the per-render vertex pre-scale
     */
    public record EntityArmorFrame(
        boolean baby, @NotNull Optional<Entity.HumanoidArmor> armor, float meshScale, @NotNull Vector3f meshOffset,
        @NotNull Vector3f modelAnchor, float ndcScale, float modelScale
    ) {

        /**
         * The frame for a wearer whose own mesh carries no whole-mesh transform.
         */
        public EntityArmorFrame(
            boolean baby, @NotNull Optional<Entity.HumanoidArmor> armor,
            @NotNull Vector3f modelAnchor, float ndcScale, float modelScale) {
            this(baby, armor, 1f, Vector3f.ZERO, modelAnchor, ndcScale, modelScale);
        }

        /**
         * The frame for a wearer, reading the whole-mesh transform off the torso bone the armor is
         * built around - the bone vanilla's own mesh transformer scales and re-anchors.
         *
         * @param baby whether the wearer renders in its baby form
         * @param armor the armor shell the wearer is dressed in
         * @param model the wearer's model
         * @param modelAnchor the model-space point mapped to the canvas centre
         * @param ndcScale the model-units-to-NDC scale
         * @param modelScale the per-render vertex pre-scale
         * @return the armor frame for that wearer
         */
        public static @NotNull EntityArmorFrame of(
            boolean baby, @NotNull Optional<Entity.HumanoidArmor> armor, @NotNull EntityModelData model,
            @NotNull Vector3f modelAnchor, float ndcScale, float modelScale
        ) {
            EntityModelData.Bone torso = model.getBones().get(TORSO_BONE);
            float meshScale = torso == null ? 1f : torso.getScale();
            Vector3f meshOffset = torso == null ? Vector3f.ZERO : torso.getPivot();
            return new EntityArmorFrame(baby, armor, meshScale, meshOffset,
                modelAnchor, ndcScale, modelScale);
        }
    }

    /**
     * The torso bone name the whole-mesh transform is read from.
     */
    private static final @NotNull String TORSO_BONE = "body";

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
            addSkinScaledSlot3D(triangles, bodyPositions, piece, ArmorTrim.Slot.HELMET, itemFor(items, ArmorTrim.Slot.HELMET), engine));
        chestplate.ifPresent(piece ->
            addSkinScaledSlot3D(triangles, bodyPositions, piece, ArmorTrim.Slot.CHESTPLATE, itemFor(items, ArmorTrim.Slot.CHESTPLATE), engine));
        leggings.ifPresent(piece ->
            addSkinScaledSlot3D(triangles, bodyPositions, piece, ArmorTrim.Slot.LEGGINGS, itemFor(items, ArmorTrim.Slot.LEGGINGS), engine));
        boots.ifPresent(piece ->
            addSkinScaledSlot3D(triangles, bodyPositions, piece, ArmorTrim.Slot.BOOTS, itemFor(items, ArmorTrim.Slot.BOOTS), engine));

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
     * <p>An adult wears the armor mesh its renderer names, mapped into the render frame - matching
     * vanilla, which dresses a humanoid in one of a handful of shared armor sets rather than in a shell
     * derived from the wearer's own mesh. A baby still wears its own bone boxes: vanilla's baby armor
     * is a separate mesh with different proportions and an extra waist part, which this does not yet
     * carry.
     *
     * @param frame the render frame the armor is built into
     * @param boneBounds map of bone name to {@code [min, max]}, for the baby form
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
        @NotNull EntityArmorFrame frame,
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
        ConcurrentList<VisibleTriangle> armor;
        if (frame.baby()) {
            Map<SkinFace, Vector3f[]> bodyPositions = new EnumMap<>(SkinFace.class);
            for (var entry : boneBounds.entrySet()) {
                SkinFace part = HUMANOID_BONE_MAP.get(entry.getKey());
                if (part != null)
                    bodyPositions.put(part, turnAboutXBounds(entry.getValue()));
            }
            armor = buildHumanoidArmor3D(bodyPositions, helmet, chestplate, leggings, boots, items, engine);
        } else {
            armor = buildGenericArmor3D(frame, helmet, chestplate, leggings, boots, items, engine);
        }

        Lighting.EntityLighting lighting =
            Lighting.resolveEntity(EntityGeometryKit.DEFAULT_ENTITY_LIGHTING);
        ConcurrentList<VisibleTriangle> entityArmor = Concurrent.newList();
        for (VisibleTriangle triangle : armor)
            entityArmor.add(turnAboutX(triangle, lighting));
        return entityArmor;
    }

    /**
     * Builds the armor for one adult humanoid from the shell it is dressed in: each equipped slot's
     * parts of that mesh, grown by the slot's deformation, mapped into the render frame, then turned
     * into the upright frame the armor unwrap is authored for.
     */
    private static @NotNull ConcurrentList<VisibleTriangle> buildGenericArmor3D(
        @NotNull EntityArmorFrame frame,
        @NotNull Optional<ArmorPiece> helmet,
        @NotNull Optional<ArmorPiece> chestplate,
        @NotNull Optional<ArmorPiece> leggings,
        @NotNull Optional<ArmorPiece> boots,
        @NotNull Map<ArmorTrim.Slot, ItemContext> items,
        @NotNull Textures engine
    ) {
        ConcurrentList<VisibleTriangle> triangles = Concurrent.newList();
        Optional<Entity.HumanoidArmor> armor = frame.armor();
        if (armor.isEmpty()) return triangles;
        // The trim rides the same frame as the armor it sits on, so its separation scales with the
        // render rather than staying a constant that vanishes at small scales.
        float trimInflate = TRIM_GROW * frame.ndcScale() * frame.modelScale();

        Map<ArmorTrim.Slot, Optional<ArmorPiece>> equipped = new EnumMap<>(ArmorTrim.Slot.class);
        equipped.put(ArmorTrim.Slot.HELMET, helmet);
        equipped.put(ArmorTrim.Slot.CHESTPLATE, chestplate);
        equipped.put(ArmorTrim.Slot.LEGGINGS, leggings);
        equipped.put(ArmorTrim.Slot.BOOTS, boots);

        for (var entry : equipped.entrySet()) {
            ArmorTrim.Slot slot = entry.getKey();
            entry.getValue().ifPresent(piece -> addMeshSlot3D(triangles,
                armorBoxes(frame, armor.get(), slot), piece, slot, itemFor(items, slot), engine, trimInflate));
        }
        return triangles;
    }

    /**
     * The armor mesh's boxes covering one slot, mapped through the render frame and turned into the
     * upright frame the armor unwrap is authored for.
     *
     * <p>The slot picks which of the shell's two deformations it wears - the leggings the inner one, the
     * other three the outer - then keeps the parts vanilla keeps for that slot. Each cube is grown by
     * that deformation plus its own {@code CubeDeformation.extend} (a leg's {@code -0.1}, a helmet's
     * second box), which is the sum vanilla's mesh builder performs in the same order. Bone order is the
     * mesh's own, so a part and the overlay box parented to it stay adjacent.
     *
     * <p>A part collapses to one axis-aligned box because the armor meshes are plain box tables - no bone
     * carries a rotation or a scale. That is a property of those meshes, not an assumption about entity
     * geometry in general.
     */
    private static @NotNull List<ArmorBox> armorBoxes(
        @NotNull EntityArmorFrame frame, @NotNull Entity.HumanoidArmor armor, @NotNull ArmorTrim.Slot slot) {
        EntityModelData tree = armor.mesh();
        Vector3f deformation = slot == ArmorTrim.Slot.LEGGINGS ? armor.innerGrow() : armor.outerGrow();
        List<ArmorBox> boxes = new ArrayList<>();
        for (Map.Entry<String, EntityModelData.Bone> entry : tree.getBones().entrySet()) {
            MeshPart part = ARMOR_MESH_PARTS.get(entry.getKey());
            if (part == null || !coveredBySlot(tree, slot, entry.getKey())) continue;
            Vector3f anchor = chainedPivot(tree, entry.getValue());
            for (EntityModelData.Cube cube : entry.getValue().getCubes()) {
                Vector3f grow = deformation.add(cube.getGrow());
                Vector3f min = anchor.add(cube.getOrigin()).subtract(grow);
                Vector3f max = min.add(cube.getSize()).add(grow).add(grow);
                Vector3f[] corners = turnAboutXBounds(new Vector3f[]{
                    toRenderFrame(frame, min.x(), min.y(), min.z()),
                    toRenderFrame(frame, max.x(), max.y(), max.z())
                });
                boxes.add(new ArmorBox(part, corners[0], corners[1]));
            }
        }
        return boxes;
    }

    /**
     * Whether a slot's armor covers this bone. Vanilla keeps the helmet's part <em>and its
     * children</em>, and exactly the named parts for the other three slots - so the head's overlay box
     * rides a helmet and nothing else.
     */
    private static boolean coveredBySlot(
        @NotNull EntityModelData tree, @NotNull ArmorTrim.Slot slot, @NotNull String bone) {
        List<String> parts = SLOT_PARTS.get(slot);
        if (parts.contains(bone)) return true;
        if (slot != ArmorTrim.Slot.HELMET) return false;
        String cursor = bone;
        for (int depth = 0; depth < MAX_BONE_DEPTH; depth++) {
            EntityModelData.Bone node = tree.getBones().get(cursor);
            if (node == null || node.getParent() == null) return false;
            cursor = node.getParent();
            if (parts.contains(cursor)) return true;
        }
        return false;
    }

    /**
     * A bone's anchor in mesh space - its own pivot plus every ancestor's, since a bone pivot is
     * parent-relative.
     */
    private static @NotNull Vector3f chainedPivot(
        @NotNull EntityModelData tree, @NotNull EntityModelData.Bone bone) {
        Vector3f anchor = bone.getPivot();
        EntityModelData.Bone cursor = bone;
        for (int depth = 0; depth < MAX_BONE_DEPTH && cursor.getParent() != null; depth++) {
            cursor = tree.getBones().get(cursor.getParent());
            if (cursor == null) break;
            anchor = anchor.add(cursor.getPivot());
        }
        return anchor;
    }

    /**
     * Maps a point from model units into the render frame, applying the same pre-scale, anchor and
     * NDC scale the entity's own geometry was built through.
     */
    private static @NotNull Vector3f toRenderFrame(@NotNull EntityArmorFrame frame, float x, float y, float z) {
        // The wearer's whole-mesh transform first, so the shell is sized and seated like the body it
        // dresses, then the render frame the body's own geometry was built through.
        Vector3f offset = frame.meshOffset();
        float mesh = frame.meshScale();
        float mx = mesh * x + offset.x();
        float my = mesh * y + offset.y();
        float mz = mesh * z + offset.z();

        Vector3f anchor = frame.modelAnchor();
        float scale = frame.modelScale();
        float ndc = frame.ndcScale();
        return new Vector3f(
            ndc * (scale * mx - anchor.x()),
            ndc * (scale * my - anchor.y()),
            ndc * (scale * mz - anchor.z()));
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
     * culling is unaffected), and the shade is recomputed from the turned normal so lighting resolves
     * in the final frame.
     *
     * <p>The shade comes from the entity lighting basis, not the block / item inventory one: worn armor
     * is part of the entity render and vanilla lights it with the same two-directional shader as the
     * body it dresses. The shading normal is Y-flipped to match the kit's tuned light frame, exactly as
     * the body's own faces are.
     */
    private static @NotNull VisibleTriangle turnAboutX(
        @NotNull VisibleTriangle triangle, @NotNull Lighting.EntityLighting lighting) {
        Vector3f normal = turnAboutX(triangle.normal());
        Vector3f shadingNormal = new Vector3f(normal.x(), -normal.y(), normal.z());
        return new VisibleTriangle(
            turnAboutX(triangle.position0()), turnAboutX(triangle.position1()), turnAboutX(triangle.position2()),
            triangle.uv0(), triangle.uv1(), triangle.uv2(),
            triangle.texture(), triangle.tintArgb(), normal,
            lighting.shade(shadingNormal, triangle.traits().cullBackFaces()),
            triangle.traits(), triangle.debugTag());
    }

    // ---------------------------------------------------------------------------------------
    // Shared internals.
    // ---------------------------------------------------------------------------------------

    /**
     * The {@link SkinFace} body parts an armor slot covers - {@link #SLOT_PARTS the same per-slot
     * table} the mesh path prunes by, read through the body part each part name is textured from.
     */
    private static final @NotNull Map<ArmorTrim.Slot, SkinFace[]> SLOT_FACES =
        new EnumMap<>(ArmorTrim.Slot.class);

    static {
        for (Map.Entry<ArmorTrim.Slot, List<String>> entry : SLOT_PARTS.entrySet())
            SLOT_FACES.put(entry.getKey(), entry.getValue().stream()
                .map(name -> ARMOR_MESH_PARTS.get(name).face())
                .distinct()
                .toArray(SkinFace[]::new));
    }

    /**
     * Maps an armor slot to the {@link SkinFace} body parts it covers.
     *
     * @param slot the armor slot
     * @return the body parts that slot's armor covers
     */
    public static @NotNull SkinFace @NotNull [] partsForSlot(@NotNull ArmorTrim.Slot slot) {
        return SLOT_FACES.get(slot).clone();
    }

    /**
     * Adds one slot's armor around bounds carried in the skin renderer's normalized frame, inflating
     * by the constants calibrated for it.
     */
    private static void addSkinScaledSlot3D(
        @NotNull ConcurrentList<VisibleTriangle> triangles,
        @NotNull Map<SkinFace, Vector3f[]> bodyPositions,
        @NotNull ArmorPiece piece,
        @NotNull ArmorTrim.Slot slot,
        @NotNull Optional<ItemContext> item,
        @NotNull Textures engine
    ) {
        float baseInflate = slot == ArmorTrim.Slot.LEGGINGS ? LEGGINGS_INFLATE : ARMOR_INFLATE;
        addSlot3D(triangles, bodyPositions, piece, slot, item, engine, baseInflate, baseInflate + TRIM_INFLATE);
    }

    private static void addSlot3D(
        @NotNull ConcurrentList<VisibleTriangle> triangles,
        @NotNull Map<SkinFace, Vector3f[]> bodyPositions,
        @NotNull ArmorPiece piece,
        @NotNull ArmorTrim.Slot slot,
        @NotNull Optional<ItemContext> item,
        @NotNull Textures engine,
        float baseInflate,
        float trimInflate
    ) {
        SkinFace[] parts = partsForSlot(slot);
        boolean useLeggingsLayer = slot == ArmorTrim.Slot.LEGGINGS;

        Optional<PixelBuffer> armorTexture = resolveArmorTexture(engine, piece, useLeggingsLayer ? LayerType.HUMANOID_LEGGINGS : LayerType.HUMANOID, item);
        if (armorTexture.isEmpty()) return;

        for (SkinFace part : parts) {
            Vector3f[] bounds = bodyPositions.get(part);
            if (bounds == null) continue;
            triangles.addAll(buildPart3D(part, false, bounds[0], bounds[1], armorTexture.get(), baseInflate));
        }

        if (piece.trimColor().isPresent() && piece.trimPattern().isPresent()) {
            String trimLayer = useLeggingsLayer ? "humanoid_leggings" : "humanoid";
            Optional<PixelBuffer> trimTexture = resolveTrimTexture(
                engine, trimLayer, piece.trimPattern().get(), piece.trimColor().get());
            if (trimTexture.isPresent()) {
                for (SkinFace part : parts) {
                    Vector3f[] bounds = bodyPositions.get(part);
                    if (bounds == null) continue;
                    triangles.addAll(buildPart3D(part, false, bounds[0], bounds[1],
                        trimTexture.get(), trimInflate));
                }
            }
        }
    }

    /**
     * Adds one slot's armor around boxes already grown and mapped into the render frame, so nothing
     * beyond the trim's own separation is inflated here.
     */
    private static void addMeshSlot3D(
        @NotNull ConcurrentList<VisibleTriangle> triangles,
        @NotNull List<ArmorBox> boxes,
        @NotNull ArmorPiece piece,
        @NotNull ArmorTrim.Slot slot,
        @NotNull Optional<ItemContext> item,
        @NotNull Textures engine,
        float trimInflate
    ) {
        boolean useLeggingsLayer = slot == ArmorTrim.Slot.LEGGINGS;
        Optional<PixelBuffer> armorTexture = resolveArmorTexture(engine, piece,
            useLeggingsLayer ? LayerType.HUMANOID_LEGGINGS : LayerType.HUMANOID, item);
        if (armorTexture.isEmpty()) return;

        for (ArmorBox box : boxes)
            triangles.addAll(buildPart3D(box.part().face(), box.part().overlay(),
                box.min(), box.max(), armorTexture.get(), 0f));

        if (piece.trimColor().isPresent() && piece.trimPattern().isPresent()) {
            String trimLayer = useLeggingsLayer ? "humanoid_leggings" : "humanoid";
            resolveTrimTexture(engine, trimLayer, piece.trimPattern().get(), piece.trimColor().get())
                .ifPresent(trimTexture -> {
                    for (ArmorBox box : boxes)
                        triangles.addAll(buildPart3D(box.part().face(), box.part().overlay(),
                            box.min(), box.max(), trimTexture, trimInflate));
                });
        }
    }

    private static @NotNull ConcurrentList<VisibleTriangle> buildPart3D(
        @NotNull SkinFace part,
        boolean overlay,
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
            inflatedMin, inflatedMax, part.cropAll(texture, overlay), ColorMath.WHITE, true);
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
