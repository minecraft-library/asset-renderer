package lib.minecraft.renderer.engine.kit;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.PixelBuffer;
import dev.simplified.image.pixel.PixelMask;
import lib.minecraft.renderer.asset.equipment.ArmorForm;
import lib.minecraft.renderer.asset.equipment.LayerType;
import lib.minecraft.renderer.asset.equipment.Shell;
import lib.minecraft.renderer.asset.equipment.ShellPart;
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
import lib.minecraft.renderer.option.PlayerOptions;
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
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * Generates 3D armor overlay geometry and 2D armor sprite layers for humanoid renders. Given the
 * body part positions used by the skin renderer, produces slightly inflated cubes (3D) or
 * composited face crops (2D) textured from the vanilla armor atlases with optional
 * paletted-permutation trim overlays.
 * <p>
 * The armor texture is a 64x32 atlas whose UV layout matches the top half of the vanilla 64x64
 * player skin - the base layer plus the head's overlay, which the helmet's second box really does read
 * on both paths - so {@link HumanoidPart#textures(PixelBuffer, boolean) textures} and
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
     * One armor box ready to build: the row it was resolved from, and its corners in the frame it is
     * drawn in. The row travels rather than a resolved crop because a slot's boxes are textured twice -
     * once from the armor sheet and once from the trim - and each crop is a read of the same row
     * against a different sheet.
     *
     * @param row the row the box was resolved from, which answers for its texture and its trace name
     * @param bounds the box in the frame it is drawn in
     */
    private record SlotBox(
        @NotNull ShellPart row,
        @NotNull Box bounds
    ) {}

    /**
     * The entity's Y-down model frame, relative to the upright frame the armor sheets are authored for
     * and the armor is built in. Every use of it here is geometric - a corner pair turned out of one
     * frame and a built triangle's positions and normal turned back into the other - since the two
     * frames differ by a half turn about X, with Y and Z negated and the two sides left where they are.
     */
    private static final @NotNull Turn MODEL_FRAME = Turn.HALF_X;

    // ---------------------------------------------------------------------------------------
    // 3D armor (triangles for ModelEngine rasterization).
    // ---------------------------------------------------------------------------------------

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
        // The player's rows are its own body boxes in the render scope's frame, which is the frame they
        // are drawn in, so nothing crosses a frame on the way - and the player is always dressed adult.
        return buildArmor3D(bodyRows(bodyPositions), UnaryOperator.identity(), ArmorForm.ADULT,
            equipped, items, engine);
    }

    /**
     * The player's own body as armor rows - one per box a slot could draw over it, in the body's own
     * part order.
     *
     * <p>The player is dressed in the adult shell's answers rather than in its boxes: which slots reach
     * a part is that shell's part table read back through the body box each bone dresses, while the box
     * itself is the player's own, in the scope's frame. A part no slot reaches contributes no row.
     *
     * <p>The second box a helmet draws over the head is a row of its own here rather than a branch
     * inside the first, which is what makes it the peer of the layer box that the shell's {@code hat}
     * cube already is on the mesh path. It is emitted directly after the box it sits over, and it is
     * covered by exactly the slots that keep a named part's children - the helmet alone.
     */
    private static @NotNull List<ShellPart> bodyRows(@NotNull Map<HumanoidPart, Box> bodyPositions) {
        List<ShellPart> rows = new ArrayList<>();

        for (HumanoidPart part : HumanoidPart.CACHED_VALUES) {
            Box bounds = bodyPositions.get(part);
            if (bounds == null) continue;
            Set<ArmorSlot> slots = ArmorForm.playerSlots(part);
            if (slots.isEmpty()) continue;

            rows.add(new ShellPart.Body(part, false, slots, bounds));

            EnumSet<ArmorSlot> second = EnumSet.noneOf(ArmorSlot.class);
            for (ArmorSlot slot : slots)
                if (slot.keepsChildren()) second.add(slot);
            if (!second.isEmpty())
                rows.add(new ShellPart.Body(part, true, Set.copyOf(second), bounds));
        }

        return rows;
    }

    // ---------------------------------------------------------------------------------------
    // 2D armor (composites south-facing face crops onto a canvas).
    // ---------------------------------------------------------------------------------------

    /**
     * Composites the 2D front-facing armor and trim sprites for one body part into the canvas rectangle
     * its layout row names. The slot determines whether to use the humanoid (layer 1) or
     * humanoid_leggings (layer 2) texture atlas.
     *
     * @param target the target buffer
     * @param row the body part whose south face to crop, and the canvas rectangle to blit it into
     * @param slot the armor slot that determines the texture layer
     * @param piece the armor piece to render
     * @param item the equipped item identity, for the pack-rule (CIT) texture override; empty leaves the
     *     slot on its equipment-model texture
     * @param engine the texture engine for pack-aware texture resolution
     */
    public static void compositeSlot2D(
        @NotNull PixelBuffer target,
        @NotNull PlayerOptions.Type.BodyPart2D row,
        @NotNull ArmorSlot slot,
        @NotNull ArmorPiece piece,
        @NotNull Optional<ItemContext> item,
        @NotNull Textures engine
    ) {
        // The target buffer owns the coverage mask (enabled by the caller when the armor is enchanted);
        // stamp the armor / trim sprite coverage into it so the enchantment foil lands on the armor,
        // not the bare skin. Absent when the caller records no mask - then stampMaskScaled is a no-op.
        PixelMask mask = target.mask().orElse(null);
        Optional<PixelBuffer> armorTexture =
            resolveArmorTexture(engine, piece, ArmorForm.ADULT.layerType(slot), item);
        armorTexture.ifPresent(tex -> blit2D(target, mask, row, tex));

        piece.trim().ifPresent(trim -> ArmorForm.ADULT.trimLayer(slot)
            .flatMap(layer -> resolveTrimTexture(engine, layer, trim.pattern(), trim.color()))
            .ifPresent(trimTex -> blit2D(target, mask, row, trimTex)));
    }

    /**
     * Crops one sheet's south face for a layout row, blits it into that row's rectangle and stamps the
     * same coverage into the glint mask. The armor sheet and the trim sheet are drawn this way in that
     * order, and the two passes differ in nothing but the sheet.
     */
    private static void blit2D(
        @NotNull PixelBuffer target, @Nullable PixelMask mask,
        @NotNull PlayerOptions.Type.BodyPart2D row, @NotNull PixelBuffer sheet) {
        PixelBuffer face = row.part().crop(sheet, Face.SOUTH, false);
        target.blitScaled(face, row.x(), row.y(), row.w(), row.h());
        stampMaskScaled(mask, face, row);
    }

    /**
     * Marks the glint mask over the destination rectangle wherever the scaled source {@code face}
     * has a non-transparent texel, mirroring {@code blitScaled}'s nearest-neighbour mapping. This is
     * the 2D analogue of the 3D rasterizer's per-pixel glint marking - it records exactly the armor /
     * trim coverage so the foil never lands on the bare skin underneath. No-op when {@code mask} is
     * {@code null}.
     */
    private static void stampMaskScaled(
        @Nullable PixelMask mask, @NotNull PixelBuffer face,
        @NotNull PlayerOptions.Type.BodyPart2D row) {
        if (mask == null) return;
        int fw = face.width();
        int fh = face.height();
        int w = row.w();
        int h = row.h();
        if (fw <= 0 || fh <= 0 || w <= 0 || h <= 0) return;
        for (int dy = 0; dy < h; dy++) {
            int sy = Math.min(fh - 1, dy * fh / h);
            for (int dx = 0; dx < w; dx++) {
                int sx = Math.min(fw - 1, dx * fw / w);
                if (ColorMath.alpha(face.getPixel(sx, sy)) != 0)
                    mask.mark(row.x() + dx, row.y() + dy);
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
     * @param shell the shell the wearer is dressed in
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
        @NotNull Shell shell,
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
        ConcurrentList<VisibleTriangle> upright = buildArmor3D(shell.walk().parts(),
            box -> intoRenderFrame(shell, modelAnchor, ndcScale, modelScale, box), shell.form(),
            equipped, items, engine);

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
     * @param shell the shell the wearer is dressed in
     * @param equipped the worn pieces keyed by slot; an unworn slot is absent
     * @param items the equipped item identity per slot, for the pack-rule (CIT) texture override
     * @param screenTransform the model-to-screen transform the bounds are accumulated through
     * @param modelScale the per-render vertex pre-scale
     * @param engine the texture engine for pack-aware texture resolution
     * @return the union of the equipped slots' screen bounds, or empty when none contributes
     */
    public static @NotNull Optional<Box> screenBounds(
        @NotNull Shell shell,
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
                shell.form().layerType(slot), Optional.ofNullable(items.get(slot)));
            if (sheet.isEmpty()) continue;
            Box slotBounds = EntityGeometryKit.computeScreenBounds(slotMesh(shell, slot), screenTransform,
                modelScale * shell.meshScale(), sheet.get());
            union = union == null ? slotBounds : union.union(slotBounds);
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
        @NotNull Shell shell, @NotNull ArmorSlot slot) {
        EntityModelData tree = shell.mesh();
        Vector3f deformation = slot.onLayer(shell.innerGrow(), shell.outerGrow());
        Vector3f seat = shell.meshOffset().multiply(1f / shell.meshScale());
        LinkedHashMap<String, EntityModelData.Bone> bones = new LinkedHashMap<>();
        for (Map.Entry<String, EntityModelData.Bone> entry : tree.getBones().entrySet()) {
            EntityModelData.Bone bone = entry.getValue();
            ConcurrentList<EntityModelData.Cube> cubes = Concurrent.newList();
            if (shell.walk().covers(slot, entry.getKey()))
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
     * One box of a worn shell mapped through the render frame and then turned into the upright frame
     * the armor unwrap is authored for. This crossing is the only thing the mesh arm hands
     * {@link #resolveBoxes} that the player's arm does not.
     *
     * <p>A row collapses to one axis-aligned box because the armor meshes are plain box tables - no
     * bone carries a rotation. A bone's uniform {@code scale} <em>is</em> honoured: every shell
     * vanilla registers untransformed leaves it at the identity, but the shell an aged-down
     * whole-mesh transformer builds carries one per bone, and drawing that shell unscaled would put a
     * full-size helmet on a half-size body. It multiplies each operand rather than the assembled
     * corners, so at the identity every existing wearer's arithmetic is the same expression on the
     * same values and cannot round differently.
     */
    private static @NotNull Box intoRenderFrame(
        @NotNull Shell shell, @NotNull Vector3f modelAnchor, float ndcScale, float modelScale,
        @NotNull Box box) {
        Vector3f[] corners = intoUprightFrameBounds(new Vector3f[]{
            toRenderFrame(shell, modelAnchor, ndcScale, modelScale, box.minX(), box.minY(), box.minZ()),
            toRenderFrame(shell, modelAnchor, ndcScale, modelScale, box.maxX(), box.maxY(), box.maxZ())
        });
        return Box.of(corners[0], corners[1]);
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
     * armor set rather than to the render, and {@link Shell#meshScale()} carries it.
     * Vanilla maps the very same transformer over the shared set rather than giving those wearers a
     * distinct mesh, so the factor is a property of what is worn and not of who wears it - reading it
     * back off the wearer's torso bone only ever worked because those three wearers' bodies happen to be
     * built through the same transformer, and it was silently wrong for a baby, whose own body pivot is
     * not one.
     */
    private static @NotNull Vector3f toRenderFrame(
        @NotNull Shell shell, @NotNull Vector3f modelAnchor, float ndcScale,
        float modelScale, float x, float y, float z) {
        // The set's own whole-mesh transform first, so the shell is sized and seated like the body it
        // dresses, then the render frame the body's own geometry was built through.
        Vector3f offset = shell.meshOffset();
        float mesh = shell.meshScale();
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
     * Builds one wearer's armor from a flat list of rows: each equipped slot's rows of that list,
     * resolved into the frame they are drawn in and textured through the form's answers for that slot.
     *
     * <p>Both wearers reach this, and they differ in exactly three arguments. A worn shell hands its
     * own cubes, the crossing into the render frame, and its own form; the player hands its own body
     * boxes, the identity, and the adult form it is always dressed in. Nothing downstream of here
     * branches on which of the two it is serving.
     */
    private static @NotNull ConcurrentList<VisibleTriangle> buildArmor3D(
        @NotNull List<ShellPart> rows,
        @NotNull UnaryOperator<Box> intoFrame,
        @NotNull ArmorForm form,
        @NotNull Map<ArmorSlot, ArmorPiece> equipped,
        @NotNull Map<ArmorSlot, ItemContext> items,
        @NotNull Textures engine
    ) {
        ConcurrentList<VisibleTriangle> triangles = Concurrent.newList();

        for (Map.Entry<ArmorSlot, ArmorPiece> entry : inCompositeOrder(equipped).entrySet()) {
            ArmorSlot slot = entry.getKey();
            addSlot3D(triangles, resolveBoxes(rows, slot, intoFrame), form, slot, entry.getValue(),
                Optional.ofNullable(items.get(slot)), engine);
        }

        return triangles;
    }

    /**
     * The rows one slot draws, each resolved to the box that slot wears it at and then into the frame
     * it is drawn in.
     *
     * <p>The slot picks which of the two deformations it wears - the leggings the inner one, the other
     * three the outer - and keeps only the rows vanilla keeps for it. A worn shell's row is grown by
     * that deformation plus its own {@code CubeDeformation.extend} (a leg's {@code -0.1}, a helmet's
     * second box), which is the sum vanilla's mesh builder performs in the same order; the player's is
     * its body box inflated by the scalar the slot is calibrated for. Row order is the list's own -
     * for a shell the mesh's bone order and then each bone's cube order, so a part and the overlay box
     * parented to it stay adjacent, which is what decides a coplanar tie.
     */
    private static @NotNull List<SlotBox> resolveBoxes(
        @NotNull List<ShellPart> rows, @NotNull ArmorSlot slot,
        @NotNull UnaryOperator<Box> intoFrame) {
        List<SlotBox> boxes = new ArrayList<>();

        for (ShellPart row : rows) {
            if (!row.coveredBy(slot)) continue;
            boxes.add(new SlotBox(row, intoFrame.apply(row.boxFor(slot))));
        }

        return boxes;
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

    /**
     * Adds one slot's armor around boxes already resolved into the frame they are drawn in, so nothing
     * is grown or inflated here.
     *
     * <p>The sheet and the trim atlas are the form's answers for the slot - a worn shell hands its own
     * form, and the player is dressed in the adult one - and every box is textured twice when the piece
     * carries a trim, once from each sheet in that order.
     */
    private static void addSlot3D(
        @NotNull ConcurrentList<VisibleTriangle> triangles,
        @NotNull List<SlotBox> boxes,
        @NotNull ArmorForm form,
        @NotNull ArmorSlot slot,
        @NotNull ArmorPiece piece,
        @NotNull Optional<ItemContext> item,
        @NotNull Textures engine
    ) {
        Optional<PixelBuffer> armorTexture =
            resolveArmorTexture(engine, piece, form.layerType(slot), item);
        if (armorTexture.isEmpty()) return;

        for (SlotBox box : boxes)
            triangles.addAll(buildBox3D(box, armorTexture.get()));

        if (piece.trim().isEmpty()) return;
        ArmorTrim trim = piece.trim().get();
        form.trimLayer(slot)
            .flatMap(layer -> resolveTrimTexture(engine, layer, trim.pattern(), trim.color()))
            .ifPresent(trimTexture -> {
                for (SlotBox box : boxes)
                    triangles.addAll(buildBox3D(box, trimTexture));
            });
    }

    /**
     * Builds one box of armor, read through its own row against the sheet it is textured with.
     *
     * <p>A worn shell's row reads its cube's own unwrap, turned into the model frame that unwrap
     * addresses; the player's reads its body part's own skin rectangles, which resolve that turn when
     * the part is declared. On the adult shell the two agree box for box, the helmet's second box
     * included: that shell IS the skin unwrap, mirrored left limbs and all.
     */
    private static @NotNull ConcurrentList<VisibleTriangle> buildBox3D(
        @NotNull SlotBox box, @NotNull PixelBuffer texture) {
        return BlockGeometryKit.buildBox(
            box.bounds(), box.row().textures(texture), ColorMath.WHITE,
            SurfaceTraits.WORN_SHELL,
            RendererDebug.tracingPixels() ? box.row().trace() : null);
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
     * Resolves and permutes a 3D entity-armor trim texture. Only the base pattern's id is this path's
     * own - the {@code trims/entity/{layer}} atlas ({@code humanoid} or {@code humanoid_leggings})
     * rather than {@link TrimKit}'s item-slot {@code trims/items/} stem - so it builds that and hands
     * the rest to the resolve both trim paths share.
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
        return TrimKit.permuteFrom(engine,
            "minecraft:trims/entity/" + layer + "/" + pattern.getKey(), color.getKey());
    }

}
