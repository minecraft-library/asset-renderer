package lib.minecraft.renderer.tooling.entity;

import dev.simplified.gson.JsonTree;
import dev.simplified.util.StringUtil;
import lib.minecraft.renderer.tooling.geometry.GeometryParser;
import lib.minecraft.renderer.tooling.geometry.GeometryRequest;
import lib.minecraft.renderer.tooling.kernel.ClassKit;
import lib.minecraft.renderer.tooling.kernel.ClassNodeCache;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lib.minecraft.renderer.tooling.kernel.VanillaSourceClasses;
import lib.minecraft.renderer.tooling.walk.AsmWalker;
import lib.minecraft.renderer.tooling.walk.BooleanStores;
import lib.minecraft.renderer.tooling.walk.Insn;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Node {@code bones} - bone-visibility deltas from a single model-hierarchy walk:
 * {@code hidden} (the unconditional ctor {@code visible = false} writes nothing ever re-enables,
 * minus the renderer's own re-enables - the Illusioner pattern) and {@code toggles} (state-gated
 * reveals / hides: chest, horns, mushrooms, base plate, reins).
 *
 * <p>Resolved for a body mesh and again for each equipment overlay, because a layer poses its own
 * mesh with its own model class and gates its own bones - a saddle's reins draw only while
 * something is riding. The walk is one and the same; what differs is the class it starts at and the
 * mesh the toggles are filtered against, which is why both arrive as arguments.
 *
 * <p>Which state-gated bones are hidden by default is asked of
 * {@link EntitySpawnFlagResolver} rather than assumed, because a flag packed into a synched byte
 * is free to store the negative and so read {@code true} on a spawned entity.
 *
 * <p>The {@code hasChest} literal gate generalises to any render-state boolean with a
 * zero-state-false default; gate detection relies on the {@code :Z} descriptor to type the
 * flag rather than a {@code has} / {@code is} name-prefix test (the prefixes survive only in
 * toggle NAMING, where {@code hasChest} strips to {@code chest}). Toggle subtrees are
 * expanded against the parsed geometry of the mesh the toggles belong to, not re-read
 * emitted JSON.
 */
final class EntityBoneResolver {

    private final @NotNull ClassNodeCache cache;
    private final @NotNull EntitySubject subject;
    private final @Nullable EntityGeometryRefResolver geometryRef;
    private final @NotNull Diagnostics diagnostics;
    private final @NotNull EntitySpawnFlagResolver spawnFlags;

    EntityBoneResolver(@NotNull EntityContext context) {
        this(context, null);
    }

    EntityBoneResolver(@NotNull EntityContext context, @Nullable EntityGeometryRefResolver geometryRef) {
        this.cache = context.cache();
        this.subject = context.subject();
        this.geometryRef = geometryRef;
        this.diagnostics = context.diagnostics();
        this.spawnFlags = new EntitySpawnFlagResolver(context);
    }

    /**
     * The body mesh's {@code bones} node ({@code hidden} / {@code toggles}), or {@code null} when
     * the model declares neither (or no model resolved).
     *
     * @return the node, or {@code null} to omit
     */
    @Nullable JsonTree resolve() {
        if (this.geometryRef == null) return null;
        var entry = this.geometryRef.resolvedEntry();
        if (entry == null) return null;
        return resolve(entry.factoryClass(), this.geometryRef.registeredRequest());
    }

