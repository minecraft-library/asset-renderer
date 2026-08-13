package lib.minecraft.renderer.tooling.blockentity;

import lib.minecraft.renderer.tooling.kernel.ClassNodeCache;
import lib.minecraft.renderer.tooling.kernel.ToolingException;
import lib.minecraft.renderer.tooling.kernel.VanillaSourceClasses;
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
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pins the chest block-to-renderer-field binding against a fixture mirroring the 26.1 bytecode of
 * both chest builders, instruction for instruction: the material block read between the chest block
 * and the renderer field, the two {@code createChest} overloads, and the four {@code copyModel} calls
 * that hand each waxed chest the model of the block it copies.
 *
 * <p>The expected table is the eleven-row map the walk replaces, so a divergence in either direction
 * fails here rather than at a flow run. {@code CHRISTMAS} is the pin that the walk is strictly better
 * than a field-set read: it is a declared renderer field no chest block binds, and a derivation keyed
 * on the bindings cannot reach it.
 */
@DisplayName("the chest variant field is walked out of the datagen builders, block by block")
class ChestVariantBindingTest {

    private static final @NotNull String GENERATORS = VanillaSourceClasses.Types.BLOCK_MODEL_GENERATORS;
    private static final @NotNull String BLOCKS = VanillaSourceClasses.Types.BLOCKS;
    private static final @NotNull String RENDERER = VanillaSourceClasses.Types.CHEST_SPECIAL_RENDERER;
    private static final @NotNull String BLOCK_DESC = "Lnet/minecraft/world/level/block/Block;";
    private static final @NotNull String RESOURCES_DESC = "Lnet/minecraft/client/renderer/MultiblockChestResources;";
    private static final @NotNull String IDENTIFIER_DESC = "Lnet/minecraft/resources/Identifier;";

    /** {@code createChest(Block, Block, MultiblockChestResources, boolean)} - the seven-of-eight shape. */
    private static final @NotNull String RESOURCES_OVERLOAD = "(" + BLOCK_DESC + BLOCK_DESC + RESOURCES_DESC + "Z)V";

    /** {@code createChest(Block, Block, Identifier, boolean)} - the ender chest's own overload. */
    private static final @NotNull String IDENTIFIER_OVERLOAD = "(" + BLOCK_DESC + BLOCK_DESC + IDENTIFIER_DESC + "Z)V";

    private static final @NotNull String COPY_MODEL_DESC = "(" + BLOCK_DESC + BLOCK_DESC + ")V";

    /** The eleven rows the declared map answered, block field to renderer field. */
    private static final @NotNull Map<String, String> EXPECTED = expected();

    private static @NotNull Map<String, String> expected() {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("CHEST", "REGULAR");
        out.put("TRAPPED_CHEST", "TRAPPED");
        out.put("ENDER_CHEST", "ENDER_CHEST");
        out.put("COPPER_CHEST", "COPPER_UNAFFECTED");
        out.put("EXPOSED_COPPER_CHEST", "COPPER_EXPOSED");
        out.put("WEATHERED_COPPER_CHEST", "COPPER_WEATHERED");
        out.put("OXIDIZED_COPPER_CHEST", "COPPER_OXIDIZED");
        out.put("WAXED_COPPER_CHEST", "COPPER_UNAFFECTED");
        out.put("WAXED_EXPOSED_COPPER_CHEST", "COPPER_EXPOSED");
        out.put("WAXED_WEATHERED_COPPER_CHEST", "COPPER_WEATHERED");
        out.put("WAXED_OXIDIZED_COPPER_CHEST", "COPPER_OXIDIZED");
        return out;
    }

    @TempDir
    Path tempDir;

    private ClassNodeCache cache;

    @BeforeEach
    void openFixtureJar() throws IOException {
        Path jar = this.tempDir.resolve("generators.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            ClassNode fixture = blockModelGenerators();
            zip.putNextEntry(new ZipEntry(fixture.name + ".class"));
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            fixture.accept(writer);
            zip.write(writer.toByteArray());
            zip.closeEntry();
        }
        this.cache = ClassNodeCache.open(jar);
    }

    @AfterEach
    void closeFixtureJar() {
        this.cache.close();
    }

    @Test
    @DisplayName("the walk recovers the eleven rows the declared map held, entry for entry")
    void recoversTheDeclaredMap() {
        assertEquals(EXPECTED, BlockCatalogResolver.chestVariantFields(this.cache));
    }

    @Test
    @DisplayName("a declared renderer field no block binds is unreachable - the walk keys on the bindings")
    void unboundRendererFieldIsUnreachable() {
        assertFalse(BlockCatalogResolver.chestVariantFields(this.cache).containsValue("CHRISTMAS"),
            "CHRISTMAS is declared on the renderer and bound by no chest block");
    }

    @Test
    @DisplayName("the ender chest's own overload is a binding site, not a shape the walk passes over")
    void bothOverloadsBind() {
        // the ender chest's field arrives as an Identifier through a second createChest overload
        assertEquals("ENDER_CHEST", BlockCatalogResolver.chestVariantFields(this.cache).get("ENDER_CHEST"));
    }

    @Test
    @DisplayName("the material block read between the two is never taken for the binding")
    void materialBlockIsNotTheBinding() {
        Map<String, String> bound = BlockCatalogResolver.chestVariantFields(this.cache);
        // each call reads its chest block, then a material block, then the field
        assertFalse(bound.containsKey("OAK_PLANKS"), "the material block binds nothing");
        assertFalse(bound.containsKey("OBSIDIAN"), "the ender chest's material binds nothing");
        assertFalse(bound.containsKey("COPPER_BLOCK"), "the copper material binds nothing");
    }

