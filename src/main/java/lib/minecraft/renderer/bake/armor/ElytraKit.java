package lib.minecraft.renderer.bake.armor;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentLinkedMap;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.PlayerRenderer;
import lib.minecraft.renderer.asset.equipment.EquipmentModel;
import lib.minecraft.renderer.asset.mesh.EntityMesh;
import lib.minecraft.renderer.asset.mesh.TextureSize;
import lib.minecraft.renderer.asset.pack.Flipbook;
import lib.minecraft.renderer.bake.mesh.EntityGeometryKit;
import lib.minecraft.renderer.content.index.RendererContext;
import lib.minecraft.renderer.engine.camera.FitFrame;
import lib.minecraft.renderer.engine.draw.PassDeclaration;
import lib.minecraft.renderer.engine.draw.VisibleTriangle;
import lib.minecraft.renderer.engine.geometry.AxisSigns;
import lib.minecraft.renderer.engine.geometry.EulerRotation;
import lib.minecraft.renderer.engine.math.Vector3f;
import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.request.ItemContext;
import lib.minecraft.renderer.vanilla.equipment.ArmorMaterial;
import lib.minecraft.renderer.vanilla.equipment.LayerType;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import lib.minecraft.renderer.vanilla.mesh.ElytraMesh;
import lib.minecraft.renderer.vanilla.mesh.HumanoidPart;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;

/**
 * Bakes the elytra wings onto a wearer. The wing bones are assembled once per wearer age from
 * {@link ElytraMesh}'s transcription of vanilla's elytra model, posed the way vanilla poses them for
 * that age whichever body wears them, and fed through {@link EntityGeometryKit#buildTriangles} - the
 * same path the entity equipment overlay uses - so no kit change or new schema is needed.
 * <p>
 * The wing texture is the data-driven {@code equipment/elytra.json} {@link LayerType#WINGS} layer
 * (its {@code use_player_texture} flag degrades to the static {@code minecraft:elytra} skin on a
 * headless render, since there is no wearer skin source). A pack that ships no such asset drops the
 * wings entirely (the no-missing-texture-fallback contract).
 */
@Parity(as = PlayerRenderer.class)
@UtilityClass
@Parity(claim = "engine-renders", mode = Mode.DEMOTE)
public class ElytraKit {

    /**
     * The elytra equipment asset id whose {@code equipment/elytra.json} supplies the wing texture.
     */
    private static final @NotNull ResourceId ELYTRA_ASSET = new ResourceId(ResourceId.DEFAULT_NAMESPACE, "elytra");

    /**
     * The armor material handed to the CIT override seam for the wings. The elytra carries no armor
     * material; the seam dispatches on the {@link LayerType#WINGS} layer type (to {@code type=elytra}
     * rules) and matches by the equipped item's identity, so this argument is a placeholder the walk
     * ignores.
     */
    private static final @NotNull ArmorMaterial CIT_MATERIAL_PLACEHOLDER = ArmorMaterial.LEATHER;

    /**
     * The adult wing mesh at full scale, authored in vanilla's model frame (shoulders at y 0).
     */
    private static final @NotNull EntityMesh WINGS = buildWingsMesh(false);

    /**
     * The baby wing mesh - the adult wings under vanilla's {@code ElytraModel.BABY_TRANSFORMER},
     * {@code MeshTransformer.scaling(0.5)}, which scales the model's root by half about
     * {@link EntityMesh#FEET_ANCHOR the feet anchor}. Nothing on that path reads the wearer's body, so
     * the baby wings hang at the same place on every baby that wears them.
     */
    private static final @NotNull EntityMesh WINGS_BABY = buildWingsMesh(true);

    /**
     * The elytra wing mesh for an age - the mesh a render draws, and so the one a caller's
     * canvas-bounds fold measures, so a protruding wing does not crop the fitted canvas.
     *
     * @param baby whether to return the half-scale baby mesh
     * @return the shared wing mesh
     */
    public static @NotNull EntityMesh wingsMesh(boolean baby) {
        return baby ? WINGS_BABY : WINGS;
    }

    /**
     * Builds the elytra wing triangles for an entity, textured from the data-driven
     * {@code equipment/elytra.json} {@link LayerType#WINGS} layer and fed through the shared entity
     * geometry kit at the caller's fit frame. Empty when the pack ships no elytra asset or its wing
     * texture is absent (no fallback).
     *
     * @param context the texture context for pack-aware texture resolution
     * @param baby whether to render the half-scale baby wings
     * @param frame the render frame the body's own geometry was built through
     * @param item the equipped elytra item identity, for the pack-rule (CIT) {@code type=elytra} override;
     *     empty leaves the wings on the equipment-model texture
     * @param tick the current animation tick
     * @return the wing triangles, empty when the wings do not resolve
     */
    public static @NotNull ConcurrentList<VisibleTriangle> buildWings3D(
        @NotNull RendererContext context, boolean baby, @NotNull FitFrame frame,
        @NotNull Optional<ItemContext> item, int tick
    ) {
        Optional<PixelBuffer> texture = wingsTexture(context, item, tick);
        if (texture.isEmpty()) return Concurrent.newList();

        return EntityGeometryKit.buildTriangles(wingsMesh(baby), texture.get(),
            new EntityGeometryKit.EntityBuildParams(frame, PassDeclaration.DEFAULT, ColorMath.WHITE)).triangles();
    }

