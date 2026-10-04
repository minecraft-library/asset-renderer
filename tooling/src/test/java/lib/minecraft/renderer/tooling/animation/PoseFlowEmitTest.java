package lib.minecraft.renderer.tooling.animation;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.diagnostic.Diagnostics;
import lib.minecraft.renderer.engine.pose.PoseChannel;
import lib.minecraft.renderer.engine.pose.PoseExpr;
import lib.minecraft.renderer.engine.pose.PoseOperator;
import lib.minecraft.renderer.engine.pose.PosePredicate;
import lib.minecraft.renderer.tooling.exception.ToolingException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two model-table passes the pose flow runs last: every form stating its pose key explicitly
 * with the family {@code bones.pose} taken off behind it, and each pose row carrying its renderer's
 * composed steps as its own container.
 *
 * <p>Exercised on hand-built trees rather than a walked corpus, so the derivation each explicit key
 * must equal - the family's named poser for a body site, the coordinate's own head for the rest -
 * is pinned per form kind, independently of any entity's layout.
 *
 * <p>Also the age each site renders at, which decides whether a row folds at a baby's own age, and
 * how a row reading the age that two ages reach is written once for both - or refused where one row
 * cannot be right at both.
 */
@DisplayName("pose flow emit passes")
class PoseFlowEmitTest {

    private static Diagnostics diagnostics;

    @BeforeAll
    static void open() {
        diagnostics = Diagnostics.root("pose", Diagnostics.Output.NONE, null);
    }

    // ------------------------------------------------------------------------------------
    // explicit pose members
    // ------------------------------------------------------------------------------------

    /** One family row: an adult mesh, and whatever else the caller lays on. */
    private static @NotNull JsonTree family(@NotNull String adultCoordinate) {
        return JsonTree.object().put("axes", JsonTree.object().put("age",
            JsonTree.object().put("options", JsonTree.object().put("adult",
                JsonTree.object().put("geometry", adultCoordinate).put("texture", "t")))));
    }

    private static @NotNull JsonTree option(@Nullable String coordinate) {
        JsonTree option = JsonTree.object();
        if (coordinate != null) option.put("geometry", coordinate);
        return option;
    }

    private static @NotNull JsonTree optionsOf(@NotNull JsonTree row, @NotNull String axis) {
        return row.child("axes").child(axis).child("options");
    }

    @Test
    @DisplayName("a body site takes the family's named poser, and the bones node goes with it")
    void aNamedPoserBecomesTheAdultsExplicitKey() {
        JsonTree row = family("HumanoidModel#createBodyLayer");
        row.put("bones", JsonTree.object().put("pose", "ZombieModel"));
        JsonTree models = JsonTree.object().put("minecraft:zombie", row);

        PoseFlow.nameExplicitPoses(models, Set.of("ZombieModel", "HumanoidModel"), diagnostics);

        JsonTree adult = models.child("minecraft:zombie").findPath("axes", "age", "options", "adult").orElseThrow();
        assertEquals("ZombieModel", adult.findString("pose").orElseThrow(),
            "the body poses through the named class, not the class heading its mesh");
        assertTrue(models.child("minecraft:zombie").find("bones").isEmpty(),
            "a bones node holding only the poser has nothing left to say");
    }

    @Test
    @DisplayName("a form with no named poser states its coordinate's own head")
    void theCoordinateHeadIsTheDerivedKey() {
        JsonTree row = family("FrogModel#createBodyLayer");
        optionsOf(row, "age").put("baby", option("FrogBabyModel#createBodyLayer@baby=x"));
        JsonTree models = JsonTree.object().put("minecraft:frog", row);

        PoseFlow.nameExplicitPoses(models, Set.of("FrogModel", "FrogBabyModel"), diagnostics);

        JsonTree frog = models.child("minecraft:frog");
        assertEquals("FrogModel",
            frog.findPath("axes", "age", "options", "adult").orElseThrow().findString("pose").orElseThrow());
        assertEquals("FrogBabyModel",
            frog.findPath("axes", "age", "options", "baby").orElseThrow().findString("pose").orElseThrow(),
            "a baby derives from its own coordinate, never from the family's poser");
    }

    @Test
    @DisplayName("a baby drawn through another class states that class, and the generation member goes")
    void aBabyPoserBecomesTheBabysExplicitKey() {
        JsonTree row = family("SnifferModel#createBodyLayer");
        optionsOf(row, "age").put("baby", option("SniffletModel#createBodyLayer")
            .put(PoseFlow.BABY_POSER, "net/minecraft/client/model/animal/sniffer/SnifferModel"));
        JsonTree models = JsonTree.object().put("minecraft:sniffer", row);

        PoseFlow.nameExplicitPoses(models, Set.of("SnifferModel", "SniffletModel"), diagnostics);

        JsonTree baby = models.child("minecraft:sniffer").findPath("axes", "age", "options", "baby").orElseThrow();
        assertEquals("SnifferModel", baby.findString("pose").orElseThrow(),
            "the baby poses through the class its renderer draws it with, not the class that baked its mesh");
        assertFalse(baby.has(PoseFlow.BABY_POSER), "the generation member is gone before the table is written");
    }

    @Test
    @DisplayName("a baby poser the pose table does not carry refuses rather than falling back to the mesh's head")
    void anUnwalkedBabyPoserRefuses() {
        JsonTree row = family("PiglinModel#createBodyLayer");
        optionsOf(row, "age").put("baby", option("BabyPiglinModel#createBodyLayer")
            .put(PoseFlow.BABY_POSER, "net/minecraft/client/model/monster/piglin/BabyZombifiedPiglinModel"));
        JsonTree models = JsonTree.object().put("minecraft:zombified_piglin", row);

        assertThrows(ToolingException.class,
            () -> PoseFlow.nameExplicitPoses(models, Set.of("PiglinModel", "BabyPiglinModel"), diagnostics),
            "the head would resolve, and render a pose the baby is never drawn with");
    }

    @Test
    @DisplayName("the pose member sits directly after the geometry it belongs to")
    void thePoseMemberSitsBesideTheGeometry() {
        JsonTree models = JsonTree.object().put("minecraft:frog", family("FrogModel#createBodyLayer"));

        PoseFlow.nameExplicitPoses(models, Set.of("FrogModel"), diagnostics);

        JsonTree adult = models.child("minecraft:frog")
            .findPath("axes", "age", "options", "adult").orElseThrow();
        assertEquals(List.of("geometry", "pose", "texture"), adult.keys().toList(),
            "the key states which class poses the mesh, so it reads beside the mesh");
    }

