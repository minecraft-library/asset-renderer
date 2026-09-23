package lib.minecraft.renderer.author.install;

import dev.simplified.collection.Concurrent;
import lib.minecraft.renderer.EntityRenderer;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.mesh.EntityMesh;
import lib.minecraft.renderer.asset.pose.EntityPose;
import lib.minecraft.renderer.asset.pose.PoseClip;
import lib.minecraft.renderer.asset.pose.PoseStyle;
import lib.minecraft.renderer.asset.pose.StyleCatalog;
import lib.minecraft.renderer.asset.pose.StyleClock;
import lib.minecraft.renderer.author.BuiltStyle;
import lib.minecraft.renderer.author.Poses;
import lib.minecraft.renderer.author.Rank;
import lib.minecraft.renderer.author.Side;
import lib.minecraft.renderer.author.Turn;
import lib.minecraft.renderer.bake.pose.PosePlayer;
import lib.minecraft.renderer.bake.pose.StyleSelection;
import lib.minecraft.renderer.diagnostic.Diagnostics;
import lib.minecraft.renderer.engine.pose.ClipDrive;
import lib.minecraft.renderer.engine.pose.PoseChannel;
import lib.minecraft.renderer.engine.pose.PoseExpr;
import lib.minecraft.renderer.engine.pose.PoseOperator;
import lib.minecraft.renderer.engine.pose.StyleDriver;
import lib.minecraft.renderer.exception.RendererException;
import lib.minecraft.renderer.fixture.CompilerFixtures;
import lib.minecraft.renderer.port.RendererContext;
import lib.minecraft.renderer.request.AppearanceOptions;
import lib.minecraft.renderer.request.EntityOptions;
import lib.minecraft.renderer.vanilla.appearance.Age;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static lib.minecraft.renderer.fixture.CompilerFixtures.boneWrite;
import static lib.minecraft.renderer.fixture.CompilerFixtures.constant;
import static lib.minecraft.renderer.fixture.CompilerFixtures.flattened;
import static lib.minecraft.renderer.fixture.CompilerFixtures.humanoid;
import static lib.minecraft.renderer.fixture.CompilerFixtures.input;
import static lib.minecraft.renderer.fixture.CompilerFixtures.pose;
import static lib.minecraft.renderer.fixture.RegistrarFixtures.catalog;
import static lib.minecraft.renderer.fixture.RegistrarFixtures.definitions;
import static lib.minecraft.renderer.fixture.RegistrarFixtures.entity;
import static lib.minecraft.renderer.fixture.RegistrarFixtures.overlay;
import static lib.minecraft.renderer.fixture.RegistrarFixtures.styleRow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The install sequence's guards and the shape of what an install leaves behind - the refusals in
 * order, the appended catalog row, the discovery surface and the per-entity pool that stacks a
 * second style's splices onto the first weave.
 */
@DisplayName("the registrar guards an install and appends one flat catalog row")
class StyleRegistrarTest {

    /**
     * The catalog period every fixture row frames its excursions against.
     */
    private static final int PERIOD = 24;

