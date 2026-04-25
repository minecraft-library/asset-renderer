package lib.minecraft.renderer.tooling.entity;

import lib.minecraft.renderer.tooling.util.AsmKit;
import lib.minecraft.renderer.tooling.util.Diagnostics;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipFile;

/**
 * Bytecode-driven discovery of the static bone rotations each vanilla mob model applies in its
 * mesh definition. Bedrock's modern (1.21+) {@code .geo.json} files dropped
 * {@code bind_pose_rotation} in favour of Molang animations, so a static renderer that doesn't
 * execute Bedrock's animation engine sees raw vertical quadruped bodies. Java Edition still
 * hardcodes those rotations in {@code Model.createBodyLayer} methods via
 * {@code PartPose.offsetAndRotation(FFFFFF)} / {@code PartPose.rotation(FFF)} - scraping them
 * via ASM yields a bind-pose table that stands in for the animation system.
 *
 * <p>Three walks feed the output:
 * <ol>
 *   <li><b>{@code EntityRenderers.<clinit>} scan</b>. Each
 *       {@code register(EntityType.X, (BiFunction) XxxRenderer::new)} pair compiles to
 *       {@code GETSTATIC EntityType.X} followed by {@code INVOKEDYNAMIC <lambda>}. The
 *       {@code invokedynamic} bootstrap method handle names the target renderer class
 *       directly.</li>
 *   <li><b>Renderer constructor scan</b>. Each renderer's {@code <init>} contains a
 *       {@code NEW <Model>} + {@code INVOKESPECIAL <Model>.<init>} sequence - that pair names
 *       the primary {@code Model} class the renderer uses at runtime.</li>
 *   <li><b>Model {@code createBodyLayer} scan</b>. Vanilla 1.21+ models build their mesh in a
 *       static factory. Walking its instruction stream, we accumulate {@code LDC String} bone
 *       names and {@code LDC float} pose literals; when we hit
 *       {@code INVOKESTATIC PartPose.offsetAndRotation(FFFFFF)} we pop the last six floats into
 *       {@code (x, y, z, xRot, yRot, zRot)}; when we hit
 *       {@code INVOKEVIRTUAL addOrReplaceChild(String, CubeListBuilder, PartPose)} we attach
 *       the most-recent pose to the most-recent child name. Bone names come straight from the
 *       string literal and match Bedrock's {@code .geo.json} bone keys for every vanilla mob.</li>
 * </ol>
 *
 * <p>Rotations are converted from radians to degrees at emission time. Output is a map
 * {@code entity_id -> bone_name -> (pitch, yaw, roll) degrees}. Entities with no discoverable
 * rotations (humanoid, armour stand, the zombie family, etc.) drop out.
 */
@UtilityClass
public final class BindPoseDiscovery {

    /** JVM internal name of {@code net.minecraft.client.renderer.entity.EntityRenderers}. */
    private static final @NotNull String ENTITY_RENDERERS = "net/minecraft/client/renderer/entity/EntityRenderers";

    /** JVM internal name of {@code net.minecraft.world.entity.EntityType}. */
    private static final @NotNull String ENTITY_TYPE = "net/minecraft/world/entity/EntityType";

    /** JVM internal name of {@code net.minecraft.client.model.geom.PartPose}. */
    private static final @NotNull String PART_POSE = "net/minecraft/client/model/geom/PartPose";

    /** JVM internal name of {@code net.minecraft.client.model.geom.builders.PartDefinition}. */
    private static final @NotNull String PART_DEFINITION = "net/minecraft/client/model/geom/builders/PartDefinition";

    /** Renderer {@code register} method name on {@code EntityRenderers}. */
    private static final @NotNull String REGISTER = "register";

    /** Prefix that characterises any Model subclass in the vanilla deobfuscated jar. */
    private static final @NotNull String MODEL_PACKAGE_PREFIX = "net/minecraft/client/model/";

    /** Prefix that characterises any renderer class in the vanilla deobfuscated jar. */
    private static final @NotNull String RENDERER_PACKAGE_PREFIX = "net/minecraft/client/renderer/entity/";

