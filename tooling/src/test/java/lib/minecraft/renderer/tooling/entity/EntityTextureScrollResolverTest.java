package lib.minecraft.renderer.tooling.entity;

import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.tooling.kernel.ClassNodeCache;
import lib.minecraft.renderer.tooling.kernel.VanillaSourceClasses;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * What a layer's render type translates its texture by, against synthetic layers mirroring the 26.1
 * bytecode of each shape the corpus carries.
 *
 * <p>The rate is read out of the arithmetic rather than fitted from an evaluation, and the two
 * refusals are what says so: a layer whose offset is a cosine of the age evaluates to a number at
 * every tick and is not a scroll, and one multiplying something other than the age answers a
 * constant the walk must not mistake for one. Both are shapes vanilla has - the wither's armour
 * oscillates where the breeze's wind scrolls - so an evaluation-shaped reader would emit a rate for
 * a pass that does not have one and animate it wrongly at every tick but the first.
 */
@DisplayName("a layer's texture scroll is read as the rate it is written as")
class EntityTextureScrollResolverTest {

    private static final @NotNull String STATE =
        VanillaSourceClasses.Types.ENTITY_RENDER_STATE_PACKAGE + "TestRenderState";
    private static final @NotNull String POSE_STACK = "com/mojang/blaze3d/vertex/PoseStack";
    private static final @NotNull String COLLECTOR = "net/minecraft/client/renderer/SubmitNodeCollector";
    private static final @NotNull String MTH = "net/minecraft/util/Mth";
    private static final @NotNull String FACTORY_DESC =
        "(L" + VanillaSourceClasses.Types.IDENTIFIER + ";FF)L" + VanillaSourceClasses.Types.RENDER_TYPE + ";";
    private static final @NotNull String SUBMIT_DESC =
        "(L" + POSE_STACK + ";L" + COLLECTOR + ";IL" + STATE + ";FF)V";

    /** The slot the render state arrives in, which the descriptor above puts fourth. */
    private static final int STATE_SLOT = 4;

    @TempDir
    Path tempDir;

    private ClassNodeCache cache;

    @AfterEach
    void closeFixtureJar() {
        if (this.cache != null) this.cache.close();
    }

    @Test
    @DisplayName("a scroll written inline reads as its own rate on the axis it is written on")
    void inlineScrollReadsItsRate() throws IOException {
        open(layer("fx/WindLayer", inlineScroll(0.02f)));
        JsonTree scroll = resolve("fx/WindLayer");
        assertNotNull(scroll, "the layer scrolls");
        assertEquals(0.02f, scroll.getFloat("u", -1f), "along u, at the rate its own multiply carries");
        assertEquals(0f, scroll.getFloat("v", -1f), "and along v by nothing, the factory being handed a zero");
    }

    @Test
    @DisplayName("a scroll written through a helper reads the same as one written inline")
    void aHelperReadsTheSame() throws IOException {
        open(layer("fx/SwirlLayer", helperScroll(), helper(0.01f)));
        JsonTree scroll = resolve("fx/SwirlLayer");
        assertNotNull(scroll, "the layer scrolls");
        assertEquals(0.01f, scroll.getFloat("u", -1f), "the helper's own multiply is the rate");
        assertEquals(0.01f, scroll.getFloat("v", -1f), "and the inline one beside it is the other");
    }

    @Test
    @DisplayName("an offset that oscillates is refused, a cosine of the age being no rate at all")
    void anOscillationIsRefused() throws IOException {
        // The wither's armour: Mth.cos(age * 0.02) * 3. It evaluates to a number at every tick, so a
        // reader that measured rather than read would emit a rate and scroll a pass that swings.
        open(layer("fx/ArmorLayer", helperScroll(), oscillatingHelper()));
        assertNull(resolve("fx/ArmorLayer"), "an oscillating offset carries no rate");
    }

    @Test
    @DisplayName("a multiply of something other than the age is refused rather than read as a rate")
    void aConstantOffsetIsRefused() throws IOException {
        open(layer("fx/StaticLayer", constantScroll(0.02f)));
        assertNull(resolve("fx/StaticLayer"), "a constant offset is not a scroll");
    }

    @Test
    @DisplayName("a layer whose factory takes no offset carries no member")
    void aPlainLayerCarriesNothing() throws IOException {
        open(layer("fx/PlainLayer", noScroll()));
        assertNull(resolve("fx/PlainLayer"), "a layer that translates nothing scrolls nothing");
    }

    // ------------------------------------------------------------------------------------

    private @Nullable JsonTree resolve(@NotNull String layerClass) {
        return new EntityTextureScrollResolver(this.cache).resolve(layerClass);
    }

    private void open(@NotNull ClassNode fixture) throws IOException {
        Path jar = this.tempDir.resolve("fixtures.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            zip.putNextEntry(new ZipEntry(fixture.name + ".class"));
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            fixture.accept(writer);
            zip.write(writer.toByteArray());
            zip.closeEntry();
        }
        this.cache = ClassNodeCache.open(jar);
    }

