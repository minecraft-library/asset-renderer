package lib.minecraft.renderer.tooling.entity;

import lib.minecraft.renderer.tooling.util.AsmKit;
import lib.minecraft.renderer.tooling.util.ClassNodeCache;
import lib.minecraft.renderer.tooling.util.Diagnostics;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.HashMap;
import java.util.Map;

/**
 * Per-entity walker for the two surviving non-geometry-foldable renderer overrides:
 *
 * <ul>
 *   <li><b>{@code setupRotations} yaw addend.</b> {@code ShulkerRenderer.setupRotations} is the
 *       single vanilla override that survives the frozen-state harness mixin; it calls
 *       {@code super.setupRotations(state, ps, bodyRot + 180.0F, entityScale)}. The walker
 *       extracts the literal {@code N} the override adds to {@code bodyRot} before delegating;
 *       returns {@code 0f} for the dozens of pass-through overrides (squid / pufferfish /
 *       drowned / ...) whose conditional branches collapse to identity at zero state.</li>
 *   <li><b>{@code scale} uniform-scale residue.</b> {@code SlimeRenderer.scale} chains
 *       {@code poseStack.scale(0.999F, 0.999F, 0.999F)} with a state-dependent factor that
 *       collapses to identity at zero state; {@code WitherBossRenderer.scale} stores
 *       {@code 2.0f} into a local slot then calls {@code scale(scale, scale, scale)}. The
 *       walker tracks pre-branch FSTORE-of-literal into a slot map, then resolves FLOAD args
 *       at the {@code PoseStack.scale} invocation. Returns {@code null} when the product
 *       collapses to 1.0 within tolerance OR no resolvable literal surfaces (renderers whose
 *       constant scale is already folded into the bone {@code pivot}+{@code scale} fields
 *       via {@code MeshTransformer.scaling}).</li>
 * </ul>
 *
 * <p>Both walks target the same renderer class for adjacent overrides; one resolver call
 * produces both outputs in a single {@link Result} and avoids two separate
 * {@code ClassNodeCache#load} hits on the renderer class.
 */
@UtilityClass
public final class EntityRendererOverrides {

    private static final @NotNull String SETUP_ROTATIONS = "setupRotations";
    private static final @NotNull String SCALE = "scale";
    private static final @NotNull String POSE_STACK = "com/mojang/blaze3d/vertex/PoseStack";
    private static final @NotNull String POSE_STACK_SCALE_DESC = "(FFF)V";

    /**
     * The local-variable slot of the {@code bodyRot} parameter on an instance
     * {@code setupRotations} method. Layout: {@code this} (slot 0), {@code state} (slot 1),
     * {@code poseStack} (slot 2), {@code bodyRot} (slot 3 - first float), {@code entityScale}
     * (slot 4 - second float).
     */
    private static final int BODY_ROT_SLOT = 3;

    /**
     * The local-variable slot of the {@code entityScale} parameter on an instance
     * {@code setupRotations} method.
     */
    private static final int SCALE_SLOT = 4;

    /**
     * Tolerance used when checking that the three {@code scale} args are uniform. Vanilla's
     * literal scales are written {@code (F, F, F)} or {@code (scale, scale, scale)} from a
     * single slot; any drift beyond this implies a non-uniform expression the resolver treats
     * as identity.
     */
    private static final float UNIFORM_SCALE_TOLERANCE = 1e-5f;

    /**
     * Yaw addend (degrees) + uniform scale residue extracted from one renderer's overrides.
     *
     * @param setupYawAddend literal degrees the renderer's {@code setupRotations} override
     *     folds into {@code bodyRot}; {@code 0f} when the override is absent / pass-through /
     *     non-literal
     * @param rendererScale uniform scale product of every literal {@code poseStack.scale(F,F,F)}
     *     in the {@code scale} override; {@code null} when the override is absent / no literal
     *     surfaces / the product collapses to {@code 1.0} within tolerance
     */
    public record Result(float setupYawAddend, @Nullable Float rendererScale) {}