    /**
     * The {@code bones} node one model class declares over one mesh, or {@code null} when it
     * declares neither half.
     *
     * @param modelClass the model class whose hierarchy carries the visibility writes
     * @param request the mesh the toggles are expanded and filtered against, or {@code null} to
     *     leave them as the walk named them
     * @return the node, or {@code null} to omit
     */
    @Nullable JsonTree resolve(@NotNull String modelClass, @Nullable GeometryRequest request) {
        HierarchyScan scan = scanModelHierarchy(modelClass);

        // hidden = unconditional only, minus the renderer's own ctor re-enables; model fields
        // translate to geometry bone names via the ctor getChild map.
        //
        // A state-gated bone is NOT hidden here even where its zero state draws nothing. Which way
        // a gate points is something the model's own pose says - the expression it writes the bone's
        // visibility with, read at the figures a render state is built holding - and saying it twice
        // is how the two came to disagree about whether a bee rests with its sting. What is left is
        // the bones nothing ever draws, which no pose speaks for at all.
        LinkedHashSet<String> hiddenFields = new LinkedHashSet<>(scan.unconditionalHidden());
        LinkedHashSet<String> reEnabled = collectReEnabledBones();
        hiddenFields.removeAll(reEnabled);
        LinkedHashSet<String> hidden = new LinkedHashSet<>();
        for (String field : hiddenFields) hidden.add(boneName(scan, field));

        // toggles: field-gated reveal (chest - hidden by default), array-element gate (the equine
        // saddle's reins - one write covering every element of a ModelPart[]), inline-gated hide
        // (goat horns - left/right pairs group under a shared stem), negated-branch gate (bogged
        // mushrooms - default follows branch polarity).
        Map<String, Toggle> toggles = new LinkedHashMap<>();
        for (Map.Entry<String, LinkedHashSet<String>> gate : scan.stateGatedByFlag().entrySet()) {
            List<String> bones = new ArrayList<>();
            for (String field : gate.getValue()) bones.add(boneName(scan, field));
            toggles.put(flagToToggleName(gate.getKey()), new Toggle(bones, visibleAtZeroState(gate.getKey())));
        }
        for (Map.Entry<String, LinkedHashSet<String>> gate : arrayGatedBones(modelClass, scan).entrySet())
            toggles.putIfAbsent(flagToToggleName(gate.getKey()),
                new Toggle(new ArrayList<>(gate.getValue()), visibleAtZeroState(gate.getKey())));
        Map<String, List<String>> inlineGroups = new LinkedHashMap<>();
        for (String bone : scan.inlineGatedBones())
            inlineGroups.computeIfAbsent(stripLeftRight(bone), key -> new ArrayList<>()).add(bone);
        for (Map.Entry<String, List<String>> group : inlineGroups.entrySet())
            toggles.putIfAbsent(group.getKey(), new Toggle(group.getValue(), true));
        for (Map.Entry<String, NegatedGate> gate : scan.negatedGatedByFlag().entrySet()) {
            List<String> bones = new ArrayList<>();
            for (String field : gate.getValue().fields()) bones.add(boneName(scan, field));
            toggles.putIfAbsent(flagToToggleName(gate.getKey()), new Toggle(bones, gate.getValue().defaultVisible()));
        }

        if (!toggles.isEmpty()) expandToggleSubtrees(toggles, request);
        if (hidden.isEmpty() && toggles.isEmpty()) return null;

        JsonTree node = JsonTree.object();
        // Which way each of these points is read off the pose, and the pose is keyed by model class.
        // A mesh coordinate already names one, so this says nothing where the two agree - and they
        // disagree for a saddle, whose mesh factory vanilla declares on the wearer's own model class
        // while handing the layer a different class to pose it with.
        String posedBy = ClassKit.simpleName(modelClass);
        if (request != null && !posedBy.equals(ClassKit.simpleName(request.factoryClass())))
            node.put("pose", posedBy);
        if (!hidden.isEmpty()) node.putStrings("hidden", hidden.toArray(String[]::new));
        if (!toggles.isEmpty()) {
            JsonTree togglesNode = node.child("toggles");
            // Which bones, and nothing about which way. A toggle names the state its subject is not
            // resting in, and what it rests in is read off the pose rather than declared here.
            for (Map.Entry<String, Toggle> toggle : toggles.entrySet())
                togglesNode.put(toggle.getKey(), JsonTree.object()
                    .putStrings("bones", toggle.getValue().bones().toArray(String[]::new)));
        }
        this.diagnostics.info("bones: hidden=%s toggles=%s", hidden, toggles.keySet());
        return node;
    }

