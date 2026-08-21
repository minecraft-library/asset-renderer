package lib.minecraft.renderer.tooling.animation;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import dev.simplified.gson.GsonSettings;
import lib.minecraft.renderer.client.ClientAcquisition;
import lib.minecraft.renderer.client.ClientOptions;
import lib.minecraft.renderer.tooling.kernel.ClassNodeCache;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pose walk against the real client jar.
 *
 * <p>Three models are pinned expression by expression, which is what says the walk builds vanilla's
 * own arithmetic rather than merely finishing without complaint: one for the arithmetic itself, one
 * for a bone posed from another bone's freshly written value, and one for a loop that has to unroll
 * into a different expression per index. The roster count says how far the walk reaches before an
 * undecided branch or an unenterable call stops it - a number expected to move as those are
 * answered, asserted so it cannot move by accident, and carrying the refusal reasons in its message
 * so what is left is a list rather than a number.
 *
 * <p>Tagged {@code slow}: the walk runs against the downloaded client jar.
 */
@Tag("slow")
@DisplayName("the pose walk")
class PoseWalkTest {

    private static final @NotNull Gson GSON = GsonSettings.defaults().create();

    /** The shipped table, relative to the renderer root every Test task runs at. */
    private static final @NotNull Path SHIPPED_GEOMETRY =
        Path.of("src/main/resources/lib/minecraft/renderer/entity_geometry.json");

    private static ClassNodeCache cache;
    private static Diagnostics diagnostics;
    private static Map<String, PoseProgram> extracted;
    private static List<String> roster;

    @BeforeAll
    static void walk() {
        cache = ClassNodeCache.open(ClientAcquisition.downloadJarToCache(ClientOptions.defaults()));
        diagnostics = Diagnostics.root("pose", Diagnostics.Output.NONE, null);
        roster = rosterClasses();
        extracted = new TreeMap<>();
        for (String model : roster) {
            Optional<PoseProgram> program = PoseWalk.extract(cache, model, diagnostics);
            program.ifPresent(value -> extracted.put(model, value));
        }
    }

    @AfterAll
    static void close() {
        if (cache != null) cache.close();
    }

    @Test
    @DisplayName("the roster is the hundred and eleven classes the geometry table names")
    void rosterIsTheGeometryTablesOwn() {
        assertEquals(111, roster.size(), "distinct model classes the geometry table sources a mesh from");
    }

    @Test
    @DisplayName("a pufferfish fin is the expression vanilla computes, operand for operand")
    void pufferfishFinIsPinned() {
        // PufferfishBigModel.setupAnim is two statements of pure arithmetic, so the whole tree can
        // be written out. It is the only assertion here that says the walk builds what vanilla
        // computes rather than merely finishing without complaint, and it pins three things a
        // looser test would miss: that the operands stay in vanilla's order, that the widening
        // before Mth.sin is a node rather than an implicit cast, and that the sine is the sampled
        // one rather than libm.
        PoseProgram fish = extracted.get("net/minecraft/client/model/animal/fish/PufferfishBigModel");
        assertNotNull(fish, "PufferfishBigModel is expected to extract");

        PoseExpr wave = PoseExpr.Op.of(PoseOperator.MTH_SIN,
            PoseExpr.Op.of(PoseOperator.F2D,
                PoseExpr.Op.of(PoseOperator.MUL, new PoseExpr.Input("ageInTicks"), PoseExpr.Const.of(0.2f))));

        assertEquals(
            PoseExpr.Op.of(PoseOperator.ADD, PoseExpr.Const.of(-0.2f),
                PoseExpr.Op.of(PoseOperator.MUL, PoseExpr.Const.of(0.4f), wave)),
            fish.bones().get("right_blue_fin").get(PoseChannel.Z_ROT),
            "the right fin leans out of a sampled sine of the age");

        assertEquals(
            PoseExpr.Op.of(PoseOperator.SUB, PoseExpr.Const.of(0.2f),
                PoseExpr.Op.of(PoseOperator.MUL, PoseExpr.Const.of(0.4f), wave)),
            fish.bones().get("left_blue_fin").get(PoseChannel.Z_ROT),
            "the left fin is the same wave subtracted, not the negation of the right");
    }

    @Test
    @DisplayName("a snow golem's arms carry the body's angle itself, not a reference to it")
    void crossBoneReadIsSubstituted() {
        // SnowGolemModel poses its upper body from the head yaw and then poses both arms FROM the
        // upper body's freshly written yaw. This is the assertion the whole shape of PoseProgram
        // rests on: because the read is substituted where it happens, the arm carries the body's
        // expression outright and no ordinal is needed to say the body was posed first. If reads
        // were left as references instead, these three would have to be replayed in order.
        PoseProgram golem = extracted.get("net/minecraft/client/model/animal/golem/SnowGolemModel");
        assertNotNull(golem, "SnowGolemModel is expected to extract");

        PoseExpr upperBodyYaw = PoseExpr.Op.of(PoseOperator.MUL,
            PoseExpr.Op.of(PoseOperator.MUL, new PoseExpr.Input("yRot"), PoseExpr.Const.of(0.017453292f)),
            PoseExpr.Const.of(0.25f));

        assertEquals(upperBodyYaw, golem.bones().get("upper_body").get(PoseChannel.Y_ROT),
            "the upper body turns a quarter as far as the head");
        assertEquals(upperBodyYaw, golem.bones().get("left_arm").get(PoseChannel.Y_ROT),
            "the left arm carries the upper body's own expression rather than a reference to it");
        assertEquals(PoseExpr.Op.of(PoseOperator.ADD, upperBodyYaw, PoseExpr.Const.of(3.1415927f)),
            golem.bones().get("right_arm").get(PoseChannel.Y_ROT),
            "the right arm is the same angle half a turn round");
    }

