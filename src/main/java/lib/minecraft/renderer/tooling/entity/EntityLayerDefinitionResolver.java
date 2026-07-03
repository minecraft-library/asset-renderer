package lib.minecraft.renderer.tooling.entity;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.tooling.ToolingEntityModels;
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
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Resolves an entity to the {@code LayerDefinition}-returning factory method that builds its
 * primary mesh. The lookup chain is:
 *
 * <ol>
 *   <li><b>Renderer constructor</b> -&gt; first {@code GETSTATIC ModelLayers.X} - the primary
 *       layer the renderer bakes via {@code context.bakeLayer(X)}. Subsequent layers
 *       (baby variants, armor model sets) are skipped here since the entity's "main"
 *       geometry is the first one wired.</li>
 *   <li><b>{@code LayerDefinitions.createRoots}</b> map - resolves {@code ModelLayers.X} to a
 *       {@code (class, method, descriptor)} target via {@code Builder.put(X, factoryCall)}.
 *       Mirrors {@code SourceDiscovery.walkLayerDefinitions} for block entities; written here
 *       independently so the entity pipeline doesn't reach into block-entity internals.</li>
 *   <li><b>Mesh-wrapper unwrap</b> (partial): when the {@code createRoots} entry itself pairs a
 *       {@code MeshDefinition} factory with an inline {@code LayerDefinition.create(mesh, W, H)},
 *       {@link #loadLayerDefinitions} captures the mesh factory as the target and carries {@code W}
 *       / {@code H} as {@link Result#texWidthOverride()} / {@link Result#texHeightOverride()}. What
 *       is <b>not</b> handled (TODO) is a resolved factory <i>method</i> whose own body is a thin
 *       {@code LayerDefinition.create(someMesh(), W, H)} wrapper - e.g.
 *       {@code SkullModel.createMobHeadLayer} - which would need a recursion into the factory body
 *       to follow the inner {@code MeshDefinition}. Block entities handle that via
 *       {@code SourceDiscovery.unwrapMeshWrapper}; for entities the pattern is rare and deferred
 *       until parity surfaces an example.</li>
 * </ol>
 *
 * <p>The result is consumed by {@link ToolingEntityModels} which feeds each
 * {@link Result} as a synthetic {@code Source} into
 * {@link lib.minecraft.renderer.tooling.parser.GeometryParser#parse} - the shared bytecode
 * walker also used by block-entity tooling, since the {@code LayerDefinition.create} /
 * {@code CubeListBuilder} / {@code PartPose} / {@code addOrReplaceChild} bytecode patterns
 * are identical between block and mob models.
 */
@UtilityClass
public final class EntityLayerDefinitionResolver {

    /** Suffix of every {@code (...)LayerDefinition} method descriptor. */
    private static final @NotNull String LAYER_DEFINITION_DESC_RETURN = ")L" + VanillaSourceClasses.LAYER_DEFINITION + ";";

    /** Suffix of every {@code (...)MeshDefinition} method descriptor. */
    private static final @NotNull String MESH_DEFINITION_DESC_RETURN = ")L" + VanillaSourceClasses.MESH_DEFINITION + ";";

    /** Field descriptor for a {@code MeshTransformer}-typed field or local. */
    private static final @NotNull String MESH_TRANSFORMER_DESC = VanillaSourceClasses.MESH_TRANSFORMER_DESC;

    /** Method descriptor of {@code LayerDefinition.apply(MeshTransformer)LayerDefinition}. */
    private static final @NotNull String APPLY_DESC = "(" + MESH_TRANSFORMER_DESC + ")L" + VanillaSourceClasses.LAYER_DEFINITION + ";";

    /**
     * The resolved factory target for one entity's primary mesh.
     *
     * @param targetClass the JVM internal name of the class hosting the factory method
     * @param targetMethod the factory method name (e.g. {@code "createBodyLayer"})
     * @param targetDesc the factory method descriptor
     * @param texWidthOverride optional explicit texture width when the factory returns a
     *     {@code MeshDefinition} wrapped in {@code LayerDefinition.create(mesh, W, H)};
     *     {@code null} when the factory itself calls {@code LayerDefinition.create}
     * @param texHeightOverride matching texture height override; {@code null} otherwise
     * @param sourceLayerField the {@code ModelLayers.X} field name that resolved to this target,
     *     for diagnostic / debugging output
     * @param defaultInflate cube inflate captured from an inline {@code new CubeDeformation(F)}
     *     passed to the factory call, or {@code 0f} when the factory takes no deformation; ridden
     *     through the synthetic {@code Source} to the parser as its {@code defaultInflate}
     * @param defaultFloatParam call-site {@code float} literal for a single-{@code float} factory
     *     ({@code createBodyLayer(F)}, e.g. donkey's {@code 0.87f}) that the parser substitutes
     *     for {@code paramFloatValues[0]}; {@code null} for other arities
     * @param appliedMeshTransformerScale composed scale from every
     *     {@code .apply(MeshTransformer.scaling(F))} chained onto the factory result; the identity
     *     {@code 1f} when no transformer applies
     */
    public record Result(
        @NotNull String targetClass,
        @NotNull String targetMethod,
        @NotNull String targetDesc,
        @Nullable Integer texWidthOverride,
        @Nullable Integer texHeightOverride,
        @NotNull String sourceLayerField,
        float defaultInflate,
        @Nullable Float defaultFloatParam,
        float appliedMeshTransformerScale
    ) {

        /**
         * Returns a copy with {@link #appliedMeshTransformerScale()} multiplied by {@code factor}.
         * Used by the resolver when a chain of {@code .apply(MeshTransformer)} calls follows the
         * factory invocation - each one composes by multiplication with the prior captures.
         *
         * @param factor the scale to fold into the running product
         * @return a copy of this {@code Result} with the composed scale
         */
        public Result composeAppliedScale(float factor) {
            return new Result(targetClass, targetMethod, targetDesc, texWidthOverride,
                texHeightOverride, sourceLayerField, defaultInflate, defaultFloatParam,
                appliedMeshTransformerScale * factor);
        }
    }

    /**
     * Detects the delegating-{@code createBodyLayer} pattern - a class whose static
     * {@code createBodyLayer} (or other factory) body consists solely of {@code INVOKESTATIC
     * <otherClass>.<otherMethod>; ARETURN}. Returns a {@code Result} rewritten to point at
     * the delegate target so multiple entities sharing a layer factory through a no-op
     * delegate collapse onto a single geometry entry. Vanilla 26.1 emits this pattern for
     * {@code AdultZombifiedPiglinModel.createBodyLayer} (delegates to
     * {@code AdultPiglinModel.createBodyLayer}) and {@code BabyZombifiedPiglinModel} similarly.
     *
     * <p>Returns the input {@code Result} unchanged when the target method has any other
     * instruction (cube builder, addOrReplaceChild, LayerDefinition.create wrapper, etc.) -
     * those are real factories that produce their own geometry.
     *
     * @param classNodes the ClassNode cache (shared with sibling resolver walks)
     * @param res the resolved factory target to test for a no-op delegate
     * @return {@code res} rewritten to point at the delegate target, or {@code res} unchanged
     *     when its factory is not a pure delegate
     */
    public static @NotNull Result unaliasDelegate(
        @NotNull ClassNodeCache classNodes,
        @NotNull Result res
    ) {
        ClassNode cn = classNodes.load(res.targetClass());
        if (cn == null) return res;
        MethodNode method = AsmKit.findMethod(cn, res.targetMethod(), res.targetDesc());
        if (method == null) return res;
        MethodInsnNode delegate = null;
        for (AbstractInsnNode in = method.instructions.getFirst(); in != null; in = in.getNext()) {
            int op = in.getOpcode();
            if (op == -1) continue;
            if (op == Opcodes.INVOKESTATIC && in instanceof MethodInsnNode mi) {
                if (delegate != null) return res;
                delegate = mi;
                continue;
            }
            if (op == Opcodes.ARETURN) continue;
            return res;
        }
        if (delegate == null) return res;
        if (!delegate.desc.equals(res.targetDesc())) return res;
        return new Result(
            delegate.owner,
            delegate.name,
            delegate.desc,
            res.texWidthOverride(),
            res.texHeightOverride(),
            res.sourceLayerField(),
            res.defaultInflate(),
            res.defaultFloatParam(),
            res.appliedMeshTransformerScale()
        );
    }

    /**
     * Resolves the primary {@code LayerDefinition} factory for the given renderer class.
     * Returns {@code null} when the renderer's constructor doesn't reference any
     * {@code ModelLayers.X} (e.g. {@code EnderDragonRenderer} which builds geometry procedurally)
     * or when the referenced layer has no entry in {@code LayerDefinitions.createRoots}.
     *
     * <p>Heuristic for "primary" layer field selection:
     * <ol>
     *   <li><b>Entity-id match</b> - prefer the field whose snake-case form equals the entity id
     *       (e.g. for {@code minecraft:donkey}, pick {@code DONKEY} over {@code DONKEY_BABY},
     *       {@code DONKEY_ARMOR}, etc.). Catches the common case where a renderer wires multiple
     *       layers in its {@code super(...)} call and the first GETSTATIC happens to be a baby
     *       or armor variant, not the body.</li>
     *   <li><b>Avoid suffixed variants</b> - skip fields ending in {@code _BABY},
     *       {@code _ARMOR}, {@code _SADDLE}, etc., even when they appear first in bytecode.
     *       Falls back to the first plain field if no entity-id match exists.</li>
     *   <li><b>First field</b> - last resort if neither rule yields a candidate.</li>
     * </ol>
     *
     * @param classNodes the ClassNode cache (shared with sibling resolver walks)
     * @param rendererInternalName the renderer's JVM internal name
     * @param entityId the namespaced entity id ({@code "minecraft:zombie"}); empty string disables
     *     the entity-id-match preference
     * @param additionalLayerFields extra {@code ModelLayers.X} field names harvested from lambda
     *     bodies that the constructor walk would otherwise miss
     * @param layerDefinitions the precomputed {@code (ModelLayers.X field name -&gt; Result)}
     *     map from {@link #loadLayerDefinitions(ClassNodeCache, Diagnostics)}
     * @param diagnostics the diagnostic sink shared with sibling discovery walks
     * @return the primary layer's resolution, or {@code null} when unresolvable
     */
    public static @Nullable Result resolvePrimary(
        @NotNull ClassNodeCache classNodes,
        @NotNull String rendererInternalName,
        @NotNull String entityId,
        @NotNull java.util.Collection<String> additionalLayerFields,
        @NotNull Map<String, Result> layerDefinitions,
        @NotNull Diagnostics diagnostics
    ) {
        java.util.LinkedHashSet<String> candidates = collectModelLayerFields(classNodes, rendererInternalName);
        // Merge in lambda-sourced fields (squid / endermite / piglin / donkey / llama /
        // zombified_piglin etc. - their renderer constructors take ModelLayerLocation params
        // and so have no GETSTATIC of their own; the supplying lambda in EntityRenderers.<clinit>
        // is the only place the layer field name appears).
        candidates.addAll(additionalLayerFields);
        if (candidates.isEmpty()) {
            diagnostics.info("renderer '%s' has no GETSTATIC ModelLayers in its constructor chain or factory lambda - skipped", rendererInternalName);
            return null;
        }
        String primaryLayerField = pickPrimaryLayerField(candidates, entityId);
        Result res = layerDefinitions.get(primaryLayerField);
        if (res == null) {
            diagnostics.info("renderer '%s' references ModelLayers.%s which is not in LayerDefinitions.createRoots - skipped", rendererInternalName, primaryLayerField);
            return null;
        }
        return res;
    }

    /**
     * Finds the entity's baby {@code ModelLayers} field by scanning every method of the renderer
     * class for the first {@code GETSTATIC ModelLayers.X_BABY} reference (the baby layer is baked
     * in {@code bakeModels} / the constructor as {@code context.bakeLayer(ModelLayers.X_BABY)}).
     * In 26.1 almost every ageable mob has a dedicated {@code Baby<X>Model} whose baby layer is
     * registered under this field, so resolving it through {@code layerDefs} yields a distinct,
     * self-contained baby geometry (no runtime young-scale needed).
     *
     * @param classNodes the ClassNode cache (shared with sibling resolver walks)
     * @param rendererInternalName the renderer's JVM internal name
     * @return the {@code X_BABY} field name, or {@code null} when the renderer references none
     */
    public static @Nullable String findBabyLayerField(
        @NotNull ClassNodeCache classNodes,
        @NotNull String rendererInternalName
    ) {
        ClassNode cn = classNodes.load(rendererInternalName);
        if (cn == null) return null;
        for (MethodNode method : cn.methods)
            for (AbstractInsnNode in = method.instructions.getFirst(); in != null; in = in.getNext())
                if (AsmKit.isGetStatic(in, VanillaSourceClasses.MODEL_LAYERS)) {
                    String name = ((FieldInsnNode) in).name;
                    if (name.endsWith("_BABY")) return name;
                }
        return null;
    }

    /**
     * Walks the renderer's constructor chain (including parent constructors) for every
     * {@code GETSTATIC ModelLayers.X} reference. The result preserves first-seen order so the
     * fallback "first field" heuristic remains deterministic, but the entity-id-match preference
     * can promote any matching field regardless of order.
     *
     * @param classNodes the ClassNode cache (shared with sibling resolver walks)
     * @param rendererInternalName the renderer's JVM internal name
     * @return the {@code ModelLayers.X} field names in first-seen order (may be empty)
     */
    private static @NotNull java.util.LinkedHashSet<String> collectModelLayerFields(
        @NotNull ClassNodeCache classNodes,
        @NotNull String rendererInternalName
    ) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        AsmKit.walkConstructorChain(classNodes, rendererInternalName, method -> {
            for (AbstractInsnNode in = method.instructions.getFirst(); in != null; in = in.getNext()) {
                if (AsmKit.isGetStatic(in, VanillaSourceClasses.MODEL_LAYERS))
                    out.add(((FieldInsnNode) in).name);
            }
        });
        return out;
    }

    /**
     * Picks the primary layer field from the candidate set per the heuristic documented on
     * {@link #resolvePrimary}. Package-private for testing.
     *
     * @param candidates the {@code ModelLayers.X} field names in first-seen order
     * @param entityId the namespaced entity id; drives the entity-id-match preference
     * @return the chosen primary layer field name
     */
    @NotNull
    static String pickPrimaryLayerField(@NotNull java.util.LinkedHashSet<String> candidates, @NotNull String entityId) {
        // Entity-id match: the field's snake-case form equals the entity id.
        // EntityType field names are uppercase snake-case ("ZOMBIE_HORSE"); entity ids are
        // lowercase ("zombie_horse"). Strip any "minecraft:" prefix off the entity id for the
        // comparison.
        String localId = entityId.startsWith("minecraft:") ? entityId.substring("minecraft:".length()) : entityId;
        for (String field : candidates) {
            if (field.equalsIgnoreCase(localId)) return field;
        }
        // Avoid suffixed variants: skip fields ending in _BABY / _ARMOR / _SADDLE / etc.
        // Pick the first non-suffixed field.
        for (String field : candidates) {
            if (!isVariantSuffixed(field)) return field;
        }
        // Fallback: first field in encounter order.
        return candidates.getFirst();
    }

    /**
     * {@code true} when the field name ends in a known variant suffix that disqualifies it as "primary".
     *
     * @param fieldName the {@code ModelLayers.X} field name to test
     * @return whether the name carries a {@code _BABY} / {@code _ARMOR} / {@code _SADDLE} / etc suffix
     */
    private static boolean isVariantSuffixed(@NotNull String fieldName) {
        return fieldName.endsWith("_BABY")
            || fieldName.endsWith("_ARMOR")
            || fieldName.endsWith("_SADDLE")
            || fieldName.endsWith("_INNER_ARMOR")
            || fieldName.endsWith("_OUTER_ARMOR")
            || fieldName.endsWith("_CAPE")
            || fieldName.endsWith("_EARS");
    }

    /**
     * Walks {@code LayerDefinitions.createRoots} and returns the {@code (ModelLayers.X field
     * name -&gt; Result)} map. Mirrors the algorithm in
     * {@code SourceDiscovery.walkLayerDefinitions}: tracks {@code ImmutableMap.Builder.put(X, Y)}
     * pairs where {@code X} is a {@code GETSTATIC ModelLayers.<field>} and {@code Y} is either
     * an {@code INVOKESTATIC} returning {@code LayerDefinition} (or its mesh-wrapped variant)
     * or an {@code ALOAD} of a slot holding such a result.
     *
     * @param classNodes the ClassNode cache (shared with sibling resolver walks)
     * @param diagnostics the diagnostic sink
     * @return the layer-name to factory-target map (empty on error)
     */
    public static @NotNull ConcurrentMap<String, Result> loadLayerDefinitions(
        @NotNull ClassNodeCache classNodes,
        @NotNull Diagnostics diagnostics
    ) {
        ConcurrentMap<String, Result> out = Concurrent.newMap();
        ClassNode cn = classNodes.load(VanillaSourceClasses.LAYER_DEFINITIONS);
        if (cn == null) {
            diagnostics.error("'%s' class missing - layer-definition map unresolved", VanillaSourceClasses.LAYER_DEFINITIONS);
            return out;
        }
        MethodNode createRoots = AsmKit.findMethod(cn, "createRoots");
        if (createRoots == null) {
            diagnostics.error("'%s.createRoots' missing - layer-definition map unresolved", VanillaSourceClasses.LAYER_DEFINITIONS);
            return out;
        }

        AsmKit.SlotTracker<Result> slotState = new AsmKit.SlotTracker<>();
        // Track {@code astore N} of MeshTransformer references built locally via
        // {@code ldc F; invokestatic MeshTransformer.scaling(F)}. When a later {@code aload N}
        // precedes an {@code invokevirtual LayerDefinition.apply(MeshTransformer)}, the F here
        // is the scale we want to fold into the {@link Result#appliedMeshTransformerScale()}.
        // The horse layer uses this pattern: {@code ldc 1.1f; invokestatic scaling; astore 75; ...
        // aload 75; invokevirtual apply}.
        AsmKit.SlotTracker<Float> meshTransformerSlots = new AsmKit.SlotTracker<>();
        // Per-class <clinit> cache for static MeshTransformer fields; lazily populated by
        // {@link #resolveStaticMeshTransformer}. Cat uses this pattern:
        // {@code getstatic AdultCatModel.CAT_TRANSFORMER; invokevirtual apply}.
        Map<String, Float> staticMeshTransformerCache = new LinkedHashMap<>();
        String pendingLayerField = null;
        Result pendingDirect = null;
        Result pendingMesh = null;
        Integer pendingInt = null;
        Integer[] widthHeight = { null, null };
        // Tracks the inflate value of the most recent inline {@code new CubeDeformation(F);
        // <init>}. When the next {@code invokestatic <FactoryClass>.createBodyLayer
        // (CubeDeformation)} fires, this value rides into the {@link Result} so the synthetic
        // overlay {@code Source} carries it through to the parser as {@code defaultInflate}.
        // Reset on each new ModelLayers field so the value can't leak across registrations.
        Float pendingDeformationInflate = null;
        Float pendingFloat = null;
        // F of the {@code MeshTransformer.scaling(F)} that just returned to the operand stack but
        // hasn't yet been astored to a slot or applied. Cleared by ASTORE (captures into
        // {@code meshTransformerSlots}) or by other consuming opcodes.
        Float pendingScalingMTFloat = null;
        // F of the MeshTransformer most recently pushed onto the operand stack (via GETSTATIC of a
        // static MeshTransformer field, or ALOAD of a tracked slot). Consumed by the next
        // {@code invokevirtual apply(MeshTransformer)} to fold into {@link #pendingDirect}.
        Float pendingAppliedMTScale = null;

        for (AbstractInsnNode in = createRoots.instructions.getFirst(); in != null; in = in.getNext()) {
            int opcode = in.getOpcode();

            // Capture float literals that may end up as the {@code new CubeDeformation(F)} arg.
            Float asFloat = AsmKit.readFloatLiteral(in);
            if (asFloat != null) {
                pendingFloat = asFloat;
                continue;
            }

            // {@code new CubeDeformation; dup; ldc <FLOAT>; invokespecial <init>(F)V}: capture
            // the float value into {@link #pendingDeformationInflate} so the next factory call
            // that consumes the deformation can pick it up. Three-arg variant (FFF) averaged
            // since the parser collapses asymmetric inflates to a scalar already.
            if (in instanceof MethodInsnNode mi
                && opcode == Opcodes.INVOKESPECIAL
                && AsmKit.INIT.equals(mi.name)
                && mi.owner.endsWith("/CubeDeformation")) {
                if (mi.desc.startsWith("(F") && pendingFloat != null) {
                    pendingDeformationInflate = pendingFloat;
                }
                pendingFloat = null;
                continue;
            }

            Integer asInt = AsmKit.readIntLiteral(in);
            if (asInt != null) {
                pendingInt = asInt;
                widthHeight[0] = widthHeight[1];
                widthHeight[1] = asInt;
                continue;
            }

            if (in instanceof FieldInsnNode fi && opcode == Opcodes.GETSTATIC && VanillaSourceClasses.MODEL_LAYERS.equals(fi.owner)) {
                pendingLayerField = fi.name;
                pendingDirect = null;
                pendingMesh = null;
                pendingInt = null;
                pendingDeformationInflate = null;
                pendingFloat = null;
                pendingAppliedMTScale = null;
                continue;
            }

            // {@code GETSTATIC <field>: MeshTransformer} - cat / horse-family pattern that
            // chains a class-level static transformer onto the LayerDefinition via apply().
            // Resolve via the field owner's {@code <clinit>} walker; missing or
            // non-canonically-initialised fields resolve to null, leaving the chain at the
            // base scale of 1f. The subsequent invokevirtual apply consumes pendingAppliedMTScale.
            if (in instanceof FieldInsnNode fi && opcode == Opcodes.GETSTATIC
                && MESH_TRANSFORMER_DESC.equals(fi.desc)) {
                pendingAppliedMTScale = resolveStaticMeshTransformer(fi.owner, fi.name, staticMeshTransformerCache, classNodes);
                continue;
            }

            // {@code invokestatic MeshTransformer.scaling(F)MeshTransformer} - two consumption
            // patterns from this call site:
            // <ul>
            //   <li><b>Horse pattern</b> ({@code aload <slot>}-mediated): the result is
            //       {@code astore <slot>}d for later {@code aload + apply()}. Captured into
            //       {@link #pendingScalingMTFloat} for the ASTORE handler below.</li>
            //   <li><b>Cave_spider pattern</b> (inline): the result is consumed immediately by
            //       the next {@code invokevirtual apply(MeshTransformer)} with no intervening
            //       ASTORE. Vanilla {@code LayerDefinitions.createRoots} writes
            //       {@code spiderBodyLayer.apply(MeshTransformer.scaling(0.7F))} for
            //       {@code ModelLayers.CAVE_SPIDER}; the bytecode is {@code aload spiderBodyLayer;
            //       ldc 0.7f; invokestatic scaling; invokevirtual apply}, no astore. Capture
            //       into {@link #pendingAppliedMTScale} too so the downstream
            //       {@code invokevirtual LayerDefinition.apply} handler finds a non-null value
            //       when consumed inline.</li>
            // </ul>
            // The ASTORE handler clears {@code pendingScalingMTFloat} on store; the apply
            // handler clears {@code pendingAppliedMTScale} on direct consumption; if both
            // patterns somehow coexist on one call site (no vanilla example exists today), the
            // ASTORE wins because it fires first in bytecode order.
            if (in instanceof MethodInsnNode mi
                && opcode == Opcodes.INVOKESTATIC
                && VanillaSourceClasses.MESH_TRANSFORMER.equals(mi.owner)
                && "scaling".equals(mi.name)
                && ("(F)" + MESH_TRANSFORMER_DESC).equals(mi.desc)
                && pendingFloat != null) {
                pendingScalingMTFloat = pendingFloat;
                pendingAppliedMTScale = pendingFloat;
                pendingFloat = null;
                continue;
            }

            if (in instanceof MethodInsnNode mi && opcode == Opcodes.INVOKESTATIC) {
                if (mi.desc.endsWith(MESH_DEFINITION_DESC_RETURN)) {
                    pendingMesh = new Result(mi.owner, mi.name, mi.desc, null, null,
                        pendingLayerField == null ? "" : pendingLayerField,
                        pendingDeformationInflate != null ? pendingDeformationInflate : 0f, null, 1f);
                    pendingInt = null;
                    pendingDeformationInflate = null;
                    continue;
                }
                if (VanillaSourceClasses.LAYER_DEFINITION.equals(mi.owner) && "create".equals(mi.name) && pendingMesh != null) {
                    pendingDirect = new Result(
                        pendingMesh.targetClass,
                        pendingMesh.targetMethod,
                        pendingMesh.targetDesc,
                        widthHeight[0],
                        widthHeight[1],
                        pendingMesh.sourceLayerField,
                        pendingMesh.defaultInflate,
                        null,
                        1f
                    );
                    pendingMesh = null;
                    continue;
                }
                if (mi.desc.endsWith(LAYER_DEFINITION_DESC_RETURN) && !VanillaSourceClasses.LAYER_DEFINITION.equals(mi.owner)) {
                    // When the factory takes a single {@code float} arg (e.g.
                    // {@code DonkeyModel.createBodyLayer(F)}), capture the call-site literal so
                    // the parser can substitute it via {@code paramFloatValues[0]}. The donkey
                    // call site is {@code ldc 0.87f; invokestatic createBodyLayer(F)} - so
                    // {@code pendingFloat} carries the F we want. Other arities (no args, or
                    // CubeDeformation only) leave {@code defaultFloatParam} null.
                    Float floatParam = (pendingFloat != null && mi.desc.startsWith("(F)")) ? pendingFloat : null;
                    pendingDirect = new Result(mi.owner, mi.name, mi.desc, null, null,
                        pendingLayerField == null ? "" : pendingLayerField,
                        pendingDeformationInflate != null ? pendingDeformationInflate : 0f,
                        floatParam, 1f);
                    pendingDeformationInflate = null;
                    pendingFloat = null;
                }
                continue;
            }

            if (in instanceof VarInsnNode vi && opcode == Opcodes.ASTORE) {
                if (pendingScalingMTFloat != null) {
                    meshTransformerSlots.store(vi.var, pendingScalingMTFloat);
                    pendingScalingMTFloat = null;
                    // Also clear the inline-apply mirror so a horse-pattern store doesn't
                    // leave a stale scale dangling for an unrelated downstream {@code apply}.
                    pendingAppliedMTScale = null;
                    continue;
                }
                if (pendingDirect != null) {
                    slotState.store(vi.var, pendingDirect);
                    pendingDirect = null;
                    continue;
                }
                continue;
            }

            if (in instanceof VarInsnNode vi && opcode == Opcodes.ALOAD) {
                Result stored = slotState.load(vi.var);
                if (stored != null) {
                    pendingDirect = stored;
                    continue;
                }
                Float mtSlot = meshTransformerSlots.load(vi.var);
                if (mtSlot != null) pendingAppliedMTScale = mtSlot;
                continue;
            }

            // {@code invokevirtual LayerDefinition.apply(MeshTransformer)LayerDefinition} - the
            // chain that wraps a per-class MeshTransformer onto a LayerDefinition right before
            // the ImmutableMap.put. Cat puts CAT_TRANSFORMER (0.8f) here for ModelLayers.CAT;
            // horse pulls the scaling result from local slot 75 for ModelLayers.HORSE.
            // Fold the resolved F into {@code pendingDirect} so the downstream put step writes
            // the composed scale into the {@link Result}.
            if (in instanceof MethodInsnNode mi
                && opcode == Opcodes.INVOKEVIRTUAL
                && VanillaSourceClasses.LAYER_DEFINITION.equals(mi.owner)
                && "apply".equals(mi.name)
                && APPLY_DESC.equals(mi.desc)
                && pendingDirect != null
                && pendingAppliedMTScale != null) {
                pendingDirect = pendingDirect.composeAppliedScale(pendingAppliedMTScale);
                pendingAppliedMTScale = null;
                // Inline {@code apply(MeshTransformer.scaling(F))} consumes the MT result
                // directly off the operand stack with no intervening ASTORE - clear the
                // scaling mirror so a subsequent unrelated ASTORE doesn't pick it up.
                pendingScalingMTFloat = null;
                continue;
            }

            if (in instanceof MethodInsnNode mi
                && opcode == Opcodes.INVOKEVIRTUAL
                && "put".equals(mi.name)
                && mi.owner.endsWith("ImmutableMap$Builder")
                && pendingLayerField != null
                && pendingDirect != null) {
                out.put(pendingLayerField, new Result(
                    pendingDirect.targetClass,
                    pendingDirect.targetMethod,
                    pendingDirect.targetDesc,
                    pendingDirect.texWidthOverride,
                    pendingDirect.texHeightOverride,
                    pendingLayerField,
                    pendingDirect.defaultInflate,
                    pendingDirect.defaultFloatParam,
                    pendingDirect.appliedMeshTransformerScale
                ));
                pendingLayerField = null;
                pendingDirect = null;
                pendingMesh = null;
                pendingInt = null;
                pendingAppliedMTScale = null;
            }
        }
        return out;
    }

    /**
     * Resolves a {@code static final MeshTransformer} field reference to its scaling factor F by
     * walking the owning class's {@code <clinit>}. Matches the canonical
     * {@code ldc F; invokestatic MeshTransformer.scaling(F); putstatic <name>} pattern.
     * Cat ({@code AdultCatModel.CAT_TRANSFORMER = scaling(0.8f)}) is the primary consumer; same
     * logic supports any per-class static MeshTransformer like dolphin / salmon / squid /
     * pufferfish family fields. Compound initialisations (multiple applies in {@code <clinit>},
     * {@code invokedynamic}-backed transformers such as {@code DonkeyModel.DONKEY_TRANSFORMER})
     * fall through and return {@code null}, causing the caller to leave
     * {@code appliedMeshTransformerScale} at its identity default of {@code 1f}.
     *
     * <p>Cached per-field across the entire {@code LayerDefinitions.createRoots} walk so a
     * single class's {@code <clinit>} is walked at most once even if multiple
     * {@code ModelLayers.X} entries reference its fields. Mirrors the parser-side
     * {@code resolveStaticMeshTransformer} walker inside
     * {@link lib.minecraft.renderer.tooling.parser.GeometryParser}; kept separate so the
     * resolver doesn't reach into parser internals.
     *
     * @param owner JVM internal name of the class declaring the {@code MeshTransformer} field
     * @param name the field name to resolve
     * @param cache the per-field resolution cache spanning the whole {@code createRoots} walk
     * @param classNodes the ClassNode cache (shared with sibling resolver walks)
     * @return F when the field's initialiser is a literal {@code MeshTransformer.scaling(F)},
     *     {@code null} for unhandled patterns
     */
    private static @Nullable Float resolveStaticMeshTransformer(
        @NotNull String owner, @NotNull String name,
        @NotNull Map<String, Float> cache, @NotNull ClassNodeCache classNodes
    ) {
        String key = owner + "." + name;
        if (cache.containsKey(key)) return cache.get(key);

        ClassNode cls = classNodes.load(owner);
        MethodNode clinit = cls != null ? AsmKit.findMethod(cls, AsmKit.CLINIT) : null;
        if (clinit == null) {
            cache.put(key, null);
            return null;
        }

        Float pendingFloat = null;
        Float pendingScaled = null;
        for (AbstractInsnNode in = clinit.instructions.getFirst(); in != null; in = in.getNext()) {
            int op = in.getOpcode();
            if (op < 0) continue;
            if (in instanceof LdcInsnNode ldc && ldc.cst instanceof Float f) {
                pendingFloat = f;
            } else if (in instanceof MethodInsnNode mi
                && op == Opcodes.INVOKESTATIC
                && VanillaSourceClasses.MESH_TRANSFORMER.equals(mi.owner)
                && "scaling".equals(mi.name)
                && ("(F)" + MESH_TRANSFORMER_DESC).equals(mi.desc)
                && pendingFloat != null) {
                pendingScaled = pendingFloat;
                pendingFloat = null;
            } else if (in instanceof FieldInsnNode fi
                && op == Opcodes.PUTSTATIC
                && MESH_TRANSFORMER_DESC.equals(fi.desc)
                && fi.owner.equals(owner)) {
                cache.put(owner + "." + fi.name, pendingScaled);
                pendingScaled = null;
                pendingFloat = null;
            } else {
                pendingFloat = null;
            }
        }

        cache.putIfAbsent(key, null);
        return cache.get(key);
    }

}
