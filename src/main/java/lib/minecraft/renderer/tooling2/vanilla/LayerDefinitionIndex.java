package lib.minecraft.renderer.tooling2.vanilla;

import lib.minecraft.renderer.tooling2.kernel.AsmKit;
import lib.minecraft.renderer.tooling2.kernel.ClassNodeCache;
import lib.minecraft.renderer.tooling2.kernel.Diagnostics;
import lib.minecraft.renderer.tooling2.kernel.ToolingSession;
import lib.minecraft.renderer.tooling2.kernel.VanillaSourceClasses;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * THE {@code LayerDefinitions.createRoots} walk, built once per session (SPINE decision 32) -
 * replaces both legacy copies (entity {@code loadLayerDefinitions} + blockentity
 * {@code walkLayerDefinitions}, the acknowledged mirror pair [D57]), keying every
 * {@code ModelLayers} field to its factory coordinate plus the call-site bake arguments.
 *
 * <p>Field names are THREADED, never discarded [D57]; the donkey / mule float-slot-0
 * call-site seeding ({@code DonkeyModel.createBodyLayer(0.87f)} / mule {@code 0.92f} [D9])
 * and the two {@code MeshTransformer.scaling} consumption shapes (slot-mediated horse,
 * inline cave_spider) carry over from the legacy walk verbatim.
 *
 * <p>Pure delegate factories ({@code INVOKESTATIC other; ARETURN} -
 * {@code AdultZombifiedPiglinModel -> AdultPiglinModel}) are unaliased HERE, at build - the
 * registration path resolves delegates by construction at every site instead of the legacy
 * 5-of-6-call-sites caller discipline (doc 07 SS2).
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
     *     never discarded [D57]
     * @param grow the 3-component cube inflate captured from an inline
     *     {@code new CubeDeformation(F)} at the call site ({@code {0,0,0}} = none)
     * @param floatParam the call-site {@code float} literal for a single-{@code float}
     *     factory ({@code DonkeyModel.createBodyLayer(F)} - donkey {@code 0.87f}, mule
     *     {@code 0.92f} [D9]), seeded into the parser's slot 0; {@code null} for other arities
     * @param appliedMeshTransformerScale the composed scale of every
     *     {@code .apply(MeshTransformer.scaling(F))} chained onto the factory result
     *     ({@code 1f} = none)
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
        float appliedMeshTransformerScale
    ) {

        /** A copy with {@link #appliedMeshTransformerScale} multiplied by {@code factor}. */
        private @NotNull Entry composeAppliedScale(float factor) {
            return new Entry(this.factoryClass, this.factoryMethod, this.factoryDesc, this.texWidthOverride,
                this.texHeightOverride, this.layerField, this.grow, this.floatParam,
                this.appliedMeshTransformerScale * factor);
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
        // Tracks the inflate of the most recent inline `new CubeDeformation(F); <init>`. When
        // the next factory call consumes the deformation, this value rides into the Entry as
        // its grow pre-seed. Reset on each new ModelLayers field so the value can't leak
        // across registrations.
        Float pendingDeformationInflate = null;
        Float pendingFloat = null;
        // F of the `MeshTransformer.scaling(F)` that just returned to the operand stack but
        // hasn't yet been astored to a slot or applied. Cleared by ASTORE (captures into
        // meshTransformerSlots) or by direct inline consumption at the apply.
        Float pendingScalingMTFloat = null;
        // F of the MeshTransformer most recently pushed onto the operand stack (via GETSTATIC
        // of a static MeshTransformer field, or ALOAD of a tracked slot). Consumed by the next
        // `invokevirtual apply(MeshTransformer)` to fold into pendingDirect.
        Float pendingAppliedMTScale = null;

        for (AbstractInsnNode in = createRoots.instructions.getFirst(); in != null; in = in.getNext()) {
            int opcode = in.getOpcode();

            // Capture float literals that may end up as the `new CubeDeformation(F)` arg or a
            // single-float factory call-site argument.
            Float asFloat = AsmKit.readFloatLiteral(in);
            if (asFloat != null) {
                pendingFloat = asFloat;
                continue;
            }

            // `new CubeDeformation; dup; ldc F; invokespecial <init>(F)V`: capture the float
            // into pendingDeformationInflate so the next factory call that consumes the
            // deformation picks it up. Matches the legacy walk's `(F`-prefix gate (the
            // single-float ctor; the FFF ctor never appears inline in createRoots on 26.1).
            if (in instanceof MethodInsnNode mi
                && opcode == Opcodes.INVOKESPECIAL
                && AsmKit.INIT.equals(mi.name)
                && VanillaSourceClasses.Types.CUBE_DEFORMATION.equals(mi.owner)) {
                if (mi.desc.startsWith("(F") && pendingFloat != null) pendingDeformationInflate = pendingFloat;
                pendingFloat = null;
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
                pendingDeformationInflate = null;
                pendingFloat = null;
                pendingAppliedMTScale = null;
                continue;
            }

            // `GETSTATIC <field>: MeshTransformer` - cat / horse-family pattern chaining a
            // class-level static transformer onto the LayerDefinition via apply(). Resolved via
            // the field owner's <clinit>; non-canonical initialisers (indy-backed
            // DONKEY_TRANSFORMER, compound applies) resolve to null, leaving the chain at 1f.
            if (in instanceof FieldInsnNode fi && opcode == Opcodes.GETSTATIC
                && VanillaSourceClasses.Descs.MESH_TRANSFORMER_REF.equals(fi.desc)) {
                pendingAppliedMTScale = AsmKit.resolveStaticScalingFactor(cache, fi.owner, fi.name,
                    VanillaSourceClasses.Types.MESH_TRANSFORMER, VanillaSourceClasses.Methods.SCALING,
                    VanillaSourceClasses.Descs.MESH_TRANSFORMER_REF);
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
                        growOf(pendingDeformationInflate), null, 1f);
                    pendingDeformationInflate = null;
                    continue;
                }
                if (VanillaSourceClasses.Types.LAYER_DEFINITION.equals(mi.owner)
                    && VanillaSourceClasses.Methods.CREATE.equals(mi.name)
                    && pendingMesh != null) {
                    pendingDirect = new Entry(pendingMesh.factoryClass(), pendingMesh.factoryMethod(),
                        pendingMesh.factoryDesc(), widthHeight[0], widthHeight[1],
                        pendingMesh.layerField(), pendingMesh.grow(), null, 1f);
                    pendingMesh = null;
                    continue;
                }
                if (AsmKit.descriptorReturns(mi.desc, VanillaSourceClasses.Types.LAYER_DEFINITION)
                    && !VanillaSourceClasses.Types.LAYER_DEFINITION.equals(mi.owner)) {
                    // A single-float factory (`DonkeyModel.createBodyLayer(F)`) captures the
                    // call-site literal so the parser can substitute it via slot 0 [D9]; other
                    // arities leave floatParam null.
                    Float floatParam = pendingFloat != null && mi.desc.startsWith("(F)") ? pendingFloat : null;
                    pendingDirect = new Entry(mi.owner, mi.name, mi.desc, null, null,
                        pendingLayerField == null ? "" : pendingLayerField,
                        growOf(pendingDeformationInflate), floatParam, 1f);
                    pendingDeformationInflate = null;
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
                && pendingAppliedMTScale != null) {
                pendingDirect = pendingDirect.composeAppliedScale(pendingAppliedMTScale);
                pendingAppliedMTScale = null;
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
                    pendingDirect.appliedMeshTransformerScale())));
                pendingLayerField = null;
                pendingDirect = null;
                pendingMesh = null;
                pendingAppliedMTScale = null;
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

    /**
     * The full index, field to entry, in {@code createRoots} registration order (unmodifiable).
     */
    public @NotNull Map<String, Entry> entries() {
        return Collections.unmodifiableMap(this.entries);
    }

    /** The uniform 3-component grow of a scalar inline inflate ({@code null} = no grow). */
    private static float @NotNull [] growOf(@Nullable Float inflate) {
        return inflate == null ? new float[]{0f, 0f, 0f} : new float[]{inflate, inflate, inflate};
    }

    /**
     * Rewrites a pure-delegate factory ({@code INVOKESTATIC other; ARETURN} and nothing else)
     * to its delegate target, so entities sharing a layer factory through a no-op delegate
     * collapse onto one geometry key by construction (doc 07 SS2 - the legacy caller
     * discipline dies). Entries whose factory has any other instruction pass through
     * unchanged.
     */
    private static @NotNull Entry unaliasDelegate(@NotNull ClassNodeCache cache, @NotNull Entry entry) {
        ClassNode cn = cache.load(entry.factoryClass());
        if (cn == null) return entry;
        MethodNode method = AsmKit.findMethod(cn, entry.factoryMethod(), entry.factoryDesc());
        if (method == null) return entry;
        MethodInsnNode delegate = null;
        for (AbstractInsnNode in = method.instructions.getFirst(); in != null; in = in.getNext()) {
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
            entry.grow(), entry.floatParam(), entry.appliedMeshTransformerScale());
    }

}
