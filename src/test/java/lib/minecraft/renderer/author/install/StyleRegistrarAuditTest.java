package lib.minecraft.renderer.author.install;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.mesh.EntityMesh;
import lib.minecraft.renderer.asset.pose.EntityPose;
import lib.minecraft.renderer.asset.pose.PoseStyle;
import lib.minecraft.renderer.asset.pose.StyleCatalog;
import lib.minecraft.renderer.author.BuiltStyle;
import lib.minecraft.renderer.author.CustomPose;
import lib.minecraft.renderer.author.PoseScript;
import lib.minecraft.renderer.author.Poses;
import lib.minecraft.renderer.author.audit.PoseAudit;
import lib.minecraft.renderer.author.audit.PoseAuditor;
import lib.minecraft.renderer.author.compile.PoseCompiler;
import lib.minecraft.renderer.author.mesh.LimbRoster;
import lib.minecraft.renderer.bake.pose.PosePlayer;
import lib.minecraft.renderer.content.index.EntityModelLoader;
import lib.minecraft.renderer.diagnostic.Diagnostics;
import lib.minecraft.renderer.engine.math.Vector3f;
import lib.minecraft.renderer.engine.pose.PoseChannel;
import lib.minecraft.renderer.engine.pose.PoseExpr;
import lib.minecraft.renderer.exception.RendererException;
import lib.minecraft.renderer.exception.StyleException;
import lib.minecraft.renderer.fixture.CompilerFixtures;
import lib.minecraft.renderer.vanilla.appearance.Size;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static lib.minecraft.renderer.fixture.CompilerFixtures.flattened;
import static lib.minecraft.renderer.fixture.CompilerFixtures.humanoid;
import static lib.minecraft.renderer.fixture.CompilerFixtures.pose;
import static lib.minecraft.renderer.fixture.CompilerFixtures.ridingHat;
import static lib.minecraft.renderer.fixture.RegistrarFixtures.definitions;
import static lib.minecraft.renderer.fixture.RegistrarFixtures.entity;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The audit held to the install it predicts, over the same definitions - an audit throws exactly
 * where a tolerant install's weave refuses and with its message, and reports an address unreached
 * exactly where a strict install refuses over one.
 *
 * <p>The pairs live here rather than beside the audit's own tests because the audit sits below the
 * install in the package order, so only the install's side may name both.
 */
@DisplayName("the audit predicts every refusal a strict install makes")
class StyleRegistrarAuditTest {

    /** The row whose baby mesh lacks the mane its adult declares. */
    private static final @NotNull String WOLF = "minecraft:wolf";

    /** The row whose wind pass shares the body's pose over a mesh lacking the body's bones. */
    private static final @NotNull String BREEZE = "minecraft:breeze";

    /** The row whose size forms each carry a mesh and a pose of their own. */
    private static final @NotNull String PUFFERFISH = "minecraft:pufferfish";

    /** The id of each hand-built row whose one size form shares the row's pose instance. */
    private static final @NotNull String TEST = "minecraft:test";

    /** The catalog period every hand-built row frames its excursions against. */
    private static final int PERIOD = 24;

    @Test
    @DisplayName("a bone the baby's mesh lacks is reported and refused alike - the wolf's mane, written at every age")
    void aBabyMissingTheWrittenBoneIsReported() {
        ConcurrentMap<String, Entity> shipped = shipped();
        BuiltStyle perk = Poses.custom("perk").bone("upper_body", mane -> mane.pitchBy(10)).allAges().build();

        StyleException refused = assertThrows(StyleException.class,
            () -> StyleRegistrar.of(shipped).add(WOLF, perk));
        assertTrue(refused.getMessage().contains("upper_body") && refused.getMessage().contains("form '$age:baby'"),
            "the install refuses the mane on the baby: " + refused.getMessage());
        assertEquals(List.of("bone 'upper_body'"), described(PoseAuditor.validate(perk, row(shipped, WOLF))),
            "and the audit reports it once - each coat's baby takes the row baby's weave");
        assertAgrees(shipped, WOLF, perk);
    }