    @Test
    @DisplayName("every coat states the family poser, whether or not it names a mesh of its own")
    void coatsTakeTheFamilyPoser() {
        JsonTree row = family("HorseModel#createBodyLayer");
        row.put("bones", JsonTree.object().put("pose", "AdultHorseModel")
            .putStrings("undrawn", "saddle"));
        JsonTree coats = row.child("axes").child("variant").child("options");
        coats.put("white", option(null).put("textures", JsonTree.object().put("wild", "w")));
        coats.put("special", option("SpecialHorseModel#createBodyLayer"));
        JsonTree models = JsonTree.object().put("minecraft:horse", row);

        PoseFlow.nameExplicitPoses(models, Set.of("AdultHorseModel", "SpecialHorseModel"), diagnostics);

        JsonTree written = models.child("minecraft:horse");
        assertEquals("AdultHorseModel",
            written.findPath("axes", "variant", "options", "white").orElseThrow()
                .findString("pose").orElseThrow(),
            "a coat drawing the family mesh poses as the family does");
        assertEquals("AdultHorseModel",
            written.findPath("axes", "variant", "options", "special").orElseThrow()
                .findString("pose").orElseThrow(),
            "a coat swaps the mesh and never the poser");
        JsonTree bones = written.find("bones").orElseThrow();
        assertTrue(bones.findString("pose").isEmpty(), "the family poser member is spent");
        assertEquals(List.of("undrawn"), bones.keys().toList(), "what else the node held stays");
    }

    @Test
    @DisplayName("a size or shape option with a mesh states its head; one without states nothing")
    void sizeAndShapeOptionsDeriveFromTheirOwnMeshes() {
        JsonTree row = family("PufferfishBigModel#createBodyLayer");
        JsonTree sizes = row.child("axes").child("size").child("options");
        sizes.put("small", option("PufferfishSmallModel#createBodyLayer"));
        sizes.put("scaled", JsonTree.object().put("scale", 0.5f));
        JsonTree shapes = row.child("axes").child("shape").child("options");
        shapes.put("large", option("LargeShapeModel#createBodyLayer"));
        JsonTree models = JsonTree.object().put("minecraft:pufferfish", row);

        PoseFlow.nameExplicitPoses(models,
            Set.of("PufferfishBigModel", "PufferfishSmallModel", "LargeShapeModel"), diagnostics);

        JsonTree written = models.child("minecraft:pufferfish");
        assertEquals("PufferfishSmallModel",
            written.findPath("axes", "size", "options", "small").orElseThrow()
                .findString("pose").orElseThrow());
        assertTrue(written.findPath("axes", "size", "options", "scaled").orElseThrow()
                .findString("pose").isEmpty(),
            "an option naming no mesh resolves no pose of its own");
        assertEquals("LargeShapeModel",
            written.findPath("axes", "shape", "options", "large").orElseThrow()
                .findString("pose").orElseThrow());
    }

    @Test
    @DisplayName("a head the pose table does not carry stays unstated, and resolves the same nothing")
    void anAbsentRowStaysAnAbsentMember() {
        JsonTree models = JsonTree.object()
            .put("minecraft:armor_stand", family("ArmorStandModel#createBodyLayer"));

        PoseFlow.nameExplicitPoses(models, Set.of("SomeOtherModel"), diagnostics);

        assertTrue(models.child("minecraft:armor_stand")
                .findPath("axes", "age", "options", "adult").orElseThrow()
                .findString("pose").isEmpty(),
            "the reader's fallback is the same head, so an absent member swaps nothing");
    }

    @Test
    @DisplayName("an equipment layer's bones node is not touched")
    void equipmentBonesStaySaid() {
        JsonTree row = family("PigModel#createBodyLayer");
        JsonTree saddle = JsonTree.object()
            .put("geometry", "PigModel#createSaddleLayer")
            .put("bones", JsonTree.object().put("pose", "PigSaddleModel"));
        row.childArray("equipment").add(saddle);
        JsonTree models = JsonTree.object().put("minecraft:pig", row);

        PoseFlow.nameExplicitPoses(models, Set.of("PigModel", "PigSaddleModel"), diagnostics);

        JsonTree layer = models.child("minecraft:pig").find("equipment").orElseThrow()
            .elements().toList().getFirst();
        assertEquals("PigSaddleModel",
            layer.find("bones").orElseThrow().findString("pose").orElseThrow(),
            "the class a layer is handed is not the class that baked its mesh, and the node says so");
        assertTrue(layer.findString("pose").isEmpty(), "no new member arrives on the row");
    }

    // ------------------------------------------------------------------------------------
    // composed containers
    // ------------------------------------------------------------------------------------

    private static @NotNull JsonTree subject(@NotNull String renderer, @NotNull String coordinate) {
        return family(coordinate).put("renderer", "net/minecraft/client/renderer/entity/" + renderer);
    }

    private static @NotNull PoseOutcome.Extracted posing(@NotNull String model) {
        return new PoseOutcome.Extracted(new PoseProgram(model, List.of(),
            Map.of("body", Map.of(PoseChannel.X_ROT, new PoseExpr.Constant(0.5f))), Map.of(), List.of()));
    }

    // ------------------------------------------------------------------------------------
    // what a resting subject draws
    // ------------------------------------------------------------------------------------

    /** One row whose body leaves {@code written} on each named bone for {@code flag}. */
    private static @NotNull Map<String, PoseOutcome> flagged(
        @NotNull BoneFlag flag, @NotNull Map<String, PoseExpr> written) {

        return Map.of("Row", new PoseOutcome.Extracted(
            new PoseProgram("Model", List.of(), Map.of(), Map.of(flag, written), List.of())));
    }

    @Test
    @DisplayName("the undrawn list is the bones resting hidden, sorted, and a row drawing whole is absent")
    void theUndrawnListIsSortedAndOmittedWhenEmpty() {
        // This list IS a geometry key: it is joined on commas into an '@rest=' suffix, so its ORDER
        // is emitted bytes rather than an implementation detail. Given deliberately out of order.
        Map<String, List<String>> undrawn = PoseFlow.restingUndrawn(flagged(BoneFlag.VISIBLE, Map.of(
            "right_arm", new PoseExpr.Constant(0),
            "hat", new PoseExpr.Constant(0),
            "left_arm", new PoseExpr.Constant(0),
            "body", new PoseExpr.Constant(1))));

        assertEquals(List.of("hat", "left_arm", "right_arm"), undrawn.get("Row"),
            "the bones resting hidden, sorted, with the one resting drawn left out");

        assertEquals(Map.of(), PoseFlow.restingUndrawn(flagged(BoneFlag.VISIBLE,
                Map.of("body", new PoseExpr.Constant(1)))),
            "a row that rests drawing every bone carries no undrawn list at all");
    }

