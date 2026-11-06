package lib.minecraft.renderer.tooling.blockentity;

import lib.minecraft.renderer.content.client.ClientOptions;
import lib.minecraft.renderer.diagnostic.Diagnostics;
import lib.minecraft.renderer.tooling.asm.ClassKit;
import lib.minecraft.renderer.tooling.asm.ClassNodeCache;
import lib.minecraft.renderer.tooling.exception.ToolingException;
import lib.minecraft.renderer.tooling.policy.AsmContext;
import lib.minecraft.renderer.tooling.policy.Navigation;
import lib.minecraft.renderer.tooling.policy.TraceReplay;
import lib.minecraft.renderer.tooling.run.ToolingRun;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the PLAYER skull skin row, declared as a coordinate plus a trace and recovered by replaying it
 * over the default-skin class's own bytecode. The fixture mirrors the 26.1 shape rather than a
 * convenient one: an eighteen-element array whose length is pushed before the allocation, {@code
 * bipush} ordinals past the {@code ICONST} range, and element expressions that carry a model-type
 * read and a factory call after the stem - each a place a looser walk would take the wrong literal.
 * <p>
 * The stems are vanilla's own, in vanilla's order, so the row is pinned at the answer the shipped
 * table carries. The pair that matters is one accessor loading the default ordinal and one loading
 * another: the same trace answers a different stem, which is what the arm buys over a frozen string.
 * <p>
 * It also pins the binding the default-skin factory builds, which is what makes the PLAYER skull's
 * texture a file.
 */
@DisplayName("the PLAYER skull skin is replayed off the default-skin array, not declared")
class PlayerSkullSkinTraceTest {

    private static final @NotNull String DEFAULT_PLAYER_SKIN = "net/minecraft/client/resources/DefaultPlayerSkin";
    private static final @NotNull String PLAYER_SKIN = "net/minecraft/world/entity/player/PlayerSkin";
    private static final @NotNull String MODEL_TYPE = "net/minecraft/world/entity/player/PlayerModelType";
    private static final @NotNull String SKINS_FIELD = "DEFAULT_SKINS";
    private static final @NotNull String SKINS_DESC = "[L" + PLAYER_SKIN + ";";
    private static final @NotNull String ACCESSOR_DESC = "()L" + PLAYER_SKIN + ";";
    private static final @NotNull String CREATE_DESC = "(Ljava/lang/String;L" + MODEL_TYPE + ";)L" + PLAYER_SKIN + ";";
    private static final @NotNull String RESOURCE_TEXTURE = "net/minecraft/core/ClientAsset$ResourceTexture";
    private static final @NotNull String IDENTIFIER = "net/minecraft/resources/Identifier";

    /** The ordinal vanilla's accessor loads. */
    private static final int DEFAULT_ORDINAL = 6;

    /** The default-skin stems, in the order the static initialiser fills the array. */
    private static final @NotNull List<String> STEMS = List.of(
        "entity/player/slim/alex", "entity/player/slim/ari", "entity/player/slim/efe",
        "entity/player/slim/kai", "entity/player/slim/makena", "entity/player/slim/noor",
        "entity/player/slim/steve", "entity/player/slim/sunny", "entity/player/slim/zuri",
        "entity/player/wide/alex", "entity/player/wide/ari", "entity/player/wide/efe",
        "entity/player/wide/kai", "entity/player/wide/makena", "entity/player/wide/noor",
        "entity/player/wide/steve", "entity/player/wide/sunny", "entity/player/wide/zuri");

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("the declared trace recovers the stem the accessor's ordinal binds")
    void traceRecoversTheDefaultStem() throws IOException {
        try (ClassNodeCache cache = open("default", DEFAULT_ORDINAL, true)) {
            assertEquals("entity/player/slim/steve", new TraceReplay(cache).replay(coordinate(cache)));
        }
    }

    @Test
    @DisplayName("an accessor loading another ordinal recovers that ordinal's stem - the answer is derived")
    void traceFollowsTheAccessorOrdinal() throws IOException {
        try (ClassNodeCache cache = open("moved", 15, true)) {
            assertEquals("entity/player/wide/steve", new TraceReplay(cache).replay(coordinate(cache)));
        }
    }

    @Test
    @DisplayName("the array's own length is pushed before the allocation and never read as an ordinal")
    void arrayLengthIsNotAnOrdinal() throws IOException {
        // the length is 18 and no element binds ordinal 18, so a walk taking it would find nothing
        try (ClassNodeCache cache = open("length", STEMS.size(), true)) {
            assertThrows(ToolingException.class, () -> new TraceReplay(cache).replay(coordinate(cache)));
        }
    }

    @Test
    @DisplayName("the default-skin factory builds a resource texture over the stem, so a default skin is a texture file")
    void theDefaultSkinIsATextureFile() throws IOException {
        try (ClassNodeCache cache = open("file", DEFAULT_ORDINAL, true)) {
            assertTrue(BlockCatalogResolver.defaultSkinBindsAFile(cache));
        }
    }

    @Test
    @DisplayName("a default-skin factory building no resource texture binds no file")
    void aDefaultSkinBuiltFromNoTextureAssetIsNoFile() throws IOException {
        try (ClassNodeCache cache = open("other", DEFAULT_ORDINAL, false)) {
            assertFalse(BlockCatalogResolver.defaultSkinBindsAFile(cache));
        }
    }

    // ------------------------------------------------------------------------------------
    // fixture plumbing
    // ------------------------------------------------------------------------------------

