package lib.minecraft.renderer.tooling.entity;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.tooling.util.AsmKit;
import lib.minecraft.renderer.tooling.util.Diagnostics;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipFile;

/**
 * Resolves an entity to the {@code LayerDefinition}-returning factory method that builds its
 * primary mesh. The lookup chain is:
 *
 * <ol>
 *   <li><b>Renderer constructor</b> -&gt; first {@code GETSTATIC ModelLayers.X} - the primary
 *       layer the renderer bakes via {@code context.bakeLayer(X)}. Subsequent layers
 *       (baby variants, armor model sets) are skipped at this phase since the entity's "main"
 *       geometry is the first one wired.</li>
 *   <li><b>{@code LayerDefinitions.createRoots}</b> map - resolves {@code ModelLayers.X} to a
 *       {@code (class, method, descriptor)} target via {@code Builder.put(X, factoryCall)}.
 *       Mirrors {@code SourceDiscovery.walkLayerDefinitions} for block entities; written here
 *       independently so the entity pipeline doesn't reach into block-entity internals.</li>
 *   <li><b>Mesh-wrapper unwrap</b> (TODO Phase C.5): some factories like
 *       {@code SkullModel.createMobHeadLayer} are thin {@code LayerDefinition.create(mesh, W, H)}
 *       wrappers around a {@code MeshDefinition} factory. Block entities handle this via
 *       {@code SourceDiscovery.unwrapMeshWrapper}; for entities the pattern is rare and deferred
 *       until parity surfaces an example.</li>
 * </ol>
 *
 * <p>The result is consumed by {@link ToolingJavaEntityModels} which feeds each
 * {@link Resolution} as a synthetic {@code Source} into
 * {@code ToolingBlockEntities.Parser.parse} - the same bytecode walker used by block entities,
 * since the {@code LayerDefinition.create} / {@code CubeListBuilder} / {@code PartPose} /
 * {@code addOrReplaceChild} bytecode patterns are identical between block and mob models.
 */
@UtilityClass
public final class JavaEntityLayerDefinitionResolver {

    /** JVM internal name of {@code net.minecraft.client.model.geom.LayerDefinitions}. */
    private static final @NotNull String LAYER_DEFINITIONS = "net/minecraft/client/model/geom/LayerDefinitions";

    /** JVM internal name of {@code net.minecraft.client.model.geom.ModelLayers}. */
    private static final @NotNull String MODEL_LAYERS = "net/minecraft/client/model/geom/ModelLayers";

    /** JVM internal name of {@code net.minecraft.client.model.geom.builders.LayerDefinition}. */
    private static final @NotNull String LAYER_DEFINITION_CLASS = "net/minecraft/client/model/geom/builders/LayerDefinition";

    /** Suffix of every {@code (...)LayerDefinition} method descriptor. */
    private static final @NotNull String LAYER_DEFINITION_DESC_RETURN = ")Lnet/minecraft/client/model/geom/builders/LayerDefinition;";

    /** Suffix of every {@code (...)MeshDefinition} method descriptor. */
    private static final @NotNull String MESH_DEFINITION_DESC_RETURN = ")Lnet/minecraft/client/model/geom/builders/MeshDefinition;";

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
     */
    public record Resolution(
        @NotNull String targetClass,
        @NotNull String targetMethod,
        @NotNull String targetDesc,
        @Nullable Integer texWidthOverride,
        @Nullable Integer texHeightOverride,
        @NotNull String sourceLayerField,
        float defaultInflate
    ) {

        /** Convenience constructor for resolutions whose factory takes no {@code CubeDeformation} arg. */
        public Resolution(
            @NotNull String targetClass,
            @NotNull String targetMethod,
            @NotNull String targetDesc,
            @Nullable Integer texWidthOverride,
            @Nullable Integer texHeightOverride,
            @NotNull String sourceLayerField
        ) {
            this(targetClass, targetMethod, targetDesc, texWidthOverride, texHeightOverride, sourceLayerField, 0f);
        }
    }

