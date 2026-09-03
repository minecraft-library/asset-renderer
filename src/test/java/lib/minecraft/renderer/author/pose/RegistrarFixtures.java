package lib.minecraft.renderer.author.pose;

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
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Optional;

/**
 * Hand-built rows the registrar tests install onto - entities carrying catalogs and overlay
 * passes beside the compiler fixtures' meshes and poses.
 */
final class RegistrarFixtures {

    private RegistrarFixtures() {}

    /**
     * A target row over a mesh, its shipped pose, a catalog and any overlay passes.
     */
    static @NotNull Entity entity(@NotNull String id, @NotNull EntityModelData mesh,
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
    static @NotNull Entity.OverlayLayer overlay(@NotNull EntityModelData mesh, @NotNull EntityPose pose) {
        return new Entity.OverlayLayer(mesh, Optional.empty(), PassDeclaration.DEFAULT,
            0xFFFFFFFF, false, Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), pose);
    }

    /**
     * A mutable definitions map over the given rows, keyed by their own ids.
     */
    static @NotNull ConcurrentLinkedMap<String, Entity> definitions(Entity @NotNull ... rows) {
        ConcurrentLinkedMap<String, Entity> map = Concurrent.newLinkedMap();
        for (Entity row : rows)
            map.put(row.id().toString(), row);
        return map;
    }

    /**
     * A catalog at the shipped period carrying the given rows in order.
     */
    static @NotNull StyleCatalog catalog(PoseStyle @NotNull ... rows) {
        return new StyleCatalog(24, Concurrent.newUnmodifiableList(rows));
    }

    /**
     * A flat style row of one id over the given drivers, ageless and untoggled.
     */
    static @NotNull PoseStyle styleRow(@NotNull String id, @NotNull Map<String, StyleDriver> drivers) {
        return new PoseStyle(id, Concurrent.newUnmodifiableList(),
            Concurrent.newUnmodifiableMap(drivers), Concurrent.newUnmodifiableList(), Optional.empty(),
            Optional.empty());
    }

}