    @Test
    @DisplayName("a pass sharing the body's pose reports nothing - the breeze's wind follows its body uncompiled")
    void aPassSharingTheBodysPoseReportsNothing() {
        ConcurrentMap<String, Entity> shipped = shipped();
        BuiltStyle peer = Poses.custom("peer").bone("head", head -> head.pitchBy(6)).build();

        assertDoesNotThrow(() -> StyleRegistrar.of(shipped).add(BREEZE, peer),
            "a strict install weaves the wind by instance");
        assertEquals(List.of(), described(PoseAuditor.validate(peer, row(shipped, BREEZE))),
            "so the audit reports nothing the wind's mesh lacks");
        assertAgrees(shipped, BREEZE, peer);
    }

    @Test
    @DisplayName("a raw read of a fin the small pufferfish lacks refuses the install and the audit alike")
    void aRawReadASizeFormLacksRefusesBoth() {
        ConcurrentMap<String, Entity> shipped = shipped();
        BuiltStyle glare = Poses.custom("fin_glare")
            .expr("body", PoseChannel.X_ROT, new PoseExpr.BoneRead("left_blue_fin", PoseChannel.X_ROT))
            .build();

        StyleException installed = assertThrows(StyleException.class,
            () -> StyleRegistrar.of(shipped).add(PUFFERFISH, glare));
        StyleException audited = assertThrows(StyleException.class,
            () -> PoseAuditor.validate(glare, row(shipped, PUFFERFISH)));
        assertTrue(installed.getMessage().contains("'left_blue_fin'"), installed.getMessage());
        assertTrue(audited.getMessage().contains("'left_blue_fin'"), audited.getMessage());
        assertAgrees(shipped, PUFFERFISH, glare);
    }

    @Test
    @DisplayName("a raw read of a bone a guarded size mesh lacks refuses the install and the audit alike")
    void aGuardedSizeMeshRefusesARawReadBoth() {
        ConcurrentMap<String, Entity> definitions = definitions(sizedRow());
        BuiltStyle glare = Poses.custom("arm_glare")
            .expr("body", PoseChannel.X_ROT, new PoseExpr.BoneRead("right_arm", PoseChannel.X_ROT))
            .build();
        StyleRegistrar registrar = StyleRegistrar.of(definitions);

        StyleException installed = assertThrows(StyleException.class,
            () -> registrar.add(TEST, glare));
        StyleException audited = assertThrows(StyleException.class,
            () -> PoseAuditor.validate(glare, definitions.get(TEST)));
        assertTrue(installed.getMessage().contains("'right_arm'"), installed.getMessage());
        assertTrue(audited.getMessage().contains("'right_arm'"), audited.getMessage());
        assertTrue(registrar.diagnostics().entries().stream().anyMatch(entry ->
                entry.severity() == Diagnostics.Severity.ERROR
                    && entry.path().equals("styles/minecraft:test/arm_glare/form/size:small/install")),
            "the install records the refusal under the size form it guards");
        assertAgrees(definitions, TEST, glare);
    }

    @Test
    @DisplayName("a written bone a guarded size mesh lacks is recorded and reported as nothing - the render filters it")
    void aGuardedSizeMeshsPlainDropReportsNothing() {
        ConcurrentMap<String, Entity> definitions = definitions(sizedRow());
        BuiltStyle lift = Poses.custom("lift").bone("right_arm", arm -> arm.pitchBy(10)).build();
        StyleRegistrar registrar = StyleRegistrar.of(definitions);

        assertDoesNotThrow(() -> registrar.add(TEST, lift), "a strict install weaves over the guard");
        assertTrue(registrar.diagnostics().entries().stream().anyMatch(entry ->
                entry.severity() == Diagnostics.Severity.WARN
                    && entry.path().equals("styles/minecraft:test/lift/form/size:small/install")
                    && entry.message().contains("weave-subset")),
            "the guard records the arm the small mesh lacks");
        assertEquals(List.of(), described(PoseAuditor.validate(lift, definitions.get(TEST))),
            "and the audit reports nothing a strict install refuses over");
        assertAgrees(definitions, TEST, lift);
    }

