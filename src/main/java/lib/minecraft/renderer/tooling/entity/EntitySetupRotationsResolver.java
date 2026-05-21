package lib.minecraft.renderer.tooling.entity;

import lib.minecraft.renderer.tooling.util.AsmKit;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.zip.ZipFile;

/**
 * Walks a renderer's {@code setupRotations} method (when overridden) for the
 * {@code super.setupRotations(state, poseStack, bodyRot + N, scale)} pattern and extracts the
 * constant {@code N} the override adds to {@code bodyRot} before the super call. Mirrors the
 * single vanilla override that survives the frozen-state harness mixin:
 * {@code ShulkerRenderer.setupRotations} calls
 * {@code super.setupRotations(state, ps, bodyRot + 180.0F, entityScale)} which folds into a
 * yaw-axis addend the {@link lib.minecraft.renderer.EntityRenderer EntityRenderer} composes
 * onto the user-supplied rotation before the iso pose. Other vanilla overrides (squid,
 * pufferfish, drowned, ...) either modify other transform-stack ops or are conditional on
 * runtime state that collapses to identity under {@code SkipSetupAnimMixin}; this resolver
 * surfaces them as addend = 0 by design.
 *
 * <p>Detection is intentionally narrow: matches only the linear {@code FLOAD bodyRot; LDC N;
 * FADD; FLOAD scale; INVOKESPECIAL super.setupRotations} or {@code FLOAD bodyRot; FLOAD scale;
 * INVOKESPECIAL super.setupRotations} (no addend = 0) bytecode shape, ignoring any conditional
 * branches above the call site. A renderer whose override adds non-yaw transforms (squid's
 * translate, drowned's xRot lerp) trivially trips the "no FADD on bodyRot" gate and reports 0;
 * the runtime falls back to the auto-fit centring that already cancels pure translates.
 */
@UtilityClass
public final class EntitySetupRotationsResolver {

    private static final @NotNull String SETUP_ROTATIONS = "setupRotations";

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
     * Returns the yaw addend (degrees) the renderer's {@code setupRotations} override folds
     * into {@code bodyRot} before delegating to {@code super.setupRotations}, or {@code 0f}
     * when:
     * <ul>
     *   <li>the renderer doesn't override {@code setupRotations},</li>
     *   <li>the override modifies non-{@code bodyRot} arguments only,</li>
     *   <li>the override's {@code bodyRot} expression isn't a literal float add (e.g. uses a
     *       runtime computation that the harness's frozen state collapses to identity).</li>
     * </ul>
     * Walks the immediate renderer class only - inherited {@code setupRotations}
     * implementations get the standard pass-through and produce addend {@code 0}.
     */
    public static float resolve(@NotNull ZipFile zip, @NotNull String rendererInternalName) {
        ClassNode cn = AsmKit.loadClass(zip, rendererInternalName);
        if (cn == null) return 0f;
        MethodNode setupRotations = findOwnSetupRotations(cn);
        if (setupRotations == null) return 0f;
        return extractAddend(setupRotations);
    }

    /**
     * Locates a {@code setupRotations} method declared directly on the renderer class
     * (not inherited). The method has 4 args: state, poseStack, bodyRot (float), entityScale
     * (float). Matched by descriptor suffix {@code FF)V} since the state parameter's specific
     * type varies per renderer (generics erase to bounds).
     */
    private static MethodNode findOwnSetupRotations(@NotNull ClassNode cn) {
        for (MethodNode m : cn.methods) {
            if (!SETUP_ROTATIONS.equals(m.name)) continue;
            if (m.desc != null && m.desc.endsWith("FF)V")) return m;
        }
        return null;
    }

    /**
     * Walks {@code setupRotations} for {@code INVOKESPECIAL <super>.setupRotations} and inspects
     * the four bytecode instructions immediately preceding it - the args pushed for the super
     * call. The expected shape for a yaw-addend override is:
     * <pre>
     *   FLOAD bodyRot   ; FLOAD 3
     *   LDC C           ; addend literal
     *   FADD
     *   FLOAD scale     ; FLOAD 4
     *   INVOKESPECIAL super.setupRotations
     * </pre>
     * The pass-through (no addend) variant is the same minus the {@code LDC; FADD} pair.
     * Anything else (computed addend, ternary, runtime field load) returns {@code 0f}; the
     * runtime falls back to the default rotation.
     */
    private static float extractAddend(@NotNull MethodNode method) {
        for (AbstractInsnNode in = method.instructions.getFirst(); in != null; in = in.getNext()) {
            if (in.getOpcode() != Opcodes.INVOKESPECIAL) continue;
            if (!(in instanceof MethodInsnNode mi)) continue;
            if (!SETUP_ROTATIONS.equals(mi.name)) continue;

            AbstractInsnNode scaleLoad = previousReal(in);
            if (!isFloadOf(scaleLoad, SCALE_SLOT)) return 0f;
            AbstractInsnNode beforeScale = previousReal(scaleLoad);
            // Pass-through shape: ...; FLOAD 3; FLOAD 4; INVOKESPECIAL super -> addend=0.
            if (isFloadOf(beforeScale, BODY_ROT_SLOT)) return 0f;
            // Addend shape: ...; FLOAD 3; LDC C; FADD; FLOAD 4; INVOKESPECIAL super -> addend=C.
            if (beforeScale != null && beforeScale.getOpcode() == Opcodes.FADD) {
                AbstractInsnNode constInsn = previousReal(beforeScale);
                Float c = AsmKit.readFloatLiteral(constInsn);
                AbstractInsnNode bodyRotLoad = constInsn == null ? null : previousReal(constInsn);
                if (c != null && isFloadOf(bodyRotLoad, BODY_ROT_SLOT)) return c;
            }
            return 0f;
        }
        return 0f;
    }

    /**
     * Skips ASM pseudo-instructions ({@code LabelNode}, {@code LineNumberNode},
     * {@code FrameNode}) walking backwards. The {@code AbstractInsnNode#getPrevious} chain
     * exposes these too; for opcode-level pattern matching they're noise.
     */
    private static AbstractInsnNode previousReal(AbstractInsnNode in) {
        if (in == null) return null;
        for (AbstractInsnNode prev = in.getPrevious(); prev != null; prev = prev.getPrevious())
            if (prev.getOpcode() >= 0) return prev;
        return null;
    }

    /**
     * Returns {@code true} when {@code in} is an {@code FLOAD} of the given local slot.
     */
    private static boolean isFloadOf(AbstractInsnNode in, int slot) {
        return in != null
            && in.getOpcode() == Opcodes.FLOAD
            && in instanceof VarInsnNode v
            && v.var == slot;
    }
}
