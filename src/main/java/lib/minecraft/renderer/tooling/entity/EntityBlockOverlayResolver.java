package lib.minecraft.renderer.tooling.entity;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import lib.minecraft.renderer.tooling.util.AsmKit;
import lib.minecraft.renderer.tooling.util.Diagnostics;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipFile;

/**
 * Bytecode-driven discovery of block-model overlays attached to entity renderers via
 * {@code addLayer(new XLayer(...))} where {@code XLayer} renders a vanilla block model on top
 * of the entity body (mooshroom mushrooms via {@code MushroomCowMushroomLayer}, iron golem
 * poppy via {@code IronGolemFlowerLayer}, enderman carried block via {@code CarriedBlockLayer},
 * generic via {@code BlockDecorationLayer}).
 *
 * <p>Generalises what {@link lib.minecraft.renderer.tooling.ToolingEntityModels}
 * previously hardcoded for mooshroom: walks the renderer constructor for {@code addLayer(new
 * RecognisedLayer(this[, args]))} dispatches, then walks the matched layer's {@code submit}
 * method for each {@code pushPose / popPose} pair, extracting the pose-stack ops issued
 * between them. Each pair becomes one {@link BlockOverlayDescriptor} - one block-overlay row
 * in {@code entity_models.json}.
 *
 * <p>Op extraction recognises:
 * <ul>
 *   <li>{@code pose.translate(F, F, F)} - emitted as {@link OpKind#TRANSLATE}.</li>
 *   <li>{@code pose.scale(F, F, F)} - emitted as {@link OpKind#SCALE}.</li>
 *   <li>{@code pose.mulPose(Axis.YP.rotationDegrees(F))} - emitted as {@link OpKind#ROTATE_Y}.
 *       Other axes / quaternion paths are skipped (vanilla block-overlay layers all use Y).</li>
 *   <li>{@code parent.getX().translateAndRotate(pose)} - flagged on the descriptor as
 *       {@code attachedBone="X"}; the pose-stack equivalent of the bone's pivot translate +
 *       rotation is reconstructed at render time.</li>
 * </ul>
 *
 * <p>Block id determination is deferred for the per-layer renderstate-driven path (mooshroom's
 * {@code variant.getBlockState()}); this resolver hardcodes the canonical default per layer
 * (RED for mooshroom -> {@code minecraft:red_mushroom}, etc) until a future pass walks the
 * variant enum's getBlockState path.
 */
@UtilityClass
public final class EntityBlockOverlayResolver {

    /** JVM internal name of {@code PoseStack} - layer submit methods invoke its push / translate / rotate / scale ops. */
    private static final @NotNull String POSE_STACK = "com/mojang/blaze3d/vertex/PoseStack";

    /** JVM internal name of {@code com.mojang.math.Axis} - its {@code YP} static field's rotation methods are how layers express Y rotations. */
    private static final @NotNull String AXIS = "com/mojang/math/Axis";

    /** JVM internal name of {@code ModelPart} - {@code translateAndRotate} pre-applies a bone's pose to the stack. */
    private static final @NotNull String MODEL_PART = "net/minecraft/client/model/geom/ModelPart";

    /**
     * Recognised block-decoration layer classes mapped to the metadata needed to resolve their
     * default block id at parse time. Each layer class points to the
     * {@code <Entity>$Variant} enum class whose {@code DEFAULT} field's BlockState backs the
     * default-rendered block; the resolver walks that enum's {@code <clinit>} to extract the
     * mapping. Layers whose block id is invariant (iron-golem poppy, etc) carry an empty
     * variant class and a literal {@code defaultBlockId} fallback.
     */
    private static final @NotNull Map<String, KnownLayer> KNOWN_LAYERS = new LinkedHashMap<>() {{
        put("net/minecraft/client/renderer/entity/layers/MushroomCowMushroomLayer",
            new KnownLayer("net/minecraft/world/entity/animal/cow/MushroomCow$Variant", null));
    }};

    /** JVM internal name of {@code Blocks} - the {@code <Variant>$<clinit>} GETSTATICs go through this. */
    private static final @NotNull String BLOCKS = "net/minecraft/world/level/block/Blocks";

