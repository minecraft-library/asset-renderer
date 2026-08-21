package lib.minecraft.renderer.tooling.animation;

import dev.simplified.annotations.UtilityClass;
import lib.minecraft.renderer.tooling.kernel.ClassKit;
import lib.minecraft.renderer.tooling.kernel.ClassNodeCache;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lib.minecraft.renderer.tooling.kernel.VanillaSourceClasses;
import lib.minecraft.renderer.tooling.walk.AsmWalker;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Answers which keyframe clips a model plays, and under what gate.
 *
 * <p>A model never names a clip where it plays it. The constructor binds an authored
 * {@code AnimationDefinition} to the bone tree and parks the result in a field; some method later
 * reads that field and calls one of three {@code apply} forms on it. So the join is a field name,
 * and resolving it means reading the constructor and the play sites separately.
 *
 * <p>Two shapes carry the clip into the constructor. Most models name the constant inline. The
 * three families with an adult and a baby form - camel, armadillo, rabbit - declare an abstract
 * base that takes its clips as constructor parameters, and each subclass supplies its own set at
 * the {@code super} call, which is what lets one base serve two subjects. Those are matched by
 * position among the parameters that are clips, never by name.
 */
@UtilityClass
public final class ClipBindingResolver {

    private static final @NotNull String DEFINITION_DESC = "L" + VanillaSourceClasses.Types.ANIMATION_DEFINITION + ";";
    private static final @NotNull String ANIMATION_DESC = "L" + VanillaSourceClasses.Types.KEYFRAME_ANIMATION + ";";

    /** Where the walk up a model's superclass chain stops. */
    private static final @NotNull List<String> ROOTS = List.of(
        VanillaSourceClasses.Types.ENTITY_MODEL, VanillaSourceClasses.Types.MODEL, "java/lang/Object");

    /**
     * Resolves every clip the given model plays.
     *
     * @param cache the open client jar
     * @param model the model's internal name
     * @param diagnostics the scope findings are recorded against
     * @return the bindings in play-site order, empty when the model plays no clip
     */
    public static @NotNull List<ClipBinding> resolve(
        @NotNull ClassNodeCache cache, @NotNull String model, @NotNull Diagnostics diagnostics) {

        List<ClassNode> chain = chain(cache, model);
        if (chain.isEmpty()) return List.of();

        Map<String, String> fieldToClip = fieldToClip(cache, model);
        List<ClipBinding> found = new ArrayList<>();
        for (ClassNode owner : chain)
            for (MethodNode method : owner.methods) {
                if (ClassKit.INIT.equals(method.name) || ClassKit.CLINIT.equals(method.name)) continue;
                collectPlaySites(method, fieldToClip, found, owner, diagnostics);
            }
        return List.copyOf(found);
    }

    /**
     * Which clip each of a model's {@code AnimationDefinition} fields was baked from.
     *
     * <p>The join a play site needs: a model never names a clip where it plays it, so recovering
     * what a call applies means having read the constructors first. The pose walk needs the same
     * map, because meeting a play site mid-body it has to say which clip is being played rather
     * than only that one is.
     *
     * @param cache the open client jar
     * @param model the model's internal name
     * @return field name to clip coordinate, empty when the model binds none
     */
    public static @NotNull Map<String, String> fieldToClip(@NotNull ClassNodeCache cache, @NotNull String model) {
        Map<String, String> out = new LinkedHashMap<>();
        List<String> passedDown = List.of();
        for (ClassNode owner : chain(cache, model)) {
            MethodNode constructor = constructor(owner);
            if (constructor == null) continue;
            out.putAll(bindings(owner, constructor, passedDown));
            passedDown = clipArguments(constructor);
        }
        return out;
    }

    /** The model's own class and every superclass below the model roots, most-derived first. */
    private static @NotNull List<ClassNode> chain(@NotNull ClassNodeCache cache, @NotNull String model) {
        List<ClassNode> chain = new ArrayList<>();
        String current = model;
        while (current != null && !ROOTS.contains(current) && chain.size() < 8) {
            ClassNode node = cache.load(current);
            if (node == null) break;
            chain.add(node);
            current = node.superName;
        }
        return chain;
    }

    private static @Nullable MethodNode constructor(@NotNull ClassNode owner) {
        for (MethodNode method : owner.methods)
            if (ClassKit.INIT.equals(method.name)) return method;
        return null;
    }

