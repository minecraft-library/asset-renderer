package lib.minecraft.renderer.tooling.entity;

import lib.minecraft.renderer.client.ClientOptions;
import lib.minecraft.renderer.tooling.kernel.ClassKit;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lib.minecraft.renderer.tooling.kernel.ToolingSession;
import lib.minecraft.renderer.tooling.kernel.VanillaSourceClasses;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins for the bake-triple consumer accounting in {@link EntityGeometryRefResolver}'s
 * renderer-constructor scan: a constructor consuming two or more models takes the last n fresh
 * triples in argument order and always empties the fresh buffer - even when it held fewer than
 * n - and a single-model consumption pops only the newest triple. Driven on synthetic
 * constructor bodies through reflection (the member is private); every layer source is a
 * direct {@code ModelLayers} read, so no jar is consulted.
 */
@DisplayName("entity geometry ref - multi-model consumer accounting on synthetic ctor bodies")
class EntityGeometryRefResolverTest {

    /** A model class under the client-model root, outside the geom subtree. */
    private static final @NotNull String MODEL = VanillaSourceClasses.Types.CLIENT_MODEL_ROOT + "CowModel";

    /** A two-model consumer owner - the AgeableMobRenderer super shape. */
    private static final @NotNull String PAIR_OWNER = "net/minecraft/client/renderer/entity/AgeableMobRenderer";

    /** A one-model consumer owner - the layer-constructor shape. */
    private static final @NotNull String SINGLE_OWNER = "net/minecraft/client/renderer/entity/layers/SaddleLayer";

    /** The {@code ModelLayerLocation} reference descriptor the push gate tests. */
    private static final @NotNull String MLL_REF = VanillaSourceClasses.Descs.ref(VanillaSourceClasses.Types.MODEL_LAYER_LOCATION);

    @Test
    @DisplayName("a two-model <init> takes the last two fresh triples in order, then clears")
    void pairConsumerTakesNewestTwoAndClears() throws ReflectiveOperationException {
        EntityGeometryRefResolver resolver = resolver();
        List<String> sites = new ArrayList<>();
        List<String> pushes = new ArrayList<>();
        MethodNode ctor = ctor(
            // an armour-set read off the same registry class - wrong desc, never pushed
            new FieldInsnNode(Opcodes.GETSTATIC, VanillaSourceClasses.Types.MODEL_LAYERS, "ARMOR", "Ljava/util/Set;"),
            layerField("A"), bakeLayer(), modelInit(),
            layerField("B"), bakeLayer(), modelInit(),
            layerField("C"), bakeLayer(), modelInit(),
            pairConsumer(),
            layerField("D"), bakeLayer(), modelInit(),
            pairConsumer());
        collect(resolver, ctor, pushes, sites);
        assertEquals(List.of("A", "B", "C", "D"), sites, "every resolved triple is appended");
        assertEquals(List.of("A", "B", "C", "D"), pushes, "only ModelLayerLocation-typed reads push");
        // the first consumer took the last two of [A, B, C]; had consumption not also emptied
        // the buffer, the second consumer would still see a pair and be recorded too
        assertEquals(List.of(new EntityGeometryRefResolver.ModelConsumer(PAIR_OWNER, List.of("B", "C"))),
            resolver.modelConsumers());
    }

    @Test
    @DisplayName("a one-model <init> pops the newest triple; an empty buffer is left alone")
    void singleConsumerPopsNewest() throws ReflectiveOperationException {
        EntityGeometryRefResolver resolver = resolver();
        List<String> sites = new ArrayList<>();
        MethodNode ctor = ctor(
            singleConsumer(),               // nothing fresh yet - the guard skips it
            layerField("A"), bakeLayer(), modelInit(),
            layerField("B"), bakeLayer(), modelInit(),
            layerField("C"), bakeLayer(), modelInit(),
            singleConsumer(),               // pops C, the newest
            pairConsumer());
        collect(resolver, ctor, new ArrayList<>(), sites);
        assertEquals(List.of("A", "B", "C"), sites);
        // the pair consumer sees [A, B]: popping the oldest would have left [B, C] instead
        assertEquals(List.of(new EntityGeometryRefResolver.ModelConsumer(PAIR_OWNER, List.of("A", "B"))),
            resolver.modelConsumers());
    }

