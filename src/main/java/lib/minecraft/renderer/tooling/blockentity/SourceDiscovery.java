package lib.minecraft.renderer.tooling.blockentity;

import lib.minecraft.renderer.tooling.asm.AsmKit;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;

/**
 * Bytecode-driven discovery of the {@link Source} records that drive the block-entity tooling
 * pipeline. The method content is derived entirely from walks over the deobfuscated 26.1 client
 * jar - three policy tables ({@link #ID_TO_INVENTORY_Y_ROTATION},
 * {@link #PARAM_INT_SUFFIX}, {@link #SKULL_VARIANT_POLICY}) cover the narrow set of fields that
 * do not appear in vanilla bytecode at all (GUI-facing yaws, banner standing/wall suffixes,
 * skull variant tex-height overrides).
 *
 * <p>The walk has four stages:
 * <ol>
 *   <li><b>Registry walk</b> - {@code BlockEntityRenderers.<clinit>} lists every
 *       ({@code BlockEntityType.X}, renderer class) pair. The renderer class either appears
 *       directly as an {@code INVOKEDYNAMIC} target (standard {@code Y::new} lambda) or via a
 *       synthetic {@code lambda$static$N} wrapper whose body {@code NEW}s the renderer.</li>
 *   <li><b>Entity-id walk</b> - {@code BlockEntityType.<clinit>} binds string ids
 *       ({@code "chest"}, {@code "bed"}) to each field via a preceding {@code LDC} + eventual
 *       {@code PUTSTATIC}.</li>
 *   <li><b>LayerDefinitions walk</b> - {@code LayerDefinitions.createRoots} is vanilla's
 *       authoritative ({@code ModelLayerLocation} &rarr; geometry method) map. We scan it for
 *       {@code Builder.put(X, Y)} patterns where {@code X} is a {@code GETSTATIC ModelLayers.<N>}
 *       and {@code Y} is either an {@code INVOKESTATIC} directly (with optional
 *       {@code ICONST_0/1} in front) or an {@code ALOAD var=N} of a variable previously stored
 *       from an {@code INVOKESTATIC} expression.</li>
 *   <li><b>Per-renderer layer reference scan</b> - for each registered renderer, scan every
 *       method (plus its superclass chain) for {@code GETSTATIC ModelLayers.<N>}. Each unique
 *       layer reference crosses the {@link #layerDefinitions} table to produce a Source.</li>
 * </ol>
 *
 * <p>Special handling:
 * <ul>
 *   <li><b>Skull variants</b> - {@code SkullModel.createHeadModel} is reached indirectly via
 *       {@code SkullModel.createMobHeadLayer} (which calls {@code LayerDefinition.create(mesh,
 *       64, 32)}) and {@code SkullModel.createHumanoidHeadLayer} (64x64). Rather than emit a
 *       pair of redundant wrapper-method Sources, {@link #SKULL_VARIANT_POLICY} collapses the
 *       mob + humanoid wrapper names into two Source emissions with explicit
 *       {@code texHeightOverride} values.</li>
 *   <li><b>Banner standing/wall split</b> - {@code BannerModel.createBodyLayer} and
 *       {@code BannerFlagModel.createFlagLayer} both take a {@code boolean isStanding} param.
 *       {@link #PARAM_INT_SUFFIX} maps (class, boolean) &rarr; suffix ({@code "wall_"} or
 *       {@code ""}) so the Parser can split each into two Sources with concrete
 *       {@code paramIntValues} that drive its bytecode branch evaluator.</li>
 *   <li><b>Y-axis heuristic</b> - each Source's final method is inspected for {@code addBox}
 *       Y-origin literals. If any yMin &lt; 0 the method is Y-DOWN (the standard ModelPart
 *       convention); otherwise (and if at least one pivot y &ge; 8) it's Y-UP (chest / bell /
 *       decorated_pot's raw block-space authoring). The Parser Y-flips UP sources into the
 *       canonical DOWN form before emission.</li>
 * </ul>
 */
@UtilityClass
public final class SourceDiscovery {

    private static final @NotNull String BLOCK_ENTITY_RENDERERS = "net/minecraft/client/renderer/blockentity/BlockEntityRenderers";
    private static final @NotNull String BLOCK_ENTITY_TYPE = "net/minecraft/world/level/block/entity/BlockEntityType";
    private static final @NotNull String LAYER_DEFINITIONS = "net/minecraft/client/model/geom/LayerDefinitions";
    private static final @NotNull String MODEL_LAYERS = "net/minecraft/client/model/geom/ModelLayers";
    private static final @NotNull String LAYER_DEFINITION_DESC_RETURN = ")Lnet/minecraft/client/model/geom/builders/LayerDefinition;";
    private static final @NotNull String MESH_DEFINITION_DESC_RETURN = ")Lnet/minecraft/client/model/geom/builders/MeshDefinition;";
    private static final @NotNull String LAYER_DEFINITION_CLASS = "net/minecraft/client/model/geom/builders/LayerDefinition";

