package lib.minecraft.renderer.engine.kit;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentLinkedMap;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.asset.equipment.EquipmentModel;
import lib.minecraft.renderer.asset.equipment.LayerType;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.asset.model.TextureSize;
import lib.minecraft.renderer.asset.pack.rule.ItemContext;
import lib.minecraft.renderer.engine.light.Lighting;
import lib.minecraft.renderer.engine.raster.VisibleTriangle;
import lib.minecraft.renderer.engine.texture.Textures;
import lib.minecraft.renderer.face.Turn;
import lib.minecraft.renderer.option.spec.ArmorMaterial;
import lib.minecraft.renderer.tensor.Box;
import lib.minecraft.renderer.tensor.EulerRotation;
import lib.minecraft.renderer.tensor.Vector2f;
import lib.minecraft.renderer.tensor.Vector3f;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;

/**
 * Builds the two-bone elytra wing mesh and its rasterizer triangles. The mesh mirrors vanilla
 * {@code ElytraModel.createLayer} bone for bone (left / right wing, each a {@code 10x20x2} box
 * inflated {@code 1.0} on a {@code 64x32} atlas, offset {@code +-5} and rotated {@code +-15deg}), fed
 * through {@link EntityGeometryKit#buildTriangles} - the same path the entity equipment overlay uses -
 * so no kit change or new schema is needed.
 * <p>
 * The wing texture is the data-driven {@code equipment/elytra.json} {@link LayerType#WINGS} layer
 * (its {@code use_player_texture} flag degrades to the static {@code minecraft:elytra} skin on a
 * headless render, since there is no wearer skin source). A pack that ships no such asset drops the
 * wings entirely (the no-missing-texture-fallback contract).
 */
@UtilityClass
public class ElytraKit {

    /** The elytra equipment asset id whose {@code equipment/elytra.json} supplies the wing texture. */
    private static final @NotNull ResourceId ELYTRA_ASSET = new ResourceId(ResourceId.DEFAULT_NAMESPACE, "elytra");

    /**
     * The armor material handed to the CIT override seam for the wings. The elytra carries no armor
     * material; the seam dispatches on the {@link LayerType#WINGS} layer type (to {@code type=elytra}
     * rules) and matches by the equipped item's identity, so this argument is a placeholder the walk
     * ignores.
     */
    private static final @NotNull ArmorMaterial CIT_MATERIAL_PLACEHOLDER = ArmorMaterial.LEATHER;

    /**
     * The whole-model back shift of both wings in model pixels - vanilla {@code WingsLayer.submit}
     * applies {@code PoseStack.translate(0, 0, 0.125)} in the entity's block frame before rendering the
     * elytra, which is {@code 0.125 * 16 = 2} pixels in this mesh's native pixel frame. Baked onto each
     * wing bone's pivot z so the wings seat behind the body rather than clipping into the back.
     */
    private static final float WING_BACK_OFFSET = 2f;

    /** The per-axis {@code CubeDeformation(1.0)} the vanilla wings inflate their box by. */
    private static final @NotNull Vector3f WING_INFLATE = new Vector3f(1f, 1f, 1f);

    /** The half-body scale vanilla {@code ElytraModel.BABY_TRANSFORMER} applies for a baby wearer. */
    private static final float BABY_SCALE = 0.5f;

    /** The vanilla humanoid body cube width in model pixels, the per-pixel scale the player frame divides by. */
    private static final float VANILLA_BODY_WIDTH = 8f;

    /** The adult wing mesh at full scale, authored in vanilla's model frame (shoulders at y 0). */
    private static final @NotNull EntityModelData WINGS = buildWingsMesh(false);

    /**
     * The baby wing mesh at half scale (vanilla {@code ElytraModel.BABY_TRANSFORMER} =
     * {@code MeshTransformer.scaling(0.5)}). The vanilla transform re-anchors the shrunk mesh at the
     * feet, but a headless render draws a dedicated baby body mesh whose shoulder height is not the
     * adult feet-anchor value, so {@link #buildWings3D} instead re-seats the baby wings on the rendered
     * body's actual shoulder bounds.
     */
    private static final @NotNull EntityModelData WINGS_BABY = buildWingsMesh(true);

