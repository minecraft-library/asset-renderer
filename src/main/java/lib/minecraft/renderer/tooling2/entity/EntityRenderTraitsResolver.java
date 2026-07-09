package lib.minecraft.renderer.tooling2.entity;

import lib.minecraft.renderer.tooling2.kernel.AsmKit;
import lib.minecraft.renderer.tooling2.kernel.ClassNodeCache;
import lib.minecraft.renderer.tooling2.kernel.Diagnostics;
import lib.minecraft.renderer.tooling2.kernel.JsonNode;
import lib.minecraft.renderer.tooling2.kernel.VanillaSourceClasses;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.HashMap;
import java.util.Map;

/**
 * Node {@code render} - the render-time residues that survive the frozen-state harness,
 * grouped under one node (SPINE 3.1 row 5): {@code scale} (renderer {@code scale()} uniform
 * product, slime 0.999 / wither 2.0), {@code yaw_addend} ({@code setupRotations} addend,
 * shulker +180), and {@code tint} (per-entity multiplicative base tint, tropical_fish
 * {@code 0xFFF9FFFE}). Omitted when all three are identity.
 *
 * <p>Derivation upgrades vs legacy: the {@code bodyRot} / {@code entityScale} local slots
 * are computed from the {@code setupRotations} descriptor instead of the
 * {@code BODY_ROT_SLOT=3} / {@code SCALE_SLOT=4} literals [D7]; the DyeColor-WHITE
 * extraction anchors the texture-diffuse int on the constructor descriptor's
 * int-before-{@code MapColor} position instead of literal-count position coding [D24].
 */
final class EntityRenderTraitsResolver {

    /**
     * Tolerance for the uniform-scale check on {@code poseStack.scale(F,F,F)} args - vanilla
     * writes literal uniform triples; drift beyond this implies a non-uniform expression
     * treated as identity (P4, {@link EntityNamingPolicies#UNIFORM_SCALE_TOLERANCE}).
     */
    private static final float UNIFORM_SCALE_TOLERANCE = EntityNamingPolicies.UNIFORM_SCALE_TOLERANCE.floatValue();

    /** The no-op multiplicative tint. */
    private static final int NO_TINT = 0xFFFFFFFF;

    private final @NotNull ClassNodeCache cache;
    private final @NotNull EntitySubject subject;
    private final @NotNull Diagnostics diagnostics;

    EntityRenderTraitsResolver(@NotNull ClassNodeCache cache, @NotNull EntitySubject subject, @NotNull Diagnostics diagnostics) {
        this.cache = cache;
        this.subject = subject;
        this.diagnostics = diagnostics;
    }

    /**
     * The {@code render} node, or {@code null} when scale, yaw addend, and tint are all
     * identity.
     *
     * @return the node, or {@code null} to omit
     */
    @Nullable JsonNode resolve() {
        ClassNode cn = this.cache.load(this.subject.rendererClass());
        if (cn == null) return null;

        Float scale = resolveRendererScale(cn);
        float yawAddend = resolveSetupYawAddend(cn);
        int tint = resolveBaseTint(cn);

        if (scale == null && yawAddend == 0f && tint == NO_TINT) return null;
        JsonNode node = JsonNode.object();
        if (scale != null) node.put("scale", (float) scale);
        if (yawAddend != 0f) node.put("yaw_addend", yawAddend);
        if (tint != NO_TINT) node.putHex("tint", tint);
        return node;
    }

    // ------------------------------------------------------------------------------------
    // setupRotations yaw addend
    // ------------------------------------------------------------------------------------

