package lib.minecraft.renderer.tooling2.entity;

import lib.minecraft.renderer.tooling2.geometry.GeometryManifest;
import lib.minecraft.renderer.tooling2.geometry.GeometryRequest;
import lib.minecraft.renderer.tooling2.kernel.AsmKit;
import lib.minecraft.renderer.tooling2.kernel.ClassNodeCache;
import lib.minecraft.renderer.tooling2.kernel.Diagnostics;
import lib.minecraft.renderer.tooling2.kernel.VanillaSourceClasses;
import lib.minecraft.renderer.tooling2.vanilla.LayerDefinitionIndex;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Node {@code geometry} - resolves THE primary body mesh, registers its
 * {@link GeometryRequest} with the manifest, and emits the factory-coordinate key
 * (SPINE 3.1 row 2). Pure registration, no parsing here.
 *
 * <p>The primary-layer pick is <b>dataflow, not name heuristics</b> [D35] - retiring the
 * legacy 7-suffix denylist: the primary layer is the one whose {@code bakeLayer} result
 * flows into the renderer's model constructor, matched as the first
 * {@code <layer source>; INVOKEVIRTUAL Context.bakeLayer; INVOKESPECIAL <model>.<init>}
 * triple in the renderer constructor chain (adult models are constructed before baby /
 * saddle models in every vanilla ctor). Three layer-source shapes resolve:
 * {@code GETSTATIC ModelLayers.X} (wolf / cow), {@code ALOAD <param>; GETFIELD $Type.model}
 * mapped through the lambda-supplied Type constant's {@code <clinit>} binding (donkey
 * family), and {@code ALOAD <param>} of a {@code ModelLayerLocation} parameter mapped
 * positionally onto the lambda's {@code ModelLayers} references (squid / piglin class -
 * the field name appears only in the {@code EntityRenderers.<clinit>} lambda).
 *
 * <p>When no triple resolves, the ordered lambda references are tried directly (the lambda
 * body constructs the model in vanilla arg order, so its first indexed field is the adult
 * mesh) - a degraded pick recorded at INFO. Entity request constants ({@code YAxis.DOWN},
 * no inventory rotation, {@code null} refParam - P3) are baked into
 * {@link GeometryRequest#body}. Delegate unaliasing already happened at index build.
 */
final class EntityGeometryRefResolver {

    private final @NotNull ClassNodeCache cache;
    private final @NotNull EntitySubject subject;
    private final @NotNull LayerDefinitionIndex layerDefinitions;
    private final @NotNull GeometryManifest manifest;
    private final @NotNull Diagnostics diagnostics;

    /** The resolved index entry, available to sibling resolvers (bones) after {@link #resolve}. */
    private @Nullable LayerDefinitionIndex.Entry resolved;

    /** The registered body request - the bone resolver re-parses it for toggle-subtree expansion. */
    private @Nullable GeometryRequest request;

    EntityGeometryRefResolver(
        @NotNull ClassNodeCache cache,
        @NotNull EntitySubject subject,
        @NotNull LayerDefinitionIndex layerDefinitions,
        @NotNull GeometryManifest manifest,
        @NotNull Diagnostics diagnostics
    ) {
        this.cache = cache;
        this.subject = subject;
        this.layerDefinitions = layerDefinitions;
        this.manifest = manifest;
        this.diagnostics = diagnostics;
    }

    /**
     * Resolves the primary layer, registers the body request, and returns the manifest key -
     * or {@code null} (key omitted, ERROR recorded) when no layer resolves.
     *
     * @return the factory-coordinate key, or {@code null} for unresolvables
     */
    @Nullable String resolve() {
        String layerField = pickPrimaryLayerField();
        if (layerField == null) {
            this.diagnostics.error("no primary ModelLayers field resolvable for renderer '%s' - geometry omitted",
                this.subject.rendererClass());
            return null;
        }
        LayerDefinitionIndex.Entry entry = this.layerDefinitions.get(layerField);
        if (entry == null) {
            this.diagnostics.error("primary layer ModelLayers.%s has no LayerDefinitions.createRoots entry - geometry omitted", layerField);
            return null;
        }
        this.resolved = entry;
        this.request = GeometryRequest.body(
            entry.factoryClass(), entry.factoryMethod(), this.subject.entityId(),
            entry.texWidthOverride(), entry.texHeightOverride(),
            entry.floatParam(), entry.appliedMeshTransformerScale());
        return this.manifest.register(this.request);
    }

    /**
     * The index entry the primary layer resolved to, or {@code null} before {@link #resolve}
     * or for unresolvables. The bone resolver reads the model class off it.
     */
    @Nullable LayerDefinitionIndex.Entry resolvedEntry() {
        return this.resolved;
    }

    /**
     * The registered body request, or {@code null} before {@link #resolve} or for
     * unresolvables.
     */
    @Nullable GeometryRequest registeredRequest() {
        return this.request;
    }

    // ------------------------------------------------------------------------------------
    // primary pick [D35]
    // ------------------------------------------------------------------------------------

    private @Nullable String pickPrimaryLayerField() {
        // Dataflow arm: the first bakeLayer-into-model-ctor triple in the ctor chain, with
        // ModelLayerLocation PARAMETERS resolved positionally against the fields the caller
        // one level down pushed - the lambda for the leaf (squid), the subclass ctor for an
        // abstract renderer (skeleton), a same-class delegating ctor for zombie / spider.
        List<String> sites = new ArrayList<>();
        List<String> bindings = mllTypedLambdaFields();
        String current = this.subject.rendererClass();
        while (current != null && !AsmKit.OBJECT_INTERNAL.equals(current)) {
            ClassNode cn = this.cache.load(current);
            if (cn == null) break;
            List<String> levelPushes = new ArrayList<>();
            for (MethodNode ctor : cn.methods) {
                if (!AsmKit.INIT.equals(ctor.name)) continue;
                collectBakedModelLayers(ctor, levelPushes, bindings, sites);
            }
            if (!levelPushes.isEmpty()) bindings = levelPushes;
            current = cn.superName;
        }
        for (String field : sites)
            if (this.layerDefinitions.get(field) != null) return field;

        // Degraded arm: the lambda's ordered ModelLayers references (vanilla constructs the
        // adult model first, so its first resolvable field is the body mesh), then the
        // Type-enum augmentation for lambdas that push only equipment layers directly.
        LinkedHashSet<String> candidates = new LinkedHashSet<>(this.subject.lambdaLayerFields());
        candidates.addAll(typeAugmentedLayerFields());
        for (String field : candidates) {
            if (this.layerDefinitions.get(field) == null) continue;
            this.diagnostics.info("primary layer via lambda-reference order: ModelLayers.%s (no ctor bake triple resolved)", field);
            return field;
        }
        return null;
    }

    /**
     * Scans one ctor, appending every {@code ModelLayerLocation}-typed
     * {@code GETSTATIC ModelLayers.X} to {@code pushes} (the positional bindings the ctors
     * this one delegates into resolve their location parameters against) and the resolved
     * field of every {@code <source>; bakeLayer; <model>.<init>} triple to {@code sites}.
     * The model gate is the P29 package test ({@code net/minecraft/client/model/}, never
     * {@code geom/}) applied to the {@code <init>} owner consuming the baked part.
     */
    private void collectBakedModelLayers(
        @NotNull MethodNode ctor,
        @NotNull List<String> pushes,
        @NotNull List<String> bindings,
        @NotNull List<String> sites
    ) {
        Type[] args = AsmKit.argTypes(ctor.desc);
        String mllRef = VanillaSourceClasses.Descs.ref(VanillaSourceClasses.Types.MODEL_LAYER_LOCATION);
        for (AbstractInsnNode in = ctor.instructions.getFirst(); in != null; in = in.getNext()) {
            if (AsmKit.isGetStatic(in, VanillaSourceClasses.Types.MODEL_LAYERS)
                && in instanceof FieldInsnNode push
                && mllRef.equals(push.desc)) {
                pushes.add(push.name);
                continue;
            }
            if (!AsmKit.isInvokeVirtual(in, VanillaSourceClasses.Types.RENDERER_PROVIDER_CONTEXT, VanillaSourceClasses.Methods.BAKE_LAYER)) continue;
            AbstractInsnNode next = AsmKit.nextReal(in);
            if (!(next instanceof MethodInsnNode init) || next.getOpcode() != Opcodes.INVOKESPECIAL
                || !AsmKit.INIT.equals(init.name) || !isModelClass(init.owner)) continue;
            String field = resolveLayerSource(AsmKit.previousReal(in), args, pushes, bindings);
            if (field != null) sites.add(field);
        }
    }

    /**
     * The lambda's {@code ModelLayers} references narrowed to
     * {@code ModelLayerLocation}-typed fields (the lambda also pushes armor-set fields off
     * the same registry class) - the leaf-level positional bindings.
     */
    private @NotNull List<String> mllTypedLambdaFields() {
        ClassNode modelLayers = this.cache.load(VanillaSourceClasses.Types.MODEL_LAYERS);
        if (modelLayers == null) return this.subject.lambdaLayerFields();
        String mllRef = VanillaSourceClasses.Descs.ref(VanillaSourceClasses.Types.MODEL_LAYER_LOCATION);
        List<String> out = new ArrayList<>();
        for (String field : this.subject.lambdaLayerFields()) {
            var node = AsmKit.findField(modelLayers, field);
            if (node != null && mllRef.equals(node.desc)) out.add(field);
        }
        return out;
    }

    /** The P29 positive package gate: a model class, never a geometry primitive. */
    private static boolean isModelClass(@NotNull String internalName) {
        return internalName.startsWith(VanillaSourceClasses.Types.CLIENT_MODEL_ROOT)
            && !internalName.startsWith(VanillaSourceClasses.Types.CLIENT_MODEL_GEOM_ROOT);
    }

    /**
     * Resolves the instruction pushing {@code bakeLayer}'s argument to a {@code ModelLayers}
     * field name, handling the three vanilla layer-source shapes. Returns {@code null} for
     * unrecognised sources.
     */
    private @Nullable String resolveLayerSource(
        @Nullable AbstractInsnNode source,
        Type @NotNull [] ctorArgs,
        @NotNull List<String> levelPushes,
        @NotNull List<String> bindings
    ) {
        if (source == null) return null;
        if (AsmKit.isGetStatic(source, VanillaSourceClasses.Types.MODEL_LAYERS))
            return ((FieldInsnNode) source).name;

        // ALOAD <param>; GETFIELD $Type.model:LModelLayerLocation; - donkey family: map the
        // parameter slot to the lambda-supplied Type constant, then read the constant's
        // <clinit> ModelLayers binding.
        if (source.getOpcode() == Opcodes.GETFIELD
            && source instanceof FieldInsnNode typeField
            && typeField.owner.endsWith("$Type")
            && VanillaSourceClasses.Descs.ref(VanillaSourceClasses.Types.MODEL_LAYER_LOCATION).equals(typeField.desc)
            && AsmKit.previousReal(source) instanceof VarInsnNode receiver
            && receiver.getOpcode() == Opcodes.ALOAD) {
            EntitySubject.TypeFieldRef constant = typeArgAtSlot(receiver.var, ctorArgs, typeField.owner);
            if (constant == null) return null;
            return typeConstantModelLayerMap(constant.owner()).get(constant.name());
        }

        // ALOAD <param> of a ModelLayerLocation parameter: the Nth location parameter pairs
        // with the caller's Nth location push - same-class delegating ctors first (zombie /
        // spider), then the one-level-down bindings (skeleton's subclass ctor, squid's lambda).
        if (source.getOpcode() == Opcodes.ALOAD && source instanceof VarInsnNode load) {
            Integer index = paramIndexOfType(load.var, ctorArgs, VanillaSourceClasses.Types.MODEL_LAYER_LOCATION);
            if (index == null) return null;
            if (index < levelPushes.size()) return levelPushes.get(index);
            if (index < bindings.size()) return bindings.get(index);
        }
        return null;
    }

    /**
     * The lambda-supplied Type constant bound to the ctor parameter at {@code slot} - the
     * Nth {@code $Type}-typed parameter pairs with the Nth {@code $Type} constant the lambda
     * pushed (lambda GETSTATIC order is call-site argument order).
     */
    private @Nullable EntitySubject.TypeFieldRef typeArgAtSlot(int slot, Type @NotNull [] ctorArgs, @NotNull String typeOwner) {
        Integer index = paramIndexOfType(slot, ctorArgs, typeOwner);
        if (index == null) return null;
        int seen = 0;
        for (EntitySubject.TypeFieldRef ref : this.subject.lambdaTypeArgs()) {
            if (!ref.owner().equals(typeOwner)) continue;
            if (seen == index) return ref;
            seen++;
        }
        return null;
    }

    /**
     * Maps a local-variable {@code slot} to its zero-based index among the instance method's
     * parameters OF the given reference type, or {@code null} when the slot is not such a
     * parameter.
     */
    private static @Nullable Integer paramIndexOfType(int slot, Type @NotNull [] args, @NotNull String internalName) {
        int current = 1;   // slot 0 = this
        int index = 0;
        for (Type arg : args) {
            boolean matches = arg.getSort() == Type.OBJECT && arg.getInternalName().equals(internalName);
            if (current == slot) return matches ? index : null;
            if (matches) index++;
            current += arg.getSize();
        }
        return null;
    }

    /**
     * The {@code ModelLayers} fields bound per Type constant in the lambda's Type-enum owners
     * (each constant's {@code <clinit>} init carries a {@code GETSTATIC ModelLayers.X} before
     * its {@code PUTSTATIC}) - the degraded-arm augmentation for lambdas that expose only an
     * equipment layer directly.
     */
    private @NotNull List<String> typeAugmentedLayerFields() {
        List<String> out = new ArrayList<>();
        LinkedHashSet<String> owners = new LinkedHashSet<>();
        for (EntitySubject.TypeFieldRef ref : this.subject.lambdaTypeArgs()) owners.add(ref.owner());
        for (String owner : owners) {
            Map<String, String> byConstant = typeConstantModelLayerMap(owner);
            for (EntitySubject.TypeFieldRef ref : this.subject.lambdaTypeArgs()) {
                String field = byConstant.get(ref.name());
                if (field != null) out.add(field);
            }
        }
        return out;
    }

    /**
     * Walks a {@code $Type} enum's {@code <clinit>} pairing each {@code GETSTATIC
     * ModelLayers.X} with the following {@code PUTSTATIC <CONSTANT>} and returns the
     * constant-to-field map (empty when the class or pattern is absent).
     */
    private @NotNull Map<String, String> typeConstantModelLayerMap(@NotNull String typeOwner) {
        ClassNode cn = this.cache.load(typeOwner);
        if (cn == null) return Map.of();
        MethodNode clinit = AsmKit.findMethod(cn, AsmKit.CLINIT);
        if (clinit == null) return Map.of();

        Map<String, String> out = new LinkedHashMap<>();
        String pendingModelLayer = null;
        for (AbstractInsnNode in = clinit.instructions.getFirst(); in != null; in = in.getNext()) {
            if (AsmKit.isGetStatic(in, VanillaSourceClasses.Types.MODEL_LAYERS)) {
                pendingModelLayer = ((FieldInsnNode) in).name;
                continue;
            }
            if (AsmKit.isPutStatic(in, typeOwner) && pendingModelLayer != null) {
                out.put(((FieldInsnNode) in).name, pendingModelLayer);
                pendingModelLayer = null;
            }
        }
        return out;
    }

}