    /**
     * The elytra wing mesh for an age, for the caller's canvas-bounds fold (so a protruding wing does
     * not crop the fitted canvas).
     *
     * @param baby whether to return the half-scale baby mesh
     * @return the shared wing mesh
     */
    static @NotNull EntityModelData wingsMesh(boolean baby) {
        return baby ? WINGS_BABY : WINGS;
    }

    /**
     * The wing mesh seated on the body it hangs from. The adult mesh is authored in vanilla's frame
     * (shoulders at {@code y 0}) so it already seats on an adult body and is returned untouched; a baby
     * draws a dedicated smaller body whose shoulders sit lower, so the half-scale wings drop by the gap
     * between their authored top and the body's actual top (in the Y-down frame the top edge is the
     * minimum y).
     *
     * <p>The seat lands in the MESH rather than on the built triangles so the canvas-bounds walk and the
     * render read one re-seat: sizing happens before any geometry is built, and wings measured where
     * they are authored but drawn lower crop off the bottom of the canvas.
     *
     * @param baby whether to seat the half-scale baby mesh
     * @param bodyBounds the body bone's model-space bounds; empty leaves the wings authored
     * @return the seated mesh, or the authored mesh when no seat applies
     */
    public static @NotNull EntityModelData wingsMesh(boolean baby, @NotNull Optional<Box> bodyBounds) {
        EntityModelData mesh = wingsMesh(baby);
        if (!baby || bodyBounds.isEmpty()) return mesh;
        float dy = bodyBounds.get().minY() - EntityGeometryKit.computeBounds(mesh).minY();
        if (dy == 0f) return mesh;
        ConcurrentLinkedMap<String, EntityModelData.Bone> seated = Concurrent.newLinkedMap();
        // New bones: the authored meshes are shared constants, so the seat must never mutate them.
        mesh.getBones().forEach((name, bone) -> seated.put(name, new EntityModelData.Bone(
            new Vector3f(bone.getPivot().x(), bone.getPivot().y() + dy, bone.getPivot().z()),
            bone.getRotation(), bone.getBindPoseRotation(), bone.getScale(), bone.getCubes(), bone.getParent())));
        return new EntityModelData(mesh.getTextureSize(), mesh.getInventoryYRotation(), seated, mesh.isCull());
    }

    /**
     * Builds the elytra wing triangles for an entity, textured from the data-driven
     * {@code equipment/elytra.json} {@link LayerType#WINGS} layer and fed through the shared entity
     * geometry kit at the caller's fit frame. Empty when the pack ships no elytra asset or its wing
     * texture is absent (no fallback).
     *
     * @param engine the texture engine for pack-aware texture resolution
     * @param baby whether to render the half-scale baby wings
     * @param bodyBounds the body bone's model-space bounds, used to re-seat the baby wings on the
     *     actual shoulder height; empty leaves the wings at their authored position
     * @param modelAnchor the model-space anchor that maps to the canvas centre (the body's fit anchor)
     * @param ndcScale the model-units-to-NDC scale (the body's fit scale)
     * @param modelScale the per-render vertex pre-scale (the body's renderer-scale chain)
     * @param item the equipped elytra item identity, for the pack-rule (CIT) {@code type=elytra} override;
     *     empty leaves the wings on the equipment-model texture
     * @param tick the current animation tick
     * @return the wing triangles, empty when the wings do not resolve
     */
    public static @NotNull ConcurrentList<VisibleTriangle> buildWings3D(
        @NotNull Textures engine, boolean baby, @NotNull Optional<Box> bodyBounds,
        @NotNull Vector3f modelAnchor, float ndcScale, float modelScale, @NotNull Optional<ItemContext> item, int tick
    ) {
        Optional<PixelBuffer> texture = wingsTexture(engine, item, tick);
        if (texture.isEmpty()) return Concurrent.newList();

        return EntityGeometryKit.buildTriangles(wingsMesh(baby, bodyBounds), texture.get(),
            modelAnchor, false, ndcScale, modelScale, ColorMath.WHITE).triangles();
    }