    /**
     * The literal degrees the renderer's own {@code setupRotations} folds into
     * {@code bodyRot} before delegating ({@code super.setupRotations(state, ps, bodyRot +
     * 180f, entityScale)}); {@code 0f} for absent / pass-through / non-literal overrides.
     */
    private float resolveSetupYawAddend(@NotNull ClassNode cn) {
        MethodNode setupRotations = null;
        for (MethodNode m : cn.methods) {
            if (!VanillaSourceClasses.Methods.SETUP_ROTATIONS.equals(m.name)) continue;
            if (m.desc != null && m.desc.endsWith("FF)V")) {
                setupRotations = m;
                break;
            }
        }
        if (setupRotations == null) return 0f;

        // Slots computed from the descriptor [D7]: the first float arg is bodyRot, the
        // second entityScale (instance method - slots start at 1).
        int bodyRotSlot = floatArgSlot(setupRotations.desc, 0);
        int scaleSlot = floatArgSlot(setupRotations.desc, 1);
        if (bodyRotSlot < 0 || scaleSlot < 0) return 0f;

        for (AbstractInsnNode in = setupRotations.instructions.getFirst(); in != null; in = in.getNext()) {
            if (in.getOpcode() != Opcodes.INVOKESPECIAL) continue;
            if (!(in instanceof MethodInsnNode mi) || !VanillaSourceClasses.Methods.SETUP_ROTATIONS.equals(mi.name)) continue;

            AbstractInsnNode scaleLoad = AsmKit.previousReal(in);
            if (!isFloadOf(scaleLoad, scaleSlot)) return 0f;
            AbstractInsnNode beforeScale = AsmKit.previousReal(scaleLoad);
            // Pass-through shape: FLOAD bodyRot; FLOAD scale; INVOKESPECIAL super.
            if (isFloadOf(beforeScale, bodyRotSlot)) return 0f;
            // Addend shape: FLOAD bodyRot; LDC C; FADD; FLOAD scale; INVOKESPECIAL super.
            if (beforeScale != null && beforeScale.getOpcode() == Opcodes.FADD) {
                AbstractInsnNode constInsn = AsmKit.previousReal(beforeScale);
                Float addend = constInsn == null ? null : AsmKit.readFloatLiteral(constInsn);
                AbstractInsnNode bodyRotLoad = constInsn == null ? null : AsmKit.previousReal(constInsn);
                if (addend != null && isFloadOf(bodyRotLoad, bodyRotSlot)) {
                    this.diagnostics.info("yaw addend %.1f from setupRotations override", addend);
                    return addend;
                }
            }
            return 0f;
        }
        return 0f;
    }

    /**
     * The local-variable slot of the instance method's Nth {@code float} argument (0-based
     * among the floats), or {@code -1} when absent.
     */
    private static int floatArgSlot(@NotNull String desc, int floatIndex) {
        int slot = 1;   // slot 0 = this
        int seen = 0;
        for (Type arg : AsmKit.argTypes(desc)) {
            if (arg.getSort() == Type.FLOAT) {
                if (seen == floatIndex) return slot;
                seen++;
            }
            slot += arg.getSize();
        }
        return -1;
    }

    /** Reports whether {@code in} is an {@code FLOAD} of the given slot. */
    private static boolean isFloadOf(@Nullable AbstractInsnNode in, int slot) {
        return in != null
            && in.getOpcode() == Opcodes.FLOAD
            && in instanceof VarInsnNode load
            && load.var == slot;
    }

    // ------------------------------------------------------------------------------------
    // scale residue
    // ------------------------------------------------------------------------------------

