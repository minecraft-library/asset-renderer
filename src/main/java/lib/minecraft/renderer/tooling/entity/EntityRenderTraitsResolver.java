package lib.minecraft.renderer.tooling.entity;

import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.tooling.kernel.AsmKit;
import lib.minecraft.renderer.tooling.kernel.ClassNodeCache;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lib.minecraft.renderer.tooling.kernel.VanillaSourceClasses;
import lib.minecraft.renderer.tooling.walk.AsmWalker;
import lib.minecraft.renderer.tooling.walk.Insn;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.Map;

/**
 * Node {@code render} - the render-time residues that survive the frozen-state harness,
 * grouped under one node: {@code scale} (renderer {@code scale()} uniform
 * product, slime 0.999 / wither 2.0), {@code yaw_addend} ({@code setupRotations} addend,
 * shulker +180), and {@code tint} (per-entity multiplicative base tint, tropical_fish
 * {@code 0xFFF9FFFE}). Omitted when all three are identity.
 *
 * <p>The {@code bodyRot} / {@code entityScale} local slots are computed from the
 * {@code setupRotations} descriptor rather than hardcoded literal slot numbers; the
 * DyeColor-WHITE extraction anchors the texture-diffuse int on the constructor descriptor's
 * int-before-{@code MapColor} position rather than literal-count position coding.
 */
final class EntityRenderTraitsResolver {

    /**
     * Tolerance for the uniform-scale check on {@code poseStack.scale(F,F,F)} args - vanilla
     * writes literal uniform triples; drift beyond this implies a non-uniform expression
     * treated as identity ({@link EntityNamingPolicies#UNIFORM_SCALE_TOLERANCE}).
     */
    private static final float UNIFORM_SCALE_TOLERANCE = EntityNamingPolicies.UNIFORM_SCALE_TOLERANCE.floatValue();

    /**
     * The no-op multiplicative tint.
     */
    private static final int NO_TINT = 0xFFFFFFFF;

    private final @NotNull ClassNodeCache cache;
    private final @NotNull EntitySubject subject;
    private final @NotNull Diagnostics diagnostics;

    EntityRenderTraitsResolver(@NotNull EntityContext context) {
        this.cache = context.cache();
        this.subject = context.subject();
        this.diagnostics = context.diagnostics();
    }

    /**
     * The {@code render} node, or {@code null} when scale, yaw addend, and tint are all
     * identity.
     *
     * @return the node, or {@code null} to omit
     */
    @Nullable JsonTree resolve() {
        ClassNode cn = this.cache.load(this.subject.rendererClass());
        if (cn == null) return null;

        Float scale = resolveRendererScale(cn);
        float yawAddend = resolveSetupYawAddend(cn);
        int tint = resolveBaseTint(cn);

        if (scale == null && yawAddend == 0f && tint == NO_TINT) return null;
        JsonTree node = JsonTree.object();
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

        // Slots computed from the descriptor: the first float arg is bodyRot, the
        // second entityScale (instance method - slots start at 1).
        int bodyRotSlot = floatArgSlot(setupRotations.desc, 0);
        int scaleSlot = floatArgSlot(setupRotations.desc, 1);
        if (bodyRotSlot < 0 || scaleSlot < 0) return 0f;

        Float resolved = AsmWalker.over(setupRotations)
            .opcode(Opcodes.INVOKESPECIAL)
            .ofType(MethodInsnNode.class)
            .where(mi -> VanillaSourceClasses.Methods.SETUP_ROTATIONS.equals(mi.name))
            .firstNotNull(mi -> {
                AbstractInsnNode scaleLoad = AsmKit.previousReal(mi);
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
            });
        return resolved == null ? 0f : resolved;
    }

    // ------------------------------------------------------------------------------------
    // setupRotations Y translation
    // ------------------------------------------------------------------------------------

    /**
     * Descriptor of the {@code PoseStack.translate} overload vanilla poses entities with.
     */
    private static final @NotNull String TRANSLATE_DESC = "(FFF)V";

    /**
     * A {@code setupRotations} translate operand that may differ by age, plus the instruction its
     * value expression starts at so the walk can step past it to the preceding argument.
     *
     * @param adult the value on the non-baby arm
     * @param baby the value on the baby arm (equal to {@code adult} for an unconditional literal)
     * @param start the first instruction of the value expression
     */
    private record AgeOperand(float adult, float baby, @NotNull AbstractInsnNode start) {}