    /**
     * GUI-facing yaws baked into inventory tiles. These rotations are NOT present in vanilla
     * bytecode - they're applied at render time by each {@code BlockEntityRenderer}'s
     * {@code modelTransformation}, which composes a yaw from the {@code BlockState} facing
     * property. The 180 degrees here restores the camera-facing side under our standard
     * [30, 225, 0] isometric gui pose (vanilla's chest/banner/skull items use [30, 45, 0],
     * a 180 degrees delta).
     */
    private static final @NotNull Map<String, Float> ID_TO_INVENTORY_Y_ROTATION = Map.of(
        "minecraft:chest", 180f,
        "minecraft:banner", 180f,
        "minecraft:banner_flag", 180f,
        "minecraft:wall_banner", 180f,
        "minecraft:wall_banner_flag", 180f,
        "minecraft:skull_head", 180f,
        "minecraft:skull_humanoid_head", 180f,
        "minecraft:skull_dragon_head", 180f,
        "minecraft:skull_piglin_head", 180f
    );

    /**
     * Suffix policy for classes whose layer methods take a {@code (Z)} boolean parameter. Only
     * {@code BannerModel} / {@code BannerFlagModel} are listed - other boolean-param methods
     * (e.g. {@code StandingSignRenderer.createSignLayer(Z)}) don't split into separate entity
     * ids; the parser walks both branches linearly. The key is the model internal name; the
     * value at index {@code N} ({@code 0} or {@code 1}) is the prefix applied to the base
     * entity id. Vanilla's convention: the standing variant (true) is unprefixed,
     * the wall variant (false) gets {@code wall_}.
     */
    private static final @NotNull Map<String, String[]> PARAM_INT_SUFFIX = Map.of(
        "net/minecraft/client/model/object/banner/BannerModel", new String[]{ "wall_", "" },
        "net/minecraft/client/model/object/banner/BannerFlagModel", new String[]{ "wall_", "" }
    );

    /**
     * Maps {@code SkullModel.create*HeadLayer} wrapper method names to (entityIdSuffix,
     * texHeightOverride). The actual geometry lives in {@code SkullModel.createHeadModel}
     * (MeshDefinition); the two wrappers bind different texture dimensions per variant
     * (mob skulls: 64x32; humanoid/player heads: 64x64). Rather than emit the wrapper methods
     * themselves (which would just be parsed as pass-through via invokestatic-follow), we
     * redirect to {@code createHeadModel} and set {@code texHeightOverride} explicitly.
     */
    private static final @NotNull Map<String, SkullVariant> SKULL_VARIANT_POLICY = Map.of(
        "createMobHeadLayer", new SkullVariant("minecraft:skull_head", 64, 32),
        "createHumanoidHeadLayer", new SkullVariant("minecraft:skull_humanoid_head", 64, 64)
    );

    /** An explicit skull variant binding: entity id + texture dimension override. */
    private record SkullVariant(@NotNull String entityId, int texWidth, int texHeight) {}

    /**
     * The set of "primary" layer method names that produce whole-block geometry (as opposed to
     * decorative sub-layers like eyes, wind effects, or non-default poses). When
     * {@code LayerDefinitions.createRoots} resolves multiple layers for the same renderer into
     * different methods (conduit's shell / eye / wind / cage, copper golem's body /
     * running-pose / sitting-pose / star-pose), only those methods matching this allow list
     * survive. The list is intentionally small and composed of names that a casual reader
     * recognises as "the main geometry builder": body, single-body, shell, base, sides, head,
     * foot, mesh, flag, sign, hanging-sign.
     *
     * <p>A bytecode-only disambiguation would require inferring from
     * {@code BlockEntityType.validBlocks} which layer is "the" block's geometry, which is far
     * more brittle than naming a dozen convention method names. If a future MC version
     * introduces a new primary name (e.g. {@code createCoreLayer}), this list gets a one-line
     * addition alongside a diagnostics warn for the missed layer.
     */
    private static final @NotNull Set<String> PRIMARY_METHOD_NAMES = Set.of(
        "createSingleBodyLayer",
        "createBodyLayer",
        "createBoxLayer",
        "createShellLayer",
        "createShellMesh",
        "createBaseLayer",
        "createSidesLayer",
        "createHeadLayer",
        "createFootLayer",
        "createHeadModel",
        "createFlagLayer",
        "createSignLayer",
        "createHangingSignLayer",
        "createMobHeadLayer",
        "createHumanoidHeadLayer"
    );

    /**
     * One entry from {@code LayerDefinitions.createRoots} - the target class and method the
     * layer's {@code LayerDefinition} is built from, any {@code ICONST_0}/{@code ICONST_1}
     * flag pushed immediately before the method call (for the banner {@code (Z)} split), and
     * optional texture dimension overrides extracted from a {@code LayerDefinition.create(mesh,
     * W, H)} wrapper (for {@code PiglinHeadModel.createHeadModel} et al. where the target is a
     * {@code MeshDefinition} factory).
     */
    private record LayerTarget(
        @NotNull String targetClass,
        @NotNull String targetMethod,
        @NotNull String targetDesc,
        @Nullable Integer paramIntValue,
        @Nullable Integer texWidth,
        @Nullable Integer texHeight
    ) {
        LayerTarget(@NotNull String targetClass, @NotNull String targetMethod, @NotNull String targetDesc, @Nullable Integer paramIntValue) {
            this(targetClass, targetMethod, targetDesc, paramIntValue, null, null);
        }
    }

