package lib.minecraft.renderer.tooling.entity;

import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.tooling.kernel.ClassKit;
import lib.minecraft.renderer.tooling.kernel.ClassNodeCache;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lib.minecraft.renderer.tooling.kernel.VanillaSourceClasses;
import lib.minecraft.renderer.tooling.vanilla.BlockRegistryIndex;
import lib.minecraft.renderer.tooling.walk.AsmWalker;
import lib.minecraft.renderer.tooling.walk.Insn;
import lib.minecraft.renderer.tooling.walk.Match;
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

import java.util.ArrayList;
import java.util.List;

/**
 * Resolves the {@code block_overlays[]} array - block-model composites on entity bodies
 * (mooshroom mushrooms, snow-golem pumpkin, iron-golem poppy, enderman carried block). A
 * roster layer qualifies when its typed {@code submit} reads a
 * {@code BlockModelRenderState}-typed field; the block id resolves from the
 * {@code $Variant}-enum {@code DEFAULT}, a presence-gated literal, or stays
 * render-selectable.
 *
 * <p>Block ids resolve through the {@link BlockRegistryIndex} registration walk, and a
 * {@code Z}-axis rotation is emitted as {@code rotate_z}.
 */
final class EntityBlockOverlayResolver {

    private final @NotNull ClassNodeCache cache;
    private final @NotNull EntitySubject subject;
    private final @NotNull List<EntityRendererResolver.LayerSite> roster;
    private final @NotNull BlockRegistryIndex blocks;
    private final @NotNull Diagnostics diagnostics;

    EntityBlockOverlayResolver(
        @NotNull EntityContext context,
        @NotNull List<EntityRendererResolver.LayerSite> roster
    ) {
        this.cache = context.cache();
        this.subject = context.subject();
        this.roster = roster;
        this.blocks = context.indexes().blocks();
        this.diagnostics = context.diagnostics();
    }

    /**
     * The {@code block_overlays} array - one row per {@code pushPose} / {@code popPose}
     * pair of every qualifying roster layer, in roster order - or {@code null} to omit.
     *
     * @return the rows, or {@code null} when no layer qualifies
     */
    @Nullable JsonTree resolve() {
        List<JsonTree> rows = new ArrayList<>();
        for (EntityRendererResolver.LayerSite site : this.roster) {
            ClassNode cn = this.cache.load(site.layerClass());
            if (cn == null) continue;
            if (!EntityOverlayResolver.readsBlockModelRenderState(cn)) continue;
            resolveLayer(site, cn, rows);
        }
        return rows.isEmpty() ? null : JsonTree.arrayOf(rows);
    }

    private void resolveLayer(
        @NotNull EntityRendererResolver.LayerSite site,
        @NotNull ClassNode cn,
        @NotNull List<JsonTree> rows
    ) {
        MethodNode submit = EntityOverlayResolver.typedSubmit(cn);
        if (submit == null) return;
        BlockSource source = classifyBlockSource(cn, submit);
        if (source.blockId() == null && !source.selectable()) {
            this.diagnostics.warn("block layer '%s' default block id unresolved - skipped",
                EntityOverlayResolver.simpleName(site.layerClass()));
            return;
        }
        for (JsonTree transforms : extractPoseBlocks(submit)) {
            JsonTree row = JsonTree.object()
                .put("source", EntityOverlayResolver.simpleName(site.layerClass()))
                .putInt("layer_index", site.layerIndex())
                .putIf("block", source.blockId());
            row.putIf("attached_bone", transforms.findString("attached_bone"));
            if (source.selectable()) row.put("selectable", true);
            row.put("transforms", transforms.find("transforms").orElse(null));
            rows.add(row);
        }
    }

    /**
     * The classified block source of a qualifying layer.
     *
     * @param blockId the canonical default block id, or {@code null} for a literal-less
     *     selectable row (the enderman carried block)
     * @param selectable whether the render-time caller supplies the block
     */
    private record BlockSource(@Nullable String blockId, boolean selectable) {}

