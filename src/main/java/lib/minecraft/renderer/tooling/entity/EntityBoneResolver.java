package lib.minecraft.renderer.tooling.entity;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.tooling.util.AsmKit;
import lib.minecraft.renderer.tooling.util.ClassNodeCache;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Per-entity bone-related resolution. One resolver folds four adjacent bone-related signals
 * that all walk the renderer's constructor chain and the same model-class hierarchy:
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
 *   <li><b>{@link #resolveBoneToggles Bone-toggle resolution.}</b> Walks the model class for
 *       state-gated {@code visible} writes a render option flips - field-gated reveals
 *       (donkey/mule/llama chest) and inline-gated hides (goat horns) - naming which bones a
 *       toggle flips and from what default. Additive to the hidden-bone set, so the default
 *       render stays byte-identical.</li>
 *   <li><b>{@link #inferArmorType Armor-type classification.}</b> Classifies the overlay-layer
 *       list from {@link #scanOverlayLayers} into an armor mesh selector ({@code "humanoid"}
 *       when a {@code HumanoidArmorLayer} is present, else {@code "none"}).</li>
 * </ul>
 *
 * <p>The layer-scan output drives {@code addLayer}-style overlay enumeration during the
 * per-entity binding walk, and the hidden-bone output drives the {@code hidden_bones} array
 * in {@code entity_models.json} during emission. Both run per-entity but at different timings
 * - the layer scan runs as part of the per-entity {@link EntitySessionWalk}, while the
 * hidden-bone resolution waits until geometry parsing produces the model class for the
 * entity.
 */
@UtilityClass
public final class EntityBoneResolver {

    /**
     * Method name used by every {@code LivingEntityRenderer.addLayer} call.
     */
    private static final @NotNull String ADD_LAYER = "addLayer";

    /**
     * Descriptor of the boolean {@code ModelPart.visible} field ({@code Z}). Used by the
     * hidden-bone walkers to gate every {@code PUTFIELD}/{@code GETFIELD} on the canonical
     * write target, distinguishing it from any other {@code visible}-named field.
     */
    private static final @NotNull String MODEL_PART_VISIBLE_DESC = "Z";

    /**
     * Walks the renderer class's constructors and every superclass constructor for
     * {@code addLayer(new XLayer(...))} call sites. Returns the unique set of layer class
     * internal names in insertion order so downstream emission is stable.
     *
     * @param classNodes the ClassNode cache (shared with sibling resolver walks)
     * @param rendererInternalName the renderer's JVM internal name
     * @return the unique set of layer class internal names attached by this renderer; the
     *     source of {@link Entity}'s overlay-layer enumeration
     */
    public static @NotNull ConcurrentList<String> scanOverlayLayers(
        @NotNull ClassNodeCache classNodes,
        @NotNull String rendererInternalName
    ) {
        Set<String> seen = new LinkedHashSet<>();
        AsmKit.walkConstructorChain(classNodes, rendererInternalName, method -> scanAddLayerCalls(method, seen));
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
     * @param classNodes the ClassNode cache (shared with sibling resolver walks)
     * @param modelClassInternal the model class's JVM internal name (typically the
     *     {@code factory_class} field on the {@link EntityLayerDefinitionResolver} resolution)
     * @param rendererClassInternal the renderer class's JVM internal name; consulted for
     *     constructor {@code visible = true} re-enables that override an ancestor model's
     *     hide ({@code IllusionerRenderer} pattern)
     * @param diag the diagnostic sink for hidden-bone INFO traces
     * @return bone names that should be emitted under {@code hidden_bones} in the per-entity
     *     row, in insertion order; empty when no bones are hidden
     */
    public static @NotNull ConcurrentList<String> resolveHiddenBones(
        @NotNull ClassNodeCache classNodes,
        @NotNull String modelClassInternal,
        @NotNull String rendererClassInternal,
        @NotNull Diagnostics diag
    ) {
        LinkedHashSet<String> hiddenFields = new LinkedHashSet<>();
        LinkedHashMap<String, String> fieldToBoneName = new LinkedHashMap<>();
        String current = modelClassInternal;
        while (current != null && !current.equals(VanillaSourceClasses.ENTITY_MODEL) && !current.equals(AsmKit.OBJECT_INTERNAL)) {
            ClassNode cn = classNodes.load(current);
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

        LinkedHashSet<String> reEnabled = collectReEnabledBones(classNodes, rendererClassInternal);
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
     * Returns the entity's {@code bone_toggles} - toggle name -&gt; {@link BoneToggle} (the bones a
     * render option flips plus their default visibility). Two gate shapes feed it:
     *
     * <ul>
     *   <li><b>Field-gated</b> {@code this.<field>.visible = state.hasChest} (donkey/mule/llama chest).
     *       These bones are hidden by default (they also appear in {@code hidden_bones}), so the toggle
     *       REVEALS them - {@code defaultVisible = false}.</li>
     *   <li><b>Inline-gated</b> {@code root.getChild("<bone>").visible = state.has<X>} (goat horns).
     *       These are NOT added to {@code hidden_bones} - vanilla renders them by default (the goat
     *       reference shows horns), so the toggle HIDES them - {@code defaultVisible = true}. Left/right
     *       pairs group under a shared stem ({@code left_horn}+{@code right_horn} -&gt; {@code "horn"}).</li>
     * </ul>
     *
     * <p>Additive to {@link #resolveHiddenBones}: field-gated bones stay in {@code hidden_bones} and
     * inline-gated bones stay visible, so the default render is byte-identical; this map only names
     * which bones a toggle flips and from what default. The loader flips them at render.
     *
     * @param classNodes the ClassNode cache (shared with sibling resolver walks)
     * @param modelClassInternal the model class's JVM internal name
     * @param diag the diagnostic sink for bone-toggle INFO traces
     * @return toggle name -&gt; {@link BoneToggle}, insertion order; empty when no gated bones exist
     */
    public static @NotNull Map<String, BoneToggle> resolveBoneToggles(
        @NotNull ClassNodeCache classNodes,
        @NotNull String modelClassInternal,
        @NotNull Diagnostics diag
    ) {
        LinkedHashMap<String, LinkedHashSet<String>> fieldGated = new LinkedHashMap<>();
        LinkedHashSet<String> inlineBones = new LinkedHashSet<>();
        LinkedHashMap<String, String> fieldToBoneName = new LinkedHashMap<>();
        String current = modelClassInternal;
        while (current != null && !current.equals(VanillaSourceClasses.ENTITY_MODEL) && !current.equals(AsmKit.OBJECT_INTERNAL)) {
            ClassNode cn = classNodes.load(current);
            if (cn == null) break;
            MethodNode ctor = AsmKit.findMethod(cn, AsmKit.INIT);
            if (ctor != null) collectFieldToBoneNameMap(cn, ctor, fieldToBoneName);
            for (MethodNode method : cn.methods) {
                if (AsmKit.INIT.equals(method.name) || AsmKit.CLINIT.equals(method.name)) continue;
                collectStateGatedToggles(cn, method, fieldGated);
                collectInlineGatedToggles(cn, method, inlineBones);
            }
            current = cn.superName;
        }

        LinkedHashMap<String, BoneToggle> toggles = new LinkedHashMap<>();
        // Field-gated (chest): hidden by default - the toggle reveals.
        for (Map.Entry<String, LinkedHashSet<String>> entry : fieldGated.entrySet()) {
            List<String> bones = new ArrayList<>();
            for (String field : entry.getValue()) bones.add(fieldToBoneName.getOrDefault(field, field));
            toggles.put(flagToToggleName(entry.getKey()), new BoneToggle(bones, false));
        }
        // Inline-gated (goat horns): visible by default - the toggle hides. Group left/right pairs.
        LinkedHashMap<String, List<String>> inlineGroups = new LinkedHashMap<>();
        for (String bone : inlineBones)
            inlineGroups.computeIfAbsent(stripLeftRight(bone), key -> new ArrayList<>()).add(bone);
        for (Map.Entry<String, List<String>> entry : inlineGroups.entrySet())
            toggles.putIfAbsent(entry.getKey(), new BoneToggle(entry.getValue(), true));

        if (!toggles.isEmpty()) diag.info("bone-toggles: '%s' -> %s", modelClassInternal, toggles);
        return toggles;
    }

    /**
     * A named bone-visibility toggle: the geometry bones it flips and their default visibility. A
     * {@code defaultVisible = false} toggle (donkey chest) reveals its bones when active; a
     * {@code defaultVisible = true} toggle (goat horns) hides them.
     *
     * @param bones the geometry bone names the toggle flips
     * @param defaultVisible whether the bones render by default (true = toggle hides; false = toggle reveals)
     */
    public record BoneToggle(@NotNull List<String> bones, boolean defaultVisible) {}

    /**
     * State-equipment visibility pattern {@code bone.visible = state.hasChest}, recording each gating
     * {@code flag -> model-field} instead of merging into one set. The parallel of
     * {@link #collectStateGatedHidden} (same matched instruction shape and guards); kept separate so
     * {@code resolveHiddenBones} stays byte-identical while {@code resolveBoneToggles} can group the
     * gated bones by their flag.
     */
    private static void collectStateGatedToggles(@NotNull ClassNode owner, @NotNull MethodNode method, @NotNull Map<String, LinkedHashSet<String>> out) {
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
            if (stateLoad == null || stateLoad.getOpcode() != Opcodes.ALOAD) continue;
            if (!(stateLoad instanceof VarInsnNode varLoad)) continue;
            if (varLoad.var == 0) continue;
            AbstractInsnNode targetInsn = AsmKit.previousReal(stateLoad);
            if (targetInsn == null || targetInsn.getOpcode() != Opcodes.GETFIELD) continue;
            if (!(targetInsn instanceof FieldInsnNode get)) continue;
            if (!owner.name.equals(get.owner)) continue;
            out.computeIfAbsent(flagGet.name, key -> new LinkedHashSet<>()).add(get.name);
        }
    }

    /**
     * Inline-{@code getChild} visibility pattern {@code root.getChild("<bone>").visible = state.has<X>}
     * (goat horns), recording each gated bone name. The visibility target is a
     * {@code getChild(LDC)} result rather than a cached {@code this.<field>}, so this does not
     * double-match the field-gated {@link #collectStateGatedToggles} (whose target is a
     * {@code GETFIELD} on the model). Matched sequence, reading backwards from the write:
     *
     * <pre>{@code
     *   LDC       "<bone>"                        // the getChild argument = geometry bone name
     *   INVOKEVIRTUAL ModelPart.getChild          // the bone ModelPart being gated
     *   ALOAD     <n>                             // the state arg (var != 0)
     *   GETFIELD  <StateClass>.has<X> : Z         // the state flag (not owned by the model)
     *   PUTFIELD  ModelPart.visible : Z
     * }</pre>
     */
    private static void collectInlineGatedToggles(@NotNull ClassNode owner, @NotNull MethodNode method, @NotNull Set<String> out) {
        for (AbstractInsnNode in = method.instructions.getFirst(); in != null; in = in.getNext()) {
            if (in.getOpcode() != Opcodes.PUTFIELD) continue;
            if (!(in instanceof FieldInsnNode put)) continue;
            if (!VanillaSourceClasses.MODEL_PART.equals(put.owner)) continue;
            if (!"visible".equals(put.name) || !MODEL_PART_VISIBLE_DESC.equals(put.desc)) continue;
            AbstractInsnNode valueInsn = AsmKit.previousReal(in);
            if (valueInsn == null || valueInsn.getOpcode() != Opcodes.GETFIELD) continue;
            if (!(valueInsn instanceof FieldInsnNode flagGet)) continue;
            if (!"Z".equals(flagGet.desc) || owner.name.equals(flagGet.owner)) continue;
            if (!flagGet.name.startsWith("has") && !flagGet.name.startsWith("is")) continue;
            AbstractInsnNode stateLoad = AsmKit.previousReal(valueInsn);
            if (stateLoad == null || stateLoad.getOpcode() != Opcodes.ALOAD) continue;
            if (!(stateLoad instanceof VarInsnNode varLoad) || varLoad.var == 0) continue;
            AbstractInsnNode childCall = AsmKit.previousReal(stateLoad);
            if (childCall == null || childCall.getOpcode() != Opcodes.INVOKEVIRTUAL) continue;
            if (!(childCall instanceof MethodInsnNode mi) || !"getChild".equals(mi.name)) continue;
            if (mi.desc == null || !mi.desc.endsWith(")L" + VanillaSourceClasses.MODEL_PART + ";")) continue;
            String boneName = AsmKit.readStringLiteral(AsmKit.previousReal(childCall));
            if (boneName != null) out.add(boneName);
        }
    }

    /**
     * Strips a leading {@code left_}/{@code right_} from a bone name to get the shared toggle stem
     * ({@code left_horn} -&gt; {@code "horn"}), so a symmetric pair flips under one toggle. Names with
     * no such prefix pass through unchanged.
     */
    private static @NotNull String stripLeftRight(@NotNull String bone) {
        if (bone.startsWith("left_")) return bone.substring("left_".length());
        if (bone.startsWith("right_")) return bone.substring("right_".length());
        return bone;
    }

    /**
     * Derives a toggle name from a state boolean flag: strips the {@code has}/{@code is} prefix and
     * converts the remaining CamelCase to snake_case ({@code hasChest} -&gt; {@code "chest"},
     * {@code hasLeftHorn} -&gt; {@code "left_horn"}).
     */
    private static @NotNull String flagToToggleName(@NotNull String flag) {
        String stem = flag.startsWith("has") ? flag.substring(3)
            : flag.startsWith("is") ? flag.substring(2)
            : flag;
        StringBuilder snake = new StringBuilder(stem.length() + 4);
        for (int i = 0; i < stem.length(); i++) {
            char c = stem.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) snake.append('_');
                snake.append(Character.toLowerCase(c));
            } else {
                snake.append(c);
            }
        }
        return snake.toString();
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
     * Walks backwards from an {@code addLayer} call site looking for the {@code NEW XLayer}
     * that allocates the layer instance being added. Tracks a {@code pendingInits} balance so
     * nested constructions in the layer's own arguments ({@code new XLayer(new Model(...))})
     * don't return the wrong {@code NEW}: each {@code INVOKESPECIAL <init>} increments the
     * balance and each {@code NEW} decrements it, so the {@code NEW} that drives the balance
     * back to zero is the outermost allocation - the layer being added. Bounded at 64
     * instructions so a long tangle of nested constructor args can't run away into earlier
     * {@code addLayer} constructions.
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
     * Scans one {@code <init>} body for the unconditional {@code this.<bone>.visible = false}
     * pattern and records each cleared bone's model-class field name. The matched instruction
     * triple, reading backwards from the write, is:
     *
     * <pre>{@code
     *   GETFIELD  <owner>.<bone> : LModelPart;   // the bone field being hidden
     *   ICONST_0                                  // false
     *   PUTFIELD  ModelPart.visible : Z          // the visibility write
     * }</pre>
     *
     * <p>Gated to {@code PUTFIELD} on {@code ModelPart.visible:Z} whose value is a literal
     * {@code ICONST_0} and whose target is a {@code GETFIELD} of a field on {@code owner} -
     * so conditional or state-gated writes ({@code collectStateGatedHidden}) and writes to
     * bones owned by another class are excluded.
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
     * hidden. The matched instruction sequence, reading backwards from the write, is:
     *
     * <pre>{@code
     *   GETFIELD  <owner>.<bone>  : LModelPart;  // the bone field
     *   ALOAD     <n>                            // the state arg (var != 0, never `this`)
     *   GETFIELD  <StateClass>.hasChest : Z      // the equipment flag (not owned by owner)
     *   PUTFIELD  ModelPart.visible : Z
     * }</pre>
     *
     * <p>The flag's {@code GETFIELD} must NOT be owned by {@code owner} (it lives on the render
     * state class, not the model) and the state {@code ALOAD} must not be slot 0 ({@code this})
     * - both guards reject a model-owned {@code this.<flag>} boolean that would otherwise look
     * identical. Currently narrow to {@code hasChest} (covers {@code AbstractChestedHorse}
     * subclasses); generalising to other state booleans needs entity-class-default walks.
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
    private static @NotNull LinkedHashSet<String> collectReEnabledBones(@NotNull ClassNodeCache classNodes, @NotNull String rendererClassInternal) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        ClassNode cn = classNodes.load(rendererClassInternal);
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
