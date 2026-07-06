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
import lib.minecraft.renderer.tooling.util.ToolingJson;
import lib.minecraft.renderer.tooling.util.ToolingText;
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
 * qualifies when its {@code submit} reads a {@code BlockModelRenderState}-typed field; the block id
 * then resolves from one of three shapes (see {@link #detectKnownLayer}): a matching {@code $Variant}
 * enum on the RenderState (mooshroom's {@code MushroomCowMushroomLayer}), a presence-gated literal
 * {@code Blocks.X} bound in the renderer's {@code extractRenderState} for an always-present
 * decoration (snow-golem's {@code SnowGolemHeadLayer} carved_pumpkin), or a <b>selectable held
 * block</b> - a conditional decoration whose block is supplied at render from
 * {@code EntityAppearance.carried} (iron-golem poppy, enderman carried block). Selectable rows carry
 * a {@code "selectable": true} flag and render only when the caller picks a block; the default render
 * draws none, so the always-present decorations stay byte-identical. The walker stays generic (no
 * layer-class allowlist) so future block-overlay layers of any shape auto-classify without a code
 * change.
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
 *   <li>{@code pose.mulPose(Axis.YP.rotationDegrees(F))} - emitted as {@link OpKind#ROTATE_Y}, and
 *       {@code Axis.XP.rotationDegrees(F)} as {@link OpKind#ROTATE_X} ({@code ?N} sign folded in). A
 *       Z rotation / quaternion path is skipped (no vanilla block-overlay layer uses one).</li>
 *   <li>{@code parent.getHead().translateAndRotate(pose)} - flagged on the descriptor as
 *       {@code attachedBone}, resolved through the model class to the {@code getChild} bone name
 *       ({@code getHead} -&gt; {@code "head"}, {@code getFlowerHoldingArm} -&gt; {@code "right_arm"});
 *       the pose-stack equivalent of the bone's pivot translate + rotation is reconstructed at
 *       render time.</li>
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
            // A selectable overlay's block is supplied at render (enderman's runtime carried block
            // has no vanilla literal), so a null id is expected - emit it with no default block_id.
            // A fixed overlay with an unresolvable id is a genuine parse failure; warn and skip.
            if (defaultBlockId == null && !layerInfo.selectable()) {
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

            List<Result> extracted = extractPoseBlocks(submitMethod, defaultBlockId, layerInfo.selectable(), classNodes);
            out.addAll(extracted);
        }
        return out;
    }

    /**
     * Detects whether {@code layerInternalName} is a block-rendering overlay layer and resolves
     * how its canonical default-state block id is determined. A layer qualifies when its
     * typed-state {@code submit} overload reads a {@code BlockModelRenderState}-typed field from
     * the entity's RenderState class. Three block-id sources are recognised:
     *
     * <ul>
     *   <li><b>Variant-driven</b> - the state class also declares an enum-typed field whose
     *       descriptor ends with {@code $Variant;}. The variant class's {@code DEFAULT} constant
     *       resolves the block id (see {@link #resolveDefaultBlockId}). Vanilla
     *       MushroomCowMushroomLayer is the match: submit reads
     *       {@code state.mushroomModel:BlockModelRenderState}, MushroomCowRenderState declares
     *       {@code variant:MushroomCow$Variant}. Returned as {@code KnownLayer(variantClass, null, false)}.</li>
     *   <li><b>Fixed literal block</b> - the state has no {@code $Variant} field, the renderer's
     *       {@code extractRenderState} binds a literal {@code Blocks.X.defaultBlockState()} into the
     *       same {@code BlockModelRenderState} field the layer reads, and that bind is
     *       <b>presence-gated</b> on an entity {@code ()Z} predicate (see {@link #resolveLiteralBlock}).
     *       Vanilla SnowGolemHeadLayer is the match: submit reads {@code state.headBlock}, and
     *       {@code SnowGolemRenderer.extractRenderState} binds {@code Blocks.CARVED_PUMPKIN} whenever
     *       {@code SnowGolem.hasPumpkin()} (default true). Returned as
     *       {@code KnownLayer(null, "minecraft:carved_pumpkin", false)}.</li>
     *   <li><b>Selectable held block</b> - the layer reads a {@code BlockModelRenderState} field but
     *       the block is not an always-present literal: either a timer-gated literal (iron-golem's
     *       {@code Blocks.POPPY} under {@code offerFlowerTick > 0}, emitted with that literal as the
     *       documented default) or a fully-runtime block (enderman's {@code getCarriedBlock()}, no
     *       literal, emitted with no default). Returned as {@code KnownLayer(null, <literal-or-null>,
     *       true)}; the render-time {@code EntityAppearance.carried} selection supplies the block.</li>
     * </ul>
     *
     * <p>Detection stays structural (no layer-class allowlist): the four vanilla layers that read a
     * {@code BlockModelRenderState} field in submit - mooshroom (variant), snow-golem (guarded
     * literal), iron-golem (unguarded literal), enderman (runtime) - each classify by shape alone.
     *
     * @param classNodes the ClassNode cache (shared with sibling resolver walks)
     * @param layerInternalName the candidate layer class's JVM internal name
     * @param renderer the entity renderer whose {@code extractRenderState} sources the literal
     *     block id for the fixed / selectable-literal path
     * @return the {@link KnownLayer} carrying the resolved variant class, literal block id, and
     *     selectable flag, or {@code null} when the class is not a recognised block-overlay layer
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
            return new KnownLayer(variantClass, null, false);
        }
        // Non-variant: classify by how (and whether) the renderer binds a literal Blocks.X. A
        // presence-gated literal ({@code SnowGolem.hasPumpkin()}, default true) is an always-present
        // decoration (fixed). A timer-gated literal ({@code IronGolem.offerFlowerTick > 0}, default
        // off) is a conditional held block - emitted as selectable so it renders only when the
        // caller picks it. No literal at all ({@code enderman.getCarriedBlock()}) is a fully-dynamic
        // held block - also selectable, with the block supplied entirely at render.
        LiteralBlock literal = resolveLiteralBlock(renderer, blockFieldName);
        if (literal != null)
            return new KnownLayer(null, literal.blockId(), !literal.guarded());
        return new KnownLayer(null, null, true);
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
     * The returned {@link LiteralBlock#guarded()} flag records which shape was found - an entity-typed
     * {@code ()Z} predicate present ({@code true}, snow-golem's always-present pumpkin) or absent
     * ({@code false}, iron-golem's conditional flower). The caller renders a guarded literal as a
     * fixed decoration and an unguarded one as a selectable held block.
     *
     * @param renderer the entity renderer whose {@code extractRenderState} is walked
     * @param blockFieldName the {@code BlockModelRenderState} field name the layer's submit reads
     * @return the literal block id and its presence-guard flag, or {@code null} when no literal-block
     *     update binds {@code blockFieldName} (the enderman carried block, a runtime source)
     */
    private static @Nullable LiteralBlock resolveLiteralBlock(
        @NotNull ClassNode renderer,
        @Nullable String blockFieldName
    ) {
        if (blockFieldName == null) return null;
        for (MethodNode method : renderer.methods) {
            if (!"extractRenderState".equals(method.name)) continue;
            String blocksField = findLiteralBlockUpdate(method, blockFieldName);
            if (blocksField == null) continue;
            return new LiteralBlock("minecraft:" + blocksField.toLowerCase(Locale.ROOT), hasEntityBooleanGuard(method));
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
     * @param blockId the canonical block id stamped onto every extracted {@link Result}, or
     *     {@code null} for a selectable overlay with no vanilla literal (enderman carried block)
     * @param selectable whether the extracted rows are caller-selected held blocks (stamped onto
     *     every {@link Result})
     * @param classNodes the ClassNode cache, used to resolve an attach-bone accessor to its bone
     * @return one {@link Result} per {@code pushPose}/{@code popPose} pair that emitted at least
     *     one recognised op
     */
    private static @NotNull List<Result> extractPoseBlocks(
        @NotNull MethodNode submit,
        @Nullable String blockId,
        boolean selectable,
        @NotNull ClassNodeCache classNodes
    ) {
        List<Result> out = new ArrayList<>();
        boolean insideBlock = false;
        List<TransformOp> currentOps = new ArrayList<>();
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
                    out.add(new Result(blockId, currentAttachedBone, List.copyOf(currentOps), selectable));
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
                        currentOps.add(new Translate(x, y, z));
                    }
                }
                case POSE_STACK + ".scale" -> {
                    if (methodInsn.desc.startsWith("(FFF") && floatStack.size() >= 3) {
                        float z = floatStack.removeLast();
                        float y = floatStack.removeLast();
                        float x = floatStack.removeLast();
                        currentOps.add(new Scale(x, y, z));
                    }
                }
                case AXIS + ".rotationDegrees" -> {
                    // Resolves the {@code Axis.<A>.rotationDegrees(F)} pattern. The Axis static field
                    // (XP / XN / YP / YN) determines the axis and sign: {@code ?P} maps to a positive
                    // rotation about that axis, {@code ?N} negates the angle. X and Y are recognised
                    // (the enderman carried block tilts on X then swings on Y; the iron golem flower
                    // lays flat on X); a Z rotation is skipped (no vanilla block-overlay layer uses one).
                    if (floatStack.isEmpty()) continue;
                    float degrees = floatStack.removeLast();
                    String axis = findPrecedingAxisField(methodInsn);
                    if (axis == null) continue;
                    if (axis.charAt(1) == 'N') degrees = -degrees;
                    switch (axis.charAt(0)) {
                        case 'Y' -> currentOps.add(new RotateY(degrees));
                        case 'X' -> currentOps.add(new RotateX(degrees));
                        default -> { }
                    }
                }
                case MODEL_PART + ".translateAndRotate" -> {
                    // Flag the parent bone whose pose pre-applies. The bone name comes from the
                    // preceding {@code parent.getXxx()} accessor, resolved through the model class to
                    // the geometry bone it returns (mooshroom / snow-golem {@code getHead} -> "head";
                    // iron-golem {@code getFlowerHoldingArm} -> the {@code rightArm} field -> "right_arm").
                    String bone = findPrecedingBoneAccessor(methodInsn, classNodes);
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
     * {@code parent.getXxx()} accessor that returned the bone, then resolves it to the geometry bone
     * name. The most recent {@code INVOKEVIRTUAL get*()->ModelPart} on the parent model is the bone
     * accessor; {@link #resolveAccessorBone} follows it through the model class to the
     * {@code getChild("bone")} string that names the returned field (mooshroom / snow-golem
     * {@code getHead} -&gt; {@code "head"}; iron-golem {@code getFlowerHoldingArm} -&gt; the
     * {@code rightArm} field -&gt; {@code "right_arm"}). When the model walk can't resolve it, falls
     * back to the {@code get}-stripped, first-letter-lower-cased accessor name (matches the direct
     * {@code getHead} -&gt; {@code head} convention).
     *
     * @param call the {@code ModelPart.translateAndRotate} invocation to walk backward from
     * @param classNodes the ClassNode cache, used to load the model class for the accessor walk
     * @return the resolved bone name, or {@code null} when no {@code get*()->ModelPart} accessor
     *     precedes the call
     */
    private static @Nullable String findPrecedingBoneAccessor(@NotNull MethodInsnNode call, @NotNull ClassNodeCache classNodes) {
        // Walk backward over real instructions (AsmKit.previousReal skips pseudo-nodes) and
        // stop at the first INVOKEVIRTUAL get*()->ModelPart accessor. No early abort on other
        // real ops: unlike findPrecedingAxisField, the bone accessor may sit several real
        // instructions before the translateAndRotate call, so this scans until the first match.
        for (AbstractInsnNode node = AsmKit.previousReal(call); node != null; node = AsmKit.previousReal(node)) {
            if (node instanceof MethodInsnNode methodCall
                && methodCall.getOpcode() == Opcodes.INVOKEVIRTUAL
                && methodCall.name.startsWith("get")
                && AsmKit.descriptorReturns(methodCall.desc, VanillaSourceClasses.MODEL_PART)) {
                String resolved = resolveAccessorBone(classNodes, methodCall.owner, methodCall.name);
                if (resolved != null) return resolved;
                String accessor = methodCall.name.substring(3);
                if (accessor.isEmpty()) return null;
                return Character.toLowerCase(accessor.charAt(0)) + accessor.substring(1);
            }
        }
        return null;
    }

    /**
     * Resolves a {@code getXxx()->ModelPart} accessor on {@code modelInternalName} to the geometry
     * bone name it returns, by (1) reading the field the getter returns ({@code getFlowerHoldingArm}
     * -&gt; {@code rightArm}) and (2) finding the {@code getChild("bone")} string that field is
     * assigned in the model's {@code <init>} ({@code rightArm} -&gt; {@code "right_arm"}). Falls back
     * to a camelCase-&gt;snake_case conversion of the field name when the {@code <init>} assignment
     * can't be located (a field bound in a superclass constructor), matching the vanilla
     * {@code this.rightArm = root.getChild("right_arm")} naming convention.
     *
     * @param classNodes the ClassNode cache
     * @param modelInternalName the model class the accessor is invoked on
     * @param accessorName the {@code getXxx} accessor method name
     * @return the resolved bone name, or {@code null} when the accessor is not a simple field getter
     */
    private static @Nullable String resolveAccessorBone(
        @NotNull ClassNodeCache classNodes,
        @NotNull String modelInternalName,
        @NotNull String accessorName
    ) {
        ClassNode model = classNodes.load(modelInternalName);
        if (model == null) return null;
        String field = fieldReturnedByGetter(model, accessorName);
        if (field == null) return null;
        String bone = boneAssignedToField(model, field);
        return bone != null ? bone : ToolingText.camelToSnake(field);
    }

    /**
     * Returns the {@code ModelPart} field name a simple getter {@code accessorName} returns - the
     * {@code aload_0; getfield <field>:ModelPart; areturn} shape ({@code getFlowerHoldingArm} -&gt;
     * {@code rightArm}) - or {@code null} when {@code accessorName} is absent from {@code model} or
     * is not such a getter.
     *
     * @param model the model class node
     * @param accessorName the getter method name
     * @return the returned field name, or {@code null}
     */
    private static @Nullable String fieldReturnedByGetter(@NotNull ClassNode model, @NotNull String accessorName) {
        String modelPartDesc = "L" + MODEL_PART + ";";
        for (MethodNode method : model.methods) {
            if (!accessorName.equals(method.name)) continue;
            if (!method.desc.equals("()" + modelPartDesc)) continue;
            for (AbstractInsnNode in = method.instructions.getFirst(); in != null; in = in.getNext())
                if (in.getOpcode() == Opcodes.GETFIELD
                    && in instanceof FieldInsnNode gf
                    && modelPartDesc.equals(gf.desc))
                    return gf.name;
        }
        return null;
    }

    /**
     * Returns the {@code getChild("bone")} string that {@code field} is assigned in {@code model}'s
     * {@code <init>} - the {@code ldc "right_arm"; getChild; ... putfield rightArm} shape - or
     * {@code null} when the field is not assigned from a {@code getChild} call in this class's
     * constructor (e.g. it is bound in a superclass constructor).
     *
     * @param model the model class node
     * @param field the {@code ModelPart} field name to trace
     * @return the {@code getChild} bone name assigned to {@code field}, or {@code null}
     */
    private static @Nullable String boneAssignedToField(@NotNull ClassNode model, @NotNull String field) {
        MethodNode init = AsmKit.findMethod(model, AsmKit.INIT);
        if (init == null) return null;
        String pendingLdc = null;
        String lastGetChildArg = null;
        for (AbstractInsnNode in = init.instructions.getFirst(); in != null; in = in.getNext()) {
            String literal = AsmKit.readStringLiteral(in);
            if (literal != null) {
                pendingLdc = literal;
                continue;
            }
            if (in.getOpcode() == Opcodes.INVOKEVIRTUAL
                && in instanceof MethodInsnNode mi
                && MODEL_PART.equals(mi.owner) && "getChild".equals(mi.name)) {
                lastGetChildArg = pendingLdc;
                pendingLdc = null;
                continue;
            }
            if (in.getOpcode() == Opcodes.PUTFIELD
                && in instanceof FieldInsnNode pf
                && field.equals(pf.name))
                return lastGetChildArg;
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
     * backs the canonical block id, an optional literal fallback for layers without a variant enum
     * (iron-golem poppy etc), and whether the block is caller-selected at render (enderman carried
     * block, iron-golem flower) rather than an always-present decoration (mooshroom, snow-golem).
     *
     * @param variantClass JVM internal name of the {@code $Variant} enum whose {@code DEFAULT}
     *     constant resolves the canonical block id, or {@code null} for a literal-only / runtime layer
     * @param defaultBlockId literal {@code minecraft:<block>} used when there is no variant class,
     *     or {@code null} when the block has no vanilla literal (enderman's runtime carried block)
     * @param selectable whether the block is supplied at render from {@code EntityAppearance.carried}
     *     rather than always drawn
     */
    private record KnownLayer(@Nullable String variantClass, @Nullable String defaultBlockId, boolean selectable) {}

    /**
     * A literal {@code Blocks.X} binding found in a renderer's {@code extractRenderState}, with
     * whether it is presence-gated on an entity {@code ()Z} predicate (an always-present decoration)
     * or not (a conditional held block).
     *
     * @param blockId the {@code minecraft:<block>} id the renderer binds
     * @param guarded whether the bind is gated on an entity presence predicate ({@code hasPumpkin()})
     */
    private record LiteralBlock(@NotNull String blockId, boolean guarded) {}

    /**
     * One pose-stack op recognised by the walker, mirroring the runtime
     * {@link lib.minecraft.renderer.pipeline.loader.EntityModelLoader.TransformOp} vocabulary: a
     * translate / scale carries three components, a rotation carries a single degree angle about one
     * named axis (X or Y - a Z rotation is never emitted). Each op serialises itself into its
     * {@code {"op": ...}} wire object.
     */
    public sealed interface TransformOp permits Translate, RotateY, RotateX, Scale {
        /**
         * The {@code {"op": ...}} wire object for this op.
         *
         * @return the serialised op
         */
        @NotNull JsonObject toJson();
    }

    /**
     * A {@code PoseStack.translate(x, y, z)} op.
     *
     * @param x translation along X
     * @param y translation along Y
     * @param z translation along Z
     */
    public record Translate(float x, float y, float z) implements TransformOp {
        @Override
        public @NotNull JsonObject toJson() {
            return ToolingJson.object().put("op", "translate").put("x", x).put("y", y).put("z", z).build();
        }
    }

    /**
     * A {@code PoseStack.mulPose(Axis.YP.rotationDegrees(degrees))} op ({@code YN} sign folded in).
     *
     * @param degrees the Y rotation in degrees
     */
    public record RotateY(float degrees) implements TransformOp {
        @Override
        public @NotNull JsonObject toJson() {
            return ToolingJson.object().put("op", "rotate_y").put("degrees", degrees).build();
        }
    }

    /**
     * A {@code PoseStack.mulPose(Axis.XP.rotationDegrees(degrees))} op ({@code XN} sign folded in).
     *
     * @param degrees the X rotation in degrees
     */
    public record RotateX(float degrees) implements TransformOp {
        @Override
        public @NotNull JsonObject toJson() {
            return ToolingJson.object().put("op", "rotate_x").put("degrees", degrees).build();
        }
    }

    /**
     * A {@code PoseStack.scale(x, y, z)} op.
     *
     * @param x scale along X
     * @param y scale along Y
     * @param z scale along Z
     */
    public record Scale(float x, float y, float z) implements TransformOp {
        @Override
        public @NotNull JsonObject toJson() {
            return ToolingJson.object().put("op", "scale").put("x", x).put("y", y).put("z", z).build();
        }
    }

    /**
     * Wire-format-friendly representation of one block overlay row. Carries the block id, an
     * optional bone name the overlay attaches to (its bind pose pre-applies), and the ordered
     * pose-stack op list. Consumed by
     * {@link ToolingEntityModels} which serialises into the
     * {@code block_overlays} JSON array consumed by {@link
     * lib.minecraft.renderer.pipeline.loader.EntityModelLoader.BlockOverlayLayer}.
     *
     * @param blockId the canonical {@code minecraft:<block>} id whose model composites onto the
     *     entity, or {@code null} for a {@link #selectable} overlay with no vanilla literal (enderman
     *     carried block) - the render-time selection supplies it
     * @param attachedBone the parent bone whose bind pose pre-applies to the overlay, or
     *     {@code null} when the overlay attaches at the entity root
     * @param ops the ordered pose-stack ops between one {@code pushPose}/{@code popPose} pair
     * @param selectable whether the block is caller-selected at render (enderman carried block, iron
     *     golem flower) rather than an always-present decoration
     */
    public record Result(
        @Nullable String blockId,
        @Nullable String attachedBone,
        @NotNull List<TransformOp> ops,
        boolean selectable
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
            if (desc.blockId() != null) row.addProperty("block_id", desc.blockId());
            if (desc.attachedBone() != null) row.addProperty("attached_bone", desc.attachedBone());
            if (desc.selectable()) row.addProperty("selectable", true);
            JsonArray opsJson = new JsonArray();
            for (TransformOp op : desc.ops()) opsJson.add(op.toJson());
            row.add("transforms", opsJson);
            rows.add(row);
        }
        return rows;
    }

}
