package lib.minecraft.renderer.author.install;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.mesh.EntityMesh;
import lib.minecraft.renderer.asset.pose.StyleCatalog;
import lib.minecraft.renderer.author.BuiltStyle;
import lib.minecraft.renderer.author.CustomPose;
import lib.minecraft.renderer.author.Poses;
import lib.minecraft.renderer.author.audit.PoseAudit;
import lib.minecraft.renderer.author.audit.PoseAuditor;
import lib.minecraft.renderer.author.compile.PoseCompiler;
import lib.minecraft.renderer.content.index.EntityModelLoader;
import lib.minecraft.renderer.diagnostic.Diagnostics;
import lib.minecraft.renderer.engine.pose.PoseChannel;
import lib.minecraft.renderer.engine.pose.PoseExpr;
import lib.minecraft.renderer.vanilla.appearance.Size;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static lib.minecraft.renderer.fixture.CompilerFixtures.humanoid;
import static lib.minecraft.renderer.fixture.CompilerFixtures.pose;
import static lib.minecraft.renderer.fixture.RegistrarFixtures.definitions;
import static lib.minecraft.renderer.fixture.RegistrarFixtures.entity;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
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

    /** The id of the hand-built row whose one size form lends its mesh to the row's pose. */
    private static final @NotNull String TEST = "minecraft:test";

    @Test
    @DisplayName("a bone the baby's mesh lacks is reported and refused alike - the wolf's mane, written at every age")
    void aBabyMissingTheWrittenBoneIsReported() {
        ConcurrentMap<String, Entity> shipped = shipped();
        BuiltStyle perk = Poses.custom("perk").bone("upper_body", mane -> mane.pitchBy(10)).allAges().build();

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
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

        IllegalArgumentException installed = assertThrows(IllegalArgumentException.class,
            () -> StyleRegistrar.of(shipped).add(PUFFERFISH, glare));
        IllegalArgumentException audited = assertThrows(IllegalArgumentException.class,
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

        IllegalArgumentException installed = assertThrows(IllegalArgumentException.class,
            () -> registrar.add(TEST, glare));
        IllegalArgumentException audited = assertThrows(IllegalArgumentException.class,
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
        } catch (IllegalArgumentException thrown) {
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
        } catch (IllegalArgumentException refused) {
            return Optional.of(refused.getMessage());
        } catch (Throwable other) {
            throw new AssertionError("an install fails only by refusing", other);
        }
    }

    /**
     * A humanoid row carrying one size form, the small, that shares the row's pose instance over a
     * mesh lacking the right arm - so the install guards that mesh rather than compiling against it.
     */
    private static @NotNull Entity sizedRow() {
        Entity bare = entity(TEST, humanoid(), pose(List.of(), Map.of(), List.of()), StyleCatalog.BIND_ONLY);
        EntityMesh armless = humanoid();
        armless.getBones().remove("right_arm");
        Entity small = bare.mutate().model(armless).build();
        LinkedHashMap<Size, Entity> sizes = new LinkedHashMap<>();
        sizes.put(Size.SMALL, small);
        return bare.mutate()
            .axes(new Entity.Axes(Optional.empty(), Entity.Variation.none(), Entity.Variation.none(),
                new Entity.Variation<>(Concurrent.newUnmodifiableLinkedMap(sizes), Optional.empty()),
                Entity.Variation.none()))
            .build();
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

}
