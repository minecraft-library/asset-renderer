package lib.minecraft.renderer.tooling.entity;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import lib.minecraft.renderer.tooling.ToolingEntityModels;
import lib.minecraft.renderer.tooling.util.AsmKit;
import lib.minecraft.renderer.tooling.util.ClassNodeCache;
import lib.minecraft.renderer.tooling.util.VanillaSourceClasses;
import lib.minecraft.renderer.tooling.util.Diagnostics;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Bytecode-driven discovery of block-model overlays attached to entity renderers via
 * {@code addLayer(new XLayer(...))} where {@code XLayer} renders a vanilla block model on top
 * of the entity body - conceptually the family that includes mooshroom mushrooms, iron golem
 * poppy, and enderman carried block. Recognition is <b>structural, not an allowlist</b>: a layer
 * qualifies when its {@code submit} reads a {@code BlockModelRenderState}-typed field and the
 * block id resolves either from a matching {@code $Variant} enum on the RenderState
 * (mooshroom's {@code MushroomCowMushroomLayer}) or from a literal {@code Blocks.X} bound in the
 * renderer's {@code extractRenderState} for an always-present decoration (snow-golem's
 * {@code SnowGolemHeadLayer} carved_pumpkin) - see {@link #detectKnownLayer}. Conditional
 * decorations (iron-golem poppy, enderman carried block) fail the presence gate and are skipped.
 * The walker stays generic (no layer-class allowlist) so future block-overlay layers of either
 * shape auto-classify without a code change.
 *
 * <p>Walks the renderer constructor for {@code addLayer(new RecognisedLayer(this[, args]))}
 * dispatches, then walks the matched layer's {@code submit} method for each
 * {@code pushPose / popPose} pair, extracting the pose-stack ops issued between them. Each
 * pair becomes one {@link Result} - one block-overlay row in {@code entity_models.json}.
 *
 * <p>Op extraction recognises:
 * <ul>
 *   <li>{@code pose.translate(F, F, F)} - emitted as {@link OpKind#TRANSLATE}.</li>
 *   <li>{@code pose.scale(F, F, F)} - emitted as {@link OpKind#SCALE}.</li>
 *   <li>{@code pose.mulPose(Axis.YP.rotationDegrees(F))} - emitted as {@link OpKind#ROTATE_Y}.
 *       Other axes / quaternion paths are skipped (vanilla block-overlay layers all use Y).</li>
 *   <li>{@code parent.getHead().translateAndRotate(pose)} - flagged on the descriptor as
 *       {@code attachedBone="head"} (the {@code get} prefix stripped and the first letter
 *       lower-cased); the pose-stack equivalent of the bone's pivot translate + rotation is
 *       reconstructed at render time.</li>
 * </ul>
 *
 * <p>Block id determination resolves the <b>canonical default</b> per layer, not the live
 * per-render variant. {@link #resolveDefaultBlockId} walks the variant enum's {@code <clinit>}
 * for its {@code DEFAULT} constant and the {@code Blocks.X} field that constant binds (RED for
 * mooshroom -&gt; {@code minecraft:red_mushroom}). The runtime per-state path (mooshroom's
 * {@code variant.getBlockState()} choosing brown vs red) is out of scope - the default is what the
 * static iso render presents.
 */
@UtilityClass
public final class EntityBlockOverlayResolver {

    /**
     * JVM internal name of {@code PoseStack} - layer submit methods invoke its push / translate / rotate / scale ops.
     */
    private static final @NotNull String POSE_STACK = "com/mojang/blaze3d/vertex/PoseStack";

    /**
     * JVM internal name of {@code com.mojang.math.Axis} - its {@code YP} static field's rotation methods are how layers express Y rotations.
     */
    private static final @NotNull String AXIS = "com/mojang/math/Axis";

    /**
     * JVM internal name of {@code ModelPart} - {@code translateAndRotate} pre-applies a bone's pose to the stack.
     */
    private static final @NotNull String MODEL_PART = "net/minecraft/client/model/geom/ModelPart";

    /**
     * JVM internal name of {@code BlockModelResolver} - the renderer's {@code extractRenderState}
     * calls {@code update(BlockModelRenderState, BlockState, BlockDisplayContext)} on it to bind a
     * block model to the overlay's render state (snow-golem carved_pumpkin, iron-golem poppy).
     */
    private static final @NotNull String BLOCK_MODEL_RESOLVER = "net/minecraft/client/renderer/block/BlockModelResolver";

    /** Descriptor for the BlockModelRenderState type that a block-overlay layer's submit reads from a state field. */
    private static final @NotNull String BLOCK_MODEL_RENDER_STATE_DESC =
        "L" + VanillaSourceClasses.BLOCK_MODEL_RENDER_STATE + ";";

    /**
     * Resolves the block-overlay descriptors attached to an entity's renderer via recognised
     * block-decoration layer classes. Returns an empty list when the renderer has no recognised
     * layers - the common case (only ~4 vanilla entities have block-decoration layers).
     *
     * @param classNodes the ClassNode cache (shared with sibling resolver walks)
     * @param entityId the entity id being resolved (used in diagnostics)
     * @param rendererInternalName the renderer class JVM internal name (e.g
     *     {@code net/minecraft/client/renderer/entity/MushroomCowRenderer})
     * @param diagnostics the diagnostic sink for parse-failure WARN messages
     * @return the block-overlay descriptors for the renderer, one per recognised layer's
     *     {@code pushPose}/{@code popPose} pair; empty when no recognised layers attach
     */
    public static @NotNull ConcurrentList<Result> resolve(
        @NotNull ClassNodeCache classNodes,
        @NotNull String entityId,
        @NotNull String rendererInternalName,
        @NotNull Diagnostics diagnostics
    ) {
        ConcurrentList<Result> out = Concurrent.newList();
        ClassNode renderer = classNodes.load(rendererInternalName);
        if (renderer == null) return out;

        // Walk the renderer's <init> for {@code new XLayer; ... ; addLayer} patterns.
        // Multiple layers may be attached - one new instruction per recognised layer class.
        MethodNode init = AsmKit.findMethod(renderer, AsmKit.INIT);
        if (init == null) return out;

        for (AbstractInsnNode node = init.instructions.getFirst(); node != null; node = node.getNext()) {
            if (!(node instanceof TypeInsnNode typeInsn) || typeInsn.getOpcode() != Opcodes.NEW) continue;
            String layerInternalName = typeInsn.desc;
            KnownLayer layerInfo = detectKnownLayer(classNodes, layerInternalName, renderer);
            if (layerInfo == null) continue;

            String defaultBlockId = resolveDefaultBlockId(classNodes, layerInfo, entityId, diagnostics);
            if (defaultBlockId == null) {
                diagnostics.warn("%s: layer '%s' default block id could not be resolved (variant class '%s')",
                    entityId, layerInternalName, layerInfo.variantClass());
                continue;
            }

            ClassNode layerClass = classNodes.load(layerInternalName);
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

            List<Result> extracted = extractPoseBlocks(submitMethod, defaultBlockId);
            out.addAll(extracted);
        }
        return out;
    }

    /**
     * Detects whether {@code layerInternalName} is a block-rendering overlay layer and resolves
     * how its canonical default-state block id is determined. A layer qualifies when its
     * typed-state {@code submit} overload reads a {@code BlockModelRenderState}-typed field from
     * the entity's RenderState class. Two block-id sources are recognised:
     *
     * <ul>
     *   <li><b>Variant-driven</b> - the state class also declares an enum-typed field whose
     *       descriptor ends with {@code $Variant;}. The variant class's {@code DEFAULT} constant
     *       resolves the block id (see {@link #resolveDefaultBlockId}). Vanilla
     *       MushroomCowMushroomLayer is the match: submit reads
     *       {@code state.mushroomModel:BlockModelRenderState}, MushroomCowRenderState declares
     *       {@code variant:MushroomCow$Variant}. Returned as {@code KnownLayer(variantClass, null)}.</li>
     *   <li><b>Fixed literal block</b> - the state has no {@code $Variant} field, and the renderer's
     *       {@code extractRenderState} binds a literal {@code Blocks.X.defaultBlockState()} into the
     *       same {@code BlockModelRenderState} field the layer reads (see
     *       {@link #resolveLiteralBlockId}). Vanilla SnowGolemHeadLayer is the match: submit reads
     *       {@code state.headBlock}, and {@code SnowGolemRenderer.extractRenderState} binds
     *       {@code Blocks.CARVED_PUMPKIN} whenever {@code SnowGolem.hasPumpkin()} (default true).
     *       Returned as {@code KnownLayer(null, "minecraft:carved_pumpkin")}.</li>
     * </ul>
     *
     * <p>The fixed-literal path is gated so only always-present decorations emit: iron-golem's
     * flower ({@code Blocks.POPPY} but gated on {@code offerFlowerTick > 0}, default off) and
     * enderman's carried block (a runtime block, not a {@code Blocks.X} literal) both fail the
     * {@link #resolveLiteralBlockId} presence gate and are skipped. Detection stays structural
     * (no layer-class allowlist).
     *
     * @param classNodes the ClassNode cache (shared with sibling resolver walks)
     * @param layerInternalName the candidate layer class's JVM internal name
     * @param renderer the entity renderer whose {@code extractRenderState} sources the literal
     *     block id for the fixed-block path
     * @return the {@link KnownLayer} carrying the resolved variant class or literal block id, or
     *     {@code null} when the class is not a recognised (or always-present) block-overlay layer
     */
    private static @Nullable KnownLayer detectKnownLayer(
        @NotNull ClassNodeCache classNodes,
        @NotNull String layerInternalName,
        @NotNull ClassNode renderer
    ) {
        ClassNode layerCn = classNodes.load(layerInternalName);
        if (layerCn == null) return null;
        MethodNode submit = findSubmitMethod(layerCn);
        if (submit == null) return null;
        String stateClass = null;
        String blockFieldName = null;
        for (AbstractInsnNode in = submit.instructions.getFirst(); in != null; in = in.getNext()) {
            if (in.getOpcode() != Opcodes.GETFIELD) continue;
            if (!(in instanceof FieldInsnNode fi)) continue;
            if (BLOCK_MODEL_RENDER_STATE_DESC.equals(fi.desc)) {
                stateClass = fi.owner;
                blockFieldName = fi.name;
                break;
            }
        }
        if (stateClass == null) return null;
        ClassNode stateCn = classNodes.load(stateClass);
        if (stateCn == null) return null;
        for (FieldNode field : stateCn.fields) {
            if (field.desc == null) continue;
            if (!field.desc.startsWith("L") || !field.desc.endsWith("$Variant;")) continue;
            String variantClass = field.desc.substring(1, field.desc.length() - 1);
            return new KnownLayer(variantClass, null);
        }
        String literalBlockId = resolveLiteralBlockId(classNodes, renderer, blockFieldName);
        if (literalBlockId == null) return null;
        return new KnownLayer(null, literalBlockId);
    }

    /**
     * Resolves the canonical block id for a fixed-block overlay layer by walking the renderer's
     * {@code extractRenderState} for a {@code BlockModelResolver.update(<blockField>,
     * Blocks.X.defaultBlockState(), ctx)} call that binds a literal {@code Blocks.X} into the same
     * {@code BlockModelRenderState} field {@code blockFieldName} the layer reads, then applies a
     * presence gate so only decorations present at the default render state emit.
     *
     * <p><b>Presence gate.</b> Vanilla gates always-on cosmetic parts on a boolean "presence
     * flag" method on the entity ({@code SnowGolem.hasPumpkin()}, default true), whereas transient
     * decorations gate on a timer / counter ({@code IronGolem.offerFlowerTick > 0}, default 0).
     * Requiring an entity-typed boolean predicate ({@code ()Z} INVOKEVIRTUAL whose owner is the
     * {@code extractRenderState} entity parameter) emits snow-golem's pumpkin while excluding
     * iron-golem's flower; enderman's carried block is already excluded because its block comes
     * from a runtime {@code getCarriedBlock()} rather than a {@code Blocks.X} literal.
     *
     * @param classNodes the ClassNode cache (shared with sibling resolver walks)
     * @param renderer the entity renderer whose {@code extractRenderState} is walked
     * @param blockFieldName the {@code BlockModelRenderState} field name the layer's submit reads
     * @return the {@code minecraft:<block>} id, or {@code null} when no literal-block update binds
     *     {@code blockFieldName} or the presence gate fails
     */
    private static @Nullable String resolveLiteralBlockId(
        @NotNull ClassNodeCache classNodes,
        @NotNull ClassNode renderer,
        @Nullable String blockFieldName
    ) {
        if (blockFieldName == null) return null;
        for (MethodNode method : renderer.methods) {
            if (!"extractRenderState".equals(method.name)) continue;
            String blocksField = findLiteralBlockUpdate(method, blockFieldName);
            if (blocksField == null) continue;
            if (!hasEntityBooleanGuard(method)) continue;
            return "minecraft:" + blocksField.toLowerCase(Locale.ROOT);
        }
        return null;
    }

    /**
     * Scans {@code method} for a {@code BlockModelResolver.update(...)} call whose arguments pair
     * a {@code GETSTATIC Blocks.X} block state with a {@code GETFIELD <blockFieldName>} target,
     * and returns the {@code Blocks.X} field name. Walks backward from each {@code update} call to
     * the previous {@code update} (or method start), so per-statement args don't bleed across
     * calls. Returns {@code null} when no update binds {@code blockFieldName} to a literal block.
     *
     * @param method the {@code extractRenderState} method to scan
     * @param blockFieldName the {@code BlockModelRenderState} field the update must target
     * @return the bound {@code Blocks.X} field name, or {@code null} when none matches
     */
    private static @Nullable String findLiteralBlockUpdate(@NotNull MethodNode method, @NotNull String blockFieldName) {
        for (AbstractInsnNode in = method.instructions.getFirst(); in != null; in = in.getNext()) {
            if (in.getOpcode() != Opcodes.INVOKEVIRTUAL) continue;
            if (!(in instanceof MethodInsnNode mi)) continue;
            if (!BLOCK_MODEL_RESOLVER.equals(mi.owner) || !"update".equals(mi.name)) continue;
            String blocksField = null;
            boolean matchedTargetField = false;
            for (AbstractInsnNode back = in.getPrevious(); back != null; back = back.getPrevious()) {
                if (back.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && back instanceof MethodInsnNode prevUpdate
                    && BLOCK_MODEL_RESOLVER.equals(prevUpdate.owner)
                    && "update".equals(prevUpdate.name))
                    break;
                if (back.getOpcode() == Opcodes.GETSTATIC
                    && back instanceof FieldInsnNode gs
                    && VanillaSourceClasses.BLOCKS.equals(gs.owner))
                    blocksField = gs.name;
                if (back.getOpcode() == Opcodes.GETFIELD
                    && back instanceof FieldInsnNode gf
                    && blockFieldName.equals(gf.name)
                    && BLOCK_MODEL_RENDER_STATE_DESC.equals(gf.desc))
                    matchedTargetField = true;
            }
            if (blocksField != null && matchedTargetField) return blocksField;
        }
        return null;
    }

    /**
     * Returns {@code true} when {@code method} calls a parameterless boolean method
     * ({@code ()Z} descriptor) on its first (entity) parameter type - the "presence flag"
     * shape ({@code SnowGolem.hasPumpkin()}) that gates always-on block decorations. The bridge
     * {@code extractRenderState(LivingEntity/Entity, ...)} overloads never reach here because they
     * don't contain the literal-block update the caller matches first.
     *
     * @param method the typed {@code extractRenderState} method (first arg = entity class)
     * @return whether a {@code ()Z} INVOKEVIRTUAL on the entity type is present
     */
    private static boolean hasEntityBooleanGuard(@NotNull MethodNode method) {
        Type[] args = Type.getArgumentTypes(method.desc);
        if (args.length == 0 || args[0].getSort() != Type.OBJECT) return false;
        String entityClass = args[0].getInternalName();
        for (AbstractInsnNode in = method.instructions.getFirst(); in != null; in = in.getNext()) {
            if (in.getOpcode() != Opcodes.INVOKEVIRTUAL) continue;
            if (!(in instanceof MethodInsnNode mi)) continue;
            if (entityClass.equals(mi.owner) && mi.desc != null && mi.desc.endsWith(")Z")) return true;
        }
        return false;
    }

    /**
     * Returns the typed-RenderState {@code submit} overload of a {@code RenderLayer} subclass.
     * Each layer overrides the abstract {@code submit(PoseStack, SubmitNodeCollector, int, S, F, F)}
     * where {@code S} is the entity-specific RenderState; the {@code EntityRenderState} overload
     * just delegates and contains no pose-stack literals. The typed overload's descriptor
     * contains the entity-specific state class name (which never equals
     * {@code EntityRenderState}); pick that one.
     *
     * @param layerClass the {@code RenderLayer} subclass to search
     * @return the typed-state {@code submit} override, or {@code null} when the class declares only
     *     the {@code EntityRenderState} overload
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
     * {@link Result}. Ops are stored in bytecode order; consumer (the renderer)
     * applies them in the order vanilla's PoseStack would.
     *
     * @param submit the layer's typed-state {@code submit} method to walk
     * @param blockId the canonical block id stamped onto every extracted {@link Result}
     * @return one {@link Result} per {@code pushPose}/{@code popPose} pair that emitted at least
     *     one recognised op
     */
    private static @NotNull List<Result> extractPoseBlocks(
        @NotNull MethodNode submit,
        @NotNull String blockId
    ) {
        List<Result> out = new ArrayList<>();
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
                    out.add(new Result(blockId, currentAttachedBone, List.copyOf(currentOps)));
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
                        float z = floatStack.removeLast();
                        float y = floatStack.removeLast();
                        float x = floatStack.removeLast();
                        currentOps.add(new TransformOpRecord(OpKind.TRANSLATE, x, y, z));
                    }
                }
                case POSE_STACK + ".scale" -> {
                    if (methodInsn.desc.startsWith("(FFF") && floatStack.size() >= 3) {
                        float z = floatStack.removeLast();
                        float y = floatStack.removeLast();
                        float x = floatStack.removeLast();
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
                    float degrees = floatStack.removeLast();
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
     *
     * @param call the {@code Axis.rotationDegrees} invocation to walk backward from
     * @return the {@code Axis.<X>} field name, or {@code null} when no matching {@code GETSTATIC}
     *     precedes the call before a non-float-literal instruction
     */
    private static @Nullable String findPrecedingAxisField(@NotNull MethodInsnNode call) {
        // Walk backward past any FCONST / LDC float literals (the angle push) until we hit
        // the GETSTATIC Axis.<X>. Pseudo-nodes are always skipped.
        AbstractInsnNode hit = AsmKit.findPreceding(call,
            n -> AsmKit.isGetStatic(n, AXIS),
            op -> op == Opcodes.LDC
                || op == Opcodes.FCONST_0 || op == Opcodes.FCONST_1 || op == Opcodes.FCONST_2);
        return hit == null ? null : ((FieldInsnNode) hit).name;
    }

    /**
     * Walks backward from a {@code translateAndRotate} call to find the preceding
     * {@code parent.getXxx()} accessor that returned the bone. Returns the bone name lower-cased
     * with the {@code get} prefix stripped (e.g. {@code getHead} -> {@code head}). Heuristic:
     * the most recent {@code INVOKEVIRTUAL} on the parent renderer's model class that returns
     * a {@code ModelPart} instance is the bone accessor.
     *
     * @param call the {@code ModelPart.translateAndRotate} invocation to walk backward from
     * @return the lower-cased bone name, or {@code null} when no {@code get*()->ModelPart} accessor
     *     precedes the call
     */
    private static @Nullable String findPrecedingBoneAccessor(@NotNull MethodInsnNode call) {
        // Walk backward over real instructions (AsmKit.previousReal skips pseudo-nodes) and
        // stop at the first INVOKEVIRTUAL get*()->ModelPart accessor. No early abort on other
        // real ops: unlike findPrecedingAxisField, the bone accessor may sit several real
        // instructions before the translateAndRotate call, so this scans until the first match.
        for (AbstractInsnNode node = AsmKit.previousReal(call); node != null; node = AsmKit.previousReal(node)) {
            if (node instanceof MethodInsnNode methodCall
                && methodCall.getOpcode() == Opcodes.INVOKEVIRTUAL
                && methodCall.name.startsWith("get")
                && AsmKit.descriptorReturns(methodCall.desc, VanillaSourceClasses.MODEL_PART)) {
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
     *
     * @param classNodes the ClassNode cache (shared with sibling resolver walks)
     * @param layerInfo the recognised layer's metadata (variant class and/or literal fallback)
     * @param entityId the entity id being resolved (used in diagnostics)
     * @param diagnostics the diagnostic sink for missing-class WARN messages
     * @return the canonical {@code minecraft:<block>} id, or {@code null} only when neither the
     *     variant walk nor the literal fallback yields one
     */
    private static @Nullable String resolveDefaultBlockId(
        @NotNull ClassNodeCache classNodes,
        @NotNull KnownLayer layerInfo,
        @NotNull String entityId,
        @NotNull Diagnostics diagnostics
    ) {
        if (layerInfo.variantClass() == null) return layerInfo.defaultBlockId();
        ClassNode variantClass = classNodes.load(layerInfo.variantClass());
        if (variantClass == null) {
            diagnostics.warn("%s: variant class '%s' missing from client jar", entityId, layerInfo.variantClass());
            return layerInfo.defaultBlockId();
        }
        MethodNode clinit = AsmKit.findMethod(variantClass, AsmKit.CLINIT);
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
            if (AsmKit.isGetStatic(node, VanillaSourceClasses.BLOCKS)) {
                pendingBlockField = ((FieldInsnNode) node).name;
                continue;
            }
            if (AsmKit.isGetStatic(node, layerInfo.variantClass())) {
                pendingDefaultSource = ((FieldInsnNode) node).name;
                continue;
            }
            if (AsmKit.isPutStatic(node, layerInfo.variantClass())) {
                FieldInsnNode putfield = (FieldInsnNode) node;
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
     *
     * @param variantClass JVM internal name of the {@code $Variant} enum whose {@code DEFAULT}
     *     constant resolves the canonical block id, or {@code null} for a literal-only layer
     * @param defaultBlockId literal {@code minecraft:<block>} fallback used when there is no
     *     variant class or the variant walk fails, or {@code null} when only a variant class is set
     */
    private record KnownLayer(@Nullable String variantClass, @Nullable String defaultBlockId) {}

    /**
     * One pose-stack op recognised by the walker. The {@code a}/{@code b}/{@code c} components
     * hold per-{@link OpKind} data.
     *
     * @param kind the op kind, selecting how {@code a}/{@code b}/{@code c} are interpreted
     * @param a first component - {@code x} for {@code TRANSLATE}/{@code SCALE}, degrees for {@code ROTATE_Y}
     * @param b second component - {@code y} for {@code TRANSLATE}/{@code SCALE}, unused ({@code 0}) for {@code ROTATE_Y}
     * @param c third component - {@code z} for {@code TRANSLATE}/{@code SCALE}, unused ({@code 0}) for {@code ROTATE_Y}
     */
    public record TransformOpRecord(@NotNull OpKind kind, float a, float b, float c) {}

    /**
     * Recognised pose-stack op kinds. {@code ROTATE_Y} stores degrees in {@code a}; the others use all three components.
     */
    public enum OpKind {
        /** {@code PoseStack.translate(x, y, z)} - components are {@code (x, y, z)}. */
        TRANSLATE,
        /** {@code PoseStack.mulPose(Axis.YP.rotationDegrees(a))} - degrees in {@code a} ({@code YN} sign folded in). */
        ROTATE_Y,
        /** {@code PoseStack.scale(x, y, z)} - components are {@code (x, y, z)}. */
        SCALE
    }

    /**
     * Wire-format-friendly representation of one block overlay row. Carries the block id, an
     * optional bone name the overlay attaches to (its bind pose pre-applies), and the ordered
     * pose-stack op list. Consumed by
     * {@link ToolingEntityModels} which serialises into the
     * {@code block_overlays} JSON array consumed by {@link
     * lib.minecraft.renderer.pipeline.loader.EntityModelLoader.BlockOverlayLayer}.
     *
     * @param blockId the canonical {@code minecraft:<block>} id whose model composites onto the entity
     * @param attachedBone the parent bone whose bind pose pre-applies to the overlay, or
     *     {@code null} when the overlay attaches at the entity root
     * @param ops the ordered pose-stack ops between one {@code pushPose}/{@code popPose} pair
     */
    public record Result(
        @NotNull String blockId,
        @Nullable String attachedBone,
        @NotNull List<TransformOpRecord> ops
    ) {}

    /**
     * Converts a list of {@link Result} into the JSON wire format consumed by
     * {@link lib.minecraft.renderer.pipeline.loader.EntityModelLoader#loadBlockOverlays}. Each
     * descriptor becomes one {@code block_overlays[]} row; descriptors are emitted in the
     * order the resolver returned them (mirrors the bytecode pushPose/popPose order).
     *
     * @param descriptors the resolved block-overlay descriptors, in resolver order
     * @return the {@code block_overlays} JSON array, one object per descriptor
     */
    public static @NotNull JsonArray toJson(@NotNull ConcurrentList<Result> descriptors) {
        JsonArray rows = new JsonArray();
        for (Result desc : descriptors) {
            JsonObject row = new JsonObject();
            row.addProperty("block_id", desc.blockId());
            if (desc.attachedBone() != null) row.addProperty("attached_bone", desc.attachedBone());
            JsonArray opsJson = new JsonArray();
            for (TransformOpRecord op : desc.ops()) {
                JsonObject opJson = switch (op.kind()) {
                    case TRANSLATE -> translateJson(op.a(), op.b(), op.c());
                    case ROTATE_Y -> rotateYJson(op.a());
                    case SCALE -> scaleJson(op.a(), op.b(), op.c());
                };
                opsJson.add(opJson);
            }
            row.add("transforms", opsJson);
            rows.add(row);
        }
        return rows;
    }

    /**
     * Builds the {@code {"op":"translate", ...}} JSON for a translate op.
     *
     * @param x translation along X
     * @param y translation along Y
     * @param z translation along Z
     * @return the translate op object
     */
    private static @NotNull JsonObject translateJson(float x, float y, float z) {
        JsonObject op = new JsonObject();
        op.addProperty("op", "translate");
        op.addProperty("x", x);
        op.addProperty("y", y);
        op.addProperty("z", z);
        return op;
    }

    /**
     * Builds the {@code {"op":"rotate_y","degrees":...}} JSON for a Y-rotation op.
     *
     * @param degrees the Y rotation in degrees ({@code YN} sign already folded in)
     * @return the rotate_y op object
     */
    private static @NotNull JsonObject rotateYJson(float degrees) {
        JsonObject op = new JsonObject();
        op.addProperty("op", "rotate_y");
        op.addProperty("degrees", degrees);
        return op;
    }

    /**
     * Builds the {@code {"op":"scale", ...}} JSON for a scale op.
     *
     * @param x scale along X
     * @param y scale along Y
     * @param z scale along Z
     * @return the scale op object
     */
    private static @NotNull JsonObject scaleJson(float x, float y, float z) {
        JsonObject op = new JsonObject();
        op.addProperty("op", "scale");
        op.addProperty("x", x);
        op.addProperty("y", y);
        op.addProperty("z", z);
        return op;
    }

}