    /**
     * The Y translation the renderer's own {@code setupRotations} applies at the harness's rest
     * pose, as an {@code {adult, baby}} pair in blocks; {@code null} when it applies none, or none
     * this can model.
     *
     * <p>Vanilla poses an entity by bracketing its rotations with {@code PoseStack} translates, and
     * two of them differ by age - so the pair is read rather than a single value. Only a translate
     * strictly along Y is accepted, with both other operands literal zero, and only when every
     * operand is a literal or an {@code isBaby} select of two literals. That is what keeps the walk
     * from over-reaching: the panda carries a genuine {@code isBaby} float select inside its own
     * {@code setupRotations}, and it is declined because it sits in an arithmetic expression rather
     * than directly in a translate's argument slot, and because its sibling translates carry a
     * non-zero Z. Anchoring on the argument slot is the guard, not the field name.
     *
     * <p>Summing the translates is only faithful because at rest every rotation between them is
     * about Y, which preserves the axis they act on. A literal X or Z rotation between two
     * translates would make the sum wrong, so one declines the walk.
     *
     * <p>The walk stops at the first class in the renderer chain that declares
     * {@code setupRotations} and reads only that class's own bodies - a glow squid declares none and
     * correctly inherits the squid's. It deliberately does NOT follow the {@code super} call the
     * body ends with: the base declaration's own translate is divided by the render scale and gated
     * on an upside-down state no subject here is in.
     *
     * @return the {@code {adult, baby}} block offsets, or {@code null} when there is nothing to emit
     */
    @Nullable float[] resolveSetupYShift() {
        ClassNode declaring = findSetupRotationsDeclarer();
        if (declaring == null) return null;

        String name = EntityOverlayResolver.simpleName(declaring.name);
        float adult = 0f;
        float baby = 0f;
        boolean translates = false;
        for (MethodNode method : declaring.methods) {
            if (!VanillaSourceClasses.Methods.SETUP_ROTATIONS.equals(method.name)) continue;
            for (AbstractInsnNode in : method.instructions) {
                if (!AsmKit.isInvokeVirtual(in, VanillaSourceClasses.Types.POSE_STACK,
                    VanillaSourceClasses.Methods.TRANSLATE, TRANSLATE_DESC)) continue;
                translates = true;
                // Arguments are pushed x, y, z, so the walk back off the call reads them in reverse.
                AgeOperand z = readAgeOperand(AsmKit.previousReal(in));
                if (z == null || z.adult() != 0f || z.baby() != 0f) return declineYShift(name, "off the Y axis");
                AgeOperand y = readAgeOperand(AsmKit.previousReal(z.start()));
                if (y == null) return declineYShift(name, "by a computed distance");
                AgeOperand x = readAgeOperand(AsmKit.previousReal(y.start()));
                if (x == null || x.adult() != 0f || x.baby() != 0f) return declineYShift(name, "off the Y axis");
                adult += y.adult();
                baby += y.baby();
            }
            // Only meaningful once something was accepted: a renderer that translates nothing has no
            // pair of translates for a turn to come between, whatever else it rotates.
            if (translates && hasNonYRotation(method))
                return declineYShift(name, "around a literal non-Y axis, so its translates do not sum");
        }
        if (!translates || (adult == 0f && baby == 0f)) return null;
        this.diagnostics.info("setupRotations Y shift adult %s / baby %s from %s", adult, baby, name);
        return new float[] {adult, baby};
    }

    /**
     * Reports the decline, so a renderer this cannot model reads as declined rather than as flat.
     *
     * @param renderer the declaring renderer's simple name
     * @param reason what it does that the walk will not model
     * @return {@code null}, the caller's answer
     */
    private @Nullable float[] declineYShift(@NotNull String renderer, @NotNull String reason) {
        this.diagnostics.info("%s.setupRotations moves %s - no y_shift", renderer, reason);
        return null;
    }

    /**
     * The first class up the renderer chain OVERRIDING {@code setupRotations}, or {@code null} when
     * none does.
     *
     * <p>The walk stops short of {@link VanillaSourceClasses.Types#LIVING_ENTITY_RENDERER}, which
     * declares the method every override overrides. Its own translate is divided by the render scale
     * and gated on an upside-down state no subject here is in, so reading it would mean nothing and
     * would decline for every entity whose renderer overrides nothing.
     *
     * @return the overriding class, or {@code null} when the renderer inherits the base unchanged
     */
    private @Nullable ClassNode findSetupRotationsDeclarer() {
        String current = this.subject.rendererClass();
        while (current != null
            && !AsmKit.OBJECT_INTERNAL.equals(current)
            && !VanillaSourceClasses.Types.LIVING_ENTITY_RENDERER.equals(current)) {
            ClassNode cn = this.cache.load(current);
            if (cn == null) return null;
            for (MethodNode method : cn.methods)
                if (VanillaSourceClasses.Methods.SETUP_ROTATIONS.equals(method.name)) return cn;
            current = cn.superName;
        }
        return null;
    }