    /**
     * Resolves the primary {@code LayerDefinition} factory for the given renderer class.
     * Returns {@code null} when the renderer's constructor doesn't reference any
     * {@code ModelLayers.X} (e.g. {@code EnderDragonRenderer} which builds geometry procedurally)
     * or when the referenced layer has no entry in {@code LayerDefinitions.createRoots}.
     *
     * <p>Heuristic for "primary" layer field selection (Phase C.5 hardening):
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
     * @param zip the deobfuscated client jar
     * @param rendererInternalName the renderer's JVM internal name
     * @param entityId the namespaced entity id ({@code "minecraft:zombie"}); empty string disables
     *     the entity-id-match preference
     * @param layerDefinitions the precomputed {@code (ModelLayers.X field name -&gt; Resolution)}
     *     map from {@link #loadLayerDefinitions(ZipFile, Diagnostics)}
     * @param diagnostics the diagnostic sink shared with sibling discovery walks
     * @return the primary layer's resolution, or {@code null} when unresolvable
     */
    public static @Nullable Resolution resolvePrimary(
        @NotNull ZipFile zip,
        @NotNull String rendererInternalName,
        @NotNull String entityId,
        @NotNull java.util.Collection<String> additionalLayerFields,
        @NotNull Map<String, Resolution> layerDefinitions,
        @NotNull Diagnostics diagnostics
    ) {
        java.util.LinkedHashSet<String> candidates = collectModelLayerFields(zip, rendererInternalName);
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
        Resolution res = layerDefinitions.get(primaryLayerField);
        if (res == null) {
            diagnostics.info("renderer '%s' references ModelLayers.%s which is not in LayerDefinitions.createRoots - skipped", rendererInternalName, primaryLayerField);
            return null;
        }
        return res;
    }