    @Test
    @DisplayName("a bone a size mesh flattened apart from its row lacks refuses the install and is reported by the audit alike - that form is woven, not guarded")
    void aSizeMeshFlattenedApartIsWoven() {
        EntityMesh tailless = flattened(2f);
        tailless.getBones().remove("tail");
        ConcurrentMap<String, Entity> definitions = definitions(sizedRow(flattened(1f), tailless));
        BuiltStyle flick = Poses.custom("flick").bone("tail", tail -> tail.pitchBy(10)).build();

        StyleException installed = assertThrows(StyleException.class,
            () -> StyleRegistrar.of(definitions).add(TEST, flick));
        assertTrue(installed.getMessage().contains("form '$size:small'") && installed.getMessage().contains("'tail'"),
            "the install compiles the small form against its own mesh and refuses the tail it lacks: "
                + installed.getMessage());
        assertEquals(List.of("bone 'tail'"), described(PoseAuditor.validate(flick, definitions.get(TEST))),
            "and the audit reports it");
        assertAgrees(definitions, TEST, flick);
    }

    @Test
    @DisplayName("a bone a size mesh resting its shared bones apart from its row's lacks refuses the install and is reported by the audit alike - that form is woven though both answer a factor of one")
    void aSizeMeshRestingApartIsWoven() {
        // The baby transform's scales - the head at 0.75, every other part at 0.5 - disagree, so the
        // small mesh answers the row's factor of one while every bone both declare rests apart.
        EntityMesh small = agedDown(humanoid());
        small.getBones().remove("right_arm");
        assertEquals(humanoid().getFlattenedScale(), small.getFlattenedScale(), "both meshes answer one factor");
        ConcurrentMap<String, Entity> definitions = definitions(sizedRow(humanoid(), small));
        BuiltStyle lift = Poses.custom("lift").bone("right_arm", arm -> arm.pitchBy(10)).build();

        StyleException installed = assertThrows(StyleException.class,
            () -> StyleRegistrar.of(definitions).add(TEST, lift));
        assertTrue(installed.getMessage().contains("form '$size:small'")
                && installed.getMessage().contains("'right_arm'"),
            "the install compiles the small form against its own mesh and refuses the arm it lacks: "
                + installed.getMessage());
        assertEquals(List.of("bone 'right_arm'"), described(PoseAuditor.validate(lift, definitions.get(TEST))),
            "and the audit reports it");
        assertAgrees(definitions, TEST, lift);
    }

    @Test
    @DisplayName("a bone a size mesh at its row's own flattened factor lacks is recorded and reported as nothing - the factor is held to the row's, not to one")
    void aSizeMeshAtItsRowsFlattenedFactorIsGuarded() {
        EntityMesh tailless = flattened(2f);
        tailless.getBones().remove("tail");
        ConcurrentMap<String, Entity> definitions = definitions(sizedRow(flattened(2f), tailless));
        BuiltStyle flick = Poses.custom("flick").bone("tail", tail -> tail.pitchBy(10)).build();
        StyleRegistrar registrar = StyleRegistrar.of(definitions);

        assertDoesNotThrow(() -> registrar.add(TEST, flick), "a strict install weaves over the guard");
        assertTrue(registrar.diagnostics().entries().stream().anyMatch(entry ->
                entry.severity() == Diagnostics.Severity.WARN
                    && entry.path().equals("styles/minecraft:test/flick/form/size:small/install")
                    && entry.message().contains("weave-subset")),
            "the guard records the tail the small mesh lacks");
        assertEquals(List.of(), described(PoseAuditor.validate(flick, definitions.get(TEST))),
            "and the audit reports nothing a strict install refuses over");
        assertAgrees(definitions, TEST, flick);
    }

    @Test
    @DisplayName("a guarded size mesh lacking a hat records the hat an author wrote and never the head's implicit copy")
    void aGuardedSizeMeshRecordsNoImplicitHatCopy() {
        EntityMesh hatless = humanoid();
        hatless.getBones().remove("hat");
        ConcurrentMap<String, Entity> definitions = definitions(sizedRow(humanoid(), hatless));
        BuiltStyle nod = Poses.humanoid("nod").head(head -> head.yaw(35)).build();
        BuiltStyle tip = Poses.humanoid("tip").hat(hat -> hat.yaw(35)).build();
        StyleRegistrar registrar = StyleRegistrar.of(definitions);

        assertDoesNotThrow(() -> registrar.add(TEST, nod).add(TEST, tip), "a strict install weaves over the guard");
        assertEquals(List.of(), subsetWarnings(registrar, "nod"),
            "the head's copy onto the hat is nobody's write, so the guard names no hat for it");
        assertEquals(1, subsetWarnings(registrar, "tip").size(), "where an authored hat is recorded once");
        assertTrue(subsetWarnings(registrar, "tip").getFirst().contains("[hat]"),
            subsetWarnings(registrar, "tip").getFirst());
        assertAgrees(definitions, TEST, nod);
    }

