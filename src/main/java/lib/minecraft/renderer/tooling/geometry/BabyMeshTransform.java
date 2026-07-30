package lib.minecraft.renderer.tooling.geometry;

import lib.minecraft.renderer.tooling.kernel.AsmKit;
import lib.minecraft.renderer.tooling.kernel.ClassNodeCache;
import lib.minecraft.renderer.tooling.kernel.VanillaSourceClasses;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A whole-mesh transformer that shrinks a model into its aged-down proportions, read off the
 * {@code <clinit>} that binds it.
 *
 * <p>The other whole-mesh transformer, {@code MeshTransformer.scaling(F)}, is one number and rewrites
 * one pose. This one is seven values and rewrites a pose per top-level bone, choosing between two
 * operators by name - so it is carried as a value rather than folded into the scale, and the geometry
 * key names it rather than encoding it.
 *
 * <p>What it does, from {@code BabyModelTransform.apply}: it builds a <b>fresh</b> mesh and re-adds
 * each of the source root's <b>direct children</b> under
 * {@code child.transformed(headParts.contains(name) ? headOp : bodyOp)}, where
 * {@code headOp(p) = p.translated(0, headYOffset, headZOffset).scaled(headFactor)} and
 * {@code bodyOp(p) = p.translated(0, bodyYOffset, 0).scaled(bodyFactor)}. Three consequences, each
 * read rather than inferred from the name:
 *
 * <ul>
 *   <li><b>Only the root's direct children move.</b> {@code PartDefinition.transformed} builds its
 *       copy from the same cube list by reference and {@code putAll}s the children in untransformed,
 *       so a grandchild keeps its own pose and rides its top-level ancestor's.</li>
 *   <li><b>The translate happens before the scale</b>, and {@code PartPose.scaled} multiplies the
 *       pivot as well as the three scale components - so a bone lands at {@code (pivot + offset) * f}
 *       on six fields, not on three, and not at {@code pivot * f + offset}.</li>
 *   <li><b>The source root's own pose and cubes are discarded</b> - the result starts from a fresh
 *       {@code MeshDefinition}, whose root is empty at {@code PartPose.ZERO}. Harmless for a mesh no
 *       other transformer has already rewritten the root of, which is every mesh this reaches.</li>
 * </ul>
 *
 * @param owner the binding field's owning class, in JVM internal form
 * @param field the static field the transformer is bound to
 * @param scaleHead whether the named parts are scaled at all, or only offset
 * @param headYOffset the Y the named parts are translated by before scaling
 * @param headZOffset the Z the named parts are translated by before scaling
 * @param headScale the divisor the named parts' scale factor is taken from
 * @param bodyScale the divisor every other top-level bone's scale factor is taken from
 * @param bodyYOffset the Y every other top-level bone is translated by before scaling
 * @param headParts the bone names that take the head operator
 */
