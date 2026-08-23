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
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.RecordComponentNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * What a render-state member holds before anything has happened to the subject.
 *
 * <p>A pose names the members it could not derive so that a caller who models none of them still
 * gets a frame vanilla draws. That rests on knowing what "none of them" IS, and the answer is not
 * always nothing: a render state constructs some of its own fields at something else.
 * {@code LivingEntityRenderState} builds {@code ageScale} at one and is the base every living
 * subject extends, {@code HumanoidRenderState} builds {@code speedValue} at one and every humanoid
 * DIVIDES an arm swing by it, and a wolf's tail rests at a fifth of a turn rather than straight
 * down. A caller answering zero to those gets a collapsed subject, a NaN and a wrong tail, none of
 * which vanilla ever draws.
 *
 * <p><b>An enum member is the same question and it has no zero to fall back on.</b> Answering no
 * constant at all is a state no enum is in, so a pose switching on one takes whichever arm the
 * switch ends at rather than the arm the subject stands in: {@code ArmedEntityRenderState} builds
 * {@code rightArmPose} at {@code EMPTY} and both arms at {@code RIGHT}, and a humanoid that reads
 * those as unset swings an arm forty-four degrees forward at rest. Which constant a subject rests
 * holding can also be the SUBJECT's own fact rather than its render state's - an illager's crossed
 * arms are - so this answers only what the state's own constructor settles, and a per-subject
 * answer overrides it.
 *
 * <p>Only the members a pose actually names are resolved, and, for a figure, only where the value
 * is not already zero: a row for something nothing reads would declare an input nothing supplies,
 * and a numeric row holding zero says what its own absence says. An enum row has no such spelling -
 * every constant is a state, none of them is the absence of one - so every resolved constant is
 * emitted.
 *
 * <p><b>A seed no render ever sees is not one either.</b> A figure the renderer INTERPOLATES across
 * the frame is a reading of the subject's motion, so it is overwritten on every extract and the
 * state's own number stands for nothing. Those rest at zero with every other figure nobody models,
 * because a subject that has never ticked has never started the motion being read.
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
            program.container().forEach(step -> step.values().forEach(expr -> collect(expr, named, walked)));
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
     * Every render-state member the walked poses compare against a declared constant.
     *
     * <p>Walked once per node, on the same terms and for the same reason {@link #namedBy} is: the
     * two questions ride one graph, and a second walk that followed the paths would be the pass
     * that could not afford to run on a humanoid.
     *
     * <p>A member reached through a call rather than held in a field is left out. It has no
     * constructor to read - {@code getMainHandItemStack} is a method, and what it answers at rest is
     * a fact about the subject's inventory rather than about the state's own construction.
     *
     * @param poses what the walk extracted, refusals included and ignored
     * @return the bare member names, in sorted order
     */
    static @NotNull Set<String> constantsNamedBy(@NotNull Map<String, PoseOutcome> poses) {
        Set<String> named = new TreeSet<>();
        Set<Object> walked = Collections.newSetFromMap(new IdentityHashMap<>());
        for (PoseOutcome outcome : poses.values()) {
            if (!(outcome instanceof PoseOutcome.Extracted extracted)) continue;
            PoseProgram program = extracted.program();
            program.container().forEach(step -> step.values().forEach(expr -> tested(expr, named, walked)));
            program.bones().values().forEach(channels ->
                channels.values().forEach(expr -> tested(expr, named, walked)));
        }
        named.removeIf(member -> member.indexOf('.') >= 0 || member.indexOf('(') >= 0);
        return named;
    }

    /** Every member one expression's conditions test, reached through its arms. */
    private static void tested(
        @NotNull PoseExpr expr, @NotNull Set<String> named, @NotNull Set<Object> walked) {

        if (!walked.add(expr)) return;
        switch (expr) {
            case PoseExpr.Op op -> op.operands().forEach(operand -> tested(operand, named, walked));
            case PoseExpr.Select select -> {
                tested(select.whenTrue(), named, walked);
                tested(select.whenFalse(), named, walked);
                tested(select.condition(), named, walked);
            }
            default -> { /* a leaf carries no condition */ }
        }
    }

    /** Every member one condition tests, which is the same question a step lower. */
    private static void tested(
        @NotNull PosePredicate predicate, @NotNull Set<String> named, @NotNull Set<Object> walked) {

        if (!walked.add(predicate)) return;
        switch (predicate) {
            case PosePredicate.EnumEq check -> named.add(check.field());
            case PosePredicate.Compare compare -> {
                tested(compare.left(), named, walked);
                tested(compare.right(), named, walked);
            }
            case PosePredicate.Not not -> tested(not.operand(), named, walked);
            default -> { /* a presence test or a decided constant names no member */ }
        }
    }

    /**
     * Every question the walked poses ask of a reference the render state holds.
     *
     * <p>Walked once per node, on the same terms and for the same reason the other two collectors
     * are. The pair is the identity: whether the left leg is turned about x and whether the right is
     * are two questions, and a table keyed on the receiver alone would answer both legs the same way
     * and splay a stand's legs the same direction.
     *
     * <p>A receiver reached through something rather than held in a field is left out, and so is one
     * reached through a call. Neither has a field of the state to be built with, which is the only
     * thing this can read.
     *
     * @param poses what the walk extracted, refusals included and ignored
     * @return each question as {@code receiver.question}, in sorted order
     */
    static @NotNull Set<String> questionsNamedBy(@NotNull Map<String, PoseOutcome> poses) {
        Set<String> named = new TreeSet<>();
        Set<Object> walked = Collections.newSetFromMap(new IdentityHashMap<>());
        for (PoseOutcome outcome : poses.values()) {
            if (!(outcome instanceof PoseOutcome.Extracted extracted)) continue;
            PoseProgram program = extracted.program();
            program.container().forEach(step -> step.values().forEach(expr -> asked(expr, named, walked)));
            program.bones().values().forEach(channels ->
                channels.values().forEach(expr -> asked(expr, named, walked)));
        }
        return named;
    }

    /** Every question one expression asks, reached through its operands and its arms. */
    private static void asked(
        @NotNull PoseExpr expr, @NotNull Set<String> named, @NotNull Set<Object> walked) {

        if (!walked.add(expr)) return;
        switch (expr) {
            case PoseExpr.InputFn question -> {
                if (question.receiver().indexOf('.') < 0 && question.receiver().indexOf('(') < 0)
                    named.add(question.receiver() + '.' + question.question());
            }
            case PoseExpr.Op op -> op.operands().forEach(operand -> asked(operand, named, walked));
            case PoseExpr.Select select -> {
                asked(select.whenTrue(), named, walked);
                asked(select.whenFalse(), named, walked);
                asked(select.condition(), named, walked);
            }
            default -> { /* a leaf that is not a question asks none */ }
        }
    }

    /** Every question one condition reaches, which is the same walk a step lower. */
    private static void asked(
        @NotNull PosePredicate predicate, @NotNull Set<String> named, @NotNull Set<Object> walked) {

        if (!walked.add(predicate)) return;
        switch (predicate) {
            case PosePredicate.Compare compare -> {
                asked(compare.left(), named, walked);
                asked(compare.right(), named, walked);
            }
            case PosePredicate.Not not -> asked(not.operand(), named, walked);
            default -> { /* an enum test, a presence test or a decided constant asks nothing */ }
        }
    }

    /**
     * What each named question rests answering, read off the value its receiver is built holding.
     *
     * <p><b>A question is not a figure and rests at nothing only by accident.</b> Where a figure the
     * state builds at zero and one nobody models are the same number, a reference the state holds is
     * a whole value and every component of it is an answer - {@code ArmorStandRenderState} builds six
     * {@code Rotations} in its constructor and two of them are a degree of leg splay each way, which
     * a stand answering nothing stands without.
     *
     * <p>Answerable because the shape is closed: the state's constructor assigns the field a static
     * of the field's own declared type, that type is a record of floats, and the static's own
     * initialiser builds it from literals. So the question names a record component, the component
     * names a constructor argument by position, and the argument is a number. Anything else - a value
     * built in the constructor, a component that is not a float, a question naming no component, an
     * argument that is not a literal - answers nothing rather than a guess, and the question falls
     * back to what it rested at before.
     *
     * @param cache the open client jar
     * @param renderState the state this model's own {@code setupAnim} reads
     * @param named the questions the walked poses ask, as {@code receiver.question}
     * @return each question's resting answer, for the ones this shape reaches
     */
    static @NotNull Map<String, Float> resolveQuestions(
        @NotNull ClassNodeCache cache, @NotNull String renderState, @NotNull Collection<String> named) {

        Map<String, FieldInsnNode> sources = new TreeMap<>();
        // Base first, so a state that re-builds an inherited reference wins - the same order and the
        // same reason the enum constants are read in.
        for (String owner : chain(cache, renderState).reversed()) {
            ClassNode declaring = cache.load(owner);
            if (declaring == null) continue;
            MethodNode constructor = ClassKit.findMethod(declaring, "<init>", "()V");
            if (constructor == null) continue;

            AsmWalker.over(constructor)
                .on(Insn.of(FieldInsnNode.class, put -> put.getOpcode() == Opcodes.PUTFIELD), put -> {
                    AbstractInsnNode push = AsmWalker.previousReal(put);
                    if (push instanceof FieldInsnNode read && read.getOpcode() == Opcodes.GETSTATIC
                        && read.desc.equals(put.desc)) sources.put(put.name, read);
                    else sources.remove(put.name);
                })
                .run();
        }

        Map<String, Float> out = new TreeMap<>();
        for (String asked : named) {
            int split = asked.indexOf('.');
            if (split < 0) continue;
            FieldInsnNode source = sources.get(asked.substring(0, split));
            if (source == null) continue;
            Float rests = component(cache, source, asked.substring(split + 1));
            // A zero row says what its own absence says, on the same terms a figure's does - and it
            // cannot shadow the one question that rests at something else, an emptiness being asked
            // of a stack rather than read off a record of floats.
            if (rests != null && rests != 0f) out.put(asked, rests);
        }
        // Order-preserving rather than Map.copyOf, for the reason resolve's own return is.
        return Collections.unmodifiableMap(out);
    }

    /**
     * One record component of the value a static holds, read off the literals its initialiser
     * builds that value from.
     *
     * <p>The component's POSITION is what joins the question to the argument - a record's canonical
     * constructor takes its components in declaration order, so the accessor's name resolves to an
     * index and the index resolves to a push. Reading the accessor's body instead would answer the
     * same thing one indirection later and would need the field-to-component join anyway.
     */
    private static @Nullable Float component(
        @NotNull ClassNodeCache cache, @NotNull FieldInsnNode source, @NotNull String question) {

        if (!source.desc.startsWith("L") || !source.desc.endsWith(";")) return null;
        String type = source.desc.substring(1, source.desc.length() - 1);
        ClassNode held = cache.load(type);
        if (held == null || held.recordComponents == null || held.recordComponents.isEmpty()) return null;

        int found = -1;
        for (int at = 0; at < held.recordComponents.size(); at++) {
            RecordComponentNode component = held.recordComponents.get(at);
            if (!"F".equals(component.descriptor)) return null;
            if (component.name.equals(question)) found = at;
        }
        if (found < 0) return null;
        int index = found;

        MethodNode clinit = ClassKit.findClinit(cache, source.owner);
        if (clinit == null) return null;

        int arity = held.recordComponents.size();
        Float[] built = {null};
        AsmWalker.over(clinit)
            .on(Insn.of(FieldInsnNode.class, put -> put.getOpcode() == Opcodes.PUTSTATIC
                && put.owner.equals(source.owner) && put.name.equals(source.name)), put -> {
                AbstractInsnNode call = AsmWalker.previousReal(put);
                if (!(call instanceof MethodInsnNode init) || init.getOpcode() != Opcodes.INVOKESPECIAL
                    || !ClassKit.INIT.equals(init.name) || !init.owner.equals(type)) return;

                // The arguments walked back one push at a time, which is what makes each of them a
                // literal rather than the tail of an expression that happened to end in one.
                Float[] arguments = new Float[arity];
                AbstractInsnNode node = init;
                for (int at = arity - 1; at >= 0; at--) {
                    node = AsmWalker.previousReal(node);
                    arguments[at] = AsmWalker.floatLiteral(node);
                    if (arguments[at] == null) return;
                }
                built[0] = arguments[index];
            })
            .run();
        return built[0];
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
        Set<String> interpolated = interpolated(cache, wanted);
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
                    if (interpolated.contains(put.name)) {
                        diagnostics.info(
                            "'%s' is built at %s by %s and interpolated across the frame on every extract,"
                                + " so the seed is not what it rests at",
                            put.name, built, ClassKit.simpleName(owner));
                        return;
                    }
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
     * The figures a renderer interpolates ACROSS the frame it is drawing.
     *
     * <p>Such a figure is a reading of the subject's motion rather than a value its state settles,
     * so its seed is a number no render ever sees - the renderer overwrites it on every extract with
     * something it had to be handed a partial tick to compute. An axolotl's is the clearest: its
     * state builds {@code inWaterFactor} at one, so a state nobody extracted into shows a swimming
     * axolotl, while every render reads a smoothed animator that a subject which has never ticked
     * has never started. Answering the seed there swims an axolotl vanilla holds still.
     *
     * <p>The partial tick is the whole of the test, and it is read off the method's own descriptor
     * rather than named: {@code extractRenderState} takes the subject, its state and that tick, so
     * the last parameter of a three-argument form IS the fraction. A statement is bounded by the
     * load of the state it assigns to, which is where the operands of one begin.
     *
     * @param cache the open client jar
     * @param wanted the render-state fields the walked poses read
     * @return the fields whose value a renderer interpolates, in sorted order
     */
    private static @NotNull Set<String> interpolated(
        @NotNull ClassNodeCache cache, @NotNull Set<String> wanted) {

        Set<String> out = new TreeSet<>();
        for (String entry : cache.list(VanillaSourceClasses.Types.ENTITY_RENDERER_PACKAGE, CLASS_SUFFIX)) {
            String owner = entry.substring(0, entry.length() - CLASS_SUFFIX.length());
            ClassNode renderer = cache.load(owner);
            if (renderer == null) continue;
            for (MethodNode method : renderer.methods) {
                if (!VanillaSourceClasses.Methods.EXTRACT_RENDER_STATE.equals(method.name)) continue;
                if ((method.access & Opcodes.ACC_STATIC) != 0) continue;
                Type[] args = ClassKit.argTypes(method.desc);
                if (args.length != 3 || args[2].getSort() != Type.FLOAT) continue;
                int state = 1 + args[0].getSize();
                int fraction = state + args[1].getSize();

                AsmWalker.over(method)
                    .on(Insn.of(FieldInsnNode.class, put -> put.getOpcode() == Opcodes.PUTFIELD
                        && wanted.contains(put.name)), put -> {
                        if (AsmWalker.before(put).real()
                            .until(Insn.of(VarInsnNode.class,
                                load -> load.getOpcode() == Opcodes.ALOAD && load.var == state))
                            .first(Insn.of(VarInsnNode.class,
                                load -> load.getOpcode() == Opcodes.FLOAD && load.var == fraction)) != null)
                            out.add(put.name);
                    })
                    .run();
            }
        }
        return out;
    }

    /**
     * The constant each named enum member is constructed holding.
     *
     * <p>The pushed value is a {@code getstatic} of the constant itself, so the constant's own name
     * is read straight off the instruction - which is also what says the field is an enum one and
     * not a reference to something built here.
     *
     * @param cache the open client jar
     * @param named the render-state members the walked poses test
     * @param diagnostics the scope findings are recorded against
     * @return member name to the constant its own render state builds it holding
     */
    static @NotNull Map<String, String> resolveConstants(
        @NotNull ClassNodeCache cache, @NotNull String renderState, @NotNull Collection<String> named) {

        List<String> chain = chain(cache, renderState);
        Map<String, String> fields = new TreeMap<>();

        // Base first, so a state that re-builds an inherited member wins: a super constructor runs
        // before the subclass's own field initialisers, and the later write is the one that stands.
        // Every field is resolved rather than only the named ones, because a member named through an
        // accessor is answered by fields the pose never names.
        for (String owner : chain.reversed()) {
            ClassNode declaring = cache.load(owner);
            if (declaring == null) continue;
            MethodNode constructor = ClassKit.findMethod(declaring, "<init>", "()V");
            if (constructor == null) continue;

            AsmWalker.over(constructor)
                .on(Insn.of(FieldInsnNode.class, put -> put.getOpcode() == Opcodes.PUTFIELD), put -> {
                    String held = constant(AsmWalker.previousReal(put), put);
                    if (held != null) fields.put(put.name, held);
                })
                .run();
        }

        Map<String, String> out = new TreeMap<>();
        for (String member : named) {
            String held = fields.containsKey(member)
                ? fields.get(member) : forwarded(cache, chain, member, fields);
            if (held != null) out.put(member, held);
        }
        // Order-preserving rather than Map.copyOf, for the reason resolve's own return is.
        return Collections.unmodifiableMap(out);
    }

    /**
     * What an accessor rests answering, read off the fields it forwards to.
     *
     * <p>A member the pose names through a call has no field of its own to rest in, and answering it
     * nothing is the same wrong answer as answering any other member nothing.
     * {@code getMainHandItemStack} picks between two stacks on which arm is the main one, and a
     * subject holding nothing rests at the empty stack down either branch - so what makes this
     * answerable is that every branch lands on the same constant, and what makes it honest is
     * refusing when they do not.
     *
     * @param cache the open client jar
     * @param chain the render state and every state it extends, leaf first
     * @param member the accessor's own name
     * @param fields what each field of the chain rests holding
     * @return the constant every branch rests at, or {@code null} when they differ or one is unknown
     */
    private static @Nullable String forwarded(
        @NotNull ClassNodeCache cache, @NotNull List<String> chain, @NotNull String member,
        @NotNull Map<String, String> fields) {

        for (String owner : chain) {
            ClassNode declaring = cache.load(owner);
            if (declaring == null) continue;
            MethodNode accessor = declaring.methods.stream()
                .filter(method -> method.name.equals(member) && method.desc.startsWith("()L"))
                .findFirst().orElse(null);
            if (accessor == null) continue;

            Set<String> answers = new TreeSet<>();
            AsmWalker.over(accessor)
                .on(Insn.of(FieldInsnNode.class, read -> read.getOpcode() == Opcodes.GETFIELD
                    && read.desc.equals(accessor.desc.substring(2))), read -> answers.add(read.name))
                .run();
            if (answers.isEmpty()) return null;

            Set<String> held = new TreeSet<>();
            for (String field : answers) {
                String constant = fields.get(field);
                if (constant == null) return null;
                held.add(constant);
            }
            return held.size() == 1 ? held.iterator().next() : null;
        }
        return null;
    }

    /**
     * One render state and every state it extends, leaf first.
     *
     * <p>Bounded by the render-state package rather than by a depth cap: the chain leaves it at
     * {@code EntityRenderState}'s own superclass, which declares none of this.
     */
    private static @NotNull List<String> chain(@NotNull ClassNodeCache cache, @NotNull String leaf) {
        List<String> out = new ArrayList<>();
        String current = leaf;
        while (current != null && current.startsWith(VanillaSourceClasses.Types.ENTITY_RENDER_STATE_PACKAGE)) {
            out.add(current);
            ClassNode node = cache.load(current);
            if (node == null) break;
            current = node.superName;
        }
        return out;
    }

    /**
     * One enum-constant push, as the constant's own name.
     *
     * <p><b>A constant of the field's OWN type is what makes this an enum member rather than a
     * reference the state happens to hold.</b> An enum constant is declared by the enum, so its
     * read names that type as owner and the field names it as descriptor; a static of any other
     * type does not. Without that test two unrelated fields sharing a bare name read as one -
     * {@code ArmorStandRenderState.rightArmPose} is a {@code Rotations} beside a humanoid's
     * {@code ArmPose}, and nothing but the type tells them apart.
     *
     * @param node the instruction that pushed the value the field was built with
     * @param put the store the push feeds, read for the field's declared type
     * @return the constant's name, or {@code null} where the push is not a constant of that type
     */
    private static @Nullable String constant(
        @Nullable AbstractInsnNode node, @NotNull FieldInsnNode put) {

        if (!(node instanceof FieldInsnNode read) || read.getOpcode() != Opcodes.GETSTATIC) return null;
        return ("L" + read.owner + ";").equals(put.desc) ? read.name : null;
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
