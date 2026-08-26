package lib.minecraft.renderer.engine.kit;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.asset.equipment.ArmorPiece;
import lib.minecraft.renderer.asset.equipment.ArmorSlot;
import lib.minecraft.renderer.asset.equipment.Shell;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.asset.pack.rule.ItemContext;
import lib.minecraft.renderer.engine.RendererContext;
import lib.minecraft.renderer.engine.camera.RenderFrame;
import lib.minecraft.renderer.engine.light.Shading;
import lib.minecraft.renderer.engine.raster.VisibleTriangle;
import lib.minecraft.renderer.face.Turn;
import lib.minecraft.renderer.tensor.Box;
import lib.minecraft.renderer.tensor.Matrix4f;
import lib.minecraft.renderer.tensor.Vector3f;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Dresses an entity in the {@link Shell} it wears, and measures what that shell adds to the canvas.
 * <p>
 * Vanilla never derives worn armour from the wearer's own mesh - it builds a few armour model sets
 * and hands each renderer the one its subject wears - so everything here starts from a resolved
 * shell rather than from the body underneath it. {@link PlayerArmorKit} is the other wearer and
 * holds no shell at all; what the two share is in {@link ArmorKit}.
 * <p>
 * Two frames meet here. The armour sheets are authored for the upright player frame, while an
 * entity's bone geometry lives in the Y-down model frame, and the two differ by a half turn about X.
 * The shell is therefore built upright and turned back, which is what puts its geometry and its
 * stored normals in the frame the pass that lights the wearer's folded stack reads every other
 * triangle in.
 */
@UtilityClass
public class EntityArmorKit {

    /**
     * The entity's Y-down model frame, relative to the upright frame the armor sheets are authored for
     * and the armor is built in. Every use of it here is geometric - a corner pair turned out of one
     * frame and a built triangle's positions and normal turned back into the other - since the two
     * frames differ by a half turn about X, with Y and Z negated and the two sides left where they are.
     */
    private static final @NotNull Turn MODEL_FRAME = Turn.HALF_X;