    /**
     * Resolves the block-overlay descriptors attached to an entity's renderer via recognised
     * block-decoration layer classes. Returns an empty list when the renderer has no recognised
     * layers - the common case (only ~4 vanilla entities have block-decoration layers).
     *
     * @param zip the deobfuscated client jar
     * @param entityId the entity id being resolved (used in diagnostics)
     * @param rendererInternalName the renderer class JVM internal name (e.g.
     *     {@code net/minecraft/client/renderer/entity/MushroomCowRenderer})
     * @param diagnostics the diagnostic sink for parse-failure WARN messages
     */
    public static @NotNull ConcurrentList<BlockOverlayDescriptor> resolve(
        @NotNull ZipFile zip,
        @NotNull String entityId,
        @NotNull String rendererInternalName,
        @NotNull Diagnostics diagnostics
    ) {
        ConcurrentList<BlockOverlayDescriptor> out = Concurrent.newList();
        ClassNode renderer = AsmKit.loadClass(zip, rendererInternalName);
        if (renderer == null) return out;

        // Walk the renderer's <init> for {@code new XLayer; ... ; addLayer} patterns.
        // Multiple layers may be attached - one new instruction per recognised layer class.
        MethodNode init = AsmKit.findMethod(renderer, "<init>");
        if (init == null) return out;

        for (AbstractInsnNode node = init.instructions.getFirst(); node != null; node = node.getNext()) {
            if (!(node instanceof TypeInsnNode typeInsn) || typeInsn.getOpcode() != Opcodes.NEW) continue;
            String layerInternalName = typeInsn.desc;
            KnownLayer layerInfo = KNOWN_LAYERS.get(layerInternalName);
            if (layerInfo == null) continue;

            String defaultBlockId = resolveDefaultBlockId(zip, layerInfo, entityId, diagnostics);
            if (defaultBlockId == null) {
                diagnostics.warn("%s: layer '%s' default block id could not be resolved (variant class '%s')",
                    entityId, layerInternalName, layerInfo.variantClass());
                continue;
            }

            ClassNode layerClass = AsmKit.loadClass(zip, layerInternalName);
            if (layerClass == null) {
                diagnostics.warn("%s: layer class '%s' not found in client jar", entityId, layerInternalName);
                continue;
            }
            // The submit overload that takes a typed RenderState is the meaningful one (the
            // EntityRenderState overload just delegates). Match by descriptor: it carries the
            // entity-specific RenderState class; the EntityRenderState overload doesn't.
            MethodNode submitMethod = findSubmitMethod(layerClass);
            if (submitMethod == null) {
                diagnostics.warn("%s: layer '%s' has no recognised submit method", entityId, layerInternalName);
                continue;
            }

            List<BlockOverlayDescriptor> extracted = extractPoseBlocks(submitMethod, defaultBlockId);
            out.addAll(extracted);
        }
        return out;
    }

    /**
     * Returns the typed-RenderState {@code submit} overload of a {@code RenderLayer} subclass.
     * Each layer overrides the abstract {@code submit(PoseStack, SubmitNodeCollector, int, S, F, F)}
     * where {@code S} is the entity-specific RenderState; the {@code EntityRenderState} overload
     * just delegates and contains no pose-stack literals. The typed overload's descriptor
     * contains the entity-specific state class name (which never equals
     * {@code EntityRenderState}); pick that one.
     */
    private static @Nullable MethodNode findSubmitMethod(@NotNull ClassNode layerClass) {
        for (MethodNode method : layerClass.methods) {
            if (!"submit".equals(method.name)) continue;
            if (method.desc.contains("EntityRenderState;")) continue;
            return method;
        }
        return null;
    }

