package lib.minecraft.renderer.author.install;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentLinkedMap;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.ImageData;
import dev.simplified.image.pixel.PixelBuffer;
import dev.simplified.util.Possible;
import lib.minecraft.renderer.EntityRenderer;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.mesh.EntityMesh;
import lib.minecraft.renderer.asset.pose.EntityPose;
import lib.minecraft.renderer.asset.pose.PoseStyle;
import lib.minecraft.renderer.asset.pose.StyleCatalog;
import lib.minecraft.renderer.author.BuiltStyle;
import lib.minecraft.renderer.author.Poses;
import lib.minecraft.renderer.author.Side;
import lib.minecraft.renderer.bake.pose.PosePlayer;
import lib.minecraft.renderer.call.request.AppearanceOptions;
import lib.minecraft.renderer.call.request.EntityOptions;
import lib.minecraft.renderer.content.index.EntityModelLoader;
import lib.minecraft.renderer.content.index.RendererContext;
import lib.minecraft.renderer.support.RecordingContext;
import lib.minecraft.renderer.vanilla.appearance.Age;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The synthesized player row - its copied mesh, its bind-only rest, its declared Steve ref, and
 * a custom style riding it through the entity path under plain {@link EntityOptions}.
 */
@DisplayName("the player rig is a carried peer of shipped rows")
class PlayerRigTest {

    /**
     * The catalog period every rig install frames its excursions against.
     */
    private static final int PERIOD = 24;

    private static ConcurrentMap<String, Entity> shipped;

    @BeforeAll
    static void load() {
        shipped = EntityModelLoader.load();
        assumeTrue(!shipped.isEmpty(), "entity_models.json not present - run entityModels first");
    }

    @Test
    @DisplayName("the row carries the canonical seven-bone humanoid mesh, copied rather than aliased")
    void rowCopiesTheHumanoidMesh() {
        EntityMesh source = shipped.get("minecraft:zombie").model();
        EntityMesh mesh = PlayerRig.entityRow().model();

        assertEquals(Set.of("head", "hat", "body", "right_arm", "left_arm", "right_leg", "left_leg"),
            Set.copyOf(mesh.getBones().keySet()), "the humanoid tier's roster, whole");
        assertEquals(64, mesh.getTextureWidth(), "cube UVs normalize against the player sheet's width");
        assertEquals(64, mesh.getTextureHeight(), "and its height");
        assertNotSame(source, mesh, "the rig never aliases the shipped mesh instance");
        assertNotSame(source.getBones(), mesh.getBones(), "nor its bone map");
        for (String bone : mesh.getBones().keySet())
            assertEquals(source.getBones().get(bone), mesh.getBones().get(bone),
                "bone '" + bone + "' carries the geometry the shipped row resolved");
        assertNotSame(PlayerRig.entityRow().model(), PlayerRig.entityRow().model(),
            "each call answers a fresh row, so no two definition maps share one");
    }

    @Test
    @DisplayName("the row rests bind-only on the declared Steve ref")
    void rowRestsBindOnlyOnTheSteveRef() {
        Entity rig = PlayerRig.entityRow();

        assertSame(StyleCatalog.BIND_ONLY, rig.styles(), "no style rows of its own");
        assertSame(EntityPose.NONE, rig.pose(), "and no stride or idle expression to collide with");
        assertEquals(Optional.of("player/wide/steve"), rig.textureRef(),
            "a bare render resolves the shipped wide Steve sheet");
        assertTrue(rig.overlays().isEmpty(), "no overlay passes");
        assertTrue(rig.members().isEmpty(), "and no canvas group");
        assertEquals("minecraft:player", PlayerRig.ENTITY_ID);
        assertEquals("minecraft:entity/player/style$skin", PlayerRig.SKIN_TEXTURE_ID,
            "the reserved id is the reserved ref under the render's own qualification");
    }

