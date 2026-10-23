package lib.minecraft.renderer.fixture;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentLinkedMap;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.mesh.EntityMesh;
import lib.minecraft.renderer.asset.pose.EntityPose;
import lib.minecraft.renderer.asset.pose.PoseStyle;
import lib.minecraft.renderer.asset.pose.StyleCatalog;
import lib.minecraft.renderer.author.BuiltStyle;
import lib.minecraft.renderer.author.CustomPose;
import lib.minecraft.renderer.author.Poses;
import lib.minecraft.renderer.engine.draw.PassDeclaration;
import lib.minecraft.renderer.engine.math.Vector2f;
import lib.minecraft.renderer.engine.pose.StyleDriver;
import lib.minecraft.renderer.vanilla.appearance.AppearanceGate;
import lib.minecraft.renderer.vanilla.appearance.TextureAxis;
import lib.minecraft.renderer.vanilla.appearance.TintAxis;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Optional;

/**
 * Hand-built rows the registrar tests install onto - entities carrying catalogs and overlay
 * passes beside the compiler fixtures' meshes and poses - and vanilla's own silhouettes spelled
 * as styles, for laying a chain against what the client draws. The pass factories are the
 * spellings every hand-built overlay pass takes, each defaulting the members a pass's render type
 * adds to none.
 */
public final class RegistrarFixtures {

    private RegistrarFixtures() {}

    /**
     * Vanilla's own resting silhouette of one state branch, spelled as a statue through the raw
     * hatch - every channel the branch places away from the resting row spliced whole - so a
     * chain can be laid bone for bone against what the client draws for the same stance.
     *
     * @param row the shipped row whose pose carries the silhouette
     * @param state the silhouette key, as {@code member=value}
     * @param styleId the style id the statue builds under
     * @return the built statue
     * @throws IllegalArgumentException if the row carries no silhouette under the key
     */
    public static @NotNull BuiltStyle silhouette(@NotNull Entity row, @NotNull String state, @NotNull String styleId) {
        EntityPose.Silhouette silhouette = row.pose().states().get(state);
        if (silhouette == null)
            throw new IllegalArgumentException("Row '" + row.id() + "' carries no silhouette for '" + state + "'");
        CustomPose.Builder builder = Poses.custom(styleId);
        silhouette.bones().forEach((bone, channels) ->
            channels.forEach((channel, expr) -> builder.expr(bone, channel, expr)));
        return builder.build();
    }

    /**
     * A target row over a mesh, its shipped pose, a catalog and any overlay passes, carrying no
     * option axis.
     */
    public static @NotNull Entity entity(@NotNull String id, @NotNull EntityMesh mesh,
                                  @NotNull EntityPose pose, @NotNull StyleCatalog styles,
                                  Entity.OverlayLayer @NotNull ... overlays) {
        return Entity.builder()
            .id(ResourceId.parse(id))
            .model(mesh)
            .pose(pose)
            .styles(styles)
            .overlays(Concurrent.newUnmodifiableList(overlays))
            .axes(CompilerFixtures.noAxes())
            .build();
    }

    /**
     * One overlay pass over a mesh and the pose row it evaluates.
     */
    public static @NotNull Entity.OverlayLayer overlay(@NotNull EntityMesh mesh, @NotNull EntityPose pose) {
        return pass(mesh, Optional.empty(), PassDeclaration.DEFAULT, 0xFFFFFFFF, false, Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty(), pose);
    }

    /**
     * One overlay pass whose render type translates its texture by nothing and whose opacity is its
     * declaration's constant one.
     */
    public static @NotNull Entity.OverlayLayer pass(@NotNull EntityMesh model, @NotNull Optional<String> textureRef,
                                                    @NotNull PassDeclaration declaration, int tintArgb,
                                                    boolean skipBounds, @NotNull Optional<TintAxis> tintBy,
                                                    @NotNull Optional<TextureAxis> textureBy,
                                                    @NotNull Optional<AppearanceGate> gate,
                                                    @NotNull Optional<EntityMesh> noHatModel,
                                                    @NotNull EntityPose pose) {
        return pass(model, textureRef, declaration, tintArgb, skipBounds, tintBy, textureBy, gate, noHatModel,
            pose, Optional.empty());
    }

    /**
     * One overlay pass whose opacity is its declaration's constant one.
     */
    public static @NotNull Entity.OverlayLayer pass(@NotNull EntityMesh model, @NotNull Optional<String> textureRef,
                                                    @NotNull PassDeclaration declaration, int tintArgb,
                                                    boolean skipBounds, @NotNull Optional<TintAxis> tintBy,
                                                    @NotNull Optional<TextureAxis> textureBy,
                                                    @NotNull Optional<AppearanceGate> gate,
                                                    @NotNull Optional<EntityMesh> noHatModel,
                                                    @NotNull EntityPose pose,
                                                    @NotNull Optional<Vector2f> textureScroll) {
        return new Entity.OverlayLayer(model, textureRef, declaration, tintArgb, skipBounds, tintBy, textureBy,
            gate, noHatModel, pose, textureScroll, Optional.empty());
    }

    /**
     * A mutable definitions map over the given rows, keyed by their own ids.
     */
    public static @NotNull ConcurrentLinkedMap<String, Entity> definitions(Entity @NotNull ... rows) {
        ConcurrentLinkedMap<String, Entity> map = Concurrent.newLinkedMap();
        for (Entity row : rows)
            map.put(row.id().toString(), row);
        return map;
    }

    /**
     * A catalog at the shipped period carrying the given rows in order.
     */
    public static @NotNull StyleCatalog catalog(PoseStyle @NotNull ... rows) {
        return new StyleCatalog(24, Concurrent.newUnmodifiableMap(), Concurrent.newUnmodifiableList(rows));
    }

    /**
     * A flat style row of one id over the given drivers, ageless and untoggled.
     */
    public static @NotNull PoseStyle styleRow(@NotNull String id, @NotNull Map<String, StyleDriver> drivers) {
        return new PoseStyle(id, Concurrent.newUnmodifiableList(),
            Concurrent.newUnmodifiableMap(drivers), Concurrent.newUnmodifiableList(), Optional.empty(),
            Optional.empty());
    }

}
