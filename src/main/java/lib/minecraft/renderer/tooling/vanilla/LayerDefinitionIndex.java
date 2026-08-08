package lib.minecraft.renderer.tooling.vanilla;

import lib.minecraft.renderer.tooling.geometry.BabyMeshTransform;
import lib.minecraft.renderer.tooling.kernel.AsmKit;
import lib.minecraft.renderer.tooling.kernel.ClassNodeCache;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lib.minecraft.renderer.tooling.kernel.ToolingSession;
import lib.minecraft.renderer.tooling.kernel.VanillaSourceClasses;
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
public final class LayerDefinitionIndex {

    /** The Guava builder the createRoots map is assembled on (JDK/Guava name, not vanilla - stays local). */
    private static final @NotNull String IMMUTABLE_MAP_BUILDER_SUFFIX = "ImmutableMap$Builder";

    /** {@code ImmutableMap$Builder.put} (Guava member name - stays local, like AsmKit's JDK constants). */
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

    private LayerDefinitionIndex(@NotNull Map<String, Entry> entries) {
        this.entries = entries;
    }

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
        MethodNode createRoots = AsmKit.findMethod(cn, VanillaSourceClasses.Methods.CREATE_ROOTS);
        if (createRoots == null) {
            diagnostics.error("'%s.%s' missing - layer-definition index unresolved",
                VanillaSourceClasses.Types.LAYER_DEFINITIONS, VanillaSourceClasses.Methods.CREATE_ROOTS);
            return new LayerDefinitionIndex(out);
        }

        AsmKit.SlotTracker<Entry> slotState = new AsmKit.SlotTracker<>();
        // Tracks `astore N` of MeshTransformer references built locally via
        // `ldc F; invokestatic MeshTransformer.scaling(F)`. When a later `aload N` precedes an
        // `invokevirtual LayerDefinition.apply(MeshTransformer)`, the F here is the scale to
        // fold into the Entry. The horse layer uses this pattern: `ldc 1.1f; invokestatic
        // scaling; astore 75; ... aload 75; invokevirtual apply`.
        AsmKit.SlotTracker<Float> meshTransformerSlots = new AsmKit.SlotTracker<>();
        String pendingLayerField = null;
        Entry pendingDirect = null;
        Entry pendingMesh = null;
        Integer[] widthHeight = {null, null};
        // Tracks the grow of the most recent deformation on the operand stack - an inline
        // `new CubeDeformation(F); <init>` or a `GETSTATIC <field>:CubeDeformation` resolved
        // through the owner's <clinit> bind (FISH_PATTERN_DEFORMATION, the cat
        // COLLAR_DEFORMATION; CubeDeformation.NONE resolves to zero). When the next factory
        // call consumes the deformation, this value rides into the Entry as its grow
        // pre-seed. Reset on each new ModelLayers field so the value can't leak across
        // registrations.
        float[] pendingDeformationGrow = null;
        Float pendingFloat = null;
        // F of the `MeshTransformer.scaling(F)` that just returned to the operand stack but
        // hasn't yet been astored to a slot or applied. Cleared by ASTORE (captures into
        // meshTransformerSlots) or by direct inline consumption at the apply.
        Float pendingScalingMTFloat = null;
        // F of the MeshTransformer most recently pushed onto the operand stack (via GETSTATIC
        // of a static MeshTransformer field, or ALOAD of a tracked slot). Consumed by the next
        // `invokevirtual apply(MeshTransformer)` to fold into pendingDirect.
        Float pendingAppliedMTScale = null;
        // The aged-down transformer the MeshTransformer most recently pushed resolves to, when it
        // is one. Read only where the scaling resolve declines, so the ten scaling-bound fields
        // never pay for the second walk.
        BabyMeshTransform pendingAppliedBaby = null;