    /**
     * Walks the renderer's constructor chain (including parent constructors) for every
     * {@code GETSTATIC ModelLayers.X} reference. The result preserves first-seen order so the
     * fallback "first field" heuristic remains deterministic, but the entity-id-match preference
     * can promote any matching field regardless of order.
     */
    private static @NotNull java.util.LinkedHashSet<String> collectModelLayerFields(
        @NotNull ZipFile zip,
        @NotNull String rendererInternalName
    ) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        String current = rendererInternalName;
        while (current != null && !"java/lang/Object".equals(current)) {
            ClassNode cn = AsmKit.loadClass(zip, current);
            if (cn == null) return out;
            for (MethodNode method : cn.methods) {
                if (!"<init>".equals(method.name)) continue;
                for (AbstractInsnNode in = method.instructions.getFirst(); in != null; in = in.getNext()) {
                    if (in.getOpcode() == Opcodes.GETSTATIC
                        && in instanceof FieldInsnNode fi
                        && MODEL_LAYERS.equals(fi.owner))
                        out.add(fi.name);
                }
            }
            current = cn.superName;
        }
        return out;
    }

    /**
     * Picks the primary layer field from the candidate set per the heuristic documented on
     * {@link #resolvePrimary}. Public for testing.
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
        return candidates.iterator().next();
    }

    /** {@code true} when the field name ends in a known variant suffix that disqualifies it as "primary". */
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
     * name -&gt; Resolution)} map. Mirrors the algorithm in
     * {@code SourceDiscovery.walkLayerDefinitions}: tracks {@code ImmutableMap.Builder.put(X, Y)}
     * pairs where {@code X} is a {@code GETSTATIC ModelLayers.<field>} and {@code Y} is either
     * an {@code INVOKESTATIC} returning {@code LayerDefinition} (or its mesh-wrapped variant)
     * or an {@code ALOAD} of a slot holding such a result.
     *
     * @param zip the deobfuscated client jar
     * @param diagnostics the diagnostic sink
     * @return the layer-name to factory-target map (empty on error)
     */
    public static @NotNull ConcurrentMap<String, Resolution> loadLayerDefinitions(
        @NotNull ZipFile zip,
        @NotNull Diagnostics diagnostics
    ) {
        ConcurrentMap<String, Resolution> out = Concurrent.newMap();
        ClassNode cn = AsmKit.loadClass(zip, LAYER_DEFINITIONS);
        if (cn == null) {
            diagnostics.error("'%s' class missing - layer-definition map unresolved", LAYER_DEFINITIONS);
            return out;
        }
        MethodNode createRoots = AsmKit.findMethod(cn, "createRoots");
        if (createRoots == null) {
            diagnostics.error("'%s.createRoots' missing - layer-definition map unresolved", LAYER_DEFINITIONS);
            return out;
        }

        Map<Integer, Resolution> slotState = new LinkedHashMap<>();
        String pendingLayerField = null;
        Resolution pendingDirect = null;
        Resolution pendingMesh = null;
        Integer pendingInt = null;
        Integer[] widthHeight = { null, null };
        // Tracks the inflate value of the most recent inline {@code new CubeDeformation(F);
        // <init>}. When the next {@code invokestatic <FactoryClass>.createBodyLayer
        // (CubeDeformation)} fires, this value rides into the {@link Resolution} so the synthetic
        // overlay {@code Source} carries it through to the parser as {@code defaultInflate}.
        // Reset on each new ModelLayers field so the value can't leak across registrations.
        Float pendingDeformationInflate = null;
        Float pendingFloat = null;

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
                && "<init>".equals(mi.name)
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

            if (in instanceof FieldInsnNode fi && opcode == Opcodes.GETSTATIC && MODEL_LAYERS.equals(fi.owner)) {
                pendingLayerField = fi.name;
                pendingDirect = null;
                pendingMesh = null;
                pendingInt = null;
                pendingDeformationInflate = null;
                pendingFloat = null;
                continue;
            }

            if (in instanceof MethodInsnNode mi && opcode == Opcodes.INVOKESTATIC) {
                if (mi.desc.endsWith(MESH_DEFINITION_DESC_RETURN)) {
                    pendingMesh = new Resolution(mi.owner, mi.name, mi.desc, null, null,
                        pendingLayerField == null ? "" : pendingLayerField,
                        pendingDeformationInflate != null ? pendingDeformationInflate : 0f);
                    pendingInt = null;
                    pendingDeformationInflate = null;
                    continue;
                }
                if (LAYER_DEFINITION_CLASS.equals(mi.owner) && "create".equals(mi.name) && pendingMesh != null) {
                    pendingDirect = new Resolution(
                        pendingMesh.targetClass,
                        pendingMesh.targetMethod,
                        pendingMesh.targetDesc,
                        widthHeight[0],
                        widthHeight[1],
                        pendingMesh.sourceLayerField,
                        pendingMesh.defaultInflate
                    );
                    pendingMesh = null;
                    continue;
                }
                if (mi.desc.endsWith(LAYER_DEFINITION_DESC_RETURN) && !LAYER_DEFINITION_CLASS.equals(mi.owner)) {
                    pendingDirect = new Resolution(mi.owner, mi.name, mi.desc, null, null,
                        pendingLayerField == null ? "" : pendingLayerField,
                        pendingDeformationInflate != null ? pendingDeformationInflate : 0f);
                    pendingDeformationInflate = null;
                }
                continue;
            }

            if (in instanceof VarInsnNode vi && opcode == Opcodes.ASTORE && pendingDirect != null) {
                slotState.put(vi.var, pendingDirect);
                pendingDirect = null;
                continue;
            }

            if (in instanceof VarInsnNode vi && opcode == Opcodes.ALOAD) {
                Resolution stored = slotState.get(vi.var);
                if (stored != null) pendingDirect = stored;
                continue;
            }

            if (in instanceof MethodInsnNode mi
                && opcode == Opcodes.INVOKEVIRTUAL
                && "put".equals(mi.name)
                && mi.owner.endsWith("ImmutableMap$Builder")
                && pendingLayerField != null
                && pendingDirect != null) {
                out.put(pendingLayerField, new Resolution(
                    pendingDirect.targetClass,
                    pendingDirect.targetMethod,
                    pendingDirect.targetDesc,
                    pendingDirect.texWidthOverride,
                    pendingDirect.texHeightOverride,
                    pendingLayerField,
                    pendingDirect.defaultInflate
                ));
                pendingLayerField = null;
                pendingDirect = null;
                pendingMesh = null;
                pendingInt = null;
            }
        }
        return out;
    }

}