    @Test
    @DisplayName("a jar without the model writer fails the run")
    void absentGeneratorsFailLoudly() throws IOException {
        Path jar = this.tempDir.resolve("absent.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            zip.putNextEntry(new ZipEntry("test/Unrelated.class"));
            ClassNode unrelated = new ClassNode();
            unrelated.version = Opcodes.V21;
            unrelated.access = Opcodes.ACC_PUBLIC;
            unrelated.name = "test/Unrelated";
            unrelated.superName = "java/lang/Object";
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            unrelated.accept(writer);
            zip.write(writer.toByteArray());
            zip.closeEntry();
        }
        try (ClassNodeCache absent = ClassNodeCache.open(jar)) {
            assertThrows(ToolingException.class, () -> BlockCatalogResolver.chestVariantFields(absent));
        }
    }

    // ------------------------------------------------------------------------------------
    // fixture - both builders, mirroring the shipped javap instruction for instruction
    // ------------------------------------------------------------------------------------

    private static @NotNull ClassNode blockModelGenerators() {
        ClassNode cn = new ClassNode();
        cn.version = Opcodes.V21;
        cn.access = Opcodes.ACC_PUBLIC;
        cn.name = GENERATORS;
        cn.superName = "java/lang/Object";

        InsnList chests = builder(cn, VanillaSourceClasses.Methods.CREATE_CHESTS);
        createChest(cn, chests, "CHEST", "OAK_PLANKS", "REGULAR", RESOURCES_DESC, RESOURCES_OVERLOAD, true);
        createChest(cn, chests, "TRAPPED_CHEST", "OAK_PLANKS", "TRAPPED", RESOURCES_DESC, RESOURCES_OVERLOAD, true);
        createChest(cn, chests, "ENDER_CHEST", "OBSIDIAN", "ENDER_CHEST", IDENTIFIER_DESC, IDENTIFIER_OVERLOAD, false);
        chests.add(new InsnNode(Opcodes.RETURN));

        InsnList copper = builder(cn, VanillaSourceClasses.Methods.CREATE_COPPER_CHESTS);
        createChest(cn, copper, "COPPER_CHEST", "COPPER_BLOCK", "COPPER_UNAFFECTED", RESOURCES_DESC, RESOURCES_OVERLOAD, false);
        createChest(cn, copper, "EXPOSED_COPPER_CHEST", "EXPOSED_COPPER", "COPPER_EXPOSED", RESOURCES_DESC, RESOURCES_OVERLOAD, false);
        createChest(cn, copper, "WEATHERED_COPPER_CHEST", "WEATHERED_COPPER", "COPPER_WEATHERED", RESOURCES_DESC, RESOURCES_OVERLOAD, false);
        createChest(cn, copper, "OXIDIZED_COPPER_CHEST", "OXIDIZED_COPPER", "COPPER_OXIDIZED", RESOURCES_DESC, RESOURCES_OVERLOAD, false);
        copyModel(cn, copper, "COPPER_CHEST", "WAXED_COPPER_CHEST");
        copyModel(cn, copper, "EXPOSED_COPPER_CHEST", "WAXED_EXPOSED_COPPER_CHEST");
        copyModel(cn, copper, "WEATHERED_COPPER_CHEST", "WAXED_WEATHERED_COPPER_CHEST");
        copyModel(cn, copper, "OXIDIZED_COPPER_CHEST", "WAXED_OXIDIZED_COPPER_CHEST");
        copper.add(new InsnNode(Opcodes.RETURN));
        return cn;
    }

    /** A private no-argument void builder, as both chest builders are. */
    private static @NotNull InsnList builder(@NotNull ClassNode owner, @NotNull String name) {
        MethodNode mn = new MethodNode(Opcodes.ACC_PRIVATE, name, VanillaSourceClasses.Descs.NO_ARG_VOID_DESC, null, null);
        owner.methods.add(mn);
        return mn.instructions;
    }

    /** {@code ALOAD 0; GETSTATIC chest; GETSTATIC material; GETSTATIC field; <flag>; INVOKEVIRTUAL createChest}. */
    private static void createChest(
        @NotNull ClassNode owner,
        @NotNull InsnList code,
        @NotNull String chest,
        @NotNull String material,
        @NotNull String field,
        @NotNull String fieldDesc,
        @NotNull String overload,
        boolean flag
    ) {
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETSTATIC, BLOCKS, chest, BLOCK_DESC));
        code.add(new FieldInsnNode(Opcodes.GETSTATIC, BLOCKS, material, BLOCK_DESC));
        code.add(new FieldInsnNode(Opcodes.GETSTATIC, RENDERER, field, fieldDesc));
        code.add(new InsnNode(flag ? Opcodes.ICONST_1 : Opcodes.ICONST_0));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, owner.name, VanillaSourceClasses.Methods.CREATE_CHEST, overload));
    }

    /** {@code ALOAD 0; GETSTATIC source; GETSTATIC target; INVOKEVIRTUAL copyModel}. */
    private static void copyModel(@NotNull ClassNode owner, @NotNull InsnList code, @NotNull String source, @NotNull String target) {
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETSTATIC, BLOCKS, source, BLOCK_DESC));
        code.add(new FieldInsnNode(Opcodes.GETSTATIC, BLOCKS, target, BLOCK_DESC));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, owner.name, VanillaSourceClasses.Methods.COPY_MODEL, COPY_MODEL_DESC));
    }

}
