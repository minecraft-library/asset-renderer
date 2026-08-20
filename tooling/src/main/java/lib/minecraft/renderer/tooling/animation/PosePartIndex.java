package lib.minecraft.renderer.tooling.animation;

import lib.minecraft.renderer.tooling.entity.EntityBoneNames;
import lib.minecraft.renderer.tooling.kernel.ClassKit;
import lib.minecraft.renderer.tooling.kernel.ClassNodeCache;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lib.minecraft.renderer.tooling.kernel.VanillaSourceClasses;
import lib.minecraft.renderer.tooling.walk.AsmWalker;
import lib.minecraft.renderer.tooling.walk.Cells;
import lib.minecraft.renderer.tooling.walk.Insn;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which geometry bone each of a model's {@code ModelPart} fields refers to, for one leaf class.
 *
 * <p>A pose writes through a field, and the field is a name the model chose; the tables are keyed
 * by the name the mesh chose. Vanilla writes the join in its constructor, so that is where both
 * halves are read from - once per class up the chain, because a model offering both a
 * {@code (root)} and a {@code (root, Function)} form builds its parts in the wider one.
 *
 * <p>A scalar field is bound by {@code getChild} on a literal name, which is the shape the bone
 * visibility walk already reads. An array field has four spellings in the corpus and all four are
 * read here, because an array is filled once and posed in a loop, so a name this walk misses is a
 * limb that never moves:
 *
 * <ul>
 * <li><b>Bulk fill.</b> {@code Arrays.setAll(parts, lambda)} - the common one. The per-index name
 * lives a method away, inside the lambda body, either concatenated there or in an indexed-name
 * helper the lambda calls.
 * <li><b>Counted loop.</b> An explicit loop closing each index with an {@code aastore}, naming the
 * part by the same concatenation.
 * <li><b>Filled after allocation.</b> The array is stored to its field first and each element is
 * then written through a fresh read of that field, named by a literal.
 * <li><b>Inline literal.</b> The array is built on the stack and each element is a local a
 * {@code getChild} already bound, with the field name arriving only at the closing store.
 * </ul>
 *
 * <p>The last two carry a literal name per index rather than a recipe, and their names are taken in
 * store order. Nothing reads the store index, because two of the shapes push an index for an
 * unrelated {@code aaload} in between; the allocation count is what validates the result instead, so
 * a fill this walk half-understood is reported rather than shipped short.
 *
 * <p>Resolved per LEAF, never per declaring class: {@code SquidModel} and {@code BabySquidModel}
 * share one {@code setupAnim} body over arrays their own constructors size, so the same instruction
 * resolves to a different count of bones depending on which model is being posed.
 *
 * @param scalarBones model field name to geometry bone name
 * @param arrayBones model array-field name to its bones, by index
 */
