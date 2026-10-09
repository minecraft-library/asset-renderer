package lib.minecraft.renderer.author.install;

import dev.simplified.collection.Concurrent;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.mesh.EntityMesh;
import lib.minecraft.renderer.asset.pose.PoseStyle;
import lib.minecraft.renderer.author.BuiltStyle;
import lib.minecraft.renderer.author.Poses;
import lib.minecraft.renderer.author.Preset;
import lib.minecraft.renderer.bake.pose.PosePlayer;
import lib.minecraft.renderer.call.request.AppearanceOptions;
import lib.minecraft.renderer.content.index.EntityModelLoader;
import lib.minecraft.renderer.diagnostic.Diagnostics;
import lib.minecraft.renderer.engine.geometry.EulerRotation;
import lib.minecraft.renderer.engine.math.Vector3f;
import lib.minecraft.renderer.engine.pose.PoseChannel;
import lib.minecraft.renderer.engine.pose.PoseExpr;
import lib.minecraft.renderer.engine.pose.PoseOperator;
import lib.minecraft.renderer.engine.pose.PoseWidth;
import lib.minecraft.renderer.engine.pose.StyleDriver;
import lib.minecraft.renderer.fixture.RegistrarFixtures;
import lib.minecraft.renderer.vanilla.appearance.Age;
import lib.minecraft.renderer.vanilla.appearance.Size;
import lib.minecraft.renderer.vanilla.appearance.TropicalFishPattern;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static lib.minecraft.renderer.fixture.CompilerFixtures.chainAt;
import static lib.minecraft.renderer.fixture.CompilerFixtures.drawnScale;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An install on the forms an appearance swaps in for a shipped row - the baby, each coat, the large
 * tropical fish, each pufferfish size, each salmon size and the small armour stand - held to what a
 * render of that form reads: the in-force catalog accepts the installed id, the form's own mesh
 * turns where the style writes, a pattern pass drawn over the large fish turns and seats with its
 * body, the small pufferfish keeps rolling the fins its own model writes, a salmon size moves a
 * bone by the pixels the style writes, and the small armour stand turns the arms its mesh carries.
 *
 * <p>One every-age probe serves every form it is installed on: a turn on {@code body}, which each
 * of those meshes declares and no loaded pose there turns about y, so the turn is the probe's alone,
 * and a container step, which seats at the pixels written whatever factor a mesh is flattened at.
 * The cat's adult is flattened at 0.8 and its baby at one, and a bone offset is held over each
 * mesh's own factor, so the cat takes an offset-only settle as well, which lands at the authored
 * pixels on both meshes. A bone scale is vanilla's field, which each mesh draws under its own
 * factor, so the cat takes a scale as well, which draws the bone at that factor times the authored
 * scale on both meshes, as vanilla draws a part under its scaled root. The salmon's small and large
 * meshes are flattened at 0.5 and 1.5 under the row's own pose, and take the same settle and the
 * same scale on {@code body_front}, a salmon declaring no {@code body}, and a bob on it as well,
 * since a clip's position keyframes cross the factor too. The happy ghast's body parents its core
 * and every tentacle, so a scale on it at either age carries them with it, and a raw scale on it
 * writes vanilla's field, so a literal and a multiple of the field it reads draw alike under the
 * factor each age's root scales by. The small armour stand draws the row's own pose over parts
 * resting at three quarters for the head and half for every other part, under the row's own factor
 * of one, so a scale on its body and head replaces each part's own rest and draws at the authored
 * scale, as the large stand does, and an aim on its head solves from the pivot its own mesh rests
 * the head at; a statue spelled from the stand's finished attack holds each stand's arms where that
 * stand's own mesh rests them, which is where vanilla's attack leaves them at that stand's age. The
 * horse's head and the bee's body are anatomy a legged verb names: the adult's
 * part sits at the pivot of the articulation its pose turns, so the verb's turn climbs to that
 * articulation, while the baby's part carries a pivot of its own; the scale stays on the part named
 * at either age, growing it and its children and nothing hung beside it.
 */
@DisplayName("an install weaves every form an appearance swaps in")
class StyleRegistrarFormTest {

    /** The id the probe installs under. */
    private static final @NotNull String STYLE = "form_probe";

    /** The bone the probe turns, which every mesh the probe is installed on declares. */
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

    /** The row whose adult is flattened at a factor its baby is not. */
    private static final @NotNull String CAT = "minecraft:cat";

    /** The row whose size forms draw its own pose over meshes flattened at factors of their own. */
    private static final @NotNull String SALMON = "minecraft:salmon";

    /** The top-level salmon bone the settle and the bob move. */
    private static final @NotNull String SALMON_BONE = "body_front";

    /** The row whose body parents a core and nine tentacles, at a factor per age. */
    private static final @NotNull String HAPPY_GHAST = "minecraft:happy_ghast";

    /** The row whose small size draws its own pose over parts resting at the baby transform's scales. */
    private static final @NotNull String ARMOR_STAND = "minecraft:armor_stand";

