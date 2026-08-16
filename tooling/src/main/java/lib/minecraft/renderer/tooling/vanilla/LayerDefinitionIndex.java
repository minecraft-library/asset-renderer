package lib.minecraft.renderer.tooling.vanilla;

import dev.simplified.annotations.AccessLevel;
import dev.simplified.annotations.AllArgsConstructor;
import lib.minecraft.renderer.tooling.geometry.BabyMeshTransform;
import lib.minecraft.renderer.tooling.kernel.ClassKit;
import lib.minecraft.renderer.tooling.kernel.ClassNodeCache;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lib.minecraft.renderer.tooling.kernel.ToolingSession;
import lib.minecraft.renderer.tooling.kernel.VanillaSourceClasses;
import lib.minecraft.renderer.tooling.walk.AsmWalker;
import lib.minecraft.renderer.tooling.walk.Cells;
import lib.minecraft.renderer.tooling.walk.Insn;
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
import java.util.List;
import java.util.Map;

/**
 * Walks {@code LayerDefinitions.createRoots} once per session, keying every
 * {@code ModelLayers} field to its factory coordinate plus the call-site bake arguments.
 *
 * <p>Field names are threaded through every entry, never discarded; the donkey / mule
 * float-slot-0 call-site seeding ({@code DonkeyModel.createBodyLayer(0.87f)} / mule
 * {@code 0.92f}) and the two {@code MeshTransformer.scaling} consumption shapes
 * (slot-mediated horse, inline cave_spider) are captured verbatim.
 *
 * <p>Pure delegate factories ({@code INVOKESTATIC other; ARETURN} -
 * {@code AdultZombifiedPiglinModel -> AdultPiglinModel}) are unaliased here, at build time,
 * so entities sharing a layer factory through a no-op delegate resolve to the same
 * geometry key by construction, at every call site.
 */
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public final class LayerDefinitionIndex {

    /** The Guava builder the createRoots map is assembled on (JDK/Guava name, not vanilla - stays local). */
    private static final @NotNull String IMMUTABLE_MAP_BUILDER_SUFFIX = "ImmutableMap$Builder";

    /** {@code ImmutableMap$Builder.put} (Guava member name - stays local, like ClassKit's JDK constants). */
    private static final @NotNull String BUILDER_PUT = "put";

    /** Method descriptor of {@code LayerDefinition.apply(MeshTransformer)LayerDefinition}. */
    private static final @NotNull String APPLY_DESC = VanillaSourceClasses.Descs.of(
        VanillaSourceClasses.Descs.ref(VanillaSourceClasses.Types.LAYER_DEFINITION),
        VanillaSourceClasses.Descs.MESH_TRANSFORMER_REF);

    /** Method descriptor of {@code MeshTransformer.scaling(F)MeshTransformer}. */
    private static final @NotNull String SCALING_DESC =
        VanillaSourceClasses.Descs.of(VanillaSourceClasses.Descs.MESH_TRANSFORMER_REF, "F");

    /**
     * One resolved {@code ModelLayers} entry: the factory coordinate plus every call-site
     * bake argument the request factories consume.
     *
     * @param factoryClass the factory class's JVM internal name (post delegate-unalias)
     * @param factoryMethod the factory method name (post delegate-unalias)
     * @param factoryDesc the factory method descriptor
     * @param texWidthOverride explicit texture width when the entry pairs a
     *     {@code MeshDefinition} factory with an inline {@code LayerDefinition.create(mesh, W, H)},
     *     or {@code null} when the factory calls {@code LayerDefinition.create} itself
     * @param texHeightOverride the matching texture height, or {@code null}
     * @param layerField the {@code ModelLayers} field name this entry resolves - threaded,
     *     never discarded
     * @param grow the 3-component cube inflate captured from an inline
     *     {@code new CubeDeformation(F)} at the call site or resolved from a static
     *     deformation field's {@code <clinit>} bind ({@code {0,0,0}} = none)
     * @param floatParam the call-site {@code float} literal for a single-{@code float}
     *     factory ({@code DonkeyModel.createBodyLayer(F)} - donkey {@code 0.87f}, mule
     *     {@code 0.92f}), seeded into the parser's slot 0; {@code null} for other arities
     * @param appliedMeshTransformerScale the composed scale of every
     *     {@code .apply(MeshTransformer.scaling(F))} chained onto the factory result
     *     ({@code 1f} = none)
     * @param appliedBabyTransform the aged-down whole-mesh transformer chained onto the factory
     *     result, or {@code null} when none is - the armor stand's small body is the one entry in
     *     26.1 that carries one
     */
    public record Entry(
        @NotNull String factoryClass,
        @NotNull String factoryMethod,
        @NotNull String factoryDesc,
        @Nullable Integer texWidthOverride,
        @Nullable Integer texHeightOverride,
        @NotNull String layerField,
        float @NotNull [] grow,
        @Nullable Float floatParam,
        float appliedMeshTransformerScale,
        @Nullable BabyMeshTransform appliedBabyTransform
    ) {

        /** A copy with {@link #appliedMeshTransformerScale} multiplied by {@code factor}. */
        private @NotNull Entry composeAppliedScale(float factor) {
            return new Entry(this.factoryClass, this.factoryMethod, this.factoryDesc, this.texWidthOverride,
                this.texHeightOverride, this.layerField, this.grow, this.floatParam,
                this.appliedMeshTransformerScale * factor, this.appliedBabyTransform);
        }

        /** A copy carrying the aged-down transformer an {@code apply} chains onto the factory result. */
        private @NotNull Entry composeBabyTransform(@NotNull BabyMeshTransform transform) {
            return new Entry(this.factoryClass, this.factoryMethod, this.factoryDesc, this.texWidthOverride,
                this.texHeightOverride, this.layerField, this.grow, this.floatParam,
                this.appliedMeshTransformerScale, transform);
        }

    }

    private final @NotNull Map<String, Entry> entries;

    /**
     * Walks {@code LayerDefinitions.createRoots} once and builds the index. Tracks
     * {@code ImmutableMap.Builder.put(ModelLayers.X, factory)} pairs where the factory side
     * is an {@code INVOKESTATIC} returning {@code LayerDefinition} (or a
     * {@code MeshDefinition} factory wrapped in an inline {@code LayerDefinition.create}),
     * possibly {@code ASTORE}d and re-{@code ALOAD}ed before the put.
     *
     * @param session the live session
     * @return the built index (empty on a missing class / method, ERROR recorded)
     */
    public static @NotNull LayerDefinitionIndex build(@NotNull ToolingSession session) {
        ClassNodeCache cache = session.cache();
        Diagnostics diagnostics = session.diagnostics().child("layerDefinitions");
        Map<String, Entry> out = new LinkedHashMap<>();

        ClassNode cn = cache.load(VanillaSourceClasses.Types.LAYER_DEFINITIONS);
        if (cn == null) {
            diagnostics.error("'%s' class missing - layer-definition index unresolved", VanillaSourceClasses.Types.LAYER_DEFINITIONS);
            return new LayerDefinitionIndex(out);
        }
        MethodNode createRoots = ClassKit.findMethod(cn, VanillaSourceClasses.Methods.CREATE_ROOTS);
        if (createRoots == null) {
            diagnostics.error("'%s.%s' missing - layer-definition index unresolved",
                VanillaSourceClasses.Types.LAYER_DEFINITIONS, VanillaSourceClasses.Methods.CREATE_ROOTS);
            return new LayerDefinitionIndex(out);
        }

        Cells.Slots<Entry> slotState = Cells.slots();
        // Tracks `astore N` of MeshTransformer references built locally via
        // `ldc F; invokestatic MeshTransformer.scaling(F)`. When a later `aload N` precedes an
        // `invokevirtual LayerDefinition.apply(MeshTransformer)`, the F here is the scale to
        // fold into the Entry. The horse layer uses this pattern: `ldc 1.1f; invokestatic
        // scaling; astore 75; ... aload 75; invokevirtual apply`.
        Cells.Slots<Float> meshTransformerSlots = Cells.slots();
        Cells.Latch<String> pendingLayerField = Cells.latch();
        Cells.Latch<Entry> pendingDirect = Cells.latch();
        Cells.Latch<Entry> pendingMesh = Cells.latch();
        // The last two int literals, older first - an inline `LayerDefinition.create(mesh, W, H)`
        // reads them as its texture width and height.
        Cells.Window<Integer> widthHeight = Cells.window(AsmWalker::intLiteral, 2);
        // Tracks the grow of the most recent deformation on the operand stack - an inline
        // `new CubeDeformation(F); <init>` or a `GETSTATIC <field>:CubeDeformation` resolved
        // through the owner's <clinit> bind (FISH_PATTERN_DEFORMATION, the cat
        // COLLAR_DEFORMATION; CubeDeformation.NONE resolves to zero). When the next factory
        // call consumes the deformation, this value rides into the Entry as its grow
        // pre-seed. Reset on each new ModelLayers field so the value can't leak across
        // registrations.
        Cells.Latch<float[]> pendingDeformationGrow = Cells.latch();
        Cells.Latch<Float> pendingFloat = Cells.latch();
        // F of the `MeshTransformer.scaling(F)` that just returned to the operand stack but
        // hasn't yet been astored to a slot or applied. Cleared by ASTORE (captures into
        // meshTransformerSlots) or by direct inline consumption at the apply.
        Cells.Latch<Float> pendingScalingMTFloat = Cells.latch();
        // F of the MeshTransformer most recently pushed onto the operand stack (via GETSTATIC
        // of a static MeshTransformer field, or ALOAD of a tracked slot). Consumed by the next
        // `invokevirtual apply(MeshTransformer)` to fold into pendingDirect.
        Cells.Latch<Float> pendingAppliedMTScale = Cells.latch();
        // The aged-down transformer the MeshTransformer most recently pushed resolves to, when it
        // is one. Read only where the scaling resolve declines, so the ten scaling-bound fields
        // never pay for the second walk.
        Cells.Latch<BabyMeshTransform> pendingAppliedBaby = Cells.latch();

        AsmWalker.over(createRoots)
            // Capture float literals that may end up as the `new CubeDeformation(F)` arg or a
            // single-float factory call-site argument.
            .on(Insn.of(AbstractInsnNode.class, in -> AsmWalker.floatLiteral(in) != null), in -> {
                Float asFloat = AsmWalker.floatLiteral(in);
                if (asFloat != null) pendingFloat.set(asFloat);
            })
            // `new CubeDeformation; dup; ldc F; invokespecial <init>(F)V`: capture the float
            // into pendingDeformationGrow so the next factory call that consumes the
            // deformation picks it up. Only the single-float ctor appears inline in
            // createRoots on 26.1; the FFF ctor never does.
            .on(Insn.invokeSpecial(VanillaSourceClasses.Types.CUBE_DEFORMATION, ClassKit.INIT), mi -> {
                Float held = pendingFloat.get();
                if (mi.desc.startsWith("(F") && held != null)
                    pendingDeformationGrow.set(new float[]{held, held, held});
                pendingFloat.clear();
            })
            // `GETSTATIC <field>: CubeDeformation` - a static-field deformation at the call
            // site, resolved through the owner's <clinit> bind; an unresolvable field leaves
            // the latch empty.
            .on(Insn.of(FieldInsnNode.class, fi -> fi.getOpcode() == Opcodes.GETSTATIC
                && VanillaSourceClasses.Descs.CUBE_DEFORMATION_REF.equals(fi.desc)), fi -> {
                float[] resolvedGrow = resolveDeformationField(cache, fi.owner, fi.name);
                if (resolvedGrow != null) pendingDeformationGrow.set(resolvedGrow);
                else pendingDeformationGrow.clear();
            })
            .feed(widthHeight)
            .on(Insn.getStatic(VanillaSourceClasses.Types.MODEL_LAYERS), fi -> {
                pendingLayerField.set(fi.name);
                pendingDirect.clear();
                pendingMesh.clear();
                pendingDeformationGrow.clear();
                pendingFloat.clear();
                pendingAppliedMTScale.clear();
                pendingAppliedBaby.clear();
            })
            // `GETSTATIC <field>: MeshTransformer` - cat / horse-family pattern chaining a
            // class-level static transformer onto the LayerDefinition via apply(). Resolved via
            // the field owner's <clinit>; non-canonical initialisers (indy-backed
            // DONKEY_TRANSFORMER, compound applies) resolve to null, leaving the chain at 1f.
            //
            // A field the scaling resolve declines may still be an aged-down transformer - the
            // MeshTransformer type covers both, and three vanilla classes spell BABY_TRANSFORMER
            // as a scaling while a fourth spells it as a BabyModelTransform.
            .on(Insn.of(FieldInsnNode.class, fi -> fi.getOpcode() == Opcodes.GETSTATIC
                && VanillaSourceClasses.Descs.MESH_TRANSFORMER_REF.equals(fi.desc)), fi -> {
                Float scale = AsmWalker.resolveStaticScalingFactor(cache, fi.owner, fi.name,
                    VanillaSourceClasses.Types.MESH_TRANSFORMER, VanillaSourceClasses.Methods.SCALING,
                    VanillaSourceClasses.Descs.MESH_TRANSFORMER_REF);
                if (scale != null) {
                    pendingAppliedMTScale.set(scale);
                    pendingAppliedBaby.clear();
                } else {
                    pendingAppliedMTScale.clear();
                    BabyMeshTransform baby = BabyMeshTransform.resolve(cache, fi.owner, fi.name);
                    if (baby != null) pendingAppliedBaby.set(baby);
                    else pendingAppliedBaby.clear();
                }
            })
            // `invokestatic MeshTransformer.scaling(F)` - two consumption patterns:
            // slot-mediated (horse: astore then aload + apply) captured via
            // pendingScalingMTFloat for the ASTORE hook, and inline (cave_spider:
            // `aload; ldc 0.7f; invokestatic scaling; invokevirtual apply`, no astore)
            // captured via pendingAppliedMTScale for the apply hook. The ASTORE hook
            // clears the inline mirror on store; the apply hook clears the slot mirror on
            // direct consumption. A scaling call with no captured literal is inert.
            .on(Insn.invokeStatic(VanillaSourceClasses.Types.MESH_TRANSFORMER,
                VanillaSourceClasses.Methods.SCALING, SCALING_DESC), mi -> {
                Float held = pendingFloat.get();
                if (held == null) return;
                pendingScalingMTFloat.set(held);
                pendingAppliedMTScale.set(held);
                pendingFloat.clear();
            })
            .on(Insn.of(MethodInsnNode.class, mi -> mi.getOpcode() == Opcodes.INVOKESTATIC), mi -> {
                if (ClassKit.descriptorReturns(mi.desc, VanillaSourceClasses.Types.MESH_DEFINITION)) {
                    String layerField = pendingLayerField.get();
                    pendingMesh.set(new Entry(mi.owner, mi.name, mi.desc, null, null,
                        layerField == null ? "" : layerField,
                        growOf(pendingDeformationGrow.get()), null, 1f, null));
                    pendingDeformationGrow.clear();
                    return;
                }
                Entry mesh = pendingMesh.get();
                if (VanillaSourceClasses.Types.LAYER_DEFINITION.equals(mi.owner)
                    && VanillaSourceClasses.Methods.CREATE.equals(mi.name)
                    && mesh != null) {
                    List<Integer> sizes = widthHeight.values();
                    pendingDirect.set(new Entry(mesh.factoryClass(), mesh.factoryMethod(),
                        mesh.factoryDesc(), sizes.size() == 2 ? sizes.getFirst() : null,
                        sizes.isEmpty() ? null : sizes.getLast(),
                        mesh.layerField(), mesh.grow(), null, 1f, null));
                    pendingMesh.clear();
                    return;
                }
                if (ClassKit.descriptorReturns(mi.desc, VanillaSourceClasses.Types.LAYER_DEFINITION)
                    && !VanillaSourceClasses.Types.LAYER_DEFINITION.equals(mi.owner)) {
                    // A single-float factory (`DonkeyModel.createBodyLayer(F)`) captures the
                    // call-site literal so the parser can substitute it via slot 0; other
                    // arities leave floatParam null.
                    Float held = pendingFloat.get();
                    Float floatParam = held != null && mi.desc.startsWith("(F)") ? held : null;
                    String layerField = pendingLayerField.get();
                    pendingDirect.set(new Entry(mi.owner, mi.name, mi.desc, null, null,
                        layerField == null ? "" : layerField,
                        growOf(pendingDeformationGrow.get()), floatParam, 1f, null));
                    pendingDeformationGrow.clear();
                    pendingFloat.clear();
                }
            })
            .on(Insn.of(VarInsnNode.class, vi -> vi.getOpcode() == Opcodes.ASTORE), vi -> {
                Float scaling = pendingScalingMTFloat.get();
                if (scaling != null) {
                    meshTransformerSlots.store(vi.var, scaling);
                    pendingScalingMTFloat.clear();
                    // Also clear the inline-apply mirror so a slot-mediated store doesn't leave
                    // a stale scale dangling for an unrelated downstream apply.
                    pendingAppliedMTScale.clear();
                    return;
                }
                Entry direct = pendingDirect.get();
                if (direct != null) {
                    slotState.store(vi.var, direct);
                    pendingDirect.clear();
                }
            })
            .on(Insn.of(VarInsnNode.class, load -> load.getOpcode() == Opcodes.ALOAD), load -> {
                Entry stored = slotState.load(load.var);
                if (stored != null) {
                    pendingDirect.set(stored);
                    return;
                }
                Float mtSlot = meshTransformerSlots.load(load.var);
                if (mtSlot != null) pendingAppliedMTScale.set(mtSlot);
            })
            // `invokevirtual LayerDefinition.apply(MeshTransformer)` - folds the resolved F
            // into the pending entry (cat CAT_TRANSFORMER 0.8f, horse slot 75, cave_spider
            // inline 0.7f).
            .on(Insn.invokeVirtual(VanillaSourceClasses.Types.LAYER_DEFINITION,
                VanillaSourceClasses.Methods.APPLY).and(mi -> APPLY_DESC.equals(mi.desc)), mi -> {
                Entry direct = pendingDirect.get();
                Float scale = pendingAppliedMTScale.get();
                BabyMeshTransform baby = pendingAppliedBaby.get();
                if (direct == null || (scale == null && baby == null)) return;
                if (scale != null) direct = direct.composeAppliedScale(scale);
                if (baby != null) direct = direct.composeBabyTransform(baby);
                pendingDirect.set(direct);
                pendingAppliedMTScale.clear();
                pendingAppliedBaby.clear();
                // Inline apply consumes the scaling result directly off the operand stack -
                // clear the slot mirror so a later unrelated ASTORE doesn't pick it up.
                pendingScalingMTFloat.clear();
            })
            // The registration put commits only while armed - an unarmed put clears nothing -
            // and the reset is narrowed to the per-registration cells: both slot tables, the
            // width/height window, the deformation grow and the float literal deliberately
            // stay live across commits.
            .commitAt(Insn.of(MethodInsnNode.class, mi -> mi.getOpcode() == Opcodes.INVOKEVIRTUAL
                && BUILDER_PUT.equals(mi.name)
                && mi.owner.endsWith(IMMUTABLE_MAP_BUILDER_SUFFIX)
                && pendingLayerField.get() != null
                && pendingDirect.get() != null), put -> {
                String layerField = pendingLayerField.get();
                Entry direct = pendingDirect.get();
                if (layerField == null || direct == null) return;
                out.put(layerField, unaliasDelegate(cache, new Entry(
                    direct.factoryClass(), direct.factoryMethod(), direct.factoryDesc(),
                    direct.texWidthOverride(), direct.texHeightOverride(),
                    layerField, direct.grow(), direct.floatParam(),
                    direct.appliedMeshTransformerScale(), direct.appliedBabyTransform())));
            })
            .clearing(pendingLayerField, pendingDirect, pendingMesh, pendingAppliedMTScale, pendingAppliedBaby)
            .run();

        diagnostics.info("indexed %d ModelLayers entries from %s.%s", out.size(),
            VanillaSourceClasses.Types.LAYER_DEFINITIONS, VanillaSourceClasses.Methods.CREATE_ROOTS);
        return new LayerDefinitionIndex(out);
    }

    /**
     * The entry for a {@code ModelLayers} field, or {@code null} when {@code createRoots}
     * registers no factory under it.
     *
     * @param layerField the {@code ModelLayers} field name
     * @return the resolved entry, or {@code null}
     */
    public @Nullable Entry get(@NotNull String layerField) {
        return this.entries.get(layerField);
    }

    /** The 3-component grow of a pending deformation ({@code null} = no grow). */
    private static float @NotNull [] growOf(float @Nullable [] grow) {
        return grow == null ? new float[]{0f, 0f, 0f} : grow;
    }

    /**
     * Resolves a static {@code CubeDeformation} field to its 3-component grow by walking the
     * owner's {@code <clinit>} for the {@code new CubeDeformation(F|FFF); PUTSTATIC <field>}
     * bind. Returns {@code null} when the owner or the bind is absent (treated as no
     * deformation - the caller's growOf handles it).
     *
     * @param cache the per-session class cache
     * @param ownerInternalName the field owner's JVM internal name
     * @param fieldName the static field name
     * @return the 3-component grow, or {@code null} when unresolvable
     */
    static float @Nullable [] resolveDeformationField(
        @NotNull ClassNodeCache cache,
        @NotNull String ownerInternalName,
        @NotNull String fieldName
    ) {
        AsmWalker clinit = AsmWalker.clinit(cache, ownerInternalName);
        if (clinit.missing() != null) return null;
        // The literal slots persist across allocation segments - only the write cursor resets
        // at a NEW - so a bind that pushed fewer than three literals reads its missing
        // components off whatever an earlier segment left in those slots, zero when nothing
        // did.
        Cells.Slots<Float> literals = Cells.slots();
        Cells.Latch<Integer> seen = Cells.latch();
        Cells.Flag inAlloc = Cells.flag();
        float[][] resolved = new float[1][];
        clinit
            .on(Insn.of(TypeInsnNode.class, alloc -> alloc.getOpcode() == Opcodes.NEW
                && VanillaSourceClasses.Types.CUBE_DEFORMATION.equals(alloc.desc)), alloc -> {
                inAlloc.set();
                seen.set(0);
            })
            .on(Insn.of(AbstractInsnNode.class, in -> AsmWalker.floatLiteral(in) != null), in -> {
                if (!inAlloc.get()) return;
                Float literal = AsmWalker.floatLiteral(in);
                if (literal == null) return;
                Integer counted = seen.get();
                int count = counted == null ? 0 : counted;
                if (count < 3) literals.store(count, literal);
                seen.set(count + 1);
            })
            .on(Insn.of(FieldInsnNode.class, fi -> fi.getOpcode() == Opcodes.PUTSTATIC), fi -> {
                if (!inAlloc.get()) return;
                Integer counted = seen.get();
                int count = counted == null ? 0 : counted;
                if (ownerInternalName.equals(fi.owner) && fieldName.equals(fi.name) && count >= 1) {
                    if (resolved[0] == null) {
                        float first = slotOrZero(literals, 0);
                        resolved[0] = count == 1
                            ? new float[]{first, first, first}
                            : new float[]{first, slotOrZero(literals, 1), slotOrZero(literals, 2)};
                    }
                    return;
                }
                inAlloc.clear();
            })
            .run();
        return resolved[0];
    }

    /** The value bound to a literal slot, or zero for a never-written slot. */
    private static float slotOrZero(@NotNull Cells.Slots<Float> slots, int slot) {
        Float value = slots.load(slot);
        return value == null ? 0f : value;
    }

    /**
     * Resolves a static {@code PartPose} field to its {@code (x, y, z)} offset by walking the
     * owner's {@code <clinit>} for the literals its bind pushes.
     *
     * <p>Read as "the first three floats this field's initialiser pushes" rather than off a
     * constructor shape, because vanilla spells the two poses that reach an armor set two ways -
     * {@code PartPose.ZERO} through the {@code offsetAndRotation(FFFFFF)} factory and the baby
     * piglin's arm offset through the nine-argument record constructor. Both lead with the offset,
     * and only the offset is read: the mesh factory that consumes the pose touches nothing but
     * {@code x()} / {@code y()} / {@code z()}.
     *
     * @param cache the per-session class cache
     * @param ownerInternalName the field owner's JVM internal name
     * @param fieldName the static field name
     * @return the 3-component offset, or {@code null} when unresolvable
     */
    static float @Nullable [] resolvePartPoseField(
        @NotNull ClassNodeCache cache,
        @NotNull String ownerInternalName,
        @NotNull String fieldName
    ) {
        return AsmWalker.clinit(cache, ownerInternalName)
            .gather(AsmWalker::floatLiteral)
            .resetAt(Insn.opcode(Opcodes.PUTSTATIC))
            .commitAt(Insn.putStatic(ownerInternalName, fieldName))
            .firstNotNull(commit -> {
                List<Float> floats = commit.values();
                return floats.size() < 3 ? null
                    : new float[]{floats.getFirst(), floats.get(1), floats.get(2)};
            });
    }

    /**
     * Rewrites a pure-delegate factory ({@code INVOKESTATIC other; ARETURN} and nothing else)
     * to its delegate target, so entities sharing a layer factory through a no-op delegate
     * collapse onto one geometry key by construction. Entries whose factory has any other
     * instruction pass through unchanged.
     */
    private static @NotNull Entry unaliasDelegate(@NotNull ClassNodeCache cache, @NotNull Entry entry) {
        ClassNode cn = cache.load(entry.factoryClass());
        if (cn == null) return entry;
        MethodNode method = ClassKit.findMethod(cn, entry.factoryMethod(), entry.factoryDesc());
        if (method == null) return entry;
        AbstractInsnNode found = AsmWalker.over(method).real()
            .first(in -> in.getOpcode() == Opcodes.INVOKESTATIC && in instanceof MethodInsnNode,
                in -> in.getOpcode() == Opcodes.ARETURN);
        if (!(found instanceof MethodInsnNode delegate)
            || AsmWalker.after(found).real().any(in -> in.getOpcode() != Opcodes.ARETURN)
            || !delegate.desc.equals(entry.factoryDesc()))
            return entry;
        return new Entry(delegate.owner, delegate.name, delegate.desc,
            entry.texWidthOverride(), entry.texHeightOverride(), entry.layerField(),
            entry.grow(), entry.floatParam(), entry.appliedMeshTransformerScale(),
            entry.appliedBabyTransform());
    }

}