    @Test
    @DisplayName("a short multi-model <init> records nothing and still clears the fresh buffer")
    void shortConsumerStillClears() throws ReflectiveOperationException {
        EntityGeometryRefResolver resolver = resolver();
        List<String> sites = new ArrayList<>();
        MethodNode ctor = ctor(
            layerField("A"), bakeLayer(), modelInit(),
            pairConsumer(),                 // one fresh triple, needs two - no consumer, buffer cleared
            layerField("B"), bakeLayer(), modelInit(),
            pairConsumer());                // without the clear this would pair the stale A with B
        collect(resolver, ctor, new ArrayList<>(), sites);
        assertEquals(List.of("A", "B"), sites);
        assertEquals(List.of(), resolver.modelConsumers());
    }

    // ------------------------------------------------------------------------------------
    // fixture plumbing
    // ------------------------------------------------------------------------------------

    /**
     * A resolver over inert context values: the driven scan resolves every layer source at the
     * direct {@code GETSTATIC ModelLayers} arm, so neither the cache nor the indexes are read.
     */
    private static @NotNull EntityGeometryRefResolver resolver() {
        Diagnostics diagnostics = Diagnostics.root("entityModels", Diagnostics.Output.NONE, null);
        ToolingSession session = new ToolingSession(ClientOptions.defaults(), null, diagnostics);
        EntityIndexes indexes = new EntityIndexes(null, null, null, null, null, null, null, null);
        return new EntityGeometryRefResolver(new EntityContext(session, indexes, null, diagnostics));
    }

    private static void collect(@NotNull EntityGeometryRefResolver resolver, @NotNull MethodNode ctor,
        @NotNull List<String> pushes, @NotNull List<String> sites) throws ReflectiveOperationException {
        Method member = EntityGeometryRefResolver.class.getDeclaredMethod("collectBakedModelLayers",
            MethodNode.class, List.class, List.class, List.class);
        member.setAccessible(true);
        member.invoke(resolver, ctor, pushes, List.of(), sites);
    }

    private static @NotNull MethodNode ctor(AbstractInsnNode @NotNull ... nodes) {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, ClassKit.INIT, "()V", null, null);
        for (AbstractInsnNode node : nodes) method.instructions.add(node);
        return method;
    }

    private static @NotNull FieldInsnNode layerField(@NotNull String name) {
        return new FieldInsnNode(Opcodes.GETSTATIC, VanillaSourceClasses.Types.MODEL_LAYERS, name, MLL_REF);
    }

    private static @NotNull MethodInsnNode bakeLayer() {
        return new MethodInsnNode(Opcodes.INVOKEVIRTUAL, VanillaSourceClasses.Types.RENDERER_PROVIDER_CONTEXT,
            VanillaSourceClasses.Methods.BAKE_LAYER,
            "(" + MLL_REF + ")" + VanillaSourceClasses.Descs.ref(VanillaSourceClasses.Types.MODEL_PART), false);
    }

    private static @NotNull MethodInsnNode modelInit() {
        return new MethodInsnNode(Opcodes.INVOKESPECIAL, MODEL, ClassKit.INIT,
            "(" + VanillaSourceClasses.Descs.ref(VanillaSourceClasses.Types.MODEL_PART) + ")V", false);
    }

    private static @NotNull MethodInsnNode pairConsumer() {
        String model = VanillaSourceClasses.Descs.ref(MODEL);
        return new MethodInsnNode(Opcodes.INVOKESPECIAL, PAIR_OWNER, ClassKit.INIT, "(" + model + model + ")V", false);
    }

    private static @NotNull MethodInsnNode singleConsumer() {
        return new MethodInsnNode(Opcodes.INVOKESPECIAL, SINGLE_OWNER, ClassKit.INIT,
            "(" + VanillaSourceClasses.Descs.ref(MODEL) + ")V", false);
    }

}