    /**
     * Runs both override walks against the renderer class and folds their outputs into a
     * single {@link Result}. Loading the renderer class once feeds both the
     * {@code setupRotations} yaw-addend walk and the {@code scale} residue walk.
     *
     * @param classNodes the shared class-node cache
     * @param rendererInternalName the renderer class internal name
     * @param diagnostics the run diagnostics sink (scale extractions are logged at info level)
     * @return the combined yaw-addend + scale-residue result; {@code Result(0f, null)} when the
     *     renderer class cannot be loaded
     */
    public static @NotNull Result resolve(@NotNull ClassNodeCache classNodes, @NotNull String rendererInternalName, @NotNull Diagnostics diagnostics) {
        ClassNode cn = classNodes.load(rendererInternalName);
        if (cn == null) return new Result(0f, null);

        float yawAddend = resolveSetupYawAddend(cn);
        Float scaleResidue = resolveRendererScale(cn, rendererInternalName, diagnostics);
        return new Result(yawAddend, scaleResidue);
    }

    /**
     * Locates a {@code setupRotations} method declared directly on the renderer class (not
     * inherited). The method has 4 args: state, poseStack, bodyRot (float), entityScale
     * (float). Matched by descriptor suffix {@code FF)V} since the state parameter's specific
     * type varies per renderer.
     */
    private static float resolveSetupYawAddend(@NotNull ClassNode cn) {
        MethodNode setupRotations = null;
        for (MethodNode m : cn.methods) {
            if (!SETUP_ROTATIONS.equals(m.name)) continue;
            if (m.desc != null && m.desc.endsWith("FF)V")) {
                setupRotations = m;
                break;
            }
        }
        if (setupRotations == null) return 0f;
        return extractYawAddend(setupRotations);
    }

    /**
     * Walks {@code setupRotations} for {@code INVOKESPECIAL <super>.setupRotations} and
     * inspects the four bytecode instructions immediately preceding it.
     */
    private static float extractYawAddend(@NotNull MethodNode method) {
        for (AbstractInsnNode in = method.instructions.getFirst(); in != null; in = in.getNext()) {
            if (in.getOpcode() != Opcodes.INVOKESPECIAL) continue;
            if (!(in instanceof MethodInsnNode mi)) continue;
            if (!SETUP_ROTATIONS.equals(mi.name)) continue;

            AbstractInsnNode scaleLoad = AsmKit.previousReal(in);
            if (!isFloadOf(scaleLoad, SCALE_SLOT)) return 0f;
            AbstractInsnNode beforeScale = AsmKit.previousReal(scaleLoad);
            // Pass-through shape: ...; FLOAD 3; FLOAD 4; INVOKESPECIAL super -> addend=0.
            if (isFloadOf(beforeScale, BODY_ROT_SLOT)) return 0f;
            // Addend shape: ...; FLOAD 3; LDC C; FADD; FLOAD 4; INVOKESPECIAL super -> addend=C.
            if (beforeScale != null && beforeScale.getOpcode() == Opcodes.FADD) {
                AbstractInsnNode constInsn = AsmKit.previousReal(beforeScale);
                Float c = constInsn == null ? null : AsmKit.readFloatLiteral(constInsn);
                AbstractInsnNode bodyRotLoad = constInsn == null ? null : AsmKit.previousReal(constInsn);
                if (c != null && isFloadOf(bodyRotLoad, BODY_ROT_SLOT)) return c;
            }
            return 0f;
        }
        return 0f;
    }

    /**
     * Returns the uniform scale extracted from the renderer's {@code scale} override, or
     * {@code null} when no literal surfaces or the product collapses to identity.
     */
    private static @Nullable Float resolveRendererScale(@NotNull ClassNode cn, @NotNull String rendererInternalName, @NotNull Diagnostics diagnostics) {
        MethodNode scaleMethod = findPrimaryScaleMethod(cn);
        if (scaleMethod == null) return null;

        Map<Integer, Float> slotLiterals = collectPreBranchFstores(scaleMethod);

        float accum = 1f;
        boolean anyResolved = false;
        for (AbstractInsnNode in = scaleMethod.instructions.getFirst(); in != null; in = in.getNext()) {
            if (!AsmKit.isInvokeVirtual(in, POSE_STACK, SCALE, POSE_STACK_SCALE_DESC)) continue;
            Float xyz = readUniformScaleArgs(in, slotLiterals);
            if (xyz == null) continue;
            accum *= xyz;
            anyResolved = true;
        }
        if (!anyResolved) return null;
        if (Math.abs(accum - 1f) <= UNIFORM_SCALE_TOLERANCE) return null;
        diagnostics.info("renderer-scale: '%s' -> %.6f from poseStack.scale chain", rendererInternalName, accum);
        return accum;
    }

