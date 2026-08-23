package lib.minecraft.renderer.tooling.entity;

import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.tooling.kernel.ClassKit;
import lib.minecraft.renderer.tooling.kernel.ClassNodeCache;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lib.minecraft.renderer.tooling.kernel.VanillaSourceClasses;
import lib.minecraft.renderer.tooling.policy.AsmContext;
import lib.minecraft.renderer.tooling.policy.Navigation;
import lib.minecraft.renderer.tooling.walk.AsmWalker;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Node {@code rest} - what each of a subject's enum and flag render-state fields holds before
 * anything has happened to it.
 *
 * <p>A model poses a bone on an enum ({@code arms.visible = armPose == CROSSED}), and answering
 * "not any constant" is a state no enum is ever in. It is also not a state that reads as harmless:
 * every illager but the pillager idles with its arms {@code CROSSED}, so a reader that answers no
 * constant draws the other pair of arms and the wrong silhouette with them. The name says nothing
 * either - {@code CROSSED} is what {@code AbstractIllager.getArmPose} returns unconditionally, and
 * the one subject that hangs its arms is the one that overrides it.
 *
 * <p>Two hops answer it, and both have to match or the field goes unanswered: the renderer's
 * {@code extractRenderState} names the entity accessor a state field is filled from, and the
 * accessor's own last return says which constant it falls through to when nothing is true of the
 * subject. An accessor laid out any other way - a switch, a field read, a constant chosen before
 * the branches - answers nothing, which is what the field read as before this existed.
 *
 * <p>Only a field the renderer fills from a plain accessor is answered. What a render state
 * computes for itself is a function of figures rather than of the entity, and belongs to whoever
 * supplies those.
 *
 * <p>A flag is answered the same way wherever the receiver it is asked of can be named, which is
 * not always the entity: a dragon's is the phase instance its manager is holding, and
 * {@link EntityRestPolicies} is where the class that answers is declared. What that class answers
 * is still read from the jar.
 */
final class EntityRestStateResolver {

    /** The render-state member a fish reads twice - for its wag amplitude and for lying on its side. */
    private static final @NotNull String IN_WATER = "isInWater";

    /** The descriptor of the no-argument flag question every {@code rest} flag is filled from. */
    private static final @NotNull String FLAG = "()Z";

    private final @NotNull ClassNodeCache cache;
    private final @NotNull EntitySubject subject;
    private final @NotNull Diagnostics diagnostics;
    private final @NotNull AsmContext frame;

    EntityRestStateResolver(@NotNull EntityContext context) {
        this.cache = context.cache();
        this.subject = context.subject();
        this.diagnostics = context.diagnostics();
        this.frame = new AsmContext(
            context.session(), context.subject().entityId(), null, context.diagnostics());
    }

    /**
     * The {@code rest} node - state field to the constant it rests at - or {@code null} when the
     * subject's renderer fills none from an accessor this can read.
     *
     * @return the node, or {@code null} to omit
     */
    @Nullable JsonTree resolve() {
        Map<String, Assignment> assignments = new LinkedHashMap<>();
        Map<String, MethodInsnNode> flags = new LinkedHashMap<>();
        ClassKit.walkSuperChain(this.cache, this.subject.rendererClass(), classNode -> {
            for (MethodNode method : classNode.methods)
                if (VanillaSourceClasses.Methods.EXTRACT_RENDER_STATE.equals(method.name)) {
                    collectEnumAssignments(method, assignments);
                    collectFlagAssignments(method, flags);
                }
        });
        // Sorted, because the walk visits the renderer chain leaf-first while a reader wants one
        // order whatever the depth a field happens to be filled at.
        Map<String, String> rest = new TreeMap<>();
        assignments.forEach((field, assignment) -> {
            String constant = fallThroughConstant(assignment);
            if (constant != null) rest.put(field, constant);
        });
        Navigation.At declared = EntityRestPolicies.RESTING_PHASE_ANSWER.requireAt(this.frame);
        flags.forEach((field, call) -> {
            String answer = restingAnswer(declared, call);
            if (answer != null) rest.put(field, answer);
        });
        if (inWater()) rest.put(IN_WATER, Boolean.TRUE.toString());
        if (rest.isEmpty()) return null;

        this.diagnostics.info("rest: %s", rest);
        JsonTree node = JsonTree.object();
        rest.forEach(node::put);
        return node;
    }