    /**
     * Classifies how the layer's block resolves: a {@code $Variant} enum on the render
     * state ({@code DEFAULT} pick - mooshroom), a literal {@code Blocks.X} bind in
     * {@code extractRenderState} (fixed when presence-gated, selectable when timer-gated),
     * or fully render-selected (no literal).
     */
    private @NotNull BlockSource classifyBlockSource(@NotNull ClassNode cn, @NotNull MethodNode submit) {
        String stateRef = VanillaSourceClasses.Descs.ref(VanillaSourceClasses.Types.BLOCK_MODEL_RENDER_STATE);
        FieldInsnNode stateRead = AsmWalker.over(submit)
            .ofType(FieldInsnNode.class)
            .first(fi -> fi.getOpcode() == Opcodes.GETFIELD && stateRef.equals(fi.desc));
        if (stateRead == null) return new BlockSource(null, true);

        ClassNode stateCn = this.cache.load(stateRead.owner);
        if (stateCn != null) {
            String variantSuffix = EntityNamingPolicies.VARIANT_DESCRIPTOR_SUFFIX.stringValue();
            for (FieldNode field : stateCn.fields) {
                if (field.desc == null || !field.desc.startsWith("L") || !field.desc.endsWith(variantSuffix)) continue;
                String variantClass = field.desc.substring(1, field.desc.length() - 1);
                return new BlockSource(resolveVariantDefaultBlock(variantClass), false);
            }
        }

        LiteralBlock literal = resolveLiteralBlock(stateRead.name);
        if (literal != null)
            // A presence-gated literal is a fixed always-present decoration; a
            // timer-gated one is a selectable held block.
            return new BlockSource(literal.blockId(),
                literal.guarded() != EntityOverlayPolicies.PRESENCE_GATE_FIXED_WHEN_GUARDED.booleanValue());
        return new BlockSource(null, true);
    }

    /**
     * The {@code $Variant} enum's canonical block - the {@code DEFAULT} alias's entry in the
     * enum's block table. The coats that wrap a different one carry it on their own variant
     * option, so the row itself only ever names the default.
     */
    private @Nullable String resolveVariantDefaultBlock(@NotNull String variantClass) {
        return VariantBlockTable.of(this.cache, this.blocks, variantClass, this.diagnostics).defaultBlockId();
    }

    /**
     * A literal {@code Blocks.X} bind in the renderer chain's {@code extractRenderState},
     * with its presence-guard classification.
     *
     * @param blockId the registered block id
     * @param guarded whether an entity {@code ()Z} presence predicate gates the bind
     */
    private record LiteralBlock(@NotNull String blockId, boolean guarded) {}

    private @Nullable LiteralBlock resolveLiteralBlock(@Nullable String blockFieldName) {
        if (blockFieldName == null) return null;
        LiteralBlock[] out = new LiteralBlock[1];
        ClassKit.walkSuperChain(this.cache, this.subject.rendererClass(), cn -> {
            if (out[0] != null) return;
            for (MethodNode method : cn.methods) {
                if (!VanillaSourceClasses.Methods.EXTRACT_RENDER_STATE.equals(method.name)) continue;
                String blocksField = findLiteralBlockUpdate(method, blockFieldName);
                if (blocksField == null) continue;
                BlockRegistryIndex.Entry entry = this.blocks.byField(blocksField);
                if (entry == null) continue;
                out[0] = new LiteralBlock(entry.id(), hasEntityBooleanGuard(method));
                return;
            }
        });
        return out[0];
    }

    /**
     * The {@code Blocks.X} field a {@code BlockModelResolver.update(...)} call pairs with a
     * {@code GETFIELD <blockFieldName>} target; the backward walk stops at the previous
     * {@code update} so per-statement args never bleed across calls.
     */
    private static @Nullable String findLiteralBlockUpdate(@NotNull MethodNode method, @NotNull String blockFieldName) {
        String stateRef = VanillaSourceClasses.Descs.ref(VanillaSourceClasses.Types.BLOCK_MODEL_RENDER_STATE);
        return AsmWalker.over(method)
            .invokeVirtual(VanillaSourceClasses.Types.BLOCK_MODEL_RESOLVER, VanillaSourceClasses.Methods.UPDATE)
            .firstNotNull(update -> {
                AsmWalker window = AsmWalker.before(update)
                    .until(Insn.invokeVirtual(VanillaSourceClasses.Types.BLOCK_MODEL_RESOLVER, VanillaSourceClasses.Methods.UPDATE));
                String blocksField = window.getStatic(VanillaSourceClasses.Types.BLOCKS).names().last();
                return blocksField != null && window.getField(blockFieldName, stateRef).any() ? blocksField : null;
            });
    }

    /**
     * The presence-flag shape: a parameterless boolean call on the entity parameter
     * ({@code hasPumpkin()}) - present marks the bind guarded (fixed), absent marks it a
     * timer / runtime gate (selectable).
     */
    private static boolean hasEntityBooleanGuard(@NotNull MethodNode method) {
        Type[] args = ClassKit.argTypes(method.desc);
        if (args.length == 0 || args[0].getSort() != Type.OBJECT) return false;
        String entityClass = args[0].getInternalName();
        return AsmWalker.over(method).any(in -> in.getOpcode() == Opcodes.INVOKEVIRTUAL && in instanceof MethodInsnNode mi
            && entityClass.equals(mi.owner) && mi.desc.endsWith(")Z"));
    }