    @Test
    @DisplayName("a guarded size mesh hanging its hat from the head refuses a head turn the row's top-level hat takes a copy of, the install and the audit alike")
    void aGuardedSizeMeshWhoseHatRidesTheHeadRefuses() {
        ConcurrentMap<String, Entity> definitions = definitions(sizedRow(humanoid(), ridingHat()));
        BuiltStyle nod = Poses.humanoid("nod").head(head -> head.yaw(35)).build();
        StyleRegistrar registrar = StyleRegistrar.of(definitions);

        StyleException refused = assertThrows(StyleException.class, () -> registrar.add(TEST, nod));
        assertTrue(refused.getMessage().contains("form 'size:small'") && refused.getMessage().contains("rides the head"),
            "the refusal names the size form and its hat: " + refused.getMessage());
        assertTrue(registrar.diagnostics().entries().stream().anyMatch(entry ->
                entry.severity() == Diagnostics.Severity.ERROR
                    && entry.path().equals("styles/minecraft:test/nod/form/size:small/install")),
            "the install records the refusal under the size form it guards");
        assertAgrees(definitions, TEST, nod);
    }

    @Test
    @DisplayName("a shape form drawing its own mesh is guarded as a lent size form is - its hat and its raw reads alike")
    void aShapeFormDrawingItsOwnMeshIsGuarded() {
        BuiltStyle nod = Poses.humanoid("nod").head(head -> head.yaw(35)).build();
        ConcurrentMap<String, Entity> riding = definitions(shapedRow(ridingHat()));
        StyleException refused = assertThrows(StyleException.class,
            () -> StyleRegistrar.of(riding).add(TEST, nod));
        assertTrue(refused.getMessage().contains("form 'shape:large'") && refused.getMessage().contains("rides the head"),
            "the large form's riding hat would move twice under the row's copy: " + refused.getMessage());
        assertAgrees(riding, TEST, nod);

        ConcurrentMap<String, Entity> hatless = definitions(shapedRow(CompilerFixtures.hatless()));
        assertDoesNotThrow(() -> StyleRegistrar.of(hatless).add(TEST, nod), "a hatless large form installs");
        assertAgrees(hatless, TEST, nod);

        EntityMesh armless = humanoid();
        armless.getBones().remove("right_arm");
        ConcurrentMap<String, Entity> unarmed = definitions(shapedRow(armless));
        BuiltStyle glare = Poses.custom("arm_glare")
            .expr("body", PoseChannel.X_ROT, new PoseExpr.BoneRead("right_arm", PoseChannel.X_ROT))
            .build();
        StyleRegistrar registrar = StyleRegistrar.of(unarmed);
        StyleException read = assertThrows(StyleException.class, () -> registrar.add(TEST, glare));
        assertTrue(read.getMessage().contains("'right_arm'"), read.getMessage());
        assertTrue(registrar.diagnostics().entries().stream().anyMatch(entry ->
                entry.severity() == Diagnostics.Severity.ERROR
                    && entry.path().equals("styles/minecraft:test/arm_glare/form/shape:large/install")),
            "the install records the raw read the large mesh cannot answer under the shape form");
        assertAgrees(unarmed, TEST, glare);
    }