    @Test
    @DisplayName("a ghast's tentacles unroll to nine bones, each carrying its own index")
    void arrayLoopUnrollsPerIndex() {
        // GhastModel poses an array of tentacles in a loop bounded by the array's own length, and
        // the phase of each tentacle's wave is its index. Nothing here detects a loop: the counter
        // is a literal the walk can see, so the test that closes the loop decides itself and the
        // body is simply walked again. What that has to produce is nine DIFFERENT expressions, which
        // is the thing a loop left unrolled or unrolled once would get wrong.
        PoseProgram ghast = extracted.get("net/minecraft/client/model/monster/ghast/GhastModel");
        assertNotNull(ghast, "GhastModel is expected to extract");
        assertEquals(9, ghast.bones().size(), "one bone per allocated tentacle");

        for (int index = 0; index < 9; index++) {
            PoseExpr phase = PoseExpr.Op.of(PoseOperator.ADD,
                PoseExpr.Op.of(PoseOperator.MUL, new PoseExpr.Input("ageInTicks"), PoseExpr.Const.of(0.3f)),
                PoseExpr.Op.of(PoseOperator.I2F, PoseExpr.Const.of(index)));
            PoseExpr expected = PoseExpr.Op.of(PoseOperator.ADD,
                PoseExpr.Op.of(PoseOperator.MUL, PoseExpr.Const.of(0.2f),
                    PoseExpr.Op.of(PoseOperator.MTH_SIN, PoseExpr.Op.of(PoseOperator.F2D, phase))),
                PoseExpr.Const.of(0.4f));
            assertEquals(expected, ghast.bones().get("tentacle" + index).get(PoseChannel.X_ROT),
                "tentacle " + index + " waves a phase behind the one before it");
        }
    }

    @Test
    @DisplayName("no extracted pose names a bone outside the model's own mesh")
    void everyPosedBoneExists() {
        Map<String, Set<String>> mesh = meshBones();
        List<String> dangling = new ArrayList<>();
        extracted.forEach((model, program) -> program.bones().keySet().forEach(bone -> {
            if (!mesh.getOrDefault(model, Set.of()).contains(bone))
                dangling.add(program.model() + " -> " + bone);
        }));
        assertEquals(List.of(), dangling, "posed bones no mesh of that model declares");
    }

    @Test
    @DisplayName("a refusal names what stopped it, so the remaining work is a list rather than a count")
    void refusalsAreAttributed() {
        Set<String> reasons = new TreeSet<>();
        diagnostics.entries().forEach(entry -> reasons.add(entry.message().replaceAll("^\\S+ not extracted: ", "")));
        assertTrue(reasons.stream().noneMatch(String::isBlank), "every refusal carries a reason");
        assertEquals(roster.size() - extracted.size(),
            diagnostics.entries().stream().filter(e -> e.message().contains("not extracted")).count(),
            "every model that did not extract said why");
    }

    @Test
    @DisplayName("the linear walk reaches the bodies that are only arithmetic")
    void coverageIsWhatALinearWalkCanReach() {
        // A branch, a loop or a helper call each stop this walk, and most bodies hold at least one,
        // so this number is a floor rather than a target. It is asserted so that adding those
        // cannot quietly move it the wrong way, and it is expected to be edited upward when they
        // land - the refusal reasons are the work list.
        assertEquals(34, extracted.size(),
            () -> "extracted " + extracted.values().stream()
                .map(program -> program.model() + "/" + program.channelCount()).toList()
                + "; refusals were:\n  " + String.join("\n  ", new TreeSet<>(diagnostics.entries().stream()
                    .map(entry -> entry.message().replaceAll("^\\S+ not extracted: ", "")).toList())));
    }

    // ------------------------------------------------------------------------------------

    private static @NotNull List<String> rosterClasses() {
        return List.copyOf(new TreeSet<>(meshBones().keySet()));
    }

    private static @NotNull Map<String, Set<String>> meshBones() {
        JsonElement root = GSON.fromJson(read(SHIPPED_GEOMETRY), JsonElement.class);
        Map<String, Set<String>> out = new TreeMap<>();
        root.getAsJsonObject().getAsJsonObject("geometries").entrySet().forEach(entry -> {
            var mesh = entry.getValue().getAsJsonObject();
            String owner = mesh.getAsJsonObject("source").get("class").getAsString();
            out.computeIfAbsent(owner, key -> new TreeSet<>()).addAll(mesh.getAsJsonObject("bones").keySet());
        });
        return out;
    }

    private static @NotNull Reader read(@NotNull Path path) {
        try {
            return Files.newBufferedReader(path, StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

}
