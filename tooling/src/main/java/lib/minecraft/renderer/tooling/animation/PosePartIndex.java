package lib.minecraft.renderer.tooling.animation;

import lib.minecraft.renderer.tooling.entity.EntityBoneNames;
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
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Which geometry bone each of a model's {@code ModelPart} fields refers to, for one leaf class.
 *
 * <p>A pose writes through a field, and the field is a name the model chose; the tables are keyed
 * by the name the mesh chose. Vanilla writes the join in its constructor, so that is where both
 * halves are read from - once per class up the chain, because a model offering both a
 * {@code (root)} and a {@code (root, Function)} form builds its parts in the wider one.
 *
 * <p>Both halves of the join - the scalar fields and the arrays of parts - are read by
 * {@link EntityBoneNames}, which the bone-visibility walk shares.
 *
 * <p>The root every model inherits is read the same way and is two different things depending on
 * what the chain did with it. A constructor either hands its root parameter up unchanged, in which
 * case the field is the baked mesh root - a container the mesh flattens and names nowhere - or it
 * narrows the parameter with one {@code getChild} first, in which case the field is that named bone
 * like any other. Only the argument to {@code super} tells them apart, so that is where it is read
 * from; the field's own name says nothing, being the same word either way.
 *
 * @param scalarBones model field name to geometry bone name
 * @param arrayBones model array-field name to its bones, by index
 * @param rootBone the bone the inherited root field names, empty when it is the mesh root itself
 */
public record PosePartIndex(
    @NotNull Map<String, String> scalarBones,
    @NotNull Map<String, List<String>> arrayBones,
    @NotNull Optional<String> rootBone
) {

    /** The descriptor a part field carries. */
    private static final @NotNull String PART_DESC = VanillaSourceClasses.Descs.MODEL_PART_REF;

    /** Where a model constructor's root parameter sits, {@code this} holding the slot below it. */
    private static final int ROOT_PARAMETER_SLOT = 1;

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
        String rooted = null;

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
                if (rooted == null) rooted = rootedAt(owner, method);
            }
            current = owner.superName;
        }

        return new PosePartIndex(Map.copyOf(scalars),
            Map.copyOf(EntityBoneNames.partArrayBoneNames(cache, modelClass, diagnostics)),
            Optional.ofNullable(rooted));
    }

    /**
     * The bone a constructor narrows its root parameter to before handing it up the chain.
     *
     * <p>Read from the region before the super call, which is where the JVM requires the argument
     * to be built and therefore the only place it can be. A narrowing is one {@code getChild} on a
     * literal taken off the parameter itself - a lookup off anything else is some other part being
     * cached, and says nothing about what the chain was handed.
     *
     * @param owner the class declaring the constructor
     * @param ctor the constructor to read
     * @return the bone name, or {@code null} when the parameter goes up unchanged
     */
    private static @Nullable String rootedAt(@NotNull ClassNode owner, @NotNull MethodNode ctor) {
        Type[] parameters = ClassKit.argTypes(ctor.desc);
        if (owner.superName == null || parameters.length == 0
            || !PART_DESC.equals(parameters[0].getDescriptor())) return null;

        return AsmWalker.over(ctor)
            .until(Insn.invokeSpecial(owner.superName, ClassKit.INIT))
            .firstNotNull(node -> takesRootParameter(node) ? EntityBoneNames.boneFromChildCall(node) : null);
    }

    /**
     * Whether a child lookup is taken off the root parameter, which sits one slot above
     * {@code this} because a model constructor declares it first.
     *
     * @param call the instruction to test as the child lookup
     * @return whether the lookup reads the constructor's own root parameter
     */
    private static boolean takesRootParameter(@NotNull AbstractInsnNode call) {
        AbstractInsnNode named = AsmWalker.previousReal(call);
        return named != null && AsmWalker.previousReal(named) instanceof VarInsnNode load
            && load.getOpcode() == Opcodes.ALOAD && load.var == ROOT_PARAMETER_SLOT;
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
