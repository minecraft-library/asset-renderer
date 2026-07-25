package lib.minecraft.renderer.engine.kit;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.PixelBuffer;
import dev.simplified.image.pixel.PixelMask;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.equipment.ArmorForm;
import lib.minecraft.renderer.asset.equipment.LayerType;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.asset.pack.rule.CitResult;
import lib.minecraft.renderer.asset.pack.rule.ItemContext;
import lib.minecraft.renderer.engine.RendererContext;
import lib.minecraft.renderer.engine.RendererDebug;
import lib.minecraft.renderer.engine.light.Lighting;
import lib.minecraft.renderer.engine.raster.VisibleTriangle;
import lib.minecraft.renderer.engine.texture.Textures;
import lib.minecraft.renderer.face.BlockFace;
import lib.minecraft.renderer.face.CubeUnwrap;
import lib.minecraft.renderer.face.EntityFace;
import lib.minecraft.renderer.face.SixFaces;
import lib.minecraft.renderer.face.SkinFace;
import lib.minecraft.renderer.option.spec.ArmorPiece;
import lib.minecraft.renderer.option.spec.ArmorTrim;
import lib.minecraft.renderer.tensor.Box;
import lib.minecraft.renderer.tensor.Matrix4f;
import lib.minecraft.renderer.tensor.Vector3f;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
     * One armor box ready to build: the shell bone and cube it comes from, and its corners in the
     * render frame. The cube travels rather than a resolved crop because a slot's boxes are textured
     * twice - once from the armor sheet and once from the trim - and each crop is a read of the cube's
     * own unwrap against a different sheet.
     *
     * @param bone the shell bone the cube belongs to, for the per-pixel trace
     * @param cube the shell cube the box was built from
     * @param min the box's lower corner
     * @param max the box's upper corner
     */
    private record ArmorBox(
        @NotNull String bone,
        @NotNull EntityModelData.Cube cube,
        @NotNull Vector3f min,
        @NotNull Vector3f max
    ) {}

    /**
     * The body part each armor-mesh bone of the <em>player's</em> shell is textured from - the skin
     * path, which dresses the player's own normalized body boxes rather than a mesh. The helmet's
     * second box reads the head's overlay half, the same region the skin renderer draws a player's hat
     * layer from.
     */
    private static final @NotNull Map<String, SkinFace> SKIN_PARTS = Map.of(
        "head", SkinFace.HEAD,
        "body", SkinFace.TORSO,
        "right_arm", SkinFace.RIGHT_ARM,
        "left_arm", SkinFace.LEFT_ARM,
        "right_leg", SkinFace.RIGHT_LEG,
        "left_leg", SkinFace.LEFT_LEG
    );

    /**
     * The model-frame face each render-frame face of a shell box reads its texture through. A shell's
     * unwrap is authored in vanilla's Y-down model frame while the boxes here are built upright, and
     * the two frames differ by exactly {@link #turnAboutX}'s half turn - which swaps up with down and
     * north with south and leaves the two sides where they are.
     */
    private static final @NotNull Map<BlockFace, EntityFace> MESH_FACES = Map.of(
        BlockFace.DOWN, EntityFace.UP,
        BlockFace.UP, EntityFace.DOWN,
        BlockFace.NORTH, EntityFace.SOUTH,
        BlockFace.SOUTH, EntityFace.NORTH,
        BlockFace.WEST, EntityFace.WEST,
        BlockFace.EAST, EntityFace.EAST
    );

    /**
     * Guard on the bone parent chain - the armor meshes are two deep, so anything beyond this is a
     * cycle rather than a hierarchy.
     */
    private static final int MAX_BONE_DEPTH = 8;

    /**
     * The render frame an entity's armor is built into: the same anchor and scales the entity's own
     * geometry was built with, so the armor mesh lands in step with the body it dresses.
     *
     * <p>The whole-mesh transform a scaled-up humanoid's shell is sized and seated by is not here -
     * it belongs to the armor set, and {@link Entity.HumanoidArmor#meshScale()} carries it. Vanilla
     * maps the very same transformer over the shared set rather than giving those wearers a distinct
     * mesh, so the factor is a property of what is worn and not of who wears it. Reading it back off
     * the wearer's torso bone is what this record used to do; that only ever worked because those
     * three wearers' bodies happen to be built through the same transformer, and it was silently
     * wrong for a baby, whose own body pivot is not one.
     *
     * @param armor the armor shell the wearer is dressed in, or empty when it wears none
     * @param modelAnchor the model-space point mapped to the canvas centre
     * @param ndcScale the model-units-to-NDC scale
     * @param modelScale the per-render vertex pre-scale
     */
    public record EntityArmorFrame(
        @NotNull Optional<Entity.HumanoidArmor> armor,
        @NotNull Vector3f modelAnchor, float ndcScale, float modelScale
    ) {}

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
     * Builds armor triangles for an entity from the shell it is dressed in, mapped into the render
     * frame - matching vanilla, which dresses a humanoid in one of a handful of shared armor sets
     * rather than in a shell derived from the wearer's own mesh. A baby is dressed in its own shell
     * the same way; the age fold picked which one before this was reached, so nothing here branches on
     * it.
     *
     * @param frame the render frame the armor is built into
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
        @NotNull Optional<ArmorPiece> helmet,
        @NotNull Optional<ArmorPiece> chestplate,
        @NotNull Optional<ArmorPiece> leggings,
        @NotNull Optional<ArmorPiece> boots,
        @NotNull Map<ArmorTrim.Slot, ItemContext> items,
        @NotNull Textures engine
    ) {
        // The armor sheets are authored for the upright player frame (the player applies a plain
        // R_Y(180) facing). An entity's bone geometry lives in the Y-down model frame and is turned
        // upright by the renderer's ENTITY_FACING = R_Z(180), which also flips Y - so the two frames
        // differ by a 180-degree turn about X (Y and Z negated). Building the armor in the upright
        // player frame (bounds turned about X) and turning the result back into the entity frame lands
        // it correctly once ENTITY_FACING is applied, with the geometry, normals, and inventory shading
        // all resolved in the final frame.
        ConcurrentList<VisibleTriangle> armor =
            buildGenericArmor3D(frame, helmet, chestplate, leggings, boots, items, engine);

        Lighting.EntityLighting lighting =
            Lighting.resolveEntity(EntityGeometryKit.DEFAULT_ENTITY_LIGHTING);
        ConcurrentList<VisibleTriangle> entityArmor = Concurrent.newList();
        for (VisibleTriangle triangle : armor)
            entityArmor.add(turnAboutX(triangle, lighting));
        return entityArmor;
    }

    /**
     * The alpha-tight screen bounds of the armor a wearer would draw, for the canvas fit.
     *
     * <p>Worn armor is a shell <em>around</em> the wearer rather than a decal on it - an inflated
     * helmet, a boot flare, and above all a baby's hooded shroud all stand clear of the body. The
     * orthographic fit sizes from measured bounds rather than from the rendered triangles, so a shell
     * nobody measured crops at the canvas edge.
     *
     * <p>Measured per equipped slot through that slot's own composited sheet, the same alpha-tight
     * walk an equipment overlay gets, over the very parts and deformations
     * {@link #buildEntityArmor3D} will draw. Empty when nothing is equipped, so an unarmored render
     * is untouched.
     *
     * @param armor the shell the wearer is dressed in
     * @param helmet equipped helmet, or empty
     * @param chestplate equipped chestplate, or empty
     * @param leggings equipped leggings, or empty
     * @param boots equipped boots, or empty
     * @param items the equipped item identity per slot, for the pack-rule (CIT) texture override
     * @param screenTransform the model-to-screen transform the bounds are accumulated through
     * @param modelScale the per-render vertex pre-scale
     * @param engine the texture engine for pack-aware texture resolution
     * @return the union of the equipped slots' screen bounds, or empty when none contributes
     */
    public static @NotNull Optional<Box> screenBounds(
        @NotNull Entity.HumanoidArmor armor,
        @NotNull Optional<ArmorPiece> helmet,
        @NotNull Optional<ArmorPiece> chestplate,
        @NotNull Optional<ArmorPiece> leggings,
        @NotNull Optional<ArmorPiece> boots,
        @NotNull Map<ArmorTrim.Slot, ItemContext> items,
        @NotNull Matrix4f screenTransform,
        float modelScale,
        @NotNull Textures engine
    ) {
        Map<ArmorTrim.Slot, Optional<ArmorPiece>> equipped = new EnumMap<>(ArmorTrim.Slot.class);
        equipped.put(ArmorTrim.Slot.HELMET, helmet);
        equipped.put(ArmorTrim.Slot.CHESTPLATE, chestplate);
        equipped.put(ArmorTrim.Slot.LEGGINGS, leggings);
        equipped.put(ArmorTrim.Slot.BOOTS, boots);

        Box union = null;
        for (Map.Entry<ArmorTrim.Slot, Optional<ArmorPiece>> entry : equipped.entrySet()) {
            ArmorTrim.Slot slot = entry.getKey();
            if (entry.getValue().isEmpty()) continue;
            Optional<PixelBuffer> sheet = resolveArmorTexture(engine, entry.getValue().get(),
                armor.form().layerType(slot), itemFor(items, slot));
            if (sheet.isEmpty()) continue;
            Box slotBounds = EntityGeometryKit.computeScreenBounds(slotMesh(armor, slot), screenTransform,
                modelScale * armor.meshScale(), sheet.get());
            union = union == null ? slotBounds : new Box(
                Math.min(union.minX(), slotBounds.minX()),
                Math.min(union.minY(), slotBounds.minY()),
                Math.min(union.minZ(), slotBounds.minZ()),
                Math.max(union.maxX(), slotBounds.maxX()),
                Math.max(union.maxY(), slotBounds.maxY()),
                Math.max(union.maxZ(), slotBounds.maxZ()));
        }
        return Optional.ofNullable(union);
    }

    /**
     * The shell as one slot wears it: every bone the shell has, keeping cubes only on the parts that
     * slot covers and growing each by the slot's deformation.
     *
     * <p>The bones a slot does not cover stay in the tree with their cubes dropped, which is what
     * vanilla's own per-slot prune does and what keeps a kept bone's ancestor chain resolvable - a
     * baby's boots keep the feet parented under legs the boots themselves do not draw. The whole-mesh
     * seat rides the root pivots pre-scale, so the caller's scale carries it.
     */
    private static @NotNull EntityModelData slotMesh(
        @NotNull Entity.HumanoidArmor armor, @NotNull ArmorTrim.Slot slot) {
        EntityModelData tree = armor.mesh();
        Vector3f deformation = slot == ArmorTrim.Slot.LEGGINGS ? armor.innerGrow() : armor.outerGrow();
        Vector3f seat = armor.meshOffset().multiply(1f / armor.meshScale());
        LinkedHashMap<String, EntityModelData.Bone> bones = new LinkedHashMap<>();
        for (Map.Entry<String, EntityModelData.Bone> entry : tree.getBones().entrySet()) {
            EntityModelData.Bone bone = entry.getValue();
            ConcurrentList<EntityModelData.Cube> cubes = Concurrent.newList();
            if (coveredBySlot(tree, armor.form(), slot, entry.getKey()))
                for (EntityModelData.Cube cube : bone.getCubes())
                    cubes.add(new EntityModelData.Cube(cube.getOrigin(), cube.getSize(), cube.getUv(),
                        deformation.add(cube.getGrow()), cube.isMirror(), cube.getPivot(), cube.getRotation(),
                        cube.getFaceUv()));
            Vector3f pivot = bone.getParent() == null ? bone.getPivot().add(seat) : bone.getPivot();
            bones.put(entry.getKey(), new EntityModelData.Bone(pivot, bone.getRotation(),
                bone.getBindPoseRotation(), bone.getScale(), cubes, bone.getParent()));
        }
        return new EntityModelData(tree.getTextureSize(), tree.getInventoryYRotation(),
            Concurrent.adoptLinkedMap(bones), tree.isCull());
    }

    /**
     * Builds the armor for one humanoid from the shell it is dressed in: each equipped slot's parts of
     * that mesh, grown by the slot's deformation, mapped into the render frame, then turned into the
     * upright frame the armor unwrap is authored for.
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

        Map<ArmorTrim.Slot, Optional<ArmorPiece>> equipped = new EnumMap<>(ArmorTrim.Slot.class);
        equipped.put(ArmorTrim.Slot.HELMET, helmet);
        equipped.put(ArmorTrim.Slot.CHESTPLATE, chestplate);
        equipped.put(ArmorTrim.Slot.LEGGINGS, leggings);
        equipped.put(ArmorTrim.Slot.BOOTS, boots);

        for (var entry : equipped.entrySet()) {
            ArmorTrim.Slot slot = entry.getKey();
            entry.getValue().ifPresent(piece -> addMeshSlot3D(triangles,
                armorBoxes(frame, armor.get(), slot), armor.get().form(), piece, slot,
                itemFor(items, slot), engine));
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
            if (!coveredBySlot(tree, armor.form(), slot, entry.getKey())) continue;
            Vector3f anchor = chainedPivot(tree, entry.getValue());
            for (EntityModelData.Cube cube : entry.getValue().getCubes()) {
                Vector3f grow = deformation.add(cube.getGrow());
                Vector3f min = anchor.add(cube.getOrigin()).subtract(grow);
                Vector3f max = min.add(cube.getSize()).add(grow).add(grow);
                Vector3f[] corners = turnAboutXBounds(new Vector3f[]{
                    toRenderFrame(frame, armor, min.x(), min.y(), min.z()),
                    toRenderFrame(frame, armor, max.x(), max.y(), max.z())
                });
                boxes.add(new ArmorBox(entry.getKey(), cube, corners[0], corners[1]));
            }
        }
        return boxes;
    }

    /**
     * Whether a slot's armor covers this bone. Vanilla keeps the helmet's part <em>and its
     * children</em>, and exactly the named parts for the other three slots - so the head's overlay box
     * rides a helmet and nothing else, and a baby's boots reach the feet parented under its legs
     * without dragging the legs along.
     */
    private static boolean coveredBySlot(
        @NotNull EntityModelData tree, @NotNull ArmorForm form, @NotNull ArmorTrim.Slot slot, @NotNull String bone) {
        List<String> parts = form.parts(slot);
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
    private static @NotNull Vector3f toRenderFrame(
        @NotNull EntityArmorFrame frame, @NotNull Entity.HumanoidArmor armor, float x, float y, float z) {
        // The set's own whole-mesh transform first, so the shell is sized and seated like the body it
        // dresses, then the render frame the body's own geometry was built through.
        Vector3f offset = armor.meshOffset();
        float mesh = armor.meshScale();
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
     * the body's own faces are. Armor boxes are built two-sided, so the shade resolves through the
     * per-face form - a face the camera sees from behind is lit by its camera-facing orientation, and
     * one seen from the front is lit by its own normal exactly as a culling cube would be.
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
     * The {@link SkinFace} body parts an armor slot covers - the adult shell's own per-slot part
     * table, read through the body part each part name is textured from.
     */
    private static final @NotNull Map<ArmorTrim.Slot, SkinFace[]> SLOT_FACES =
        new EnumMap<>(ArmorTrim.Slot.class);

    static {
        for (ArmorTrim.Slot slot : ArmorTrim.Slot.values())
            SLOT_FACES.put(slot, ArmorForm.ADULT.parts(slot).stream()
                .map(SKIN_PARTS::get)
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
     * by the constant calibrated for it.
     */
    private static void addSkinScaledSlot3D(
        @NotNull ConcurrentList<VisibleTriangle> triangles,
        @NotNull Map<SkinFace, Vector3f[]> bodyPositions,
        @NotNull ArmorPiece piece,
        @NotNull ArmorTrim.Slot slot,
        @NotNull Optional<ItemContext> item,
        @NotNull Textures engine
    ) {
        addSlot3D(triangles, bodyPositions, piece, slot, item, engine,
            slot == ArmorTrim.Slot.LEGGINGS ? LEGGINGS_INFLATE : ARMOR_INFLATE);
    }

    private static void addSlot3D(
        @NotNull ConcurrentList<VisibleTriangle> triangles,
        @NotNull Map<SkinFace, Vector3f[]> bodyPositions,
        @NotNull ArmorPiece piece,
        @NotNull ArmorTrim.Slot slot,
        @NotNull Optional<ItemContext> item,
        @NotNull Textures engine,
        float inflate
    ) {
        SkinFace[] parts = partsForSlot(slot);
        boolean useLeggingsLayer = slot == ArmorTrim.Slot.LEGGINGS;

        Optional<PixelBuffer> armorTexture = resolveArmorTexture(engine, piece, useLeggingsLayer ? LayerType.HUMANOID_LEGGINGS : LayerType.HUMANOID, item);
        if (armorTexture.isEmpty()) return;

        for (SkinFace part : parts) {
            Vector3f[] bounds = bodyPositions.get(part);
            if (bounds == null) continue;
            triangles.addAll(buildSkinBox3D(part, bounds[0], bounds[1], armorTexture.get(), inflate));
        }

        if (piece.trimColor().isPresent() && piece.trimPattern().isPresent()) {
            String trimLayer = useLeggingsLayer ? "humanoid_leggings" : "humanoid";
            Optional<PixelBuffer> trimTexture = resolveTrimTexture(
                engine, trimLayer, piece.trimPattern().get(), piece.trimColor().get());
            if (trimTexture.isPresent()) {
                for (SkinFace part : parts) {
                    Vector3f[] bounds = bodyPositions.get(part);
                    if (bounds == null) continue;
                    triangles.addAll(buildSkinBox3D(part, bounds[0], bounds[1],
                        trimTexture.get(), inflate));
                }
            }
        }
    }

    /**
     * Adds one slot's armor around boxes already grown and mapped into the render frame, so nothing
     * is inflated here. The shell's form picks the equipment layer the sheet is composited from and
     * whether a trim is drawn at all - a baby's four slots all read the baby sheet, and vanilla draws
     * no trim over one.
     */
    private static void addMeshSlot3D(
        @NotNull ConcurrentList<VisibleTriangle> triangles,
        @NotNull List<ArmorBox> boxes,
        @NotNull ArmorForm form,
        @NotNull ArmorPiece piece,
        @NotNull ArmorTrim.Slot slot,
        @NotNull Optional<ItemContext> item,
        @NotNull Textures engine
    ) {
        Optional<PixelBuffer> armorTexture = resolveArmorTexture(engine, piece, form.layerType(slot), item);
        if (armorTexture.isEmpty()) return;

        for (ArmorBox box : boxes)
            triangles.addAll(buildMeshBox3D(box, armorTexture.get()));

        if (piece.trimColor().isEmpty() || piece.trimPattern().isEmpty()) return;
        form.trimLayer(slot)
            .flatMap(layer -> resolveTrimTexture(engine, layer, piece.trimPattern().get(), piece.trimColor().get()))
            .ifPresent(trimTexture -> {
                for (ArmorBox box : boxes)
                    triangles.addAll(buildMeshBox3D(box, trimTexture));
            });
    }

    /**
     * Builds one box of the player's own armor, around bounds carried in the skin renderer's
     * normalized frame and read through that body part's fixed skin rectangles.
     */
    private static @NotNull ConcurrentList<VisibleTriangle> buildSkinBox3D(
        @NotNull SkinFace part,
        @NotNull Vector3f min,
        @NotNull Vector3f max,
        @NotNull PixelBuffer texture,
        float inflate
    ) {
        Vector3f inflatedMin = new Vector3f(min.x() - inflate, min.y() - inflate, min.z() - inflate);
        Vector3f inflatedMax = new Vector3f(max.x() + inflate, max.y() + inflate, max.z() + inflate);
        ConcurrentList<VisibleTriangle> box = BlockGeometryKit.buildArmorBoxTriangles(
            inflatedMin, inflatedMax, part.cropAll(texture, false), ColorMath.WHITE);
        return RendererDebug.tracingPixels()
            ? tagged(box, part.name().toLowerCase(Locale.ROOT))
            : box;
    }

    /**
     * Builds one box of a shell, read through the cube's own unwrap against the sheet.
     *
     * <p>The unwrap is the cube's rather than a table's because a shell states where each of its boxes
     * sits on its sheet, and the two shells state different things - the baby's head is a nine-wide box
     * at the sheet's origin where the adult's is eight-wide, and its feet and waist have no counterpart
     * in the skin layout at all. On the adult shell the two agree box for box, the helmet's second box
     * included: that shell IS the skin unwrap, mirrored left limbs and all.
     */
    private static @NotNull ConcurrentList<VisibleTriangle> buildMeshBox3D(
        @NotNull ArmorBox box, @NotNull PixelBuffer texture) {
        ConcurrentList<VisibleTriangle> triangles = BlockGeometryKit.buildArmorBoxTriangles(
            box.min(), box.max(), cropCube(texture, box.cube()), ColorMath.WHITE);
        return RendererDebug.tracingPixels() ? tagged(triangles, box.bone()) : triangles;
    }

    /**
     * Crops one shell cube's six faces out of a sheet, turning each render-frame face into the
     * model-frame one the shell's unwrap addresses.
     */
    private static @NotNull SixFaces cropCube(
        @NotNull PixelBuffer sheet, @NotNull EntityModelData.Cube cube) {
        return new SixFaces(
            cropFace(sheet, cube, BlockFace.DOWN),
            cropFace(sheet, cube, BlockFace.UP),
            cropFace(sheet, cube, BlockFace.NORTH),
            cropFace(sheet, cube, BlockFace.SOUTH),
            cropFace(sheet, cube, BlockFace.WEST),
            cropFace(sheet, cube, BlockFace.EAST));
    }

    /** One render-frame face of a shell cube, cropped through the model-frame face it pairs with. */
    private static @NotNull PixelBuffer cropFace(
        @NotNull PixelBuffer sheet, @NotNull EntityModelData.Cube cube, @NotNull BlockFace face) {
        return CubeUnwrap.crop(sheet, cube.getUv(), cube.getSize(), cube.isMirror(), MESH_FACES.get(face));
    }

    /**
     * Names each triangle of one armor box for the per-pixel trace, so a dump reads
     * {@code right_leg:north} rather than {@code null} where two shell faces contest a pixel. The
     * shared box builder emits two triangles per face in {@link BlockFace#CACHED_VALUES} order,
     * which is what lets the face be recovered from the position.
     *
     * @param box the box's twelve triangles as the builder emitted them
     * @param name the part the box belongs to
     * @return the same triangles, each carrying a {@code part:face} tag
     */
    private static @NotNull ConcurrentList<VisibleTriangle> tagged(
        @NotNull ConcurrentList<VisibleTriangle> box, @NotNull String name) {
        ConcurrentList<VisibleTriangle> out = Concurrent.newList();
        for (int index = 0; index < box.size(); index++)
            out.add(box.get(index).withDebugTag(name + ":" + BlockFace.CACHED_VALUES[index / 2].direction()));
        return out;
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