    /**
     * A named visibility toggle: the bones it flips and their default visibility
     * ({@code false} = the toggle reveals, chest; {@code true} = the toggle hides, horns).
     *
     * @param bones the geometry bone names the toggle flips
     * @param defaultVisible whether the bones render by default
     */
    private record Toggle(@NotNull List<String> bones, boolean defaultVisible) {}

    /**
     * A negated-branch gate's fields plus the default visibility read off the branch
     * polarity.
     *
     * @param fields the model bone fields the gate writes
     * @param defaultVisible the value written at the flag's zero state
     */
    private record NegatedGate(@NotNull LinkedHashSet<String> fields, boolean defaultVisible) {}

    /**
     * Everything ONE walk up the model hierarchy yields: the ctor-built field-to-bone map,
     * the unconditional hides, and the four gate shapes.
     *
     * @param fieldToBone model field name to geometry bone name ({@code rightChest} to {@code right_chest})
     * @param unconditionalHidden fields cleared unconditionally in a ctor
     * @param stateGatedByFlag positive state-gated fields, grouped by flag
     * @param arrayGatedByFlag positive state-gated {@code ModelPart[]} fields, grouped by flag
     * @param inlineGatedBones {@code getChild("<bone>")}-targeted gated bone names
     * @param negatedGatedByFlag negated-branch-gated fields, grouped by flag
     */
    private record HierarchyScan(
        @NotNull Map<String, String> fieldToBone,
        @NotNull LinkedHashSet<String> unconditionalHidden,
        @NotNull Map<String, LinkedHashSet<String>> stateGatedByFlag,
        @NotNull Map<String, LinkedHashSet<String>> arrayGatedByFlag,
        @NotNull LinkedHashSet<String> inlineGatedBones,
        @NotNull Map<String, NegatedGate> negatedGatedByFlag
    ) {}

    // ------------------------------------------------------------------------------------
    // THE hierarchy walk
    // ------------------------------------------------------------------------------------

    /**
     * Walks the model class chain once, up to (excluding) {@code EntityModel} /
     * {@code Object}: every ctor feeds the unconditional hides + the field-to-bone map;
     * every other method feeds the gate collectors.
     *
     * <p>Every ctor, not the first: a model that offers both a {@code (root)} and a
     * {@code (root, Function)} form builds its parts in the wider one and delegates from the
     * narrower, so reading only the first declared leaves the whole class's fields unmapped -
     * which is how {@code HumanoidModel}'s inherited arms and legs used to reach the emitted
     * JSON spelled as Java fields.
     */
    private @NotNull HierarchyScan scanModelHierarchy(@NotNull String modelClass) {
        HierarchyScan scan = new HierarchyScan(new LinkedHashMap<>(), new LinkedHashSet<>(),
            new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashSet<>(), new LinkedHashMap<>());
        String current = modelClass;
        while (current != null && !current.equals(VanillaSourceClasses.Types.ENTITY_MODEL) && !current.equals(ClassKit.OBJECT_INTERNAL)) {
            ClassNode cn = this.cache.load(current);
            if (cn == null) break;
            // State-gated visibility can live in setupAnim, prepareMobModel, or any other
            // override hook - walk every non-init method for the gate shapes.
            for (MethodNode method : cn.methods) {
                if (ClassKit.CLINIT.equals(method.name)) continue;
                if (ClassKit.INIT.equals(method.name)) {
                    collectUnconditionalHidden(cn, method, scan.unconditionalHidden());
                    EntityBoneNames.collectFieldToBoneNameMap(cn, method, scan.fieldToBone());
                    continue;
                }
                collectGates(cn, method, scan);
            }
            current = cn.superName;
        }
        return scan;
    }

    /**
     * A decoded {@code ModelPart.visible = <boolean>} write: the store-target instruction
     * plus the decoded boolean r-value. The vanilla semantic guards (which owner is the
     * model, which target shape) stay in the collectors.
     *
     * @param targetInsn the instruction pushing the {@code ModelPart} being written
     * @param value the decoded boolean r-value
     */
    private record VisibleWrite(@NotNull AbstractInsnNode targetInsn, @NotNull BooleanStores.BooleanStore value) {}

