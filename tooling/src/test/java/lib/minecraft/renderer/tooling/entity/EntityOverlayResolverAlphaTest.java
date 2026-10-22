package lib.minecraft.renderer.tooling.entity;

import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.content.client.ClientOptions;
import lib.minecraft.renderer.diagnostic.Diagnostics;
import lib.minecraft.renderer.tooling.animation.PoseJson;
import lib.minecraft.renderer.tooling.asm.ClassNodeCache;
import lib.minecraft.renderer.tooling.run.ToolingRun;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Which way a layer's alpha is drawn: as an opacity following the clock, or as one number frozen at
 * the first frame - against synthetic alpha lambdas, the warden's five among them in the shape
 * {@code javac} gives vanilla's.
 *
 * <p>An alpha is walked into an expression and folded against the fields the tick drives. One that
 * still reads such a field after the fold - the age, a clock - is the pass's opacity, kept whatever it
 * answers at the first frame, since a pass dark at age 0 lights later. Everything else is evaluated
 * once at the frozen frame as before: a constant, an alpha reading only fields nothing drives, and an
 * alpha the walk cannot read at all, which a branch is. So a constant under one stays the pass's
 * alpha and never becomes an opacity.
 */
@DisplayName("a layer's alpha folds to an opacity where it reads a driven input")
class EntityOverlayResolverAlphaTest {

    private static final @NotNull String RENDERER = "fx/WardenRenderer";
    private static final @NotNull String STATE = "fx/WardenRenderState";
    private static final @NotNull String MTH = "net/minecraft/util/Mth";
    private static final @NotNull String ALPHA_DESC = "(L" + STATE + ";F)F";

    /** The pulsating spots' opacity, as the warden's first spot layer computes it. */
    private static final @NotNull String SPOTS = "{\"max\": [{\"const\": 0.0}, {\"mul\": [{\"mth_cos\": [{\"f2d\": "
        + "[{\"mul\": [{\"input\": \"ageInTicks\"}, {\"const\": 0.045}]}]}]}, {\"const\": 0.25}]}]}";

    /** The second spot layer's, the same pulse turned half a cycle. */
    private static final @NotNull String SPOTS_TURNED = "{\"max\": [{\"const\": 0.0}, {\"mul\": [{\"mth_cos\": [{\"f2d\": "
        + "[{\"add\": [{\"mul\": [{\"input\": \"ageInTicks\"}, {\"const\": 0.045}]}, {\"const\": 3.1415927}]}]}]}, "
        + "{\"const\": 0.25}]}]}";

    @TempDir
    Path tempDir;

    private ClassNodeCache cache;

    @AfterEach
    void closeFixtureJar() {
        if (this.cache != null) this.cache.close();
    }

    @Test
    @DisplayName("an alpha over the age argument is an opacity")
    void anAlphaOverTheAgeIsAnOpacity() throws IOException {
        open(lambda("lambda$age", load(Opcodes.FLOAD, 1), ldc(0.5f), op(Opcodes.FMUL), op(Opcodes.FRETURN)));

        assertEquals(json("{\"mul\": [{\"input\": \"ageInTicks\"}, {\"const\": 0.5}]}"), opacityOf("lambda$age"),
            "the age argument reads as the age the frame is posed at");
    }

    @Test
    @DisplayName("an alpha over a field nothing drives folds to a constant and is frozen")
    void anAlphaOverARestingFieldIsFrozen() throws IOException {
        open(lambda("lambda$tendrils", load(Opcodes.ALOAD, 0), field("tendrilAnimation"), op(Opcodes.FRETURN)));

        assertEquals(new EntityOverlayResolver.Alpha.Frozen(0f), alphaOf("lambda$tendrils"),
            "the tendrils answer an event no style drives, so the pass rests dark and drops");
    }

    @Test
    @DisplayName("an alpha that branches is frozen")
    void aBranchingAlphaIsFrozen() throws IOException {
        LabelNode dark = new LabelNode();
        open(lambda("lambda$eyes", load(Opcodes.ALOAD, 0), flag("eyesGlowing"), new JumpInsnNode(Opcodes.IFEQ, dark),
            op(Opcodes.FCONST_1), op(Opcodes.FRETURN), dark, op(Opcodes.FCONST_0), op(Opcodes.FRETURN)));

        assertEquals(new EntityOverlayResolver.Alpha.Frozen(0f), alphaOf("lambda$eyes"),
            "the creaking's glow is a branch the walk does not read, so it keeps the frozen answer");
    }

    @Test
    @DisplayName("an alpha over a driven field is an opacity though it answers nothing at the first frame")
    void anAlphaDarkAtTheFirstFrameIsKept() throws IOException {
        open(lambda("lambda$heart", load(Opcodes.ALOAD, 0), field("heartAnimation"), op(Opcodes.FRETURN)));

        assertEquals(json("{\"input\": \"heartAnimation\"}"), opacityOf("lambda$heart"),
            "the heart is dark at age 0 and flashes later, so it ships rather than drops");
    }

    @Test
    @DisplayName("a constant under one is a frozen alpha, never an opacity")
    void aConstantUnderOneIsFrozen() throws IOException {
        open(lambda("lambda$half", ldc(0.5f), op(Opcodes.FRETURN)));

        assertEquals(new EntityOverlayResolver.Alpha.Frozen(0.5f), alphaOf("lambda$half"),
            "a constant ships as the pass's pipeline alpha");
    }