    /**
     * Builds the elytra wing triangles for a player scope, seated behind the torso in the player's
     * normalized model frame. The wings render the {@code playerTexture} when present (the wearer's
     * cape - vanilla's {@code use_player_texture}, so a caped player's elytra shows the cape design) and
     * degrade to the static {@code minecraft:elytra} wing skin otherwise. Empty when neither resolves.
     *
     * <p>The wings are built once in the vanilla entity frame, then each triangle is folded into the
     * player frame ({@code R_X(180)} scaled about the torso's shoulder line, matching how the cape hangs
     * on the {@code -Z} back) and its shade re-baked from the reoriented normal via
     * {@link Lighting#inventory}, so the wings light with the same cardinal-shade model as the body and
     * cape they sit against.
     *
     * @param engine the texture engine for pack-aware texture resolution
     * @param torsoMin the player torso's minimum-corner bounds
     * @param torsoMax the player torso's maximum-corner bounds
     * @param playerTexture the wearer's cape / elytra texture, or empty to use the static elytra skin
     * @param item the equipped elytra item identity, for the pack-rule (CIT) {@code type=elytra} override,
     *     which wins over the wearer texture; empty leaves the wings on the wearer / static texture
     * @param tick the current animation tick
     * @return the wing triangles in the player model frame, empty when the wings do not resolve
     */
    public static @NotNull ConcurrentList<VisibleTriangle> buildPlayerWings3D(
        @NotNull Textures engine, @NotNull Vector3f torsoMin, @NotNull Vector3f torsoMax,
        @NotNull Optional<PixelBuffer> playerTexture, @NotNull Optional<ItemContext> item, int tick
    ) {
        Optional<PixelBuffer> texture = citWingTexture(engine, item, tick)
            .or(() -> playerTexture)
            .or(() -> resolveWingTexture(engine, tick));
        if (texture.isEmpty()) return Concurrent.newList();

        ConcurrentList<VisibleTriangle> wings = EntityGeometryKit.buildTriangles(
            WINGS, texture.get(), Vector3f.ZERO, false, 1f, 1f, ColorMath.WHITE).triangles();

        float scale = (torsoMax.x() - torsoMin.x()) / VANILLA_BODY_WIDTH;
        float centreX = (torsoMin.x() + torsoMax.x()) * 0.5f;
        float centreZ = (torsoMin.z() + torsoMax.z()) * 0.5f;
        float shoulderY = torsoMax.y();

        ConcurrentList<VisibleTriangle> out = Concurrent.newList();
        for (VisibleTriangle t : wings) {
            Vector3f normal = Turn.HALF_X.apply(t.normal()).normalize();
            float shade = Lighting.inventory(normal);
            out.add(new VisibleTriangle(
                toPlayerFrame(t.position0(), scale, centreX, shoulderY, centreZ),
                toPlayerFrame(t.position1(), scale, centreX, shoulderY, centreZ),
                toPlayerFrame(t.position2(), scale, centreX, shoulderY, centreZ),
                t.uv0(), t.uv1(), t.uv2(), t.texture(), t.tintArgb(), normal, shade, t.traits(), t.debugTag()));
        }
        return out;
    }

    /**
     * Folds a vanilla-frame wing vertex into the player model frame: the vanilla model (Y-down, back at
     * {@code +Z}) is flipped through {@code R_X(180)}, scaled to the torso's per-pixel size, and anchored
     * at the shoulder line (torso top centre), so the wings hang on the {@code -Z} back like the cape.
     */
    private static @NotNull Vector3f toPlayerFrame(
        @NotNull Vector3f v, float scale, float centreX, float shoulderY, float centreZ) {
        return new Vector3f(centreX + v.x() * scale, shoulderY - v.y() * scale, centreZ - v.z() * scale);
    }

    /**
     * The texture the wings draw with: the pack-rule (CIT) {@code type=elytra} override when an item
     * supplies a matching one, else the equipment model's own {@link LayerType#WINGS} layer. Empty when
     * the pack ships no wing texture at all, in which case the wings render nothing.
     *
     * <p>Public so a caller sizing a canvas measures the wings by the same texture they draw with,
     * rather than by their mesh - the wing box is largely transparent, and wings that do not resolve
     * must not bound a render they never appear in.
     *
     * @param engine the texture engine for pack-aware texture resolution
     * @param item the equipped elytra item identity, for the pack-rule override; empty leaves the wings
     *     on the equipment-model texture
     * @param tick the current animation tick
     * @return the wing texture, or empty when the wings do not resolve
     */
    public static @NotNull Optional<PixelBuffer> wingsTexture(
        @NotNull Textures engine, @NotNull Optional<ItemContext> item, int tick) {
        return citWingTexture(engine, item, tick).or(() -> resolveWingTexture(engine, tick));
    }