    /**
     * Matches a {@code PUTFIELD ModelPart.visible:Z} at {@code in} and decodes its r-value +
     * store target, or {@code null} when {@code in} is not the canonical write.
     */
    private static @Nullable VisibleWrite matchVisibleWrite(@NotNull AbstractInsnNode in) {
        if (!AsmWalker.isPutField(in, VanillaSourceClasses.Types.MODEL_PART, "visible")) return null;
        if (!(in instanceof FieldInsnNode put) || !"Z".equals(put.desc)) return null;
        AbstractInsnNode valueInsn = AsmWalker.previousReal(in);
        if (valueInsn == null) return null;
        BooleanStores.BooleanStore value = BooleanStores.decodeBooleanStore(valueInsn);
        if (value == null) return null;
        AbstractInsnNode target = AsmWalker.previousReal(value.valueStart());
        if (target == null) return null;
        return new VisibleWrite(target, value);
    }

    /**
     * Collects the gate shapes from one method into the scan: a positive
     * {@code this.<bone>.visible = state.<flag>} groups under its flag; the same write through an
     * element of a {@code ModelPart[]} field groups the whole array under its flag; a positive
     * gate on a {@code getChild(LDC)} target records the bone name (goat horns); a
     * negated-branch gate ({@code visible = !state.<flag>}, bogged) groups with the
     * branch-polarity default. The flag must be a non-model-owned {@code :Z} field read off
     * a non-{@code this} load - detected by descriptor, not by name prefix.
     */
    private static void collectGates(@NotNull ClassNode owner, @NotNull MethodNode method, @NotNull HierarchyScan scan) {
        Map<Integer, String> arrayElements = partArrayElementLocals(owner, method);
        AsmWalker.over(method)
            .mapNotNull(EntityBoneResolver::matchVisibleWrite)
            .forEach(write -> {
                if (!(write.value() instanceof BooleanStores.FieldStore flag)) return;
                FieldInsnNode flagGet = flag.field();
                if (owner.name.equals(flagGet.owner)) return;
                if (!(flag.receiver() instanceof VarInsnNode receiver) || receiver.getOpcode() != Opcodes.ALOAD || receiver.var == 0) return;

                if (flag.polarity() == BooleanStores.Polarity.POSITIVE) {
                    if (write.targetInsn() instanceof FieldInsnNode get
                        && get.getOpcode() == Opcodes.GETFIELD
                        && owner.name.equals(get.owner)) {
                        scan.stateGatedByFlag().computeIfAbsent(flagGet.name, key -> new LinkedHashSet<>()).add(get.name);
                        return;
                    }
                    // A loop over a ModelPart[] writes through the element local it just read, so
                    // the target names no field at all and the gate covers the whole array.
                    if (write.targetInsn() instanceof VarInsnNode load
                        && load.getOpcode() == Opcodes.ALOAD
                        && arrayElements.containsKey(load.var)) {
                        scan.arrayGatedByFlag().computeIfAbsent(flagGet.name, key -> new LinkedHashSet<>())
                            .add(arrayElements.get(load.var));
                        return;
                    }
                    // Inline getChild target: LDC "<bone>"; INVOKEVIRTUAL ModelPart.getChild.
                    if (write.targetInsn() instanceof MethodInsnNode childCall
                        && childCall.getOpcode() == Opcodes.INVOKEVIRTUAL
                        && VanillaSourceClasses.Methods.GET_CHILD.equals(childCall.name)
                        && childCall.desc != null
                        && ClassKit.descriptorReturns(childCall.desc, VanillaSourceClasses.Types.MODEL_PART)) {
                        AbstractInsnNode boneLdc = AsmWalker.previousReal(childCall);
                        String boneName = boneLdc == null ? null : AsmWalker.stringLiteral(boneLdc);
                        if (boneName != null) scan.inlineGatedBones().add(boneName);
                    }
                    return;
                }

                // Negated-branch gate on a this.<bone> field; the default visibility is the
                // value written at the flag's zero state (the decoder folds the branch polarity).
                if (write.targetInsn() instanceof FieldInsnNode get
                    && get.getOpcode() == Opcodes.GETFIELD
                    && owner.name.equals(get.owner)) {
                    boolean defaultVisible = flag.valueAtFieldFalse();
                    scan.negatedGatedByFlag()
                        .computeIfAbsent(flagGet.name, key -> new NegatedGate(new LinkedHashSet<>(), defaultVisible))
                        .fields().add(get.name);
                }
            });
    }

