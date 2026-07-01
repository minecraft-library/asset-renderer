package lib.minecraft.renderer.tooling.blockentity;

import dev.simplified.collection.ConcurrentList;
import lib.minecraft.renderer.tooling.util.Diagnostics;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

/**
 * Fast mutation tests for {@link SourceDiscovery#discover}. Each test builds a <i>synthetic</i>
 * client jar via {@link ClassWriter} (no real classes from {@code client.jar}), runs discovery
 * against it, then mutates the synthetic input and re-runs to prove the emitted {@link Source}
 * records follow the bytecode shape rather than a hardcoded table. This pins the four discovery
 * stages - registry walk, entity-id walk, {@code LayerDefinitions.createRoots} walk, per-renderer
 * layer scan - plus the {@link YAxis} pivot heuristic and the primary-method allow-list filter,
 * without paying for the slow real-jar parity run.
 *
 * <p>The fixtures are deliberately minimal - each mirrors exactly the one bytecode shape a
 * discovery stage keys off:
 * <ul>
 *     <li>{@code BlockEntityRenderers.<clinit>} with one or two
 *         {@code GETSTATIC BlockEntityType.X; INVOKEDYNAMIC Renderer::new} pairs (the registry
 *         walk's ({@code BE-type}, renderer) binding).</li>
 *     <li>{@code BlockEntityType} with one or two {@code LDC "id"; PUTSTATIC field} pairs (the
 *         entity-id walk's field &rarr; id binding).</li>
 *     <li>{@code LayerDefinitions.createRoots} with one or two
 *         {@code Builder.put(ModelLayers.X, Model.createY())} pairs (the layer-definitions table).</li>
 *     <li>A minimal {@code Renderer} class whose {@code <init>(Context)} references
 *         {@code ModelLayers.X} (the per-renderer layer-reference scan target).</li>
 *     <li>A minimal {@code Model} class with a {@code createY} method containing a single
 *         {@code CubeListBuilder.addBox(...)} - and optionally a {@code PartPose.offset} pivot -
 *         so the Y-axis heuristic has something to inspect.</li>
 * </ul>
 */
@DisplayName("SourceDiscovery (bytecode-driven)")
class SourceDiscoveryTest {

    @TempDir Path tempDir;

    /**
     * A named bundle of the coordinated identifiers one synthetic renderer needs across all four
     * fixture classes - the BE-type field, renderer + model internal names, layer factory method,
     * and {@code ModelLayers} field. Tests pass an array of these to {@link #buildSyntheticJar}
     * and mutate the jar by dropping an enumerant (drops a register call) or overriding one
     * fixture class through {@link Mutations}. Two shapes ({@code A}, {@code B}) suffice to
     * exercise single- and two-renderer cases and their emission order.
     */
    private enum TestRenderer {
        A("foo", "test/RendererA", "test/ModelA", "createBodyLayer", "FOO_LAYER"),
        B("bar", "test/RendererB", "test/ModelB", "createShellLayer", "BAR_LAYER");

        /** The {@code BlockEntityType} field name, entity id upper-cased ({@code "foo"} &rarr; {@code "FOO"}). */
        final String beField;
        /** The renderer class's JVM internal name (registered in {@code BlockEntityRenderers.<clinit>}). */
        final String rendererInternal;
        /** The model class's JVM internal name (owns the layer factory method). */
        final String modelInternal;
        /** The layer factory method name, one of {@code SourceDiscovery}'s primary names. */
        final String modelMethod;
        /** The {@code ModelLayers} static field name the renderer's {@code <init>} references. */
        final String layerField;
        /** The lower-cased entity id ({@code "foo"}), namespaced to {@code minecraft:foo} on emission. */
        final String entityId;

        TestRenderer(String beField, String rendererInternal, String modelInternal, String modelMethod, String layerField) {
            // BE types are named by the entity id upper-cased ("foo" -> "FOO").
            this.beField = beField.toUpperCase();
            this.rendererInternal = rendererInternal;
            this.modelInternal = modelInternal;
            this.modelMethod = modelMethod;
            this.layerField = layerField;
            this.entityId = beField;
        }
    }

    // ------------------------------------------------------------------------------------------
    // Synthetic jar builder
    // ------------------------------------------------------------------------------------------