    /**
     * Builds armor triangles for an entity from the shell it is dressed in, mapped into the render
     * frame - matching vanilla, which dresses a humanoid in one of a handful of shared armor sets
     * rather than in a shell derived from the wearer's own mesh. A baby is dressed in its own shell
     * the same way; the age fold picked which one before this was reached, so nothing here branches on
     * it.
     *
     * @param shell the shell the wearer is dressed in
     * @param frame the render frame the wearer's own geometry was built through
     * @param equipped the worn pieces keyed by slot; an unworn slot is absent
     * @param items the equipped item identity per slot, for the pack-rule (CIT) texture override; empty
     *     leaves each slot on its equipment-model texture
     * @param context the texture context for pack-aware texture resolution
     * @return the armor + trim triangles
     */
    public static @NotNull ConcurrentList<VisibleTriangle> buildEntityArmor3D(
        @NotNull Shell shell,
        @NotNull RenderFrame frame,
        @NotNull Map<ArmorSlot, ArmorPiece> equipped,
        @NotNull Map<ArmorSlot, ItemContext> items,
        @NotNull RendererContext context
    ) {
        // The armor sheets are authored for the upright player frame (the player applies a plain
        // R_Y(180) facing). An entity's bone geometry lives in the Y-down model frame and is turned
        // upright by the renderer's ENTITY_FACING = R_Z(180), which also flips Y - so the two frames
        // differ by a 180-degree turn about X (Y and Z negated). Building the armor in the upright
        // player frame (bounds turned about X) and turning the result back into the entity frame lands
        // it correctly once ENTITY_FACING is applied, with the geometry and normals in the frame the
        // wearer's own faces are in - which is the frame the pass that lights the folded stack reads.
        ConcurrentList<VisibleTriangle> upright = ArmorKit.buildArmor3D(shell.walk().parts(),
            box -> intoRenderFrame(shell, frame, box), shell.form(), equipped, items, context);

        ConcurrentList<VisibleTriangle> entityArmor = Concurrent.newList();
        for (VisibleTriangle triangle : upright)
            entityArmor.add(intoModelFrame(triangle));
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
     * @param context the texture context for pack-aware texture resolution
     * @return the union of the equipped slots' screen bounds, or empty when none contributes
     */
    public static @NotNull Optional<Box> screenBounds(
        @NotNull Shell shell,
        @NotNull Map<ArmorSlot, ArmorPiece> equipped,
        @NotNull Map<ArmorSlot, ItemContext> items,
        @NotNull Matrix4f screenTransform,
        float modelScale,
        @NotNull RendererContext context
    ) {
        Box union = null;
        for (Map.Entry<ArmorSlot, ArmorPiece> entry : ArmorKit.inCompositeOrder(equipped).entrySet()) {
            ArmorSlot slot = entry.getKey();
            Optional<PixelBuffer> sheet = ArmorKit.resolveArmorTexture(context, entry.getValue(),
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
            bones.put(entry.getKey(), bone.withCubes(cubes).withPivot(pivot));
        }
        return new EntityModelData(tree.getTextureSize(), tree.getInventoryYRotation(),
            Concurrent.adoptLinkedMap(bones), tree.isCull());
    }

    /**
     * One box of a worn shell mapped through the render frame and then turned into the upright frame
     * the armor unwrap is authored for. This crossing is the only thing the shell arm hands
     * {@link ArmorKit#buildArmor3D the shared builder} that {@link PlayerArmorKit}'s arm does not.
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
        @NotNull Shell shell, @NotNull RenderFrame frame, @NotNull Box box) {
        Vector3f[] corners = intoUprightFrameBounds(new Vector3f[]{
            toRenderFrame(shell, frame, box.minX(), box.minY(), box.minZ()),
            toRenderFrame(shell, frame, box.maxX(), box.maxY(), box.maxZ())
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
        @NotNull Shell shell, @NotNull RenderFrame frame, float x, float y, float z) {
        // The set's own whole-mesh transform first, so the shell is sized and seated like the body it
        // dresses, then the render frame the body's own geometry was built through.
        Vector3f offset = shell.meshOffset();
        float mesh = shell.meshScale();
        float mx = mesh * x + offset.x();
        float my = mesh * y + offset.y();
        float mz = mesh * z + offset.z();

        Vector3f anchor = frame.anchor();
        float ndcScale = frame.ndcScale();
        float modelScale = frame.modelScale();
        return new Vector3f(
            ndcScale * (modelScale * mx - anchor.x()),
            ndcScale * (modelScale * my - anchor.y()),
            ndcScale * (modelScale * mz - anchor.z()));
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
     * frame. Positions and the stored normal turn together - a half turn is a pure rotation and
     * preserves winding, so culling is unaffected.
     *
     * <p>The shade is not resolved here. Worn armor is part of the entity render and vanilla lights it
     * with the same two-directional shader as the body it dresses, under one entry bound before any
     * layer is submitted, so the shell reaches the rasterizer lit by the pass that lights the wearer's
     * folded stack rather than by a shade of its own. Turning the stored normal into the model frame is
     * what puts that pass in the frame it reads every other triangle in the stack in.
     */
    private static @NotNull VisibleTriangle intoModelFrame(@NotNull VisibleTriangle triangle) {
        Vector3f normal = MODEL_FRAME.apply(triangle.normal());
        return new VisibleTriangle(
            MODEL_FRAME.apply(triangle.position0()),
            MODEL_FRAME.apply(triangle.position1()),
            MODEL_FRAME.apply(triangle.position2()),
            triangle.uv0(), triangle.uv1(), triangle.uv2(),
            triangle.texture(), triangle.tintArgb(), normal,
            Shading.UNLIT, triangle.traits(), triangle.debugTag());
    }

}