    /**
     * Walks a layer's submit method, splitting on {@code pushPose} / {@code popPose} pairs and
     * collecting the recognised pose-stack ops issued in each. Each pair becomes one
     * {@link BlockOverlayDescriptor}. Ops are stored in bytecode order; consumer (the renderer)
     * applies them in the order vanilla's PoseStack would.
     */
    private static @NotNull List<BlockOverlayDescriptor> extractPoseBlocks(
        @NotNull MethodNode submit,
        @NotNull String blockId
    ) {
        List<BlockOverlayDescriptor> out = new ArrayList<>();
        boolean insideBlock = false;
        List<TransformOpRecord> currentOps = new ArrayList<>();
        String currentAttachedBone = null;
        // Track recent literal floats so a pose-stack op consumes the most-recent N floats.
        List<Float> floatStack = new ArrayList<>();

        for (AbstractInsnNode node = submit.instructions.getFirst(); node != null; node = node.getNext()) {
            // Track LDC float / fconst_N pushes.
            Float literal = AsmKit.readFloatLiteral(node);
            if (literal != null) {
                floatStack.add(literal);
                continue;
            }
            // Handle method invocations on the pose stack and adjacent calls.
            if (!(node instanceof MethodInsnNode methodInsn)) continue;

            if (methodInsn.owner.equals(POSE_STACK) && methodInsn.name.equals("pushPose")) {
                insideBlock = true;
                currentOps = new ArrayList<>();
                currentAttachedBone = null;
                floatStack.clear();
                continue;
            }
            if (methodInsn.owner.equals(POSE_STACK) && methodInsn.name.equals("popPose")) {
                if (insideBlock && !currentOps.isEmpty())
                    out.add(new BlockOverlayDescriptor(blockId, currentAttachedBone, List.copyOf(currentOps)));
                insideBlock = false;
                currentOps = new ArrayList<>();
                currentAttachedBone = null;
                floatStack.clear();
                continue;
            }
            if (!insideBlock) continue;

            switch (methodInsn.owner + "." + methodInsn.name) {
                case POSE_STACK + ".translate" -> {
                    if (methodInsn.desc.startsWith("(FFF") && floatStack.size() >= 3) {
                        float z = floatStack.remove(floatStack.size() - 1);
                        float y = floatStack.remove(floatStack.size() - 1);
                        float x = floatStack.remove(floatStack.size() - 1);
                        currentOps.add(new TransformOpRecord(OpKind.TRANSLATE, x, y, z));
                    }
                }
                case POSE_STACK + ".scale" -> {
                    if (methodInsn.desc.startsWith("(FFF") && floatStack.size() >= 3) {
                        float z = floatStack.remove(floatStack.size() - 1);
                        float y = floatStack.remove(floatStack.size() - 1);
                        float x = floatStack.remove(floatStack.size() - 1);
                        currentOps.add(new TransformOpRecord(OpKind.SCALE, x, y, z));
                    }
                }
                case AXIS + ".rotationDegrees" -> {
                    // Resolves the {@code Axis.YP.rotationDegrees(F)} / similar pattern. The
                    // Axis static field (YP / YN / etc) determines the axis; YP and YN both map
                    // to a Y rotation (sign baked into the angle for YN). Other axes are skipped
                    // since vanilla block-overlay layers consistently use Y - flagged via WARN
                    // by the caller if a non-Y axis becomes load-bearing.
                    if (floatStack.isEmpty()) continue;
                    float degrees = floatStack.remove(floatStack.size() - 1);
                    String axis = findPrecedingAxisField(methodInsn);
                    if (axis == null || axis.charAt(0) != 'Y') continue;
                    if (axis.equals("YN")) degrees = -degrees;
                    currentOps.add(new TransformOpRecord(OpKind.ROTATE_Y, degrees, 0f, 0f));
                }
                case MODEL_PART + ".translateAndRotate" -> {
                    // Flag the parent bone whose pose pre-applies. The bone name comes from the
                    // preceding {@code parent.getXxx()} accessor; recognise the typical
                    // {@code getHead}/{@code getBody}/etc convention by stripping the {@code get}
                    // prefix and lower-casing the first letter. Mooshroom uses {@code getHead}.
                    String bone = findPrecedingBoneAccessor(methodInsn);
                    if (bone != null) currentAttachedBone = bone;
                }
                default -> { }
            }
        }
        return out;
    }

    /**
     * Walks backward from a {@code rotationDegrees} call to find the preceding
     * {@code GETSTATIC com/mojang/math/Axis.<X>} field load that determined the axis. Returns
     * the field name ({@code YP} / {@code YN} / {@code XP} / etc) or {@code null} if the axis
     * couldn't be resolved.
     */
    private static @Nullable String findPrecedingAxisField(@NotNull MethodInsnNode call) {
        for (AbstractInsnNode node = call.getPrevious(); node != null; node = node.getPrevious()) {
            if (node.getOpcode() < 0) continue;
            if (node.getOpcode() == Opcodes.GETSTATIC && node instanceof FieldInsnNode field
                && field.owner.equals(AXIS)) {
                return field.name;
            }
            // LDC pushes the angle - keep walking backward past it.
            if (AsmKit.readFloatLiteral(node) != null) continue;
            // Anything else (eg a method invocation) means we've left the rotationDegrees
            // expression and the axis isn't the immediate preceding GETSTATIC.
            break;
        }
        return null;
    }

    /**
     * Walks backward from a {@code translateAndRotate} call to find the preceding
     * {@code parent.getXxx()} accessor that returned the bone. Returns the bone name lower-cased
     * with the {@code get} prefix stripped (e.g. {@code getHead} -> {@code head}). Heuristic:
     * the most recent {@code INVOKEVIRTUAL} on the parent renderer's model class that returns
     * a {@link MODEL_PART} instance is the bone accessor.
     */
    private static @Nullable String findPrecedingBoneAccessor(@NotNull MethodInsnNode call) {
        for (AbstractInsnNode node = call.getPrevious(); node != null; node = node.getPrevious()) {
            if (node.getOpcode() < 0) continue;
            if (node instanceof MethodInsnNode methodCall
                && methodCall.getOpcode() == Opcodes.INVOKEVIRTUAL
                && methodCall.name.startsWith("get")
                && methodCall.desc.endsWith("Lnet/minecraft/client/model/geom/ModelPart;")) {
                String accessor = methodCall.name.substring(3);
                if (accessor.isEmpty()) return null;
                return Character.toLowerCase(accessor.charAt(0)) + accessor.substring(1);
            }
        }
        return null;
    }

