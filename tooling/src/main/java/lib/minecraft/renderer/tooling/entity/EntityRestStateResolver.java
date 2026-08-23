package lib.minecraft.renderer.tooling.entity;

import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.tooling.kernel.ClassKit;
import lib.minecraft.renderer.tooling.kernel.ClassNodeCache;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lib.minecraft.renderer.tooling.kernel.VanillaSourceClasses;
import lib.minecraft.renderer.tooling.walk.AsmWalker;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Node {@code rest} - which constant each of a subject's enum render-state fields holds before
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
 */
final class EntityRestStateResolver {

    /** The render-state member a fish reads twice - for its wag amplitude and for lying on its side. */
    private static final @NotNull String IN_WATER = "isInWater";

    private final @NotNull ClassNodeCache cache;
    private final @NotNull EntitySubject subject;
    private final @NotNull Diagnostics diagnostics;

    EntityRestStateResolver(@NotNull EntityContext context) {
        this.cache = context.cache();
        this.subject = context.subject();
        this.diagnostics = context.diagnostics();
    }

    /**
     * The {@code rest} node - state field to the constant it rests at - or {@code null} when the
     * subject's renderer fills none from an accessor this can read.
     *
     * @return the node, or {@code null} to omit
     */
    @Nullable JsonTree resolve() {
        Map<String, Assignment> assignments = new LinkedHashMap<>();
        ClassKit.walkSuperChain(this.cache, this.subject.rendererClass(), classNode -> {
            for (MethodNode method : classNode.methods)
                if (VanillaSourceClasses.Methods.EXTRACT_RENDER_STATE.equals(method.name))
                    collectEnumAssignments(method, assignments);
        });
        // Sorted, because the walk visits the renderer chain leaf-first while a reader wants one
        // order whatever the depth a field happens to be filled at.
        Map<String, String> rest = new TreeMap<>();
        assignments.forEach((field, assignment) -> {
            String constant = fallThroughConstant(assignment);
            if (constant != null) rest.put(field, constant);
        });
        if (inWater()) rest.put(IN_WATER, Boolean.TRUE.toString());
        if (rest.isEmpty()) return null;

        this.diagnostics.info("rest: %s", rest);
        JsonTree node = JsonTree.object();
        rest.forEach(node::put);
        return node;
    }

    /**
     * Whether an offline render puts this subject in water.
     *
     * <p>A fish renderer reads {@code isInWater} twice - once to scale the amplitude its body wags
     * at, and once to decide whether to lay the subject on its side. The second is vanilla's
     * flopping-on-land pose, right in a world and the wrong shape for a reference render, so the
     * harness pins the field on and this side has to answer the same or the two draw different fish.
     *
     * <p><b>Scoped to {@code AbstractFish} exactly, because the harness's pin is.</b> A dolphin, an
     * axolotl, a squid and a drowned each read the same field for something else, so widening it to
     * everything aquatic would answer for four subjects that never asked the same question.
     *
     * @return whether the subject's entity class descends from the fish base
     */
    private boolean inWater() {
        String current = this.subject.entityClass();
        for (int depth = 0; current != null && depth < 16; depth++) {
            if (VanillaSourceClasses.Types.ABSTRACT_FISH.equals(current)) return true;
            ClassNode node = this.cache.load(current);
            if (node == null) return false;
            current = node.superName;
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
