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
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pose walk against the real client jar.
 *
 * <p>A model is pinned expression by expression wherever a mechanism could otherwise finish without
 * complaint and be wrong: the arithmetic itself, a bone posed from another bone's freshly written
 * value, a loop that has to unroll into a different expression per index, a branch nothing offline
 * decides, a branch nested inside another which meets its own arm where the outer one is waiting, a
 * pose divided between a model and the base it inherits, and a question asked of the render state
 * whose answer a bone is made visible by.
 *
 * <p>Others are pinned for what their expressions NAME rather than for the shape they take, all
 * being cases that would render perfectly while being wrong: a golem asking each of its hands the
 * same word has to be answered twice, a wither posing two heads out of one array of angles has to
 * read two indices of it, a spear's swing has to ask eight separate things of each hand rather than
 * one thing eight times, a strider's six bristles have to keep the six rates one lambda was applied
 * at rather than settling on whichever was applied last, an allay has to spin about the bone its
 * constructor re-rooted it at, a foal has to hold the pitch it assigned itself rather than the one
 * it was handed, and a piglin's crossbow has to land on the arm its render state calls the main one.
 *
 * <p>The clip half is checked differently, against the shipped table rather than against a written
 * expectation. Two mechanisms that never see each other - the binding resolver reading constructors
 * and play sites, and this walk meeting an application mid-body - have to name the same clips under
 * the same drives, and that agreement is worth more than either one restated by hand.
 *
 * <p>The roster count says how far the walk reaches before an unenterable call or an unnameable bone
 * stops it - a number expected to move as those are answered, asserted so it cannot move by accident,
 * and carrying a weighted list of what is still refusing so the next thing to answer is chosen by
 * how many subjects it holds up.
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

    /** The shipped pose table, which the clip half of a walk has to agree with. */
    private static final @NotNull Path SHIPPED_POSES =
        Path.of("src/main/resources/lib/minecraft/renderer/entity_poses.json");

    private static ClassNodeCache cache;
    private static Diagnostics diagnostics;
    private static Map<String, PoseProgram> extracted;
    private static List<String> roster;

    @BeforeAll
    static void walk() {
        cache = ClassNodeCache.open(ClientAcquisition.downloadJarToCache(ClientOptions.defaults()));
        diagnostics = Diagnostics.root("pose", Diagnostics.Output.NONE, null);
        roster = rosterClasses();
        Map<String, Set<String>> rootBones = meshRootBones();
        extracted = new TreeMap<>();
        for (String model : roster)
            if (PoseWalk.extract(cache, model, rootBones.getOrDefault(model, Set.of()), diagnostics)
                instanceof PoseOutcome.Extracted walked)
                extracted.put(model, walked.program());
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
    @DisplayName("a cod's tail keeps both arms of the branch nothing offline decides")
    void undecidedBranchBecomesAChoice() {
        // CodModel swims at one speed in water and flaps at another out of it, and nothing offline
        // says which. Both arms are kept and the choice is handed to whoever supplies the inputs -
        // which is the whole point: picking an arm here would ship one of the poses a cod can take
        // as though it were the pose, and picking the wrong one is how a fish renders mid-flop.
        PoseProgram cod = extracted.get("net/minecraft/client/model/animal/fish/CodModel");
        assertNotNull(cod, "CodModel is expected to extract");

        PoseExpr wave = PoseExpr.Op.of(PoseOperator.MTH_SIN,
            PoseExpr.Op.of(PoseOperator.F2D,
                PoseExpr.Op.of(PoseOperator.MUL, PoseExpr.Const.of(0.6f), new PoseExpr.Input("ageInTicks"))));

        // The choice sits around the SPEED, which is the only thing the two arms disagreed about,
        // and the tail sweep is written once. That is the join-point merge earning its keep: merging
        // at the end of the body instead would have hoisted the choice over the whole sweep and
        // written the sine twice, which is correct and twice the size.
        PoseExpr speed = new PoseExpr.Select(
            PosePredicate.Compare.of(PosePredicate.Comparison.EQ,
                new PoseExpr.Input("isInWater"), PoseExpr.Const.of(0)),
            PoseExpr.Const.of(1.5f), PoseExpr.Const.of(1.0f));

        assertEquals(
            PoseExpr.Op.of(PoseOperator.MUL,
                PoseExpr.Op.of(PoseOperator.MUL,
                    PoseExpr.Op.of(PoseOperator.NEG, speed), PoseExpr.Const.of(0.45f)),
                wave),
            cod.bones().get("tail_fin").get(PoseChannel.Y_ROT),
            "out of water the tail sweeps half again as far");
    }

    @Test
    @DisplayName("a choice stays around the operand that differed rather than over what followed it")
    void choicesDoNotHoist() {
        // The structural half of the cod assertion, said once so it cannot be lost in a rewrite of
        // the arithmetic: a channel whose branch merged at the join has its Select buried inside the
        // expression, not sitting on top of it.
        PoseProgram cod = extracted.get("net/minecraft/client/model/animal/fish/CodModel");
        assertNotNull(cod, "CodModel is expected to extract");
        assertInstanceOf(PoseExpr.Op.class, cod.bones().get("tail_fin").get(PoseChannel.Y_ROT),
            "a hoisted choice would make the whole channel a Select");
    }

    @Test
    @DisplayName("a wolf's roll is the render state's own arithmetic, clamp and all")
    void renderStateArithmeticIsWalkedRatherThanNamed() {
        // WolfModel poses the body from getBodyRollAngle, a render-state method that computes an
        // angle out of one field the state carries. Walking it rather than naming it is what keeps
        // the expression in terms of shakeAnim, which a caller can supply, instead of an opaque
        // channel nothing could.
        //
        // It is also the corpus's proof that a nested branch closing where its enclosing branch was
        // already waiting has a join: the clamp's upper arm jumps straight to the lower arm's join,
        // so the two levels of Select below exist only if that point counts as somewhere both arms
        // reached. Without it the walk runs the body twice and loses the value it was computing.
        PoseProgram wolf = extracted.get("net/minecraft/client/model/animal/wolf/AdultWolfModel");
        assertNotNull(wolf, "AdultWolfModel is expected to extract");

        PoseExpr shake = PoseExpr.Op.of(PoseOperator.DIV,
            PoseExpr.Op.of(PoseOperator.ADD, new PoseExpr.Input("shakeAnim"), PoseExpr.Const.of(-0.16f)),
            PoseExpr.Const.of(1.8f));
        PoseExpr clamped = new PoseExpr.Select(
            PosePredicate.Compare.of(PosePredicate.Comparison.GE, shake, PoseExpr.Const.of(0f)),
            new PoseExpr.Select(
                PosePredicate.Compare.of(PosePredicate.Comparison.LE, shake, PoseExpr.Const.of(1f)),
                shake, PoseExpr.Const.of(1f)),
            PoseExpr.Const.of(0f));
        PoseExpr turn = PoseExpr.Op.of(PoseOperator.MUL, clamped, PoseExpr.Const.of(3.1415927f));

        assertEquals(
            PoseExpr.Op.of(PoseOperator.MUL,
                PoseExpr.Op.of(PoseOperator.MUL,
                    PoseExpr.Op.of(PoseOperator.MUL,
                        PoseExpr.Op.of(PoseOperator.MTH_SIN, PoseExpr.Op.of(PoseOperator.F2D, turn)),
                        PoseExpr.Op.of(PoseOperator.MTH_SIN, PoseExpr.Op.of(PoseOperator.F2D,
                            PoseExpr.Op.of(PoseOperator.MUL, turn, PoseExpr.Const.of(11.0f))))),
                    PoseExpr.Const.of(0.15f)),
                PoseExpr.Const.of(3.1415927f)),
            wolf.bones().get("body").get(PoseChannel.Z_ROT),
            "the body rolls on two sines of the clamped shake");
    }

    @Test
    @DisplayName("a base that poses is walked, and a base that only resets is not")
    void inheritedPoseSurvivesTheResetTest() {
        // Whether a super.setupAnim is the reset or an inherited pose is decided by where the body
        // it reaches is declared, and both answers have to keep working or the failure is silent:
        // treating a posing base as the reset drops every term it contributed, and the leaf still
        // extracts, still names bones its mesh has, and still says nothing about what went missing.
        //
        // The sheep answers both at once. Its legs are posed by the quadruped base it calls up to
        // and its head by its own body, so the two channels below can only both be right if the
        // walk entered one and not the other. The feline sits on the opposite side of the same
        // test - its base declares no pose at all, so the identical instruction IS the reset.
        PoseProgram sheep = extracted.get("net/minecraft/client/model/animal/sheep/SheepModel");
        assertNotNull(sheep, "SheepModel is expected to extract");

        assertEquals(
            PoseExpr.Op.of(PoseOperator.MUL,
                PoseExpr.Op.of(PoseOperator.MUL,
                    PoseExpr.Op.of(PoseOperator.MTH_COS, PoseExpr.Op.of(PoseOperator.F2D,
                        PoseExpr.Op.of(PoseOperator.MUL,
                            new PoseExpr.Input("walkAnimationPos"), PoseExpr.Const.of(0.6662f)))),
                    PoseExpr.Const.of(1.4f)),
                new PoseExpr.Input("walkAnimationSpeed")),
            sheep.bones().get("right_hind_leg").get(PoseChannel.X_ROT),
            "the hind leg swings the quadruped base's own stride, which only the base writes");

        assertEquals(new PoseExpr.Input("headEatAngleScale"),
            sheep.bones().get("head").get(PoseChannel.X_ROT),
            "the head is the sheep's own, written over the base's after it");
    }

    @Test
    @DisplayName("a frog's croaking body is visible exactly while the croak is running")
    void aQuestionAnswersAsANumber() {
        // The frog asks whether an animation is running and writes the answer straight into a bone's
        // visibility, with no branch anywhere between the two. That only works because the answer is
        // a number by the time it arrives, which is what a boolean the render state declares as a
        // field already is - so a question it answers through a method is spelled the same way.
        PoseProgram frog = extracted.get("net/minecraft/client/model/animal/frog/FrogModel");
        assertNotNull(frog, "FrogModel is expected to extract");

        assertEquals(new PoseExpr.InputFn("croakAnimationState", "isStarted"),
            frog.bones().get("croaking_body").get(PoseChannel.VISIBLE),
            "the croaking body draws while the croak runs and not otherwise");
    }

    @Test
    @DisplayName("a wither's two heads read two indices of the same array of angles")
    void anArrayElementIsPinnedAtItsIndex() {
        // Both heads are posed by one helper handed a different index, so the whole difference
        // between them is that literal. Losing it would point both heads the same way, and losing
        // the read entirely is worse than that: an array load that neither pops nor pushes leaves
        // the array standing where a number should be, and the multiply after it goes on to build a
        // perfectly well formed angle out of the index.
        PoseProgram wither = extracted.get("net/minecraft/client/model/monster/wither/WitherBossModel");
        assertNotNull(wither, "WitherBossModel is expected to extract");

        assertEquals(
            PoseExpr.Op.of(PoseOperator.MUL,
                PoseExpr.Op.of(PoseOperator.SUB,
                    new PoseExpr.InputElement("yHeadRots", 0), new PoseExpr.Input("bodyRot")),
                PoseExpr.Const.of(0.017453292f)),
            wither.bones().get("right_head").get(PoseChannel.Y_ROT),
            "the right head turns off the first tracked yaw, against the body");

        assertEquals(
            PoseExpr.Op.of(PoseOperator.MUL,
                new PoseExpr.InputElement("xHeadRots", 1), PoseExpr.Const.of(0.017453292f)),
            wither.bones().get("left_head").get(PoseChannel.X_ROT),
            "the left head tilts off the second tracked pitch");
    }

    @Test
    @DisplayName("a copper golem asks each hand on its own, and is answered twice")
    void aQuestionIsNamedAfterWhatItIsAskedOf() {
        // The golem asks whether the right hand is empty and then whether the left is: one word,
        // asked of two things. Naming the question alone collapses those into a single input, and
        // nothing downstream could tell - the pose still builds, the golem still renders, and it
        // renders as though its two hands always agreed with each other.
        PoseProgram golem = extracted.get("net/minecraft/client/model/animal/golem/CopperGolemModel");
        assertNotNull(golem, "CopperGolemModel is expected to extract");

        Set<PoseExpr.InputFn> asked = new LinkedHashSet<>();
        golem.bones().values().forEach(channels -> channels.values().forEach(expr -> collectQuestions(expr, asked)));

        assertEquals(
            Set.of(new PoseExpr.InputFn("rightHandItemState", "isEmpty"),
                new PoseExpr.InputFn("leftHandItemState", "isEmpty")),
            asked, "each hand is an input of its own");
    }

    /** Every question asked of the render state anywhere inside an expression. */
    private static void collectQuestions(PoseExpr expr, Set<PoseExpr.InputFn> out) {
        switch (expr) {
            case PoseExpr.InputFn question -> out.add(question);
            case PoseExpr.Op op -> op.operands().forEach(operand -> collectQuestions(operand, out));
            case PoseExpr.Select select -> {
                collectQuestions(select.whenTrue(), out);
                collectQuestions(select.whenFalse(), out);
                collectQuestions(select.condition(), out);
            }
            default -> { /* a leaf asks nothing */ }
        }
    }

    private static void collectQuestions(PosePredicate predicate, Set<PoseExpr.InputFn> out) {
        switch (predicate) {
            case PosePredicate.Not not -> collectQuestions(not.operand(), out);
            case PosePredicate.Compare compare -> {
                collectQuestions(compare.left(), out);
                collectQuestions(compare.right(), out);
            }
            default -> { /* an enum test or a decided constant asks nothing */ }
        }
    }

    @Test
    @DisplayName("the clips a walk finds are the clips the shipped table already binds")
    void clipSitesAgreeWithTheShippedTable() {
        // Two mechanisms that never see each other: the binding resolver reads constructors and play
        // sites to build the shipped table, while the walk meets an application mid-body and asks
        // what is being applied. They have to name the same clips under the same drives, and a
        // disagreement means one of them is reading the corpus wrongly.
        Map<String, Set<String>> shipped = shippedBindings();
        Map<String, Set<String>> walked = new TreeMap<>();
        extracted.values().forEach(program -> {
            if (program.clipSites().isEmpty()) return;
            walked.put(program.model(), program.clipSites().stream()
                .map(site -> site.clip() + "/" + site.drive().token())
                .collect(Collectors.toCollection(TreeSet::new)));
        });

        assertTrue(!walked.isEmpty(), "the walk is expected to reach some clip applications");
        walked.forEach((model, clips) ->
            assertEquals(shipped.getOrDefault(model, Set.of()), clips, model + " plays these clips"));
    }

    @Test
    @DisplayName("a walk-driven clip carries the model's own timing and amplitude")
    void walkDrivenClipsKeepTheirConstants() {
        // applyWalk(pos, speed, maxSpeed, scale): the first two come off the render state and the
        // last two are constants the MODEL owns, living nowhere in the clip. They are the reason a
        // play site is recorded rather than skipped, so a site that lost them would look like a
        // success. Two models applying one clip at different constants is the case that proves it.
        List<PoseClipSite> walkDriven = extracted.values().stream()
            .flatMap(program -> program.clipSites().stream())
            .filter(site -> site.drive() == ClipBinding.Gate.WALK)
            .toList();

        assertTrue(!walkDriven.isEmpty(), "the corpus is expected to drive clips off the walk");
        for (PoseClipSite site : walkDriven) {
            assertEquals(4, site.arguments().size(), site.clip() + " is driven by four arguments");
            assertInstanceOf(PoseExpr.Const.class, site.arguments().get(2),
                site.clip() + " runs at a constant rate against the walk");
            assertInstanceOf(PoseExpr.Const.class, site.arguments().get(3),
                site.clip() + " swings by a constant amount");
        }
    }

    @Test
    @DisplayName("a state-driven clip carries its tick and drops the state it is gated on")
    void stateDrivenClipsCarryOnlyTheTick() {
        List<PoseClipSite> stateDriven = extracted.values().stream()
            .flatMap(program -> program.clipSites().stream())
            .filter(site -> site.drive() == ClipBinding.Gate.STATE)
            .toList();

        assertTrue(!stateDriven.isEmpty(), "the corpus is expected to gate clips behind animation states");
        // The AnimationState operand is a reference nothing offline evaluates, and the drive already
        // says the clip sits behind one, so it is absent rather than placeheld.
        stateDriven.forEach(site ->
            assertEquals(1, site.arguments().size(), site.clip() + " is driven by a tick alone"));
    }

    @Test
    @DisplayName("a parrot's pose turns on its own enum, one guard per constant but the last")
    void anEnumSwitchKeepsOneGuardPerConstant() {
        // A switch over an enum is answered by walking the body once per constant and keeping the
        // guards, folded so the constant declared last is what remains when none of the others
        // matched. Worth pinning rather than discovering, because the near misses are all well
        // formed and all mean something else: a guard for every constant including the last says
        // the pose can be none of them, and guards in a different order says a different pose.
        //
        // The parrot is the whole of it - five poses, one switch, and every arm walkable.
        PoseProgram parrot = extracted.get("net/minecraft/client/model/animal/parrot/ParrotModel");
        assertNotNull(parrot, "ParrotModel is expected to extract");

        List<PosePredicate.EnumEq> tests = new ArrayList<>();
        parrot.bones().values().forEach(channels -> channels.values()
            .forEach(expr -> collectEnumTests(expr, tests)));

        assertEquals(Set.of("pose"),
            tests.stream().map(PosePredicate.EnumEq::field).collect(Collectors.toSet()),
            "the only thing a parrot's pose turns on");
        assertEquals(Set.of("FLYING", "STANDING", "SITTING", "PARTY"),
            tests.stream().map(PosePredicate.EnumEq::constant).collect(Collectors.toSet()),
            "one guard per pose but ON_SHOULDER, which is what is left when none of them matched");
    }

    /** Every enum test anywhere inside an expression, however deeply a choice nests it. */
    private static void collectEnumTests(PoseExpr expr, List<PosePredicate.EnumEq> out) {
        switch (expr) {
            case PoseExpr.Op op -> op.operands().forEach(operand -> collectEnumTests(operand, out));
            case PoseExpr.Select select -> {
                collectEnumTests(select.whenTrue(), out);
                collectEnumTests(select.whenFalse(), out);
                collectEnumTests(select.condition(), out);
            }
            default -> { /* a leaf holds no test */ }
        }
    }

    private static void collectEnumTests(PosePredicate predicate, List<PosePredicate.EnumEq> out) {
        switch (predicate) {
            case PosePredicate.EnumEq test -> out.add(test);
            case PosePredicate.Not not -> collectEnumTests(not.operand(), out);
            case PosePredicate.Compare compare -> {
                collectEnumTests(compare.left(), out);
                collectEnumTests(compare.right(), out);
            }
            default -> { /* a decided constant holds no enum test */ }
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
    @DisplayName("a spear is held at the angle vanilla computes, operand for operand")
    void spearArmIsPinned() {
        // The whole of the spear pose a renderer that models no item components draws, written out.
        // Vanilla adds a sway term to this from figures a kinetic weapon carries, and every one of
        // them is zero when there is no such component - so this expression is not an approximation
        // of that path, it IS it.
        //
        // Pinned for both arms rather than one, because the two differ only by the sign the walk
        // folds out of a boolean the call site passes: taking the flip the wrong way round poses a
        // spear a fifth of a radian to the wrong side and nothing downstream can tell.
        PoseProgram humanoid = extracted.get("net/minecraft/client/model/HumanoidModel");
        assertNotNull(humanoid, "HumanoidModel is expected to extract");

        List<PoseExpr> reached = nodesOf(humanoid);
        assertTrue(reached.contains(spearHold(-0.1f)), "the right arm holds a spear a tenth off the head's yaw");
        assertTrue(reached.contains(spearHold(0.1f)), "the left arm holds it the same distance the other way");
    }

    /**
     * The angle an arm holds a spear at, for the sign the call site folded out of its own boolean.
     *
     * <p>The head yaw it leans off is the one {@code setupAnim} has already written, substituted
     * where it is read - so this is the whole expression rather than a reference to the head.
     */
    private static @NotNull PoseExpr spearHold(float lean) {
        PoseExpr headYaw = PoseExpr.Op.of(PoseOperator.MUL,
            new PoseExpr.Input("yRot"), PoseExpr.Const.of(0.017453292f));
        return PoseExpr.Op.of(PoseOperator.MUL, PoseExpr.Const.of(0.017453292f),
            PoseExpr.Op.of(PoseOperator.CLAMP,
                PoseExpr.Op.of(PoseOperator.MUL, PoseExpr.Const.of(57.295776f),
                    PoseExpr.Op.of(PoseOperator.ADD, PoseExpr.Const.of(lean), headYaw)),
                PoseExpr.Const.of(-60f), PoseExpr.Const.of(60f)));
    }

    @Test
    @DisplayName("a spear's sway asks eight things of each hand, and each hand answers for itself")
    void theSwayTermAsksEachFigureOnItsOwn() {
        // The eight figures a kinetic weapon swings at live in server data no offline run carries,
        // so they are named rather than derived. Eight DISTINCT inputs per hand is the whole of what
        // makes that safe: a walk that collapsed them would build a sway term out of one number, and
        // the arm would still move, smoothly, and wrongly.
        //
        // Two hands and not one for the same reason a golem asks each of its own: the stack being
        // used is part of the question, and a name that dropped it would sway both arms alike.
        PoseProgram humanoid = extracted.get("net/minecraft/client/model/HumanoidModel");
        assertNotNull(humanoid, "HumanoidModel is expected to extract");

        Set<PoseExpr.InputFn> asked = new LinkedHashSet<>();
        humanoid.bones().values().forEach(channels -> channels.values()
            .forEach(expr -> collectQuestions(expr, asked)));

        Set<PoseExpr.InputFn> expected = new LinkedHashSet<>();
        for (String hand : List.of("RIGHT", "LEFT"))
            for (String figure : List.of("swayIntensity", "swayScaleSlow", "swayScaleFast",
                "raiseProgressStart", "raiseProgressMiddle", "raiseProgressEnd",
                "lowerProgress", "raiseBackProgress"))
                expected.add(new PoseExpr.InputFn(
                    "getUseItemStackForArm(" + hand + ").KINETIC_WEAPON", figure));

        assertEquals(expected, asked, "eight figures per hand, each an input of its own");
    }

    @Test
    @DisplayName("a spear's sway is guarded on the hand carrying the component it is read off")
    void theSwayTermIsGuardedOnTheComponent() {
        // The guard a caller that models no item components answers false to, which is what makes
        // the pose above the one vanilla draws rather than a term nothing supplies.
        PoseProgram humanoid = extracted.get("net/minecraft/client/model/HumanoidModel");
        assertNotNull(humanoid, "HumanoidModel is expected to extract");

        Set<PosePredicate.Has> guards = new LinkedHashSet<>();
        humanoid.bones().values().forEach(channels -> channels.values()
            .forEach(expr -> collectPresence(expr, guards)));

        assertEquals(Set.of(
                new PosePredicate.Has("getUseItemStackForArm(RIGHT).KINETIC_WEAPON"),
                new PosePredicate.Has("getUseItemStackForArm(LEFT).KINETIC_WEAPON")),
            guards, "one guard per hand, naming the path the figures are read down");
    }

    @Test
    @DisplayName("a turtle carrying an egg drops by one, and everything the container held drops with it")
    void aFlattenedContainerFoldsOntoTheBonesItHeld() {
        // AdultTurtleModel poses the container it was built around - root.y -= 1, while the egg belly
        // is showing - and the mesh flattened that container away, so the write has nowhere of its
        // own to land. It lands on each of the bones the container held instead, which is the same
        // total move: translation composes by addition.
        //
        // Two things have to be right and each is a different animal. The set has to be the WHOLE of
        // what the container held, because a bone left out stays where it was while the rest of the
        // turtle drops. And what each bone is given has to be the amount the write MOVED the
        // container by rather than what it left it holding - which is why a turtle with no egg moves
        // by exactly nothing, and why the container's own value never has to be known.
        PoseProgram turtle = extracted.get("net/minecraft/client/model/animal/turtle/AdultTurtleModel");
        assertNotNull(turtle, "AdultTurtleModel is expected to extract");

        PoseExpr shift = new PoseExpr.Select(
            PosePredicate.Compare.of(PosePredicate.Comparison.EQ,
                new PoseExpr.Input("hasEgg"), PoseExpr.Const.of(0)),
            PoseExpr.Const.of(0f), PoseExpr.Const.of(-1f));

        Set<String> held = Set.of("head", "body", "egg_belly",
            "right_hind_leg", "left_hind_leg", "right_front_leg", "left_front_leg");
        assertEquals(held, turtle.bones().keySet(), "the container held every bone this mesh names");
        for (String bone : held)
            assertEquals(PoseExpr.Op.of(PoseOperator.ADD, new PoseExpr.BoneRead(bone, PoseChannel.Y), shift),
                turtle.bones().get(bone).get(PoseChannel.Y),
                bone + " drops by whatever the container it hung off was moved by");
    }

    @Test
    @DisplayName("a piglin's crossbow arms turn on which arm is its main one, and land the right way round")
    void aSplitReachesInsideAComparisonTheCallerBuilt() {
        // The crossbow hold picks a main arm and an off arm out of the two bones it was handed, and
        // picks them with a boolean the CALLER computed from the render state's main-arm enum. So
        // the branch inside the helper is a comparison against zero and the enum is two levels down
        // inside one of its operands - and the two arms of that branch leave two different BONES in
        // one place, which is the one shape a pose must never take. The answer is to split the
        // caller instead, once per constant, so each walk writes to an arm it has already been given.
        //
        // Pinned by the SIGN each arm carries, because that is exactly what a split resolved the
        // other way round would swap: vanilla leans the main arm out by three tenths and the off arm
        // back by six, so the right arm is negative on both counts and the left positive on both. A
        // piglin holding its crossbow on the wrong arm renders perfectly.
        PoseProgram piglin = extracted.get("net/minecraft/client/model/monster/piglin/AdultPiglinModel");
        assertNotNull(piglin, "AdultPiglinModel is expected to extract");

        Set<Double> right = constantsIn(piglin.bones().get("right_arm").get(PoseChannel.Y_ROT));
        Set<Double> left = constantsIn(piglin.bones().get("left_arm").get(PoseChannel.Y_ROT));

        assertTrue(right.containsAll(Set.of((double) -0.3f, (double) -0.6f)),
            "the right arm leans out three tenths as the main arm and back six as the off one");
        assertFalse(right.contains((double) 0.3f) || right.contains((double) 0.6f),
            "and never the other way, which is the whole of what a split resolved backwards gives it");
        assertTrue(left.containsAll(Set.of((double) 0.3f, (double) 0.6f)),
            "the left arm carries the same two offsets mirrored");
        assertFalse(left.contains((double) -0.3f) || left.contains((double) -0.6f),
            "and never the right arm's");

        List<PosePredicate.EnumEq> tests = new ArrayList<>();
        collectEnumTests(piglin.bones().get("right_arm").get(PoseChannel.Y_ROT), tests);
        assertTrue(tests.stream().anyMatch(test -> test.field().equals("mainArm")),
            "which arm leans which way is the render state's own question, kept rather than decided");
    }

    /** Every literal an expression reaches, walking the graph once rather than each of its paths. */
    private static @NotNull Set<Double> constantsIn(@NotNull PoseExpr expr) {
        List<PoseExpr> nodes = new ArrayList<>();
        collectNodes(expr, nodes, Collections.newSetFromMap(new IdentityHashMap<>()));
        return nodes.stream().filter(PoseExpr.Const.class::isInstance)
            .map(node -> ((PoseExpr.Const) node).value()).collect(Collectors.toSet());
    }

    @Test
    @DisplayName("a foal holds its head at the pitch it assigned itself, not the one it was handed")
    void anAssignmentToTheRenderStateIsReadBack() {
        // BabyDonkeyModel runs the donkey's own pose, then writes thirty degrees down to the render
        // state's pitch and reads it straight back. That is an assignment rather than a dependence,
        // and the difference is the whole foal: substituted, its head is pinned down by a constant
        // the model owns; left as an input it tilts with wherever the animal happens to be looking,
        // which renders perfectly and is a different animal.
        //
        // Vanilla adds the assigned angle to a literal, so the fold is what says it was substituted:
        // a walk that read it back has one number where a walk that did not has a term.
        PoseProgram foal = extracted.get("net/minecraft/client/model/animal/equine/BabyDonkeyModel");
        assertNotNull(foal, "BabyDonkeyModel is expected to extract");

        List<PoseExpr> reached = nodesOf(foal);
        assertTrue(reached.contains(PoseExpr.Op.of(PoseOperator.MUL,
                new PoseExpr.Input("standAnimation"),
                PoseExpr.Const.of(0.2617994f + -30.0f * 0.017453292f))),
            "the head's standing term carries the assigned pitch folded into its own literal");
        assertEquals(List.of(), reached.stream().filter(PoseExpr.Input.class::isInstance)
                .map(PoseExpr.Input.class::cast).map(PoseExpr.Input::field).filter("xRot"::equals).toList(),
            "nothing is left reading the pitch the render state carries");

        // The assignment belongs to the walk that made it. The donkey the foal calls up to is walked
        // on its own as well, and there the same field is an input like any other - which is what
        // says the substitution is a fact about one path rather than about the field.
        PoseProgram donkey = extracted.get("net/minecraft/client/model/animal/equine/DonkeyModel");
        assertNotNull(donkey, "DonkeyModel is expected to extract");
        assertTrue(nodesOf(donkey).contains(new PoseExpr.Input("xRot")),
            "a donkey of its own reads the pitch it is handed");
    }

    @Test
    @DisplayName("an allay spins about the bone its constructor re-rooted it at")
    void anInheritedRootResolvesToTheBoneTheConstructorNarrowedItTo() {
        // The allay hands super a getChild of its own root parameter, so the root every model
        // inherits IS one of this mesh's bones - and the pose writes through it. What has to survive
        // is that the bone was NAMED: a walk that resolved the inherited field to some other bone
        // would still write a well formed angle, and the allay would still spin, about the wrong
        // part of itself.
        //
        // The arm that does not spin is the half that proves it, because it reads the root's own
        // authored angle back. That read names a bone, so it is only right if the field resolved to
        // the same bone the write lands on.
        PoseProgram allay = extracted.get("net/minecraft/client/model/animal/allay/AllayModel");
        assertNotNull(allay, "AllayModel is expected to extract");

        assertTrue(nodesOf(allay).contains(new PoseExpr.Select(
                PosePredicate.Compare.of(PosePredicate.Comparison.EQ,
                    new PoseExpr.Input("isSpinning"), PoseExpr.Const.of(0)),
                new PoseExpr.BoneRead("root", PoseChannel.Y_ROT),
                PoseExpr.Op.of(PoseOperator.MUL,
                    PoseExpr.Const.of(12.566371f), new PoseExpr.Input("spinningProgress")))),
            "spinning turns the root two whole turns, and not spinning leaves it where it was authored");
    }

    @Test
    @DisplayName("a strider's bristles each carry the rate the lambda was applied at")
    void lambdaApplicationsKeepTheirOwnRate() {
        // Six applications of one lambda, through a functional interface no model descends from and
        // over a float that rides there in a box. What has to survive is that they are six: the
        // three bristles of a side are stirred at three rates and shaken at three more, and a walk
        // that lost the arguments would leave the six accumulating the same number - which draws a
        // strider whose bristles move together and looks entirely plausible.
        PoseProgram strider = extracted.get("net/minecraft/client/model/monster/strider/AdultStriderModel");
        assertNotNull(strider, "AdultStriderModel is expected to extract");

        List<String> bristles = List.of("right_top_bristle", "right_middle_bristle", "right_bottom_bristle",
            "left_top_bristle", "left_middle_bristle", "left_bottom_bristle");
        Set<PoseExpr> distinct = new LinkedHashSet<>();
        for (String bristle : bristles) distinct.add(strider.bones().get(bristle).get(PoseChannel.Z_ROT));
        assertEquals(bristles.size(), distinct.size(), "one lean per bristle, none of them shared");

        PoseExpr flow = PoseExpr.Op.of(PoseOperator.MUL,
            PoseExpr.Op.of(PoseOperator.MUL,
                PoseExpr.Op.of(PoseOperator.MTH_COS, PoseExpr.Op.of(PoseOperator.F2D,
                    PoseExpr.Op.of(PoseOperator.ADD,
                        PoseExpr.Op.of(PoseOperator.MUL,
                            new PoseExpr.Input("walkAnimationPos"), PoseExpr.Const.of(1.5f)),
                        PoseExpr.Const.of(3.1415927f)))),
                PoseExpr.Op.of(PoseOperator.MIN,
                    new PoseExpr.Input("walkAnimationSpeed"), PoseExpr.Const.of(0.25f))),
            PoseExpr.Const.of(0.6f));

        assertEquals(
            PoseExpr.Op.of(PoseOperator.ADD,
                PoseExpr.Op.of(PoseOperator.ADD, PoseExpr.Const.of(-0.87266463f), flow),
                PoseExpr.Op.of(PoseOperator.MUL, PoseExpr.Const.of(0.1f),
                    PoseExpr.Op.of(PoseOperator.MTH_SIN, PoseExpr.Op.of(PoseOperator.F2D,
                        PoseExpr.Op.of(PoseOperator.MUL,
                            new PoseExpr.Input("ageInTicks"), PoseExpr.Const.of(0.4f)))))),
            strider.bones().get("right_top_bristle").get(PoseChannel.Z_ROT),
            "the top bristle leans off its authored angle, stirred at its own rate and shaken at another");
    }

    /**
     * Every node a model's channels reach, following the graph once rather than each of its paths.
     *
     * <p>One walked set across the whole pose rather than one per channel, because a pose is a graph
     * and the sharing is the point: a humanoid's arms are nine hundred distinct nodes standing for
     * twenty-two million paths, and a set per channel would walk the paths.
     */
    private static @NotNull List<PoseExpr> nodesOf(@NotNull PoseProgram program) {
        List<PoseExpr> out = new ArrayList<>();
        Set<Object> walked = Collections.newSetFromMap(new IdentityHashMap<>());
        program.bones().values().forEach(channels -> channels.values()
            .forEach(expr -> collectNodes(expr, out, walked)));
        return out;
    }

    /** Every node an expression reaches, following the graph once rather than each of its paths. */
    private static void collectNodes(PoseExpr expr, List<PoseExpr> out, Set<Object> walked) {
        if (!walked.add(expr)) return;
        out.add(expr);
        switch (expr) {
            case PoseExpr.Op op -> op.operands().forEach(operand -> collectNodes(operand, out, walked));
            case PoseExpr.Select select -> {
                collectNodes(select.whenTrue(), out, walked);
                collectNodes(select.whenFalse(), out, walked);
                collectNodes(select.condition(), out, walked);
            }
            default -> { /* a leaf reaches nothing */ }
        }
    }

    private static void collectNodes(PosePredicate predicate, List<PoseExpr> out, Set<Object> walked) {
        if (!walked.add(predicate)) return;
        switch (predicate) {
            case PosePredicate.Not not -> collectNodes(not.operand(), out, walked);
            case PosePredicate.Compare compare -> {
                collectNodes(compare.left(), out, walked);
                collectNodes(compare.right(), out, walked);
            }
            default -> { /* a leaf reaches nothing */ }
        }
    }

    /** Every presence guard anywhere inside an expression. */
    private static void collectPresence(PoseExpr expr, Set<PosePredicate.Has> out) {
        switch (expr) {
            case PoseExpr.Op op -> op.operands().forEach(operand -> collectPresence(operand, out));
            case PoseExpr.Select select -> {
                collectPresence(select.whenTrue(), out);
                collectPresence(select.whenFalse(), out);
                collectPresence(select.condition(), out);
            }
            default -> { /* a leaf holds no guard */ }
        }
    }

    private static void collectPresence(PosePredicate predicate, Set<PosePredicate.Has> out) {
        switch (predicate) {
            case PosePredicate.Has present -> out.add(present);
            case PosePredicate.Not not -> collectPresence(not.operand(), out);
            case PosePredicate.Compare compare -> {
                collectPresence(compare.left(), out);
                collectPresence(compare.right(), out);
            }
            default -> { /* an enum test or a decided constant holds no guard */ }
        }
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
        assertEquals(107, extracted.size(), PoseWalkTest::refusalReport);
    }

    // ------------------------------------------------------------------------------------

    /**
     * What each remaining refusal costs, most models first - the work list this count stands for.
     *
     * <p>Built as a histogram rather than a set, because which shape to answer next is a question
     * about how many subjects it is holding up, and a deduplicated list of reasons cannot say.
     */
    private static @NotNull String refusalReport() {
        Map<String, Integer> byReason = new TreeMap<>();
        Map<String, String> firstSubject = new TreeMap<>();
        diagnostics.entries().forEach(entry -> {
            String message = entry.message();
            String reason = message.replaceAll("^\\S+ not extracted: ", "");
            byReason.merge(reason, 1, Integer::sum);
            firstSubject.putIfAbsent(reason, message.substring(0, Math.max(0, message.indexOf(' '))));
        });

        StringBuilder report = new StringBuilder("extracted ").append(extracted.size())
            .append(" of ").append(roster.size()).append("; refusals by weight:");
        byReason.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .forEach(entry -> report.append("\n  ").append(entry.getValue()).append("x ").append(entry.getKey())
                .append("  [e.g. ").append(firstSubject.get(entry.getKey())).append(']'));
        return report.toString();
    }

    /** What the shipped pose table says each model plays, keyed the way the walk names a model. */
    private static @NotNull Map<String, Set<String>> shippedBindings() {
        JsonElement root = GSON.fromJson(read(SHIPPED_POSES), JsonElement.class);
        Map<String, Set<String>> out = new TreeMap<>();
        root.getAsJsonObject().getAsJsonObject("models").entrySet().forEach(entry -> {
            Set<String> clips = new TreeSet<>();
            entry.getValue().getAsJsonArray().forEach(binding -> clips.add(
                binding.getAsJsonObject().get("clip").getAsString()
                    + "/" + binding.getAsJsonObject().get("gate").getAsString()));
            out.put(entry.getKey(), clips);
        });
        return out;
    }

    private static @NotNull List<String> rosterClasses() {
        return List.copyOf(new TreeSet<>(meshBones().keySet()));
    }

    /**
     * The bones each model's mesh names at TOP LEVEL, which is what the flattened container holds.
     *
     * <p>Read off the shipped table rather than off a fresh parse, the same source the roster and the
     * dangling-bone check are read from - so a walk and the mesh it is checked against cannot come
     * from two different readings of the corpus.
     */
    private static @NotNull Map<String, Set<String>> meshRootBones() {
        JsonElement root = GSON.fromJson(read(SHIPPED_GEOMETRY), JsonElement.class);
        Map<String, Set<String>> out = new TreeMap<>();
        root.getAsJsonObject().getAsJsonObject("geometries").entrySet().forEach(entry -> {
            var mesh = entry.getValue().getAsJsonObject();
            String owner = mesh.getAsJsonObject("source").get("class").getAsString();
            mesh.getAsJsonObject("bones").entrySet().forEach(bone -> {
                if (!bone.getValue().getAsJsonObject().has("parent"))
                    out.computeIfAbsent(owner, key -> new TreeSet<>()).add(bone.getKey());
            });
        });
        return out;
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