    @Test
    @DisplayName("a flag that rests at more than a literal is refused, naming the bone")
    void anUnsettledFlagIsRefused() {
        // Nothing at render reads a flag: which bones a subject rests without is stamped onto the
        // mesh, so a visibility the tick could still move has nowhere to be said. The fold settles
        // every flag it can, and one it cannot is a stopped generation rather than a bone that
        // guesses.
        ToolingException raised = assertThrows(ToolingException.class,
            () -> PoseFlow.restingUndrawn(flagged(BoneFlag.VISIBLE,
                Map.of("head", new PoseExpr.Input("ageInTicks")))));

        assertTrue(raised.getMessage().contains("head.visible"), raised.getMessage());
        assertTrue(raised.getMessage().contains("Row"), raised.getMessage());
    }

    @Test
    @DisplayName("a row resting with a bone's own cubes skipped is refused, an undrawn list cannot say it")
    void aRestingSkipDrawIsRefused() {
        // skip_draw hides a bone's own cubes while its descendants keep drawing, and the undrawn
        // list is whole-subtree. Resting at one therefore has no spelling, where resting at zero is
        // the default and says nothing.
        ToolingException raised = assertThrows(ToolingException.class,
            () -> PoseFlow.restingUndrawn(flagged(BoneFlag.SKIP_DRAW,
                Map.of("body", new PoseExpr.Constant(1)))));
        assertTrue(raised.getMessage().contains("body"), raised.getMessage());

        assertEquals(Map.of(), PoseFlow.restingUndrawn(flagged(BoneFlag.SKIP_DRAW,
                Map.of("body", new PoseExpr.Constant(0)))),
            "resting at zero is what every bone does and contributes nothing");
    }

    @Test
    @DisplayName("a flag written on the flattened container is refused, it reaches no bone below it")
    void aContainerFlagIsRefused() {
        // The mesh names the container nowhere - it is a parent transform above every bone the mesh
        // holds at top level - so there is no bone for a subject to rest without. Asked of the
        // carrier rather than of the lifted container steps, because the lift takes the nine
        // channels out of the mesh root and leaves a flag written there where it was.
        ToolingException raised = assertThrows(ToolingException.class,
            () -> PoseFlow.restingUndrawn(flagged(BoneFlag.VISIBLE,
                Map.of(PoseWalk.MESH_ROOT, new PoseExpr.Constant(0)))));

        assertTrue(raised.getMessage().contains("visible"), raised.getMessage());
        assertTrue(raised.getMessage().contains("container"), raised.getMessage());
    }

    @Test
    @DisplayName("a node the fold settles is refused at the writer rather than written")
    void anUnsettledNodeIsRefusedAtTheWriter() {
        // A class reached at two resting frames with no split key is emitted exactly as walked, so a
        // node the fold would have erased can reach the writer. The renderer's reader has no case for
        // one and throws at load for EVERY entity; refused here it names the row that carries it.
        PoseOutcome.Extracted walked = new PoseOutcome.Extracted(new PoseProgram("FoxModel", List.of(),
            Map.of("body", Map.of(PoseChannel.X_ROT, new PoseExpr.Answered.Carried("legMotionPos"))), Map.of(), List.of()));

        ToolingException raised = assertThrows(ToolingException.class, () -> PoseJson.of(walked));
        assertTrue(raised.getMessage().contains("carried"), raised.getMessage());
        assertTrue(raised.getMessage().contains("FoxModel"), raised.getMessage());
    }

    @Test
    @DisplayName("a play site still carrying a guard is refused rather than shipped unconditional")
    void aGuardedClipSiteIsRefusedAtTheWriter() {
        // A site ships its clip, its drive and its arguments and never its condition, the fold being
        // the only reader of one - it drops what it proves unreachable and settles the rest to ALWAYS.
        // Shipped with a guard still on it, the model plays the clip wherever it is reachable rather
        // than where it is gated to, which is every walk clip at once.
        PoseClipSite guarded = new PoseClipSite("fox_sleep", PoseClipSite.Gate.NONE, "", List.of(),
            new PoseExpr.Select(
                new PosePredicate(PosePredicate.Comparison.GT,
                    new PoseExpr.Input("ageInTicks"), new PoseExpr.Constant(0f)),
                PoseClipSite.ALWAYS, PoseClipSite.NEVER));
        PoseOutcome.Extracted walked = new PoseOutcome.Extracted(
            new PoseProgram("FoxModel", List.of(), Map.of(), Map.of(), List.of(guarded)));

        ToolingException raised = assertThrows(ToolingException.class, () -> PoseJson.of(walked));
        assertTrue(raised.getMessage().contains("fox_sleep"), raised.getMessage());
    }

    @Test
    @DisplayName("a row whose renderer composes carries steps, ground frame, then its own container")
    void aComposedRowCarriesItsWholeStack() {
        JsonTree models = JsonTree.object().put("minecraft:cod", subject("CodRenderer", "CodModel#createBodyLayer"));
        Map<PoseChannel, PoseExpr> step = Map.of(PoseChannel.Z_ROT, new PoseExpr.Constant(1.5707964f));
        Map<String, RenderTransform> transforms =
            Map.of("CodRenderer", RenderTransform.of("CodRenderer", 0f, List.of(step)));

        Map<String, PoseOutcome> out = PoseFlow.composeContainers(
            Map.of("CodModel", posing("CodModel")), models, transforms, diagnostics);

        PoseProgram program = ((PoseOutcome.Extracted) out.get("CodModel")).program();
        assertEquals(2, program.container().size(), "one composed step and the frame that seats it");
        assertEquals(step, program.container().getFirst());
        assertEquals(Map.of(PoseChannel.Y, new PoseExpr.Constant(-24.016f)), program.container().getLast(),
            "the ground frame is the float bits of -1.501 blocks in model pixels, exactly");
        assertEquals(new PoseExpr.Constant(-1.501f * 16f),
            program.container().getLast().get(PoseChannel.Y),
            "the two spellings of the constant are one value");
        assertEquals(posing("CodModel").program().bones(), program.bones(), "the bones are untouched");
    }

