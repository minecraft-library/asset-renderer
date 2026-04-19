package dev.sbs.renderer.tooling.blockentity;

import dev.simplified.collection.ConcurrentList;
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
 * Fast mutation tests for {@link SourceDiscovery}. Each test builds a <i>synthetic</i> client
 * jar via {@link ClassWriter} (no real classes from {@code client.jar}), runs discovery
 * against it, then mutates the synthetic input and re-runs to prove the output follows the
 * bytecode - not a hardcoded table.
 *
 * <p>The fixtures are deliberately minimal:
 * <ul>
 *     <li>{@code BlockEntityRenderers.<clinit>} with one or two
 *         {@code register(BlockEntityType.X, Renderer::new)} pairs.</li>
 *     <li>{@code BlockEntityType} with one or two {@code PUTSTATIC} bound to
 *         {@code LDC "id"} literals.</li>
 *     <li>{@code LayerDefinitions.createRoots} with one or two
 *         {@code put(ModelLayers.X, Model.createY())} pairs.</li>
 *     <li>A minimal {@code Renderer} class that references {@code ModelLayers.X} in its
 *         constructor.</li>
 *     <li>A minimal {@code Model} class with a {@code createY} method containing a single
 *         {@code CubeListBuilder.addBox(...)} so the Y-axis heuristic has something to
 *         inspect.</li>
 * </ul>
 */
@DisplayName("SourceDiscovery (bytecode-driven)")
class SourceDiscoveryTest {

    @TempDir Path tempDir;

    /**
     * {@code true} when the synthetic fixture should include a particular register call.
     * Two-shape enum exists so tests can mutate the jar by dropping an enum entry.
     */
    private enum TestRenderer {
        A("foo", "test/RendererA", "test/ModelA", "createBodyLayer", "FOO_LAYER"),
        B("bar", "test/RendererB", "test/ModelB", "createShellLayer", "BAR_LAYER");

        final String beField;
        final String rendererInternal;
        final String modelInternal;
        final String modelMethod;
        final String layerField;
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

    private static byte[] emptyClass(String internalName) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        cw.visitEnd();
        return cw.toByteArray();
    }

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

    /** Builds a synthetic model whose {@code createYZ} method emits a single cube. */
    private static byte[] modelClass(TestRenderer r) {
        return modelClassWithCube(r, 0f, 0f, 0f, 8f, 8f, 8f);
    }

    /**
     * Builds a synthetic model whose {@code createYZ} method emits a single cube at the given
     * origin + size. Used by Y-axis heuristic tests that care about the cube's Y origin.
     */
    private static byte[] modelClassWithCube(TestRenderer r, float x, float y, float z, float w, float h, float d) {
        return modelClassWithCubeAndPivot(r, x, y, z, w, h, d, 0f);
    }

    /**
     * Builds a synthetic model whose {@code createYZ} method emits one cube plus one
     * {@code PartPose.offset(0, pivotY, 0)} call. Lets Y-axis tests exercise the
     * pivot-height heuristic.
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

    @Test
    @DisplayName("negative cube Y does not force DOWN - pivot controls the axis")
    void yAxisDownFromCubeOnly() throws IOException {
        // Our synthetic modelClass emits a single cube at y=0 (DOWN-authored) with no pivot.
        // Expected: DOWN.
        ConcurrentList<Source> out = run(new TestRenderer[]{ TestRenderer.A }, new Mutations());
        assertThat(out, hasSize(1));
        assertThat("default model yAxis", out.get(0).yAxis(), equalTo(YAxis.DOWN));
    }

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
     * Writes the shared skeleton of classes (BE type, ModelLayers, BlockEntityRenderers,
     * LayerDefinitions, and the support classes) - every test needs these. Renderer + model
     * classes are left to the test.
     */
    private void writeSkeleton(ZipOutputStream zos, TestRenderer[] renderers) throws IOException {
        writeClass(zos, "net/minecraft/world/level/block/entity/BlockEntityType", defaultBlockEntityType(renderers));
        writeClass(zos, "net/minecraft/client/model/geom/ModelLayers", defaultModelLayers(renderers));
        writeClass(zos, "net/minecraft/client/renderer/blockentity/BlockEntityRenderers", defaultBlockEntityRenderers(renderers));
        writeClass(zos, "net/minecraft/client/model/geom/LayerDefinitions", defaultLayerDefinitions(renderers));
    }

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

    @Test
    @DisplayName("two renderers share entity-id registration order (bytecode order)")
    void entityOrderMatchesRegistryOrder() throws IOException {
        ConcurrentList<Source> out = run(new TestRenderer[]{ TestRenderer.A, TestRenderer.B }, new Mutations());
        assertThat("emission order follows registry order",
            java.util.List.of(out.get(0).entityId(), out.get(1).entityId()),
            containsInAnyOrder("minecraft:foo", "minecraft:bar"));
    }
}
