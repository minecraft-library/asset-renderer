package lib.minecraft.renderer.tooling.animation;

import lib.minecraft.renderer.tooling.kernel.ClassKit;
import lib.minecraft.renderer.tooling.kernel.ClassNodeCache;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lib.minecraft.renderer.tooling.kernel.VanillaSourceClasses;
import lib.minecraft.renderer.tooling.walk.AsmWalker;
import lib.minecraft.renderer.tooling.walk.Insn;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * What a render-state figure holds before anything has happened to the subject.
 *
 * <p>A pose names the figures it could not derive so that a caller who models none of them still
 * gets a frame vanilla draws. That rests on knowing what "none of them" IS, and the answer is not
 * always nothing: a render state constructs some of its own fields at something else.
 * {@code LivingEntityRenderState} builds {@code ageScale} at one and is the base every living
 * subject extends, {@code HumanoidRenderState} builds {@code speedValue} at one and every humanoid
 * DIVIDES an arm swing by it, and a wolf's tail rests at a fifth of a turn rather than straight
 * down. A caller answering zero to those gets a collapsed subject, a NaN and a wrong tail, none of
 * which vanilla ever draws.
 *
 * <p>Only the fields a pose actually names are resolved, and only where the value is not already
 * zero: a row for a figure nothing reads would declare an input nothing supplies, and a row holding
 * zero says what its own absence says.
 *
 * <p>Read off the CONSTRUCTOR rather than the field declaration, because a Java field initialiser
 * is compiled into one and there is nothing else to read. The whole package is scanned rather than
 * the class a read named, because javac writes the LEAF as the owner of an inherited field's
 * reference - a goat reading {@code ageScale} names {@code GoatRenderState}, which declares it
 * nowhere.
 */
final class InputDefaultResolver {

    /** What a jar entry for a class ends in, and therefore what its internal name is without. */
    private static final @NotNull String CLASS_SUFFIX = ".class";

    private InputDefaultResolver() {}

    /**
     * Every render-state figure the walked poses read.
     *
     * <p>Walked once per NODE rather than once per path: a pose is a graph, and a collector that
     * followed the paths would be the pass that could not afford to run on a humanoid.
     *
     * <p>A figure reached through something the render state holds - a vector's component, spelled
     * with the path to it - is left out. It is not a field of the state and has no constructor of
     * its own to read, and vanilla always fills the thing it hangs off before a pose reads it, so
     * nothing is being assumed away.
     *
     * @param poses what the walk extracted, refusals included and ignored
     * @return the bare field names, in sorted order
     */
    static @NotNull Set<String> namedBy(@NotNull Map<String, PoseOutcome> poses) {
        Set<String> named = new TreeSet<>();
        Set<Object> walked = Collections.newSetFromMap(new IdentityHashMap<>());
        for (PoseOutcome outcome : poses.values()) {
            if (!(outcome instanceof PoseOutcome.Extracted extracted)) continue;
            PoseProgram program = extracted.program();
            program.container().values().forEach(expr -> collect(expr, named, walked));
            program.bones().values().forEach(channels ->
                channels.values().forEach(expr -> collect(expr, named, walked)));
        }
        named.removeIf(field -> field.indexOf('.') >= 0);
        return named;
    }

    /** Every figure one expression reaches, through its operands and through its conditions. */
    private static void collect(
        @NotNull PoseExpr expr, @NotNull Set<String> named, @NotNull Set<Object> walked) {

        if (!walked.add(expr)) return;
        switch (expr) {
            case PoseExpr.Input input -> named.add(input.field());
            case PoseExpr.Op op -> op.operands().forEach(operand -> collect(operand, named, walked));
            case PoseExpr.Select select -> {
                collect(select.whenTrue(), named, walked);
                collect(select.whenFalse(), named, walked);
                collect(select.condition(), named, walked);
            }
            default -> { /* a leaf that is not an input names no figure */ }
        }
    }