    /**
     * Which local slots of one method hold an element of which {@code ModelPart[]} field.
     *
     * <p>An element read is {@code AALOAD} over an index push over the array, and the array is
     * either read straight off the field or out of a local a previous read stored it in - the
     * shape javac gives {@code for (ModelPart part : this.parts)}. Only that shape is followed: an
     * index that is an expression rather than one instruction leaves the element unbound, which
     * costs a toggle rather than naming the wrong bones.
     *
     * @param owner the class declaring the array field, which the read has to name for the binding
     *     to be its own
     * @param method the method to read
     * @return local slot to the array field its value came out of
     */
    private static @NotNull Map<Integer, String> partArrayElementLocals(
        @NotNull ClassNode owner, @NotNull MethodNode method) {

        Map<Integer, String> arrays = new LinkedHashMap<>();
        Map<Integer, String> elements = new LinkedHashMap<>();
        AsmWalker.over(method)
            .on(Insn.of(VarInsnNode.class, store -> store.getOpcode() == Opcodes.ASTORE), store -> {
                AbstractInsnNode source = AsmWalker.previousReal(store);
                if (source == null) return;
                String read = partArrayFieldRead(owner, source);
                if (read != null) {
                    arrays.put(store.var, read);
                    return;
                }
                if (source.getOpcode() != Opcodes.AALOAD) return;
                AbstractInsnNode array = AsmWalker.previousReal(AsmWalker.previousReal(source));
                String direct = partArrayFieldRead(owner, array);
                if (direct != null) {
                    elements.put(store.var, direct);
                    return;
                }
                if (array instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD && arrays.containsKey(load.var))
                    elements.put(store.var, arrays.get(load.var));
            })
            .run();
        return elements;
    }

    /** The {@code ModelPart[]} field a read on {@code owner} names, or {@code null} for anything else. */
    private static @Nullable String partArrayFieldRead(@NotNull ClassNode owner, @Nullable AbstractInsnNode node) {
        return node instanceof FieldInsnNode get
            && get.getOpcode() == Opcodes.GETFIELD
            && owner.name.equals(get.owner)
            && VanillaSourceClasses.Descs.MODEL_PART_ARRAY_REF.equals(get.desc) ? get.name : null;
    }

    /**
     * The bones each array-gated flag reaches - every element of every {@code ModelPart[]} it
     * writes through, named from the constructor that filled the array.
     *
     * <p>Read only for a model that has such a gate, so the extra constructor walk stays as rare as
     * the shape is: one class in the corpus.
     */
    private @NotNull Map<String, LinkedHashSet<String>> arrayGatedBones(
        @NotNull String modelClass, @NotNull HierarchyScan scan) {

        if (scan.arrayGatedByFlag().isEmpty()) return Map.of();
        Map<String, List<String>> arrays = EntityBoneNames.partArrayBoneNames(this.cache, modelClass, this.diagnostics);
        Map<String, LinkedHashSet<String>> out = new LinkedHashMap<>();
        scan.arrayGatedByFlag().forEach((flag, fields) -> {
            LinkedHashSet<String> bones = new LinkedHashSet<>();
            for (String field : fields) {
                List<String> members = arrays.get(field);
                if (members == null) {
                    this.diagnostics.warn("%s gates '%s' on %s but nothing names that array's bones - toggle dropped",
                        ClassKit.simpleName(modelClass), field, flag);
                    continue;
                }
                bones.addAll(members);
            }
            if (!bones.isEmpty()) out.put(flag, bones);
        });
        return out;
    }

