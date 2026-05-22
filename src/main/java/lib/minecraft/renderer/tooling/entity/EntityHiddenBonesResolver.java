package lib.minecraft.renderer.tooling.entity;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import lib.minecraft.renderer.tooling.util.AsmKit;
import lib.minecraft.renderer.tooling.util.Diagnostics;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.zip.ZipFile;

/**
 * Walks a model class's constructor (and its inherited constructor chain) for bones whose
 * {@code visible} flag is unconditionally cleared during construction. Matches the bytecode
 * shape vanilla emits for {@code this.<bone>.visible = false} at the top of a model's
 * {@code <init>}:
 * <pre>
 *   aload_0
 *   getfield   ModelClass.<bone> : Lnet/minecraft/client/model/geom/ModelPart;
 *   iconst_0
 *   putfield   ModelPart.visible : Z
 * </pre>
 * The harness-frozen renderer never re-enables these bones at runtime, so the static
 * pipeline must hide them too - currently encoded in {@code entity_models_overrides.json}
 * as {@code hidden_bones} entries for armor_stand, pillager, vindicator, evoker (all hide
 * "hat"; ArmorStandModel and IllagerModel set their hat invisible in the constructor).
 *
 * <p>The walker traverses the constructor inheritance chain (model class + parents up to
 * {@code EntityModel}) so a subclass that inherits IllagerModel inherits its hat hide.
 * Conditional or state-dependent visibility writes (PUTFIELD preceded by an FLOAD / GETFIELD
 * / IF result rather than ICONST_0) are ignored - those are the runtime
 * {@code arms.visible = state.isAggressive} pattern in setupAnim, not the constructor's
 * unconditional default.
 */
@UtilityClass
public final class EntityHiddenBonesResolver {

    private static final @NotNull String MODEL_PART = "net/minecraft/client/model/geom/ModelPart";
    private static final @NotNull String MODEL_PART_VISIBLE_DESC = "Z";
    private static final @NotNull String ENTITY_MODEL = "net/minecraft/client/model/EntityModel";

    /**
     * Returns the set of bone field names whose {@code visible} flag is unconditionally
     * cleared by a {@code this.<bone>.visible = false} assignment in the model class's
     * constructor (or any ancestor model class's constructor up to {@code EntityModel}),
     * MINUS any bones the renderer's own constructor re-enables via
     * {@code this.model.get<Bone>().visible = true} or {@code this.model.<bone>.visible = true}
     * (the IllusionerRenderer pattern that overrides the IllagerModel hat hide).
     * Returns an empty list when the resulting set is empty.
     */
    public static @NotNull ConcurrentList<String> resolve(
        @NotNull ZipFile zip,
        @NotNull String modelClassInternal,
        @NotNull String rendererClassInternal,
        @NotNull Diagnostics diag
    ) {
        LinkedHashSet<String> hidden = new LinkedHashSet<>();
        String current = modelClassInternal;
        while (current != null && !current.equals(ENTITY_MODEL) && !current.equals(AsmKit.OBJECT_INTERNAL)) {
            ClassNode cn = AsmKit.loadClass(zip, current);
            if (cn == null) break;
            MethodNode ctor = AsmKit.findMethod(cn, AsmKit.INIT);
            if (ctor != null) collectHiddenBones(cn, ctor, hidden);
            current = cn.superName;
        }
        if (hidden.isEmpty()) return Concurrent.newList();

        LinkedHashSet<String> reEnabled = collectReEnabledBones(zip, rendererClassInternal);
        if (!reEnabled.isEmpty()) {
            hidden.removeAll(reEnabled);
            diag.info("hidden-bones: '%s' -> %s (renderer '%s' re-enables %s)",
                modelClassInternal, hidden, rendererClassInternal, reEnabled);
        } else {
            diag.info("hidden-bones: '%s' -> %s", modelClassInternal, hidden);
        }
        if (hidden.isEmpty()) return Concurrent.newList();
        ConcurrentList<String> out = Concurrent.newList();
        out.addAll(hidden);
        return out;
    }