public record PosePartIndex(
    @NotNull Map<String, String> scalarBones,
    @NotNull Map<String, List<String>> arrayBones
) {

    /** The descriptor an array-of-parts field carries. */
    private static final @NotNull String PART_ARRAY_DESC = "[L" + VanillaSourceClasses.Types.MODEL_PART + ";";

    /** The descriptor of the indexed-name helpers vanilla builds a part name with. */
    private static final @NotNull String INT_TO_STRING_DESC = "(I)Ljava/lang/String;";

    /** Where the bulk fill lives - most models hand the whole array to it with a lambda. */
    private static final @NotNull String ARRAYS = "java/util/Arrays";

    /** The bulk fill's own name. */
    private static final @NotNull String SET_ALL = "setAll";

    /**
     * Reads one model's part fields, walking its own constructors and its superclasses'.
     *
     * @param cache the open client jar
     * @param modelClass the leaf model's internal name
     * @param diagnostics the scope findings are recorded against
     * @return the index, empty when the class is missing from the jar
     */
    public static @NotNull PosePartIndex of(
        @NotNull ClassNodeCache cache, @NotNull String modelClass, @NotNull Diagnostics diagnostics) {

        Map<String, String> scalars = new LinkedHashMap<>();
        ArrayScan arrays = new ArrayScan(new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashMap<>());

        String current = modelClass;
        while (current != null
            && !current.equals(VanillaSourceClasses.Types.ENTITY_MODEL)
            && !current.equals(VanillaSourceClasses.Types.MODEL)
            && !current.equals(ClassKit.OBJECT_INTERNAL)) {

            ClassNode owner = cache.load(current);
            if (owner == null) break;
            for (MethodNode method : owner.methods) {
                if (!ClassKit.INIT.equals(method.name)) continue;
                EntityBoneNames.collectFieldToBoneNameMap(owner, method, scalars);
                collectPartArrays(cache, method, arrays);
            }
            current = owner.superName;
        }

        return new PosePartIndex(Map.copyOf(scalars), Map.copyOf(enumerate(modelClass, arrays, diagnostics)));
    }

    /**
     * What one constructor said about each array field: how long it is, and how its elements are
     * named - by a concatenation recipe, or by a literal per index.
     *
     * @param lengths array field name to the count its allocation declares
     * @param recipes array field name to the concatenation each index is named by
     * @param literals array field name to the names its elements were given, in store order
     */
    private record ArrayScan(
        @NotNull Map<String, Integer> lengths,
        @NotNull Map<String, String> recipes,
        @NotNull Map<String, List<String>> literals
    ) {}

    /**
     * Reads one constructor's array-of-parts allocations and the name each index is filled from.
     *
     * <p>Latches over one pass, because the instructions that matter are separated by the index
     * arithmetic between them: the allocation length arrives before the {@code anewarray} and the
     * field name at the {@code putfield}, while the array being filled and the names it is filled
     * with arrive on either side of whichever instruction closes the fill.
     */
    private static void collectPartArrays(
        @NotNull ClassNodeCache cache, @NotNull MethodNode ctor, @NotNull ArrayScan scan) {

        Cells.Latch<Integer> pendingLength = Cells.latch();
        Cells.Latch<Integer> allocatedLength = Cells.latch();
        Cells.Latch<String> filling = Cells.latch();
        Cells.Latch<InvokeDynamicInsnNode> pendingLambda = Cells.latch();

        Map<Integer, String> localBones = new HashMap<>();
        List<String> collected = new ArrayList<>();

        AsmWalker.over(ctor)
            .feed(pendingLength)
            .feed(allocatedLength)
            .feed(filling)
            .feed(pendingLambda)
            .on(Insn.of(AbstractInsnNode.class, node -> AsmWalker.intLiteral(node) != null),
                node -> pendingLength.set(AsmWalker.intLiteral(node)))
            .on(Insn.of(TypeInsnNode.class, type -> type.getOpcode() == Opcodes.ANEWARRAY
                    && VanillaSourceClasses.Types.MODEL_PART.equals(type.desc)),
                type -> {
                    if (pendingLength.get() != null) allocatedLength.set(pendingLength.get());
                    // An array built on the stack is filled before anything names it, so the
                    // elements gathered from here belong to whichever field closes it.
                    flush(scan, filling, collected);
                })
            .on(Insn.of(VarInsnNode.class, store -> store.getOpcode() == Opcodes.ASTORE),
                store -> {
                    String bone = boneFromChildCall(AsmWalker.previousReal(store));
                    if (bone != null) localBones.put(store.var, bone);
                })
            .on(Insn.of(FieldInsnNode.class, field -> field.getOpcode() == Opcodes.GETFIELD
                    && PART_ARRAY_DESC.equals(field.desc)),
                field -> {
                    if (field.name.equals(filling.get())) return;
                    flush(scan, filling, collected);
                    filling.set(field.name);
                })
            .on(Insn.of(InvokeDynamicInsnNode.class, indy -> AsmWalker.resolveStringConcatRecipe(indy) != null),
                indy -> recordRecipe(scan, filling.get(), AsmWalker.resolveStringConcatRecipe(indy)))
            .on(Insn.of(InvokeDynamicInsnNode.class, AsmWalker::isLambdaInvokeDynamic), pendingLambda::set)
            .on(Insn.of(MethodInsnNode.class, call -> call.getOpcode() == Opcodes.INVOKESTATIC
                    && INT_TO_STRING_DESC.equals(call.desc)),
                call -> recordRecipe(scan, filling.get(), recipeOf(cache, call)))
            .on(Insn.invokeStatic(ARRAYS, SET_ALL), fill -> {
                if (pendingLambda.get() != null)
                    recordRecipe(scan, filling.get(), recipeInLambda(cache, pendingLambda.get()));
                pendingLambda.clear();
            })
            .on(Insn.opcode(Opcodes.AASTORE), store -> {
                AbstractInsnNode value = AsmWalker.previousReal(store);
                String bone = boneFromChildCall(value);
                if (bone == null && value instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD)
                    bone = localBones.get(load.var);
                if (bone != null) collected.add(bone);
            })
            .on(Insn.of(FieldInsnNode.class, field -> field.getOpcode() == Opcodes.PUTFIELD
                    && PART_ARRAY_DESC.equals(field.desc)),
                field -> {
                    if (allocatedLength.get() != null) scan.lengths().putIfAbsent(field.name, allocatedLength.get());
                    allocatedLength.clear();
                    // An inline array reaches its field only here, so anything gathered since the
                    // allocation is this field's.
                    if (!collected.isEmpty() && filling.get() == null) {
                        scan.literals().putIfAbsent(field.name, List.copyOf(collected));
                        collected.clear();
                    }
                })
            .run();

        flush(scan, filling, collected);
    }

    /** Attaches whatever literal names have been gathered to the array they were gathered for. */
    private static void flush(
        @NotNull ArrayScan scan, @NotNull Cells.Latch<String> filling, @NotNull List<String> collected) {

        if (filling.get() != null && !collected.isEmpty())
            scan.literals().putIfAbsent(filling.get(), List.copyOf(collected));
        collected.clear();
    }

    private static void recordRecipe(@NotNull ArrayScan scan, @Nullable String field, @Nullable String recipe) {
        if (field != null && recipe != null) scan.recipes().putIfAbsent(field, recipe);
    }

    /**
     * The bone name a {@code getChild} on a literal answers, read one hop back from the call.
     *
     * @param call the instruction to test as the child lookup
     * @return the bone name, or {@code null} when this is not a literal-named lookup
     */
    private static @Nullable String boneFromChildCall(@Nullable AbstractInsnNode call) {
        if (call == null) return null;
        if (!AsmWalker.isInvokeVirtual(call, VanillaSourceClasses.Types.MODEL_PART, VanillaSourceClasses.Methods.GET_CHILD))
            return null;
        return AsmWalker.previousReal(call) instanceof LdcInsnNode ldc && ldc.cst instanceof String name ? name : null;
    }

    /**
     * The concatenation recipe behind an indexed-name helper, following the call into its body.
     *
     * @param cache the open client jar
     * @param call the helper call
     * @return the recipe, or {@code null} when the helper holds no concatenation
     */
    private static @Nullable String recipeOf(@NotNull ClassNodeCache cache, @NotNull MethodInsnNode call) {
        MethodNode helper = ClassKit.findMethodInHierarchy(cache, call.owner, call.name, call.desc);
        return helper == null ? null : AsmWalker.findStringConcatRecipeIn(helper);
    }

    /**
     * The concatenation recipe a bulk fill's lambda names each index with.
     *
     * <p>The lambda either concatenates in its own body or calls an indexed-name helper that does;
     * both spellings are in the corpus, so both are followed rather than one being assumed.
     *
     * @param cache the open client jar
     * @param indy the lambda the fill was handed
     * @return the recipe, or {@code null} when the lambda names its part some other way
     */
    private static @Nullable String recipeInLambda(
        @NotNull ClassNodeCache cache, @NotNull InvokeDynamicInsnNode indy) {

        Handle handle = AsmWalker.extractLambdaHandle(indy);
        if (handle == null) return null;
        MethodNode body = ClassKit.findMethodInHierarchy(cache, handle.getOwner(), handle.getName(), handle.getDesc());
        if (body == null) return null;

        String inline = AsmWalker.findStringConcatRecipeIn(body);
        if (inline != null) return inline;
        return AsmWalker.over(body).firstNotNull(node -> node instanceof MethodInsnNode call
            && call.getOpcode() == Opcodes.INVOKESTATIC
            && INT_TO_STRING_DESC.equals(call.desc) ? recipeOf(cache, call) : null);
    }

    /**
     * Expands each array's length and naming into the bone every index resolves to.
     *
     * <p>A recipe generates the whole run; a literal fill has to have named every index the
     * allocation declared. Anything else is reported rather than guessed, because an array silently
     * short by one bone is a pose missing a tentacle.
     */
    private static @NotNull Map<String, List<String>> enumerate(
        @NotNull String modelClass, @NotNull ArrayScan scan, @NotNull Diagnostics diagnostics) {

        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : scan.lengths().entrySet()) {
            String field = entry.getKey();
            int length = entry.getValue();
            String recipe = scan.recipes().get(field);
            if (recipe != null) {
                List<String> bones = new ArrayList<>(length);
                for (int index = 0; index < length; index++)
                    bones.add(ClassKit.applyStringConcatRecipeWithInt(recipe, index));
                out.put(field, List.copyOf(bones));
                continue;
            }
            List<String> literals = scan.literals().get(field);
            if (literals != null && literals.size() == length) {
                out.put(field, literals);
                continue;
            }
            diagnostics.warn("%s allocates '%s' with %d part(s) but names %d of them",
                ClassKit.simpleName(modelClass), field, length, literals == null ? 0 : literals.size());
        }
        for (String named : scan.recipes().keySet())
            if (!scan.lengths().containsKey(named))
                diagnostics.warn("%s fills '%s' but nothing in the chain allocates it",
                    ClassKit.simpleName(modelClass), named);
        return out;
    }

    /**
     * The bone a scalar part field refers to.
     *
     * @param field the model field name
     * @return the bone name, or {@code null} when the field is not a bound part
     */
    public @Nullable String boneOf(@NotNull String field) {
        return this.scalarBones.get(field);
    }

    /**
     * The bone one element of an array part field refers to.
     *
     * @param field the model array-field name
     * @param index the element index
     * @return the bone name, or {@code null} when the field is not a bound array or the index is
     *     outside what its constructor allocated
     */
    public @Nullable String boneOf(@NotNull String field, int index) {
        List<String> bones = this.arrayBones.get(field);
        return bones == null || index < 0 || index >= bones.size() ? null : bones.get(index);
    }

}