    @Test
    @DisplayName("a row whose renderer composes nothing is left exactly as folded")
    void anUncomposedRowIsUntouched() {
        JsonTree models = JsonTree.object()
            .put("minecraft:pig", subject("PigRenderer", "PigModel#createBodyLayer"));

        Map<String, PoseOutcome> out = PoseFlow.composeContainers(
            Map.of("PigModel", posing("PigModel")), models, Map.of(), diagnostics);

        assertEquals(List.of(), ((PoseOutcome.Extracted) out.get("PigModel")).program().container(),
            "no sequence means nothing for the ground frame to seat");
    }

    @Test
    @DisplayName("one row reached with two different step sequences is refused")
    void disagreeingRenderersRefuse() {
        JsonTree models = JsonTree.object()
            .put("minecraft:cod", subject("CodRenderer", "SharedModel#createBodyLayer"))
            .put("minecraft:pig", subject("PigRenderer", "SharedModel#createBodyLayer"));
        Map<String, RenderTransform> transforms = Map.of("CodRenderer", RenderTransform.of(
            "CodRenderer", 0f,
            List.of(Map.of(PoseChannel.Z_ROT, new PoseExpr.Constant(1.5707964f)))));

        assertThrows(ToolingException.class, () -> PoseFlow.composeContainers(
                Map.of("SharedModel", posing("SharedModel")), models, transforms, diagnostics),
            "one container cannot answer for two renderers' sequences");
    }

    // ------------------------------------------------------------------------------------
    // the pose a filled slot swaps onto its wearer
    // ------------------------------------------------------------------------------------

    /** HappyGhastModel#setupAnim's squeeze: the body's own scale while the slot is empty, 0.9375 while it is filled. */
    private static @NotNull PoseOutcome.Extracted squeezed() {
        PoseExpr scale = new PoseExpr.Select(new PoseExpr.Answered.InputFn("bodyItem", "isEmpty").truthy(),
            new PoseExpr.BoneRead("body", PoseChannel.X_SCALE), new PoseExpr.Constant(0.9375f));
        return new PoseOutcome.Extracted(new PoseProgram("HappyGhastModel", List.of(),
            Map.of("body", Map.of(PoseChannel.X_SCALE, scale)), Map.of(), List.of()));
    }

    /** A subject whose body is {@code coordinate} and whose one equipment row's getter reads {@code field}. */
    private static @NotNull JsonTree wearing(@NotNull String coordinate, @NotNull String field) {
        JsonTree row = family(coordinate);
        row.childArray("equipment").add(JsonTree.object()
            .put("slot", "body")
            .put("geometry", "HarnessModel#createHarnessLayer")
            .put(PoseFlow.ITEM_FIELD, field));
        return row;
    }

    private static @NotNull JsonTree equipmentOf(@NotNull JsonTree models, @NotNull String entity) {
        return models.child(entity).find("equipment").orElseThrow().elements().toList().getFirst();
    }

    @Test
    @DisplayName("a body asking the slot's emptiness gains a row folded filled, and the layer names it")
    void aFilledSlotFoldsItsWearerOnceMore() {
        JsonTree models = JsonTree.object()
            .put("minecraft:happy_ghast", wearing("HappyGhastModel#createBodyLayer", "bodyItem"))
            .put("minecraft:pig", wearing("PigModel#createBodyLayer", "saddle"));
        Map<String, PoseOutcome> walked = Map.of("HappyGhastModel", squeezed(), "PigModel", posing("PigModel"));
        Map<String, PoseOutcome> folded = Map.of(
            "HappyGhastModel", new PoseOutcome.Extracted(PoseFold.fold(squeezed().program(), Map.of(), Map.of(),
                Map.of(), Map.of(), Set.of(), Set.of(), Map.of())),
            "PigModel", posing("PigModel"));

        Map<String, PoseOutcome> out = PoseFlow.foldWearers(walked, models, folded, Map.of(), Map.of(), Map.of(),
            Map.of(), diagnostics);

        String key = "HappyGhastModel@bodyItem.isEmpty=false";
        assertEquals(Set.of("HappyGhastModel", "PigModel", key), out.keySet(), "exactly one row is added");
        assertEquals(new PoseExpr.Constant(0.9375f),
            ((PoseOutcome.Extracted) out.get(key)).program().bones().get("body").get(PoseChannel.X_SCALE),
            "the added row takes the filled arm");
        assertEquals(folded.get("HappyGhastModel"), out.get("HappyGhastModel"), "the shipped row is untouched");
        assertEquals(key, equipmentOf(models, "minecraft:happy_ghast").findString("wearer_pose").orElseThrow(),
            "the layer names the pose its wearer takes");
        assertTrue(equipmentOf(models, "minecraft:pig").findString("wearer_pose").isEmpty(),
            "a body that asks nothing of the slot gains no wearer pose");
    }

    @Test
    @DisplayName("a wearer pose the pose table does not carry is refused")
    void anUnresolvedWearerPoseRefuses() {
        JsonTree models = JsonTree.object().put("minecraft:happy_ghast", family("HappyGhastModel#createBodyLayer"));
        models.child("minecraft:happy_ghast").childArray("equipment").add(JsonTree.object()
            .put("slot", "body")
            .put("geometry", "HarnessModel#createHarnessLayer")
            .put("wearer_pose", "HappyGhastModel@bodyItem.isEmpty=false"));

        ToolingException raised = assertThrows(ToolingException.class,
            () -> PoseFlow.requirePosersResolve(models, Map.of("HappyGhastModel", squeezed())));
        assertTrue(raised.getMessage().contains("HappyGhastModel@bodyItem.isEmpty=false"), raised.getMessage());
    }