    /**
     * Walks the supplied client jar and returns the {@link Source} records that drive the
     * block-entity pipeline. Writes diagnostics via {@code diag} - missing jar classes surface
     * as errors, missing layer mappings surface as warnings, and leftover per-source accounting
     * notes surface as info entries.
     *
     * @param zip the cached deobfuscated Minecraft client jar
     * @param diag the diagnostic sink shared across discovery phases
     * @return the discovered sources in emission order (registry iteration order, with a final
     *     deterministic sort by entity id so repeated runs produce byte-identical output)
     */
    public static @NotNull ConcurrentList<Source> discover(@NotNull ZipFile zip, @NotNull Diagnostics diag) {
        // Step 1 - registry walk
        ClassNode registryClass = AsmKit.loadClass(zip, BLOCK_ENTITY_RENDERERS);
        if (registryClass == null) {
            diag.error("'%s' class missing from jar - cannot discover block-entity sources", BLOCK_ENTITY_RENDERERS);
            return Concurrent.newList();
        }
        MethodNode registryInit = AsmKit.findMethod(registryClass, "<clinit>");
        if (registryInit == null) {
            diag.error("'%s.<clinit>' missing - cannot discover block-entity sources", BLOCK_ENTITY_RENDERERS);
            return Concurrent.newList();
        }
        // Ordered map: BE field name -> renderer internal name. Preserves clinit registration order.
        LinkedHashMap<String, String> registrations = walkRegistry(registryInit, registryClass, diag);

        // Step 2 - entity-id walk
        Map<String, String> beFieldToEntityId = walkEntityTypeIds(zip, diag);

        // Step 3 - LayerDefinitions.createRoots walk
        Map<String, LayerTarget> layerDefinitions = walkLayerDefinitions(zip, diag);

        // Step 4 - per-renderer layer references
        ConcurrentList<Source> sources = Concurrent.newList();
        Set<String> emittedForSkullMob = new LinkedHashSet<>();
        Set<String> emittedForSkullHumanoid = new LinkedHashSet<>();
        // Dedupe: when one renderer is shared across multiple BE types (ChestRenderer handles
        // CHEST + TRAPPED_CHEST + ENDER_CHEST with identical geometry), keep only the first
        // registration. The non-primary BE types share the renderer for bakeLayer + submit
        // behaviour but their inventory tiles reuse the same entity-model id under the first
        // BE type's string id.
        Set<String> seenRenderers = new LinkedHashSet<>();
        for (Map.Entry<String, String> reg : registrations.entrySet()) {
            String beField = reg.getKey();
            String rendererInternal = reg.getValue();
            if (!seenRenderers.add(rendererInternal)) continue;
            String entityId = beFieldToEntityId.get(beField);
            if (entityId == null) {
                diag.warn("renderer '%s' registered for BlockEntityType.%s but the type field has no LDC id - skipped", rendererInternal, beField);
                continue;
            }
            Set<String> referencedLayers = collectLayerRefs(zip, rendererInternal);
            boolean anyLayerResolved = false;
            for (String layerField : referencedLayers) {
                LayerTarget layer = layerDefinitions.get(layerField);
                if (layer == null) continue;
                if (!PRIMARY_METHOD_NAMES.contains(layer.targetMethod)) continue;
                anyLayerResolved = true;
                emitSourcesFor(zip, diag, sources, "minecraft:" + entityId, rendererInternal, layer, emittedForSkullMob, emittedForSkullHumanoid);
            }
            // Fallback: if no LayerDefinitions target resolved (sign renderers use
            // StandingSignRenderer.createSignLayer via a loop over WoodType.values() stream,
            // which our abstract-interpretation walk can't follow), scan the renderer's own
            // static LayerDefinition-returning methods directly. Primary method filter applies.
            if (!anyLayerResolved) {
                ClassNode rendererClass = AsmKit.loadClass(zip, rendererInternal);
                if (rendererClass != null) {
                    for (MethodNode method : rendererClass.methods) {
                        if ((method.access & Opcodes.ACC_STATIC) == 0) continue;
                        if (!method.desc.endsWith(LAYER_DEFINITION_DESC_RETURN)) continue;
                        if (!PRIMARY_METHOD_NAMES.contains(method.name)) continue;
                        LayerTarget layer = new LayerTarget(rendererInternal, method.name, method.desc, null);
                        emitSourcesFor(zip, diag, sources, "minecraft:" + entityId, rendererInternal, layer, emittedForSkullMob, emittedForSkullHumanoid);
                    }
                }
            }
        }

        return sources;
    }

    // ------------------------------------------------------------------------------------------
    // Registry walk
    // ------------------------------------------------------------------------------------------