public record BabyMeshTransform(
    @NotNull String owner,
    @NotNull String field,
    boolean scaleHead,
    float headYOffset,
    float headZOffset,
    float headScale,
    float bodyScale,
    float bodyYOffset,
    @NotNull Set<String> headParts
) {

    /**
     * The numerator vanilla's {@code apply} divides by {@link #headScale} - a baby's head is drawn
     * half again the size its body's shrink would give it, which is what makes the proportions read
     * as a child rather than as a small adult.
     */
    private static final float HEAD_NUMERATOR = 1.5f;

    /** Descriptor of the widest {@code BabyModelTransform} constructor - the one every other delegates to. */
    private static final @NotNull String FULL_DESC = "(ZFFFFFLjava/util/Set;)V";

    /** Descriptor of the four-argument constructor, which fixes the three scales and the body offset. */
    private static final @NotNull String SHORT_DESC = "(ZFFLjava/util/Set;)V";

    /** Descriptor of the one-argument constructor, which fixes the head offsets as well. */
    private static final @NotNull String BARE_DESC = "(Ljava/util/Set;)V";

    /** The scale divisor, body Y offset and head offsets the narrower constructors substitute. */
    private static final float DEFAULT_SCALE = 2f;
    private static final float DEFAULT_BODY_Y_OFFSET = 24f;
    private static final float DEFAULT_HEAD_Y_OFFSET = 5f;
    private static final float DEFAULT_HEAD_Z_OFFSET = 2f;

    /**
     * The factor the named parts are scaled by.
     *
     * @return the head factor, {@code 1} when this transformer only offsets them
     */
    public float headFactor() {
        return this.scaleHead ? HEAD_NUMERATOR / this.headScale : 1f;
    }

    /**
     * The factor every other top-level bone is scaled by.
     *
     * @return the body factor
     */
    public float bodyFactor() {
        return 1f / this.bodyScale;
    }

    /**
     * Whether a top-level bone takes the head operator.
     *
     * @param bone the top-level bone name
     * @return whether the bone is one of the named parts
     */
    public boolean isHeadPart(@NotNull String bone) {
        return this.headParts.contains(bone);
    }

    /**
     * The geometry-key discriminator - the constant this transformer is, rather than the seven values
     * it holds.
     *
     * <p>Named the way {@code @ref=} names an enum constant: two distinct transformers can never
     * collide on it, which value-encoding seven arguments would only achieve by spelling all seven
     * into every key that carries one.
     *
     * @return the {@code SimpleClass.FIELD} discriminator
     */
    public @NotNull String discriminator() {
        return this.owner.substring(this.owner.lastIndexOf('/') + 1) + '.' + this.field;
    }

    /**
     * Resolves a static {@code MeshTransformer} field to the {@code BabyModelTransform} it is bound
     * to, or {@code null} when its initialiser is anything else.
     *
     * <p>The bind is a plain {@code new / dup / <args> / <init> / PUTSTATIC} run, so the walk
     * collects the literals pushed since the allocation and reads them off at the constructor by
     * descriptor. The two narrower constructors delegate to the widest with fixed values rather than
     * doing anything of their own, so they are answered by substituting the same values.
     *
     * @param cache the session's jar cache
     * @param owner the field's owning class, in JVM internal form
     * @param field the static field name
     * @return the bound transformer, or {@code null} for any other initialiser
     */
    public static @Nullable BabyMeshTransform resolve(
        @NotNull ClassNodeCache cache, @NotNull String owner, @NotNull String field
    ) {
        ClassNode cls = cache.load(owner);
        MethodNode clinit = cls == null ? null : AsmKit.findMethod(cls, AsmKit.CLINIT);
        if (clinit == null) return null;

        List<Integer> ints = new ArrayList<>();
        List<Float> floats = new ArrayList<>();
        Set<String> strings = new LinkedHashSet<>();
        BabyMeshTransform pending = null;

        for (AbstractInsnNode in : clinit.instructions) {
            if (AsmKit.isPseudoNode(in)) continue;

            if (in instanceof TypeInsnNode type
                && in.getOpcode() == Opcodes.NEW
                && VanillaSourceClasses.Types.BABY_MODEL_TRANSFORM.equals(type.desc)) {
                ints.clear();
                floats.clear();
                strings.clear();
                pending = null;
                continue;
            }

            Integer asInt = AsmKit.readIntLiteral(in);
            if (asInt != null) {
                ints.add(asInt);
                continue;
            }
            Float asFloat = AsmKit.readFloatLiteral(in);
            if (asFloat != null) {
                floats.add(asFloat);
                continue;
            }
            if (in instanceof LdcInsnNode ldc && ldc.cst instanceof String value) {
                strings.add(value);
                continue;
            }

            if (in instanceof MethodInsnNode mi
                && in.getOpcode() == Opcodes.INVOKESPECIAL
                && AsmKit.INIT.equals(mi.name)
                && VanillaSourceClasses.Types.BABY_MODEL_TRANSFORM.equals(mi.owner)) {
                pending = build(owner, field, mi.desc, ints, floats, strings);
                continue;
            }

            if (in instanceof FieldInsnNode fi
                && in.getOpcode() == Opcodes.PUTSTATIC
                && owner.equals(fi.owner)
                && field.equals(fi.name)) return pending;
        }
        return null;
    }

    /**
     * Assembles one constructor call site, substituting the values the narrower constructors pass on
     * their delegate. Answers {@code null} for an argument list that does not fill the descriptor,
     * which is what a transformer built from anything but literals looks like.
     */
    private static @Nullable BabyMeshTransform build(
        @NotNull String owner,
        @NotNull String field,
        @NotNull String desc,
        @NotNull List<Integer> ints,
        @NotNull List<Float> floats,
        @NotNull Set<String> parts
    ) {
        if (parts.isEmpty()) return null;
        Set<String> headParts = Set.copyOf(parts);
        return switch (desc) {
            case FULL_DESC -> ints.isEmpty() || floats.size() < 5 ? null : new BabyMeshTransform(
                owner, field, ints.getFirst() != 0, floats.getFirst(), floats.get(1),
                floats.get(2), floats.get(3), floats.get(4), headParts);
            case SHORT_DESC -> ints.isEmpty() || floats.size() < 2 ? null : new BabyMeshTransform(
                owner, field, ints.getFirst() != 0, floats.getFirst(), floats.get(1),
                DEFAULT_SCALE, DEFAULT_SCALE, DEFAULT_BODY_Y_OFFSET, headParts);
            case BARE_DESC -> new BabyMeshTransform(owner, field, false,
                DEFAULT_HEAD_Y_OFFSET, DEFAULT_HEAD_Z_OFFSET,
                DEFAULT_SCALE, DEFAULT_SCALE, DEFAULT_BODY_Y_OFFSET, headParts);
            default -> null;
        };
    }

}