    /**
     * Collects the unconditional {@code this.<bone>.visible = false} ctor writes: a literal
     * {@code false} r-value whose target is a {@code GETFIELD} on {@code owner}.
     */
    private static void collectUnconditionalHidden(@NotNull ClassNode owner, @NotNull MethodNode ctor, @NotNull LinkedHashSet<String> out) {
        AsmWalker.over(ctor)
            .mapNotNull(EntityBoneResolver::matchVisibleWrite)
            .where(write -> write.value() instanceof BooleanStores.ConstantStore constant && !constant.value())
            .mapNotNull(write -> write.targetInsn() instanceof FieldInsnNode get
                && get.getOpcode() == Opcodes.GETFIELD
                && owner.name.equals(get.owner) ? get.name : null)
            .forEach(out::add);
    }

    // ------------------------------------------------------------------------------------
    // renderer re-enables + toggle-subtree expansion + naming
    // ------------------------------------------------------------------------------------

    /**
     * The bones the renderer's own ctor re-enables via {@code visible = true} (the
     * Illusioner pattern overriding {@code IllagerModel}'s hat hide). Two target shapes: a
     * direct {@code GETFIELD <bone>:LModelPart;} and a {@code get<Bone>():LModelPart;}
     * accessor. Single-class walk - an inherited re-enable is deliberately not picked up.
     */
    private @NotNull LinkedHashSet<String> collectReEnabledBones() {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        ClassNode cn = this.cache.load(this.subject.rendererClass());
        MethodNode ctor = cn == null ? null : ClassKit.findMethod(cn, ClassKit.INIT);
        if (ctor == null) return out;
        AsmWalker.over(ctor)
            .mapNotNull(EntityBoneResolver::matchVisibleWrite)
            .where(write -> write.value() instanceof BooleanStores.ConstantStore constant && constant.value())
            .mapNotNull(write -> extractBoneName(write.targetInsn()))
            .forEach(out::add);
        return out;
    }

    /** The bone field name behind a re-enable target (GETFIELD or {@code get<Bone>()} accessor). */
    private static @Nullable String extractBoneName(@NotNull AbstractInsnNode node) {
        if (node.getOpcode() == Opcodes.GETFIELD && node instanceof FieldInsnNode get)
            return VanillaSourceClasses.Descs.MODEL_PART_REF.equals(get.desc) ? get.name : null;
        if (node.getOpcode() == Opcodes.INVOKEVIRTUAL && node instanceof MethodInsnNode mi) {
            if (mi.desc == null || !ClassKit.descriptorReturns(mi.desc, VanillaSourceClasses.Types.MODEL_PART)) return null;
            String name = mi.name;
            if (!name.startsWith("get") || name.length() <= 3) return null;
            String stem = name.substring(3);
            return stem.substring(0, 1).toLowerCase(Locale.ROOT) + stem.substring(1);
        }
        return null;
    }