    /**
     * The uniform product of every literal {@code poseStack.scale(F,F,F)} in the renderer's
     * own {@code scale} override, or {@code null} when no literal surfaces or the product
     * collapses to 1 within tolerance. Tracks pre-branch {@code FSTORE}-of-literal slots so
     * wither's {@code fstore 2.0f; ...; scale(v,v,v)} resolves.
     */
    private @Nullable Float resolveRendererScale(@NotNull ClassNode cn) {
        MethodNode scaleMethod = findPrimaryScaleMethod(cn);
        if (scaleMethod == null) return null;

        Map<Integer, Float> slotLiterals = new HashMap<>();
        for (AbstractInsnNode in = scaleMethod.instructions.getFirst(); in != null; in = in.getNext()) {
            if (AsmKit.isBranchInsn(in.getOpcode())) break;
            if (in.getOpcode() != Opcodes.FSTORE || !(in instanceof VarInsnNode store)) continue;
            AbstractInsnNode prev = AsmKit.previousReal(in);
            Float literal = prev == null ? null : AsmKit.readFloatLiteral(prev);
            if (literal != null) slotLiterals.put(store.var, literal);
        }

        String scaleDesc = "(FFF)V";
        float accum = 1f;
        boolean anyResolved = false;
        for (AbstractInsnNode in = scaleMethod.instructions.getFirst(); in != null; in = in.getNext()) {
            if (!AsmKit.isInvokeVirtual(in, VanillaSourceClasses.Types.POSE_STACK, VanillaSourceClasses.Methods.SCALE, scaleDesc)) continue;
            Float xyz = readUniformScaleArgs(in, slotLiterals);
            if (xyz == null) continue;
            accum *= xyz;
            anyResolved = true;
        }
        if (!anyResolved) return null;
        if (Math.abs(accum - 1f) <= UNIFORM_SCALE_TOLERANCE) return null;
        this.diagnostics.info("renderer scale %.6f from poseStack.scale chain", accum);
        return accum;
    }

    /**
     * The renderer's {@code scale} method taking its OWN render-state class; the
     * {@code LivingEntityRenderState} bridge is preferred only when no typed override exists.
     */
    private static @Nullable MethodNode findPrimaryScaleMethod(@NotNull ClassNode cn) {
        String descSuffix = ";" + VanillaSourceClasses.Descs.ref(VanillaSourceClasses.Types.POSE_STACK) + ")V";
        String bridgeSuffix = VanillaSourceClasses.Descs.ref(VanillaSourceClasses.Types.LIVING_ENTITY_RENDER_STATE)
            + VanillaSourceClasses.Descs.ref(VanillaSourceClasses.Types.POSE_STACK) + ")V";
        MethodNode bridge = null;
        for (MethodNode m : cn.methods) {
            if (!VanillaSourceClasses.Methods.SCALE.equals(m.name)) continue;
            if (m.desc == null || !m.desc.endsWith(descSuffix)) continue;
            if (m.desc.endsWith(bridgeSuffix)) {
                bridge = m;
                continue;
            }
            return m;
        }
        return bridge;
    }

    /**
     * The uniform value of the three float args preceding a
     * {@code PoseStack.scale(FFF)V}, resolvable via direct literal or the pre-branch slot
     * map; {@code null} when any arg is unresolvable or the triple is non-uniform.
     */
    private static @Nullable Float readUniformScaleArgs(@NotNull AbstractInsnNode invoke, @NotNull Map<Integer, Float> slotLiterals) {
        AbstractInsnNode z = AsmKit.previousReal(invoke);
        Float zValue = resolveFloatArg(z, slotLiterals);
        if (zValue == null) return null;
        AbstractInsnNode y = AsmKit.previousReal(z);
        Float yValue = resolveFloatArg(y, slotLiterals);
        if (yValue == null) return null;
        Float xValue = resolveFloatArg(AsmKit.previousReal(y), slotLiterals);
        if (xValue == null) return null;
        if (Math.abs(xValue - yValue) > UNIFORM_SCALE_TOLERANCE || Math.abs(yValue - zValue) > UNIFORM_SCALE_TOLERANCE) return null;
        return xValue;
    }

    /** A single float-arg push resolved to its zero-state value (literal or tracked FLOAD). */
    private static @Nullable Float resolveFloatArg(@Nullable AbstractInsnNode node, @NotNull Map<Integer, Float> slotLiterals) {
        if (node == null) return null;
        Float literal = AsmKit.readFloatLiteral(node);
        if (literal != null) return literal;
        if (node.getOpcode() == Opcodes.FLOAD && node instanceof VarInsnNode load) return slotLiterals.get(load.var);
        return null;
    }