    @Test
    @DisplayName("an unknown entity id refuses at install, where the author is")
    void unknownEntityIdRefuses() {
        StyleRegistrar registrar = StyleRegistrar.of(definitions(
            entity("minecraft:test", humanoid(), EntityPose.NONE, StyleCatalog.BIND_ONLY)));

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
            () -> registrar.add("minecraft:ghost", sit()));
        assertTrue(refused.getMessage().contains("minecraft:ghost"),
            "the refusal names the id: " + refused.getMessage());
    }

    @Test
    @DisplayName("a style id the catalog already carries refuses, and the context records before the throw")
    void shippedIdCollisionRefusesWithRecordedContext() {
        StyleRegistrar registrar = StyleRegistrar.of(definitions(
            entity("minecraft:test", humanoid(), EntityPose.NONE,
                catalog(styleRow("dance", Map.of())))));

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
            () -> registrar.add("minecraft:test",
                Poses.humanoid("dance").head(head -> head.yaw(10)).build()));
        assertTrue(refused.getMessage().contains("'dance'"),
            "the refusal names the taken id: " + refused.getMessage());
        assertTrue(registrar.diagnostics().entries().stream().anyMatch(entry ->
                entry.severity() == Diagnostics.Severity.ERROR
                    && entry.message().equals(refused.getMessage())
                    && entry.path().equals("styles/minecraft:test/dance/install")),
            "the exact thrown message records under the failing install's scope");
    }

    @Test
    @DisplayName("a repeated custom id refuses against the catalog the first install appended to")
    void repeatedCustomIdRefuses() {
        StyleRegistrar registrar = StyleRegistrar.of(definitions(
            entity("minecraft:test", humanoid(), EntityPose.NONE, StyleCatalog.BIND_ONLY)));
        registrar.add("minecraft:test", sit());

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
            () -> registrar.add("minecraft:test", sit()));
        assertTrue(refused.getMessage().contains("'sit'"), refused.getMessage());
    }

    @Test
    @DisplayName("an age-split pair installs under one id, where the same age twice refuses")
    void anAgeSplitPairInstallsUnderOneId() {
        StyleRegistrar registrar = StyleRegistrar.of(definitions(
            entity("minecraft:test", humanoid(), EntityPose.NONE, StyleCatalog.BIND_ONLY)));
        registrar.add("minecraft:test", playDead(Age.ADULT));
        registrar.add("minecraft:test", playDead(Age.BABY));

        Entity woven = registrar.definitions().get("minecraft:test");
        assertEquals(List.of(Optional.of(Age.ADULT), Optional.of(Age.BABY)),
            woven.styles().styles().stream()
                .filter(style -> "play_dead".equals(style.id()))
                .map(PoseStyle::age)
                .toList(),
            "disjoint ages claim one id apart, which is the shape the shipped axolotl table carries");

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
            () -> registrar.add("minecraft:test", playDead(Age.BABY)));
        assertTrue(refused.getMessage().contains("'play_dead'"),
            "the same age twice is still taken: " + refused.getMessage());
    }

    @Test
    @DisplayName("an every-age claim refuses against a row already installed at one age")
    void anEveryAgeClaimRefusesAgainstAnAgedRow() {
        StyleRegistrar registrar = StyleRegistrar.of(definitions(
            entity("minecraft:test", humanoid(), EntityPose.NONE, StyleCatalog.BIND_ONLY)));
        registrar.add("minecraft:test", playDead(Age.ADULT));

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
            () -> registrar.add("minecraft:test",
                Poses.humanoid("play_dead").head(head -> head.yaw(10)).allAges().build()));
        assertTrue(refused.getMessage().contains("'play_dead'"),
            "an ageless claim spans every age, so one already taken refuses it: " + refused.getMessage());
    }

    @Test
    @DisplayName("a strict install refuses naming every missing bone and the roster the mesh declares")
    void strictInstallNamesEveryMissingBone() {
        EntityMesh mesh = flattened(1f);
        StyleRegistrar registrar = StyleRegistrar.of(definitions(
            entity("minecraft:test", mesh, EntityPose.NONE, StyleCatalog.BIND_ONLY)));
        BuiltStyle beg = Poses.legged("beg")
            .head(head -> head.pitch(-15))
            .bone("right_hind_leg", leg -> leg.pitch(-70))
            .bone("left_hind_leg", leg -> leg.pitch(-70))
            .build();

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
            () -> registrar.add("minecraft:test", beg));
        assertTrue(refused.getMessage().contains("right_hind_leg"), refused.getMessage());
        assertTrue(refused.getMessage().contains("left_hind_leg"), "every missing bone is named");
        assertTrue(refused.getMessage().contains("tail"), "and the declared roster rides along");
    }

    @Test
    @DisplayName("a tolerant install drops the absent bones and poses the present subset")
    void tolerantInstallPosesThePresentSubset() {
        EntityMesh mesh = flattened(1f);
        StyleRegistrar registrar = StyleRegistrar.of(definitions(
            entity("minecraft:test", mesh, EntityPose.NONE, StyleCatalog.BIND_ONLY)));
        registrar.addTolerant("minecraft:test", Poses.legged("beg")
            .head(head -> head.pitch(-15))
            .bone("right_hind_leg", leg -> leg.pitch(-70))
            .bone("left_hind_leg", leg -> leg.pitch(-70))
            .build());

        Entity woven = registrar.definitions().get("minecraft:test");
        PoseStyle installed = StyleSelection.byId(woven.styles(), "beg").orElseThrow();
        assertEquals(-15f,
            PosePlayer.posed(woven.pose(), mesh, installed, PERIOD, 0)
                .getBones().get("head").getRotation().pitch(),
            1e-4f, "everything the mesh declares still lands");
        assertTrue(registrar.diagnostics().entries().stream().anyMatch(entry ->
                entry.severity() == Diagnostics.Severity.WARN
                    && entry.message().contains("right_hind_leg")),
            "the drop records its warning");
    }

    @Test
    @DisplayName("a scale write over a bone a shipped clip scales refuses naming bone and coordinate")
    void shippedClipScaleCollisionRefuses() {
        PoseClip puff = new PoseClip(1f, true, Concurrent.newUnmodifiableList(
            new PoseClip.Channel("right_arm", PoseChannel.Kind.SCALE, Concurrent.newUnmodifiableList(
                new PoseClip.Keyframe(0f, 0f, 0f, 0f, PoseClip.Interpolation.LINEAR),
                new PoseClip.Keyframe(0.5f, 0.1f, 0.1f, 0.1f, PoseClip.Interpolation.LINEAR)))));
        EntityPose shipped = pose(List.of(), Map.of(), List.of(
            new EntityPose.Clip("FixtureAnimation#PUFF", ClipDrive.NONE, Optional.empty(),
                Concurrent.newUnmodifiableList(), puff)));
        StyleRegistrar registrar = StyleRegistrar.of(definitions(
            entity("minecraft:test", humanoid(), shipped, StyleCatalog.BIND_ONLY)));

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
            () -> registrar.add("minecraft:test",
                Poses.humanoid("bulk").arm(Side.RIGHT, arm -> arm.scale(1.5)).build()));
        assertTrue(refused.getMessage().contains("'right_arm'"), refused.getMessage());
        assertTrue(refused.getMessage().contains("FixtureAnimation#PUFF"),
            "the colliding clip is named where the author is");
    }

    @Test
    @DisplayName("a hand-built selection site naming no gate field refuses at install")
    void selectSiteWithoutFieldRefuses() {
        PoseClip wob = new PoseClip(1f, true, Concurrent.newUnmodifiableList(
            new PoseClip.Channel("head", PoseChannel.Kind.ROTATION, Concurrent.newUnmodifiableList(
                new PoseClip.Keyframe(0f, 0f, 0f, 0f, PoseClip.Interpolation.LINEAR)))));
        EntityPose shipped = pose(List.of(), Map.of(), List.of(
            new EntityPose.Clip("FixtureAnimation#WOB", ClipDrive.SELECT, Optional.empty(),
                Concurrent.newUnmodifiableList(input("someState")), wob)));
        StyleRegistrar registrar = StyleRegistrar.of(definitions(
            entity("minecraft:test", humanoid(), shipped, StyleCatalog.BIND_ONLY)));

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
            () -> registrar.add("minecraft:test", sit()));
        assertTrue(refused.getMessage().contains("FixtureAnimation#WOB"), refused.getMessage());
        assertTrue(refused.getMessage().contains("gate field"),
            "the load validation a hand-built row skips runs here instead");
    }

    @Test
    @DisplayName("a raw read of a bone a same-instance overlay mesh lacks refuses at install")
    void rawReadMissingFromOverlayMeshRefuses() {
        EntityMesh body = humanoid();
        body.getBones().put("aux", CompilerFixtures.bone(0f, 4f, 0f, 0f, 0f, 0f, 1f, null));
        EntityPose bodyPose = pose(List.of(), Map.of(), List.of());
        StyleRegistrar registrar = StyleRegistrar.of(definitions(
            entity("minecraft:test", body, bodyPose, StyleCatalog.BIND_ONLY,
                overlay(humanoid(), bodyPose))));
        BuiltStyle glare = Poses.custom("glare")
            .expr("head", PoseChannel.X_ROT, new PoseExpr.BoneRead("aux", PoseChannel.X_ROT))
            .build();

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
            () -> registrar.add("minecraft:test", glare));
        assertTrue(refused.getMessage().contains("'aux'"),
            "the overlay evaluates the woven row, so the read would throw at render: " + refused.getMessage());
    }

    @Test
    @DisplayName("the appended row is flat: sources, drivers, toggles and age, no group anywhere")
    void appendedRowShapeIsFlat() {
        StyleRegistrar registrar = StyleRegistrar.of(definitions(
            entity("minecraft:test", humanoid(), EntityPose.NONE, StyleCatalog.BIND_ONLY)));
        registrar.add("minecraft:test", Poses.humanoid("jog")
            .keepStride()
            .arm(Side.RIGHT, arm -> arm.pitchBy(-20)
                .timeline(track -> track.swing(Turn.PITCH, -10, 10).over(0.6)))
            .build());

        PoseStyle installed = StyleSelection.byId(registrar.definitions().get("minecraft:test")
            .styles(), "jog").orElseThrow();
        assertEquals(List.of(new PoseStyle.StyleSource(StyleClock.TICK, Optional.empty())),
            List.copyOf(installed.sources()), "the inferred clock source, ungated");
        assertEquals(Optional.of(Age.ADULT), installed.age(), "the baby-safe default rides the row");
        assertTrue(installed.toggles().isEmpty());
        for (String field : List.of("ageInTicks", "walkAnimationSpeed", "walkAnimationPos",
            "style$jog$right_arm$x_rot", "style$jog", "style$jog$clock"))
            assertNotNull(installed.drivers().get(field), "driver '" + field + "' rides the row");
        assertTrue(installed.drivers().values().stream().allMatch(driver -> driver.group().isEmpty()),
            "custom styles exclude one another by id selection alone, never by group");
    }

    @Test
    @DisplayName("discovery lists bind first, shipped ids in order, custom ids trailing in install order")
    void discoveryListsCustomIdsAsTrailingPeers() {
        StyleRegistrar registrar = StyleRegistrar.of(definitions(
            entity("minecraft:test", humanoid(), EntityPose.NONE,
                catalog(styleRow("dance", Map.of())))));
        registrar.add("minecraft:test", sit());
        registrar.add("minecraft:test", Poses.humanoid("wave").head(head -> head.yaw(15)).build());

        assertEquals(List.of("bind", "dance", "sit", "wave"),
            List.copyOf(StyleSelection.ids(new EntityRenderer(registrar.context(RendererContext.builder().build()))
                .styles("minecraft:test"))));
    }

    @Test
    @DisplayName("a custom id resolves as a carried row and the universal ids resolve untouched")
    void customIdResolvesBesideTheUniversalRows() {
        StyleRegistrar registrar = StyleRegistrar.of(definitions(
            entity("minecraft:test", humanoid(), EntityPose.NONE, StyleCatalog.BIND_ONLY)));
        registrar.add("minecraft:test", sit());

        StyleCatalog installed = registrar.definitions().get("minecraft:test").styles();
        EntityOptions options = EntityOptions.of("minecraft:test");
        assertEquals("sit", StyleSelection.resolve(installed, "sit", options).id());
        assertEquals(PoseStyle.BIND, StyleSelection.resolve(installed, PoseStyle.BIND, options).id());
        assertEquals(PoseStyle.STRIDE, StyleSelection.resolve(installed, PoseStyle.STRIDE, options).id(),
            "the universal rows answer exactly as before the install");
    }

    @Test
    @DisplayName("a baby subject refuses the adult-default row loudly at resolve")
    void babySubjectRefusesTheAdultDefault() {
        StyleRegistrar registrar = StyleRegistrar.of(definitions(
            entity("minecraft:test", humanoid(), EntityPose.NONE, StyleCatalog.BIND_ONLY)));
        registrar.add("minecraft:test", sit());
        EntityOptions baby = EntityOptions.builder()
            .entityId("minecraft:test")
            .appearance(AppearanceOptions.builder().age(Age.BABY).build())
            .build();

        RendererException refused = assertThrows(RendererException.class,
            () -> StyleSelection.resolve(registrar.definitions().get("minecraft:test").styles(), "sit", baby));
        assertTrue(refused.getMessage().contains("has no style 'sit'"), refused.getMessage());
        assertTrue(refused.getMessage().contains("minecraft:test"), "the refusal names the subject");
    }

    @Test
    @DisplayName("a second style's splice stacks onto the first weave through the shared pool")
    void secondStyleSplicesOntoTheFirstWeave() {
        PoseExpr shippedExpr = constant(0.3d);
        StyleRegistrar registrar = StyleRegistrar.of(definitions(
            entity("minecraft:test", humanoid(),
                boneWrite("right_arm", PoseChannel.X_ROT, shippedExpr), StyleCatalog.BIND_ONLY)));
        registrar.add("minecraft:test",
            Poses.humanoid("lean").arm(Side.RIGHT, arm -> arm.pitch(-40)).build());
        PoseExpr afterFirst = registrar.definitions().get("minecraft:test")
            .pose().bones().get("right_arm").get(PoseChannel.X_ROT);
        registrar.add("minecraft:test",
            Poses.humanoid("brace").arm(Side.RIGHT, arm -> arm.pitchBy(-10)).build());

        PoseExpr.Op stacked = assertInstanceOf(PoseExpr.Op.class,
            registrar.definitions().get("minecraft:test")
                .pose().bones().get("right_arm").get(PoseChannel.X_ROT));
        assertEquals(PoseOperator.DADD, stacked.operator());
        assertSame(afterFirst, stacked.operands().getFirst(),
            "the second splice references the first weave's instance directly");
        PoseExpr.Op inner = assertInstanceOf(PoseExpr.Op.class, stacked.operands().getFirst());
        assertSame(shippedExpr, inner.operands().getFirst(),
            "and the shipped instance still rides innermost, never rebuilt");
    }

    @Test
    @DisplayName("shipped styles pose bit-identically before and after an install")
    void shippedStylesPoseIdenticallyAcrossAnInstall() {
        EntityMesh mesh = humanoid();
        // The shipped table writes head and hat with one shared instance, the way a shell that
        // copies its head is baked - the hat mirror weaves over that same instance.
        PoseExpr shippedHead = CompilerFixtures.dadd(constant(0.02d), input("ageInTicks"));
        EntityPose shipped = pose(List.of(), Map.of(
            "head", Map.of(PoseChannel.Y_ROT, shippedHead),
            "hat", Map.of(PoseChannel.Y_ROT, shippedHead)), List.of());
        PoseStyle wob = styleRow("wob", Map.of("ageInTicks",
            new StyleDriver("ageInTicks", StyleDriver.Wave.RAMP, 0f, 1f, Optional.empty())));
        Entity row = entity("minecraft:test", mesh, shipped, catalog(wob));
        StyleRegistrar registrar = StyleRegistrar.of(definitions(row));
        registrar.add("minecraft:test",
            Poses.humanoid("nod").head(head -> head.yaw(35)).build());
        Entity woven = registrar.definitions().get("minecraft:test");

        assertSame(row.model(), woven.model(),
            "the weave replaces only the pose, overlays and catalog - bind identity survives");
        for (int tick = 0; tick < PERIOD; tick += 3)
            assertEquals(
                PosePlayer.posed(shipped, mesh, wob, PERIOD, tick).getBones(),
                PosePlayer.posed(woven.pose(), mesh, StyleSelection.byId(woven.styles(), "wob").orElseThrow(), PERIOD, tick).getBones(),
                "tick " + tick + " answers the shipped bits under the shipped style");
    }

    @Test
    @DisplayName("every install records its summary under the install scope")
    void installRecordsItsSummary() {
        StyleRegistrar registrar = StyleRegistrar.of(definitions(
            entity("minecraft:test", humanoid(), EntityPose.NONE, StyleCatalog.BIND_ONLY)));
        registrar.add("minecraft:test", sit());

        assertTrue(registrar.diagnostics().entries().stream().anyMatch(entry ->
                entry.severity() == Diagnostics.Severity.INFO
                    && entry.path().equals("styles/minecraft:test/sit/install")
                    && entry.message().contains("install summary")
                    && entry.message().contains("sit")),
            "the summary names the id installed and the catalog's ids now");
    }

    @Test
    @DisplayName("file mode writes at close, and the renderer outlives the block that closed it")
    void fileModeWritesAtClose(@TempDir Path tempDir) throws IOException {
        Path log = tempDir.resolve("logs/styles.log");
        EntityRenderer renderer;
        try (StyleRegistrar registrar = StyleRegistrar.of(
            definitions(entity("minecraft:test", humanoid(), EntityPose.NONE, StyleCatalog.BIND_ONLY)),
            Diagnostics.Output.FILE, log)) {

            registrar.add("minecraft:test", sit());
            assertFalse(Files.exists(log), "nothing reaches the target before the close");
            renderer = new EntityRenderer(registrar.context(RendererContext.builder().build()));
        }

        assertNotNull(renderer, "the renderer holds nothing the close released");
        assertTrue(Files.readString(log).contains("install summary: style 'sit' joins entity 'minecraft:test'"),
            "the install's own entry reaches the file target");
    }

    @Test
    @DisplayName("an aborted install reaches the log at close - the refusal records before it throws")
    void refusedInstallReachesTheLogAtClose(@TempDir Path tempDir) throws IOException {
        Path log = tempDir.resolve("styles.log");
        try (StyleRegistrar registrar = StyleRegistrar.of(
            definitions(entity("minecraft:test", humanoid(), EntityPose.NONE, StyleCatalog.BIND_ONLY)),
            Diagnostics.Output.FILE, log)) {

            assertThrows(IllegalArgumentException.class, () -> registrar.add("minecraft:ghost", sit()));
        }

        assertTrue(Files.readString(log).contains("minecraft:ghost"),
            "an install that threw is the one a file log exists to keep, so the write cannot "
                + "sit at the end of a path a refusal leaves by");
    }

    // ------------------------------------------------------------------------------------

    /**
     * The seated statue every guard test installs - one container step, nothing animated.
     */
    private static @NotNull BuiltStyle sit() {
        return Poses.humanoid("sit").container(step -> step.offset(0, 7, 0)).build();
    }

    private static @NotNull BuiltStyle playDead(@NotNull Age age) {
        return Poses.humanoid("play_dead").head(head -> head.yaw(10)).age(age).build();
    }

}