    @Test
    @DisplayName("a guarded size whose roster answers another leg installs a selected scale the row's own leg takes - the size plays the row's compile")
    void aGuardedSizeReadsTheRowsScaledLeg() {
        // The small mesh swaps the hips front to back, so its roster would answer the right hind
        // leg, which the row's clip scales; the row's compile scales its right front leg.
        EntityMesh swapped = CompilerFixtures.hipped(7f, -5f);
        PoseScript.Limb.Selected frontRight = assertInstanceOf(PoseScript.Limb.Selected.class,
            CompilerFixtures.stomp().script().stances().getFirst().limb().orElseThrow());
        assertEquals(List.of("right_hind_leg"),
            List.copyOf(LimbRoster.members(frontRight.selector(), swapped, () -> LimbRoster.of(swapped))),
            "the small mesh's own roster answers the right hind leg for the front right address");
        ConcurrentMap<String, Entity> definitions = definitions(
            sizedRow(CompilerFixtures.hipped(), swapped, CompilerFixtures.swelling("body", "right_hind_leg")));
        StyleRegistrar registrar = StyleRegistrar.of(definitions);

        assertDoesNotThrow(() -> registrar.add(TEST, CompilerFixtures.stomp()),
            "the row's scan read the leg its compile scales, which no clip scales");
        Entity woven = registrar.definitions().get(TEST);
        PoseStyle installed = woven.styles().byId("stomp").orElseThrow();
        Entity small = woven.axes().size().select(Size.SMALL).orElseThrow();
        assertSame(swapped, small.model(), "the small form keeps its own mesh");
        EntityMesh posed = assertDoesNotThrow(() -> PosePlayer.posed(small.pose(), small.model(), installed, PERIOD, 0),
            "and the small form plays it with no bone scaled by both a pose and a clip");
        assertEquals(new Vector3f(1.25f, 1.25f, 1.25f), posed.getBones().get("right_hind_leg").getPoseScale(),
            "the small form's right hind leg carries the clip's scale alone");
        assertEquals(new Vector3f(1.5f, 1.5f, 1.5f), posed.getBones().get("right_front_leg").getPoseScale(),
            "and its right front leg the scale the row's compile writes");
        assertAgrees(definitions, TEST, CompilerFixtures.stomp());
    }

    @Test
    @DisplayName("a scale on a bone the row drops does not refuse a guarded size declaring it - the row's compile writes it nowhere")
    void aTolerantScaleTheRowDropsDoesNotRefuseAGuardedSize() {
        EntityMesh tailed = humanoid();
        tailed.getBones().put("tail", CompilerFixtures.bone(0f, 12f, 4f, 0f, 0f, 0f, 1f, null));
        ConcurrentMap<String, Entity> definitions = definitions(
            sizedRow(humanoid(), tailed, CompilerFixtures.swelling("body", "tail")));
        BuiltStyle bulk = Poses.custom("bulk")
            .bone("body", body -> body.pitchBy(5))
            .bone("tail", tail -> tail.scale(1.5))
            .build();
        StyleRegistrar registrar = StyleRegistrar.of(definitions);

        assertDoesNotThrow(() -> registrar.addTolerant(TEST, bulk), "the tolerant install drops the tail on the row");
        Entity woven = registrar.definitions().get(TEST);
        Entity small = woven.axes().size().select(Size.SMALL).orElseThrow();
        EntityMesh posed = PosePlayer.posed(small.pose(), small.model(),
            woven.styles().byId("bulk").orElseThrow(), PERIOD, 0);
        assertEquals(new Vector3f(1.25f, 1.25f, 1.25f), posed.getBones().get("tail").getPoseScale(),
            "the small form's tail carries the clip's scale alone");
        assertAgrees(definitions, TEST, bulk);
    }

    @Test
    @DisplayName("a scale collision a guarded size would draw refuses at the row, whose compile the size plays")
    void aGuardedSizeCollisionStillRefusesAtTheRow() {
        EntityPose shipped = CompilerFixtures.swelling("body", "right_front_leg");
        EntityMesh small = CompilerFixtures.hipped();
        Entity row = sizedRow(CompilerFixtures.hipped(), small, shipped);
        ConcurrentMap<String, Entity> definitions = definitions(row);
        StyleRegistrar registrar = StyleRegistrar.of(definitions);

        StyleException refused = assertThrows(StyleException.class,
            () -> registrar.add(TEST, CompilerFixtures.stomp()));
        assertTrue(refused.getMessage().contains("'right_front_leg'"), refused.getMessage());
        assertEquals(List.of("styles/minecraft:test/stomp/install"), registrar.diagnostics().entries().stream()
                .filter(entry -> entry.severity() == Diagnostics.Severity.ERROR)
                .map(Diagnostics.Entry::path)
                .toList(),
            "the row's own scan refuses, before the size form is reached");
        assertAgrees(definitions, TEST, CompilerFixtures.stomp());

        // Compiled past the scan, the row's compile scales the leg the small form draws under the
        // same clip, so the row's refusal is the one the small form needs.
        PoseCompiler.Compiled compiled = PoseCompiler.compile(CompilerFixtures.stomp(), row);
        RendererException thrown = assertThrows(RendererException.class,
            () -> PosePlayer.posed(compiled.pose(), small, compiled.style(), PERIOD, 0));
        assertTrue(thrown.getMessage().contains("bone 'right_front_leg' is scaled by its model and by a clip"),
            thrown.getMessage());
    }