    /**
     * Whether an offline render puts this subject in water - whether its entity class descends from
     * the base {@link EntityRestPolicies#IN_WATER_FAMILY} names.
     *
     * @return whether the subject is one the harness's pin covers
     */
    private boolean inWater() {
        String current = this.subject.entityClass();
        for (int depth = 0; current != null && depth < 16; depth++) {
            if (EntityRestPolicies.IN_WATER_FAMILY.stringValue().equals(current)) return true;
            ClassNode node = this.cache.load(current);
            if (node == null) return false;
            current = node.superName;
        }
        return false;
    }

    /**
     * Collects every {@code PUTFIELD <state>.<field>:Z} in one method whose value came straight off
     * a no-argument question. The instruction is kept whole rather than its parts, because what the
     * question was asked OF is the half a walk cannot answer and a policy names. First assignment
     * wins, as it does for an enum.
     *
     * @param method one {@code extractRenderState} in the renderer chain
     * @param out the field-to-question map to fill
     */
    private static void collectFlagAssignments(
        @NotNull MethodNode method, @NotNull Map<String, MethodInsnNode> out) {

        AsmWalker.over(method)
            .ofType(FieldInsnNode.class)
            .where(put -> put.getOpcode() == Opcodes.PUTFIELD && "Z".equals(put.desc))
            .forEach(put -> {
                AbstractInsnNode source = AsmWalker.previousReal(put);
                if (!(source instanceof MethodInsnNode call) || !FLAG.equals(call.desc)) return;
                if (source.getOpcode() != Opcodes.INVOKEVIRTUAL
                    && source.getOpcode() != Opcodes.INVOKEINTERFACE) return;
                out.putIfAbsent(put.name, call);
            });
    }

    /**
     * The flag a question falls through to, read off the class the policy declares answers it.
     *
     * <p>Answered only where the declared class is the one the call site names or descends from it,
     * so the link between the two is read from the jar rather than declared with them: a question
     * put to anything else is one no policy speaks for, and stays unanswered the way it was before
     * this existed.
     *
     * @param declared the coordinate naming the class a resting subject puts the question to
     * @param call the question the renderer asked
     * @return {@code "true"} or {@code "false"}, or {@code null} when nothing answers it
     */
    private @Nullable String restingAnswer(
        Navigation.@NotNull At declared, @NotNull MethodInsnNode call) {

        if (!declared.member().equals(call.name) || !answersFor(declared.owner(), call.owner))
            return null;
        MethodNode answer = ClassKit.findMethodInHierarchy(
            this.cache, declared.owner(), declared.member(), FLAG);
        if (answer == null) return null;

        String[] last = {null};
        AsmWalker.over(answer)
            .where(node -> node.getOpcode() == Opcodes.IRETURN)
            .forEach(exit -> {
                AbstractInsnNode value = AsmWalker.previousReal(exit);
                int opcode = value == null ? Opcodes.NOP : value.getOpcode();
                last[0] = opcode == Opcodes.ICONST_1 ? Boolean.TRUE.toString()
                    : opcode == Opcodes.ICONST_0 ? Boolean.FALSE.toString()
                    : null;
            });
        return last[0];
    }