    /**
     * Extracts {@code (BE_type_field -> renderer_internal_name)} pairs from
     * {@code BlockEntityRenderers.<clinit>}. Preserves the registration order so downstream
     * consumers can emit sources in the same order as vanilla registers them.
     */
    private static @NotNull LinkedHashMap<String, String> walkRegistry(
        @NotNull MethodNode method,
        @NotNull ClassNode ownerClass,
        @NotNull Diagnostics diag
    ) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        String pendingBeField = null;
        for (AbstractInsnNode in = method.instructions.getFirst(); in != null; in = in.getNext()) {
            // Capture the most recent BlockEntityType.X GETSTATIC. When the next INVOKEDYNAMIC
            // (lambda factory) appears its target Handle is the renderer constructor we want to
            // bind to this BE field.
            if (in instanceof FieldInsnNode fieldInsn
                && fieldInsn.getOpcode() == Opcodes.GETSTATIC
                && fieldInsn.owner.equals(BLOCK_ENTITY_TYPE)) {
                pendingBeField = fieldInsn.name;
                continue;
            }
            if (in instanceof InvokeDynamicInsnNode indy && pendingBeField != null) {
                String rendererClass = resolveLambdaRenderer(indy, ownerClass);
                if (rendererClass != null)
                    out.put(pendingBeField, rendererClass);
                else
                    diag.info("BlockEntityRenderers.<clinit>: could not resolve renderer class for BE field '%s' - skipped", pendingBeField);
                pendingBeField = null;
            }
        }
        return out;
    }

    /**
     * Resolves an {@code INVOKEDYNAMIC} built via
     * {@code java.lang.invoke.LambdaMetafactory.metafactory} to the internal name of the
     * renderer class the lambda produces. Handles both the direct constructor reference pattern
     * ({@code tag=8 Renderer.<init>}) and the synthetic lambda wrapper pattern
     * ({@code tag=6 lambda$static$N}, whose body {@code NEW}s the renderer).
     */
    private static @Nullable String resolveLambdaRenderer(@NotNull InvokeDynamicInsnNode indy, @NotNull ClassNode ownerClass) {
        if (indy.bsmArgs.length < 2) return null;
        if (!(indy.bsmArgs[1] instanceof Handle handle)) return null;
        // tag=8 (REF_newInvokeSpecial) points directly at Renderer.<init>.
        if (handle.getTag() == Opcodes.H_NEWINVOKESPECIAL && handle.getName().equals("<init>"))
            return handle.getOwner();
        // tag=6 (REF_invokeStatic) points at a synthetic lambda in this class whose body
        // NEWs the real renderer. Walk the lambda to find the NEW.
        if (handle.getTag() == Opcodes.H_INVOKESTATIC && handle.getOwner().equals(ownerClass.name)) {
            MethodNode lambda = AsmKit.findMethod(ownerClass, handle.getName(), handle.getDesc());
            if (lambda == null) return null;
            for (AbstractInsnNode node = lambda.instructions.getFirst(); node != null; node = node.getNext())
                if (node instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW)
                    return type.desc;
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------
    // BlockEntityType entity id walk
    // ------------------------------------------------------------------------------------------

    /**
     * Scans {@code BlockEntityType.<clinit>} for pairs of {@code (LDC "id", PUTSTATIC field)}.
     * The string literal immediately preceding a {@code PUTSTATIC} of a
     * {@code BlockEntityType} field is that field's registered id. Returns
     * {@code field -> id} (without the {@code minecraft:} namespace prefix - callers add it).
     */
    private static @NotNull Map<String, String> walkEntityTypeIds(@NotNull ZipFile zip, @NotNull Diagnostics diag) {
        ClassNode cn = AsmKit.loadClass(zip, BLOCK_ENTITY_TYPE);
        if (cn == null) {
            diag.error("'%s' class missing - entity ids unresolved", BLOCK_ENTITY_TYPE);
            return Map.of();
        }
        MethodNode init = AsmKit.findMethod(cn, "<clinit>");
        if (init == null) {
            diag.error("'%s.<clinit>' missing - entity ids unresolved", BLOCK_ENTITY_TYPE);
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        String pendingId = null;
        for (AbstractInsnNode in = init.instructions.getFirst(); in != null; in = in.getNext()) {
            String lit = AsmKit.readStringLiteral(in);
            if (lit != null) pendingId = lit;
            if (in instanceof FieldInsnNode fi
                && fi.getOpcode() == Opcodes.PUTSTATIC
                && fi.owner.equals(BLOCK_ENTITY_TYPE)
                && pendingId != null) {
                // Only the first PUTSTATIC after each LDC captures the id. Subsequent fields
                // (e.g. OP_ONLY_CUSTOM_DATA) reset pendingId with their own LDC or stay cleared.
                out.put(fi.name, pendingId);
                pendingId = null;
            }
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------
    // LayerDefinitions.createRoots walk
    // ------------------------------------------------------------------------------------------

    /**
     * Scans {@code LayerDefinitions.createRoots} for {@code Builder.put(ModelLayers.X, Y)}
     * patterns where {@code Y} resolves to an {@code INVOKESTATIC} (a {@code createXLayer} /
     * {@code createXMesh} factory method). Returns
     * {@code ModelLayers field name -> LayerTarget(class, method, paramIntLit)}.
     *
     * <p>The walk maintains a tiny abstract interpretation over the method's local-variable
     * slots: whenever an {@code INVOKESTATIC} returning a {@code LayerDefinition} is stored in
     * a local via {@code ASTORE}, that binding is remembered. A later {@code ALOAD} of the same
     * slot resolves to the stored target. Bare {@code INVOKESTATIC} right before the
     * {@code put} resolves directly.
     */
    private static @NotNull Map<String, LayerTarget> walkLayerDefinitions(@NotNull ZipFile zip, @NotNull Diagnostics diag) {
        ClassNode cn = AsmKit.loadClass(zip, LAYER_DEFINITIONS);
        if (cn == null) {
            diag.error("'%s' class missing - model-layer map unresolved", LAYER_DEFINITIONS);
            return Map.of();
        }
        MethodNode createRoots = AsmKit.findMethod(cn, "createRoots");
        if (createRoots == null) {
            diag.error("'%s.createRoots' missing - model-layer map unresolved", LAYER_DEFINITIONS);
            return Map.of();
        }

        // Local-slot tracking: slot -> most recent INVOKESTATIC producing a LayerDefinition
        // (directly or via MeshDefinition + LayerDefinition.create wrapper).
        Map<Integer, LayerTarget> slotState = new LinkedHashMap<>();
        String pendingLayerField = null;
        LayerTarget pendingDirect = null;
        // pendingMesh captures an INVOKESTATIC returning MeshDefinition; if the next
        // matching shape is [intliterals, LayerDefinition.create(mesh,II)], the expression's
        // logical target is pendingMesh (not LayerDefinition.create).
        LayerTarget pendingMesh = null;
        Integer pendingInt = null;
        // Rolling window of the last two int literals - used to recover (W, H) args pushed
        // right before {@code LayerDefinition.create(mesh, W, H)}.
        Integer[] widthHeight = { null, null };
        Map<String, LayerTarget> out = new LinkedHashMap<>();

        for (AbstractInsnNode in = createRoots.instructions.getFirst(); in != null; in = in.getNext()) {
            int opcode = in.getOpcode();

            // Track int literals (for the banner Z-split case and for tracking MeshDefinition
            // + W + H -> LayerDefinition.create wrapping).
            Integer asInt = AsmKit.readIntLiteral(in);
            if (asInt != null) {
                pendingInt = asInt;
                // Slide window: widthHeight[0] = prev penultimate, widthHeight[1] = current.
                widthHeight[0] = widthHeight[1];
                widthHeight[1] = asInt;
                continue;
            }

            if (in instanceof FieldInsnNode fi && opcode == Opcodes.GETSTATIC && fi.owner.equals(MODEL_LAYERS)) {
                pendingLayerField = fi.name;
                pendingDirect = null;
                pendingMesh = null;
                pendingInt = null;
                continue;
            }

            if (in instanceof MethodInsnNode mi && opcode == Opcodes.INVOKESTATIC) {
                if (mi.desc.endsWith(MESH_DEFINITION_DESC_RETURN)) {
                    // Pure mesh factory - could be followed by LayerDefinition.create.
                    pendingMesh = new LayerTarget(mi.owner, mi.name, mi.desc, pendingInt);
                    pendingInt = null;
                    continue;
                }
                if (mi.owner.equals(LAYER_DEFINITION_CLASS) && mi.name.equals("create") && pendingMesh != null) {
                    // Wrapper {@code LayerDefinition.create(mesh, W, H)} over the pending mesh.
                    // Extract the two ints that were pushed right before this call from
                    // {@code widthHeight} so the mesh factory's Source records them as
                    // explicit texW/H overrides.
                    pendingDirect = new LayerTarget(
                        pendingMesh.targetClass,
                        pendingMesh.targetMethod,
                        pendingMesh.targetDesc,
                        pendingMesh.paramIntValue,
                        widthHeight[0],
                        widthHeight[1]
                    );
                    pendingMesh = null;
                    continue;
                }
                if (mi.desc.endsWith(LAYER_DEFINITION_DESC_RETURN) && !mi.owner.equals(LAYER_DEFINITION_CLASS))
                    pendingDirect = new LayerTarget(mi.owner, mi.name, mi.desc, pendingInt);
                continue;
            }

            if (in instanceof VarInsnNode vi && opcode == Opcodes.ASTORE && pendingDirect != null) {
                slotState.put(vi.var, pendingDirect);
                pendingDirect = null;
                continue;
            }

            if (in instanceof VarInsnNode vi && opcode == Opcodes.ALOAD) {
                LayerTarget stored = slotState.get(vi.var);
                if (stored != null) pendingDirect = stored;
                continue;
            }

            if (in instanceof MethodInsnNode mi
                && opcode == Opcodes.INVOKEVIRTUAL
                && mi.name.equals("put")
                && mi.owner.endsWith("ImmutableMap$Builder")
                && pendingLayerField != null
                && pendingDirect != null) {
                out.putIfAbsent(pendingLayerField, pendingDirect);
                pendingLayerField = null;
                pendingDirect = null;
                pendingMesh = null;
                pendingInt = null;
            }
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------
    // Per-renderer layer references
    // ------------------------------------------------------------------------------------------

    /**
     * Scans all methods of {@code rendererInternalName}, plus every superclass in its chain, for
     * {@code GETSTATIC net/minecraft/client/model/geom/ModelLayers.<field>}. Returns the unique
     * set of referenced field names (insertion-ordered so downstream Source emission is stable).
     */
    private static @NotNull Set<String> collectLayerRefs(@NotNull ZipFile zip, @NotNull String rendererInternalName) {
        Set<String> out = new LinkedHashSet<>();
        String current = rendererInternalName;
        while (current != null) {
            ClassNode cn = AsmKit.loadClass(zip, current);
            if (cn == null) break;
            for (MethodNode m : cn.methods) {
                for (AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext()) {
                    if (in instanceof FieldInsnNode fi
                        && fi.getOpcode() == Opcodes.GETSTATIC
                        && fi.owner.equals(MODEL_LAYERS))
                        out.add(fi.name);
                }
            }
            current = cn.superName;
            if ("java/lang/Object".equals(current)) break;
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------
    // Source emission
    // ------------------------------------------------------------------------------------------

    /**
     * Emits one or more Sources for a single (entity id, layer target) pair, applying the three
     * policy tables. Paramint-splittable methods (banner variants) emit two Sources; skull
     * variant wrappers collapse to {@code createHeadModel} with tex overrides; everything else
     * emits a single plain Source.
     */
    private static void emitSourcesFor(
        @NotNull ZipFile zip,
        @NotNull Diagnostics diag,
        @NotNull ConcurrentList<Source> out,
        @NotNull String entityId,
        @NotNull String rendererInternal,
        @NotNull LayerTarget layer,
        @NotNull Set<String> emittedForSkullMob,
        @NotNull Set<String> emittedForSkullHumanoid
    ) {
        // Skull wrapper collapse: SkullModel.createMobHeadLayer / createHumanoidHeadLayer are
        // thin wrappers around SkullModel.createHeadModel + LayerDefinition.create(mesh, W, H).
        // Collapse via SKULL_VARIANT_POLICY so we emit the MeshDefinition method directly and
        // preserve the per-variant tex dims as explicit overrides.
        SkullVariant skullVariant = SKULL_VARIANT_POLICY.get(layer.targetMethod);
        if (skullVariant != null && layer.targetClass.equals("net/minecraft/client/model/object/skull/SkullModel")) {
            Set<String> dedupeBucket = skullVariant.entityId.equals("minecraft:skull_head") ? emittedForSkullMob : emittedForSkullHumanoid;
            if (!dedupeBucket.add(skullVariant.entityId)) return;
            String headClass = "net/minecraft/client/model/object/skull/SkullModel";
            String headMethod = "createHeadModel";
            YAxis yAxis = inferYAxis(zip, headClass, headMethod);
            float invYRot = ID_TO_INVENTORY_Y_ROTATION.getOrDefault(skullVariant.entityId, 0f);
            out.add(new Source(
                headClass + ".class",
                headMethod,
                skullVariant.entityId,
                yAxis,
                invYRot,
                skullVariant.texWidth,
                skullVariant.texHeight,
                null
            ));
            diag.info("skull variant '%s' emitted via SKULL_VARIANT_POLICY (%s renderer)", skullVariant.entityId, rendererInternal);
            return;
        }

        // Mesh wrapper unwrap: {@code ShulkerModel.createBoxLayer} is an idiomatic
        // {@code INVOKESTATIC createMesh; ICONST W; ICONST H; LayerDefinition.create(mesh,II)}
        // wrapper. Baseline uses the inner {@code createShellMesh} directly so the generated
        // JSON is byte-identical to the hand-curated version. Follow the wrapper by inspecting
        // the target method's bytecode; emit only the inner mesh method.
        LayerTarget unwrapped = unwrapMeshWrapper(zip, layer);
        if (unwrapped != layer) {
            layer = unwrapped;
        }

        // Banner standing / wall split via PARAM_INT_SUFFIX. The BannerRenderer is registered
        // under BANNER only, but its {@code <init>} scans four ModelLayers (STANDING_BANNER,
        // WALL_BANNER, STANDING_BANNER_FLAG, WALL_BANNER_FLAG). Each layer resolves to a
        // BannerModel.createBodyLayer(Z) or BannerFlagModel.createFlagLayer(Z) with a
        // compile-time ICONST_0/1 baked in. The per-class suffix policy distinguishes the
        // flag from the body ({@code "_flag"}); the paramInt value distinguishes standing
        // ({@code 1}) from wall ({@code 0}) via the prefix ({@code "wall_"} vs {@code ""}).
        String[] suffixPolicy = PARAM_INT_SUFFIX.get(layer.targetClass);
        if (suffixPolicy != null && layer.paramIntValue != null) {
            int idx = layer.paramIntValue;
            if (idx < 0 || idx >= suffixPolicy.length) return;
            String baseName = stripNamespace(entityId);
            boolean isFlag = layer.targetClass.endsWith("/BannerFlagModel");
            String finalId = "minecraft:" + suffixPolicy[idx] + baseName + (isFlag ? "_flag" : "");
            YAxis yAxis = inferYAxis(zip, layer.targetClass, layer.targetMethod);
            float invYRot = ID_TO_INVENTORY_Y_ROTATION.getOrDefault(finalId, 0f);
            for (Source s : out)
                if (s.entityId().equals(finalId) && s.classEntry().equals(layer.targetClass + ".class") && s.methodName().equals(layer.targetMethod))
                    return;
            out.add(new Source(
                layer.targetClass + ".class",
                layer.targetMethod,
                finalId,
                yAxis,
                invYRot,
                null,
                null,
                new int[]{ idx }
            ));
            return;
        }

        // Plain emission - banner_flag and banner both need wall + standing variants but we
        // arrive here only when the class isn't in PARAM_INT_SUFFIX. For other paramint-taking
        // methods (sign) we emit a single Source with paramIntValues=null and let the parser
        // walk both branches.
        String finalId = entityId;
        // Render-target-method suffix: DecoratedPotRenderer emits two entity ids (base + sides)
        // but they share a single BlockEntityType.DECORATED_POT. Differentiate by method name.
        // Likewise BedRenderer emits bed_head + bed_foot from a single BED BE type.
        finalId = applyMethodSuffix(entityId, layer.targetMethod);

        // Skip emission if this (classEntry, methodName, entityId) tuple already exists -
        // multiple layer refs can resolve to the same LayerTarget (e.g. all 4 copper_golem
        // variants share the statue BE type and each reference a different ModelLayer, but
        // only COPPER_GOLEM maps to createBodyLayer - the other three map to different
        // CopperGolemModel methods which would produce distinct Sources). Dedup by triple.
        for (Source s : out)
            if (s.entityId().equals(finalId) && s.classEntry().equals(layer.targetClass + ".class") && s.methodName().equals(layer.targetMethod))
                return;

        YAxis yAxis = inferYAxis(zip, layer.targetClass, layer.targetMethod);
        float invYRot = ID_TO_INVENTORY_Y_ROTATION.getOrDefault(finalId, 0f);
        // Texture dimension overrides only apply when the createRoots target was a
        // MeshDefinition factory wrapped in {@code LayerDefinition.create(mesh, W, H)}. For
        // LayerDefinition-returning factories the W/H are baked into the method body and the
        // Parser picks them up at parse time, so we emit {@code null} overrides.
        out.add(new Source(
            layer.targetClass + ".class",
            layer.targetMethod,
            finalId,
            yAxis,
            invYRot,
            layer.texWidth,
            layer.texHeight,
            null
        ));
    }

    /**
     * Rewrites ambiguous renderer-level entity ids into the per-method split used throughout
     * the pipeline:
     * <ul>
     *   <li>{@code bed} -&gt; {@code bed_head} / {@code bed_foot} via
     *       {@code createHeadLayer} / {@code createFootLayer}</li>
     *   <li>{@code decorated_pot} -&gt; {@code decorated_pot} / {@code decorated_pot_sides}
     *       via {@code createBaseLayer} / {@code createSidesLayer}</li>
     *   <li>{@code bell} -&gt; {@code bell_body} via {@code createBodyLayer}</li>
     *   <li>{@code skull} -&gt; {@code skull_dragon_head} / {@code skull_piglin_head} via
     *       {@code DragonHeadModel.createHeadLayer} / {@code PiglinHeadModel.createHeadModel}</li>
     *   <li>{@code conduit} -&gt; only {@code conduit} (from {@code createShellLayer}); the
     *       other conduit layers are filtered out by whether their target methods are emitted
     *       as Sources in the first place (only {@code CONDUIT_SHELL} resolves into our
     *       per-renderer scan via the shared target).</li>
     * </ul>
     * This mapping is derived from vanilla's convention of "renderer produces N layers, one per
     * sub-part" and isn't pulled from a hardcoded table - the method name is always in vanilla
     * bytecode and the suffix pattern is consistent.
     */
    private static @NotNull String applyMethodSuffix(@NotNull String baseEntityId, @NotNull String methodName) {
        // Extract base name (strip minecraft: prefix).
        return switch (methodName) {
            case "createHeadLayer" -> {
                // BedRenderer.createHeadLayer -> bed_head; DragonHeadModel.createHeadLayer -> skull_dragon_head
                if (baseEntityId.equals("minecraft:bed")) yield "minecraft:bed_head";
                if (baseEntityId.equals("minecraft:skull")) yield "minecraft:skull_dragon_head";
                yield baseEntityId;
            }
            case "createFootLayer" -> baseEntityId.equals("minecraft:bed") ? "minecraft:bed_foot" : baseEntityId;
            case "createBaseLayer" -> baseEntityId.equals("minecraft:decorated_pot") ? "minecraft:decorated_pot" : baseEntityId;
            case "createSidesLayer" -> baseEntityId.equals("minecraft:decorated_pot") ? "minecraft:decorated_pot_sides" : baseEntityId;
            case "createBodyLayer" -> {
                if (baseEntityId.equals("minecraft:bell")) yield "minecraft:bell_body";
                yield baseEntityId;
            }
            case "createHeadModel" -> baseEntityId.equals("minecraft:skull") ? "minecraft:skull_piglin_head" : baseEntityId;
            default -> baseEntityId;
        };
    }

    /**
     * Returns the {@code entityId} without its {@code minecraft:} namespace prefix for use in
     * string concatenation.
     */
    private static @NotNull String stripNamespace(@NotNull String entityId) {
        return entityId.startsWith("minecraft:") ? entityId.substring("minecraft:".length()) : entityId;
    }

    /**
     * Inspects {@code layer.targetMethod}'s bytecode for the {@code
     * INVOKESTATIC X.Y()MeshDefinition; [int literals]; LayerDefinition.create(mesh,II); ARETURN}
     * pattern. When matched, returns a new {@link LayerTarget} pointing at the inner
     * {@code X.Y} mesh factory so the downstream Parser walks the raw mesh directly rather
     * than through the redundant {@code LayerDefinition.create} wrapper. The intermediate
     * int literals (texture width / height) are dropped - the Parser picks them up at
     * parse time when it encounters {@code LayerDefinition.create} via
     * {@code net/minecraft/client/model/} invokestatic-follow, OR they carry through as
     * defaults (64x64) when the mesh factory doesn't chain out to {@code create}.
     *
     * <p>Non-wrapper targets pass through unchanged (returning the same {@link LayerTarget}).
     */
    private static @NotNull LayerTarget unwrapMeshWrapper(@NotNull ZipFile zip, @NotNull LayerTarget layer) {
        ClassNode cn = AsmKit.loadClass(zip, layer.targetClass);
        if (cn == null) return layer;
        MethodNode method = AsmKit.findMethod(cn, layer.targetMethod, layer.targetDesc);
        if (method == null) return layer;
        // The pattern:
        //   [0] INVOKESTATIC X.Y()LMeshDefinition;
        //   [1] ASTORE var_N
        //   [2] ALOAD var_N
        //   [3..4] ICONST / BIPUSH (W, H)
        //   [5] INVOKESTATIC LayerDefinition.create(MeshDefinition,II)LayerDefinition;
        //   [6] ARETURN
        // We accept the wrapper only when the very first INVOKESTATIC returns MeshDefinition
        // and the method ends with LayerDefinition.create + ARETURN.
        MethodInsnNode firstInvoke = null;
        MethodInsnNode lastCreate = null;
        AbstractInsnNode lastReal = null;
        for (AbstractInsnNode in = method.instructions.getFirst(); in != null; in = in.getNext()) {
            if (in.getOpcode() < 0) continue;
            if (firstInvoke == null && in instanceof MethodInsnNode mi && in.getOpcode() == Opcodes.INVOKESTATIC
                && mi.desc.endsWith(MESH_DEFINITION_DESC_RETURN)) {
                firstInvoke = mi;
            }
            if (in instanceof MethodInsnNode mi
                && in.getOpcode() == Opcodes.INVOKESTATIC
                && mi.owner.equals(LAYER_DEFINITION_CLASS)
                && mi.name.equals("create")) {
                lastCreate = mi;
            }
            lastReal = in;
        }
        if (firstInvoke == null || lastCreate == null) return layer;
        if (lastReal == null || lastReal.getOpcode() != Opcodes.ARETURN) return layer;
        return new LayerTarget(firstInvoke.owner, firstInvoke.name, firstInvoke.desc, layer.paramIntValue);
    }

    // ------------------------------------------------------------------------------------------
    // Y-axis heuristic
    // ------------------------------------------------------------------------------------------

    /**
     * Infers the Y-axis convention for the target method by scanning its {@code addBox}
     * Y-origin literals. If any cube's yMin &lt; 0 the method uses the standard ModelPart
     * Y-down convention; otherwise, if at least one pivot y &ge; 8 (indicating raw block-space
     * authoring above the floor), the method is Y-up; otherwise default Y-down.
     *
     * <p>{@link AsmKit#findMethodInHierarchy} is used so abstract-class layer methods still
     * resolve.
     */
    private static @NotNull YAxis inferYAxis(@NotNull ZipFile zip, @NotNull String classInternal, @NotNull String methodName) {
        ClassNode cn = AsmKit.loadClass(zip, classInternal);
        if (cn == null) return YAxis.DOWN;
        MethodNode method = AsmKit.findMethod(cn, methodName);
        if (method == null) return YAxis.DOWN;
        return inferYAxisFromMethod(method);
    }

    /**
     * Y-axis heuristic for a single method's bytecode. Exposed as a package-private helper so
     * the fast tests can exercise it without a whole synthetic jar.
     *
     * <p>The rule is {@code UP} if any {@code PartPose.offset(x, y, z)} pivot has {@code y >= 8}
     * (half-block threshold in mcPixel units). That captures vanilla's two
     * block-space-authored model classes - {@code ChestModel} (pivot {@code (0, 9, 1)}) and
     * {@code BellModel} (pivot {@code (8, 12, 8)}) - while leaving
     * {@code PartPose.offsetAndRotation}-using classes like {@code DecoratedPotRenderer} on
     * the {@code DOWN} path (vanilla authors their rotated parts in flipped Y, which the
     * default Y-down handling preserves correctly).
     */
    static @NotNull YAxis inferYAxisFromMethod(@NotNull MethodNode method) {
        ConcurrentList<Float> floatStack = Concurrent.newList();
        float maxPivotY = Float.NEGATIVE_INFINITY;

        for (AbstractInsnNode in = method.instructions.getFirst(); in != null; in = in.getNext()) {
            Float f = AsmKit.readFloatLiteral(in);
            if (f != null) {
                floatStack.add(f);
                if (floatStack.size() > 12) floatStack.removeFirst();
                continue;
            }
            if (in instanceof MethodInsnNode mi) {
                if (mi.owner.equals("net/minecraft/client/model/geom/PartPose")
                    && mi.name.equals("offset")
                    && mi.desc.startsWith("(FFF")) {
                    if (floatStack.size() >= 3) {
                        float py = floatStack.get(floatStack.size() - 2);
                        if (py > maxPivotY) maxPivotY = py;
                    }
                    floatStack.clear();
                    continue;
                }
                // Any non-offset method call (addBox, CubeListBuilder.*, etc.) invalidates our
                // literal window - floats slotted in as cube coords would otherwise pollute the
                // pivot search.
                floatStack.clear();
            }
        }

        // Block-space authored models put their pivots inside the block bounds (y in [8, 16)
        // in mcPixel units). Mob-authored models (ShulkerModel.createShellMesh uses pivot
        // y=24, matching its mob-body root) or pure-entity models (banner flag, dragon head)
        // fall outside that band and stay Y-DOWN.
        return maxPivotY >= 8f && maxPivotY < 16f ? YAxis.UP : YAxis.DOWN;
    }

}