    @Test
    @DisplayName("the shipped tables carry the harnessed ghast's row, named by its body layer alone")
    void theShippedTablesCarryTheHarnessedRow() throws Exception {
        String key = "HappyGhastModel@bodyItem.isEmpty=false";
        JsonObject poses = JsonParser.parseString(Files.readString(
            Path.of("src/main/resources/lib/minecraft/renderer/entity_poses.json")))
            .getAsJsonObject().getAsJsonObject("poses");
        JsonObject models = JsonParser.parseString(Files.readString(
            Path.of("src/main/resources/lib/minecraft/renderer/entity_models.json")))
            .getAsJsonObject().getAsJsonObject("models");

        JsonObject harnessed = poses.getAsJsonObject(key).deepCopy();
        JsonObject body = harnessed.getAsJsonObject("bones").getAsJsonObject("body");
        for (String axis : List.of("x_scale", "y_scale", "z_scale")) {
            assertEquals(0.9375f, body.getAsJsonObject(axis).get("const").getAsFloat(), axis);
            body.add(axis, poses.getAsJsonObject("HappyGhastModel").getAsJsonObject("bones")
                .getAsJsonObject("body").get(axis));
        }
        assertEquals(poses.getAsJsonObject("HappyGhastModel"), harnessed,
            "the harnessed row is the shipped row but for the three body scales");

        List<String> named = new ArrayList<>();
        models.entrySet().forEach(entry -> {
            JsonObject row = entry.getValue().getAsJsonObject();
            if (!row.has("equipment")) return;
            row.getAsJsonArray("equipment").forEach(item -> {
                assertFalse(item.getAsJsonObject().has(PoseFlow.ITEM_FIELD),
                    entry.getKey() + " ships the generation-only item_field");
                if (item.getAsJsonObject().has("wearer_pose"))
                    named.add(entry.getKey() + " " + item.getAsJsonObject().get("wearer_pose").getAsString());
            });
        });
        assertEquals(List.of("minecraft:happy_ghast " + key), named, "the ghast's body layer alone names a wearer pose");
    }

    // ------------------------------------------------------------------------------------
    // the age a site renders at
    // ------------------------------------------------------------------------------------

    /** A family whose baby age option draws {@code babyCoordinate} at {@code ageScale}, or carries no age where it is null. */
    private static @NotNull JsonTree withBaby(
        @NotNull String adultCoordinate, @NotNull String babyCoordinate, @Nullable Float ageScale) {

        JsonTree row = family(adultCoordinate);
        JsonTree baby = option(babyCoordinate);
        if (ageScale != null) baby.put(PoseFlow.AGE_SCALE, ageScale.floatValue());
        optionsOf(row, "age").put("baby", baby);
        return row;
    }

    @Test
    @DisplayName("a baby option's mesh renders at its age_scale and the adult body at one")
    void aBabyOptionRendersAtItsOwnAge() {
        JsonTree row = withBaby("HorseModel#createBodyLayer", "BabyHorseModel#createBabyMesh", 0.5f);
        optionsOf(row, "age").child("baby").childArray("overlays")
            .add(JsonTree.object().put("geometry", "BabyMarkingsModel#createBabyMesh"));
        JsonTree models = JsonTree.object().put("minecraft:horse", row);

        Map<String, Map<Float, Set<String>>> ages = PoseFlow.ageScalesOf(models);

        assertEquals(Map.of(0.5f, Set.of("minecraft:horse")), ages.get("BabyHorseModel"),
            "the baby's mesh renders at the age its entity answers");
        assertEquals(Map.of(0.5f, Set.of("minecraft:horse")), ages.get("BabyMarkingsModel"),
            "and so does an overlay the baby option carries");
        assertEquals(Map.of(1f, Set.of("minecraft:horse")), ages.get("HorseModel"),
            "the adult body renders at the age the render state builds");
    }

    @Test
    @DisplayName("an overlay's own baby mesh renders at the baby's age, and the worn armour's alternate at one")
    void anOverlayBabyIsABabySiteAndTheArmourAlternateIsNot() {
        JsonTree row = withBaby("SheepModel#createBodyLayer", "BabySheepModel#createBodyLayer", 0.5f);
        row.childArray("overlays").add(JsonTree.object()
            .put("geometry", "SheepFurModel#createFurLayer")
            .put("baby", JsonTree.object().put("geometry", "BabySheepFurModel#createBodyLayer")));
        row.put("armor", JsonTree.object()
            .put("geometry", "HumanoidArmorModel#createArmorLayer")
            .put("alternate", JsonTree.object().put("geometry", "BabyArmorModel#createArmorLayer")));
        JsonTree models = JsonTree.object().put("minecraft:sheep", row);

        Map<String, Map<Float, Set<String>>> ages = PoseFlow.ageScalesOf(models);

        assertEquals(Set.of(0.5f), ages.get("BabySheepFurModel").keySet(),
            "the overlay the baby draws renders at the baby's age");
        assertEquals(Set.of(1f), ages.get("SheepFurModel").keySet(), "the overlay the adult draws at one");
        assertEquals(Set.of(1f), ages.get("BabyArmorModel").keySet(),
            "a worn shell evaluates no pose row, so its alternate is no baby site");
    }

    @Test
    @DisplayName("a baby drawn through another class files its site under that class, at the baby's age")
    void aBabyPoserIsTheKeyItsSiteFilesUnder() {
        JsonTree row = withBaby("SnifferModel#createBodyLayer", "SniffletModel#createBodyLayer", 0.5f);
        optionsOf(row, "age").child("baby")
            .put(PoseFlow.BABY_POSER, "net/minecraft/client/model/animal/sniffer/SnifferModel");
        JsonTree models = JsonTree.object().put("minecraft:sniffer", row);

        Map<String, Map<Float, Set<String>>> ages = PoseFlow.ageScalesOf(models);

        assertEquals(Map.of(1f, Set.of("minecraft:sniffer"), 0.5f, Set.of("minecraft:sniffer")),
            ages.get("SnifferModel"), "the class the baby is drawn through is reached at the baby's age too");
        assertFalse(ages.containsKey("SniffletModel"), "the class that only baked the mesh is reached by no site");
    }

    @Test
    @DisplayName("a key an adult body and a baby option both reach renders at both ages")
    void aKeyReachedAtTwoAgesCarriesBoth() {
        JsonTree models = JsonTree.object()
            .put("minecraft:big", family("SharedModel#createBodyLayer"))
            .put("minecraft:small", withBaby("OtherModel#createBodyLayer", "SharedModel#createBabyLayer", 0.5f));

        assertEquals(Map.of(1f, Set.of("minecraft:big"), 0.5f, Set.of("minecraft:small")),
            PoseFlow.ageScalesOf(models).get("SharedModel"));
    }

    @Test
    @DisplayName("a baby option carrying no age_scale files its key at the no-answer age")
    void aBabyWithNoAgeIsMarked() {
        JsonTree models = JsonTree.object().put("minecraft:odd",
            withBaby("OddModel#createBodyLayer", "BabyOddModel#createBodyLayer", null));

        assertEquals(Set.of(PoseFlow.NO_AGE), PoseFlow.ageScalesOf(models).get("BabyOddModel").keySet(),
            "an unread age is marked rather than taken as the adult's");
    }