    /** The declared row, consulted on a subject-keyed frame exactly as the resolver consults it. */
    private static Navigation.@NotNull Dataflow coordinate(@NotNull ClassNodeCache cache) {
        ToolingRun run = new ToolingRun(ClientOptions.defaults(), cache,
            Diagnostics.root("blockentity", Diagnostics.Output.NONE, null));
        AsmContext frame = new AsmContext(run, "minecraft:skull", null, run.diagnostics());
        return assertInstanceOf(Navigation.Dataflow.class, BlockFamilyPolicies.PLAYER_SKULL_SKIN.navigate(frame));
    }

    private @NotNull ClassNodeCache open(@NotNull String name, int ordinal, boolean resourceTexture) throws IOException {
        Path jar = this.tempDir.resolve(name + ".jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            ClassNode fixture = defaultPlayerSkin(ordinal, resourceTexture);
            zip.putNextEntry(new ZipEntry(fixture.name + ".class"));
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            fixture.accept(writer);
            zip.write(writer.toByteArray());
            zip.closeEntry();
        }
        return ClassNodeCache.open(jar);
    }

    /**
     * Builds the default-skin class: the filled array, the accessor loading one ordinal out of it, and
     * the factory building a skin out of a stem.
     *
     * @param ordinal the ordinal the accessor loads
     * @param resourceTexture whether the factory builds a resource texture over the stem, as 26.1's does,
     *     or a bare {@code Object} in its place
     * @return the class
     */
    private static @NotNull ClassNode defaultPlayerSkin(int ordinal, boolean resourceTexture) {
        ClassNode cn = new ClassNode();
        cn.version = Opcodes.V21;
        cn.access = Opcodes.ACC_PUBLIC;
        cn.name = DEFAULT_PLAYER_SKIN;
        cn.superName = "java/lang/Object";
        cn.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
            SKINS_FIELD, SKINS_DESC, null, null));

        MethodNode initialiser = new MethodNode(Opcodes.ACC_STATIC, ClassKit.CLINIT, "()V", null, null);
        cn.methods.add(initialiser);
        InsnList clinit = initialiser.instructions;
        pushInt(clinit, STEMS.size());
        clinit.add(new TypeInsnNode(Opcodes.ANEWARRAY, PLAYER_SKIN));
        for (int index = 0; index < STEMS.size(); index++) {
            clinit.add(new InsnNode(Opcodes.DUP));
            pushInt(clinit, index);
            clinit.add(new LdcInsnNode(STEMS.get(index)));
            clinit.add(new FieldInsnNode(Opcodes.GETSTATIC, MODEL_TYPE, "SLIM", "L" + MODEL_TYPE + ";"));
            clinit.add(new MethodInsnNode(Opcodes.INVOKESTATIC, PLAYER_SKIN, "create", CREATE_DESC));
            clinit.add(new InsnNode(Opcodes.AASTORE));
        }
        clinit.add(new FieldInsnNode(Opcodes.PUTSTATIC, cn.name, SKINS_FIELD, SKINS_DESC));
        clinit.add(new InsnNode(Opcodes.RETURN));

        MethodNode accessor = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "getDefaultSkin", ACCESSOR_DESC, null, null);
        cn.methods.add(accessor);
        InsnList body = accessor.instructions;
        body.add(new FieldInsnNode(Opcodes.GETSTATIC, cn.name, SKINS_FIELD, SKINS_DESC));
        pushInt(body, ordinal);
        body.add(new InsnNode(Opcodes.AALOAD));
        body.add(new InsnNode(Opcodes.ARETURN));

        // 26.1's opening: new ClientAsset$ResourceTexture over Identifier.withDefaultNamespace(stem).
        MethodNode factory = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "create", CREATE_DESC, null, null);
        cn.methods.add(factory);
        InsnList create = factory.instructions;
        create.add(new TypeInsnNode(Opcodes.NEW, PLAYER_SKIN));
        create.add(new InsnNode(Opcodes.DUP));
        if (resourceTexture) {
            create.add(new TypeInsnNode(Opcodes.NEW, RESOURCE_TEXTURE));
            create.add(new InsnNode(Opcodes.DUP));
            create.add(new VarInsnNode(Opcodes.ALOAD, 0));
            create.add(new MethodInsnNode(Opcodes.INVOKESTATIC, IDENTIFIER, "withDefaultNamespace",
                "(Ljava/lang/String;)L" + IDENTIFIER + ";"));
            create.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, RESOURCE_TEXTURE, ClassKit.INIT, "(L" + IDENTIFIER + ";)V"));
        } else {
            create.add(new TypeInsnNode(Opcodes.NEW, ClassKit.OBJECT_INTERNAL));
            create.add(new InsnNode(Opcodes.DUP));
            create.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, ClassKit.OBJECT_INTERNAL, ClassKit.INIT, "()V"));
        }
        create.add(new InsnNode(Opcodes.ACONST_NULL));
        create.add(new InsnNode(Opcodes.ACONST_NULL));
        create.add(new VarInsnNode(Opcodes.ALOAD, 1));
        create.add(new InsnNode(Opcodes.ICONST_1));
        create.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, PLAYER_SKIN, ClassKit.INIT,
            "(Lnet/minecraft/core/ClientAsset$Texture;Lnet/minecraft/core/ClientAsset$Texture;"
                + "Lnet/minecraft/core/ClientAsset$Texture;L" + MODEL_TYPE + ";Z)V"));
        create.add(new InsnNode(Opcodes.ARETURN));
        return cn;
    }

    /** An int constant as javac emits it - {@code ICONST_*} to five, {@code BIPUSH} beyond. */
    private static void pushInt(@NotNull InsnList code, int value) {
        if (value <= 5) code.add(new InsnNode(Opcodes.ICONST_0 + value));
        else code.add(new IntInsnNode(Opcodes.BIPUSH, value));
    }

}
