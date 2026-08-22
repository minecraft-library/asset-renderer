package lib.minecraft.renderer.tooling.entity;

import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.tooling.geometry.GeometryManifest;
import lib.minecraft.renderer.tooling.geometry.GeometryRequest;
import lib.minecraft.renderer.tooling.kernel.ClassKit;
import lib.minecraft.renderer.tooling.kernel.ClassNodeCache;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lib.minecraft.renderer.tooling.kernel.VanillaSourceClasses;
import lib.minecraft.renderer.tooling.vanilla.LayerDefinitionIndex;
import lib.minecraft.renderer.tooling.walk.AsmWalker;
import lib.minecraft.renderer.tooling.walk.Cells;
import lib.minecraft.renderer.tooling.walk.CommitWalk;
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
 * The equipment side of the {@code layers[]} node - one row per saddle / body-armor layer
 * a renderer attaches. Candidates come from the roster site's call-site window (a
 * {@code LayerType} static opening the candidate, the following {@code ModelLayers} statics
 * carrying the adult and baby meshes) or from a bespoke layer's own class internals (wolf
 * armor, llama decor).
 *
 * <p>The render layer reads the {@code EquipmentClientInfo$LayerType.<clinit>} id literal; its
 * {@code material -> asset id} table and default material come from the inverted equipment corpus
 * ({@link EquipmentAssetIndex}), a sole-material layer naming its own default and a multi-material
 * one consulting {@link EntityOverlayPolicies#defaultMaterialFor}.
 *
 * <p>A row also carries its own {@code bones}, because the layer poses its mesh with a model class
 * of its own and gates its own bones - a saddle's reins draw only while something is riding. That
 * class is the one the layer is handed, never the one the mesh factory is declared on: a donkey's
 * saddle is baked by {@code DonkeyModel} and posed by {@code EquineSaddleModel}, and reading the
 * factory's own class instead answers the wearer's chest gate for a mesh with reins.
 */
final class EntityEquipmentResolver {

    private final @NotNull ClassNodeCache cache;
    private final @NotNull EntitySubject subject;
    private final @NotNull LayerDefinitionIndex layerDefinitions;
    private final @NotNull EquipmentAssetIndex equipmentAssets;
    private final @NotNull GeometryManifest manifest;
    private final @NotNull EntityBoneResolver bones;
    private final @NotNull Diagnostics diagnostics;

    EntityEquipmentResolver(@NotNull EntityContext context) {
        this.cache = context.cache();
        this.subject = context.subject();
        this.layerDefinitions = context.indexes().layerDefinitions();
        this.equipmentAssets = context.indexes().equipmentAssets();
        this.manifest = context.indexes().manifest();
        this.bones = new EntityBoneResolver(context.scope("bones"));
        this.diagnostics = context.diagnostics();
    }

    /**
     * The equipment row of a call-site candidate: the window's {@code LayerType} static
     * names the subdir, the following {@code ModelLayers} statics the adult (first) and
     * baby (second) meshes.
     *
     * <p>A renderer may instead take both as constructor parameters, so the window loads them
     * rather than naming them ({@code DonkeyRenderer}, {@code UndeadHorseRenderer} - shared by
     * two entities each, so the values cannot live in the class). Such a window carries no
     * statics at all and falls through to {@link #registrationRow}.
     *
     * @param site the roster site the row belongs to
     * @param windowStart the first instruction of the site's call-site window
     * @return the row, or {@code null} when the window carries no equipment candidate
     */
    @Nullable JsonTree resolveCallSite(
        @NotNull EntityRendererResolver.LayerSite site,
        @NotNull AbstractInsnNode windowStart
    ) {
        Cells.Latch<String> layerType = Cells.latch();
        Cells.ListCell<String> meshFields = Cells.list();
        Cells.Flag parameterisedLayerType = Cells.flag();
        Cells.Flag parameterisedMesh = Cells.flag();
        // Every re-latch of the candidate type clears the gathered meshes - the ModelLayers
        // statics that follow a LayerType belong to that candidate alone.
        AsmWalker.from(windowStart).until(site.addLayer())
            .on(Insn.getStatic(VanillaSourceClasses.Types.EQUIPMENT_LAYER_TYPE), fi -> {
                layerType.set(fi.name);
                meshFields.clear();
            })
            .on(Insn.getStatic(VanillaSourceClasses.Types.MODEL_LAYERS).and(fi -> layerType.get() != null),
                fi -> meshFields.add(fi.name))
            .on(Insn.of(VarInsnNode.class, load -> load.getOpcode() == Opcodes.ALOAD), load -> {
                if (AsmWalker.isParameterOfType(
                    site.method(), load.var, VanillaSourceClasses.Types.EQUIPMENT_LAYER_TYPE))
                    parameterisedLayerType.set();
                if (AsmWalker.isParameterOfType(
                    site.method(), load.var, VanillaSourceClasses.Types.MODEL_LAYER_LOCATION))
                    parameterisedMesh.set();
            })
            .run();
        if (layerType.get() == null && parameterisedLayerType.get() && parameterisedMesh.get())
            return registrationRow(site);
        List<String> meshes = meshFields.values();
        if (layerType.get() == null || meshes.isEmpty()) return null;
        return buildRow(site, layerType.get(), meshes.getFirst(), meshes.size() > 1 ? meshes.get(1) : null);
    }

    /**
     * The equipment row of a site whose layer type and mesh are constructor parameters: both
     * come from the subject's own renderer registration, which is where a renderer shared by
     * several entities gets each one's distinct pair (donkey vs mule, skeleton vs zombie horse).
     * Keyed off the subject rather than the renderer class, so the pairs never cross.
     *
     * <p>Requires exactly one candidate of each - the registration of a renderer that
     * parameterises its layer type passes one layer type and one mesh - and refuses to guess
     * otherwise. No baby mesh: every such site passes a null baby model.
     */
    private @Nullable JsonTree registrationRow(@NotNull EntityRendererResolver.LayerSite site) {
        String layerType = sole(this.subject.lambdaEquipmentLayerTypes(), "layer type");
        String mesh = sole(this.subject.lambdaLayerFields(), "mesh");
        if (layerType == null || mesh == null) return null;
        this.diagnostics.info("equipment layer type + mesh are constructor parameters - registration supplies %s/%s",
            layerType, mesh);
        return buildRow(site, layerType, mesh, null);
    }

    /**
     * The single member of a registration candidate list, or {@code null} with a WARN when the
     * list does not hold exactly one - an ambiguous registration is not guessed at.
     */
    private @Nullable String sole(@NotNull List<String> candidates, @NotNull String what) {
        if (candidates.size() == 1) return candidates.getFirst();
        this.diagnostics.warn("renderer registration offers %d candidate %ss %s - equipment row dropped",
            candidates.size(), what, candidates);
        return null;
    }

    /**
     * The equipment row of a bespoke layer: the class's own first {@code LayerType} +
     * {@code ModelLayers} references (wolf armor, llama decor).
     *
     * @param site the roster site the row belongs to
     * @param cn the bespoke layer class
     * @return the row, or {@code null} when the pair cannot be resolved
     */
    @Nullable JsonTree resolveBespoke(@NotNull EntityRendererResolver.LayerSite site, @NotNull ClassNode cn) {
        // Both first-wins reads span every method of the class, so the pair lives in locals
        // written from the walks rather than in per-run cells. The first non-baby ModelLayers
        // field is the adult mesh.
        String[] layerType = {null};
        String[] meshField = {null};
        for (MethodNode method : cn.methods)
            AsmWalker.over(method)
                .on(Insn.getStatic(VanillaSourceClasses.Types.EQUIPMENT_LAYER_TYPE)
                        .and(fi -> layerType[0] == null),
                    fi -> layerType[0] = fi.name)
                .on(Insn.getStatic(VanillaSourceClasses.Types.MODEL_LAYERS)
                        .and(fi -> meshField[0] == null && !fi.name.contains("BABY")),
                    fi -> meshField[0] = fi.name)
                .run();
        if (layerType[0] == null || meshField[0] == null) return null;
        return buildRow(site, layerType[0], meshField[0], null);
    }

    /**
     * Assembles one {@code layers[]} row: {@code id} is the slot, the gate is
     * {@code when: {equipment: <slot>}}, and the overlay body carries the registered
     * adult mesh, the bones its model class gates, the render layer, its
     * {@code material -> asset id} table, the derived or declared default material, and the
     * captured baby mesh.
     */
    private @Nullable JsonTree buildRow(
        @NotNull EntityRendererResolver.LayerSite site,
        @NotNull String layerTypeConstant,
        @NotNull String adultField,
        @Nullable String babyField
    ) {
        String layerTypeId = layerTypeSubdir(this.cache, layerTypeConstant);
        if (layerTypeId == null) {
            this.diagnostics.warn("LayerType.%s has no <clinit> id literal - equipment row dropped", layerTypeConstant);
            return null;
        }
        // The layers-row slot vocabulary is {saddle, body}; mob-equipment layer ids follow
        // the <mob>_<slot> grammar. A LayerType outside it (wings, humanoid armor) is
        // player-style runtime equipment, never a static-pose row.
        String slot = layerTypeId.endsWith("_saddle") ? "saddle" : layerTypeId.endsWith("_body") ? "body" : null;
        if (slot == null) {
            this.diagnostics.info("LayerType id '%s' outside the mob-equipment slot grammar - no row", layerTypeId);
            return null;
        }
        Map<String, String> materials = this.equipmentAssets.materials(layerTypeId);
        if (materials.isEmpty()) {
            // Every material the render could select would resolve to no layers, so the mesh could
            // only ever draw untextured while still inflating the canvas bounds.
            this.diagnostics.warn("layer '%s' has no equipment asset declaring it - row dropped", layerTypeId);
            return null;
        }
        GeometryRequest adultRequest = meshRequest(adultField);
        if (adultRequest == null) {
            this.diagnostics.info("equipment mesh ModelLayers.%s unresolved - row dropped", adultField);
            return null;
        }
        String adultKey = this.manifest.register(adultRequest);
        Map<String, JsonTree> materialAssets = new LinkedHashMap<>();
        materials.forEach((material, assetId) -> materialAssets.put(material, JsonTree.of(assetId)));
        JsonTree overlay = JsonTree.object()
            .put("geometry", adultKey)
            .putIf("bones", layerBones(site, adultRequest))
            .put("layer_type", layerTypeId)
            .put("default_material", defaultMaterial(layerTypeId, materials))
            .put("material_assets", materialAssets);
        if (babyField != null) {
            String babyKey = registerMesh(babyField);
            if (babyKey != null) overlay.put("baby_geometry", babyKey);
        }
        this.diagnostics.info("equipment row '%s' (%s) meshes adult=%s baby=%s over %d materials",
            slot, layerTypeId, adultField, babyField, materials.size());
        return JsonTree.object()
            .put("source", EntityOverlayResolver.simpleName(site.layerClass()))
            .putInt("layer_index", site.layerIndex())
            .put("id", slot)
            .put("when", JsonTree.object().put("equipment", slot))
            .put("overlay", overlay);
    }

    /** Registers an equipment mesh request off its index entry, or {@code null} when unindexed. */
    private @Nullable String registerMesh(@NotNull String meshField) {
        GeometryRequest request = meshRequest(meshField);
        return request == null ? null : this.manifest.register(request);
    }

    /** The request an equipment mesh's index entry describes, or {@code null} when unindexed. */
    private @Nullable GeometryRequest meshRequest(@NotNull String meshField) {
        LayerDefinitionIndex.Entry entry = this.layerDefinitions.get(meshField);
        if (entry == null) return null;
        return GeometryRequest.equipment(
            entry.factoryClass(), entry.factoryMethod(), entry.factoryDesc(), this.subject.entityId(),
            entry.texWidthOverride(), entry.texHeightOverride(), entry.floatParam(),
            entry.grow(), entry.appliedMeshTransformerScale());
    }

    /**
     * The {@code bones} node the layer's own model class declares over the row's mesh, or
     * {@code null} when it declares none or the class cannot be recovered.
     *
     * @param site the roster site the row belongs to
     * @param mesh the row's adult mesh, which the toggles are expanded and filtered against
     * @return the node, or {@code null} to omit
     */
    private @Nullable JsonTree layerBones(
        @NotNull EntityRendererResolver.LayerSite site, @NotNull GeometryRequest mesh) {

        String modelClass = layerModelClass(site);
        if (modelClass == null) {
            this.diagnostics.info("layer '%s' allocates no model - no bones", ClassKit.simpleName(site.layerClass()));
            return null;
        }
        return this.bones.resolve(modelClass, mesh);
    }

    /**
     * The model class a layer poses its mesh with: the first model allocated as part of the layer's
     * own construction.
     *
     * <p>Three spellings, one rule. A layer built inline holds its model in its own argument region,
     * which is what the window covers; one produced by a factory helper builds it in that helper's
     * body; and a bespoke layer builds it in its own constructor. So the window is read first and
     * the body the site's allocation names is read when the window carries none, which is the same
     * hop for the last two. Models the renderer allocated for itself sit before the layer's
     * allocation and are outside all three regions.
     *
     * @param site the roster site the row belongs to
     * @return the model's internal name, or {@code null} when no region allocates one
     */
    private @Nullable String layerModelClass(@NotNull EntityRendererResolver.LayerSite site) {
        String inWindow = firstModelAllocation(AsmWalker.from(site.allocation()).until(site.addLayer()));
        if (inWindow != null) return inWindow;
        MethodNode body = site.allocation() instanceof MethodInsnNode factory
            ? ClassKit.findMethodInHierarchy(this.cache, factory.owner, factory.name, factory.desc)
            : layerConstructor(site.layerClass());
        return body == null ? null : firstModelAllocation(AsmWalker.over(body));
    }

    /** The named layer class's constructor, or {@code null} when the class or it is missing. */
    private @Nullable MethodNode layerConstructor(@NotNull String layerClass) {
        ClassNode cn = this.cache.load(layerClass);
        return cn == null ? null : ClassKit.findMethod(cn, ClassKit.INIT);
    }

    /** The first {@code EntityModel} subclass the walk allocates, or {@code null} when it allocates none. */
    private @Nullable String firstModelAllocation(@NotNull AsmWalker walk) {
        return walk.firstNotNull(node -> node.getOpcode() == Opcodes.NEW
            && node instanceof TypeInsnNode type
            && ClassKit.extendsClass(this.cache, type.desc, VanillaSourceClasses.Types.ENTITY_MODEL)
            ? type.desc : null);
    }

    /**
     * The material substituted when a render selects the slot without naming one: the sole
     * material when the layer offers exactly one (the saddle, the wolf's armadillo scute), else
     * the declared pick from {@link EntityOverlayPolicies#defaultMaterialFor}. A declared pick
     * absent from the layer's own materials would resolve to no layers and silently drop the
     * texture, so it warns and falls back to the layer's first material.
     */
    private @NotNull String defaultMaterial(@NotNull String layerTypeId, @NotNull Map<String, String> materials) {
        if (materials.size() == 1) {
            String sole = materials.keySet().iterator().next();
            this.diagnostics.info("default material '%s' via sole-material layer '%s'", sole, layerTypeId);
            return sole;
        }
        String declared = EntityOverlayPolicies.defaultMaterialFor(layerTypeId);
        if (materials.containsKey(declared)) return declared;
        String fallback = materials.keySet().iterator().next();
        this.diagnostics.warn("declared default material '%s' is not a material of layer '%s' %s - using '%s'",
            declared, layerTypeId, materials.keySet(), fallback);
        return fallback;
    }

    /**
     * The equipment texture subdir of a {@code LayerType} constant - its {@code <clinit>}
     * id literal (the last string paired with the constant's {@code PUTSTATIC}).
     *
     * @param cache the class cache
     * @param constant the {@code EquipmentClientInfo$LayerType} constant name
     * @return the id literal, or {@code null} when unresolved
     */
    static @Nullable String layerTypeSubdir(@NotNull ClassNodeCache cache, @NotNull String constant) {
        String owner = VanillaSourceClasses.Types.EQUIPMENT_LAYER_TYPE;
        CommitWalk.Commit<FieldInsnNode, String> committed = AsmWalker.clinit(cache, owner)
            .latch(AsmWalker::stringLiteral)
            .commitAt(Insn.putStatic(owner, constant))
            .first();
        return committed == null ? null : committed.value();
    }

}