    /**
     * Locates the renderer's scale method that takes its OWN render state class as the first
     * parameter; the {@code LivingEntityRenderState} bridge variant is preferred only when no
     * typed override exists (its body just forwards via checkcast and carries no scale
     * literals to extract).
     */
    private static @Nullable MethodNode findPrimaryScaleMethod(@NotNull ClassNode cn) {
        String descSuffix = ";L" + POSE_STACK + ";)V";
        String livingState = "L" + lib.minecraft.renderer.tooling.util.VanillaSourceClasses.LIVING_ENTITY_RENDER_STATE + ";L" + POSE_STACK + ";)V";
        MethodNode bridge = null;
        for (MethodNode m : cn.methods) {
            if (!SCALE.equals(m.name)) continue;
            if (m.desc == null || !m.desc.endsWith(descSuffix)) continue;
            if (m.desc.endsWith(livingState)) {
                bridge = m;
                continue;
            }
            return m;
        }
        return bridge;
    }

    /**
     * Walks the method from start to the first conditional-branch opcode and collects every
     * {@code FSTORE N} preceded by a float literal push into the slot map.
     */
    private static @NotNull Map<Integer, Float> collectPreBranchFstores(@NotNull MethodNode method) {
        Map<Integer, Float> slots = new HashMap<>();
        for (AbstractInsnNode in = method.instructions.getFirst(); in != null; in = in.getNext()) {
            int op = in.getOpcode();
            if (isBranchOp(op)) break;
            if (op != Opcodes.FSTORE) continue;
            if (!(in instanceof VarInsnNode v)) continue;
            AbstractInsnNode prev = AsmKit.previousReal(in);
            Float lit = prev == null ? null : AsmKit.readFloatLiteral(prev);
            if (lit != null) slots.put(v.var, lit);
        }
        return slots;
    }

    /**
     * {@code true} when the opcode would split the linear walk (conditional / unconditional
     * branch or method exit).
     */
    private static boolean isBranchOp(int op) {
        if (op >= Opcodes.IFEQ && op <= Opcodes.IF_ACMPNE) return true;
        return op == Opcodes.GOTO
            || op == Opcodes.JSR
            || op == Opcodes.IFNULL
            || op == Opcodes.IFNONNULL
            || op == Opcodes.TABLESWITCH
            || op == Opcodes.LOOKUPSWITCH
            || op == Opcodes.RETURN
            || op == Opcodes.IRETURN
            || op == Opcodes.LRETURN
            || op == Opcodes.FRETURN
            || op == Opcodes.DRETURN
            || op == Opcodes.ARETURN
            || op == Opcodes.ATHROW;
    }

    /**
     * Reads the three arguments preceding an {@code INVOKEVIRTUAL PoseStack.scale(FFF)V} and
     * returns the uniform scale value when all three are resolvable AND identical (within
     * tolerance).
     */
    private static @Nullable Float readUniformScaleArgs(@NotNull AbstractInsnNode invoke, @NotNull Map<Integer, Float> slotLiterals) {
        AbstractInsnNode z = AsmKit.previousReal(invoke);
        Float zVal = resolveFloatArg(z, slotLiterals);
        if (zVal == null) return null;
        AbstractInsnNode y = AsmKit.previousReal(z);
        Float yVal = resolveFloatArg(y, slotLiterals);
        if (yVal == null) return null;
        AbstractInsnNode x = AsmKit.previousReal(y);
        Float xVal = resolveFloatArg(x, slotLiterals);
        if (xVal == null) return null;
        if (Math.abs(xVal - yVal) > UNIFORM_SCALE_TOLERANCE || Math.abs(yVal - zVal) > UNIFORM_SCALE_TOLERANCE) return null;
        return xVal;
    }

    /**
     * Resolves a single float-arg push to its zero-state value via direct literal or slot map
     * FLOAD lookup. Returns {@code null} when neither path produces a value.
     */
    private static @Nullable Float resolveFloatArg(@Nullable AbstractInsnNode node, @NotNull Map<Integer, Float> slotLiterals) {
        if (node == null) return null;
        Float literal = AsmKit.readFloatLiteral(node);
        if (literal != null) return literal;
        if (node.getOpcode() == Opcodes.FLOAD && node instanceof VarInsnNode v) return slotLiterals.get(v.var);
        return null;
    }

    /**
     * {@code true} when {@code in} is an {@code FLOAD} of the given local slot.
     */
    private static boolean isFloadOf(@Nullable AbstractInsnNode in, int slot) {
        return in != null
            && in.getOpcode() == Opcodes.FLOAD
            && in instanceof VarInsnNode v
            && v.var == slot;
    }

}
