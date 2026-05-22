package lib.minecraft.renderer.tooling.entity;

import lib.minecraft.renderer.tooling.util.AsmKit;
import lib.minecraft.renderer.tooling.util.Diagnostics;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipFile;

/**
 * Walks a renderer's {@code scale(state, poseStack)} override for {@code poseStack.scale(F, F, F)}
 * invocations and extracts the effective uniform scale that vanilla applies at zero state
 * before submitting the entity mesh. Mirrors the two non-geometry-foldable vanilla overrides
 * that survive the harness's frozen state:
 * <ul>
 *   <li>{@code SlimeRenderer.scale} chains {@code poseStack.scale(0.999F, 0.999F, 0.999F)} with
 *       a state-dependent {@code scale(w * size, 1/w * size, w * size)} that collapses to
 *       identity at zero state ({@code squish=0, size=1}). The walker treats the literal call's
 *       0.999 as the product and skips the non-literal second call - matching the runtime fold.</li>
 *   <li>{@code WitherBossRenderer.scale} stores {@code 2.0f} into a local slot before a
 *       conditional adjustment based on {@code state.invulnerableTicks} (zero at rest); the
 *       single {@code poseStack.scale(scale, scale, scale)} call then loads slot 3 three times.
 *       The walker tracks FSTORE-of-literal up to the first conditional branch, freezes the
 *       slot map, then resolves FLOAD references at the scale() invocation.</li>
 * </ul>
 * Other renderers that override {@code scale} ({@code MagmaCubeRenderer}, {@code GhastRenderer},
 * {@code PolarBearRenderer}, ...) already fold their constant scale into the geometry's bone
 * {@code pivot}+{@code scale} fields via {@link EntityLayerDefinitionResolver}'s
 * {@code MeshTransformer.scaling} extraction; the runtime would double-apply if the renderer
 * Map ALSO emitted a non-1 value, so the resolver returns {@code null} when nothing literal
 * surfaces.
 *
 * <p>Detection shape (slot tracking variant):
 * <pre>
 *   ...FCONST_2 / LDC F            (literal push)
 *   FSTORE N                       (slot[N] = literal, only honoured before first IF* branch)
 *   ...                            (any non-branching instructions; second FSTORE of same slot
 *                                   overwrites; first conditional branch freezes the map)
 *   ALOAD poseStack
 *   FLOAD N / FCONST_* / LDC F    (3 arg pushes; FLOADs resolve via slot map, others read literal)
 *   INVOKEVIRTUAL PoseStack.scale(FFF)V
 * </pre>
 * Non-resolvable arg (FLOAD of unknown slot, GETFIELD, computed expression) treats the entire
 * call as identity and contributes 1.0 to the product - matching vanilla's zero-state
 * evaluation where state-dependent expressions fold to identity.
 */
@UtilityClass
public final class EntityRendererScaleResolver {

    private static final @NotNull String SCALE = "scale";
    private static final @NotNull String POSE_STACK = "com/mojang/blaze3d/vertex/PoseStack";
    private static final @NotNull String POSE_STACK_SCALE_DESC = "(FFF)V";

    /**
     * Tolerance used when checking that the three scale args are uniform. Vanilla's literal
     * scales are written {@code (F, F, F)} or {@code (scale, scale, scale)} from a single
     * slot - any drift beyond this implies a non-uniform expression that the resolver treats
     * as identity.
     */
    private static final float UNIFORM_SCALE_TOLERANCE = 1e-5f;

    /**
     * Returns the effective uniform scale the renderer's {@code scale} override applies at
     * zero state, or {@code null} when:
     * <ul>
     *   <li>the renderer doesn't override {@code scale} (inherits identity from
     *       {@code MobRenderer}),</li>
     *   <li>no {@code poseStack.scale(F, F, F)} invocation produces a resolvable uniform
     *       value (all calls use non-literal / non-uniform / unresolved-slot args),</li>
     *   <li>the product collapses to {@code 1.0} within tolerance.</li>
     * </ul>
     */
    public static @Nullable Float resolve(@NotNull ZipFile zip, @NotNull String rendererInternalName, @NotNull Diagnostics diag) {
        ClassNode cn = AsmKit.loadClass(zip, rendererInternalName);
        if (cn == null) return null;
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
        diag.info("renderer-scale: '%s' -> %.6f from poseStack.scale chain", rendererInternalName, accum);
        return accum;
    }

    /**
     * Locates the renderer's scale method that takes its OWN render state class as the first
     * parameter (rather than the {@code LivingEntityRenderState} synthetic bridge which just
     * delegates back to the typed override via checkcast). Prefers methods whose first param
     * descriptor ends with {@code RenderState;} but NOT {@code LivingEntityRenderState;} -
     * the bridge variant is skipped because its body is one {@code invokevirtual scale}
     * forwarding call with no scale literals to extract.
     */
    private static @Nullable MethodNode findPrimaryScaleMethod(@NotNull ClassNode cn) {
        String descSuffix = ";L" + POSE_STACK + ";)V";
        String livingState = "Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;L" + POSE_STACK + ";)V";
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
     * {@code FSTORE N} preceded by a float literal push into the slot map. Branches freeze
     * the map: assignments inside conditional bodies (wither's invulnerability adjustment)
     * never reach the map because the zero-state walker can't pick between branch arms.
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
     * Returns {@code true} when the opcode is any conditional or unconditional branch that
     * would split the linear walk. Honoured opcodes: {@code IFEQ}..{@code IF_ACMPNE},
     * {@code GOTO}, {@code JSR}, {@code IFNULL}, {@code IFNONNULL}, {@code TABLESWITCH},
     * {@code LOOKUPSWITCH}, {@code RETURN}/{@code XRETURN}/{@code ATHROW} (method exit).
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
     * tolerance). Each arg resolves either as a direct float literal ({@code FCONST_*} /
     * {@code LDC F}) or as an {@code FLOAD} of a slot listed in the pre-branch slot map.
     * Returns {@code null} otherwise.
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
     * Resolves a single float-arg push to its zero-state value. Tries the direct literal path
     * ({@code AsmKit.readFloatLiteral}) first, then falls back to {@code FLOAD N} resolution
     * via the pre-branch slot map. Returns {@code null} when neither path produces a value.
     */
    private static @Nullable Float resolveFloatArg(@Nullable AbstractInsnNode node, @NotNull Map<Integer, Float> slotLiterals) {
        if (node == null) return null;
        Float literal = AsmKit.readFloatLiteral(node);
        if (literal != null) return literal;
        if (node.getOpcode() == Opcodes.FLOAD && node instanceof VarInsnNode v) return slotLiterals.get(v.var);
        return null;
    }
}