    /**
     * Walks the renderer class's {@code <init>} for {@code <something>.visible = true}
     * assignments and returns the inferred bone names. Two shapes are honoured:
     * <pre>
     *   GETFIELD <bone>:LModelPart;  ; ICONST_1 ; PUTFIELD ModelPart.visible:Z
     *   INVOKEVIRTUAL get<Bone>()    ; ICONST_1 ; PUTFIELD ModelPart.visible:Z
     * </pre>
     * The bone name comes from either the GETFIELD's name or the method's "get" suffix
     * (lowercased first character). Returns an empty set on no matches.
     */
    private static @NotNull LinkedHashSet<String> collectReEnabledBones(@NotNull ZipFile zip, @NotNull String rendererClassInternal) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        ClassNode cn = AsmKit.loadClass(zip, rendererClassInternal);
        if (cn == null) return out;
        MethodNode ctor = AsmKit.findMethod(cn, AsmKit.INIT);
        if (ctor == null) return out;
        for (AbstractInsnNode in = ctor.instructions.getFirst(); in != null; in = in.getNext()) {
            if (in.getOpcode() != Opcodes.PUTFIELD) continue;
            if (!(in instanceof FieldInsnNode put)) continue;
            if (!MODEL_PART.equals(put.owner)) continue;
            if (!"visible".equals(put.name)) continue;
            if (!MODEL_PART_VISIBLE_DESC.equals(put.desc)) continue;
            AbstractInsnNode valueInsn = AsmKit.previousReal(in);
            if (valueInsn == null || valueInsn.getOpcode() != Opcodes.ICONST_1) continue;
            AbstractInsnNode pathInsn = AsmKit.previousReal(valueInsn);
            if (pathInsn == null) continue;
            String bone = extractBoneName(pathInsn);
            if (bone != null) out.add(bone);
        }
        return out;
    }

    /**
     * Pulls the bone field name from the instruction immediately preceding the ICONST_1
     * + PUTFIELD ModelPart.visible:Z pair. Either a {@code GETFIELD <bone>:LModelPart;}
     * (direct field access) or an {@code INVOKEVIRTUAL get<Bone>():LModelPart;}
     * (accessor). Returns {@code null} when the instruction shape doesn't match.
     */
    private static String extractBoneName(@NotNull AbstractInsnNode node) {
        if (node.getOpcode() == Opcodes.GETFIELD && node instanceof FieldInsnNode get) {
            if (get.desc == null || !get.desc.equals("Lnet/minecraft/client/model/geom/ModelPart;")) return null;
            return get.name;
        }
        if (node.getOpcode() == Opcodes.INVOKEVIRTUAL && node instanceof MethodInsnNode mi) {
            if (mi.desc == null || !mi.desc.endsWith(")Lnet/minecraft/client/model/geom/ModelPart;")) return null;
            String name = mi.name;
            if (!name.startsWith("get") || name.length() <= 3) return null;
            String stem = name.substring(3);
            return stem.substring(0, 1).toLowerCase(Locale.ROOT) + stem.substring(1);
        }
        return null;
    }

    /**
     * Scans one {@code <init>} body for the unconditional-visibility-false pattern. The
     * bone field is identified by the GETFIELD instruction's name; only GETFIELDs whose
     * owner matches the declaring class (i.e. {@code this.<bone>}) are honoured. Conditional
     * or state-dependent PUTFIELDs (ICONST_0 not present, or preceded by a branch result)
     * are skipped.
     */
    private static void collectHiddenBones(@NotNull ClassNode owner, @NotNull MethodNode ctor, @NotNull LinkedHashSet<String> out) {
        for (AbstractInsnNode in = ctor.instructions.getFirst(); in != null; in = in.getNext()) {
            if (in.getOpcode() != Opcodes.PUTFIELD) continue;
            if (!(in instanceof FieldInsnNode put)) continue;
            if (!MODEL_PART.equals(put.owner)) continue;
            if (!"visible".equals(put.name)) continue;
            if (!MODEL_PART_VISIBLE_DESC.equals(put.desc)) continue;
            AbstractInsnNode valueInsn = AsmKit.previousReal(in);
            if (valueInsn == null || valueInsn.getOpcode() != Opcodes.ICONST_0) continue;
            AbstractInsnNode targetInsn = AsmKit.previousReal(valueInsn);
            if (targetInsn == null || targetInsn.getOpcode() != Opcodes.GETFIELD) continue;
            if (!(targetInsn instanceof FieldInsnNode get)) continue;
            if (!owner.name.equals(get.owner)) continue;
            out.add(get.name);
        }
    }
}
