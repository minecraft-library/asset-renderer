package lib.minecraft.renderer.tooling.entity;

import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.content.client.ClientOptions;
import lib.minecraft.renderer.diagnostic.Diagnostics;
import lib.minecraft.renderer.tooling.asm.ClassNodeCache;
import lib.minecraft.renderer.tooling.names.SourceClasses;
import lib.minecraft.renderer.tooling.run.ToolingRun;
import org.jetbrains.annotations.NotNull;
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
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A bone its body draws only while an equipment stack is empty, and the slot that fills it.
 *
 * <p>The warm zombie nautilus is the shape: {@code ZombieNautilusCoralModel.setupAnim} writes
 * {@code corals.visible = state.bodyArmorItem.isEmpty()}, and the body-armour layer draws from that
 * same {@code bodyArmorItem}. So the corals are a toggle named off the stack's field, and the
 * equipment row whose item getter reads the field names that toggle for its wearer.
 */
@DisplayName("a filled slot names the toggle its stack's emptiness gates")
class EntityWearerToggleTest {

    private static final @NotNull String STATE = "fx/State";
    private static final @NotNull String MODEL = "fx/CoralModel";
    private static final @NotNull String RENDERER = "fx/Renderer";
    private static final @NotNull String ITEM_STACK_DESC = "L" + SourceClasses.Types.ITEM_STACK + ";";

    @TempDir
    Path tempDir;

    private ClassNodeCache cache;

    @AfterEach
    void closeFixtureJar() {
        if (this.cache != null) this.cache.close();
    }

    @Test
    @DisplayName("a bone drawn while a stack is empty is a toggle named off the stack's field")
    void anEmptinessGateNamesAToggle() throws IOException {
        open(coralModel());
        EntityBoneResolver bones = new EntityBoneResolver(context());

        JsonTree node = bones.resolve(MODEL, null, Set.of());
        assertNotNull(node, "the gate is a toggle, so the class declares one");
        assertEquals(Optional.of(List.of("corals")),
            node.findPath("toggles", "body_armor_item", "bones")
                .map(named -> named.elements().flatMap(bone -> bone.asString().stream()).toList()),
            "the corals answer to the toggle named off bodyArmorItem");
        assertTrue(node.findPath("undrawn").isEmpty(), "and nothing is said about which way they rest");
    }

    @Test
    @DisplayName("the row whose item field is the gated stack names the toggle, and no other row does")
    void theFillingSlotNamesTheToggle() {
        JsonTree row = row(true);

        assertEquals(1, EntityEquipmentResolver.nameWearerToggles(row), "one row fills the gated stack");
        List<JsonTree> items = row.findArray("equipment").orElseThrow().elements().toList();
        assertEquals(Optional.of("body_armor_item"), items.getFirst().findString("wearer_toggle"),
            "the body row names the toggle its stack's emptiness gates");
        assertTrue(items.getLast().findString("wearer_toggle").isEmpty(),
            "the saddle row fills a stack nothing gates a bone on");
    }

    @Test
    @DisplayName("a row whose stack gates no bone names no toggle")
    void anUngatedStackNamesNothing() {
        JsonTree row = row(false);

        assertEquals(0, EntityEquipmentResolver.nameWearerToggles(row), "no node states the toggle");
        row.findArray("equipment").orElseThrow().elements().forEach(item ->
            assertTrue(item.findString("wearer_toggle").isEmpty(), "so no row names one"));
    }

    // ------------------------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------------------------

    /**
     * The zombie nautilus row as the resolvers leave it: a warm coat carrying the coral toggle when
     * {@code gated}, a body row reading {@code bodyArmorItem} and a saddle row reading {@code saddle}.
     */
    private static @NotNull JsonTree row(boolean gated) {
        JsonTree warm = JsonTree.object().put("geometry", "CoralModel#createBodyLayer");
        if (gated)
            warm.put("toggles", JsonTree.object()
                .put("body_armor_item", JsonTree.object().putStrings("bones", "corals")));
        JsonTree row = JsonTree.object()
            .put("axes", JsonTree.object()
                .put("variant", JsonTree.object().put("options", JsonTree.object().put("warm", warm))));
        JsonTree equipment = row.childArray("equipment");
        equipment.add(JsonTree.object().put("slot", "body").put("item_field", "bodyArmorItem"));
        equipment.add(JsonTree.object().put("slot", "saddle").put("item_field", "saddle"));
        return row;
    }

    /** {@code this.corals.visible = state.bodyArmorItem.isEmpty()}, as 26.1 compiles it. */
    private static @NotNull ClassNode coralModel() {
        ClassNode node = new ClassNode();
        node.version = Opcodes.V21;
        node.access = Opcodes.ACC_PUBLIC;
        node.name = MODEL;
        node.superName = SourceClasses.Types.ENTITY_MODEL;
        MethodNode setupAnim = new MethodNode(Opcodes.ACC_PUBLIC, "setupAnim", "(L" + STATE + ";)V", null, null);
        InsnList code = setupAnim.instructions;
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, MODEL, "corals", SourceClasses.Descs.MODEL_PART_REF));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, STATE, "bodyArmorItem", ITEM_STACK_DESC));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, SourceClasses.Types.ITEM_STACK,
            SourceClasses.Methods.IS_EMPTY, "()Z", false));
        code.add(new FieldInsnNode(Opcodes.PUTFIELD, SourceClasses.Types.MODEL_PART, "visible", "Z"));
        code.add(new InsnNode(Opcodes.RETURN));
        node.methods.add(setupAnim);
        return node;
    }

    /** A context over the fixture jar, naming a renderer the jar does not hold; no index is consulted. */
    private @NotNull EntityContext context() {
        Diagnostics diagnostics = Diagnostics.root("entityModels", Diagnostics.Output.NONE, null);
        ToolingRun run = new ToolingRun(ClientOptions.defaults(), this.cache, diagnostics);
        EntityIndexes indexes = new EntityIndexes(null, null, null, null, null, null, null, null);
        EntitySubject subject = new EntitySubject("minecraft:fx", "fx/Entity", RENDERER, List.of(), List.of(), List.of());
        return new EntityContext(run, indexes, subject, diagnostics);
    }

    private void open(@NotNull ClassNode @NotNull ... fixtures) throws IOException {
        Path jar = this.tempDir.resolve("fixtures.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            for (ClassNode fixture : fixtures) {
                zip.putNextEntry(new ZipEntry(fixture.name + ".class"));
                ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                fixture.accept(writer);
                zip.write(writer.toByteArray());
                zip.closeEntry();
            }
        }
        this.cache = ClassNodeCache.open(jar);
    }

}