    @Test
    @DisplayName("the row wears wings, as the player's vanilla renderer builds the wings layer")
    void rowWearsWings() {
        assertTrue(PlayerRig.entityRow().layers().wings(),
            "the rig has no table row, so it sets the wings fact itself");
    }

    @Test
    @DisplayName("a custom style installed on the rig renders through the entity path under plain options")
    void customStyleRendersThroughTheEntityPath() {
        RecordingContext spy = RecordingContext.over(
            RendererContext.builder().textures(id -> Possible.of(sheet())).build());
        EntityRenderer renderer = new EntityRenderer(registrarWithRig()
            .add(PlayerRig.ENTITY_ID, hail())
            .context(spy));

        assertEquals(List.of("bind", "hail"), List.copyOf(renderer.styles(PlayerRig.ENTITY_ID).ids()),
            "discovery lists the rig's installed style beside bind");

        ImageData still = renderer.render(EntityOptions.of(PlayerRig.ENTITY_ID)).image();
        assertTrue(opaqueCount(still.toPixelBuffer()) > 0,
            "a bare render draws Steve rather than the empty frame");
        assertTrue(spy.getResolved().contains("minecraft:entity/player/wide/steve"),
            "and resolves the shipped Steve id");

        ImageData hailed = renderer.render(EntityOptions.builder()
            .entityId(PlayerRig.ENTITY_ID)
            .style("hail")
            .build()).image();
        assertTrue(opaqueCount(hailed.toPixelBuffer()) > 0, "the styled render draws too");
        assertFalse(Arrays.equals(still.toPixelBuffer().data(), hailed.toPixelBuffer().data()),
            "the raised arm moves pixels the bind render does not");
    }

    @Test
    @DisplayName("an installed row rebases against the authored mesh and its adult default resolves on plain options")
    void installedStyleResolvesAndPosesOnTheRig() {
        StyleRegistrar registrar = registrarWithRig().add(PlayerRig.ENTITY_ID, hail());
        Entity rig = registrar.definitions().get(PlayerRig.ENTITY_ID);

        PoseStyle installed = rig.styles().resolve("hail", AppearanceOptions.defaults()::applies, PlayerRig.ENTITY_ID);
        assertEquals(Optional.of(Age.ADULT), installed.age(),
            "the baby-safe default rides the row and the bag's adult default answers it");
        assertEquals(-90f,
            PosePlayer.posed(rig.pose(), rig.model(), installed, PERIOD, 0)
                .getBones().get("right_arm").getRotation().pitch(),
            1e-4f, "the absolute write lands exactly - rest is the authored bind pose");
    }

    // ------------------------------------------------------------------------------------

    /**
     * The one raised-arm statue every rig test installs.
     */
    private static @NotNull BuiltStyle hail() {
        return Poses.humanoid("hail").arm(Side.RIGHT, arm -> arm.pitch(-90)).build();
    }

    /**
     * A registrar over a copy of the shipped rows plus a fresh rig row.
     */
    private static @NotNull StyleRegistrar registrarWithRig() {
        ConcurrentLinkedMap<String, Entity> defs = Concurrent.newLinkedMap(shipped);
        defs.put(PlayerRig.ENTITY_ID, PlayerRig.entityRow());
        return StyleRegistrar.of(defs);
    }

    /**
     * One opaque 64x64 sheet, so drawn geometry lands visible pixels.
     */
    private static @NotNull PixelBuffer sheet() {
        PixelBuffer sheet = PixelBuffer.create(64, 64);
        sheet.fill(0xFF6A8CAD);
        return sheet;
    }

    /**
     * How many pixels of a frame carry any alpha at all.
     */
    private static int opaqueCount(@NotNull PixelBuffer buffer) {
        int count = 0;
        for (int y = 0; y < buffer.height(); y++)
            for (int x = 0; x < buffer.width(); x++)
                if ((buffer.getPixel(x, y) >>> 24) != 0) count++;
        return count;
    }

}