    /** A family whose size option draws {@code smallCoordinate}, at {@code ageScale} where it states one. */
    private static @NotNull JsonTree withSize(
        @NotNull String adultCoordinate, @NotNull String smallCoordinate, @Nullable Float ageScale) {

        JsonTree row = family(adultCoordinate);
        JsonTree small = option(smallCoordinate);
        if (ageScale != null) small.put(PoseFlow.AGE_SCALE, ageScale.floatValue());
        optionsOf(row, "size").put("small", small);
        return row;
    }

    @Test
    @DisplayName("a size option stating an age_scale files its mesh and its overlays at it, and one stating none at one")
    void aSizeOptionStatingItsAgeFilesAtIt() {
        JsonTree stand = withSize("StandModel#createBodyLayer", "StandModel#createBodyLayer@baby=x", 0.5f);
        optionsOf(stand, "size").child("small").childArray("overlays")
            .add(JsonTree.object().put("geometry", "StandPlateModel#createPlate"));
        JsonTree models = JsonTree.object()
            .put("minecraft:armor_stand", stand)
            .put("minecraft:salmon", withSize("SalmonModel#createBodyLayer", "SalmonModel#createBodyLayer@scaled=0.5", null));

        Map<String, Map<Float, Set<String>>> ages = PoseFlow.ageScalesOf(models);

        assertEquals(Map.of(1f, Set.of("minecraft:armor_stand"), 0.5f, Set.of("minecraft:armor_stand")),
            ages.get("StandModel"), "the body at one and the small size at the age it states");
        assertEquals(Map.of(0.5f, Set.of("minecraft:armor_stand")), ages.get("StandPlateModel"),
            "an overlay the option carries renders at the option's age");
        assertEquals(Map.of(1f, Set.of("minecraft:salmon")), ages.get("SalmonModel"),
            "a size option stating no age renders at the one the render state builds");
        assertEquals(Map.of(1f, Set.of("StandModel#createBodyLayer"), 0.5f, Set.of("StandModel#createBodyLayer@baby=x")),
            PoseFlow.meshesOf(models).get("StandModel"), "and each age names the mesh drawn there");
    }

    /** Where every part rests on each mesh a test row is drawn on, by coordinate. */
    private static @NotNull Function<String, PoseStates.Site> rests(
        @NotNull Map<String, Map<String, Map<PoseChannel, Float>>> byCoordinate) {

        return coordinate -> new PoseStates.Site(coordinate, byCoordinate.getOrDefault(coordinate, Map.of()));
    }

    /** {@code attackTime > 0 ? whenAttacking : <bone>.<channel>}, the shape of HumanoidModel's attack. */
    private static @NotNull PoseExpr attacking(
        @NotNull String bone, @NotNull PoseChannel channel, @NotNull PoseExpr whenAttacking) {

        return new PoseExpr.Select(
            new PosePredicate(PosePredicate.Comparison.GT, new PoseExpr.Input("attackTime"), new PoseExpr.Constant(0f)),
            whenAttacking, new PoseExpr.BoneRead(bone, channel));
    }

    /** {@code 5 * ageScale}, the attack's arm offset. */
    private static @NotNull PoseExpr agedOffset(float offset) {
        return PoseExpr.operation(PoseOperator.MUL, new PoseExpr.Constant(offset), new PoseExpr.Input(PoseFlow.AGE_SCALE_FIGURE));
    }

    /** The stand: one row both sizes draw, the small size at half the age. */
    private static @NotNull JsonTree stand() {
        return JsonTree.object().put("minecraft:armor_stand",
            withSize("StandModel#createBodyLayer", "StandModel#createBodyLayer@baby=x", 0.5f));
    }

    /** The large and the small stand's arm pivots, as their meshes rest them. */
    private static @NotNull Function<String, PoseStates.Site> standRests() {
        return rests(Map.of(
            "StandModel#createBodyLayer", Map.of(
                "left_arm", Map.of(PoseChannel.X, 5f, PoseChannel.Y, 2f, PoseChannel.Z, 0f),
                "right_arm", Map.of(PoseChannel.X, -5f, PoseChannel.Y, 2f, PoseChannel.Z, 0f)),
            "StandModel#createBodyLayer@baby=x", Map.of(
                "left_arm", Map.of(PoseChannel.X, 2.5f, PoseChannel.Y, 13f, PoseChannel.Z, 0f),
                "right_arm", Map.of(PoseChannel.X, -2.5f, PoseChannel.Y, 13f, PoseChannel.Z, 0f))));
    }

    /**
     * {@link PoseFlow#foldAll} over no resting map, no question and no derived figure, the render
     * state building {@code ageScale} at one and {@code attackTime} at zero.
     */
    private static @NotNull Map<String, PoseOutcome> foldAll(
        @NotNull Map<String, PoseOutcome> walked, @NotNull JsonTree models,
        @NotNull Map<String, Map<String, PoseStates.Silhouette>> states,
        @NotNull Function<String, PoseStates.Site> sites, @NotNull Diagnostics scope) {

        return PoseFlow.foldAll(walked, models, Map.of(), Map.of(),
            Map.of(PoseFlow.AGE_SCALE_FIGURE, 1f, "attackTime", 0f), Map.of(), states, sites, scope);
    }

    @Test
    @DisplayName("a row reading the age at two ages writes each state once, leaving out the position each age places at its own rest")
    void aRowReachedAtTwoAgesWritesEachStateOnce() {
        // HumanoidModel's finished attack places each arm at 5 * ageScale: 5 on the large stand and
        // 2.5 on the small one, each exactly where that stand's arm rests - so the one row says
        // nothing of x, and each stand's arm stays where its own mesh puts it. z agrees and stays.
        PoseOutcome.Extracted walked = new PoseOutcome.Extracted(new PoseProgram("StandModel", List.of(), Map.of(
            "left_arm", Map.of(
                PoseChannel.X, attacking("left_arm", PoseChannel.X, agedOffset(5f)),
                PoseChannel.Z, attacking("left_arm", PoseChannel.Z, new PoseExpr.Constant(-0.0f))),
            "right_arm", Map.of(
                PoseChannel.X, attacking("right_arm", PoseChannel.X, agedOffset(-5f)),
                PoseChannel.Z, attacking("right_arm", PoseChannel.Z, new PoseExpr.Constant(0f)))),
            Map.of(), List.of()));
        Map<String, Map<String, PoseStates.Silhouette>> states = new TreeMap<>();
        Diagnostics scope = Diagnostics.root("pose", Diagnostics.Output.NONE, null);

        Map<String, PoseOutcome> out = foldAll(Map.of("StandModel", walked), stand(), states, standRests(), scope);

        Map<String, Map<PoseChannel, PoseExpr>> attack = states.get("StandModel").get("attackTime=1").bones();
        assertEquals(Map.of(PoseChannel.Z, new PoseExpr.Constant(-0.0f)), attack.get("left_arm"),
            "the left arm keeps the z both ages place, and loses the x they place apart");
        assertEquals(Map.of(PoseChannel.Z, new PoseExpr.Constant(0f)), attack.get("right_arm"));
        assertEquals(new PoseExpr.BoneRead("left_arm", PoseChannel.X),
            ((PoseOutcome.Extracted) out.get("StandModel")).program().bones().get("left_arm").get(PoseChannel.X),
            "the row rests the arm at its own read, which each stand answers from its own mesh");
        assertTrue(scope.entries().stream().anyMatch(entry -> entry.message().contains("StandModel reads ageScale")
                && entry.message().contains("[attackTime=1 left_arm.x, attackTime=1 right_arm.x]")),
            "one line names what was left at each site's rest: " + scope.entries());
    }

