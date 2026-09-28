package lib.minecraft.renderer.author.install;

import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.mesh.EntityMesh;
import lib.minecraft.renderer.asset.pose.PoseStyle;
import lib.minecraft.renderer.author.BuiltStyle;
import lib.minecraft.renderer.author.Poses;
import lib.minecraft.renderer.bake.pose.PosePlayer;
import lib.minecraft.renderer.content.index.EntityModelLoader;
import lib.minecraft.renderer.diagnostic.Diagnostics;
import lib.minecraft.renderer.engine.geometry.EulerRotation;
import lib.minecraft.renderer.engine.pose.PoseChannel;
import lib.minecraft.renderer.engine.pose.PoseExpr;
import lib.minecraft.renderer.math.Vector3f;
import lib.minecraft.renderer.request.AppearanceOptions;
import lib.minecraft.renderer.vanilla.appearance.Age;
import lib.minecraft.renderer.vanilla.appearance.Size;
import lib.minecraft.renderer.vanilla.appearance.TropicalFishPattern;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An install on the forms an appearance swaps in for a shipped row - the baby, each coat, the large
 * tropical fish and each pufferfish size - held to what a render of that form reads: the in-force
 * catalog accepts the installed id, the form's own mesh turns where the style writes, a pattern
 * pass drawn over the large fish turns and seats with its body, and the small pufferfish keeps
 * rolling the fins its own model writes.
 *
 * <p>One every-age probe serves every case: a turn on {@code body}, which every mesh here declares
 * and no loaded pose here turns about y, so the turn is the probe's alone, and a container step,
 * which every mesh here is flattened at one to take.
 */
@DisplayName("an install weaves every form an appearance swaps in")
class StyleRegistrarFormTest {

    /** The id the probe installs under. */
    private static final @NotNull String STYLE = "form_probe";

    /** The bone the probe turns, which every mesh this class poses declares. */
    private static final @NotNull String BONE = "body";

    /** How far past its bind yaw the probe turns the bone, in degrees. */
    private static final float TURN = 20f;

    /** The tick every form is posed at. */
    private static final int TICK = 6;

    /** How near two rotations or two pivots come to count as one. */
    private static final float EPSILON = 1e-4f;

    /** The name every container step of a posed mesh begins with. */
    private static final @NotNull String CONTAINER = "$container";

    /** The row whose size forms each carry a mesh and a pose of their own. */
    private static final @NotNull String PUFFERFISH = "minecraft:pufferfish";

    @Test
    @DisplayName("the wolf's baby lists the id in force and turns the bone on its own mesh")
    void theBabyTakesTheStyle() {
        assertTakes("minecraft:wolf", AppearanceOptions.builder().age(Age.BABY).build(),
            "the wolf's baby");
    }

    @Test
    @DisplayName("the wolf's declared coat resolves the id its row lists and turns the bone")
    void theDeclaredCoatTakesTheStyle() {
        assertTakes("minecraft:wolf", AppearanceOptions.builder().variant("pale").build(),
            "the wolf's declared coat 'pale'");
    }

    @Test
    @DisplayName("a named coat of the wolf resolves the id and turns the bone")
    void aNamedCoatTakesTheStyle() {
        assertTakes("minecraft:wolf", AppearanceOptions.builder().variant("ashen").build(),
            "the wolf's coat 'ashen'");
    }

    @Test
    @DisplayName("a coat's own baby resolves the id and turns the bone on the baby mesh")
    void aCoatsBabyTakesTheStyle() {
        assertTakes("minecraft:wolf", AppearanceOptions.builder().variant("ashen").age(Age.BABY).build(),
            "the baby of the wolf's coat 'ashen'");
    }

    @Test
    @DisplayName("the cow's cold coat, drawing a mesh of its own, resolves the id and turns the bone")
    void aCoatOfItsOwnMeshTakesTheStyle() {
        assertTakes("minecraft:cow", AppearanceOptions.builder().variant("cold").build(),
            "the cow's coat 'cold'");
    }