    /** The row whose adult hangs its head cube at the pivot of the neck assembly its pose turns. */
    private static final @NotNull String HORSE = "minecraft:horse";

    /** The row whose adult hangs its body at the pivot of the root its pose turns. */
    private static final @NotNull String BEE = "minecraft:bee";

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
    @DisplayName("a baby flattened at a factor its adult is not lands an offset at the authored pixels, as the adult does")
    void aBabysOffsetLandsTheAuthoredPixels() {
        BuiltStyle settle = Poses.custom("settle").bone(BONE, body -> body.offset(0, 2, 0)).allAges().build();
        Entity pristine = EntityModelLoader.load().get(CAT);
        Entity row = StyleRegistrar.ofShipped().add(CAT, settle).definitions().get(CAT);
        PoseStyle installed = row.styles().styles().stream()
            .filter(style -> style.id().equals("settle"))
            .findFirst().orElseThrow();
        float adultFactor = pristine.model().getFlattenedScale();
        float babyFactor = pristine.axes().baby().orElseThrow().model().getFlattenedScale();
        assertNotEquals(adultFactor, babyFactor, "the cat's two meshes are flattened at different factors");

        assertEquals(2f / adultFactor, installed.drivers().get("style$settle$body$y").extent(), 1e-6f,
            "the row's field holds the authored pixels over its own factor");
        StyleDriver baby = installed.drivers().get("style$settle$$age:baby$body$y");
        assertNotNull(baby, "the baby spells a field of its own");
        assertEquals(2f / babyFactor, baby.extent(), 1e-6f, "holding the authored pixels over the baby's factor");

        // Measured against the shipped pose under the same frame, so what each model writes to its
        // body at rest drops out.
        for (Age age : List.of(Age.ADULT, Age.BABY)) {
            AppearanceOptions appearance = AppearanceOptions.builder().age(age).build();
            Entity resolved = appearance.resolve(row);
            Entity plain = appearance.resolve(pristine);
            PoseStyle style = resolved.styles().resolve("settle", appearance::applies, CAT);
            int period = resolved.styles().periodTicks();
            float settled = PosePlayer.posed(resolved, style, period, TICK).model().getBones().get(BONE).getPivot().y();
            float shipped = PosePlayer.posed(plain.pose(), plain.model(), style, period, TICK).getBones().get(BONE).getPivot().y();
            assertEquals(2f, settled - shipped, 1e-4f, age + " moves its body by the authored pixels");
        }
    }

    @Test
    @DisplayName("a baby flattened at a factor its adult is not draws a scaled bone at its own factor times the authored scale, as the adult does")
    void aBabysScaleMultipliesItsOwnFactor() {
        BuiltStyle bulk = Poses.custom("bulk").bone(BONE, body -> body.scale(1.5)).allAges().build();
        Entity pristine = EntityModelLoader.load().get(CAT);
        Entity row = StyleRegistrar.ofShipped().add(CAT, bulk).definitions().get(CAT);
        PoseStyle installed = row.styles().styles().stream()
            .filter(style -> style.id().equals("bulk"))
            .findFirst().orElseThrow();
        assertEquals(0.8f, pristine.model().getFlattenedScale(), "the cat's adult is flattened at 0.8");
        assertEquals(1f, pristine.axes().baby().orElseThrow().model().getFlattenedScale(),
            "and its baby at nothing");

        assertEquals(0.5f, installed.drivers().get("style$bulk$body$scale").extent(), 1e-6f,
            "the row's field holds the authored scale less the field's rest of one, the factor riding the root");
        StyleDriver baby = installed.drivers().get("style$bulk$$age:baby$body$scale");
        assertNotNull(baby, "the baby spells a field of its own");
        assertEquals(0.5f, baby.extent(), 1e-6f, "holding the authored scale less the baby's rest of one");

        Map<Age, Float> rests = Map.of(Age.ADULT, 0.8f, Age.BABY, 1f);
        Map<Age, Float> drawn = Map.of(Age.ADULT, 1.2f, Age.BABY, 1.5f);
        for (Map.Entry<Age, Float> expected : drawn.entrySet()) {
            Age age = expected.getKey();
            AppearanceOptions appearance = AppearanceOptions.builder().age(age).build();
            Entity resolved = appearance.resolve(row);
            PoseStyle style = resolved.styles().resolve("bulk", appearance::applies, CAT);
            EntityMesh posed = PosePlayer.posed(resolved, style, resolved.styles().periodTicks(), TICK).model();
            EntityMesh.Bone body = posed.getBones().get(BONE);
            assertEquals(rests.get(age), body.getScale(), 1e-6f, age + " keeps its body at the factor it rests at");
            assertRatio(1.5f, body, age + " rides the authored scale over that factor");
            assertEquals(expected.getValue(), drawnScale(posed, BONE), 1e-5f,
                age + " draws its body at its own factor times the authored scale");
        }
    }