    @Test
    @DisplayName("a row reading no age at two ages folds as it did, at the constructed age")
    void aRowReadingNoAgeKeepsItsFold() {
        // The happy ghast's and the nautilus's shape: two ages reach the row and nothing in it reads
        // the age, so there is nothing for the ages to disagree on.
        PoseOutcome.Extracted walked = new PoseOutcome.Extracted(new PoseProgram("StandModel", List.of(), Map.of(
            "left_arm", Map.of(PoseChannel.X, attacking("left_arm", PoseChannel.X, new PoseExpr.Constant(5f)))),
            Map.of(), List.of()));
        Map<String, Map<String, PoseStates.Silhouette>> states = new TreeMap<>();
        Diagnostics scope = Diagnostics.root("pose", Diagnostics.Output.NONE, null);

        foldAll(Map.of("StandModel", walked), stand(), states, rests(Map.of()), scope);

        assertEquals(new PoseExpr.Constant(5f),
            states.get("StandModel").get("attackTime=1").bones().get("left_arm").get(PoseChannel.X),
            "the literal stands, no mesh consulted");
        assertTrue(scope.entries().stream().noneMatch(entry -> entry.message().contains("written once")),
            "and nothing is said of ages: " + scope.entries());
    }

    @Test
    @DisplayName("a row reading the age whose resting row folds apart by age refuses, naming the row and both ages")
    void aRestingRowApartByAgeRefuses() {
        PoseOutcome.Extracted walked = new PoseOutcome.Extracted(new PoseProgram("StandModel", List.of(),
            Map.of("body", Map.of(PoseChannel.Y, agedOffset(12f))), Map.of(), List.of()));

        ToolingException raised = assertThrows(ToolingException.class, () -> foldAll(Map.of("StandModel", walked),
            stand(), new TreeMap<>(), standRests(), diagnostics));
        assertTrue(raised.getMessage().contains("StandModel"), raised.getMessage());
        assertTrue(raised.getMessage().contains("0.5") && raised.getMessage().contains("1.0"), raised.getMessage());
        assertTrue(raised.getMessage().contains("body.y"), raised.getMessage());
    }

    @Test
    @DisplayName("a state one spelling cannot carry at every site refuses rather than writing one age's value for both")
    void aStateApartFromASitesRestRefuses() {
        // The small mesh rests its arm at 3 where the age places it at 2.5: no spelling is right at
        // both, and the large stand's 5 written for both is wrong on the small one.
        PoseOutcome.Extracted walked = new PoseOutcome.Extracted(new PoseProgram("StandModel", List.of(),
            Map.of("left_arm", Map.of(PoseChannel.X, attacking("left_arm", PoseChannel.X, agedOffset(5f)))),
            Map.of(), List.of()));
        Function<String, PoseStates.Site> sites = rests(Map.of(
            "StandModel#createBodyLayer", Map.of("left_arm", Map.of(PoseChannel.X, 5f)),
            "StandModel#createBodyLayer@baby=x", Map.of("left_arm", Map.of(PoseChannel.X, 3f))));

        ToolingException raised = assertThrows(ToolingException.class, () -> foldAll(Map.of("StandModel", walked),
            stand(), new TreeMap<>(), sites, diagnostics));
        assertTrue(raised.getMessage().contains("attackTime=1"), raised.getMessage());
        assertTrue(raised.getMessage().contains("left_arm.x"), raised.getMessage());
    }

    @Test
    @DisplayName("a row reading the age reached at two frames and at two ages refuses")
    void twoFramesAtTwoAgesRefuse() {
        // The two stands rest apart on a flag the row names, so the row has two frames - and a frame
        // split cannot also be folded at two ages.
        PoseOutcome.Extracted walked = new PoseOutcome.Extracted(new PoseProgram("StandModel", List.of(), Map.of(
            "body", Map.of(PoseChannel.Y, new PoseExpr.Select(
                new PosePredicate(PosePredicate.Comparison.NE, new PoseExpr.Input("isMarker"), new PoseExpr.Constant(0)),
                agedOffset(2f), new PoseExpr.BoneRead("body", PoseChannel.Y)))),
            Map.of(), List.of()));
        JsonTree models = JsonTree.object()
            .put("minecraft:armor_stand", family("StandModel#createBodyLayer")
                .put("rest", JsonTree.object().put("isMarker", "false")))
            .put("minecraft:marker", withSize("OtherModel#createBodyLayer", "StandModel#createBodyLayer@baby=x", 0.5f)
                .put("rest", JsonTree.object().put("isMarker", "true")));

        ToolingException raised = assertThrows(ToolingException.class, () -> foldAll(Map.of("StandModel", walked),
            models, new TreeMap<>(), standRests(), diagnostics));
        assertTrue(raised.getMessage().contains("StandModel"), raised.getMessage());
        assertTrue(raised.getMessage().contains("frames"), raised.getMessage());
    }