    @Test
    @DisplayName("every readable shipped row agrees - a turn on every bone at every age audits as it installs")
    void everyShippedRowAgrees() {
        ConcurrentMap<String, Entity> shipped = shipped();

        int held = 0;
        for (Map.Entry<String, Entity> entry : shipped.entrySet()) {
            Entity row = entry.getValue();
            if (!row.pose().isReadable() || row.model().getBones().isEmpty()) continue;
            CustomPose.Builder builder = Poses.custom("every_bone");
            for (String bone : row.model().getBones().keySet())
                builder.bone(bone, stance -> stance.pitchBy(1));
            assertAgrees(shipped, entry.getKey(), builder.allAges().build());
            held++;
        }
        assertTrue(held >= 90, "the roster agrees whole, yet only " + held + " rows answered");
    }

    // ------------------------------------------------------------------------------------

    /**
     * Holds one audit to the installs it predicts over the same definitions. The audit throws
     * where a tolerant install refuses, with the same message; where it returns, a tolerant
     * install weaves, and a strict one refuses exactly where the audit reports an address
     * unreached, naming one of them.
     *
     * @param definitions the definitions both read
     * @param entityId the row the style installs on
     * @param style the style audited and installed
     */
    private static void assertAgrees(@NotNull ConcurrentMap<String, Entity> definitions, @NotNull String entityId,
                                     @NotNull BuiltStyle style) {
        Optional<String> strict = refusal(() -> StyleRegistrar.of(definitions).add(entityId, style));
        Optional<String> tolerant = refusal(() -> StyleRegistrar.of(definitions).addTolerant(entityId, style));
        PoseAudit audit;
        try {
            audit = PoseAuditor.validate(style, definitions.get(entityId));
        } catch (StyleException thrown) {
            assertEquals(Optional.of(thrown.getMessage()), tolerant,
                entityId + ": the audit refuses where a tolerant install does, and says what it says");
            return;
        }

        assertEquals(Optional.empty(), tolerant, entityId + ": the audit accepted what a tolerant install refuses");
        assertEquals(strict.isPresent(), !audit.drops().isEmpty(),
            entityId + ": " + strict.orElse("a strict install weaves") + "\n" + audit.report());
        strict.ifPresent(message -> assertTrue(audit.drops().stream().anyMatch(drop -> message.contains(drop.describe())),
            entityId + ": the install refused over " + message + " and the audit reported " + described(audit)));
    }

    /**
     * The message an install refuses with, empty where it weaves.
     */
    private static @NotNull Optional<String> refusal(@NotNull Executable install) {
        try {
            install.execute();
            return Optional.empty();
        } catch (StyleException refused) {
            return Optional.of(refused.getMessage());
        } catch (Throwable other) {
            throw new AssertionError("an install fails only by refusing", other);
        }
    }

    /**
     * A humanoid row carrying one size form, the small, that shares the row's pose instance over a
     * mesh resting every bone at the row's own scale and lacking the right arm - so the install
     * guards that mesh rather than compiling against it.
     */
    private static @NotNull Entity sizedRow() {
        EntityMesh armless = humanoid();
        armless.getBones().remove("right_arm");
        return sizedRow(humanoid(), armless);
    }

    /**
     * A row carrying one size form, the small, that shares the row's pose instance over a mesh of
     * its own.
     *
     * @param mesh the row's mesh
     * @param small the small form's mesh
     * @return the row
     */
    private static @NotNull Entity sizedRow(@NotNull EntityMesh mesh, @NotNull EntityMesh small) {
        return sizedRow(mesh, small, pose(List.of(), Map.of(), List.of()));
    }