    @Test
    @DisplayName("the happy ghast's scaled body carries its core and every tentacle with it, at either age")
    void theHappyGhastsChildrenFollowItsBody() {
        // Vanilla's body field reaches inner_body and the tentacles through the stack, so each draws at
        // its own rest times the written ratio and hangs at its pivot swung out by it about the body's.
        // The adult is flattened at 4 and the baby at 0.95, each ratio landing over its own rest.
        BuiltStyle bulk = Poses.custom("bulk").bone(BONE, body -> body.scale(1.5)).allAges().build();
        Entity row = StyleRegistrar.ofShipped().add(HAPPY_GHAST, bulk).definitions().get(HAPPY_GHAST);

        Map<Age, Float> rests = Map.of(Age.ADULT, 4f, Age.BABY, 0.95f);
        Map<Age, Vector3f> tentacles = Map.of(
            Age.ADULT, new Vector3f(-22.5f, 33.951996f, -30f),
            Age.BABY, new Vector3f(-5.34375f, 26.3758f, -7.125f));
        for (Age age : List.of(Age.ADULT, Age.BABY)) {
            AppearanceOptions appearance = AppearanceOptions.builder().age(age).build();
            Entity resolved = appearance.resolve(row);
            PoseStyle style = resolved.styles().resolve("bulk", appearance::applies, HAPPY_GHAST);
            EntityMesh posed = PosePlayer.posed(resolved, style, resolved.styles().periodTicks(), TICK).model();
            float rest = rests.get(age);

            EntityMesh.Bone body = posed.getBones().get(BONE);
            assertEquals(rest, body.getScale(), 0f, age + " keeps its body at the factor it rests at");
            assertRatio(1.5f, body, age + " rides the authored scale over that factor");
            assertFalse(posed.getBones().get("inner_body").isPoseScaled(), age + "'s core takes no scale of its own");
            assertEquals(rest * 1.5f, drawnScale(posed, "inner_body"), 1e-4f,
                age + "'s core draws at its rest times the body's ratio");
            assertSeatedAlike(tentacles.get(age), chainAt(posed, "tentacle0"),
                age + "'s first tentacle hangs at its pivot swung out by the body's ratio");
        }
    }

    @Test
    @DisplayName("a harnessed happy ghast keeps playing an installed style, its body at the harnessed size unless the style scales it")
    void aHarnessedGhastKeepsPlayingAnInstalledStyle() {
        // The filled body slot swaps the harnessed row in at resolve, so a weave that never reached
        // that row would swap the installed style out with it. The style's scale is a field value and
        // replaces the squeeze: 1.5 over the adult's factor of 4 draws at 6, harness or not.
        AppearanceOptions harnessed = AppearanceOptions.builder().equipment(Map.of("body", "white_harness")).build();

        BuiltStyle bulk = Poses.custom("bulk").bone(BONE, body -> body.scale(1.5)).allAges().build();
        Entity bulky = harnessed.resolve(StyleRegistrar.ofShipped().add(HAPPY_GHAST, bulk).definitions().get(HAPPY_GHAST));
        PoseStyle bulkRow = bulky.styles().resolve("bulk", harnessed::applies, HAPPY_GHAST);
        EntityMesh scaled = PosePlayer.posed(bulky, bulkRow, bulky.styles().periodTicks(), TICK).model();
        assertEquals(6f, drawnScale(scaled, BONE), 1e-4f, "the style's own body scale replaces the squeeze");

        BuiltStyle sway = Poses.custom("sway").bone("tentacle0", tentacle -> tentacle.pitchBy(30)).allAges().build();
        Entity swaying = harnessed.resolve(StyleRegistrar.ofShipped().add(HAPPY_GHAST, sway).definitions().get(HAPPY_GHAST));
        PoseStyle swayRow = swaying.styles().resolve("sway", harnessed::applies, HAPPY_GHAST);
        EntityMesh swung = PosePlayer.posed(swaying, swayRow, swaying.styles().periodTicks(), TICK).model();
        EntityMesh unswung = PosePlayer.posed(harnessed.resolve(EntityModelLoader.load().get(HAPPY_GHAST)),
            swayRow, swaying.styles().periodTicks(), TICK).model();
        assertEquals(3.75f, drawnScale(swung, BONE), 1e-4f, "a style leaving the body unscaled keeps the harnessed size");
        assertNotEquals(unswung.getBones().get("tentacle0").getRotation(), swung.getBones().get("tentacle0").getRotation(),
            "and the style still turns the tentacle it names");
    }

    @Test
    @DisplayName("a literal raw scale on the happy ghast's body is vanilla's field, drawn under the factor each age's root scales by")
    void aLiteralRawScaleIsVanillasField() {
        // Vanilla's body field rests at one under a root scaled by 4 on the adult and 0.95 on the
        // baby, so a field assigned 1.5 draws the body, and the core it carries, at 6 and at 1.425.
        PoseExpr grown = new PoseExpr.Constant(1.5d, PoseWidth.FLOAT);
        assertGhastBodyDrawsItsField(Poses.custom("swell")
            .expr(BONE, PoseChannel.X_SCALE, grown)
            .expr(BONE, PoseChannel.Y_SCALE, grown)
            .expr(BONE, PoseChannel.Z_SCALE, grown)
            .allAges()
            .build(), "a literal field of 1.5");
    }

