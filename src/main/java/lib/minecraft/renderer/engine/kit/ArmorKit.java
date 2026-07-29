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
import lib.minecraft.renderer.engine.raster.SurfaceTraits;
import lib.minecraft.renderer.engine.raster.VisibleTriangle;
import lib.minecraft.renderer.engine.texture.Textures;
import lib.minecraft.renderer.face.Face;
import lib.minecraft.renderer.face.HumanoidPart;
import lib.minecraft.renderer.face.Turn;
import lib.minecraft.renderer.face.Unwrap;
import lib.minecraft.renderer.option.spec.ArmorPiece;
import lib.minecraft.renderer.option.spec.ArmorSlot;
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
 * so {@link HumanoidPart#textures(PixelBuffer, boolean) textures} and
 * {@link HumanoidPart#crop(PixelBuffer, Face, boolean) crop} work directly on the armor
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
     * The frame a shell's unwrap is authored in, relative to the upright frame its boxes are built in.
     * A shell states its strips in vanilla's Y-down model frame, so a render-frame face reads its
     * texture through the face this turn maps it onto - up with down and north with south swapped,
     * the two sides left where they are.
     */
    private static final @NotNull Turn MODEL_FRAME = Turn.HALF_X;

    // ---------------------------------------------------------------------------------------
    // 3D armor (triangles for ModelEngine rasterization).
    // ---------------------------------------------------------------------------------------

    /**
     * The order the <em>player's own</em> armor is emitted in, which is deliberately not
     * {@link ArmorSlot}'s declared composite order.
     *
     * <p>Every other consumer - the mesh path and the 2D compositor - walks the declared order, which
     * paints the layer-2 leggings first so the layer-1 pieces composite over them. This path emits the
     * helmet first and the leggings third. It is unobservable today because the two inflations separate
     * every shared bone, so no equal-depth contest arises between them; the divergence is spelled out
     * here rather than left implicit in the emission sequence.
     */
    private static final @NotNull ArmorSlot @NotNull [] SKIN_SLOT_ORDER = {
        ArmorSlot.HELMET, ArmorSlot.CHESTPLATE, ArmorSlot.LEGGINGS, ArmorSlot.BOOTS
    };

    /**
     * Builds all armor and trim triangles for a humanoid body.
     *
     * @param bodyPositions map from body part to the box it is seated in
     * @param equipped the worn pieces keyed by slot; an unworn slot is absent
     * @param items the equipped item identity per slot, for the pack-rule (CIT) texture override; empty
     *     leaves each slot on its equipment-model texture
     * @param engine the texture engine for pack-aware texture resolution
     * @return the armor + trim triangles, empty when no armor is equipped
     */
    public static @NotNull ConcurrentList<VisibleTriangle> buildHumanoidArmor3D(
        @NotNull Map<HumanoidPart, Box> bodyPositions,
        @NotNull Map<ArmorSlot, ArmorPiece> equipped,
        @NotNull Map<ArmorSlot, ItemContext> items,
        @NotNull Textures engine
    ) {
        ConcurrentList<VisibleTriangle> triangles = Concurrent.newList();

        for (ArmorSlot slot : SKIN_SLOT_ORDER) {
            ArmorPiece piece = equipped.get(slot);
            if (piece == null) continue;
            addSlot3D(triangles, bodyPositions, piece, slot,
                Optional.ofNullable(items.get(slot)), engine);
        }

        return triangles;
    }

    /**
     * The worn pieces in {@link ArmorSlot} declaration order, so the emission order is this kit's
     * rather than whatever iteration order the caller's map happens to have.
     */
    private static @NotNull Map<ArmorSlot, ArmorPiece> inCompositeOrder(
        @NotNull Map<ArmorSlot, ArmorPiece> equipped) {
        Map<ArmorSlot, ArmorPiece> ordered = new EnumMap<>(ArmorSlot.class);
        ordered.putAll(equipped);
        return ordered;
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
        @NotNull HumanoidPart part,
        @NotNull ArmorSlot slot,
        @NotNull ArmorPiece piece,
        int x, int y, int w, int h,
        @NotNull Optional<ItemContext> item,
        @NotNull Textures engine
    ) {
        // The target buffer owns the coverage mask (enabled by the caller when the armor is enchanted);
        // stamp the armor / trim sprite coverage into it so the enchantment foil lands on the armor,
        // not the bare skin. Absent when the caller records no mask - then stampMaskScaled is a no-op.
        PixelMask mask = target.mask().orElse(null);
        Optional<PixelBuffer> armorTexture =
            resolveArmorTexture(engine, piece, ArmorForm.ADULT.layerType(slot), item);
        armorTexture.ifPresent(tex -> {
            PixelBuffer face = part.crop(tex, Face.SOUTH, false);
            target.blitScaled(face, x, y, w, h);
            stampMaskScaled(mask, face, x, y, w, h);
        });

        piece.trim().ifPresent(trim -> ArmorForm.ADULT.trimLayer(slot)
            .flatMap(layer -> resolveTrimTexture(engine, layer, trim.pattern(), trim.color()))
            .ifPresent(trimTex -> {
                PixelBuffer face = part.crop(trimTex, Face.SOUTH, false);
                target.blitScaled(face, x, y, w, h);
                stampMaskScaled(mask, face, x, y, w, h);
            }));
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

    // ---------------------------------------------------------------------------------------
    // Entity armor (maps bone names to humanoid HumanoidPart parts).
    // ---------------------------------------------------------------------------------------

    /**
     * Builds armor triangles for an entity from the shell it is dressed in, mapped into the render
     * frame - matching vanilla, which dresses a humanoid in one of a handful of shared armor sets
     * rather than in a shell derived from the wearer's own mesh. A baby is dressed in its own shell
     * the same way; the age fold picked which one before this was reached, so nothing here branches on
     * it.
     *
     * @param armor the shell the wearer is dressed in
     * @param modelAnchor the model-space point mapped to the canvas centre
     * @param ndcScale the model-units-to-NDC scale
     * @param modelScale the per-render vertex pre-scale
     * @param equipped the worn pieces keyed by slot; an unworn slot is absent
     * @param items the equipped item identity per slot, for the pack-rule (CIT) texture override; empty
     *     leaves each slot on its equipment-model texture
     * @param engine the texture engine for pack-aware texture resolution
     * @return the armor + trim triangles
     */
    public static @NotNull ConcurrentList<VisibleTriangle> buildEntityArmor3D(
        @NotNull Entity.HumanoidArmor armor,
        @NotNull Vector3f modelAnchor,
        float ndcScale,
        float modelScale,
        @NotNull Map<ArmorSlot, ArmorPiece> equipped,
        @NotNull Map<ArmorSlot, ItemContext> items,
        @NotNull Textures engine
    ) {
        // The armor sheets are authored for the upright player frame (the player applies a plain
        // R_Y(180) facing). An entity's bone geometry lives in the Y-down model frame and is turned
        // upright by the renderer's ENTITY_FACING = R_Z(180), which also flips Y - so the two frames
        // differ by a 180-degree turn about X (Y and Z negated). Building the armor in the upright
        // player frame (bounds turned about X) and turning the result back into the entity frame lands
        // it correctly once ENTITY_FACING is applied, with the geometry, normals, and inventory shading
        // all resolved in the final frame.
        ConcurrentList<VisibleTriangle> upright =
            buildGenericArmor3D(armor, modelAnchor, ndcScale, modelScale, equipped, items, engine);

        Lighting.EntityLighting lighting =
            Lighting.resolveEntity(EntityGeometryKit.DEFAULT_ENTITY_LIGHTING);
        ConcurrentList<VisibleTriangle> entityArmor = Concurrent.newList();
        for (VisibleTriangle triangle : upright)
            entityArmor.add(intoModelFrame(triangle, lighting));
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
     * @param equipped the worn pieces keyed by slot; an unworn slot is absent
     * @param items the equipped item identity per slot, for the pack-rule (CIT) texture override
     * @param screenTransform the model-to-screen transform the bounds are accumulated through
     * @param modelScale the per-render vertex pre-scale
     * @param engine the texture engine for pack-aware texture resolution
     * @return the union of the equipped slots' screen bounds, or empty when none contributes
     */
    public static @NotNull Optional<Box> screenBounds(
        @NotNull Entity.HumanoidArmor armor,
        @NotNull Map<ArmorSlot, ArmorPiece> equipped,
        @NotNull Map<ArmorSlot, ItemContext> items,
        @NotNull Matrix4f screenTransform,
        float modelScale,
        @NotNull Textures engine
    ) {
        Box union = null;
        for (Map.Entry<ArmorSlot, ArmorPiece> entry : inCompositeOrder(equipped).entrySet()) {
            ArmorSlot slot = entry.getKey();
            Optional<PixelBuffer> sheet = resolveArmorTexture(engine, entry.getValue(),
                armor.form().layerType(slot), Optional.ofNullable(items.get(slot)));
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
        @NotNull Entity.HumanoidArmor armor, @NotNull ArmorSlot slot) {
        EntityModelData tree = armor.mesh();
        Vector3f deformation = slot.grow(armor);
        Vector3f seat = armor.meshOffset().multiply(1f / armor.meshScale());
        LinkedHashMap<String, EntityModelData.Bone> bones = new LinkedHashMap<>();
        for (Map.Entry<String, EntityModelData.Bone> entry : tree.getBones().entrySet()) {
            EntityModelData.Bone bone = entry.getValue();
            ConcurrentList<EntityModelData.Cube> cubes = Concurrent.newList();
            if (armor.walk().covers(slot, entry.getKey()))
                for (EntityModelData.Cube cube : bone.getCubes())
                    cubes.add(grownBy(cube, deformation));
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
        @NotNull Entity.HumanoidArmor armor,
        @NotNull Vector3f modelAnchor,
        float ndcScale,
        float modelScale,
        @NotNull Map<ArmorSlot, ArmorPiece> equipped,
        @NotNull Map<ArmorSlot, ItemContext> items,
        @NotNull Textures engine
    ) {
        ConcurrentList<VisibleTriangle> triangles = Concurrent.newList();

        for (Map.Entry<ArmorSlot, ArmorPiece> entry : inCompositeOrder(equipped).entrySet()) {
            ArmorSlot slot = entry.getKey();
            addMeshSlot3D(triangles, armorBoxes(armor, slot, modelAnchor, ndcScale, modelScale),
                armor.form(), entry.getValue(), slot, Optional.ofNullable(items.get(slot)), engine);
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
     * <p>A part collapses to one axis-aligned box because the armor meshes are plain box tables - no
     * bone carries a rotation. A bone's uniform {@code scale} <em>is</em> honoured: every shell
     * vanilla registers untransformed leaves it at the identity, but the shell an aged-down
     * whole-mesh transformer builds carries one per bone, and drawing that shell unscaled would put a
     * full-size helmet on a half-size body. It multiplies each operand rather than the assembled
     * corners, so at the identity every existing wearer's arithmetic is the same expression on the
     * same values and cannot round differently.
     */
    private static @NotNull List<ArmorBox> armorBoxes(
        @NotNull Entity.HumanoidArmor armor, @NotNull ArmorSlot slot,
        @NotNull Vector3f modelAnchor, float ndcScale, float modelScale) {
        Vector3f deformation = slot.grow(armor);
        List<ArmorBox> boxes = new ArrayList<>();
        for (Map.Entry<String, EntityModelData.Bone> entry : armor.mesh().getBones().entrySet()) {
            if (!armor.walk().covers(slot, entry.getKey())) continue;
            Vector3f anchor = armor.walk().anchor(entry.getKey());
            float scale = entry.getValue().getScale();
            for (EntityModelData.Cube cube : entry.getValue().getCubes()) {
                Vector3f grow = deformation.add(cube.getGrow()).multiply(scale);
                Vector3f min = anchor.add(cube.getOrigin().multiply(scale)).subtract(grow);
                Vector3f max = min.add(cube.getSize().multiply(scale)).add(grow).add(grow);
                Vector3f[] corners = intoUprightFrameBounds(new Vector3f[]{
                    toRenderFrame(armor, modelAnchor, ndcScale, modelScale, min.x(), min.y(), min.z()),
                    toRenderFrame(armor, modelAnchor, ndcScale, modelScale, max.x(), max.y(), max.z())
                });
                boxes.add(new ArmorBox(entry.getKey(), cube, corners[0], corners[1]));
            }
        }
        return boxes;
    }

    /**
     * The same cube carrying the slot's deformation summed onto its own - the pair vanilla's mesh
     * builder adds, in that order.
     */
    private static @NotNull EntityModelData.Cube grownBy(
        @NotNull EntityModelData.Cube cube, @NotNull Vector3f deformation) {
        return new EntityModelData.Cube(cube.getOrigin(), cube.getSize(), cube.getUv(),
            deformation.add(cube.getGrow()), cube.isMirror(), cube.getPivot(), cube.getRotation(),
            cube.getFaceUv());
    }

    /**
     * Maps a point from model units into the render frame, applying the same pre-scale, anchor and
     * NDC scale the entity's own geometry was built through.
     *
     * <p>The whole-mesh transform a scaled-up humanoid's shell is sized and seated by belongs to the
     * armor set rather than to the render, and {@link Entity.HumanoidArmor#meshScale()} carries it.
     * Vanilla maps the very same transformer over the shared set rather than giving those wearers a
     * distinct mesh, so the factor is a property of what is worn and not of who wears it - reading it
     * back off the wearer's torso bone only ever worked because those three wearers' bodies happen to be
     * built through the same transformer, and it was silently wrong for a baby, whose own body pivot is
     * not one.
     */
    private static @NotNull Vector3f toRenderFrame(
        @NotNull Entity.HumanoidArmor armor, @NotNull Vector3f modelAnchor, float ndcScale,
        float modelScale, float x, float y, float z) {
        // The set's own whole-mesh transform first, so the shell is sized and seated like the body it
        // dresses, then the render frame the body's own geometry was built through.
        Vector3f offset = armor.meshOffset();
        float mesh = armor.meshScale();
        float mx = mesh * x + offset.x();
        float my = mesh * y + offset.y();
        float mz = mesh * z + offset.z();

        return new Vector3f(
            ndcScale * (modelScale * mx - modelAnchor.x()),
            ndcScale * (modelScale * my - modelAnchor.y()),
            ndcScale * (modelScale * mz - modelAnchor.z()));
    }

    /**
     * Turns a {@code [min, max]} bounding box out of the model frame, re-sorting the negated Y and Z
     * extents so the result stays a valid {@code [min, max]} pair - the turn swaps which of the two
     * corners is the lower one on those axes.
     */
    private static @NotNull Vector3f @NotNull [] intoUprightFrameBounds(@NotNull Vector3f @NotNull [] bounds) {
        Vector3f low = MODEL_FRAME.apply(bounds[1]);
        Vector3f high = MODEL_FRAME.apply(bounds[0]);
        return new Vector3f[]{
            new Vector3f(bounds[0].x(), low.y(), low.z()),
            new Vector3f(bounds[1].x(), high.y(), high.z())
        };
    }

    /**
     * Maps a built armor triangle from the upright player frame back into the entity's Y-down model
     * frame. Positions and the stored normal turn together (a half turn is a pure rotation and
     * preserves winding, so culling is unaffected), and the shade is recomputed from the turned normal
     * so lighting resolves in the final frame.
     *
     * <p><b>Two different turns happen here, and only one of them is the frame change.</b> The
     * geometry takes the half turn about X; the shading normal then takes a further Y mirror, because
     * the kit's tuned light frame is reflected in Y from the geometry frame - exactly as the body's
     * own faces are.
     *
     * <p>The shade comes from the entity lighting basis, not the block / item inventory one: worn armor
     * is part of the entity render and vanilla lights it with the same two-directional shader as the
     * body it dresses. Armor boxes are built two-sided, so the shade resolves through the per-face
     * form - a face the camera sees from behind is lit by its camera-facing orientation, and one seen
     * from the front is lit by its own normal exactly as a culling cube would be.
     */
    private static @NotNull VisibleTriangle intoModelFrame(
        @NotNull VisibleTriangle triangle, @NotNull Lighting.EntityLighting lighting) {
        Vector3f normal = MODEL_FRAME.apply(triangle.normal());
        Vector3f shadingNormal = Turn.MIRROR_Y.apply(normal);
        return new VisibleTriangle(
            MODEL_FRAME.apply(triangle.position0()),
            MODEL_FRAME.apply(triangle.position1()),
            MODEL_FRAME.apply(triangle.position2()),
            triangle.uv0(), triangle.uv1(), triangle.uv2(),
            triangle.texture(), triangle.tintArgb(), normal,
            lighting.shade(shadingNormal, triangle.traits().cullBackFaces()),
            triangle.traits(), triangle.debugTag());
    }

    // ---------------------------------------------------------------------------------------
    // Shared internals.
    // ---------------------------------------------------------------------------------------

    /**
     * Adds one slot's armor around bounds carried in the skin renderer's normalized frame, inflating
     * by the amount that slot is calibrated for.
     *
     * <p>The player is dressed in the adult shell, so the sheet and the trim atlas are that form's -
     * the same two answers the mesh path reads off the shell it was handed, rather than a second pair
     * of literals that happen to agree with them.
     */
    private static void addSlot3D(
        @NotNull ConcurrentList<VisibleTriangle> triangles,
        @NotNull Map<HumanoidPart, Box> bodyPositions,
        @NotNull ArmorPiece piece,
        @NotNull ArmorSlot slot,
        @NotNull Optional<ItemContext> item,
        @NotNull Textures engine
    ) {
        HumanoidPart[] parts = ArmorForm.playerParts(slot);
        float inflate = slot.skinInflate();

        Optional<PixelBuffer> armorTexture =
            resolveArmorTexture(engine, piece, ArmorForm.ADULT.layerType(slot), item);
        if (armorTexture.isEmpty()) return;

        for (HumanoidPart part : parts) {
            Box bounds = bodyPositions.get(part);
            if (bounds == null) continue;
            triangles.addAll(buildSkinBox3D(part, bounds, armorTexture.get(), inflate));
        }

        if (piece.trim().isEmpty()) return;
        ArmorTrim trim = piece.trim().get();
        ArmorForm.ADULT.trimLayer(slot)
            .flatMap(layer -> resolveTrimTexture(engine, layer, trim.pattern(), trim.color()))
            .ifPresent(trimTexture -> {
                for (HumanoidPart part : parts) {
                    Box bounds = bodyPositions.get(part);
                    if (bounds == null) continue;
                    triangles.addAll(buildSkinBox3D(part, bounds, trimTexture, inflate));
                }
            });
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
        @NotNull ArmorSlot slot,
        @NotNull Optional<ItemContext> item,
        @NotNull Textures engine
    ) {
        Optional<PixelBuffer> armorTexture = resolveArmorTexture(engine, piece, form.layerType(slot), item);
        if (armorTexture.isEmpty()) return;

        for (ArmorBox box : boxes)
            triangles.addAll(buildMeshBox3D(box, armorTexture.get()));

        if (piece.trim().isEmpty()) return;
        ArmorTrim trim = piece.trim().get();
        form.trimLayer(slot)
            .flatMap(layer -> resolveTrimTexture(engine, layer, trim.pattern(), trim.color()))
            .ifPresent(trimTexture -> {
                for (ArmorBox box : boxes)
                    triangles.addAll(buildMeshBox3D(box, trimTexture));
            });
    }

    /**
     * Builds one box of the player's own armor, around bounds carried in the skin renderer's
     * normalized frame and read through that body part's own skin rectangles.
     */
    private static @NotNull ConcurrentList<VisibleTriangle> buildSkinBox3D(
        @NotNull HumanoidPart part,
        @NotNull Box bounds,
        @NotNull PixelBuffer texture,
        float inflate
    ) {
        Vector3f inflatedMin = new Vector3f(
            bounds.minX() - inflate, bounds.minY() - inflate, bounds.minZ() - inflate);
        Vector3f inflatedMax = new Vector3f(
            bounds.maxX() + inflate, bounds.maxY() + inflate, bounds.maxZ() + inflate);
        return BlockGeometryKit.buildBox(
            inflatedMin, inflatedMax, part.textures(texture, false), ColorMath.WHITE,
            SurfaceTraits.WORN_SHELL,
            RendererDebug.tracingPixels() ? part.name().toLowerCase(Locale.ROOT) : null);
    }

    /**
     * Builds one box of a shell, read through the cube's own unwrap against the sheet - each
     * render-frame face turned into the model-frame one that unwrap addresses.
     *
     * <p>The unwrap is the cube's rather than a table's because a shell states where each of its boxes
     * sits on its sheet, and the two shells state different things - the baby's head is a nine-wide box
     * at the sheet's origin where the adult's is eight-wide, and its feet and waist have no counterpart
     * in the skin layout at all. On the adult shell the two agree box for box, the helmet's second box
     * included: that shell IS the skin unwrap, mirrored left limbs and all.
     */
    private static @NotNull ConcurrentList<VisibleTriangle> buildMeshBox3D(
        @NotNull ArmorBox box, @NotNull PixelBuffer texture) {
        EntityModelData.Cube cube = box.cube();
        Unwrap.Atlas unwrap = new Unwrap.Atlas(cube.getUv(), cube.getSize(), cube.isMirror());
        return BlockGeometryKit.buildBox(
            box.min(), box.max(),
            face -> unwrap.crop(texture, MODEL_FRAME.apply(face)), ColorMath.WHITE,
            SurfaceTraits.WORN_SHELL,
            RendererDebug.tracingPixels() ? box.bone() : null);
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
