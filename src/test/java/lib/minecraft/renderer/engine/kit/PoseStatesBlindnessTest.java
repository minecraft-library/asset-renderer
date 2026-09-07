package lib.minecraft.renderer.engine.kit;

import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.asset.pose.EntityPose;
import lib.minecraft.renderer.asset.pose.PoseStyle;
import lib.minecraft.renderer.asset.pose.StyleCatalog;
import lib.minecraft.renderer.exception.RendererException;
import lib.minecraft.renderer.option.EntityOptions;
import lib.minecraft.renderer.pipeline.loader.EntityModelLoader;
import lib.minecraft.renderer.pose.PoseChannel;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The runtime is blind to a pose row's state silhouettes: every mesh of every shipped row - the
 * body, each overlay pass, the baby form - poses bit-for-bit the same under every catalog style
 * whether or not its pose carries them. What the member says is read by pose authoring alone.
 */
@DisplayName("the render path never reads a state silhouette")
class PoseStatesBlindnessTest {

    /**
     * The eight strip ticks an animated schedule samples.
     */
    private static final int[] STRIP_TICKS = {0, 3, 6, 9, 12, 15, 18, 21};

    @Test
    @DisplayName("every shipped mesh poses identically with its silhouettes stripped, under every style")
    void everyShippedMeshPosesIdenticallyWithoutSilhouettes() {
        ConcurrentMap<String, Entity> definitions = EntityModelLoader.load();
        assumeTrue(!definitions.isEmpty(), "bundled entity tables are present");

        int carrying = 0;
        int compared = 0;
        for (Entity row : definitions.values()) {
            StyleCatalog catalog = row.styles();
            List<PoseStyle> styles = stylesOf(row);
            for (Map.Entry<EntityModelData, EntityPose> mesh : meshesOf(row).entrySet()) {
                EntityPose shipped = mesh.getValue();
                if (!shipped.states().isEmpty()) carrying++;
                EntityPose stripped = new EntityPose(shipped.container(), shipped.bones(),
                    shipped.clips(), shipped.refusal());
                for (PoseStyle style : styles)
                    for (int tick : STRIP_TICKS) {
                        assertEquals(
                            outcome(shipped, mesh.getKey(), style, catalog.periodTicks(), tick),
                            outcome(stripped, mesh.getKey(), style, catalog.periodTicks(), tick),
                            row.id() + " under '" + style.id() + "' at tick " + tick);
                        compared++;
                    }
            }
        }
        assertTrue(carrying > 0, "the shipped tables carry silhouettes, so the comparison is not vacuous");
        assertTrue(compared > 0);
    }

    @Test
    @DisplayName("the shipped wolf carries its sitting silhouette - the body lowered, tail and hind legs placed")
    void shippedWolfCarriesTheSittingSilhouette() {
        Entity wolf = EntityModelLoader.load().get("minecraft:wolf");
        assumeTrue(wolf != null, "bundled entity tables answer the wolf");

        EntityPose.Silhouette sitting = wolf.pose().states().get("isSitting=true");
        assertTrue(sitting != null, "the sitting branch is carried: " + wolf.pose().states().keySet());
        for (String placed : List.of("body", "upper_body", "tail", "right_hind_leg", "left_hind_leg"))
            assertTrue(sitting.bones().containsKey(placed), "'" + placed + "' is placed by the sitting branch");
        assertTrue(sitting.bones().get("body").containsKey(PoseChannel.Y), "the body is lowered");
        assertTrue(sitting.bones().get("body").containsKey(PoseChannel.X_ROT), "and tilted");
        assertTrue(sitting.bones().get("tail").containsKey(PoseChannel.Z), "the tail is moved forward");
    }

    /**
     * What posing one mesh answers - its bones, or the refusal the write-back raises. A shipped
     * clip that scales the container refuses at render with or without the silhouettes, and the
     * two sides must refuse alike.
     */
    private static @NotNull Object outcome(
        @NotNull EntityPose pose, @NotNull EntityModelData mesh, @NotNull PoseStyle style,
        int periodTicks, int tick) {

        try {
            return PoseKit.posed(pose, mesh, style, periodTicks, tick).getBones();
        } catch (RendererException refused) {
            return refused.getMessage();
        }
    }

    /**
     * Every style row a subject of this definition can pose under - the bind row, every carried
     * row by id, and the two universal rows resolved for the adult subject.
     */
    private static @NotNull List<PoseStyle> stylesOf(@NotNull Entity row) {
        StyleCatalog catalog = row.styles();
        List<PoseStyle> styles = new ArrayList<>();
        styles.add(catalog.bind());
        for (String id : catalog.ids())
            catalog.byId(id).ifPresent(styles::add);
        EntityOptions options = EntityOptions.of(row.id().toString());
        for (String universal : List.of(PoseStyle.IDLE, PoseStyle.STRIDE)) {
            PoseStyle resolved = catalog.resolve(universal, options);
            if (styles.stream().noneMatch(held -> held == resolved)) styles.add(resolved);
        }
        return styles;
    }

    /**
     * Every mesh a definition renders with the pose that belongs to it - the body, each overlay
     * pass and its no-hat alternate, and the baby form where the definition declares one.
     */
    private static @NotNull Map<EntityModelData, EntityPose> meshesOf(@NotNull Entity row) {
        Map<EntityModelData, EntityPose> meshes = new LinkedHashMap<>();
        meshes.put(row.model(), row.pose());
        for (Entity.OverlayLayer overlay : row.overlays()) {
            meshes.put(overlay.model(), overlay.pose());
            overlay.noHatModel().ifPresent(alternate -> meshes.put(alternate, overlay.pose()));
        }
        row.axes().babyModel().ifPresent(baby ->
            meshes.put(baby, row.axes().babyPose().orElse(EntityPose.NONE)));
        return meshes;
    }

}