    @Test
    @DisplayName("a raw scale reading the happy ghast's body field draws what a literal of its value draws")
    void aRelativeRawScaleReadsAndWritesOneField() {
        // The read and the write are one unit, so a raw multiplying the field it reads draws the same
        // under either: the field reads one on both ages, and 1.5 of it is the literal above.
        PoseExpr grown = new PoseExpr.Op(PoseOperator.MUL, Concurrent.newUnmodifiableList(
            new PoseExpr.BoneRead(BONE, PoseChannel.X_SCALE), new PoseExpr.Constant(1.5d, PoseWidth.FLOAT)));
        assertGhastBodyDrawsItsField(Poses.custom("swell")
            .expr(BONE, PoseChannel.X_SCALE, grown)
            .expr(BONE, PoseChannel.Y_SCALE, grown)
            .expr(BONE, PoseChannel.Z_SCALE, grown)
            .allAges()
            .build(), "1.5 times the field it reads");
    }

    @Test
    @DisplayName("a salmon size drawing the row's pose over a mesh flattened apart lands an offset at the authored pixels, as the row does")
    void aSalmonSizesOffsetLandsTheAuthoredPixels() {
        BuiltStyle settle = Poses.custom("settle").bone(SALMON_BONE, front -> front.offset(0, 2, 0)).allAges().build();
        Entity pristine = EntityModelLoader.load().get(SALMON);
        Entity row = StyleRegistrar.ofShipped().add(SALMON, settle).definitions().get(SALMON);
        PoseStyle installed = row.styles().styles().stream()
            .filter(style -> style.id().equals("settle"))
            .findFirst().orElseThrow();
        assertEquals(1f, pristine.model().getFlattenedScale(), "the row's mesh is flattened at nothing");
        assertEquals(2f, installed.drivers().get("style$settle$" + SALMON_BONE + "$y").extent(), 1e-6f,
            "the row's field holds the authored pixels");

        Map<Size, Float> fields = Map.of(Size.SMALL, 4f, Size.LARGE, 1.3333334f);
        for (Map.Entry<Size, Float> expected : fields.entrySet()) {
            Size size = expected.getKey();
            Entity form = pristine.axes().size().select(size).orElseThrow();
            assertSame(pristine.pose(), form.pose(), "the " + size + " salmon shares the row's pose instance");
            assertNotEquals(1f, form.model().getFlattenedScale(), "over a mesh flattened at a factor of its own");
            StyleDriver own = installed.drivers().get("style$settle$$size:" + size.name().toLowerCase(Locale.ROOT)
                + "$" + SALMON_BONE + "$y");
            assertNotNull(own, "the " + size + " salmon spells a field of its own");
            assertEquals(expected.getValue(), own.extent(), 1e-6f,
                "holding the authored pixels over the " + size + " salmon's factor");
        }

        for (Size size : List.of(Size.SMALL, Size.MEDIUM, Size.LARGE))
            assertEquals(2f, salmonMoved(row, pristine, "settle", size), 1e-4f,
                size + " moves '" + SALMON_BONE + "' by the authored pixels");
    }

    @Test
    @DisplayName("a salmon size drawing the row's pose over a mesh flattened apart draws a scaled bone at its own factor times the authored scale")
    void aSalmonSizesScaleMultipliesItsOwnFactor() {
        BuiltStyle bulk = Poses.custom("bulk").bone(SALMON_BONE, front -> front.scale(1.5)).allAges().build();
        Entity row = StyleRegistrar.ofShipped().add(SALMON, bulk).definitions().get(SALMON);

        Map<Size, Float> factors = Map.of(Size.SMALL, 0.5f, Size.MEDIUM, 1f, Size.LARGE, 1.5f);
        for (Map.Entry<Size, Float> flattened : factors.entrySet()) {
            Size size = flattened.getKey();
            AppearanceOptions appearance = AppearanceOptions.builder().size(size).build();
            Entity resolved = appearance.resolve(row);
            assertEquals(flattened.getValue(), resolved.model().getFlattenedScale(),
                "the " + size + " salmon's mesh is flattened at " + flattened.getValue());
            PoseStyle style = resolved.styles().resolve("bulk", appearance::applies, SALMON);
            EntityMesh posed = PosePlayer.posed(resolved, style, resolved.styles().periodTicks(), TICK).model();
            EntityMesh.Bone front = posed.getBones().get(SALMON_BONE);
            assertEquals(flattened.getValue(), front.getScale(), 1e-6f,
                size + " keeps '" + SALMON_BONE + "' at the factor it rests at");
            assertRatio(1.5f, front, size + " rides the authored scale over that factor");
            assertEquals(1.5f * flattened.getValue(), drawnScale(posed, SALMON_BONE), 1e-5f,
                size + " draws '" + SALMON_BONE + "' at its own factor times the authored scale");
        }
    }