    /** One fixture layer carrying a submit body and whatever helpers it calls. */
    private static @NotNull ClassNode layer(
        @NotNull String name, @NotNull InsnList submit, @NotNull MethodNode @NotNull ... helpers) {

        ClassNode node = new ClassNode();
        node.version = Opcodes.V21;
        node.access = Opcodes.ACC_PUBLIC;
        node.name = name;
        node.superName = "java/lang/Object";

        MethodNode body = new MethodNode(Opcodes.ACC_PUBLIC, "submit", SUBMIT_DESC, null, null);
        body.instructions = submit;
        body.instructions.add(new InsnNode(Opcodes.RETURN));
        node.methods.add(body);
        for (MethodNode helper : helpers) node.methods.add(helper);
        return node;
    }

    /** {@code RenderTypes.x(TEXTURE, (state.ageInTicks * rate) % 1f, 0f)}, the breeze's own. */
    private static @NotNull InsnList inlineScroll(float rate) {
        InsnList code = texture();
        age(code);
        code.add(new LdcInsnNode(rate));
        code.add(new InsnNode(Opcodes.FMUL));
        wrap(code);
        code.add(new InsnNode(Opcodes.FCONST_0));
        factory(code);
        return code;
    }

    /** {@code RenderTypes.x(TEXTURE, xOffset(age) % 1f, (age * 0.01f) % 1f)}, the swirl's own. */
    private static @NotNull InsnList helperScroll() {
        InsnList code = texture();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        age(code);
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "fx/Owner", "xOffset", "(F)F", false));
        wrap(code);
        age(code);
        code.add(new LdcInsnNode(0.01f));
        code.add(new InsnNode(Opcodes.FMUL));
        wrap(code);
        factory(code);
        return code;
    }

    /** {@code RenderTypes.x(TEXTURE, (0.5f * rate) % 1f, 0f)} - a number, not a scroll. */
    private static @NotNull InsnList constantScroll(float rate) {
        InsnList code = texture();
        code.add(new LdcInsnNode(0.5f));
        code.add(new LdcInsnNode(rate));
        code.add(new InsnNode(Opcodes.FMUL));
        wrap(code);
        code.add(new InsnNode(Opcodes.FCONST_0));
        factory(code);
        return code;
    }

    /** A factory handed no offset on either axis. */
    private static @NotNull InsnList noScroll() {
        InsnList code = texture();
        code.add(new InsnNode(Opcodes.FCONST_0));
        code.add(new InsnNode(Opcodes.FCONST_0));
        factory(code);
        return code;
    }

    /** {@code protected float xOffset(float f) { return f * rate; }}. */
    private static @NotNull MethodNode helper(float rate) {
        MethodNode method = new MethodNode(Opcodes.ACC_PROTECTED, "xOffset", "(F)F", null, null);
        method.instructions = new InsnList();
        method.instructions.add(new VarInsnNode(Opcodes.FLOAD, 1));
        method.instructions.add(new LdcInsnNode(rate));
        method.instructions.add(new InsnNode(Opcodes.FMUL));
        method.instructions.add(new InsnNode(Opcodes.FRETURN));
        return method;
    }

    /** {@code protected float xOffset(float f) { return Mth.cos(f * 0.02f) * 3f; }} - the wither's. */
    private static @NotNull MethodNode oscillatingHelper() {
        MethodNode method = new MethodNode(Opcodes.ACC_PROTECTED, "xOffset", "(F)F", null, null);
        method.instructions = new InsnList();
        method.instructions.add(new VarInsnNode(Opcodes.FLOAD, 1));
        method.instructions.add(new LdcInsnNode(0.02f));
        method.instructions.add(new InsnNode(Opcodes.FMUL));
        method.instructions.add(new InsnNode(Opcodes.F2D));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MTH, "cos", "(D)F", false));
        method.instructions.add(new LdcInsnNode(3f));
        method.instructions.add(new InsnNode(Opcodes.FMUL));
        method.instructions.add(new InsnNode(Opcodes.FRETURN));
        return method;
    }

    /** The texture argument, which is a static of the layer's own. */
    private static @NotNull InsnList texture() {
        InsnList code = new InsnList();
        code.add(new FieldInsnNode(Opcodes.GETSTATIC, "fx/Owner", "TEXTURE",
            "L" + VanillaSourceClasses.Types.IDENTIFIER + ";"));
        return code;
    }

    /** {@code state.ageInTicks}. */
    private static void age(@NotNull InsnList code) {
        code.add(new VarInsnNode(Opcodes.ALOAD, STATE_SLOT));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, STATE, "ageInTicks", "F"));
    }

    /** {@code % 1f}, the wrap the factory is handed. */
    private static void wrap(@NotNull InsnList code) {
        code.add(new InsnNode(Opcodes.FCONST_1));
        code.add(new InsnNode(Opcodes.FREM));
    }

    /** The factory the offsets are handed to. */
    private static void factory(@NotNull InsnList code) {
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
            VanillaSourceClasses.Types.RENDER_TYPES, "scrolled", FACTORY_DESC, false));
        code.add(new VarInsnNode(Opcodes.ASTORE, 7));
    }

}