    /**
     * Resolves the canonical default block id for a known layer. When {@code layerInfo} carries
     * a variant-class reference, walks that class's {@code <clinit>} for the {@code DEFAULT}
     * field's variant constant and the {@code Blocks.X} field its constructor binds. Returns
     * the {@code minecraft:<lowercase_block_field>} id (e.g. {@code Blocks.RED_MUSHROOM} ->
     * {@code minecraft:red_mushroom}). Falls back to {@code layerInfo.defaultBlockId} when
     * present and the variant walk fails / is absent.
     */
    private static @Nullable String resolveDefaultBlockId(
        @NotNull ZipFile zip,
        @NotNull KnownLayer layerInfo,
        @NotNull String entityId,
        @NotNull Diagnostics diagnostics
    ) {
        if (layerInfo.variantClass() == null) return layerInfo.defaultBlockId();
        ClassNode variantClass = AsmKit.loadClass(zip, layerInfo.variantClass());
        if (variantClass == null) {
            diagnostics.warn("%s: variant class '%s' missing from client jar", entityId, layerInfo.variantClass());
            return layerInfo.defaultBlockId();
        }
        MethodNode clinit = AsmKit.findMethod(variantClass, "<clinit>");
        if (clinit == null) return layerInfo.defaultBlockId();

        // Walk the <clinit> sequentially, tracking each enum constant's bound Blocks.X field.
        // Pattern per constant:
        //   new Variant; dup; ldc <NAME>; iconst_<id>; ldc <type>; iconst_<id>;
        //   getstatic Blocks.X; invokevirtual defaultBlockState; invokespecial <init>;
        //   putstatic <CONSTANT>;
        // Default field assignment: getstatic <CONSTANT>; putstatic DEFAULT;
        Map<String, String> constantToBlockField = new LinkedHashMap<>();
        String pendingBlockField = null;
        String pendingDefaultSource = null;
        String defaultConstant = null;

        for (AbstractInsnNode node = clinit.instructions.getFirst(); node != null; node = node.getNext()) {
            if (node.getOpcode() == Opcodes.GETSTATIC && node instanceof FieldInsnNode getfield) {
                if (getfield.owner.equals(BLOCKS)) {
                    pendingBlockField = getfield.name;
                } else if (getfield.owner.equals(layerInfo.variantClass())) {
                    pendingDefaultSource = getfield.name;
                }
                continue;
            }
            if (node.getOpcode() == Opcodes.PUTSTATIC && node instanceof FieldInsnNode putfield
                && putfield.owner.equals(layerInfo.variantClass())) {
                if ("DEFAULT".equals(putfield.name) && pendingDefaultSource != null) {
                    defaultConstant = pendingDefaultSource;
                } else if (pendingBlockField != null) {
                    constantToBlockField.put(putfield.name, pendingBlockField);
                }
                pendingBlockField = null;
                pendingDefaultSource = null;
            }
        }

        if (defaultConstant == null) return layerInfo.defaultBlockId();
        String blockField = constantToBlockField.get(defaultConstant);
        if (blockField == null) return layerInfo.defaultBlockId();
        return "minecraft:" + blockField.toLowerCase(Locale.ROOT);
    }

    /**
     * Metadata for one recognised block-decoration layer: the variant enum class whose DEFAULT
     * backs the canonical block id, and an optional literal fallback for layers without a
     * variant enum (iron-golem poppy etc). Both fields nullable - at least one must be set.
     */
    private record KnownLayer(@Nullable String variantClass, @Nullable String defaultBlockId) {}

    /** One pose-stack op recognised by the walker. {@code a/b/c} fields hold per-kind data. */
    public record TransformOpRecord(@NotNull OpKind kind, float a, float b, float c) {}

    /** Recognised pose-stack op kinds. {@code ROTATE_Y} stores degrees in {@code a}; the others use all three components. */
    public enum OpKind { TRANSLATE, ROTATE_Y, SCALE }

    /**
     * Wire-format-friendly representation of one block overlay row. Carries the block id, an
     * optional bone name the overlay attaches to (its bind pose pre-applies), and the ordered
     * pose-stack op list. Consumed by
     * {@link lib.minecraft.renderer.tooling.ToolingEntityModels} which serialises into the
     * {@code block_overlays} JSON array consumed by {@link
     * lib.minecraft.renderer.pipeline.loader.EntityModelLoader.BlockOverlayLayer}.
     */
    public record BlockOverlayDescriptor(
        @NotNull String blockId,
        @Nullable String attachedBone,
        @NotNull List<TransformOpRecord> ops
    ) {}

}