    /**
     * Settles each toggle against the mesh it belongs to: its directly-named bones expand to the
     * full subtree they root, so flipping a group bone (bogged's {@code mushrooms}) takes its
     * children with it, and anything the mesh does not declare is dropped, a toggle left with
     * nothing going with it.
     *
     * <p>Both halves matter for an equipment overlay, whose model class may be the wearer's own -
     * a donkey's saddle is posed by the same class that gates its chests - so the mesh is what says
     * which of the gates the walk found this overlay can honour. The parent links come from parsing
     * the request, never re-read emitted JSON; leaf toggle bones root no subtree and pass through
     * unchanged.
     */
    private void expandToggleSubtrees(@NotNull Map<String, Toggle> toggles, @Nullable GeometryRequest request) {
        if (request == null) return;
        JsonTree parsed = GeometryParser.parse(this.cache, request, this.diagnostics.child("toggle-expansion"));
        JsonTree bones = parsed == null ? null : parsed.find("bones").orElse(null);
        if (bones == null) return;

        // name -> parent, insertion order preserved.
        Map<String, String> parents = new LinkedHashMap<>();
        bones.members().forEach((name, bone) ->
            parents.put(name, bone.findString("parent").orElse(null)));

        Iterator<Map.Entry<String, Toggle>> entries = toggles.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<String, Toggle> entry = entries.next();
            LinkedHashSet<String> members = new LinkedHashSet<>(entry.getValue().bones());
            // Fixpoint so a subtree of any depth closes regardless of parent-before-child
            // ordering in the parsed tree.
            boolean grew = true;
            while (grew) {
                grew = false;
                for (Map.Entry<String, String> bone : parents.entrySet()) {
                    if (members.contains(bone.getKey())) continue;
                    if (bone.getValue() != null && members.contains(bone.getValue())) {
                        members.add(bone.getKey());
                        grew = true;
                    }
                }
            }
            members.retainAll(parents.keySet());
            if (members.isEmpty()) entries.remove();
            else entry.setValue(new Toggle(new ArrayList<>(members), entry.getValue().defaultVisible()));
        }
    }

    /**
     * Whether a positive-gated flag's bones render on a freshly spawned entity - which is what
     * the flag itself reads there, since the write is {@code bone.visible = state.<flag>}.
     *
     * <p>Not always {@code false}: vanilla packs several flags into one synched byte and a packed
     * flag may store the negative, so a zero byte can read {@code true}. An armor stand's base
     * plate is the corpus's one such bone.
     */
    private boolean visibleAtZeroState(@NotNull String flag) {
        return this.spawnFlags.spawnValue(flag);
    }

    /**
     * The geometry bone name behind a model field: the ctor {@code getChild} map where the
     * field was built there, else the field's own name snake-cased.
     *
     * <p>The fallback is the convention rather than a guess - vanilla authors every bone
     * snake_case and every field holding one camelCase, so a field the map missed still names
     * the bone it holds. Passing the field name through raw instead emits a name no geometry
     * can carry, which resolves to nothing and takes the toggle down with it.
     */
    private static @NotNull String boneName(@NotNull HierarchyScan scan, @NotNull String field) {
        String bone = scan.fieldToBone().get(field);
        return bone != null ? bone : StringUtil.toSnakeCase(field);
    }

    /**
     * Strips a leading {@code left_} / {@code right_} so a symmetric pair flips under one
     * toggle ({@code left_horn} to {@code horn}); see
     * {@link EntityNamingPolicies#LEFT_RIGHT_STEMS}.
     */
    private static @NotNull String stripLeftRight(@NotNull String bone) {
        for (String stem : EntityNamingPolicies.LEFT_RIGHT_STEMS.strings())
            if (bone.startsWith(stem)) return bone.substring(stem.length());
        return bone;
    }

    /**
     * Derives a toggle name from a state flag: strips the {@code has} / {@code is} / {@code show}
     * prefix and snake_cases the stem ({@code hasChest} to {@code chest}) - naming only, never a
     * match gate.
     *
     * <p>A toggle names the thing it reaches, not a state of it, which is what leaves the corpus
     * reading as bare nouns - {@code chest}, {@code horn}, {@code egg}, {@code stinger}. Carrying a
     * flag's {@code show} through would break that for the two flags vanilla happens to spell that
     * way, and would read as an assertion the name cannot keep: a toggle reference renders the
     * flag's <em>other</em> state, so {@code show_base_plate} would name the render with no base
     * plate. Stripping it says which bones the toggle reaches and leaves the direction to
     * {@code default}, which is derived.
     */
    private static @NotNull String flagToToggleName(@NotNull String flag) {
        String stem = flag.startsWith("has") ? flag.substring(3)
            : flag.startsWith("is") ? flag.substring(2)
            : flag.startsWith("show") ? flag.substring(4)
            : flag;
        return StringUtil.toSnakeCase(stem);
    }

}