    /**
     * Decodes one translate argument: a plain float literal, or javac's select of two literals on an
     * {@code isBaby} read ({@code GETFIELD isBaby:Z; IFEQ; <baby>; GOTO; <adult>}). Returns
     * {@code null} for anything else - an arithmetic expression, a non-literal, or a select on some
     * other field - which is what declines the whole walk.
     *
     * @param tail the last instruction of the argument's value expression
     * @return the decoded operand, or {@code null} when it is not one of the two accepted shapes
     */
    private static @Nullable AgeOperand readAgeOperand(@Nullable AbstractInsnNode tail) {
        if (tail == null) return null;
        Float direct = AsmKit.readFloatLiteral(tail);

        // Select shape, read backwards: <adult literal>; GOTO; <baby literal>; IF; GETFIELD isBaby.
        AbstractInsnNode jump = AsmKit.previousReal(tail);
        if (direct != null && jump != null && jump.getOpcode() == Opcodes.GOTO) {
            AbstractInsnNode fallThrough = AsmKit.previousReal(jump);
            Float taken = fallThrough == null ? null : AsmKit.readFloatLiteral(fallThrough);
            AbstractInsnNode branch = fallThrough == null ? null : AsmKit.previousReal(fallThrough);
            int opcode = branch == null ? Opcodes.NOP : branch.getOpcode();
            if (taken != null && (opcode == Opcodes.IFEQ || opcode == Opcodes.IFNE)) {
                AbstractInsnNode read = AsmKit.previousReal(branch);
                if (read != null && read.getOpcode() == Opcodes.GETFIELD
                    && read instanceof FieldInsnNode field
                    && VanillaSourceClasses.Fields.IS_BABY.equals(field.name)
                    && "Z".equals(field.desc)) {
                    // IFEQ jumps when the flag is false, so the fall-through arm is the baby's.
                    AbstractInsnNode receiver = AsmKit.previousReal(read);
                    if (receiver == null) return null;
                    return opcode == Opcodes.IFEQ
                        ? new AgeOperand(direct, taken, receiver)
                        : new AgeOperand(taken, direct, receiver);
                }
            }
        }
        return direct == null ? null : new AgeOperand(direct, direct, tail);
    }

    /**
     * Whether the method turns about a non-Y axis by a non-zero literal angle. Such a turn between
     * two translates tilts the frame the later one acts in, so the two stop summing and the walk
     * declines. A non-Y turn by a STATE angle is fine and is why this asks for a literal: the squid
     * pitches by {@code xBodyRot}, which the harness freezes at zero.
     *
     * @param method the {@code setupRotations} body
     * @return whether a literal non-Y turn is applied
     */
    private static boolean hasNonYRotation(@NotNull MethodNode method) {
        return AsmWalker.over(method)
            .getStatic(VanillaSourceClasses.Types.MATH_AXIS)
            .where(field -> !field.name.startsWith("Y"))
            .any(field -> {
                AbstractInsnNode angle = AsmKit.nextReal(field);
                Float literal = angle == null ? null : AsmKit.readFloatLiteral(angle);
                return literal != null && literal != 0f;
            });
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

    /**
     * Reports whether {@code in} is an {@code FLOAD} of the given slot.
     */
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

        // First pass: pre-branch FSTORE-of-literal slots, stopping short of the first branch.
        Map<Integer, Float> slotLiterals = AsmWalker.over(scaleMethod)
            .until(Insn.branch())
            .ofType(VarInsnNode.class)
            .where(store -> store.getOpcode() == Opcodes.FSTORE)
            .toMap(store -> store.var, store -> AsmWalker.floatLiteral(AsmWalker.previousReal(store)));

        // Second pass: the uniform product over every resolvable scale call, reading the
        // first pass's slots. An empty product is exactly 1 and falls to the tolerance gate.
        String scaleDesc = "(FFF)V";
        float accum = AsmWalker.over(scaleMethod)
            .invokeVirtual(VanillaSourceClasses.Types.POSE_STACK, VanillaSourceClasses.Methods.SCALE)
            .where(call -> scaleDesc.equals(call.desc))
            .mapNotNull(call -> readUniformScaleArgs(call, slotLiterals))
            .reduce(1f, (product, xyz) -> product * xyz);
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

    /**
     * A single float-arg push resolved to its zero-state value (literal or tracked FLOAD).
     */
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
     * walked out of {@code DyeColor.<clinit>}. {@code NO_TINT} when the renderer never
     * calls {@code getTextureDiffuseColor}.
     */
    private int resolveBaseTint(@NotNull ClassNode cn) {
        for (MethodNode method : cn.methods)
            if (AsmWalker.over(method).invokeVirtual(VanillaSourceClasses.Types.DYE_COLOR, VanillaSourceClasses.Methods.GET_TEXTURE_DIFFUSE_COLOR).any())
                return whiteTextureDiffuseColor(this.cache);
        return NO_TINT;
    }

    /**
     * Walks {@code DyeColor.<clinit>}'s first allocation (WHITE - enum declaration order) for
     * its texture-diffuse constructor argument, anchored on the descriptor: the
     * constructor's {@code int} parameter directly preceding the {@code MapColor} parameter
     * is the texture-diffuse colour, so the value is the last int literal pushed before the
     * {@code MapColor} GETSTATIC. Alpha {@code 0xFF} is prepended to match the ARGB tint
     * convention. {@code NO_TINT} on any pattern miss.
     */
    static int whiteTextureDiffuseColor(@NotNull ClassNodeCache cache) {
        MethodNode clinit = AsmKit.findClinit(cache, VanillaSourceClasses.Types.DYE_COLOR);
        if (clinit == null) return NO_TINT;

        boolean inAlloc = false;
        Integer lastInt = null;
        Integer diffuse = null;
        for (AbstractInsnNode in : clinit.instructions) {
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

    /**
     * Reports whether the constructor descriptor pairs an {@code int} directly before {@code MapColor}.
     */
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