    @Test
    @DisplayName("the warden's five layers fold as vanilla computes them")
    void theWardensLayersFoldAsVanillaComputesThem() throws IOException {
        open(lambda("lambda$new$1", op(Opcodes.FCONST_1), op(Opcodes.FRETURN)),
            lambda("lambda$new$3", op(Opcodes.FCONST_0), load(Opcodes.FLOAD, 1), ldc(0.045f), op(Opcodes.FMUL),
                op(Opcodes.F2D), cos(), ldc(0.25f), op(Opcodes.FMUL), max(), op(Opcodes.FRETURN)),
            lambda("lambda$new$5", op(Opcodes.FCONST_0), load(Opcodes.FLOAD, 1), ldc(0.045f), op(Opcodes.FMUL),
                ldc(3.1415927f), op(Opcodes.FADD), op(Opcodes.F2D), cos(), ldc(0.25f), op(Opcodes.FMUL), max(),
                op(Opcodes.FRETURN)),
            lambda("lambda$new$7", load(Opcodes.ALOAD, 0), field("tendrilAnimation"), op(Opcodes.FRETURN)),
            lambda("lambda$new$9", load(Opcodes.ALOAD, 0), field("heartAnimation"), op(Opcodes.FRETURN)));

        assertEquals(new EntityOverlayResolver.Alpha.Frozen(1f), alphaOf("lambda$new$1"),
            "the bioluminescent layer draws at a constant one, which ships neither member");
        assertEquals(json(SPOTS), opacityOf("lambda$new$3"), "the first spots pulse on the age");
        assertEquals(json(SPOTS_TURNED), opacityOf("lambda$new$5"), "the second spots pulse half a cycle on");
        assertEquals(new EntityOverlayResolver.Alpha.Frozen(0f), alphaOf("lambda$new$7"), "the tendrils rest dark");
        assertEquals(json("{\"input\": \"heartAnimation\"}"), opacityOf("lambda$new$9"), "the heart follows its clock");
    }

    // ------------------------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------------------------

    private @NotNull EntityOverlayResolver.Alpha alphaOf(@NotNull String lambda) {
        Diagnostics diagnostics = Diagnostics.root("entityModels", Diagnostics.Output.NONE, null);
        ToolingRun run = new ToolingRun(ClientOptions.defaults(), this.cache, diagnostics);
        EntityIndexes indexes = new EntityIndexes(null, null, null, null, null, null, null, null);
        EntitySubject subject = new EntitySubject("minecraft:warden", "fx/Warden", RENDERER, List.of(), List.of(), List.of());
        EntityContext context = new EntityContext(run, indexes, subject, diagnostics);
        EntityOverlayResolver resolver = new EntityOverlayResolver(context, List.of(), new EntityGeometryRefResolver(context));
        return resolver.alphaOf(new Handle(Opcodes.H_INVOKESTATIC, RENDERER, lambda, ALPHA_DESC, false));
    }

    /** The opacity an alpha folds to, spelled as the table spells it. */
    private @NotNull String opacityOf(@NotNull String lambda) {
        EntityOverlayResolver.Alpha.Opacity opacity = assertInstanceOf(EntityOverlayResolver.Alpha.Opacity.class,
            alphaOf(lambda), lambda + " folds to an opacity");
        return PoseJson.inline(opacity.expression()).toJson();
    }

    /** One node spelled as the table's writer spells it. */
    private static @NotNull String json(@NotNull String text) {
        return JsonTree.parse(text.getBytes(StandardCharsets.UTF_8)).toJson();
    }

    private void open(@NotNull MethodNode @NotNull ... lambdas) throws IOException {
        ClassNode renderer = new ClassNode();
        renderer.version = Opcodes.V21;
        renderer.access = Opcodes.ACC_PUBLIC;
        renderer.name = RENDERER;
        renderer.superName = "java/lang/Object";
        renderer.methods.addAll(List.of(lambdas));
        Path jar = this.tempDir.resolve("fixtures.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            zip.putNextEntry(new ZipEntry(RENDERER + ".class"));
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            renderer.accept(writer);
            zip.write(writer.toByteArray());
            zip.closeEntry();
        }
        this.cache = ClassNodeCache.open(jar);
    }

    /** A static alpha lambda over the render state and the age, as vanilla's are compiled. */
    private static @NotNull MethodNode lambda(@NotNull String name, @NotNull AbstractInsnNode @NotNull ... body) {
        MethodNode method = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
            name, ALPHA_DESC, null, null);
        InsnList code = method.instructions;
        for (AbstractInsnNode node : body) code.add(node);
        return method;
    }

    private static @NotNull AbstractInsnNode load(int opcode, int slot) {
        return new VarInsnNode(opcode, slot);
    }

    private static @NotNull AbstractInsnNode op(int opcode) {
        return new InsnNode(opcode);
    }

    private static @NotNull AbstractInsnNode ldc(float value) {
        return new LdcInsnNode(value);
    }

    private static @NotNull AbstractInsnNode field(@NotNull String name) {
        return new FieldInsnNode(Opcodes.GETFIELD, STATE, name, "F");
    }

    private static @NotNull AbstractInsnNode flag(@NotNull String name) {
        return new FieldInsnNode(Opcodes.GETFIELD, STATE, name, "Z");
    }

    private static @NotNull AbstractInsnNode cos() {
        return new MethodInsnNode(Opcodes.INVOKESTATIC, MTH, "cos", "(D)F", false);
    }

    private static @NotNull AbstractInsnNode max() {
        return new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Math", "max", "(FF)F", false);
    }

}
