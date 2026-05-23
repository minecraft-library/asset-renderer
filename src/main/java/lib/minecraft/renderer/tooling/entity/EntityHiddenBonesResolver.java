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

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
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
 * pipeline must hide them too - emitted into {@code entity_models.json} as
 * {@code hidden_bones} for armor_stand, pillager, vindicator, evoker (all hide "hat";
 * ArmorStandModel and IllagerModel set their hat invisible in the constructor).
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
        LinkedHashSet<String> hiddenFields = new LinkedHashSet<>();
        LinkedHashMap<String, String> fieldToBoneName = new LinkedHashMap<>();
        String current = modelClassInternal;
        while (current != null && !current.equals(ENTITY_MODEL) && !current.equals(AsmKit.OBJECT_INTERNAL)) {
            ClassNode cn = AsmKit.loadClass(zip, current);
            if (cn == null) break;
            MethodNode ctor = AsmKit.findMethod(cn, AsmKit.INIT);
            if (ctor != null) {
                collectHiddenBones(cn, ctor, hiddenFields);
                collectFieldToBoneNameMap(cn, ctor, fieldToBoneName);
            }
            // State-equipment visibility - LlamaModel.setupAnim writes
            // `bone.visible = state.<flag>` where the flag's zero-state is false. Walk every
            // method (not just setupAnim by name, since vanilla also uses prepareMobModel and
            // other override hooks) for the pattern.
            for (MethodNode method : cn.methods) {
                if (AsmKit.INIT.equals(method.name) || AsmKit.CLINIT.equals(method.name)) continue;
                collectStateGatedHiddenBones(cn, method, hiddenFields);
            }
            current = cn.superName;
        }
        if (hiddenFields.isEmpty()) return Concurrent.newList();

        LinkedHashSet<String> reEnabled = collectReEnabledBones(zip, rendererClassInternal);
        if (!reEnabled.isEmpty()) hiddenFields.removeAll(reEnabled);

        // Translate model-class field names ({@code rightChest}) to the corresponding bone names
        // emitted by the geometry JSON ({@code right_chest}) via the ctor's
        // {@code getChild("<bone>"); PUTFIELD <field>} map. Fields with no map entry pass
        // through unchanged - this matches the legacy behavior for models whose field name
        // already equals the bone name (armor_stand hat, illager hat).
        LinkedHashSet<String> hidden = new LinkedHashSet<>();
        for (String field : hiddenFields)
            hidden.add(fieldToBoneName.getOrDefault(field, field));

        if (!reEnabled.isEmpty())
            diag.info("hidden-bones: '%s' -> %s (renderer '%s' re-enables %s)",
                modelClassInternal, hidden, rendererClassInternal, reEnabled);
        else
            diag.info("hidden-bones: '%s' -> %s", modelClassInternal, hidden);

        if (hidden.isEmpty()) return Concurrent.newList();
        ConcurrentList<String> out = Concurrent.newList();
        out.addAll(hidden);
        return out;
    }

    /**
     * Walks a model class constructor for the {@code LDC "<boneName>"; INVOKEVIRTUAL
     * ModelPart.getChild; PUTFIELD <fieldName>:LModelPart} chain and records each (field,
     * boneName) pair. Vanilla models build their bone-field cache this way - the field name is
     * camelCase ({@code rightChest}) while the bone name is the snake_case geometry id
     * ({@code right_chest}) the engine looks up by string. Without this translation the
     * resolver emits the camelCase form to the JSON, and the runtime loader can't find the
     * matching bone.
     */
    private static void collectFieldToBoneNameMap(@NotNull ClassNode owner, @NotNull MethodNode ctor, @NotNull Map<String, String> out) {
        String pendingBoneName = null;
        boolean pendingChildCall = false;
        for (AbstractInsnNode in = ctor.instructions.getFirst(); in != null; in = in.getNext()) {
            String literal = AsmKit.readStringLiteral(in);
            if (literal != null) {
                pendingBoneName = literal;
                pendingChildCall = false;
                continue;
            }
            if (in.getOpcode() == Opcodes.INVOKEVIRTUAL
                && in instanceof MethodInsnNode mi
                && MODEL_PART.equals(mi.owner)
                && "getChild".equals(mi.name)
                && pendingBoneName != null) {
                pendingChildCall = true;
                continue;
            }
            if (in.getOpcode() == Opcodes.PUTFIELD
                && in instanceof FieldInsnNode put
                && owner.name.equals(put.owner)
                && pendingChildCall
                && pendingBoneName != null) {
                out.putIfAbsent(put.name, pendingBoneName);
                pendingBoneName = null;
                pendingChildCall = false;
            }
        }
    }

    /**
     * Walks a model method (non-init) for the state-equipment visibility pattern:
     * <pre>
     *   ALOAD_0
     *   GETFIELD &lt;model&gt;.&lt;bone&gt; : LModelPart
     *   ALOAD &lt;state-arg&gt;
     *   GETFIELD &lt;state&gt;.hasChest : Z
     *   PUTFIELD ModelPart.visible : Z
     * </pre>
     * Bones gated to a state-class {@code hasChest} flag render only when the entity has a
     * chest equipped. At the vanilla harness's zero state no equipment is present so the flag
     * is {@code false} and the bone is hidden - emitting it as a {@code hidden_bones} entry
     * mirrors the harness output exactly.
     *
     * <p>The walker is intentionally narrow: only the {@code hasChest} field name fires.
     * Generalising to "any boolean state flag whose zero-state default is false" requires
     * walking the renderer's {@code extractRenderState} + the entity's getter (often a
     * {@code SynchedEntityData} accessor with a default that's not visible in the getter
     * bytecode) - more code than the 1-field-name case justifies. The other vanilla
     * visibility-gating fields ({@code showArms} on ArmorStandModel, {@code hasStinger} on
     * BeeModel, etc.) need entity-class-default walks to classify safely; this narrow filter
     * covers the chest case (AbstractChestedHorse subclasses: horse, donkey, mule, llama,
     * trader_llama) without producing false-positive hides for non-chest gates.
     */
    private static void collectStateGatedHiddenBones(@NotNull ClassNode owner, @NotNull MethodNode method, @NotNull LinkedHashSet<String> out) {
        for (AbstractInsnNode in = method.instructions.getFirst(); in != null; in = in.getNext()) {
            if (in.getOpcode() != Opcodes.PUTFIELD) continue;
            if (!(in instanceof FieldInsnNode put)) continue;
            if (!MODEL_PART.equals(put.owner)) continue;
            if (!"visible".equals(put.name)) continue;
            if (!MODEL_PART_VISIBLE_DESC.equals(put.desc)) continue;
            // Value-path: GETFIELD <state>.hasChest:Z (any other flag falls through).
            AbstractInsnNode valueInsn = AsmKit.previousReal(in);
            if (valueInsn == null || valueInsn.getOpcode() != Opcodes.GETFIELD) continue;
            if (!(valueInsn instanceof FieldInsnNode flagGet)) continue;
            if (!"Z".equals(flagGet.desc)) continue;
            if (!"hasChest".equals(flagGet.name)) continue;
            if (owner.name.equals(flagGet.owner)) continue;
            AbstractInsnNode stateLoad = AsmKit.previousReal(valueInsn);
            if (stateLoad == null) continue;
            if (stateLoad.getOpcode() != Opcodes.ALOAD) continue;
            if (!(stateLoad instanceof org.objectweb.asm.tree.VarInsnNode varLoad)) continue;
            if (varLoad.var == 0) continue;
            // Target-path: GETFIELD <model>.<bone>:LModelPart, with the GETFIELD's owner the
            // model class itself (this.<bone>).
            AbstractInsnNode targetInsn = AsmKit.previousReal(stateLoad);
            if (targetInsn == null || targetInsn.getOpcode() != Opcodes.GETFIELD) continue;
            if (!(targetInsn instanceof FieldInsnNode get)) continue;
            if (!owner.name.equals(get.owner)) continue;
            out.add(get.name);
        }
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