    /**
     * Reads one constructor's field bindings.
     *
     * <p>The shape is strictly adjacent - the clip is pushed, the bone tree is pushed, {@code bake}
     * is called and the result is stored - so a latch over the last clip seen is enough, and a
     * stack model would be answering a question this code does not ask.
     *
     * @param owner the declaring class
     * @param constructor the constructor to read
     * @param passedDown the clips a subclass supplied at its {@code super} call, positionally
     * @return field name to clip coordinate
     */
    private static @NotNull Map<String, String> bindings(
        @NotNull ClassNode owner, @NotNull MethodNode constructor, @NotNull List<String> passedDown) {

        Map<Integer, String> slotToClip = clipParameterSlots(constructor, passedDown);
        Map<String, String> out = new LinkedHashMap<>();
        String[] pending = {null};

        AsmWalker.over(constructor).real().forEach(in -> {
            if (in.getOpcode() == Opcodes.GETSTATIC && in instanceof FieldInsnNode field
                && DEFINITION_DESC.equals(field.desc)) {
                pending[0] = ClassKit.simpleName(field.owner) + "#" + field.name;
            } else if (in.getOpcode() == Opcodes.ALOAD && in instanceof VarInsnNode load) {
                String supplied = slotToClip.get(load.var);
                if (supplied != null) pending[0] = supplied;
            } else if (in.getOpcode() == Opcodes.PUTFIELD && in instanceof FieldInsnNode put
                && ANIMATION_DESC.equals(put.desc) && owner.name.equals(put.owner)) {
                if (pending[0] != null) out.put(put.name, pending[0]);
                pending[0] = null;
            }
        });
        return out;
    }

    /**
     * Maps each local slot holding a clip parameter to the clip a subclass supplied for it.
     *
     * <p>Matched by position among the clip-typed parameters rather than by slot arithmetic alone,
     * so a base whose first parameter is the bone tree lines up with a subclass that passes the
     * bone tree first and its clips after.
     */
    private static @NotNull Map<Integer, String> clipParameterSlots(
        @NotNull MethodNode constructor, @NotNull List<String> passedDown) {

        Map<Integer, String> out = new LinkedHashMap<>();
        if (passedDown.isEmpty()) return out;
        Type[] parameters = ClassKit.argTypes(constructor.desc);
        int slot = 1;
        int clipIndex = 0;
        for (Type parameter : parameters) {
            if (DEFINITION_DESC.equals(parameter.getDescriptor())) {
                if (clipIndex < passedDown.size()) out.put(slot, passedDown.get(clipIndex));
                clipIndex++;
            }
            slot += parameter.getSize();
        }
        return out;
    }

    /**
     * Reads the clip constants a constructor hands to its {@code super} call, in argument order.
     *
     * @param constructor the subclass constructor
     * @return the clip coordinates, empty when it passes none
     */
    private static @NotNull List<String> clipArguments(@NotNull MethodNode constructor) {
        List<String> collected = new ArrayList<>();
        boolean[] closed = {false};

        AsmWalker.over(constructor).real().forEach(in -> {
            if (closed[0]) return;
            if (in.getOpcode() == Opcodes.GETSTATIC && in instanceof FieldInsnNode field
                && DEFINITION_DESC.equals(field.desc)) {
                collected.add(ClassKit.simpleName(field.owner) + "#" + field.name);
            } else if (in.getOpcode() == Opcodes.INVOKESPECIAL && in instanceof MethodInsnNode call
                && ClassKit.INIT.equals(call.name)) {
                closed[0] = true;
            }
        });
        return List.copyOf(collected);
    }

    /**
     * Finds every site playing a bound clip, recording the gate the call form names.
     *
     * <p>A play site reads its receiver several instructions before the call - the render state's
     * own fields are pushed in between - so the latch holds only fields typed as a bound clip,
     * which the intervening reads are not.
     */
    private static void collectPlaySites(
        @NotNull MethodNode method, @NotNull Map<String, String> fieldToClip,
        @NotNull List<ClipBinding> out, @NotNull ClassNode owner, @NotNull Diagnostics diagnostics) {

        String[] receiver = {null};

        AsmWalker.over(method).real().forEach(in -> {
            if (in.getOpcode() == Opcodes.GETFIELD && in instanceof FieldInsnNode field
                && ANIMATION_DESC.equals(field.desc)) {
                receiver[0] = field.name;
                return;
            }
            if (!(in instanceof MethodInsnNode call)
                || !VanillaSourceClasses.Types.KEYFRAME_ANIMATION.equals(call.owner)) return;

            ClipBinding.Gate gate = gateOf(call.name);
            if (gate == null) return;
            String clip = receiver[0] == null ? null : fieldToClip.get(receiver[0]);
            if (clip == null)
                diagnostics.warn("%s.%s plays a clip through '%s', which no constructor binds",
                    ClassKit.simpleName(owner.name), method.name, String.valueOf(receiver[0]));
            else out.add(new ClipBinding(clip, gate));
            receiver[0] = null;
        });
    }

    private static @Nullable ClipBinding.Gate gateOf(@NotNull String method) {
        if (VanillaSourceClasses.Methods.APPLY.equals(method)) return ClipBinding.Gate.STATE;
        if (VanillaSourceClasses.Methods.APPLY_WALK.equals(method)) return ClipBinding.Gate.WALK;
        if (VanillaSourceClasses.Methods.APPLY_STATIC.equals(method)) return ClipBinding.Gate.STATIC;
        return null;
    }

}