        for (AbstractInsnNode in : createRoots.instructions) {
            int opcode = in.getOpcode();

            // Capture float literals that may end up as the `new CubeDeformation(F)` arg or a
            // single-float factory call-site argument.
            Float asFloat = AsmKit.readFloatLiteral(in);
            if (asFloat != null) {
                pendingFloat = asFloat;
                continue;
            }

            // `new CubeDeformation; dup; ldc F; invokespecial <init>(F)V`: capture the float
            // into pendingDeformationGrow so the next factory call that consumes the
            // deformation picks it up. Only the single-float ctor appears inline in
            // createRoots on 26.1; the FFF ctor never does.
            if (in instanceof MethodInsnNode mi
                && opcode == Opcodes.INVOKESPECIAL
                && AsmKit.INIT.equals(mi.name)
                && VanillaSourceClasses.Types.CUBE_DEFORMATION.equals(mi.owner)) {
                if (mi.desc.startsWith("(F") && pendingFloat != null)
                    pendingDeformationGrow = new float[]{pendingFloat, pendingFloat, pendingFloat};
                pendingFloat = null;
                continue;
            }

            // `GETSTATIC <field>: CubeDeformation` - a static-field deformation at the call
            // site, resolved through the owner's <clinit> bind.
            if (in instanceof FieldInsnNode fi && opcode == Opcodes.GETSTATIC
                && VanillaSourceClasses.Descs.CUBE_DEFORMATION_REF.equals(fi.desc)) {
                pendingDeformationGrow = resolveDeformationField(cache, fi.owner, fi.name);
                continue;
            }

            Integer asInt = AsmKit.readIntLiteral(in);
            if (asInt != null) {
                widthHeight[0] = widthHeight[1];
                widthHeight[1] = asInt;
                continue;
            }

            if (AsmKit.isGetStatic(in, VanillaSourceClasses.Types.MODEL_LAYERS)) {
                pendingLayerField = ((FieldInsnNode) in).name;
                pendingDirect = null;
                pendingMesh = null;
                pendingDeformationGrow = null;
                pendingFloat = null;
                pendingAppliedMTScale = null;
                pendingAppliedBaby = null;
                continue;
            }

            // `GETSTATIC <field>: MeshTransformer` - cat / horse-family pattern chaining a
            // class-level static transformer onto the LayerDefinition via apply(). Resolved via
            // the field owner's <clinit>; non-canonical initialisers (indy-backed
            // DONKEY_TRANSFORMER, compound applies) resolve to null, leaving the chain at 1f.
            //
            // A field the scaling resolve declines may still be an aged-down transformer - the
            // MeshTransformer type covers both, and three vanilla classes spell BABY_TRANSFORMER
            // as a scaling while a fourth spells it as a BabyModelTransform.
            if (in instanceof FieldInsnNode fi && opcode == Opcodes.GETSTATIC
                && VanillaSourceClasses.Descs.MESH_TRANSFORMER_REF.equals(fi.desc)) {
                pendingAppliedMTScale = AsmKit.resolveStaticScalingFactor(cache, fi.owner, fi.name,
                    VanillaSourceClasses.Types.MESH_TRANSFORMER, VanillaSourceClasses.Methods.SCALING,
                    VanillaSourceClasses.Descs.MESH_TRANSFORMER_REF);
                pendingAppliedBaby = pendingAppliedMTScale != null
                    ? null
                    : BabyMeshTransform.resolve(cache, fi.owner, fi.name);
                continue;
            }

            // `invokestatic MeshTransformer.scaling(F)` - two consumption patterns:
            // slot-mediated (horse: astore then aload + apply) captured via
            // pendingScalingMTFloat for the ASTORE handler, and inline (cave_spider:
            // `aload; ldc 0.7f; invokestatic scaling; invokevirtual apply`, no astore)
            // captured via pendingAppliedMTScale for the apply handler. The ASTORE handler
            // clears the inline mirror on store; the apply handler clears the slot mirror on
            // direct consumption.
            if (AsmKit.isInvokeStatic(in, VanillaSourceClasses.Types.MESH_TRANSFORMER,
                    VanillaSourceClasses.Methods.SCALING, SCALING_DESC)
                && pendingFloat != null) {
                pendingScalingMTFloat = pendingFloat;
                pendingAppliedMTScale = pendingFloat;
                pendingFloat = null;
                continue;
            }

            if (in instanceof MethodInsnNode mi && opcode == Opcodes.INVOKESTATIC) {
                if (AsmKit.descriptorReturns(mi.desc, VanillaSourceClasses.Types.MESH_DEFINITION)) {
                    pendingMesh = new Entry(mi.owner, mi.name, mi.desc, null, null,
                        pendingLayerField == null ? "" : pendingLayerField,
                        growOf(pendingDeformationGrow), null, 1f, null);
                    pendingDeformationGrow = null;
                    continue;
                }
                if (VanillaSourceClasses.Types.LAYER_DEFINITION.equals(mi.owner)
                    && VanillaSourceClasses.Methods.CREATE.equals(mi.name)
                    && pendingMesh != null) {
                    pendingDirect = new Entry(pendingMesh.factoryClass(), pendingMesh.factoryMethod(),
                        pendingMesh.factoryDesc(), widthHeight[0], widthHeight[1],
                        pendingMesh.layerField(), pendingMesh.grow(), null, 1f, null);
                    pendingMesh = null;
                    continue;
                }
                if (AsmKit.descriptorReturns(mi.desc, VanillaSourceClasses.Types.LAYER_DEFINITION)
                    && !VanillaSourceClasses.Types.LAYER_DEFINITION.equals(mi.owner)) {
                    // A single-float factory (`DonkeyModel.createBodyLayer(F)`) captures the
                    // call-site literal so the parser can substitute it via slot 0; other
                    // arities leave floatParam null.
                    Float floatParam = pendingFloat != null && mi.desc.startsWith("(F)") ? pendingFloat : null;
                    pendingDirect = new Entry(mi.owner, mi.name, mi.desc, null, null,
                        pendingLayerField == null ? "" : pendingLayerField,
                        growOf(pendingDeformationGrow), floatParam, 1f, null);
                    pendingDeformationGrow = null;
                    pendingFloat = null;
                }
                continue;
            }

            if (in instanceof VarInsnNode vi && opcode == Opcodes.ASTORE) {
                if (pendingScalingMTFloat != null) {
                    meshTransformerSlots.store(vi.var, pendingScalingMTFloat);
                    pendingScalingMTFloat = null;
                    // Also clear the inline-apply mirror so a slot-mediated store doesn't leave
                    // a stale scale dangling for an unrelated downstream apply.
                    pendingAppliedMTScale = null;
                    continue;
                }
                if (pendingDirect != null) {
                    slotState.store(vi.var, pendingDirect);
                    pendingDirect = null;
                }
                continue;
            }

            if (in instanceof VarInsnNode vi && opcode == Opcodes.ALOAD) {
                Entry stored = slotState.load(vi.var);
                if (stored != null) {
                    pendingDirect = stored;
                    continue;
                }
                Float mtSlot = meshTransformerSlots.load(vi.var);
                if (mtSlot != null) pendingAppliedMTScale = mtSlot;
                continue;
            }

            // `invokevirtual LayerDefinition.apply(MeshTransformer)` - folds the resolved F
            // into the pending entry (cat CAT_TRANSFORMER 0.8f, horse slot 75, cave_spider
            // inline 0.7f).
            if (AsmKit.isInvokeVirtual(in, VanillaSourceClasses.Types.LAYER_DEFINITION,
                    VanillaSourceClasses.Methods.APPLY, APPLY_DESC)
                && pendingDirect != null
                && (pendingAppliedMTScale != null || pendingAppliedBaby != null)) {
                if (pendingAppliedMTScale != null) pendingDirect = pendingDirect.composeAppliedScale(pendingAppliedMTScale);
                if (pendingAppliedBaby != null) pendingDirect = pendingDirect.composeBabyTransform(pendingAppliedBaby);
                pendingAppliedMTScale = null;
                pendingAppliedBaby = null;
                // Inline apply consumes the scaling result directly off the operand stack -
                // clear the slot mirror so a later unrelated ASTORE doesn't pick it up.
                pendingScalingMTFloat = null;
                continue;
            }

            if (in instanceof MethodInsnNode mi
                && opcode == Opcodes.INVOKEVIRTUAL
                && BUILDER_PUT.equals(mi.name)
                && mi.owner.endsWith(IMMUTABLE_MAP_BUILDER_SUFFIX)
                && pendingLayerField != null
                && pendingDirect != null) {
                out.put(pendingLayerField, unaliasDelegate(cache, new Entry(
                    pendingDirect.factoryClass(), pendingDirect.factoryMethod(), pendingDirect.factoryDesc(),
                    pendingDirect.texWidthOverride(), pendingDirect.texHeightOverride(),
                    pendingLayerField, pendingDirect.grow(), pendingDirect.floatParam(),
                    pendingDirect.appliedMeshTransformerScale(), pendingDirect.appliedBabyTransform())));
                pendingLayerField = null;
                pendingDirect = null;
                pendingMesh = null;
                pendingAppliedMTScale = null;
                pendingAppliedBaby = null;
            }
        }

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
        MethodNode clinit = AsmKit.findClinit(cache, ownerInternalName);
        if (clinit == null) return null;
        boolean inAlloc = false;
        float[] literals = new float[3];
        int seen = 0;
        for (AbstractInsnNode in : clinit.instructions) {
            if (in.getOpcode() == Opcodes.NEW
                && in instanceof TypeInsnNode alloc
                && VanillaSourceClasses.Types.CUBE_DEFORMATION.equals(alloc.desc)) {
                inAlloc = true;
                seen = 0;
                continue;
            }
            if (!inAlloc) continue;
            Float literal = AsmKit.readFloatLiteral(in);
            if (literal != null) {
                if (seen < 3) literals[seen] = literal;
                seen++;
                continue;
            }
            if (AsmKit.isPutStatic(in, ownerInternalName, fieldName) && seen >= 1)
                return seen == 1
                    ? new float[]{literals[0], literals[0], literals[0]}
                    : new float[]{literals[0], literals[1], literals[2]};
            if (in.getOpcode() == Opcodes.PUTSTATIC) inAlloc = false;
        }
        return null;
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
        MethodNode clinit = AsmKit.findClinit(cache, ownerInternalName);
        if (clinit == null) return null;
        float[] literals = new float[3];
        int seen = 0;
        for (AbstractInsnNode in : clinit.instructions) {
            Float literal = AsmKit.readFloatLiteral(in);
            if (literal != null) {
                if (seen < 3) literals[seen] = literal;
                seen++;
                continue;
            }
            if (in.getOpcode() != Opcodes.PUTSTATIC) continue;
            if (AsmKit.isPutStatic(in, ownerInternalName, fieldName) && seen >= 3)
                return new float[]{literals[0], literals[1], literals[2]};
            seen = 0;
        }
        return null;
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
        MethodNode method = AsmKit.findMethod(cn, entry.factoryMethod(), entry.factoryDesc());
        if (method == null) return entry;
        MethodInsnNode delegate = null;
        for (AbstractInsnNode in : method.instructions) {
            int op = in.getOpcode();
            if (op == -1) continue;
            if (op == Opcodes.INVOKESTATIC && in instanceof MethodInsnNode mi) {
                if (delegate != null) return entry;
                delegate = mi;
                continue;
            }
            if (op == Opcodes.ARETURN) continue;
            return entry;
        }
        if (delegate == null || !delegate.desc.equals(entry.factoryDesc())) return entry;
        return new Entry(delegate.owner, delegate.name, delegate.desc,
            entry.texWidthOverride(), entry.texHeightOverride(), entry.layerField(),
            entry.grow(), entry.floatParam(), entry.appliedMeshTransformerScale(),
            entry.appliedBabyTransform());
    }

}