    @Test
    @DisplayName("the small armour stand, resting its parts apart from the large one's under one factor, draws a scaled part at the authored scale, as the large one does")
    void theSmallArmorStandDrawsTheAuthoredScale() {
        // Vanilla's baby transform writes 0.75 into the head's own scale field and 0.5 into every
        // other part's, and a write replaces that field, so the small stand draws both at 1.5.
        BuiltStyle bulk = Poses.custom("bulk")
            .bone(BONE, body -> body.scale(1.5))
            .bone("head", head -> head.scale(1.5))
            .allAges()
            .build();
        Entity row = StyleRegistrar.ofShipped().add(ARMOR_STAND, bulk).definitions().get(ARMOR_STAND);
        PoseStyle installed = row.styles().styles().stream()
            .filter(style -> style.id().equals("bulk"))
            .findFirst().orElseThrow();

        Map<String, Float> rests = Map.of(BONE, 0.5f, "head", 0.75f);
        for (Size size : List.of(Size.SMALL, Size.LARGE)) {
            AppearanceOptions appearance = AppearanceOptions.builder().size(size).build();
            Entity resolved = appearance.resolve(row);
            PoseStyle style = resolved.styles().resolve("bulk", appearance::applies, ARMOR_STAND);
            EntityMesh posed = PosePlayer.posed(resolved, style, resolved.styles().periodTicks(), TICK).model();
            for (String bone : List.of(BONE, "head"))
                assertEquals(1.5f, drawnScale(posed, bone), 1e-5f,
                    "the " + size + " stand draws '" + bone + "' at the authored scale");
        }

        for (Map.Entry<String, Float> rest : rests.entrySet()) {
            String bone = rest.getKey();
            StyleDriver small = installed.drivers().get("style$bulk$$size:small$" + bone + "$scale");
            assertNotNull(small, "the small stand spells a field of its own for '" + bone + "'");
            assertEquals(1.5f - rest.getValue(), small.extent(), 1e-6f,
                "holding the authored scale less the rest '" + bone + "' takes there");
        }
    }

    @Test
    @DisplayName("a legged head scale lands on the head the adult horse and the foal both name, while the adult's turn climbs to its neck assembly")
    void aLeggedHeadScaleStaysOnTheHeadAtEitherAge() {
        // Vanilla draws a part's scale field over that part and its children alone, so the head's
        // field grows the head cube and its ears and never the neck, mane or mouth hung beside it.
        // The adult's head sits at the neck assembly's pivot, so its turn climbs to the assembly its
        // pose turns; the foal's head carries a pivot of its own. The adult is flattened at 1.1.
        BuiltStyle crane = Poses.legged("crane").head(head -> head.pitchBy(10).scale(1.5)).allAges().build();
        StyleRegistrar registrar = StyleRegistrar.ofShipped().add(HORSE, crane);
        Entity pristine = EntityModelLoader.load().get(HORSE);
        Entity row = registrar.definitions().get(HORSE);

        Map<Age, Float> factors = Map.of(Age.ADULT, 1.1f, Age.BABY, 1f);
        for (Map.Entry<Age, Float> flattened : factors.entrySet()) {
            Age age = flattened.getKey();
            EntityMesh posed = posedAt(row, HORSE, "crane", age);
            assertRatio(1.5f, posed.getBones().get("head"), age + " rides the authored scale on the head it names");
            assertFalse(posed.getBones().get("head_parts").isPoseScaled(), age + "'s neck assembly takes no scale");
            for (String part : List.of("head", "left_ear"))
                assertEquals(1.5f * flattened.getValue(), drawnScale(posed, part), 1e-5f,
                    age + " draws '" + part + "' at its factor times the authored scale");
        }
        EntityMesh adult = posedAt(row, HORSE, "crane", Age.ADULT);
        for (String beside : List.of("mane", "upper_mouth"))
            assertEquals(1.1f, drawnScale(adult, beside), 1e-5f,
                "the adult's '" + beside + "' hangs beside the head and draws at its factor alone");
        assertEquals(10f, pitchedBy(row, pristine, HORSE, "crane", Age.ADULT, "head_parts"), 1e-3f,
            "the adult's turn climbs to the neck assembly its pose turns");
        assertEquals(0f, pitchedBy(row, pristine, HORSE, "crane", Age.ADULT, "head"), 1e-3f,
            "and leaves the head cube where the assembly carries it");
        assertEquals(10f, pitchedBy(row, pristine, HORSE, "crane", Age.BABY, "head"), 1e-3f,
            "the foal's turn stays on the head, which carries a pivot of its own");
        assertTrue(registrar.diagnostics().entries().stream().anyMatch(entry ->
                entry.severity() == Diagnostics.Severity.INFO
                    && entry.message().equals("scale: 'head' stays on the part named; its turns land on 'head_parts'")),
            "the adult's compile records the part the scale stays on beside the bone the turns land on");
        assertTrue(registrar.diagnostics().entries().stream().noneMatch(entry ->
                entry.message().equals("scale: 'head' stays on the part named; its turns land on 'head'")),
            "and the foal's, whose scale and turns land on one bone, records nothing");
    }