    // ------------------------------------------------------------------------------------
    // pose-op extraction
    // ------------------------------------------------------------------------------------

    /**
     * Splits the typed submit on {@code pushPose} / {@code popPose} pairs, each pair
     * yielding a carrier node {@code {attached_bone?, transforms[]}} of the recognised
     * pose-stack ops in bytecode order. The float pseudo-stack consumes literal pushes
     * most-recent-first for each op.
     */
    private @NotNull List<JsonTree> extractPoseBlocks(@NotNull MethodNode submit) {
        List<JsonTree> out = new ArrayList<>();
        boolean insideBlock = false;
        JsonTree transforms = null;
        String attachedBone = null;
        List<Float> floats = new ArrayList<>();
        int opCount = 0;

        for (AbstractInsnNode in : submit.instructions) {
            Float literal = AsmWalker.floatLiteral(in);
            if (literal != null) {
                floats.add(literal);
                continue;
            }
            if (!(in instanceof MethodInsnNode call)) continue;

            if (AsmWalker.isInvokeVirtual(in, VanillaSourceClasses.Types.POSE_STACK, VanillaSourceClasses.Methods.PUSH_POSE)) {
                insideBlock = true;
                transforms = JsonTree.array();
                attachedBone = null;
                floats.clear();
                opCount = 0;
                continue;
            }
            if (AsmWalker.isInvokeVirtual(in, VanillaSourceClasses.Types.POSE_STACK, VanillaSourceClasses.Methods.POP_POSE)) {
                if (insideBlock && opCount > 0) {
                    JsonTree carrier = JsonTree.object();
                    carrier.putIf("attached_bone", attachedBone);
                    carrier.put("transforms", transforms);
                    out.add(carrier);
                }
                insideBlock = false;
                transforms = null;
                attachedBone = null;
                floats.clear();
                continue;
            }
            if (!insideBlock || transforms == null) continue;

            if (AsmWalker.isInvokeVirtual(in, VanillaSourceClasses.Types.POSE_STACK, VanillaSourceClasses.Methods.TRANSLATE)
                && call.desc.startsWith("(FFF") && floats.size() >= 3) {
                float z = floats.removeLast();
                float y = floats.removeLast();
                float x = floats.removeLast();
                transforms.add(JsonTree.object().put("op", "translate").put("x", x).put("y", y).put("z", z));
                opCount++;
                continue;
            }
            if (AsmWalker.isInvokeVirtual(in, VanillaSourceClasses.Types.POSE_STACK, VanillaSourceClasses.Methods.SCALE)
                && call.desc.startsWith("(FFF") && floats.size() >= 3) {
                float z = floats.removeLast();
                float y = floats.removeLast();
                float x = floats.removeLast();
                transforms.add(JsonTree.object().put("op", "scale").put("x", x).put("y", y).put("z", z));
                opCount++;
                continue;
            }
            // Axis is an interface, so rotationDegrees dispatches INVOKEINTERFACE - match
            // by owner + name, opcode-agnostic.
            if (VanillaSourceClasses.Types.MATH_AXIS.equals(call.owner)
                && VanillaSourceClasses.Methods.ROTATION_DEGREES.equals(call.name)) {
                if (floats.isEmpty()) continue;
                float degrees = floats.removeLast();
                String axis = findPrecedingAxisField(call);
                if (axis == null || axis.length() < 2) continue;
                // `?P` is a positive rotation about the axis, `?N` negates the angle; a Z
                // axis is emitted.
                if (axis.charAt(1) == 'N') degrees = -degrees;
                String op = switch (axis.charAt(0)) {
                    case 'X' -> "rotate_x";
                    case 'Y' -> "rotate_y";
                    case 'Z' -> "rotate_z";
                    default -> null;
                };
                if (op == null) {
                    this.diagnostics.warn("unrecognised rotation axis field '%s' - op skipped", axis);
                    continue;
                }
                transforms.add(JsonTree.object().put("op", op).put("degrees", degrees));
                opCount++;
                continue;
            }
            if (AsmWalker.isInvokeVirtual(in, VanillaSourceClasses.Types.MODEL_PART, VanillaSourceClasses.Methods.TRANSLATE_AND_ROTATE)) {
                String bone = findPrecedingBoneAccessor(call);
                if (bone != null) attachedBone = bone;
            }
        }
        return out;
    }