    @Test
    @DisplayName("the large tropical fish resolves the id, and every pattern pass turns and seats with its body")
    void theLargeFishsPatternFollowsItsBody() {
        AppearanceOptions large = AppearanceOptions.builder().pattern(TropicalFishPattern.FLOPPER).build();
        Entity posed = assertTakes("minecraft:tropical_fish", large, "the large tropical fish");

        EntityMesh body = posed.model();
        assertFalse(posed.overlays().isEmpty(), "the large form draws its pattern passes");
        assertTrue(body.getBones().keySet().stream().anyMatch(name -> name.startsWith(CONTAINER)),
            "the body carries the probe's container step");
        for (Entity.OverlayLayer pass : posed.overlays()) {
            for (String step : body.getBones().keySet())
                if (step.startsWith(CONTAINER))
                    assertTrue(pass.model().getBones().containsKey(step),
                        "the pattern carries the body's container step '" + step + "'");
            for (Map.Entry<String, EntityMesh.Bone> drawn : pass.model().getBones().entrySet()) {
                String name = drawn.getKey();
                EntityMesh.Bone under = body.getBones().get(name);
                assertNotNull(under, "the body declares every bone its pattern draws: '" + name + "'");
                assertTurnedAlike(under.getRotation(), drawn.getValue().getRotation(),
                    "'" + name + "' turns on the pattern as it turns on the body");
                if (name.startsWith(CONTAINER))
                    assertSeatedAlike(under.getPivot(), drawn.getValue().getPivot(),
                        "the pattern's container step '" + name + "' seats where the body's does");
            }
        }
    }

    @Test
    @DisplayName("the small pufferfish resolves the id and turns the bone on its own mesh under its own model's pose")
    void theSmallPufferfishTakesTheStyle() {
        assertTakes(PUFFERFISH, AppearanceOptions.builder().size(Size.SMALL).build(),
            "the small pufferfish");
    }

    @Test
    @DisplayName("the medium pufferfish resolves the id and turns the bone on its own mesh under its own model's pose")
    void theMediumPufferfishTakesTheStyle() {
        assertTakes(PUFFERFISH, AppearanceOptions.builder().size(Size.MEDIUM).build(),
            "the medium pufferfish");
    }

    @Test
    @DisplayName("an install leaves the small pufferfish rolling its own fins under a shipped style")
    void theSmallPufferfishKeepsItsFinsUnderAnInstall() {
        AppearanceOptions small = AppearanceOptions.builder().size(Size.SMALL).build();
        Entity installed = small.resolve(StyleRegistrar.ofShipped().add(PUFFERFISH, probe())
            .definitions().get(PUFFERFISH));
        Entity shipped = small.resolve(EntityModelLoader.load().get(PUFFERFISH));
        EntityMesh woven = idleAt(installed, small).model();
        EntityMesh pristine = idleAt(shipped, small).model();
        for (String fin : List.of("right_fin", "left_fin")) {
            float roll = woven.getBones().get(fin).getRotation().roll();
            assertEquals(pristine.getBones().get(fin).getRotation().roll(), roll, 0f,
                "'" + fin + "' rolls under idle as it did before the install");
            assertNotEquals(installed.model().getBones().get(fin).getRotation().roll(), roll,
                "'" + fin + "' leaves its bind roll");
        }
    }