    @Test
    @DisplayName("a legged body scale lands on the body the adult bee and the baby both name, while the adult's turn climbs to the root its wings and legs hang from")
    void aLeggedBodyScaleStaysOnTheBodyAtEitherAge() {
        // The bee's body parents its stinger and antennae, and its root parents the body, the wings
        // and the legs beside it. The adult's body sits at the root's pivot, so its turn climbs to the
        // root its pose turns; the baby's body carries a pivot of its own. Neither age is flattened.
        BuiltStyle puff = Poses.legged("puff").body(body -> body.pitchBy(10).scale(1.5)).allAges().build();
        Entity pristine = EntityModelLoader.load().get(BEE);
        Entity row = StyleRegistrar.ofShipped().add(BEE, puff).definitions().get(BEE);

        for (Age age : List.of(Age.ADULT, Age.BABY)) {
            EntityMesh posed = posedAt(row, BEE, "puff", age);
            assertRatio(1.5f, posed.getBones().get(BONE), age + " rides the authored scale on the body it names");
            assertFalse(posed.getBones().get("bone").isPoseScaled(), age + "'s root takes no scale");
            assertEquals(1.5f, drawnScale(posed, "stinger"), 1e-5f, age + " draws the stinger with the body");
        }
        EntityMesh adult = posedAt(row, BEE, "puff", Age.ADULT);
        assertEquals(1.5f, drawnScale(adult, "left_antenna"), 1e-5f, "the adult draws its antenna with the body");
        for (String beside : List.of("right_wing", "front_legs"))
            assertEquals(1f, drawnScale(adult, beside), 1e-5f,
                "the adult's '" + beside + "' hangs from the root beside the body and keeps its rest");
        assertEquals(10f, pitchedBy(row, pristine, BEE, "puff", Age.ADULT, "bone"), 1e-3f,
            "the adult's turn climbs to the root its pose turns");
        assertEquals(0f, pitchedBy(row, pristine, BEE, "puff", Age.ADULT, BONE), 1e-3f,
            "and leaves the body where the root carries it");
    }

    @Test
    @DisplayName("a strict humanoid preset installs on the armour stand, and the small stand turns its own arms")
    void aHumanoidPresetTurnsTheSmallArmorStandsArms() {
        BuiltStyle tPose = Poses.humanoid("t_pose").preset(Preset.T_POSE).allAges().build();
        Entity row = assertDoesNotThrow(
            () -> StyleRegistrar.ofShipped().add(ARMOR_STAND, tPose).definitions().get(ARMOR_STAND),
            "a strict install finds every arm it writes on every form of the stand");

        AppearanceOptions small = AppearanceOptions.builder().size(Size.SMALL).toggles(Set.of("arms")).build();
        Entity resolved = small.resolve(row);
        PoseStyle style = resolved.styles().resolve("t_pose", small::applies, ARMOR_STAND);
        EntityMesh posed = PosePlayer.posed(resolved, style, resolved.styles().periodTicks(), TICK).model();
        assertTurnedAlike(new EulerRotation(0f, 0f, 90f), posed.getBones().get("right_arm").getRotation(),
            "the small stand's 'right_arm' takes the preset's turn");
        assertTurnedAlike(new EulerRotation(0f, 0f, -90f), posed.getBones().get("left_arm").getRotation(),
            "and so does its 'left_arm'");
    }

