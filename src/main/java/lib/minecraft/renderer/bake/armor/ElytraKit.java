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
import lib.minecraft.renderer.asset.pack.Flipbook;
import lib.minecraft.renderer.bake.mesh.EntityGeometryKit;
import lib.minecraft.renderer.engine.camera.FitFrame;
import lib.minecraft.renderer.engine.draw.PassDeclaration;
import lib.minecraft.renderer.engine.draw.VisibleTriangle;
import lib.minecraft.renderer.engine.geometry.AxisSigns;
import lib.minecraft.renderer.engine.geometry.Box;
import lib.minecraft.renderer.math.Vector3f;
import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.port.RendererContext;
import lib.minecraft.renderer.request.ItemContext;
import lib.minecraft.renderer.vanilla.equipment.ArmorMaterial;
import lib.minecraft.renderer.vanilla.equipment.LayerType;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import lib.minecraft.renderer.vanilla.mesh.ElytraMesh;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Bakes the elytra wings onto a wearer. The {@link ElytraMesh} bones are seated on the body they hang
 * from and fed through {@link EntityGeometryKit#buildTriangles} - the same path the entity equipment
 * overlay uses - so no kit change or new schema is needed.
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

    /** The elytra equipment asset id whose {@code equipment/elytra.json} supplies the wing texture. */
    private static final @NotNull ResourceId ELYTRA_ASSET = new ResourceId(ResourceId.DEFAULT_NAMESPACE, "elytra");

    /**
     * The armor material handed to the CIT override seam for the wings. The elytra carries no armor
     * material; the seam dispatches on the {@link LayerType#WINGS} layer type (to {@code type=elytra}
     * rules) and matches by the equipped item's identity, so this argument is a placeholder the walk
     * ignores.
     */
    private static final @NotNull ArmorMaterial CIT_MATERIAL_PLACEHOLDER = ArmorMaterial.LEATHER;

    /** The vanilla humanoid body cube width in model pixels, the per-pixel scale the player frame divides by. */
    private static final float VANILLA_BODY_WIDTH = 8f;

    /**
     * The elytra wing mesh for an age, for the caller's canvas-bounds fold (so a protruding wing does
     * not crop the fitted canvas).
     *
     * @param baby whether to return the half-scale baby mesh
     * @return the shared wing mesh
     */
    static @NotNull EntityMesh wingsMesh(boolean baby) {
        return baby ? ElytraMesh.WINGS_BABY : ElytraMesh.WINGS;
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
    public static @NotNull EntityMesh wingsMesh(boolean baby, @NotNull Optional<Box> bodyBounds) {
        EntityMesh mesh = wingsMesh(baby);
        if (!baby || bodyBounds.isEmpty()) return mesh;
        float dy = bodyBounds.get().minY() - EntityGeometryKit.computeBounds(mesh).minY();
        if (dy == 0f) return mesh;
        // New bones: the authored meshes are shared constants, so the seat must never mutate them.
        ConcurrentLinkedMap<String, EntityMesh.Bone> seated = mesh.getBones()
            .entrySet()
            .stream()
            .collect(Concurrent.toLinkedMap(Map.Entry::getKey, entry -> {
                Vector3f pivot = entry.getValue().getPivot();
                return entry.getValue()
                    .withPivot(new Vector3f(pivot.x(), pivot.y() + dy, pivot.z()));
            }));
        return new EntityMesh(mesh.getTextureSize(), seated, mesh.isCull());
    }

    /**
     * Builds the elytra wing triangles for an entity, textured from the data-driven
     * {@code equipment/elytra.json} {@link LayerType#WINGS} layer and fed through the shared entity
     * geometry kit at the caller's fit frame. Empty when the pack ships no elytra asset or its wing
     * texture is absent (no fallback).
     *
     * @param context the texture context for pack-aware texture resolution
     * @param baby whether to render the half-scale baby wings
     * @param bodyBounds the body bone's model-space bounds, used to re-seat the baby wings on the
     *     actual shoulder height; empty leaves the wings at their authored position
     * @param frame the render frame the body's own geometry was built through
     * @param item the equipped elytra item identity, for the pack-rule (CIT) {@code type=elytra} override;
     *     empty leaves the wings on the equipment-model texture
     * @param tick the current animation tick
     * @return the wing triangles, empty when the wings do not resolve
     */
    public static @NotNull ConcurrentList<VisibleTriangle> buildWings3D(
        @NotNull RendererContext context, boolean baby, @NotNull Optional<Box> bodyBounds,
        @NotNull FitFrame frame, @NotNull Optional<ItemContext> item, int tick
    ) {
        Optional<PixelBuffer> texture = wingsTexture(context, item, tick);
        if (texture.isEmpty()) return Concurrent.newList();

        return EntityGeometryKit.buildTriangles(wingsMesh(baby, bodyBounds), texture.get(),
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

        ConcurrentList<VisibleTriangle> wings = EntityGeometryKit.buildTriangles(ElytraMesh.WINGS, texture.get(),
            new EntityGeometryKit.EntityBuildParams(
                FitFrame.IDENTITY, PassDeclaration.DEFAULT, ColorMath.WHITE)).triangles();

        float scale = (torsoMax.x() - torsoMin.x()) / VANILLA_BODY_WIDTH;
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
     * The texture the wings draw with: the pack-rule (CIT) {@code type=elytra} override when an item
     * supplies a matching one, else the equipment model's own {@link LayerType#WINGS} layer. Empty when
     * the pack ships no wing texture at all, in which case the wings render nothing.
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

    /** Resolves the elytra wing texture from the {@code equipment/elytra.json} {@link LayerType#WINGS} layer. */
    private static @NotNull Optional<PixelBuffer> resolveWingTexture(@NotNull RendererContext context, int tick) {
        List<EquipmentModel.Layer> layers = context.resolveEquipmentLayers(ELYTRA_ASSET, LayerType.WINGS);
        if (layers.isEmpty()) return Optional.empty();
        String textureId = layers.getFirst().textureLocation(LayerType.WINGS).id();
        return Flipbook.atTick(context.resolveTexture(textureId), context.findFlipbook(textureId), tick);
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
            .flatMap(cit -> cit.textureFor("layer0"))
            .flatMap(id -> Flipbook.atTick(context.resolveTexture(id.id()), context.findFlipbook(id.id()), tick));
    }

}