    /**
     * The {@code Axis.<X>} field name behind a {@code rotationDegrees} call.
     */
    private static @Nullable String findPrecedingAxisField(@NotNull MethodInsnNode call) {
        AbstractInsnNode hit = AsmWalker.before(call).real().first(
            node -> AsmWalker.isGetStatic(node, VanillaSourceClasses.Types.MATH_AXIS),
            node -> {
                int opcode = node.getOpcode();
                return opcode == Opcodes.LDC
                    || opcode == Opcodes.FCONST_0 || opcode == Opcodes.FCONST_1 || opcode == Opcodes.FCONST_2;
            });
        return hit == null ? null : ((FieldInsnNode) hit).name;
    }

    /**
     * The bone a {@code translateAndRotate} pre-applies: the most recent
     * {@code get*()->ModelPart} accessor, resolved through the model class to its
     * {@code getChild} string, with getter-name / snake_case fallbacks.
     */
    private @Nullable String findPrecedingBoneAccessor(@NotNull MethodInsnNode call) {
        AbstractInsnNode hit = AsmWalker.before(call).real().first(in ->
            in instanceof MethodInsnNode accessor
                && accessor.getOpcode() == Opcodes.INVOKEVIRTUAL
                && accessor.name.startsWith("get")
                && ClassKit.descriptorReturns(accessor.desc, VanillaSourceClasses.Types.MODEL_PART));
        if (!(hit instanceof MethodInsnNode accessor)) return null;
        String resolved = resolveAccessorBone(accessor.owner, accessor.name);
        if (resolved != null) return resolved;
        String stem = accessor.name.substring(3);
        // Fallback: getter-name decapitalisation when the field trace misses.
        return stem.isEmpty() ? null : Character.toLowerCase(stem.charAt(0)) + stem.substring(1);
    }

    /**
     * A {@code getXxx()->ModelPart} accessor resolved to the geometry bone it returns: the
     * getter's returned field, then the {@code getChild("bone")} string that field is
     * assigned in the model {@code <init>}, with a snake_case fallback for superclass-bound
     * fields.
     */
    private @Nullable String resolveAccessorBone(@NotNull String modelInternalName, @NotNull String accessorName) {
        ClassNode model = this.cache.load(modelInternalName);
        if (model == null) return null;
        String field = fieldReturnedByGetter(model, accessorName);
        if (field == null) return null;
        String bone = boneAssignedToField(model, field);
        return bone != null ? bone : EntityOverlayResolver.axisToken(field);
    }

    /**
     * The field a simple {@code ()->ModelPart} getter returns, or {@code null}.
     */
    private static @Nullable String fieldReturnedByGetter(@NotNull ClassNode model, @NotNull String accessorName) {
        String returnDesc = "()" + VanillaSourceClasses.Descs.MODEL_PART_REF;
        for (MethodNode method : model.methods) {
            if (!accessorName.equals(method.name) || !returnDesc.equals(method.desc)) continue;
            AbstractInsnNode hit = AsmWalker.over(method).first(in ->
                in.getOpcode() == Opcodes.GETFIELD && in instanceof FieldInsnNode fi
                    && VanillaSourceClasses.Descs.MODEL_PART_REF.equals(fi.desc));
            if (hit != null) return ((FieldInsnNode) hit).name;
        }
        return null;
    }

    /**
     * The {@code getChild} string a model {@code <init>} assigns into {@code field}, or {@code null}.
     */
    private static @Nullable String boneAssignedToField(@NotNull ClassNode model, @NotNull String field) {
        MethodNode init = ClassKit.findMethod(model, ClassKit.INIT);
        if (init == null) return null;
        Match<MethodInsnNode> getChild =
            Insn.invokeVirtual(VanillaSourceClasses.Types.MODEL_PART, VanillaSourceClasses.Methods.GET_CHILD);
        FieldInsnNode assignment = AsmWalker.over(init)
            .ofType(FieldInsnNode.class)
            .first(fi -> fi.getOpcode() == Opcodes.PUTFIELD && field.equals(fi.name));
        if (assignment == null) return null;
        // The assignment binds the most recent getChild - even one that consumed no literal
        // of its own - so the literal search is scoped to that call's window alone.
        MethodInsnNode lastGetChild = AsmWalker.before(assignment).first(getChild);
        if (lastGetChild == null) return null;
        return AsmWalker.before(lastGetChild)
            .until(getChild)
            .mapNotNull(AsmWalker::stringLiteral)
            .first();
    }

}
