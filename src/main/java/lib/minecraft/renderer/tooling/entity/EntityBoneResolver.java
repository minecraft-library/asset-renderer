package lib.minecraft.renderer.tooling.entity;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.tooling.util.AsmKit;
import lib.minecraft.renderer.tooling.util.Diagnostics;
import lib.minecraft.renderer.tooling.util.VanillaSourceClasses;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;

/**
 * Per-entity bone-related resolution. One resolver folds two adjacent bone-related signals
 * that both walk the renderer's constructor chain and the same model-class hierarchy:
 *
 * <ul>
 *   <li><b>{@link #scanOverlayLayers Overlay-layer scan.}</b> Walks the renderer's
 *       constructor chain (including superclasses) for
 *       {@code addLayer(new XLayer(...))} call sites and records each {@code RenderLayer}
 *       subclass internal name. Output: the ordered set of layer classes the renderer
 *       composes onto its base mesh ({@code HumanoidArmorLayer}, {@code EyesLayer}
 *       subclasses, per-mob overlays like {@code SheepWoolLayer}).</li>
 *   <li><b>{@link #resolveHiddenBones Hidden-bone resolution.}</b> Walks the model class's
 *       constructor (and ancestor model constructors up to {@code EntityModel}) for
 *       unconditional {@code this.<bone>.visible = false} writes, plus state-equipment
 *       gated writes ({@code bone.visible = state.hasChest}) whose zero-state default is
 *       false. Then walks the renderer's own constructor for {@code visible = true}
 *       re-enables (the {@code IllusionerRenderer} pattern that overrides
 *       {@code IllagerModel}'s hat hide) and subtracts those from the hidden set.</li>
 * </ul>
 *
 * <p>The layer-scan output drives {@code addLayer}-style overlay enumeration (Phase B) and
 * the hidden-bone output drives the {@code hidden_bones} array in {@code entity_models.json}
 * (Phase D). Both run per-entity but at different timings - the layer scan runs as part of
 * the per-entity {@link EntitySessionWalk}, while the hidden-bone resolution waits until
 * after Phase C (geometry parse) produces the model class for the entity.
 */
@UtilityClass
public final class EntityBoneResolver {

    /**
     * Method name used by every {@code LivingEntityRenderer.addLayer} call.
     */
    private static final @NotNull String ADD_LAYER = "addLayer";

    /**
     * {@code ModelPart.visible} field descriptor. Used by the hidden-bone walker to gate on
     * the canonical write target.
     */
    private static final @NotNull String MODEL_PART_VISIBLE_DESC = "Z";

    /**
     * Walks the renderer class's constructors and every superclass constructor for
     * {@code addLayer(new XLayer(...))} call sites. Returns the unique set of layer class
     * internal names in insertion order so downstream emission is stable.
     *
     * @param context the tooling context (jar + diagnostics + ClassNode cache)
     * @param rendererInternalName the renderer's JVM internal name
     * @return the unique set of layer class internal names attached by this renderer; the
     *     source of {@link Entity}'s overlay-layer enumeration
     */
    public static @NotNull ConcurrentList<String> scanOverlayLayers(
        @NotNull ToolingEntityContext context,
        @NotNull String rendererInternalName
    ) {
        Set<String> seen = new LinkedHashSet<>();
        AsmKit.walkConstructorChain(context.zip(), rendererInternalName, method -> scanAddLayerCalls(method, seen));
        ConcurrentList<String> out = Concurrent.newList();
        seen.forEach(out::add);
        return out;
    }

