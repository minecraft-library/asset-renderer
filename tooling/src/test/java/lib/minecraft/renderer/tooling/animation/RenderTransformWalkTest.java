package lib.minecraft.renderer.tooling.animation;

import lib.minecraft.renderer.tooling.kernel.ClassNodeCache;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins for {@link RenderTransformWalk} against synthetic renderers mirroring the 26.1 bytecode of
 * each {@code setupRotations} shape the corpus carries, verified against {@code javap -c -p} of the
 * extracted client classes.
 *
 * <p>The accepted shapes are pinned as the exact bytes they emit, because what the walk
 * produces is a shipped table and every arm of the frame crossing is a sign the arithmetic cannot
 * tell right from wrong: hand-writing the cod's yaw un-negated renders a fish and renders it wrong.
 * So the negation of x and y, the untouched z, the degrees factor and the sixteen model pixels a
 * block is are all read out of the emitted expression rather than asserted about the walk.
 *
 * <p>The refusals matter as much as the reads. A body understood in half places a subject somewhere
 * vanilla never puts it, and every one of these is a shape the real corpus has: a translate that
 * does not commute with the base turn, a branch inside a branch, a call outside the grammar, a
 * negative axis, and a body that never runs the base at all.
 */
@DisplayName("RenderTransformWalk reads a setupRotations into container steps")
class RenderTransformWalkTest {

    private static final @NotNull String STATE =
        "net/minecraft/client/renderer/entity/state/LivingEntityRenderState";
    private static final @NotNull String POSE_STACK = "com/mojang/blaze3d/vertex/PoseStack";
    private static final @NotNull String AXIS = "com/mojang/math/Axis";
    private static final @NotNull String AXIS_DESC = "Lcom/mojang/math/Axis;";
    private static final @NotNull String MTH = "net/minecraft/util/Mth";
    private static final @NotNull String QUATERNION = "(F)Lorg/joml/Quaternionf;";
    private static final @NotNull String MUL_POSE = "(Lorg/joml/Quaternionfc;)V";
    private static final @NotNull String TRANSLATE = "(FFF)V";
    private static final @NotNull String SETUP = "(L" + STATE + ";L" + POSE_STACK + ";FF)V";
    private static final @NotNull String BASE = "net/minecraft/client/renderer/entity/MobRenderer";

    private static final @NotNull String DIRECTION = "net/minecraft/core/Direction";
    private static final @NotNull String DIRECTION_DESC = "L" + DIRECTION + ";";
    private static final @NotNull String OPPOSITE = "()" + DIRECTION_DESC;
    private static final @NotNull String ROTATION = "()Lorg/joml/Quaternionf;";
    private static final @NotNull String ROTATE_AROUND = "(Lorg/joml/Quaternionfc;FFF)V";

    /** The slot the render state arrives in, and the one the pose stack does. */
    private static final int STATE_SLOT = 1;
    private static final int STACK_SLOT = 2;

    /** A frame answering no resting constant at all, which every numeric fixture walks under. */
    private static final @NotNull BiFunction<String, String, Optional<String>> NO_CONSTANTS =
        (state, member) -> Optional.empty();

    /** The shulker's frame: {@code attachFace} rests {@code DOWN}, read of the state the body takes. */
    private static final @NotNull BiFunction<String, String, Optional<String>> ATTACHED_DOWN =
        (state, member) -> STATE.equals(state) && "attachFace".equals(member)
            ? Optional.of("DOWN") : Optional.empty();

    @TempDir
    Path tempDir;

    private ClassNodeCache cache;