    /**
     * One bone's discovered rest pose: three rotation axes in degrees plus the pivot position
     * the rotation anchors on. The pivot lets the runtime loader match a Java bind-pose to a
     * Bedrock bone by position even when the bone names disagree - Java's spider legs are
     * {@code left_front_leg} / {@code right_middle_hind_leg} / etc., while Bedrock's are
     * {@code leg0} through {@code leg7}, so a pivot-based fallback is the only way to pair
     * them up without a hand-rolled dictionary.
     *
     * @param pitch pitch (rotation about X) in degrees
     * @param yaw yaw (rotation about Y) in degrees
     * @param roll roll (rotation about Z) in degrees
     * @param pivotX pivot X in entity-root units (matches Bedrock's bone pivot space for
     *     root-level bones; nested bones carry parent-local values which may drift from the
     *     Bedrock pivot by the parent's offset)
     * @param pivotY pivot Y in entity-root units
     * @param pivotZ pivot Z in entity-root units
     */
    public record Pose(float pitch, float yaw, float roll, float pivotX, float pivotY, float pivotZ) {

        /** Returns {@code true} when all three rotation axes are zero within a sub-degree epsilon. */
        public boolean isZero() {
            return Math.abs(this.pitch) < 1e-3f && Math.abs(this.yaw) < 1e-3f && Math.abs(this.roll) < 1e-3f;
        }
    }

    /**
     * Scans the client jar and returns {@code entity_id -> bone -> Pose} for every discoverable
     * mob. Entities with no non-zero bone rotations are omitted - only entries that would
     * actually change rendering reach the output map.
     *
     * @param zip the deobfuscated client jar
     * @param mobs the {@link MobRegistryDiscovery} output the caller already has on hand
     * @param diagnostics the diagnostic sink for registration patterns that can't be parsed
     * @return an ordered map keyed by namespace-free entity id
     */
    public static @NotNull Map<String, Map<String, Pose>> discover(
        @NotNull ZipFile zip,
        @NotNull Iterable<MobRegistryDiscovery.MobEntry> mobs,
        @NotNull Diagnostics diagnostics
    ) {
        Map<String, String> entityTypeToRenderer = collectRendererMap(zip, diagnostics);
        diagnostics.info("EntityRenderers mapped %d entity types to renderer classes", entityTypeToRenderer.size());

        // Per-model cache so a model class shared across entities (horse family, skeleton
        // family) only gets scanned once.
        Map<String, Map<String, Pose>> poseCache = new LinkedHashMap<>();

        Map<String, Map<String, Pose>> out = new LinkedHashMap<>();
        int noRenderer = 0, noModel = 0, noPose = 0;
        for (MobRegistryDiscovery.MobEntry mob : mobs) {
            String renderer = entityTypeToRenderer.get(mob.fieldName());
            if (renderer == null) { noRenderer++; continue; }

            String modelClass = findModelClass(zip, renderer);
            if (modelClass == null) { noModel++; continue; }

            Map<String, Pose> poses = poseCache.computeIfAbsent(modelClass, cls -> scanModel(zip, cls));
            if (poses.isEmpty()) { noPose++; continue; }

            out.put(mob.entityId(), poses);
        }
        diagnostics.info("Bind-pose tally: kept=%d, noRenderer=%d, noModel=%d, noPose=%d",
            out.size(), noRenderer, noModel, noPose);
        return out;
    }