    /** Resolves the elytra wing texture from the {@code equipment/elytra.json} {@link LayerType#WINGS} layer. */
    private static @NotNull Optional<PixelBuffer> resolveWingTexture(@NotNull Textures engine, int tick) {
        List<EquipmentModel.Layer> layers = engine.getContext().resolveEquipmentLayers(ELYTRA_ASSET, LayerType.WINGS);
        if (layers.isEmpty()) return Optional.empty();
        return engine.tryResolveTextureAtTick(layers.getFirst().textureLocation(LayerType.WINGS).id(), tick);
    }

    /**
     * Resolves the pack-rule (CIT) {@code type=elytra} wing override for an equipped item, or empty when
     * no item is supplied or no rule matches. Dormant on a vanilla stack (no {@code optifine/} tree) and
     * whenever the caller passes no item, so the wings keep their equipment-model / wearer texture.
     */
    private static @NotNull Optional<PixelBuffer> citWingTexture(
        @NotNull Textures engine, @NotNull Optional<ItemContext> item, int tick) {
        return item
            .map(context -> engine.getContext().resolveArmorTextureOverride(CIT_MATERIAL_PLACEHOLDER, LayerType.WINGS, context))
            .flatMap(cit -> cit.textureFor("layer0"))
            .flatMap(id -> engine.tryResolveTextureAtTick(id.id(), tick));
    }

    /**
     * Builds the two-bone wing mesh, transcribed from {@code ElytraModel.createLayer}: left wing box
     * origin {@code (-10,0,0)} size {@code (10,20,2)} texOffs {@code (22,0)} at pivot {@code (5,0,2)}
     * rotated {@code (15,0,-15)}, right wing mirrored. A baby carries the {@code 0.5} per-vertex scale
     * (vanilla {@code BABY_TRANSFORMER}) with its pivot offsets halved to match; the vanilla feet-anchor
     * re-seat is applied at render against the actual body bounds ({@link #buildWings3D}).
     */
    private static @NotNull EntityModelData buildWingsMesh(boolean baby) {
        float scale = baby ? BABY_SCALE : 1f;
        ConcurrentLinkedMap<String, EntityModelData.Bone> bones = Concurrent.newLinkedMap();
        bones.put("left_wing", wingBone(
            wingPivot(5f, scale),
            new EulerRotation(15f, 0f, -15f),
            scale,
            wingCube(new Vector3f(-10f, 0f, 0f), false)
        ));
        bones.put("right_wing", wingBone(
            wingPivot(-5f, scale),
            new EulerRotation(15f, 0f, 15f),
            scale,
            wingCube(new Vector3f(0f, 0f, 0f), true)
        ));
        return new EntityModelData(new TextureSize(64, 32), 0f, bones, false);
    }

    /**
     * The wing pivot: the createLayer pivot {@code (x, 0, WING_BACK_OFFSET)} with its X and Z offsets
     * scaled by {@code scale} (Y stays at the shoulder line), so an adult ({@code scale == 1}) keeps its
     * exact createLayer pivot and a baby's pivots shrink toward the body centre.
     */
    private static @NotNull Vector3f wingPivot(float x, float scale) {
        return new Vector3f(x * scale, 0f, WING_BACK_OFFSET * scale);
    }

    /** A wing bone owning one cube, at the given pivot, rotation, and per-vertex scale. */
    private static @NotNull EntityModelData.Bone wingBone(
        @NotNull Vector3f pivot, @NotNull EulerRotation rotation, float scale, @NotNull EntityModelData.Cube cube) {
        ConcurrentList<EntityModelData.Cube> cubes = Concurrent.newList();
        cubes.add(cube);
        return new EntityModelData.Bone(pivot, rotation, EulerRotation.NONE, scale, cubes, null);
    }

    /** A wing cube of size {@code 10x20x2} at the given origin, texOffs {@code (22,0)}, inflated {@code 1.0}. */
    private static @NotNull EntityModelData.Cube wingCube(@NotNull Vector3f origin, boolean mirror) {
        return new EntityModelData.Cube(
            origin,
            new Vector3f(10f, 20f, 2f),
            new Vector2f(22f, 0f),
            WING_INFLATE,
            mirror,
            Vector3f.ZERO,
            EulerRotation.NONE,
            Concurrent.newMap()
        );
    }

}