    /**
     * Whether {@code candidate} is the type a call site names, or reaches it upward through a
     * superclass or an interface. Bounded by a visiting set rather than a depth cap, an interface
     * graph having no depth a class hierarchy's does not.
     *
     * @param candidate the class the policy declares answers the question
     * @param callOwner the type the instruction names
     * @return whether the candidate is one of that type
     */
    private boolean answersFor(@NotNull String candidate, @NotNull String callOwner) {
        Deque<String> pending = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        pending.add(candidate);
        while (!pending.isEmpty()) {
            String current = pending.poll();
            if (callOwner.equals(current)) return true;
            if (!seen.add(current)) continue;
            ClassNode node = this.cache.load(current);
            if (node == null) continue;
            if (node.superName != null) pending.add(node.superName);
            pending.addAll(node.interfaces);
        }
        return false;
    }

    /**
     * One {@code state.<field> = entity.<accessor>()} assignment of an enum-typed field.
     *
     * @param field the render-state field name
     * @param accessor the accessor's method name
     * @param enumType the enum's JVM internal name, which the constant has to be one of
     */
    private record Assignment(
        @NotNull String field,
        @NotNull String accessor,
        @NotNull String enumType
    ) {}

    /**
     * Collects every {@code PUTFIELD <state>.<field>:L<Enum>;} in one method whose value came
     * straight off a no-argument accessor returning that same enum. First assignment wins - a
     * renderer chain that fills a field twice fills it in the subclass, which the leaf-first walk
     * reaches first.
     *
     * <p>An enum is recognised by its own descriptor rather than by a supertype lookup: a field
     * holding {@code L<T>;} filled by a {@code ()L<T>;} call is the shape either way, and treating
     * a non-enum reference the same costs only the constant read that follows, which then finds
     * nothing.
     */
    private static void collectEnumAssignments(
        @NotNull MethodNode method, @NotNull Map<String, Assignment> out) {

        AsmWalker.over(method)
            .ofType(FieldInsnNode.class)
            .where(put -> put.getOpcode() == Opcodes.PUTFIELD && put.desc.startsWith("L"))
            .forEach(put -> {
                AbstractInsnNode source = AsmWalker.previousReal(put);
                if (source == null
                    || source.getOpcode() != Opcodes.INVOKEVIRTUAL
                    || !(source instanceof MethodInsnNode call)
                    || !call.desc.equals("()" + put.desc)) return;
                String enumType = ClassKit.internalNameOfRef(put.desc);
                if (enumType == null) return;
                out.putIfAbsent(put.name, new Assignment(put.name, call.name, enumType));
            });
    }

    /**
     * The constant an accessor returns when every branch in it falls through - its LAST
     * {@code GETSTATIC <enum>.<constant>; ARETURN} pair.
     *
     * <p>Last rather than first, because javac lays a chain of {@code if (...) return X;} out in
     * source order and the unguarded return closes it. A method whose final two instructions are
     * anything else has no fall-through constant to read and answers nothing.
     *
     * <p>Resolved from the SUBJECT's own entity class rather than from the class the call site
     * names. A renderer shared by several entities calls the accessor on the type they have in
     * common, so reading the owner off the instruction answers that class's implementation for all
     * of them - which gives a pillager the arms every other illager crosses, {@code NEUTRAL} being
     * exactly what its own override exists to say.
     *
     * @param assignment the field's accessor and the enum it must answer with
     * @return the constant's own name, or {@code null} when the accessor is not that shape
     */
    private @Nullable String fallThroughConstant(@NotNull Assignment assignment) {
        MethodNode accessor = ClassKit.findMethodInHierarchy(this.cache, this.subject.entityClass(),
            assignment.accessor(), "()L" + assignment.enumType() + ";");
        if (accessor == null) return null;

        String[] last = {null};
        AsmWalker.over(accessor)
            .where(node -> node.getOpcode() == Opcodes.ARETURN)
            .forEach(exit -> {
                AbstractInsnNode value = AsmWalker.previousReal(exit);
                if (value instanceof FieldInsnNode read
                    && read.getOpcode() == Opcodes.GETSTATIC
                    && assignment.enumType().equals(read.owner)
                    && assignment.enumType().equals(ClassKit.internalNameOfRef(read.desc)))
                    last[0] = read.name;
                else
                    last[0] = null;
            });
        return last[0];
    }

}