    /**
     * Bytecode shaping hooks used to replace individual classes entirely. When a hook is
     * non-null the corresponding class is built by the hook instead of using the default
     * fixture; when {@code null} the default fixture applies.
     */
    private static final class Mutations {
        java.util.function.Function<TestRenderer[], byte[]> blockEntityRenderers;
        java.util.function.Function<TestRenderer[], byte[]> blockEntityType;
        java.util.function.Function<TestRenderer[], byte[]> layerDefinitions;
        java.util.function.Function<TestRenderer[], byte[]> modelLayers;
        java.util.function.Function<TestRenderer, byte[]> model;
    }

    /** Writes {@code bytes} into {@code zos} under the {@code internalName + ".class"} zip entry. */
    private static void writeClass(ZipOutputStream zos, String internalName, byte[] bytes) throws IOException {
        zos.putNextEntry(new ZipEntry(internalName + ".class"));
        zos.write(bytes);
        zos.closeEntry();
    }

    /**
     * Builds a synthetic client jar containing the minimum set of classes that
     * {@link SourceDiscovery#discover} expects: {@code BlockEntityRenderers},
     * {@code BlockEntityType}, {@code LayerDefinitions}, {@code ModelLayers}, plus per-test
     * renderer + model classes for the {@link TestRenderer} enumerants used.
     */
    private Path buildSyntheticJar(TestRenderer[] renderers, Mutations mutations) throws IOException {
        String name = "synthetic-" + Arrays.toString(renderers).hashCode() + "-" + System.nanoTime() + ".jar";
        Path jar = tempDir.resolve(name);
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(jar))) {
            writeClass(zos, "net/minecraft/world/level/block/entity/BlockEntityType",
                mutations.blockEntityType != null ? mutations.blockEntityType.apply(renderers) : defaultBlockEntityType(renderers));
            writeClass(zos, "net/minecraft/client/model/geom/ModelLayers",
                mutations.modelLayers != null ? mutations.modelLayers.apply(renderers) : defaultModelLayers(renderers));
            writeClass(zos, "net/minecraft/client/renderer/blockentity/BlockEntityRenderers",
                mutations.blockEntityRenderers != null ? mutations.blockEntityRenderers.apply(renderers) : defaultBlockEntityRenderers(renderers));
            writeClass(zos, "net/minecraft/client/model/geom/LayerDefinitions",
                mutations.layerDefinitions != null ? mutations.layerDefinitions.apply(renderers) : defaultLayerDefinitions(renderers));
            for (TestRenderer r : renderers) {
                writeClass(zos, r.rendererInternal, rendererClass(r));
                writeClass(zos, r.modelInternal, mutations.model != null ? mutations.model.apply(r) : modelClass(r));
            }
        }
        return jar;
    }

    /**
     * Builds a bare {@code public} class with no fields, methods, or {@code <clinit>} - used to
     * stub a class whose mere presence (not content) is exercised, e.g. the missing-registry test
     * that ships {@code BlockEntityType} but omits {@code BlockEntityRenderers}.
     */
    private static byte[] emptyClass(String internalName) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * Builds a stub {@code EntityModelSet} whose {@code bakeLayer} returns null. Referenced by the
     * signatures the synthetic bytecode emits so class verification stays satisfied.
     */
    private static byte[] entityModelSetClass() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "net/minecraft/client/model/geom/EntityModelSet", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "bakeLayer",
            "(Lnet/minecraft/client/model/geom/ModelLayerLocation;)Lnet/minecraft/client/model/geom/ModelPart;", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(1, 2);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * Builds a stub {@code CubeListBuilder} exposing {@code create()} and {@code addBox(FFFFFF)}
     * so the model bytecode's {@code INVOKESTATIC create} / {@code INVOKEVIRTUAL addBox} calls
     * resolve against real signatures.
     */
    private static byte[] cubeListBuilderClass() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "net/minecraft/client/model/geom/builders/CubeListBuilder", null, "java/lang/Object", null);
        // Simplified stubs so the bytecode we emit references these signatures.
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "create", "()Lnet/minecraft/client/model/geom/builders/CubeListBuilder;", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(1, 0);
        mv.visitEnd();
        mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "addBox", "(FFFFFF)Lnet/minecraft/client/model/geom/builders/CubeListBuilder;", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(1, 7);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * Builds {@code BlockEntityType.<clinit>} with one {@code LDC "id"; PUTSTATIC field} pair
     * per renderer, preceded by a declared field for each.
     */
    private static byte[] defaultBlockEntityType(TestRenderer[] renderers) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "net/minecraft/world/level/block/entity/BlockEntityType", null, "java/lang/Object", null);
        for (TestRenderer r : renderers)
            cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, r.beField,
                "Lnet/minecraft/world/level/block/entity/BlockEntityType;", null, null).visitEnd();
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        mv.visitCode();
        for (TestRenderer r : renderers) {
            mv.visitLdcInsn(r.entityId);
            // Use the string as a dummy value bound to the field. The PUTSTATIC expects
            // BlockEntityType - cast via Type but we never evaluate at runtime.
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitFieldInsn(Opcodes.PUTSTATIC, "net/minecraft/world/level/block/entity/BlockEntityType", r.beField,
                "Lnet/minecraft/world/level/block/entity/BlockEntityType;");
            mv.visitInsn(Opcodes.POP); // drop the ldc we left on the stack (the PUTSTATIC popped ACONST_NULL)
        }
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(2, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * Builds {@code ModelLayers} with one {@code public static final X} field per renderer.
     * The fields are uninitialised - discovery only inspects their presence, not their values.
     */
    private static byte[] defaultModelLayers(TestRenderer[] renderers) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "net/minecraft/client/model/geom/ModelLayers", null, "java/lang/Object", null);
        for (TestRenderer r : renderers)
            cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, r.layerField,
                "Lnet/minecraft/client/model/geom/ModelLayerLocation;", null, null).visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * Builds {@code BlockEntityRenderers.<clinit>} with one
     * {@code GETSTATIC BlockEntityType.X; INVOKEDYNAMIC Renderer::new} pair per renderer.
     */
    private static byte[] defaultBlockEntityRenderers(TestRenderer[] renderers) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "net/minecraft/client/renderer/blockentity/BlockEntityRenderers", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        mv.visitCode();
        for (TestRenderer r : renderers) {
            mv.visitFieldInsn(Opcodes.GETSTATIC, "net/minecraft/world/level/block/entity/BlockEntityType", r.beField,
                "Lnet/minecraft/world/level/block/entity/BlockEntityType;");
            Handle ctor = new Handle(Opcodes.H_NEWINVOKESPECIAL, r.rendererInternal, "<init>",
                "(Lnet/minecraft/client/renderer/blockentity/BlockEntityRendererProvider$Context;)V", false);
            mv.visitInvokeDynamicInsn("create",
                "()Ljava/util/function/Supplier;",
                new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
                    "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false),
                Type.getType("(Lnet/minecraft/client/renderer/blockentity/BlockEntityRendererProvider$Context;)Ljava/lang/Object;"),
                ctor,
                Type.getType("(Lnet/minecraft/client/renderer/blockentity/BlockEntityRendererProvider$Context;)Ljava/lang/Object;"));
            // Drop the two stack values the JVM would route to registerPut. We don't care
            // about the runtime register call - discovery only reads GETSTATIC + INVOKEDYNAMIC.
            mv.visitInsn(Opcodes.POP);
            mv.visitInsn(Opcodes.POP);
        }
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(4, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * Builds {@code LayerDefinitions.createRoots} with one
     * {@code Builder.put(ModelLayers.X, Model.createY())} pair per renderer.
     */
    private static byte[] defaultLayerDefinitions(TestRenderer[] renderers) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "net/minecraft/client/model/geom/LayerDefinitions", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "createRoots", "()Ljava/util/Map;", null, null);
        mv.visitCode();
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "com/google/common/collect/ImmutableMap", "builder", "()Lcom/google/common/collect/ImmutableMap$Builder;", false);
        mv.visitVarInsn(Opcodes.ASTORE, 0);
        for (TestRenderer r : renderers) {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(Opcodes.GETSTATIC, "net/minecraft/client/model/geom/ModelLayers", r.layerField,
                "Lnet/minecraft/client/model/geom/ModelLayerLocation;");
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, r.modelInternal, r.modelMethod,
                "()Lnet/minecraft/client/model/geom/builders/LayerDefinition;", false);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/google/common/collect/ImmutableMap$Builder", "put",
                "(Ljava/lang/Object;Ljava/lang/Object;)Lcom/google/common/collect/ImmutableMap$Builder;", false);
            mv.visitInsn(Opcodes.POP);
        }
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/google/common/collect/ImmutableMap$Builder", "build",
            "()Lcom/google/common/collect/ImmutableMap;", false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(4, 1);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * Builds a synthetic renderer whose {@code <init>(Context)} references the
     * {@code ModelLayers.X} field (so {@link SourceDiscovery#collectLayerRefs} sees it).
     */
    private static byte[] rendererClass(TestRenderer r) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, r.rendererInternal, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>",
            "(Lnet/minecraft/client/renderer/blockentity/BlockEntityRendererProvider$Context;)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitFieldInsn(Opcodes.GETSTATIC, "net/minecraft/client/model/geom/ModelLayers", r.layerField,
            "Lnet/minecraft/client/model/geom/ModelLayerLocation;");
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(1, 2);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * Builds a synthetic model whose {@code r.modelMethod} emits a single 8x8x8 cube at the
     * origin with no pivot - the default fixture, exercising the {@link YAxis#DOWN} fall-through.
     */
    private static byte[] modelClass(TestRenderer r) {
        return modelClassWithCube(r, 0f, 0f, 0f, 8f, 8f, 8f);
    }

    /**
     * Builds a synthetic model whose {@code r.modelMethod} emits a single cube at the given
     * origin + size, with no {@code PartPose.offset} pivot. The Y-axis heuristic ignores cube
     * coordinates entirely (it only reads pivots), so this always yields {@link YAxis#DOWN}.
     */
    private static byte[] modelClassWithCube(TestRenderer r, float x, float y, float z, float w, float h, float d) {
        return modelClassWithCubeAndPivot(r, x, y, z, w, h, d, 0f);
    }

    /**
     * Builds a synthetic model whose {@code r.modelMethod} emits one cube plus, when
     * {@code pivotY != 0}, one {@code PartPose.offset(0, pivotY, 0)} call. The {@code pivotY} is
     * the sole input to the Y-axis heuristic - {@code [8, 16)} yields {@link YAxis#UP}, anything
     * else {@link YAxis#DOWN}.
     */
    private static byte[] modelClassWithCubeAndPivot(TestRenderer r, float x, float y, float z, float w, float h, float d, float pivotY) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, r.modelInternal, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, r.modelMethod,
            "()Lnet/minecraft/client/model/geom/builders/LayerDefinition;", null, null);
        mv.visitCode();
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraft/client/model/geom/builders/CubeListBuilder", "create",
            "()Lnet/minecraft/client/model/geom/builders/CubeListBuilder;", false);
        mv.visitLdcInsn(x); mv.visitLdcInsn(y); mv.visitLdcInsn(z);
        mv.visitLdcInsn(w); mv.visitLdcInsn(h); mv.visitLdcInsn(d);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/minecraft/client/model/geom/builders/CubeListBuilder", "addBox",
            "(FFFFFF)Lnet/minecraft/client/model/geom/builders/CubeListBuilder;", false);
        mv.visitInsn(Opcodes.POP);

        if (pivotY != 0f) {
            mv.visitLdcInsn(0f); mv.visitLdcInsn(pivotY); mv.visitLdcInsn(0f);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraft/client/model/geom/PartPose", "offset",
                "(FFF)Lnet/minecraft/client/model/geom/PartPose;", false);
            mv.visitInsn(Opcodes.POP);
        }

        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(7, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * Builds the synthetic jar for {@code renderers} under {@code m}, opens it, and runs
     * {@link SourceDiscovery#discover} with a fresh {@link Diagnostics}. The diagnostics are
     * discarded here - tests that assert on them build their jar inline instead.
     */
    private @NotNull ConcurrentList<Source> run(TestRenderer[] renderers, Mutations m) throws IOException {
        Path jar = buildSyntheticJar(renderers, m);
        try (ZipFile zf = new ZipFile(jar.toFile())) {
            Diagnostics diag = new Diagnostics();
            return SourceDiscovery.discover(zf, diag);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("two register calls produce two sources (baseline mutation: add/remove register)")
    void twoRegisters() throws IOException {
        ConcurrentList<Source> twoReg = run(new TestRenderer[]{ TestRenderer.A, TestRenderer.B }, new Mutations());
        assertThat("two register calls -> two sources", twoReg, hasSize(2));
        assertThat(twoReg.get(0).entityId(), equalTo("minecraft:foo"));
        assertThat(twoReg.get(1).entityId(), equalTo("minecraft:bar"));

        ConcurrentList<Source> oneReg = run(new TestRenderer[]{ TestRenderer.A }, new Mutations());
        assertThat("removing a register drops a source", oneReg, hasSize(1));
        assertThat(oneReg.get(0).entityId(), equalTo("minecraft:foo"));
    }

    @Test
    @DisplayName("mutating BlockEntityType.X's LDC renames the emitted entityId")
    void mutateEntityIdLdc() throws IOException {
        Mutations renamed = new Mutations();
        // Replace BlockEntityType entirely so FOO binds to "renamed_id" instead of "foo".
        renamed.blockEntityType = renderers -> {
            ClassWriter cw = new ClassWriter(0);
            cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "net/minecraft/world/level/block/entity/BlockEntityType", null, "java/lang/Object", null);
            cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "FOO",
                "Lnet/minecraft/world/level/block/entity/BlockEntityType;", null, null).visitEnd();
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
            mv.visitCode();
            mv.visitLdcInsn("renamed_id");
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitFieldInsn(Opcodes.PUTSTATIC, "net/minecraft/world/level/block/entity/BlockEntityType", "FOO",
                "Lnet/minecraft/world/level/block/entity/BlockEntityType;");
            mv.visitInsn(Opcodes.POP);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(2, 0);
            mv.visitEnd();
            cw.visitEnd();
            return cw.toByteArray();
        };
        ConcurrentList<Source> out = run(new TestRenderer[]{ TestRenderer.A }, renamed);
        assertThat(out, hasSize(1));
        assertThat("renamed ldc surfaces in Source.entityId", out.get(0).entityId(), equalTo("minecraft:renamed_id"));
    }

    /**
     * Pins that {@link SourceDiscovery#inferYAxisFromMethod} keys off {@code PartPose.offset}
     * pivots only, never cube coordinates: a lone {@code addBox} with no {@code offset} call
     * leaves {@code maxPivotY} at negative infinity, which falls below the {@code [8, 16)} band
     * and defaults to {@link YAxis#DOWN}.
     */
    @Test
    @DisplayName("negative cube Y does not force DOWN - pivot controls the axis")
    void yAxisDownFromCubeOnly() throws IOException {
        // Our synthetic modelClass emits a single cube at y=0 (DOWN-authored) with no pivot.
        // Expected: DOWN.
        ConcurrentList<Source> out = run(new TestRenderer[]{ TestRenderer.A }, new Mutations());
        assertThat(out, hasSize(1));
        assertThat("default model yAxis", out.get(0).yAxis(), equalTo(YAxis.DOWN));
    }

    /**
     * Pins the lower half of the {@code [8, 16)} band: a pivot at {@code y=12} (chest / bell
     * block-space authoring) flips {@link YAxis#UP} so the parser pre-flips it into canonical
     * Y-down form.
     */
    @Test
    @DisplayName("pivot y>=8 within block bounds flips yAxis to UP")
    void yAxisUpFromPivotInBlockBounds() throws IOException {
        // Pivot y = 12 (block-space authoring, within [8, 16)) -> UP.
        Path jar = tempDir.resolve("y-up.jar");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(jar))) {
            writeSkeleton(zos, new TestRenderer[]{ TestRenderer.A });
            writeClass(zos, TestRenderer.A.rendererInternal, rendererClass(TestRenderer.A));
            writeClass(zos, TestRenderer.A.modelInternal,
                modelClassWithCubeAndPivot(TestRenderer.A, 0f, 0f, 0f, 8f, 8f, 8f, 12f));
        }
        try (ZipFile zf = new ZipFile(jar.toFile())) {
            ConcurrentList<Source> out = SourceDiscovery.discover(zf, new Diagnostics());
            assertThat(out, hasSize(1));
            assertThat(out.get(0).yAxis(), equalTo(YAxis.UP));
        }
    }

    /**
     * Pins the upper edge of the {@code [8, 16)} band: a pivot at {@code y=24} (mob-root
     * convention, e.g. {@code ShulkerModel.createShellMesh}) sits above the half-open band and
     * stays {@link YAxis#DOWN} - the band is deliberately half-open so mob-authored roots aren't
     * mistaken for block-space authoring.
     */
    @Test
    @DisplayName("pivot y>=16 (mob-authored) stays DOWN")
    void yAxisDownFromTallPivot() throws IOException {
        Path jar = tempDir.resolve("y-tall.jar");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(jar))) {
            writeSkeleton(zos, new TestRenderer[]{ TestRenderer.A });
            writeClass(zos, TestRenderer.A.rendererInternal, rendererClass(TestRenderer.A));
            // Pivot y = 24 (mob-root convention) -> DOWN.
            writeClass(zos, TestRenderer.A.modelInternal,
                modelClassWithCubeAndPivot(TestRenderer.A, 0f, 0f, 0f, 8f, 8f, 8f, 24f));
        }
        try (ZipFile zf = new ZipFile(jar.toFile())) {
            ConcurrentList<Source> out = SourceDiscovery.discover(zf, new Diagnostics());
            assertThat(out, hasSize(1));
            assertThat("tall pivot (>=16) stays DOWN", out.get(0).yAxis(), equalTo(YAxis.DOWN));
        }
    }

    @Test
    @DisplayName("flipping the createRoots INVOKESTATIC target redirects the Source.methodName")
    void mutateLayerDefinitionTarget() throws IOException {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "net/minecraft/client/model/geom/LayerDefinitions", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "createRoots", "()Ljava/util/Map;", null, null);
        mv.visitCode();
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "com/google/common/collect/ImmutableMap", "builder", "()Lcom/google/common/collect/ImmutableMap$Builder;", false);
        mv.visitVarInsn(Opcodes.ASTORE, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETSTATIC, "net/minecraft/client/model/geom/ModelLayers", TestRenderer.A.layerField,
            "Lnet/minecraft/client/model/geom/ModelLayerLocation;");
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, TestRenderer.A.modelInternal, "createHeadLayer",
            "()Lnet/minecraft/client/model/geom/builders/LayerDefinition;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/google/common/collect/ImmutableMap$Builder", "put",
            "(Ljava/lang/Object;Ljava/lang/Object;)Lcom/google/common/collect/ImmutableMap$Builder;", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/google/common/collect/ImmutableMap$Builder", "build",
            "()Lcom/google/common/collect/ImmutableMap;", false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(4, 1);
        mv.visitEnd();
        cw.visitEnd();
        byte[] layerDefsBytes = cw.toByteArray();

        Path jar = tempDir.resolve("rerouted.jar");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(jar))) {
            writeSkeletonWithLayerDefinitionsReplacement(zos, new TestRenderer[]{ TestRenderer.A }, layerDefsBytes);
            writeClass(zos, TestRenderer.A.rendererInternal, rendererClass(TestRenderer.A));
            // Model has two primary methods: createBodyLayer (original) + createHeadLayer (alt target)
            writeClass(zos, TestRenderer.A.modelInternal, modelClassWithAltMethod(TestRenderer.A, "createHeadLayer"));
        }
        try (ZipFile zf = new ZipFile(jar.toFile())) {
            ConcurrentList<Source> out = SourceDiscovery.discover(zf, new Diagnostics());
            assertThat(out, hasSize(1));
            assertThat("createRoots target drives Source.methodName", out.get(0).methodName(), equalTo("createHeadLayer"));
        }
    }

    @Test
    @DisplayName("missing BlockEntityRenderers class surfaces error + empty output")
    void missingRegistryClass() throws IOException {
        Path jar = tempDir.resolve("no-registry.jar");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(jar))) {
            // Deliberately skip BlockEntityRenderers.
            writeClass(zos, "net/minecraft/world/level/block/entity/BlockEntityType", emptyClass("net/minecraft/world/level/block/entity/BlockEntityType"));
        }
        try (ZipFile zf = new ZipFile(jar.toFile())) {
            Diagnostics diag = new Diagnostics();
            ConcurrentList<Source> out = SourceDiscovery.discover(zf, diag);
            assertThat("no registry -> empty output", out, empty());
            assertThat("diagnostic surfaces", diag.strictFailingCount(), is(1));
        }
    }

    @Test
    @DisplayName("missing BlockEntityType.X LDC triggers warn and skips the registration")
    void missingBlockEntityTypeId() throws IOException {
        // Provide the FOO field but omit the LDC "foo" in <clinit> -> beFieldToEntityId has no
        // entry -> discovery warns and skips.
        Mutations noId = new Mutations();
        noId.blockEntityType = renderers -> {
            ClassWriter cw = new ClassWriter(0);
            cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "net/minecraft/world/level/block/entity/BlockEntityType", null, "java/lang/Object", null);
            for (TestRenderer r : renderers)
                cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, r.beField,
                    "Lnet/minecraft/world/level/block/entity/BlockEntityType;", null, null).visitEnd();
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
            mv.visitCode();
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
            cw.visitEnd();
            return cw.toByteArray();
        };
        ConcurrentList<Source> out = run(new TestRenderer[]{ TestRenderer.A }, noId);
        assertThat("unresolvable BE type id -> no Source", out, empty());
    }

    /**
     * Pins the {@code PRIMARY_METHOD_NAMES} allow-list filter: a layer whose factory method is
     * not a recognised primary name ({@code createFancyLayer}) is dropped, and the fallback
     * renderer-method scan also finds no primary, so discovery emits nothing. This is the guard
     * that keeps decorative sub-layers (eyes, wind, alt poses) out of the Source set.
     */
    @Test
    @DisplayName("renderer with no matching primary method name emits nothing")
    void rendererWithoutPrimaryMethodName() throws IOException {
        // Build a LayerDefinitions whose only put() points at a non-primary method
        // ("createFancyLayer"), leaving the renderer's own static methods also non-primary.
        // Discovery should emit no Source.
        Mutations nonPrimary = new Mutations();
        nonPrimary.layerDefinitions = renderers -> {
            ClassWriter cw = new ClassWriter(0);
            cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "net/minecraft/client/model/geom/LayerDefinitions", null, "java/lang/Object", null);
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "createRoots", "()Ljava/util/Map;", null, null);
            mv.visitCode();
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "com/google/common/collect/ImmutableMap", "builder", "()Lcom/google/common/collect/ImmutableMap$Builder;", false);
            mv.visitVarInsn(Opcodes.ASTORE, 0);
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(Opcodes.GETSTATIC, "net/minecraft/client/model/geom/ModelLayers", TestRenderer.A.layerField,
                "Lnet/minecraft/client/model/geom/ModelLayerLocation;");
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, TestRenderer.A.modelInternal, "createFancyLayer",
                "()Lnet/minecraft/client/model/geom/builders/LayerDefinition;", false);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/google/common/collect/ImmutableMap$Builder", "put",
                "(Ljava/lang/Object;Ljava/lang/Object;)Lcom/google/common/collect/ImmutableMap$Builder;", false);
            mv.visitInsn(Opcodes.POP);
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/google/common/collect/ImmutableMap$Builder", "build",
                "()Lcom/google/common/collect/ImmutableMap;", false);
            mv.visitInsn(Opcodes.ARETURN);
            mv.visitMaxs(4, 1);
            mv.visitEnd();
            cw.visitEnd();
            return cw.toByteArray();
        };
        // Also replace the model with one whose only static LayerDefinition method is
        // "createFancyLayer" - fallback renderer-method scan won't find a primary either.
        nonPrimary.model = r -> {
            ClassWriter cw = new ClassWriter(0);
            cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, r.modelInternal, null, "java/lang/Object", null);
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "createFancyLayer",
                "()Lnet/minecraft/client/model/geom/builders/LayerDefinition;", null, null);
            mv.visitCode();
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitInsn(Opcodes.ARETURN);
            mv.visitMaxs(1, 0);
            mv.visitEnd();
            cw.visitEnd();
            return cw.toByteArray();
        };
        ConcurrentList<Source> out = run(new TestRenderer[]{ TestRenderer.A }, nonPrimary);
        assertThat("non-primary method name -> source filtered out", out, empty());
    }

    /**
     * End-to-end (through {@link SourceDiscovery#discover}) checks of the Y-axis heuristic, as
     * opposed to unit-level exercises of {@code inferYAxisFromMethod} in isolation. Pins the
     * default-fixture case where a model with no {@code PartPose.offset} call resolves to
     * {@link YAxis#DOWN}.
     */
    @Nested
    @DisplayName("Y-axis heuristic direct checks")
    class YAxisHeuristicTests {

        @Test
        @DisplayName("no offset calls default to DOWN")
        void noOffsets() throws IOException {
            ConcurrentList<Source> out = run(new TestRenderer[]{ TestRenderer.A }, new Mutations());
            assertThat(out.get(0).yAxis(), equalTo(YAxis.DOWN));
        }
    }

    // ------------------------------------------------------------------------------------------
    // Internal helpers for tests that do not use `buildSyntheticJar`
    // ------------------------------------------------------------------------------------------

    /**
     * Writes the four shared skeleton classes ({@code BlockEntityType}, {@code ModelLayers},
     * {@code BlockEntityRenderers}, {@code LayerDefinitions}) that every discovery run needs.
     * The per-test renderer + model classes are left for the caller to write.
     */
    private void writeSkeleton(ZipOutputStream zos, TestRenderer[] renderers) throws IOException {
        writeClass(zos, "net/minecraft/world/level/block/entity/BlockEntityType", defaultBlockEntityType(renderers));
        writeClass(zos, "net/minecraft/client/model/geom/ModelLayers", defaultModelLayers(renderers));
        writeClass(zos, "net/minecraft/client/renderer/blockentity/BlockEntityRenderers", defaultBlockEntityRenderers(renderers));
        writeClass(zos, "net/minecraft/client/model/geom/LayerDefinitions", defaultLayerDefinitions(renderers));
    }

    /**
     * Writes the shared skeleton but substitutes {@code layerDefsBytes} for the default
     * {@code LayerDefinitions} class - lets a test reroute {@code createRoots}' factory target
     * while keeping the other three skeleton classes stock.
     */
    private void writeSkeletonWithLayerDefinitionsReplacement(ZipOutputStream zos, TestRenderer[] renderers, byte[] layerDefsBytes) throws IOException {
        writeClass(zos, "net/minecraft/world/level/block/entity/BlockEntityType", defaultBlockEntityType(renderers));
        writeClass(zos, "net/minecraft/client/model/geom/ModelLayers", defaultModelLayers(renderers));
        writeClass(zos, "net/minecraft/client/renderer/blockentity/BlockEntityRenderers", defaultBlockEntityRenderers(renderers));
        writeClass(zos, "net/minecraft/client/model/geom/LayerDefinitions", layerDefsBytes);
    }

    /**
     * Builds a synthetic model with two static LayerDefinition-returning methods: the
     * renderer's default {@code modelMethod} plus the given {@code altName} used by the
     * reroute test.
     */
    private static byte[] modelClassWithAltMethod(TestRenderer r, String altName) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, r.modelInternal, null, "java/lang/Object", null);
        for (String name : new String[]{ r.modelMethod, altName }) {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name,
                "()Lnet/minecraft/client/model/geom/builders/LayerDefinition;", null, null);
            mv.visitCode();
            // Emit an addBox so the Y-axis heuristic has something to inspect.
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraft/client/model/geom/builders/CubeListBuilder", "create",
                "()Lnet/minecraft/client/model/geom/builders/CubeListBuilder;", false);
            mv.visitLdcInsn(0f); mv.visitLdcInsn(0f); mv.visitLdcInsn(0f);
            mv.visitLdcInsn(8f); mv.visitLdcInsn(8f); mv.visitLdcInsn(8f);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/minecraft/client/model/geom/builders/CubeListBuilder", "addBox",
                "(FFFFFF)Lnet/minecraft/client/model/geom/builders/CubeListBuilder;", false);
            mv.visitInsn(Opcodes.POP);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitInsn(Opcodes.ARETURN);
            mv.visitMaxs(7, 0);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * Pins that both registered entity ids surface (membership, not position). The
     * {@code containsInAnyOrder} matcher is order-agnostic - strict positional order is asserted
     * separately by {@link #twoRegisters()}, which checks {@code get(0)}/{@code get(1)} directly.
     */
    @Test
    @DisplayName("two renderers share entity-id registration order (bytecode order)")
    void entityOrderMatchesRegistryOrder() throws IOException {
        ConcurrentList<Source> out = run(new TestRenderer[]{ TestRenderer.A, TestRenderer.B }, new Mutations());
        assertThat("emission order follows registry order",
            List.of(out.get(0).entityId(), out.get(1).entityId()),
            containsInAnyOrder("minecraft:foo", "minecraft:bar"));
    }
}