    /** Every figure one condition reaches, which is the same question a step lower. */
    private static void collect(
        @NotNull PosePredicate predicate, @NotNull Set<String> named, @NotNull Set<Object> walked) {

        if (!walked.add(predicate)) return;
        switch (predicate) {
            case PosePredicate.Compare compare -> {
                collect(compare.left(), named, walked);
                collect(compare.right(), named, walked);
            }
            case PosePredicate.Not not -> collect(not.operand(), named, walked);
            default -> { /* an enum test, a presence test or a decided constant names no figure */ }
        }
    }

    /**
     * The non-zero value each named figure is constructed with.
     *
     * @param cache the open client jar
     * @param named the render-state fields the walked poses read
     * @param diagnostics the scope findings are recorded against
     * @return field name to the value its own render state builds it at, omitting the zeros
     */
    static @NotNull Map<String, Float> resolve(
        @NotNull ClassNodeCache cache, @NotNull Collection<String> named, @NotNull Diagnostics diagnostics) {

        Set<String> wanted = Set.copyOf(named);
        Map<String, Float> out = new TreeMap<>();
        Map<String, String> declaredBy = new TreeMap<>();

        for (String entry : cache.list(VanillaSourceClasses.Types.ENTITY_RENDER_STATE_PACKAGE, CLASS_SUFFIX)) {
            String owner = entry.substring(0, entry.length() - CLASS_SUFFIX.length());
            ClassNode declaring = cache.load(owner);
            if (declaring == null) continue;
            MethodNode constructor = ClassKit.findMethod(declaring, "<init>", "()V");
            if (constructor == null) continue;

            AsmWalker.over(constructor)
                .on(Insn.of(FieldInsnNode.class, put -> put.getOpcode() == Opcodes.PUTFIELD), put -> {
                    if (!wanted.contains(put.name)) return;
                    // The push immediately before it, which is a one-hop neighbour read rather than a
                    // walk of its own - and adjacency is the whole of what makes it this field's
                    // value rather than the last constant some earlier statement happened to leave.
                    Float built = literal(AsmWalker.previousReal(put));
                    if (built == null || built == 0f) return;
                    record(out, declaredBy, put, owner, built, diagnostics);
                })
                .run();
        }
        // Order-preserving rather than Map.copyOf, whose iteration order is salted per JVM launch:
        // this is written straight into the table, so a salted order is a file that does not
        // reproduce and a digest that fails its own determinism check.
        return Collections.unmodifiableMap(out);
    }

    /**
     * Records one figure's value, refusing to answer at all where two states disagree.
     *
     * <p>A pose names a figure by its bare field name, so two render states building one name at two
     * values is a question the shipped table has no way to ask. Answering either would bind one
     * subject's figure to another's; the row is dropped and said out loud instead.
     */
    private static void record(
        @NotNull Map<String, Float> out, @NotNull Map<String, String> declaredBy,
        @NotNull FieldInsnNode put, @NotNull String owner, float built, @NotNull Diagnostics diagnostics) {

        Float held = out.get(put.name);
        if (held == null) {
            out.put(put.name, built);
            declaredBy.put(put.name, owner);
            return;
        }
        if (held == built) return;

        diagnostics.info("'%s' is built at %s by %s and at %s by %s, so no default is emitted for it",
            put.name, held, ClassKit.simpleName(declaredBy.get(put.name)), built, ClassKit.simpleName(owner));
        out.remove(put.name);
    }

    /**
     * One constant push, as the float a channel finally computes at.
     *
     * <p>An integral push is a boolean's own spelling here - a flag the render state builds set
     * arrives as a one - so both widths answer and neither is refused.
     */
    private static @Nullable Float literal(@Nullable AbstractInsnNode node) {
        Float single = AsmWalker.floatLiteral(node);
        if (single != null) return single;
        Integer whole = AsmWalker.intLiteral(node);
        return whole == null ? null : (float) (int) whole;
    }

}