    /**
     * Walks {@code EntityRenderers.<clinit>} and returns {@code EntityType_field -> RendererClass}.
     * Follows two registration patterns:
     * <ul>
     *   <li><b>Direct constructor reference</b> ({@code H_NEWINVOKESPECIAL}) - the handle's
     *       owner is the renderer class. Used by single-arg renderers like
     *       {@code AllayRenderer::new}.</li>
     *   <li><b>Static lambda indirection</b> ({@code H_INVOKESTATIC} targeting
     *       {@code EntityRenderers.lambda$static$N}) - the lambda body contains
     *       {@code NEW XxxRenderer} for multi-arg constructors like
     *       {@code DonkeyRenderer(context, layerType, layer, type, babyType)}. Follow the
     *       lambda into its instruction stream and pull the first {@code NEW} targeting the
     *       renderer package.</li>
     * </ul>
     */
    private static @NotNull Map<String, String> collectRendererMap(
        @NotNull ZipFile zip,
        @NotNull Diagnostics diagnostics
    ) {
        ClassNode renderers = AsmKit.loadClass(zip, ENTITY_RENDERERS);
        if (renderers == null) {
            diagnostics.error("Missing %s - bind poses cannot be derived", ENTITY_RENDERERS);
            return Map.of();
        }

        Map<String, String> out = new LinkedHashMap<>();
        for (MethodNode method : renderers.methods)
            collectRendererMapFromMethod(renderers, method, out);
        return out;
    }

    /**
     * Scans one method for {@code EntityRenderers.register(EntityType.X, factory)} call
     * sequences and populates {@code out} with every {@code X -> RendererClass} pair that can
     * be decoded. The {@code renderers} class node is passed in so lambda indirections can be
     * resolved against its own method table without re-loading the class.
     */
    private static void collectRendererMapFromMethod(
        @NotNull ClassNode renderers,
        @NotNull MethodNode method,
        @NotNull Map<String, String> out
    ) {
        String pendingFieldName = null;
        String pendingRenderer = null;

        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() == Opcodes.GETSTATIC
                && insn instanceof FieldInsnNode field
                && ENTITY_TYPE.equals(field.owner)) {
                pendingFieldName = field.name;
                pendingRenderer = null;
                continue;
            }

            if (insn.getOpcode() == Opcodes.INVOKEDYNAMIC
                && insn instanceof InvokeDynamicInsnNode dyn) {
                pendingRenderer = resolveRendererFromBsm(dyn, renderers);
                continue;
            }