    /**
     * A row carrying one size form, the small, that shares the row's pose instance over a mesh of
     * its own.
     *
     * @param mesh the row's mesh
     * @param small the small form's mesh
     * @param shipped the row's shipped pose, which the small form shares
     * @return the row
     */
    private static @NotNull Entity sizedRow(@NotNull EntityMesh mesh, @NotNull EntityMesh small,
                                            @NotNull EntityPose shipped) {
        Entity bare = entity(TEST, mesh, shipped, StyleCatalog.BIND_ONLY);
        LinkedHashMap<Size, Entity> sizes = new LinkedHashMap<>();
        sizes.put(Size.SMALL, bare.mutate().model(small).build());
        return bare.mutate()
            .axes(new Entity.Axes(Optional.empty(), Entity.Variation.none(), Entity.Variation.none(),
                new Entity.Variation<>(Concurrent.newUnmodifiableLinkedMap(sizes), Optional.empty()),
                Entity.Variation.none()))
            .build();
    }

    /**
     * A humanoid row carrying a shape axis - the declared option the row itself, and a large option
     * drawing the row's pose over a mesh of its own.
     *
     * @param large the large option's mesh
     * @return the row
     */
    private static @NotNull Entity shapedRow(@NotNull EntityMesh large) {
        Entity bare = entity(TEST, humanoid(), pose(List.of(), Map.of(), List.of()), StyleCatalog.BIND_ONLY);
        LinkedHashMap<String, Entity> shapes = new LinkedHashMap<>();
        shapes.put("small", bare);
        shapes.put("large", bare.mutate().model(large).build());
        return bare.mutate()
            .axes(new Entity.Axes(Optional.empty(),
                new Entity.Variation<>(Concurrent.newUnmodifiableLinkedMap(shapes), Optional.of("small")),
                Entity.Variation.none(), Entity.Variation.none(), Entity.Variation.none()))
            .build();
    }

    /**
     * One mesh with its parts resting at the scales vanilla's baby transform writes into each part's
     * own field - the head at 0.75 and every other part at 0.5 - everything else about each bone
     * untouched.
     *
     * @param mesh the mesh at one
     * @return a fresh mesh
     */
    private static @NotNull EntityMesh agedDown(@NotNull EntityMesh mesh) {
        EntityMesh aged = new EntityMesh();
        mesh.getBones().forEach((name, bone) -> aged.getBones().put(name, new EntityMesh.Bone(bone.getPivot(),
            bone.getRotation(), bone.getBindPoseRotation(), "head".equals(name) ? 0.75f : 0.5f, bone.getCubes(),
            bone.getParent())));
        return aged;
    }

    /**
     * The shipped definitions, skipping the case where the bundled tables are absent.
     */
    private static @NotNull ConcurrentMap<String, Entity> shipped() {
        ConcurrentMap<String, Entity> shipped = EntityModelLoader.load();
        assumeTrue(!shipped.isEmpty(), "bundled entity tables are present");
        return shipped;
    }

    /**
     * One shipped row, skipping the case where the bundled tables do not answer it.
     */
    private static @NotNull Entity row(@NotNull ConcurrentMap<String, Entity> shipped, @NotNull String entityId) {
        Entity row = shipped.get(entityId);
        assumeTrue(row != null, "bundled entity tables answer " + entityId);
        return row;
    }

    /**
     * The unreached addresses of an audit as their readings, in report order.
     */
    private static @NotNull List<String> described(@NotNull PoseAudit audit) {
        return audit.drops().stream().map(PoseCompiler.Unreached::describe).toList();
    }

    /**
     * The weave-subset warnings one style's install recorded over the guarded small form, in order.
     */
    private static @NotNull List<String> subsetWarnings(@NotNull StyleRegistrar registrar, @NotNull String styleId) {
        return registrar.diagnostics().entries().stream()
            .filter(entry -> entry.severity() == Diagnostics.Severity.WARN
                && entry.path().equals("styles/" + TEST + "/" + styleId + "/form/size:small/install")
                && entry.message().contains("weave-subset"))
            .map(Diagnostics.Entry::message)
            .toList();
    }

}
