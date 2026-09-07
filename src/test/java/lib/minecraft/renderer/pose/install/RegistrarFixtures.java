package lib.minecraft.renderer.pose.install;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentLinkedMap;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.asset.pose.EntityPose;
import lib.minecraft.renderer.asset.pose.PoseStyle;
import lib.minecraft.renderer.asset.pose.StyleCatalog;
import lib.minecraft.renderer.asset.pose.StyleDriver;
import lib.minecraft.renderer.engine.raster.PassDeclaration;
import lib.minecraft.renderer.pose.author.BuiltStyle;
import lib.minecraft.renderer.pose.author.CustomPose;
import lib.minecraft.renderer.pose.author.Poses;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Optional;

/**
 * Hand-built rows the registrar tests install onto - entities carrying catalogs and overlay
 * passes beside the compiler fixtures' meshes and poses - and vanilla's own silhouettes spelled
 * as styles, for laying a chain against what the client draws.
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
     * A target row over a mesh, its shipped pose, a catalog and any overlay passes.
     */
    public static @NotNull Entity entity(@NotNull String id, @NotNull EntityModelData mesh,
                                  @NotNull EntityPose pose, @NotNull StyleCatalog styles,
                                  Entity.OverlayLayer @NotNull ... overlays) {
        return Entity.builder()
            .id(ResourceId.parse(id))
            .model(mesh)
            .pose(pose)
            .styles(styles)
            .overlays(Concurrent.newUnmodifiableList(overlays))
            .build();
    }

    /**
     * One overlay pass over a mesh and the pose row it evaluates.
     */
    public static @NotNull Entity.OverlayLayer overlay(@NotNull EntityModelData mesh, @NotNull EntityPose pose) {
        return new Entity.OverlayLayer(mesh, Optional.empty(), PassDeclaration.DEFAULT,
            0xFFFFFFFF, false, Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), pose);
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
        return new StyleCatalog(24, Concurrent.newUnmodifiableList(rows));
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