    @BeforeEach
    void openFixtureJar() throws IOException {
        Path jar = this.tempDir.resolve("fixtures.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            for (ClassNode fixture : new ClassNode[]{
                fishRenderer(), pufferRenderer(), amplitudeRenderer(), tiltRenderer(),
                bareRenderer(), foreignRenderer(), negativeAxisRenderer(), nestedRenderer(),
                plainRenderer(), shulkerRenderer(), inputAddendRenderer(),
                unrecoverableAddendRenderer(), pivotTurnRenderer()}) {
                zip.putNextEntry(new ZipEntry(fixture.name + ".class"));
                ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                fixture.accept(writer);
                zip.write(writer.toByteArray());
                zip.closeEntry();
            }
        }
        this.cache = ClassNodeCache.open(jar);
    }

    @AfterEach
    void closeFixtureJar() {
        this.cache.close();
    }

    // ------------------------------------------------------------------------------------
    // the three shapes the corpus carries
    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName("a fish's yaw, out-of-water translate and roll are three steps, each crossed into the model frame")
    void fishShape() {
        // The steps the shipped cod row's container leads with, byte for byte. The yaw is NEGATED
        // and the roll is not, which is the whole of the frame crossing: diag(-1, -1, 1) is a half
        // turn about z, so it turns the x and y axes around and leaves z where it is. The un-negated
        // yaw renders a fish that swims the wrong way and costs 57 of delta against 0.17.
        assertEquals("{\"shared\":[{\"eq\":[{\"input\":\"isInWater\"},{\"iconst\":0}]},"
                + "{\"select\":[{\"ref\":0},{\"const\":-1.6},{\"const\":0.0}]}],"
                + "\"container\":[{\"y_rot\":{\"neg\":[{\"mul\":[{\"mul\":[{\"const\":4.3},"
                + "{\"mth_sin\":[{\"f2d\":[{\"mul\":[{\"const\":0.6},{\"input\":\"ageInTicks\"}]}]}]}]},"
                + "{\"const\":0.017453292}]}]}},"
                + "{\"x\":{\"ref\":1},\"y\":{\"ref\":1},\"z\":{\"ref\":1}},"
                + "{\"z_rot\":{\"select\":[{\"ref\":0},{\"const\":1.5707964},{\"const\":0.0}]}}],"
                + "\"bones\":{}}",
            emitted("fx/FishRenderer"));
    }

    @Test
    @DisplayName("a pufferfish's bob is one step along y, negated and taken into model pixels")
    void pufferShape() {
        // A PoseStack moves in blocks where a pivot is in model pixels, so the sixteen is a unit
        // crossing rather than a scale, and it rides the expression rather than being folded away.
        assertEquals("{\"container\":[{\"y\":{\"neg\":[{\"mul\":[{\"mul\":[{\"mth_cos\":[{\"f2d\":"
                + "[{\"mul\":[{\"input\":\"ageInTicks\"},{\"const\":0.05}]}]}]},{\"const\":0.08}]},"
                + "{\"const\":16.0}]}]}}],\"bones\":{}}",
            emitted("fx/PufferRenderer"));
    }

    @Test
    @DisplayName("a salmon's conditional amplitudes become a choice per local, not the branch's own arm")
    void amplitudeShape() {
        // The walk runs the block unconditionally, so a local the block assigns has to be reconciled
        // against what it held before or the amplitude comes out as the out-of-water one on every
        // frame. Both figures resolve to their in-water arm here, which is what a fish rests at.
        assertEquals("{\"shared\":[{\"eq\":[{\"input\":\"isInWater\"},{\"iconst\":0}]}],"
                + "\"container\":[{\"y_rot\":{\"neg\":[{\"mul\":[{\"mul\":[{\"mul\":["
                + "{\"select\":[{\"ref\":0},{\"const\":1.3},{\"const\":1.0}]},{\"const\":4.3}]},"
                + "{\"mth_sin\":[{\"f2d\":[{\"mul\":[{\"mul\":["
                + "{\"select\":[{\"ref\":0},{\"const\":1.7},{\"const\":1.0}]},{\"const\":0.6}]},"
                + "{\"input\":\"ageInTicks\"}]}]}]}]},{\"const\":0.017453292}]}]}}],\"bones\":{}}",
            emitted("fx/AmplitudeRenderer"));
    }

    // ------------------------------------------------------------------------------------
    // the refusals
    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName("a shulker's addend is a facing rather than a step, and its attach turn folds away")
    void shulkerShape() {
        // The 180 rides the delegation's own body rotation, which the base applies as the subject's
        // FACING - so it leaves here in the degrees vanilla wrote and goes into the mesh, and this
        // renderer composes no step at all. The rotateAround settles at generation: attachFace rests
        // DOWN, DOWN's opposite is UP, and UP's rotation is the identity, which turns nothing
        // whatever the pivot.
        RenderTransform read = RenderTransformWalk.read(this.cache, "fx/ShulkerRenderer", ATTACHED_DOWN);
        assertNotNull(read, "the shulker composes a facing");
        assertTrue(read.isReadable(), "and reads whole");
        assertEquals(180f, read.facingYaw(), "the facing, in the degrees it was written in");
        assertEquals(List.of(), read.steps(), "and nothing is left to compose above the mesh");
        // It seats nothing in any container: the facing is in the geometry by then.
        assertEquals("{\"bones\":{}}", emitted("fx/ShulkerRenderer", ATTACHED_DOWN));
    }

    @Test
    @DisplayName("a direction no constant answers refuses rather than guessing an attach face")
    void unresolvedDirectionRefuses() {
        assertRefused("fx/ShulkerRenderer", NO_CONSTANTS,
            "reads 'attachFace', which rests at no constant this can answer");
    }

    @Test
    @DisplayName("a resolved rotation that is not the identity refuses rather than spelling a turn nothing measures")
    void nonIdentityRotationRefuses() {
        BiFunction<String, String, Optional<String>> attachedUp =
            (state, member) -> Optional.of("UP");
        assertRefused("fx/ShulkerRenderer", attachedUp,
            "turns about the 'DOWN' rotation, which does not rest at the identity");
    }

    @Test
    @DisplayName("a body rotation carrying anything but a literal addend refuses the body")
    void inputAddendRefuses() {
        assertRefused("fx/InputAddendRenderer", "delegates a body rotation it could not read");
    }

    @Test
    @DisplayName("an addend no round trip touches is carried in the degrees it was written in")
    void awkwardAddendSurvives() {
        // 0.031f used to refuse: the walk crossed it into radians and the reader divided it back
        // out, and that round trip does not invert for every float. Nothing crosses it now - the
        // degrees go into the mesh - so the value that could not survive the trip is simply carried.
        RenderTransform read =
            RenderTransformWalk.read(this.cache, "fx/UnrecoverableAddendRenderer", NO_CONSTANTS);
        assertNotNull(read, "the fixture composes a facing");
        assertTrue(read.isReadable(), "and no longer refuses, there being no division to fail");
        assertEquals(0.031f, read.facingYaw(), "carried verbatim, in degrees");
    }

    @Test
    @DisplayName("a pivoted turn by anything but a settled direction refuses")
    void pivotTurnRefuses() {
        assertRefused("fx/PivotTurnRenderer", "turns about a pivot by a rotation it could not read");
    }

    @Test
    @DisplayName("a translate off the y axis before the base turn refuses rather than being placed after it")
    void offAxisBeforeTheBaseRefuses() {
        // A step before the delegation is composed OUTSIDE the base's turn about y, and only a
        // translate along y or a turn about y survives being moved across it. The pufferfish's bob
        // is the one that does, and this is the same body with the bob moved onto x.
        assertRefused("fx/TiltRenderer",
            "moves 'x' before the base rotation, which does not commute with it");
    }

    @Test
    @DisplayName("a body that never runs the base rotation refuses, composing around a base this cannot name")
    void noDelegationRefuses() {
        assertRefused("fx/BareRenderer", "never runs the base rotation");
    }

    @Test
    @DisplayName("a call outside the grammar refuses, naming what it met")
    void foreignCallRefuses() {
        assertRefused("fx/ForeignRenderer", "calls 'Mth.abs'");
    }

    @Test
    @DisplayName("a negative axis refuses, no subject in the corpus turning about one")
    void negativeAxisRefuses() {
        assertRefused("fx/NegativeAxisRenderer", "turns about 'Axis.XN'");
    }

    @Test
    @DisplayName("a branch inside a branch refuses, the block shape being a contiguous run and nothing else")
    void nestedBranchRefuses() {
        assertRefused("fx/NestedRenderer", "branches inside a branch");
    }

    @Test
    @DisplayName("a renderer that composes nothing beyond the base gets no row at all")
    void plainRendererAnswersNothing() {
        assertNull(RenderTransformWalk.read(this.cache, "fx/PlainRenderer", NO_CONSTANTS),
            "a body that only delegates composes no transform");
    }

    @Test
    @DisplayName("a renderer declaring no setupRotations answers nothing rather than an empty transform")
    void undeclaredAnswersNothing() {
        assertNull(RenderTransformWalk.read(this.cache, "fx/Missing", NO_CONSTANTS),
            "a class the jar does not hold declares nothing to walk");
    }

    // ------------------------------------------------------------------------------------

    /** The shipped bytes one fixture's transform writes, walked under no resting constants. */
    private @NotNull String emitted(@NotNull String renderer) {
        return emitted(renderer, NO_CONSTANTS);
    }

    /**
     * The shipped bytes one fixture's transform writes - the steps seated in a pose row's container,
     * which is where a composed sequence ships.
     */
    private @NotNull String emitted(
        @NotNull String renderer, @NotNull BiFunction<String, String, Optional<String>> resting) {

        RenderTransform read = RenderTransformWalk.read(this.cache, renderer, resting);
        assertNotNull(read, renderer + " composed no transform");
        assertTrue(read.isReadable(), renderer + " refused: " + read.refusal().orElse(""));
        return PoseJson.of(new PoseOutcome.Extracted(
            new PoseProgram(renderer, read.steps(), Map.of(), List.of()))).toGson().toString();
    }

    /** Asserts one fixture refuses under no resting constants, and refuses for the stated reason. */
    private void assertRefused(@NotNull String renderer, @NotNull String reason) {
        assertRefused(renderer, NO_CONSTANTS, reason);
    }

    /** Asserts one fixture refuses, and refuses for the stated reason. */
    private void assertRefused(
        @NotNull String renderer, @NotNull BiFunction<String, String, Optional<String>> resting,
        @NotNull String reason) {

        RenderTransform read = RenderTransformWalk.read(this.cache, renderer, resting);
        assertNotNull(read, renderer + " answered nothing where a refusal is owed");
        assertEquals(reason, read.refusal().orElse("<read>"), renderer + " refusal");
    }

    // ------------------------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------------------------

    /** The cod's own body: base turn, per-frame yaw, then an out-of-water translate and roll. */
    private static @NotNull ClassNode fishRenderer() {
        MethodNode body = setupRotations();
        InsnList code = body.instructions;
        delegate(code);
        // float f = 4.3f * Mth.sin(0.6f * state.ageInTicks);
        code.add(new LdcInsnNode(4.3f));
        code.add(new LdcInsnNode(0.6f));
        input(code, "ageInTicks", "F");
        code.add(new InsnNode(Opcodes.FMUL));
        code.add(new InsnNode(Opcodes.F2D));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MTH, "sin", "(D)F", false));
        code.add(new InsnNode(Opcodes.FMUL));
        code.add(new VarInsnNode(Opcodes.FSTORE, 5));
        turn(code, "YP", load -> load.add(new VarInsnNode(Opcodes.FLOAD, 5)));

        LabelNode end = new LabelNode();
        input(code, "isInWater", "Z");
        code.add(new JumpInsnNode(Opcodes.IFNE, end));
        translate(code, 0.1f, 0.1f, -0.1f);
        turn(code, "ZP", load -> load.add(new LdcInsnNode(90f)));
        code.add(end);
        code.add(new InsnNode(Opcodes.RETURN));
        return renderer("fx/FishRenderer", body);
    }

    /** The pufferfish's own body: a bob along y, then the base turn. */
    private static @NotNull ClassNode pufferRenderer() {
        MethodNode body = setupRotations();
        InsnList code = body.instructions;
        code.add(new VarInsnNode(Opcodes.ALOAD, STACK_SLOT));
        code.add(new InsnNode(Opcodes.FCONST_0));
        input(code, "ageInTicks", "F");
        code.add(new LdcInsnNode(0.05f));
        code.add(new InsnNode(Opcodes.FMUL));
        code.add(new InsnNode(Opcodes.F2D));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MTH, "cos", "(D)F", false));
        code.add(new LdcInsnNode(0.08f));
        code.add(new InsnNode(Opcodes.FMUL));
        code.add(new InsnNode(Opcodes.FCONST_0));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, POSE_STACK, "translate", TRANSLATE, false));
        delegate(code);
        code.add(new InsnNode(Opcodes.RETURN));
        return renderer("fx/PufferRenderer", body);
    }

    /** The salmon's own body: two amplitudes a branch reassigns, then one yaw built from both. */
    private static @NotNull ClassNode amplitudeRenderer() {
        MethodNode body = setupRotations();
        InsnList code = body.instructions;
        delegate(code);
        code.add(new InsnNode(Opcodes.FCONST_1));
        code.add(new VarInsnNode(Opcodes.FSTORE, 5));
        code.add(new InsnNode(Opcodes.FCONST_1));
        code.add(new VarInsnNode(Opcodes.FSTORE, 6));

        LabelNode end = new LabelNode();
        input(code, "isInWater", "Z");
        code.add(new JumpInsnNode(Opcodes.IFNE, end));
        code.add(new LdcInsnNode(1.3f));
        code.add(new VarInsnNode(Opcodes.FSTORE, 5));
        code.add(new LdcInsnNode(1.7f));
        code.add(new VarInsnNode(Opcodes.FSTORE, 6));
        code.add(end);

        code.add(new VarInsnNode(Opcodes.FLOAD, 5));
        code.add(new LdcInsnNode(4.3f));
        code.add(new InsnNode(Opcodes.FMUL));
        code.add(new VarInsnNode(Opcodes.FLOAD, 6));
        code.add(new LdcInsnNode(0.6f));
        code.add(new InsnNode(Opcodes.FMUL));
        input(code, "ageInTicks", "F");
        code.add(new InsnNode(Opcodes.FMUL));
        code.add(new InsnNode(Opcodes.F2D));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MTH, "sin", "(D)F", false));
        code.add(new InsnNode(Opcodes.FMUL));
        code.add(new VarInsnNode(Opcodes.FSTORE, 7));
        turn(code, "YP", load -> load.add(new VarInsnNode(Opcodes.FLOAD, 7)));
        code.add(new InsnNode(Opcodes.RETURN));
        return renderer("fx/AmplitudeRenderer", body);
    }

    /** The pufferfish's body with its bob moved onto x, which no longer commutes with the base turn. */
    private static @NotNull ClassNode tiltRenderer() {
        MethodNode body = setupRotations();
        InsnList code = body.instructions;
        translate(code, 0.1f, 0f, 0f);
        delegate(code);
        code.add(new InsnNode(Opcodes.RETURN));
        return renderer("fx/TiltRenderer", body);
    }

    /** A body that turns without ever running the base, so it composes around something else. */
    private static @NotNull ClassNode bareRenderer() {
        MethodNode body = setupRotations();
        InsnList code = body.instructions;
        turn(code, "YP", load -> load.add(new LdcInsnNode(90f)));
        code.add(new InsnNode(Opcodes.RETURN));
        return renderer("fx/BareRenderer", body);
    }

    /** A body reaching one method past the grammar, which is a whole-body refusal. */
    private static @NotNull ClassNode foreignRenderer() {
        MethodNode body = setupRotations();
        InsnList code = body.instructions;
        delegate(code);
        turn(code, "YP", load -> {
            input(load, "ageInTicks", "F");
            load.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MTH, "abs", "(F)F", false));
        });
        code.add(new InsnNode(Opcodes.RETURN));
        return renderer("fx/ForeignRenderer", body);
    }

    /** A body turning about a negative axis, which nothing in the corpus does. */
    private static @NotNull ClassNode negativeAxisRenderer() {
        MethodNode body = setupRotations();
        InsnList code = body.instructions;
        delegate(code);
        turn(code, "XN", load -> load.add(new LdcInsnNode(90f)));
        code.add(new InsnNode(Opcodes.RETURN));
        return renderer("fx/NegativeAxisRenderer", body);
    }

    /** A cat's own shape: a branch opened while another is still open. */
    private static @NotNull ClassNode nestedRenderer() {
        MethodNode body = setupRotations();
        InsnList code = body.instructions;
        delegate(code);
        LabelNode outer = new LabelNode();
        LabelNode inner = new LabelNode();
        input(code, "isInWater", "Z");
        code.add(new JumpInsnNode(Opcodes.IFNE, outer));
        translate(code, 0f, 0.1f, 0f);
        input(code, "isFullyFrozen", "Z");
        code.add(new JumpInsnNode(Opcodes.IFEQ, inner));
        translate(code, 0f, 0.2f, 0f);
        code.add(inner);
        code.add(outer);
        code.add(new InsnNode(Opcodes.RETURN));
        return renderer("fx/NestedRenderer", body);
    }

    /** A body that only delegates, which is a renderer with no transform rather than an empty one. */
    private static @NotNull ClassNode plainRenderer() {
        MethodNode body = setupRotations();
        delegate(body.instructions);
        body.instructions.add(new InsnNode(Opcodes.RETURN));
        return renderer("fx/PlainRenderer", body);
    }

    /** The shulker's own body: the 180 addend on the delegation, then the attach-face pivot turn. */
    private static @NotNull ClassNode shulkerRenderer() {
        MethodNode body = setupRotations();
        InsnList code = body.instructions;
        delegate(code, 180f);
        code.add(new VarInsnNode(Opcodes.ALOAD, STACK_SLOT));
        input(code, "attachFace", DIRECTION_DESC);
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, DIRECTION, "getOpposite", OPPOSITE, false));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, DIRECTION, "getRotation", ROTATION, false));
        code.add(new InsnNode(Opcodes.FCONST_0));
        code.add(new LdcInsnNode(0.5f));
        code.add(new InsnNode(Opcodes.FCONST_0));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, POSE_STACK, "rotateAround", ROTATE_AROUND, false));
        code.add(new InsnNode(Opcodes.RETURN));
        return renderer("fx/ShulkerRenderer", body);
    }

    /** A delegation folding a render-state figure into the body rotation, which no literal covers. */
    private static @NotNull ClassNode inputAddendRenderer() {
        MethodNode body = setupRotations();
        InsnList code = body.instructions;
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, STATE_SLOT));
        code.add(new VarInsnNode(Opcodes.ALOAD, STACK_SLOT));
        code.add(new VarInsnNode(Opcodes.FLOAD, 3));
        input(code, "ageInTicks", "F");
        code.add(new InsnNode(Opcodes.FADD));
        code.add(new VarInsnNode(Opcodes.FLOAD, 4));
        code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, BASE, "setupRotations", SETUP, false));
        code.add(new InsnNode(Opcodes.RETURN));
        return renderer("fx/InputAddendRenderer", body);
    }

    /** The shulker's shape with an addend whose degrees the reader's division moves by an ulp. */
    private static @NotNull ClassNode unrecoverableAddendRenderer() {
        MethodNode body = setupRotations();
        delegate(body.instructions, 0.031f);
        body.instructions.add(new InsnNode(Opcodes.RETURN));
        return renderer("fx/UnrecoverableAddendRenderer", body);
    }

    /** The drowned's shape: a pivoted turn by a built quaternion rather than a settled direction. */
    private static @NotNull ClassNode pivotTurnRenderer() {
        MethodNode body = setupRotations();
        InsnList code = body.instructions;
        delegate(code);
        code.add(new VarInsnNode(Opcodes.ALOAD, STACK_SLOT));
        code.add(new FieldInsnNode(Opcodes.GETSTATIC, AXIS, "XP", AXIS_DESC));
        code.add(new LdcInsnNode(10f));
        code.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, AXIS, "rotationDegrees", QUATERNION, true));
        code.add(new InsnNode(Opcodes.FCONST_0));
        code.add(new LdcInsnNode(0.9f));
        code.add(new InsnNode(Opcodes.FCONST_0));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, POSE_STACK, "rotateAround", ROTATE_AROUND, false));
        code.add(new InsnNode(Opcodes.RETURN));
        return renderer("fx/PivotTurnRenderer", body);
    }

    // ------------------------------------------------------------------------------------

    /** {@code super.setupRotations(state, poseStack, bodyRot, scale)}. */
    private static void delegate(@NotNull InsnList code) {
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, STATE_SLOT));
        code.add(new VarInsnNode(Opcodes.ALOAD, STACK_SLOT));
        code.add(new VarInsnNode(Opcodes.FLOAD, 3));
        code.add(new VarInsnNode(Opcodes.FLOAD, 4));
        code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, BASE, "setupRotations", SETUP, false));
    }

    /** {@code super.setupRotations(state, poseStack, bodyRot + addend, scale)}. */
    private static void delegate(@NotNull InsnList code, float addend) {
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, STATE_SLOT));
        code.add(new VarInsnNode(Opcodes.ALOAD, STACK_SLOT));
        code.add(new VarInsnNode(Opcodes.FLOAD, 3));
        code.add(new LdcInsnNode(addend));
        code.add(new InsnNode(Opcodes.FADD));
        code.add(new VarInsnNode(Opcodes.FLOAD, 4));
        code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, BASE, "setupRotations", SETUP, false));
    }

    /** {@code state.<field>}. */
    private static void input(@NotNull InsnList code, @NotNull String field, @NotNull String desc) {
        code.add(new VarInsnNode(Opcodes.ALOAD, STATE_SLOT));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, STATE, field, desc));
    }

    /** {@code poseStack.translate(x, y, z)}. */
    private static void translate(@NotNull InsnList code, float x, float y, float z) {
        code.add(new VarInsnNode(Opcodes.ALOAD, STACK_SLOT));
        code.add(new LdcInsnNode(x));
        code.add(new LdcInsnNode(y));
        code.add(new LdcInsnNode(z));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, POSE_STACK, "translate", TRANSLATE, false));
    }

    /** {@code poseStack.mulPose(Axis.<axis>.rotationDegrees(<angle>))}. */
    private static void turn(
        @NotNull InsnList code, @NotNull String axis, @NotNull java.util.function.Consumer<InsnList> angle) {

        code.add(new VarInsnNode(Opcodes.ALOAD, STACK_SLOT));
        code.add(new FieldInsnNode(Opcodes.GETSTATIC, AXIS, axis, AXIS_DESC));
        angle.accept(code);
        code.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, AXIS, "rotationDegrees", QUATERNION, true));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, POSE_STACK, "mulPose", MUL_POSE, false));
    }

    /** An empty {@code setupRotations} taking the base render state, the shape every override has. */
    private static @NotNull MethodNode setupRotations() {
        MethodNode method = new MethodNode(Opcodes.ACC_PROTECTED, "setupRotations", SETUP, null, null);
        method.instructions = new InsnList();
        return method;
    }

    /** One fixture renderer carrying the body. */
    private static @NotNull ClassNode renderer(@NotNull String name, @NotNull MethodNode body) {
        ClassNode node = new ClassNode();
        node.version = Opcodes.V21;
        node.access = Opcodes.ACC_PUBLIC;
        node.name = name;
        node.superName = BASE;
        node.methods.add(body);
        return node;
    }

}