    /**
     * Builds the elytra wing triangles for a player scope, seated behind the torso in the player's
     * normalized model frame. The wings render the {@code playerTexture} when present (the wearer's
     * cape - vanilla's {@code use_player_texture}, so a caped player's elytra shows the cape design) and
     * degrade to the static {@code minecraft:elytra} wing skin otherwise. Empty when neither resolves.
     *
     * <p>The wings are built once in the vanilla entity frame, then each triangle is folded into the
     * player frame ({@code R_X(180)} scaled about the torso's shoulder line, matching how the cape hangs
     * on the {@code -Z} back), carrying the reoriented normal so the scope's own relight reads the wings
     * in the frame the body and cape they sit against are read in.
     *
     * @param context the texture context for pack-aware texture resolution
     * @param torsoMin the player torso's minimum-corner bounds
     * @param torsoMax the player torso's maximum-corner bounds
     * @param playerTexture the wearer's cape / elytra texture, or empty to use the static elytra skin
     * @param item the equipped elytra item identity, for the pack-rule (CIT) {@code type=elytra} override,
     *     which wins over the wearer texture; empty leaves the wings on the wearer / static texture
     * @param tick the current animation tick
     * @return the wing triangles in the player model frame, empty when the wings do not resolve
     */
    public static @NotNull ConcurrentList<VisibleTriangle> buildPlayerWings3D(
        @NotNull RendererContext context, @NotNull Vector3f torsoMin, @NotNull Vector3f torsoMax,
        @NotNull Optional<PixelBuffer> playerTexture, @NotNull Optional<ItemContext> item, int tick
    ) {
        Optional<PixelBuffer> texture = citWingTexture(context, item, tick)
            .or(() -> playerTexture)
            .or(() -> resolveWingTexture(context, tick));
        if (texture.isEmpty()) return Concurrent.newList();

        ConcurrentList<VisibleTriangle> wings = EntityGeometryKit.buildTriangles(WINGS, texture.get(),
            new EntityGeometryKit.EntityBuildParams(
                FitFrame.IDENTITY, PassDeclaration.DEFAULT, ColorMath.WHITE)).triangles();

        float scale = (torsoMax.x() - torsoMin.x()) / HumanoidPart.TORSO.pixelWidth();
        float centreX = (torsoMin.x() + torsoMax.x()) * 0.5f;
        float centreZ = (torsoMin.z() + torsoMax.z()) * 0.5f;
        float shoulderY = torsoMax.y();

        ConcurrentList<VisibleTriangle> out = Concurrent.newList();
        for (VisibleTriangle t : wings) {
            Vector3f normal = AxisSigns.HALF_X.apply(t.normal()).normalize();
            out.add(new VisibleTriangle(
                toPlayerFrame(t.position0(), scale, centreX, shoulderY, centreZ),
                toPlayerFrame(t.position1(), scale, centreX, shoulderY, centreZ),
                toPlayerFrame(t.position2(), scale, centreX, shoulderY, centreZ),
                t.uv0(), t.uv1(), t.uv2(), t.texture(), t.tintArgb(), normal, t.shading(), t.traits(), t.debugTag()));
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
     * Builds the two-bone wing mesh from {@link ElytraMesh}'s transcription of
     * {@code ElytraModel.createLayer}: each wing's box at its createLayer pivot and rotation, left wing
     * first. A baby carries the {@link ElytraMesh#BABY_SCALE} per-vertex scale, with each pivot taken
     * through the same feet-anchored transform (vanilla {@code BABY_TRANSFORMER}).
     */
    private static @NotNull EntityMesh buildWingsMesh(boolean baby) {
        float scale = baby ? ElytraMesh.BABY_SCALE : 1f;
        ConcurrentLinkedMap<String, EntityMesh.Bone> bones = Concurrent.newLinkedMap();
        for (ElytraMesh.Wing wing : List.of(ElytraMesh.LEFT, ElytraMesh.RIGHT)) {
            bones.put(wing.bone(), wingBone(
                wingPivot(wing.pivotX(), scale),
                wing.rotation(),
                scale,
                wingCube(wing.origin(), wing.mirror())
            ));
        }
        return new EntityMesh(new TextureSize(ElytraMesh.TEXTURE_WIDTH, ElytraMesh.TEXTURE_HEIGHT), bones, false);
    }

    /**
     * The wing pivot: createLayer's {@code (x, 0, 0)} taken through the model root's transform, then
     * {@link ElytraMesh#BACK_OFFSET}. {@code MeshTransformer.scaling} scales the root by {@code scale}
     * about the feet anchor, which scales {@code x} and lands {@code y} on
     * {@link EntityMesh#flattenedShift}; the back shift is {@code WingsLayer}'s translate, outside the
     * root, so it is whole at every scale. An adult ({@code scale == 1}) keeps
     * {@code (x, 0, BACK_OFFSET)} exactly.
     */
    private static @NotNull Vector3f wingPivot(float x, float scale) {
        return new Vector3f(x * scale, EntityMesh.flattenedShift(scale), ElytraMesh.BACK_OFFSET);
    }

    /**
     * A wing bone owning one cube, at the given pivot, rotation, and per-vertex scale.
     */
    private static @NotNull EntityMesh.Bone wingBone(
        @NotNull Vector3f pivot, @NotNull EulerRotation rotation, float scale, @NotNull EntityMesh.Cube cube) {
        ConcurrentList<EntityMesh.Cube> cubes = Concurrent.newList();
        cubes.add(cube);
        return new EntityMesh.Bone(pivot, rotation, EulerRotation.NONE, scale, cubes, null);
    }

    /**
     * A wing cube of size {@link ElytraMesh#BOX_SIZE} at the given origin, cut at
     * {@link ElytraMesh#TEX_OFFSET} and inflated by {@link ElytraMesh#INFLATE}.
     */
    private static @NotNull EntityMesh.Cube wingCube(@NotNull Vector3f origin, boolean mirror) {
        return new EntityMesh.Cube(
            origin,
            ElytraMesh.BOX_SIZE,
            ElytraMesh.TEX_OFFSET,
            ElytraMesh.INFLATE,
            mirror,
            Vector3f.ZERO,
            EulerRotation.NONE,
            Concurrent.newMap()
        );
    }

    /**
     * The texture the wings draw with: the pack-rule (CIT) {@code type=elytra} override when an item
     * supplies a matching one, else the equipment model's own {@link LayerType#WINGS} layer. Empty when
     * the pack ships no wing texture at all, or ships one that cannot be decoded, in which case the
     * wings render nothing.
     *
     * <p>Public so a caller sizing a canvas measures the wings by the same texture they draw with,
     * rather than by their mesh - the wing box is largely transparent, and wings that do not resolve
     * must not bound a render they never appear in.
     *
     * @param context the texture context for pack-aware texture resolution
     * @param item the equipped elytra item identity, for the pack-rule override; empty leaves the wings
     *     on the equipment-model texture
     * @param tick the current animation tick
     * @return the wing texture, or empty when the wings do not resolve
     */
    public static @NotNull Optional<PixelBuffer> wingsTexture(
        @NotNull RendererContext context, @NotNull Optional<ItemContext> item, int tick) {
        return citWingTexture(context, item, tick).or(() -> resolveWingTexture(context, tick));
    }

    /**
     * Resolves the elytra wing texture from the {@code equipment/elytra.json} {@link LayerType#WINGS} layer.
     */
    private static @NotNull Optional<PixelBuffer> resolveWingTexture(@NotNull RendererContext context, int tick) {
        List<EquipmentModel.Layer> layers = context.resolveEquipmentLayers(ELYTRA_ASSET, LayerType.WINGS);
        if (layers.isEmpty()) return Optional.empty();
        String textureId = layers.getFirst().textureLocation(LayerType.WINGS).id();
        return Flipbook.atTick(context.resolveTexture(textureId), context.findFlipbook(textureId), tick).toOptional();
    }

    /**
     * Resolves the pack-rule (CIT) {@code type=elytra} wing override for an equipped item, or empty when
     * no item is supplied or no rule matches. Dormant on a vanilla stack (no {@code optifine/} tree) and
     * whenever the caller passes no item, so the wings keep their equipment-model / wearer texture.
     */
    private static @NotNull Optional<PixelBuffer> citWingTexture(
        @NotNull RendererContext context, @NotNull Optional<ItemContext> item, int tick) {
        return item
            .map(itemContext -> context.resolveArmorTextureOverride(CIT_MATERIAL_PLACEHOLDER, LayerType.WINGS, itemContext))
            .flatMap(cit -> cit.textureFor("layer0").toOptional())
            .flatMap(id -> Flipbook.atTick(context.resolveTexture(id.id()), context.findFlipbook(id.id()), tick).toOptional());
    }

}