    @Test
    @DisplayName("the small armour stand aims its head from the pivot its own mesh rests it at, as the large one aims from its")
    void theSmallArmorStandAimsFromItsOwnPivot() {
        // Vanilla poses the small model's own parts, so an aim solves from the pivot the small mesh
        // rests its head at rather than from the large mesh's.
        BuiltStyle gaze = Poses.humanoid("gaze").head(head -> head.aimAt(18, -30, -14)).build();
        Entity row = StyleRegistrar.ofShipped().add(ARMOR_STAND, gaze).definitions().get(ARMOR_STAND);

        Map<Size, Float> pitches = new EnumMap<>(Size.class);
        for (Size size : List.of(Size.SMALL, Size.LARGE)) {
            AppearanceOptions appearance = AppearanceOptions.builder().size(size).build();
            Entity resolved = appearance.resolve(row);
            Vector3f pivot = resolved.model().getBones().get("head").getPivot();
            double dx = 18d - pivot.x();
            double dy = -30d - pivot.y();
            double dz = -14d - pivot.z();
            PoseStyle style = resolved.styles().resolve("gaze", appearance::applies, ARMOR_STAND);
            EulerRotation head = PosePlayer.posed(resolved, style, resolved.styles().periodTicks(), TICK).model()
                .getBones().get("head").getRotation();
            assertEquals(Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))), head.pitch(), 1e-3,
                "the " + size + " stand pitches its head toward the target from its own pivot");
            assertEquals(Math.toDegrees(Math.atan2(-dx, -dz)), head.yaw(), 1e-3,
                "and yaws it from there");
            pitches.put(size, head.pitch());
        }
        assertTrue(Math.abs(pitches.get(Size.SMALL) - pitches.get(Size.LARGE)) > 1f,
            "the two pivots answer two pitches: " + pitches);
    }

    @Test
    @DisplayName("a statue spelled from the stand's finished attack holds each stand's arms where vanilla does, 2.5 out on the small one and 5 on the large")
    void theAttackStatueLandsEachStandsArmsAtItsOwnOffset() {
        // HumanoidModel's attack places each arm at 5 * ageScale, and vanilla draws the small stand
        // at half the age, so its arms stay at 2.5 where the large stand's stay at 5 - each where
        // that stand's own mesh rests them. Spliced as the showcase splices a silhouette, the one
        // shared state has to land both.
        Entity pristine = EntityModelLoader.load().get(ARMOR_STAND);
        BuiltStyle statue = RegistrarFixtures.silhouette(pristine, "attackTime=1", "attack");
        Entity row = StyleRegistrar.ofShipped().add(ARMOR_STAND, statue).definitions().get(ARMOR_STAND);
        Map<Size, Float> offsets = Map.of(Size.SMALL, 2.5f, Size.LARGE, 5f);
        for (Map.Entry<Size, Float> offset : offsets.entrySet()) {
            AppearanceOptions appearance = AppearanceOptions.builder()
                .size(offset.getKey())
                .toggles(Set.of("arms"))
                .build();
            Entity resolved = appearance.resolve(row);
            PoseStyle style = resolved.styles().resolve("attack", appearance::applies, ARMOR_STAND);
            EntityMesh posed = PosePlayer.posed(resolved, style, resolved.styles().periodTicks(), TICK).model();
            assertEquals(offset.getValue(), posed.getBones().get("left_arm").getPivot().x(), 1e-6f,
                "the " + offset.getKey() + " stand holds its left arm where vanilla's attack leaves it");
            assertEquals(-offset.getValue(), posed.getBones().get("right_arm").getPivot().x(), 1e-6f,
                "and its right arm");
        }

        Map<PoseChannel, PoseExpr> leftArm = pristine.pose().states().get("attackTime=1").bones().get("left_arm");
        assertFalse(leftArm.containsKey(PoseChannel.X),
            "the shared state names no arm x, the one number the two stands place apart: " + leftArm);
    }

    @Test
    @DisplayName("a salmon size drawing the row's pose over a mesh flattened apart plays a bob at the pixels the row does")
    void aSalmonSizesBobLandsTheRowsPixels() {
        BuiltStyle bob = Poses.custom("bob")
            .bone(SALMON_BONE, front -> front.timeline(timeline -> timeline.bob(2).over(1)))
            .allAges()
            .build();
        Entity pristine = EntityModelLoader.load().get(SALMON);
        Entity row = StyleRegistrar.ofShipped().add(SALMON, bob).definitions().get(SALMON);

        float lifted = salmonMoved(row, pristine, "bob", Size.MEDIUM);
        assertNotEquals(0f, lifted, "the row's bob lifts '" + SALMON_BONE + "' at the tick posed");
        for (Size size : List.of(Size.SMALL, Size.LARGE))
            assertEquals(lifted, salmonMoved(row, pristine, "bob", size), 1e-4f,
                size + " lifts '" + SALMON_BONE + "' by the pixels the row does");
    }

    @Test
    @DisplayName("a baby flattened at a factor its adult is not seats the probe's container step at the pixels written, through the field the adult drives")
    void aBabysContainerStepSeatsAtThePixelsWritten() {
        Entity pristine = EntityModelLoader.load().get(CAT);
        assertNotEquals(pristine.model().getFlattenedScale(),
            pristine.axes().baby().orElseThrow().model().getFlattenedScale(),
            "the cat's two meshes are flattened at different factors");
        PoseStyle installed = StyleRegistrar.ofShipped().add(CAT, probe()).definitions().get(CAT)
            .styles().styles().stream()
            .filter(style -> style.id().equals(STYLE))
            .findFirst().orElseThrow();

        assertEquals(List.of("style$form_probe$$container$y"),
            installed.drivers().keySet().stream().filter(field -> field.contains(CONTAINER)).toList(),
            "the container field spells no form, so the adult and the baby drive one field");
        assertEquals(2f, installed.drivers().get("style$form_probe$$container$y").extent(),
            "holding the pixels written, which neither form's factor divides");

        for (Age age : List.of(Age.ADULT, Age.BABY)) {
            Entity posed = assertTakes(CAT, AppearanceOptions.builder().age(age).build(), "the cat's " + age);
            EntityMesh.Bone seat = posed.model().getBones().get(CONTAINER);
            assertNotNull(seat, age + " carries the probe's container step");
            assertEquals(2f, seat.getPivot().y(), EPSILON, age + " seats the step at the pixels written");
        }
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
     * How far one installed style moves {@link #SALMON_BONE}'s pivot on y at one salmon size, at
     * {@link #TICK} - measured against the shipped pose under the same frame, so what the salmon's
     * own model writes to the bone drops out.
     *
     * @param row the salmon row the style is installed on
     * @param pristine the salmon row as it shipped
     * @param styleId the installed style's id
     * @param size the size posed
     * @return the pivot's y where the style leaves it, less the shipped pose's
     */
    private static float salmonMoved(@NotNull Entity row, @NotNull Entity pristine, @NotNull String styleId,
                                     @NotNull Size size) {
        AppearanceOptions appearance = AppearanceOptions.builder().size(size).build();
        Entity resolved = appearance.resolve(row);
        Entity plain = appearance.resolve(pristine);
        PoseStyle style = resolved.styles().resolve(styleId, appearance::applies, SALMON);
        int period = resolved.styles().periodTicks();
        float posed = PosePlayer.posed(resolved, style, period, TICK).model().getBones().get(SALMON_BONE).getPivot().y();
        float shipped = PosePlayer.posed(plain.pose(), plain.model(), style, period, TICK).getBones().get(SALMON_BONE).getPivot().y();
        return posed - shipped;
    }

    /**
     * One age of an installed row, posed at {@link #TICK} under one of its styles.
     *
     * @param row the row the style is installed on
     * @param entityId the row's id
     * @param styleId the installed style's id
     * @param age the age posed
     * @return the posed mesh
     */
    private static @NotNull EntityMesh posedAt(@NotNull Entity row, @NotNull String entityId,
                                               @NotNull String styleId, @NotNull Age age) {
        AppearanceOptions appearance = AppearanceOptions.builder().age(age).build();
        Entity resolved = appearance.resolve(row);
        PoseStyle style = resolved.styles().resolve(styleId, appearance::applies, entityId);
        return PosePlayer.posed(resolved, style, resolved.styles().periodTicks(), TICK).model();
    }

    /**
     * How far one installed style pitches a bone at one age, at {@link #TICK} - measured against the
     * shipped pose under the same frame, so what the row's own model writes to the bone drops out.
     *
     * @param row the row the style is installed on
     * @param pristine the row as it shipped
     * @param entityId the row's id
     * @param styleId the installed style's id
     * @param age the age posed
     * @param bone the bone measured
     * @return the bone's pitch where the style leaves it less the shipped pose's, in degrees
     */
    private static float pitchedBy(@NotNull Entity row, @NotNull Entity pristine, @NotNull String entityId,
                                   @NotNull String styleId, @NotNull Age age, @NotNull String bone) {
        AppearanceOptions appearance = AppearanceOptions.builder().age(age).build();
        Entity resolved = appearance.resolve(row);
        Entity plain = appearance.resolve(pristine);
        PoseStyle style = resolved.styles().resolve(styleId, appearance::applies, entityId);
        int period = resolved.styles().periodTicks();
        EntityMesh styled = PosePlayer.posed(resolved, style, period, TICK).model();
        EntityMesh shipped = PosePlayer.posed(plain.pose(), plain.model(), style, period, TICK);
        return styled.getBones().get(bone).getRotation().pitch() - shipped.getBones().get(bone).getRotation().pitch();
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
     * Holds the happy ghast's body, under a style scaling it to a field of 1.5 on both ages, to the
     * rest each age's mesh holds it at, a ratio of 1.5 over that rest, and the body and its core
     * drawn at the rest times 1.5 - the field under the factor the root scales by.
     *
     * @param swell the style, installed under the id {@code swell}
     * @param spelled how a failure names the scale the style writes
     */
    private static void assertGhastBodyDrawsItsField(@NotNull BuiltStyle swell, @NotNull String spelled) {
        Entity row = StyleRegistrar.ofShipped().add(HAPPY_GHAST, swell).definitions().get(HAPPY_GHAST);
        Map<Age, Float> rests = Map.of(Age.ADULT, 4f, Age.BABY, 0.95f);
        for (Age age : List.of(Age.ADULT, Age.BABY)) {
            AppearanceOptions appearance = AppearanceOptions.builder().age(age).build();
            Entity resolved = appearance.resolve(row);
            PoseStyle style = resolved.styles().resolve("swell", appearance::applies, HAPPY_GHAST);
            EntityMesh posed = PosePlayer.posed(resolved, style, resolved.styles().periodTicks(), TICK).model();
            float rest = rests.get(age);
            String where = age + " under " + spelled;

            EntityMesh.Bone body = posed.getBones().get(BONE);
            assertEquals(rest, body.getScale(), 0f, where + " keeps its body at the factor it rests at");
            assertRatio(1.5f, body, where + " rides the field over the one it rests at");
            assertEquals(rest * 1.5f, drawnScale(posed, BONE), 1e-5f,
                where + " draws its body at the field times the factor its root scales by");
            assertEquals(rest * 1.5f, drawnScale(posed, "inner_body"), 1e-5f,
                where + " draws its core with the body");
        }
    }

    /**
     * Holds a bone's pose scale to one uniform ratio over its rest, axis by axis.
     */
    private static void assertRatio(float expected, @NotNull EntityMesh.Bone bone, @NotNull String message) {
        Vector3f ratio = bone.getPoseScale();
        assertEquals(expected, ratio.x(), 1e-6f, message + " (x)");
        assertEquals(expected, ratio.y(), 1e-6f, message + " (y)");
        assertEquals(expected, ratio.z(), 1e-6f, message + " (z)");
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
