package lib.minecraft.renderer.tooling.animation;

import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lib.minecraft.renderer.tooling.kernel.ToolingException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
            Map.of("body", Map.of(PoseChannel.X_ROT, PoseExpr.Const.of(0.5f))), List.of()));
    }

    @Test
    @DisplayName("a row whose renderer composes carries steps, ground frame, then its own container")
    void aComposedRowCarriesItsWholeStack() {
        JsonTree models = JsonTree.object().put("minecraft:cod", subject("CodRenderer", "CodModel#createBodyLayer"));
        Map<PoseChannel, PoseExpr> step = Map.of(PoseChannel.Z_ROT, PoseExpr.Const.of(1.5707964f));
        Map<String, RenderTransform> transforms =
            Map.of("CodRenderer", RenderTransform.of("CodRenderer", 0f, List.of(step)));

        Map<String, PoseOutcome> out = PoseFlow.composeContainers(
            Map.of("CodModel", posing("CodModel")), models, transforms, diagnostics);

        PoseProgram program = ((PoseOutcome.Extracted) out.get("CodModel")).program();
        assertEquals(2, program.container().size(), "one composed step and the frame that seats it");
        assertEquals(step, program.container().getFirst());
        assertEquals(Map.of(PoseChannel.Y, PoseExpr.Const.of(-24.016f)), program.container().getLast(),
            "the ground frame is the float bits of -1.501 blocks in model pixels, exactly");
        assertEquals(PoseExpr.Const.of(-1.501f * 16f),
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
            List.of(Map.of(PoseChannel.Z_ROT, PoseExpr.Const.of(1.5707964f)))));

        assertThrows(ToolingException.class, () -> PoseFlow.composeContainers(
                Map.of("SharedModel", posing("SharedModel")), models, transforms, diagnostics),
            "one container cannot answer for two renderers' sequences");
    }

}