    @Test
    @DisplayName("a fin the small pufferfish lacks refuses a strict install on its form, and a tolerant one weaves the rest")
    void aFinTheSmallPufferfishLacksRefusesStrictly() {
        BuiltStyle flick = Poses.custom("fin_flick").bone("top_front_fin", fin -> fin.pitchBy(10)).build();
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
            () -> StyleRegistrar.ofShipped().add(PUFFERFISH, flick));
        assertTrue(refused.getMessage().contains("form '$size:small'")
                && refused.getMessage().contains("'top_front_fin'"),
            "the refusal names the small form and the fin its mesh lacks: " + refused.getMessage());
        assertDoesNotThrow(() -> StyleRegistrar.ofShipped().addTolerant(PUFFERFISH, flick),
            "a tolerant install weaves the fin where a mesh declares it");
    }

    @Test
    @DisplayName("a raw read of a fin the small pufferfish lacks refuses at install rather than throwing at render")
    void aRawReadASizeMeshLacksRefuses() {
        BuiltStyle glare = Poses.custom("fin_glare")
            .expr(BONE, PoseChannel.X_ROT, new PoseExpr.BoneRead("left_blue_fin", PoseChannel.X_ROT))
            .build();
        StyleRegistrar registrar = StyleRegistrar.ofShipped();

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
            () -> registrar.add(PUFFERFISH, glare));
        assertTrue(refused.getMessage().contains("'left_blue_fin'"),
            "the refusal names the fin the small mesh lacks: " + refused.getMessage());
        assertTrue(registrar.diagnostics().entries().stream().anyMatch(entry ->
                entry.severity() == Diagnostics.Severity.ERROR
                    && entry.path().equals("styles/minecraft:pufferfish/fin_glare/form/size:small/compile")),
            "and records under the size form whose mesh lacks it");
    }

    // ------------------------------------------------------------------------------------

    /**
     * The every-age probe - a turn on {@link #BONE} and a container step.
     */
    private static @NotNull BuiltStyle probe() {
        return Poses.custom(STYLE)
            .bone(BONE, body -> body.yawBy(TURN))
            .container(step -> step.offset(0, 2, 0))
            .allAges()
            .build();
    }

    /**
     * Installs the probe strictly on one shipped row, resolves one appearance of it the way a
     * render does, and holds the form it swaps in to the probe: the in-force catalog accepts the
     * id, and the form's own mesh turns {@link #BONE} by {@link #TURN} past its bind yaw.
     *
     * @param entityId the shipped row the probe installs on
     * @param appearance the appearance selecting the form
     * @param form how a failure names the form
     * @return the resolved subject, posed at {@link #TICK}
     */
    private static @NotNull Entity assertTakes(@NotNull String entityId, @NotNull AppearanceOptions appearance,
                                               @NotNull String form) {
        Entity row = StyleRegistrar.ofShipped().add(entityId, probe()).definitions().get(entityId);
        Entity resolved = appearance.resolve(row);
        PoseStyle style = assertDoesNotThrow(
            () -> resolved.styles().resolve(STYLE, appearance::applies, entityId),
            form + " lists the installed id in force");
        EntityMesh.Bone bind = resolved.model().getBones().get(BONE);
        assertNotNull(bind, form + "'s mesh declares '" + BONE + "'");

        Entity posed = PosePlayer.posed(resolved, style, resolved.styles().periodTicks(), TICK);
        assertEquals(bind.getRotation().yaw() + TURN, posed.model().getBones().get(BONE).getRotation().yaw(), 1e-3f,
            form + " turns '" + BONE + "' on its own mesh by what the style writes");
        return posed;
    }

    /**
     * One subject where its own idle row leaves it at {@link #TICK}.
     *
     * @param subject the resolved subject
     * @param appearance the appearance it was resolved for
     * @return the subject posed under idle
     */
    private static @NotNull Entity idleAt(@NotNull Entity subject, @NotNull AppearanceOptions appearance) {
        PoseStyle idle = subject.styles().resolve(PoseStyle.IDLE, appearance::applies, PUFFERFISH);
        return PosePlayer.posed(subject, idle, subject.styles().periodTicks(), TICK);
    }

    /**
     * Holds two rotations to one another, axis by axis.
     */
    private static void assertTurnedAlike(@NotNull EulerRotation expected, @NotNull EulerRotation actual,
                                          @NotNull String message) {
        assertEquals(expected.pitch(), actual.pitch(), EPSILON, message + " (pitch)");
        assertEquals(expected.yaw(), actual.yaw(), EPSILON, message + " (yaw)");
        assertEquals(expected.roll(), actual.roll(), EPSILON, message + " (roll)");
    }

    /**
     * Holds two pivots to one another, axis by axis.
     */
    private static void assertSeatedAlike(@NotNull Vector3f expected, @NotNull Vector3f actual,
                                          @NotNull String message) {
        assertEquals(expected.x(), actual.x(), EPSILON, message + " (x)");
        assertEquals(expected.y(), actual.y(), EPSILON, message + " (y)");
        assertEquals(expected.z(), actual.z(), EPSILON, message + " (z)");
    }

}