    @Test
    @DisplayName("a site answers its parts' rests as the mesh ships them - no y where a later pass shifts it, and nothing with no entry")
    void aSiteAnswersOnlyWhatShips() {
        JsonTree entry = JsonTree.object().putInts("texture_size", 64, 64).put("bones", JsonTree.object()
            .put("left_arm", JsonTree.object().putFloats("pivot", 5f, 2f, 0f)));

        assertEquals(Map.of("left_arm", Map.of(PoseChannel.X, 5f, PoseChannel.Y, 2f, PoseChannel.Z, 0f)),
            PoseFlow.siteOf("Stand#large", entry, false).rests(), "every position of a part the mesh hangs from its root");
        assertEquals(Map.of("left_arm", Map.of(PoseChannel.X, 5f, PoseChannel.Z, 0f)),
            PoseFlow.siteOf("Stand#large", entry, true).rests(),
            "the shift moves the y after the pose flow has run, so the y it parsed proves nothing");
        assertEquals(Map.of(), PoseFlow.siteOf("Stand#gone", null, false).rests(), "a mesh the table lacks answers nothing");
    }

    /** A body whose filled slot squeezes it by {@code 0.9375}, times the age where {@code aged} says so. */
    private static @NotNull PoseOutcome.Extracted squeezedBy(boolean aged) {
        PoseExpr filled = aged
            ? PoseExpr.operation(PoseOperator.MUL, new PoseExpr.Constant(0.9375f), new PoseExpr.Input(PoseFlow.AGE_SCALE_FIGURE))
            : new PoseExpr.Constant(0.9375f);
        PoseExpr scale = new PoseExpr.Select(new PoseExpr.Answered.InputFn("bodyItem", "isEmpty").truthy(),
            new PoseExpr.BoneRead("body", PoseChannel.X_SCALE), filled);
        PoseExpr lift = agedOffset(1f);
        return new PoseOutcome.Extracted(new PoseProgram("GhastModel", List.of(),
            Map.of("body", Map.of(PoseChannel.X_SCALE, scale, PoseChannel.Y, attacking("body", PoseChannel.Y, lift))),
            Map.of(), List.of()));
    }

    @Test
    @DisplayName("a wearer row of a body reading the age at two ages is folded at each, and refuses where they part")
    void aWearerRowAtTwoAgesHasToAgree() {
        JsonTree models = JsonTree.object().put("minecraft:ghast",
            withSize("GhastModel#createBodyLayer", "GhastModel#createBodyLayer@baby=x", 0.5f));
        models.child("minecraft:ghast").childArray("equipment").add(JsonTree.object()
            .put("slot", "body")
            .put("geometry", "HarnessModel#createHarnessLayer")
            .put(PoseFlow.ITEM_FIELD, "bodyItem"));
        Map<String, Float> defaults = Map.of(PoseFlow.AGE_SCALE_FIGURE, 1f, "attackTime", 0f);

        PoseOutcome.Extracted agreeing = squeezedBy(false);
        Map<String, PoseOutcome> folded = Map.of("GhastModel", new PoseOutcome.Extracted(PoseFold.fold(
            agreeing.program(), Map.of(), Map.of(), Map.of(), defaults, Set.of(), Set.of(), Map.of())));
        Map<String, PoseOutcome> out = PoseFlow.foldWearers(Map.of("GhastModel", agreeing), models, folded,
            Map.of(), Map.of(), defaults, Map.of(), diagnostics);
        assertEquals(new PoseExpr.Constant(0.9375f),
            ((PoseOutcome.Extracted) out.get("GhastModel@bodyItem.isEmpty=false")).program().bones().get("body")
                .get(PoseChannel.X_SCALE), "a filled fold the age does not move is one row at both ages");

        PoseOutcome.Extracted parting = squeezedBy(true);
        ToolingException raised = assertThrows(ToolingException.class, () -> PoseFlow.foldWearers(
            Map.of("GhastModel", parting), models, folded, Map.of(), Map.of(), defaults, Map.of(), diagnostics));
        assertTrue(raised.getMessage().contains("GhastModel"), raised.getMessage());
        assertTrue(raised.getMessage().contains("body.x_scale"), raised.getMessage());
    }

    /** A walked row whose tail moves by {@code ageScale}, or by a figure that is not the age. */
    private static @NotNull PoseOutcome.Extracted tailBy(@NotNull String figure) {
        return new PoseOutcome.Extracted(new PoseProgram("BabyModel", List.of(),
            Map.of("tail", Map.of(PoseChannel.Y, new PoseExpr.Input(figure))), Map.of(), List.of()));
    }

    @Test
    @DisplayName("a model reading ageScale folds at the one age every site reaching it renders at")
    void aModelReadingTheAgeFoldsAtItsSitesAge() {
        Map<String, Float> defaults = Map.of(PoseFlow.AGE_SCALE_FIGURE, 1f);

        assertEquals(Optional.of(0.5f), PoseFlow.foldAge("BabyModel", tailBy(PoseFlow.AGE_SCALE_FIGURE),
            Map.of(0.5f, Set.of("minecraft:foal")), defaults), "a baby-only class folds at the baby's age");
        assertEquals(Optional.empty(), PoseFlow.foldAge("BabyModel", tailBy(PoseFlow.AGE_SCALE_FIGURE),
            Map.of(1f, Set.of("minecraft:horse")), defaults), "an adult-only class keeps the shared defaults");
        assertEquals(Optional.empty(), PoseFlow.foldAge("BabyModel", tailBy(PoseFlow.AGE_SCALE_FIGURE),
            Map.of(1f, Set.of("minecraft:horse"), 0.5f, Set.of("minecraft:foal")), defaults),
            "a class reached at two ages has no one age here, and foldAll folds it at each");
        assertEquals(Optional.empty(), PoseFlow.foldAge("BabyModel", tailBy("walkAnimationSpeed"),
            Map.of(0.5f, Set.of("minecraft:foal")), defaults), "a class reading no age has none to fold");
    }

    @Test
    @DisplayName("a model reading ageScale at a baby with no readable age refuses, naming class and subject")
    void anUnreadAgeRefusesAModelReadingIt() {
        Map<String, Float> defaults = Map.of(PoseFlow.AGE_SCALE_FIGURE, 1f);
        Map<Float, Set<String>> unread = Map.of(PoseFlow.NO_AGE, Set.of("minecraft:odd"));

        ToolingException raised = assertThrows(ToolingException.class,
            () -> PoseFlow.foldAge("BabyModel", tailBy(PoseFlow.AGE_SCALE_FIGURE), unread, defaults));
        assertTrue(raised.getMessage().contains("BabyModel"), raised.getMessage());
        assertTrue(raised.getMessage().contains("minecraft:odd"), raised.getMessage());

        assertEquals(Optional.empty(), PoseFlow.foldAge("BabyModel", tailBy("walkAnimationSpeed"), unread, defaults),
            "a class reading no age costs nothing where its baby's age is unread");
    }

}