    // ------------------------------------------------------------------------------------
    // base tint
    // ------------------------------------------------------------------------------------

    /**
     * The per-entity multiplicative base tint (vanilla {@code getModelTint}) for renderers
     * reading a {@code DyeColor} state field - sole 26.1 hit: tropical_fish, whose zero-state
     * {@code getBaseColor()} is {@code DyeColor.WHITE}; the WHITE texture-diffuse constant is
     * walked out of {@code DyeColor.<clinit>} [D24]. {@code NO_TINT} when the renderer never
     * calls {@code getTextureDiffuseColor}.
     */
    private int resolveBaseTint(@NotNull ClassNode cn) {
        for (MethodNode method : cn.methods)
            for (AbstractInsnNode in = method.instructions.getFirst(); in != null; in = in.getNext())
                if (AsmKit.isInvokeVirtual(in, VanillaSourceClasses.Types.DYE_COLOR, VanillaSourceClasses.Methods.GET_TEXTURE_DIFFUSE_COLOR))
                    return walkDyeColorWhiteTextureDiffuseColor();
        return NO_TINT;
    }

    /**
     * Walks {@code DyeColor.<clinit>}'s first allocation (WHITE - enum declaration order) for
     * its texture-diffuse constructor argument, anchored on the descriptor [D24]: the
     * constructor's {@code int} parameter directly preceding the {@code MapColor} parameter
     * is the texture-diffuse colour, so the value is the last int literal pushed before the
     * {@code MapColor} GETSTATIC. Alpha {@code 0xFF} is prepended to match the ARGB tint
     * convention. {@code NO_TINT} on any pattern miss.
     */
    private int walkDyeColorWhiteTextureDiffuseColor() {
        ClassNode dyeColor = this.cache.load(VanillaSourceClasses.Types.DYE_COLOR);
        MethodNode clinit = dyeColor == null ? null : AsmKit.findMethod(dyeColor, AsmKit.CLINIT);
        if (clinit == null) return NO_TINT;

        boolean inAlloc = false;
        Integer lastInt = null;
        Integer diffuse = null;
        for (AbstractInsnNode in = clinit.instructions.getFirst(); in != null; in = in.getNext()) {
            if (in.getOpcode() == Opcodes.NEW
                && in instanceof TypeInsnNode alloc
                && VanillaSourceClasses.Types.DYE_COLOR.equals(alloc.desc)) {
                inAlloc = true;
                lastInt = null;
                continue;
            }
            if (!inAlloc) continue;
            Integer intLiteral = AsmKit.readIntLiteral(in);
            if (intLiteral != null) {
                lastInt = intLiteral;
                continue;
            }
            if (AsmKit.isGetStatic(in, VanillaSourceClasses.Types.MAP_COLOR)) {
                diffuse = lastInt;
                continue;
            }
            if (in.getOpcode() == Opcodes.INVOKESPECIAL
                && in instanceof MethodInsnNode init
                && AsmKit.INIT.equals(init.name)
                && VanillaSourceClasses.Types.DYE_COLOR.equals(init.owner)) {
                // Descriptor anchor: the int directly before the MapColor parameter.
                if (diffuse == null || !hasIntBeforeMapColor(init.desc)) return NO_TINT;
                return 0xFF000000 | diffuse;
            }
        }
        return NO_TINT;
    }

    /** Reports whether the constructor descriptor pairs an {@code int} directly before {@code MapColor}. */
    private static boolean hasIntBeforeMapColor(@NotNull String desc) {
        Type[] args = AsmKit.argTypes(desc);
        for (int i = 1; i < args.length; i++)
            if (args[i].getSort() == Type.OBJECT
                && VanillaSourceClasses.Types.MAP_COLOR.equals(args[i].getInternalName())
                && args[i - 1].getSort() == Type.INT)
                return true;
        return false;
    }

}