    /**
     * Returns the set of bone names whose {@code visible} flag is unconditionally cleared by
     * the model class's constructor (or any ancestor model class's constructor up to
     * {@code EntityModel}), minus any bones the renderer's own constructor re-enables via
     * a {@code visible = true} write. Returns an empty list when the resulting set is empty.
     *
     * @param context the tooling context (jar + diagnostics + ClassNode cache)
     * @param modelClassInternal the model class's JVM internal name (typically the
     *     {@code factory_class} field on the Phase-C {@code EntityLayerDefinitionResolver}
     *     resolution)
     * @param rendererClassInternal the renderer class's JVM internal name; consulted for
     *     constructor {@code visible = true} re-enables that override an ancestor model's
     *     hide ({@code IllusionerRenderer} pattern)
     * @return bone names that should be emitted under {@code hidden_bones} in the per-entity
     *     row, in insertion order; empty when no bones are hidden
     */
    public static @NotNull ConcurrentList<String> resolveHiddenBones(
        @NotNull ToolingEntityContext context,
        @NotNull String modelClassInternal,
        @NotNull String rendererClassInternal
    ) {
        ZipFile zip = context.zip();
        Diagnostics diag = context.diagnostics();
        LinkedHashSet<String> hiddenFields = new LinkedHashSet<>();
        LinkedHashMap<String, String> fieldToBoneName = new LinkedHashMap<>();
        String current = modelClassInternal;
        while (current != null && !current.equals(VanillaSourceClasses.ENTITY_MODEL) && !current.equals(AsmKit.OBJECT_INTERNAL)) {
            ClassNode cn = AsmKit.loadClass(context.classNodes(), zip, current);
            if (cn == null) break;
            MethodNode ctor = AsmKit.findMethod(cn, AsmKit.INIT);
            if (ctor != null) {
                collectUnconditionalHidden(cn, ctor, hiddenFields);
                collectFieldToBoneNameMap(cn, ctor, fieldToBoneName);
            }
            // State-equipment visibility - LlamaModel.setupAnim writes
            // `bone.visible = state.<flag>` where the flag's zero-state is false. Walk every
            // method (not just setupAnim by name, since vanilla also uses prepareMobModel and
            // other override hooks) for the pattern.
            for (MethodNode method : cn.methods) {
                if (AsmKit.INIT.equals(method.name) || AsmKit.CLINIT.equals(method.name)) continue;
                collectStateGatedHidden(cn, method, hiddenFields);
            }
            current = cn.superName;
        }
        if (hiddenFields.isEmpty()) return Concurrent.newList();

        LinkedHashSet<String> reEnabled = collectReEnabledBones(context, rendererClassInternal);
        if (!reEnabled.isEmpty()) hiddenFields.removeAll(reEnabled);

        // Translate model-class field names ({@code rightChest}) to the corresponding bone
        // names emitted by the geometry JSON ({@code right_chest}) via the ctor's
        // {@code getChild("<bone>"); PUTFIELD <field>} map. Fields with no map entry pass
        // through unchanged - matches legacy behavior for models whose field name already
        // equals the bone name (armor_stand hat, illager hat).
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
     * Heuristic armor-type classification - the runtime renderer uses this to pick which
     * armor mesh to layer over the entity. Returns {@code "humanoid"} when the renderer's
     * overlay layer list contains {@code HumanoidArmorLayer}; otherwise {@code "none"}.
     * Co-located here because the layer list it consumes is produced by
     * {@link #scanOverlayLayers}.
     *
     * @param layers the layer class internal names from {@link #scanOverlayLayers}
     * @return {@code "humanoid"} or {@code "none"}
     */
    public static @NotNull String inferArmorType(@NotNull ConcurrentList<String> layers) {
        for (String layer : layers)
            if (layer.endsWith("HumanoidArmorLayer")) return "humanoid";
        return "none";
    }

    /**
     * Scans one constructor for {@code INVOKEVIRTUAL addLayer} calls. For each, walks
     * backwards to find the most recent {@code NEW XLayer} TypeInsnNode and records its
     * {@code desc}.
     */
    private static void scanAddLayerCalls(@NotNull MethodNode method, @NotNull Set<String> out) {
        for (AbstractInsnNode in = method.instructions.getFirst(); in != null; in = in.getNext()) {
            // Owner-agnostic addLayer match - the renderer's super may be any of several
            // LivingEntityRenderer subclasses, so AsmKit's owner-qualified isInvokeVirtual is
            // too narrow. Inline the predicate and gate on the canonical descriptor shape
            // (single Layer arg, boolean return).
            if (in.getOpcode() != Opcodes.INVOKEVIRTUAL) continue;
            if (!(in instanceof MethodInsnNode mi)) continue;
            if (!ADD_LAYER.equals(mi.name)) continue;
            if (!mi.desc.startsWith("(L") || !mi.desc.endsWith(";)Z")) continue;

            String layerClass = findPrecedingLayerNew(in);
            if (layerClass != null) out.add(layerClass);
        }
    }

    /**
     * Walks backwards from an {@code addLayer} call site looking for the most recent
     * {@code NEW XLayer}. Bounded at 64 instructions so a long tangle of nested constructor
     * args doesn't infinite-loop into earlier addLayer constructions.
     */
    private static @Nullable String findPrecedingLayerNew(@NotNull AbstractInsnNode addLayerInsn) {
        AbstractInsnNode cursor = addLayerInsn.getPrevious();
        int depth = 0;
        int pendingInits = 0;
        while (cursor != null && depth < 64) {
            depth++;
            if (cursor.getOpcode() == Opcodes.INVOKESPECIAL
                && cursor instanceof MethodInsnNode mi
                && AsmKit.INIT.equals(mi.name))
                pendingInits++;
            if (cursor.getOpcode() == Opcodes.NEW && cursor instanceof TypeInsnNode type) {
                pendingInits--;
                if (pendingInits == 0) return type.desc;
            }
            cursor = cursor.getPrevious();
        }
        return null;
    }

    /**
     * Scans one {@code <init>} body for the unconditional-visibility-false pattern.
     */
    private static void collectUnconditionalHidden(@NotNull ClassNode owner, @NotNull MethodNode ctor, @NotNull LinkedHashSet<String> out) {
        for (AbstractInsnNode in = ctor.instructions.getFirst(); in != null; in = in.getNext()) {
            if (in.getOpcode() != Opcodes.PUTFIELD) continue;
            if (!(in instanceof FieldInsnNode put)) continue;
            if (!VanillaSourceClasses.MODEL_PART.equals(put.owner)) continue;
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

    /**
     * Walks a model class constructor for the {@code LDC "<boneName>"; INVOKEVIRTUAL
     * ModelPart.getChild; PUTFIELD <fieldName>:LModelPart} chain and records each (field,
     * boneName) pair. Vanilla models build their bone-field cache this way - the field name
     * is camelCase ({@code rightChest}) while the bone name is the snake_case geometry id
     * ({@code right_chest}) the engine looks up by string.
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
                && VanillaSourceClasses.MODEL_PART.equals(mi.owner)
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
     * State-equipment visibility pattern: {@code bone.visible = state.hasChest}. Bones gated
     * to a state-class boolean whose zero-state default is false render only when equipment
     * is present; at the vanilla harness's zero state the flag is false, so the bone is
     * hidden. Currently narrow to {@code hasChest} (covers AbstractChestedHorse subclasses);
     * generalising to other state booleans needs entity-class-default walks.
     */
    private static void collectStateGatedHidden(@NotNull ClassNode owner, @NotNull MethodNode method, @NotNull LinkedHashSet<String> out) {
        for (AbstractInsnNode in = method.instructions.getFirst(); in != null; in = in.getNext()) {
            if (in.getOpcode() != Opcodes.PUTFIELD) continue;
            if (!(in instanceof FieldInsnNode put)) continue;
            if (!VanillaSourceClasses.MODEL_PART.equals(put.owner)) continue;
            if (!"visible".equals(put.name)) continue;
            if (!MODEL_PART_VISIBLE_DESC.equals(put.desc)) continue;
            AbstractInsnNode valueInsn = AsmKit.previousReal(in);
            if (valueInsn == null || valueInsn.getOpcode() != Opcodes.GETFIELD) continue;
            if (!(valueInsn instanceof FieldInsnNode flagGet)) continue;
            if (!"Z".equals(flagGet.desc)) continue;
            if (!"hasChest".equals(flagGet.name)) continue;
            if (owner.name.equals(flagGet.owner)) continue;
            AbstractInsnNode stateLoad = AsmKit.previousReal(valueInsn);
            if (stateLoad == null) continue;
            if (stateLoad.getOpcode() != Opcodes.ALOAD) continue;
            if (!(stateLoad instanceof VarInsnNode varLoad)) continue;
            if (varLoad.var == 0) continue;
            AbstractInsnNode targetInsn = AsmKit.previousReal(stateLoad);
            if (targetInsn == null || targetInsn.getOpcode() != Opcodes.GETFIELD) continue;
            if (!(targetInsn instanceof FieldInsnNode get)) continue;
            if (!owner.name.equals(get.owner)) continue;
            out.add(get.name);
        }
    }

    /**
     * Walks the renderer class's {@code <init>} for {@code <something>.visible = true}
     * assignments and returns the inferred bone names. Two shapes are honoured: a direct
     * {@code GETFIELD <bone>:LModelPart} access and an
     * {@code INVOKEVIRTUAL get<Bone>():LModelPart} accessor. The bone name comes from either
     * the GETFIELD's name or the method's "get" suffix (lowercased first character).
     */
    private static @NotNull LinkedHashSet<String> collectReEnabledBones(@NotNull ToolingEntityContext context, @NotNull String rendererClassInternal) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        ClassNode cn = AsmKit.loadClass(context.classNodes(), context.zip(), rendererClassInternal);
        if (cn == null) return out;
        MethodNode ctor = AsmKit.findMethod(cn, AsmKit.INIT);
        if (ctor == null) return out;
        for (AbstractInsnNode in = ctor.instructions.getFirst(); in != null; in = in.getNext()) {
            if (in.getOpcode() != Opcodes.PUTFIELD) continue;
            if (!(in instanceof FieldInsnNode put)) continue;
            if (!VanillaSourceClasses.MODEL_PART.equals(put.owner)) continue;
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
     * Pulls the bone field name from the instruction immediately preceding the ICONST_1 +
     * PUTFIELD ModelPart.visible:Z pair.
     */
    private static @Nullable String extractBoneName(@NotNull AbstractInsnNode node) {
        if (node.getOpcode() == Opcodes.GETFIELD && node instanceof FieldInsnNode get) {
            if (get.desc == null || !get.desc.equals("L" + VanillaSourceClasses.MODEL_PART + ";")) return null;
            return get.name;
        }
        if (node.getOpcode() == Opcodes.INVOKEVIRTUAL && node instanceof MethodInsnNode mi) {
            if (mi.desc == null || !mi.desc.endsWith(")L" + VanillaSourceClasses.MODEL_PART + ";")) return null;
            String name = mi.name;
            if (!name.startsWith("get") || name.length() <= 3) return null;
            String stem = name.substring(3);
            return stem.substring(0, 1).toLowerCase(Locale.ROOT) + stem.substring(1);
        }
        return null;
    }

}