            if (insn.getOpcode() == Opcodes.INVOKESTATIC
                && insn instanceof MethodInsnNode call
                && ENTITY_RENDERERS.equals(call.owner)
                && REGISTER.equals(call.name)) {
                if (pendingFieldName != null && pendingRenderer != null)
                    out.putIfAbsent(pendingFieldName, pendingRenderer);
                pendingFieldName = null;
                pendingRenderer = null;
            }
        }
    }

    /**
     * Resolves the renderer class an {@code invokedynamic} lambda constructs. Handles the two
     * vanilla patterns: {@code H_NEWINVOKESPECIAL} (direct constructor reference) names the
     * renderer directly; {@code H_INVOKESTATIC} on an {@code EntityRenderers.lambda$static$N}
     * method requires following the lambda body's first {@code NEW <RendererClass>} to pin
     * down the concrete renderer. Returns {@code null} when neither pattern applies.
     */
    private static @Nullable String resolveRendererFromBsm(
        @NotNull InvokeDynamicInsnNode dyn,
        @NotNull ClassNode renderers
    ) {
        for (Object arg : dyn.bsmArgs) {
            if (!(arg instanceof Handle h)) continue;
            if (h.getTag() == Opcodes.H_NEWINVOKESPECIAL)
                return h.getOwner();
            if (h.getTag() == Opcodes.H_INVOKESTATIC && ENTITY_RENDERERS.equals(h.getOwner())) {
                MethodNode lambda = AsmKit.findMethod(renderers, h.getName(), h.getDesc());
                if (lambda == null) continue;
                for (AbstractInsnNode li = lambda.instructions.getFirst(); li != null; li = li.getNext()) {
                    if (li.getOpcode() == Opcodes.NEW
                        && li instanceof TypeInsnNode typeInsn
                        && typeInsn.desc.startsWith(RENDERER_PACKAGE_PREFIX)) {
                        return typeInsn.desc;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Finds the primary {@code Model} class a renderer instantiates. Walks the renderer's
     * methods (typically {@code <init>}) looking for a {@code NEW net/minecraft/client/model/...}
     * instruction. Returns the first such class name - Minecraft renderers that layer multiple
     * models (wolf collar over body, player inner+outer) list their primary body model first.
     * <p>
     * Walks the superclass chain when the renderer's own bytecode has no direct {@code NEW}.
     * {@code CaveSpiderRenderer}'s constructor only calls {@code super()} into
     * {@code SpiderRenderer}, and the {@code NEW SpiderModel} instruction lives in the
     * parent's constructor. Without this walk cave_spider's bind-pose entry wouldn't
     * emit.
     * <p>
     * Returns {@code null} when nothing in the chain references a Model class.
     */
    private static @Nullable String findModelClass(@NotNull ZipFile zip, @NotNull String rendererClass) {
        String current = rendererClass;
        while (current != null && current.startsWith(RENDERER_PACKAGE_PREFIX)) {
            ClassNode renderer = AsmKit.loadClass(zip, current);
            if (renderer == null) return null;

            for (MethodNode method : renderer.methods) {
                for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (insn.getOpcode() == Opcodes.NEW
                        && insn instanceof TypeInsnNode typeInsn
                        && typeInsn.desc.startsWith(MODEL_PACKAGE_PREFIX)) {
                        return typeInsn.desc;
                    }
                }
            }
            current = renderer.superName;
        }
        return null;
    }

    /**
     * Scans a Model class (and its superclass chain, up to the model package boundary) for
     * static {@code createXxxLayer}-style methods and extracts per-bone poses. Returns
     * {@code bone -> Pose} with rotations in degrees; bones without a non-zero rotation drop
     * out but their pivots are still captured for completeness.
     */
    private static @NotNull Map<String, Pose> scanModel(@NotNull ZipFile zip, @NotNull String modelClass) {
        // Each entry: [pivotX, pivotY, pivotZ, xRot_rad, yRot_rad, zRot_rad].
        Map<String, float[]> rawData = new LinkedHashMap<>();

        String current = modelClass;
        while (current != null && current.startsWith(MODEL_PACKAGE_PREFIX)) {
            ClassNode classNode = AsmKit.loadClass(zip, current);
            if (classNode == null) break;

            for (MethodNode method : classNode.methods)
                scanMeshMethod(method, rawData);
            current = classNode.superName;
        }

        Map<String, Pose> out = new LinkedHashMap<>();
        for (Map.Entry<String, float[]> entry : rawData.entrySet()) {
            float[] v = entry.getValue();
            Pose pose = new Pose(
                (float) Math.toDegrees(v[3]),
                (float) Math.toDegrees(v[4]),
                (float) Math.toDegrees(v[5]),
                v[0], v[1], v[2]
            );
            if (!pose.isZero())
                out.put(entry.getKey(), pose);
        }
        return out;
    }

    /**
     * Walks one method's instruction stream correlating
     * {@code PartPose.offsetAndRotation(FFFFFF)} / {@code PartPose.rotation(FFF)} /
     * {@code PartPose.offset(FFF)} factory calls with the {@code addOrReplaceChild(String,
     * CubeListBuilder, PartPose)} (or {@code addChild}) that consumes them. Populates
     * {@code rawData} with {@code bone -> [pivotX, pivotY, pivotZ, xRot, yRot, zRot]} in
     * radians for the rotation components; the pivot components are in the Java client's
     * native units (which match Bedrock's entity-root units for root-level bones).
     * <p>
     * The scan uses a small rolling stack of floats so pose arguments are recovered even when
     * the bytecode between them contains unrelated literal pushes. Method-scoped string
     * tracking holds the most recent bone-name candidate - vanilla models always push the bone
     * name literal immediately before the cube-list / pose factory sequence, so the pairing is
     * unambiguous.
     */
    private static void scanMeshMethod(
        @NotNull MethodNode method,
        @NotNull Map<String, float[]> rawData
    ) {
        // Skip obvious non-mesh-builder methods so we don't scrape transient temporaries.
        // Valid mesh-builder methods are static and return either {@code MeshDefinition}
        // (classic signature) or {@code LayerDefinition} (modern wrapper that internally
        // constructs the mesh and sizes the texture - most 1.21+ models use this form).
        if ((method.access & Opcodes.ACC_STATIC) == 0) return;
        boolean returnsMesh = method.desc.endsWith(")Lnet/minecraft/client/model/geom/builders/MeshDefinition;");
        boolean returnsLayer = method.desc.endsWith(")Lnet/minecraft/client/model/geom/builders/LayerDefinition;");
        if (!returnsMesh && !returnsLayer) return;

        java.util.Deque<Float> floats = new java.util.ArrayDeque<>();
        String pendingBone = null;
        float[] pendingPose = null;

        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            // String push: bone-name candidate. Replace any earlier candidate - vanilla models
            // always push the name immediately before the factory chain.
            if (insn.getOpcode() == Opcodes.LDC
                && insn instanceof org.objectweb.asm.tree.LdcInsnNode ldc
                && ldc.cst instanceof String s) {
                pendingBone = s;
                pendingPose = null;
                continue;
            }

            Float f = AsmKit.readFloatLiteral(insn);
            if (f != null) {
                floats.push(f);
                continue;
            }

            if (insn.getOpcode() != Opcodes.INVOKESTATIC && insn.getOpcode() != Opcodes.INVOKEVIRTUAL) continue;
            if (!(insn instanceof MethodInsnNode call)) continue;

            // PartPose.offsetAndRotation(FFFFFF) - six floats: offset (3) + rotation (3).
            if (PART_POSE.equals(call.owner) && "offsetAndRotation".equals(call.name)
                && "(FFFFFF)Lnet/minecraft/client/model/geom/PartPose;".equals(call.desc)) {
                pendingPose = popFloats(floats, 6);
                continue;
            }

            // PartPose.rotation(FFF) - three floats, rotation only. Pivot is unknown; zero-fill.
            if (PART_POSE.equals(call.owner) && "rotation".equals(call.name)
                && "(FFF)Lnet/minecraft/client/model/geom/PartPose;".equals(call.desc)) {
                float[] rot = popFloats(floats, 3);
                if (rot != null)
                    pendingPose = new float[]{ 0f, 0f, 0f, rot[0], rot[1], rot[2] };
                continue;
            }

            // PartPose.offset(FFF) - offset only; no rotation. Capture the pivot for potential
            // positional matching (rotation-less bones drop out at emission time).
            if (PART_POSE.equals(call.owner) && "offset".equals(call.name)
                && "(FFF)Lnet/minecraft/client/model/geom/PartPose;".equals(call.desc)) {
                float[] off = popFloats(floats, 3);
                pendingPose = off == null
                    ? null
                    : new float[]{ off[0], off[1], off[2], 0f, 0f, 0f };
                continue;
            }

            // addOrReplaceChild / addChild - latches the most recent (bone_name, pose) pair.
            if (PART_DEFINITION.equals(call.owner)
                && ("addOrReplaceChild".equals(call.name) || "addChild".equals(call.name))
                && pendingBone != null) {
                if (pendingPose != null) {
                    rawData.put(pendingBone, pendingPose);
                }
                pendingBone = null;
                pendingPose = null;
                // Reset the float stack too - any leftover floats belonged to the consumed
                // sub-expression and shouldn't leak into the next bone's pose.
                floats.clear();
                continue;
            }

            // Any other invoke consumes its stack contribution; keep the float stack pruned
            // so a sequence like `addBox(6 floats)` doesn't strand six floats for the next
            // PartPose call to pick up. We don't know the exact count without parsing
            // descriptors, so the heuristic is: on every unrelated invoke, clear the stack.
            floats.clear();
        }
    }

    /**
     * Pops up to {@code count} floats off {@code stack} into an array, LIFO-to-index order
     * reversed so index 0 is the float that was pushed first (the {@code x} argument, say).
     * Returns {@code null} when the stack didn't have enough entries.
     */
    private static float @Nullable [] popFloats(@NotNull java.util.Deque<Float> stack, int count) {
        if (stack.size() < count) return null;
        float[] out = new float[count];
        for (int i = count - 1; i >= 0; i--) out[i] = stack.pop();
        return out;
    }

}
