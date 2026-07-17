package lib.minecraft.renderer.tooling.entity;

import lib.minecraft.renderer.tooling.geometry.GeometryManifest;
import lib.minecraft.renderer.tooling.geometry.GeometryRequest;
import lib.minecraft.renderer.tooling.kernel.AsmKit;
import lib.minecraft.renderer.tooling.kernel.ClassNodeCache;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lib.minecraft.renderer.tooling.kernel.JsonNode;
import lib.minecraft.renderer.tooling.kernel.VanillaSourceClasses;
import lib.minecraft.renderer.tooling.vanilla.LayerDefinitionIndex;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;

/**
 * The equipment side of the {@code layers[]} node - one row per saddle / body-armor layer
 * a renderer attaches. Candidates come from the roster site's call-site window (a
 * {@code LayerType} static opening the candidate, the following {@code ModelLayers} statics
 * carrying the adult and baby meshes) or from a bespoke layer's own class internals (wolf
 * armor, llama decor).
 *
 * <p>The texture subdir reads the {@code EquipmentClientInfo$LayerType.<clinit>} id literal;
 * sole-PNG subdirs derive their default material from the jar listing; multi-material subdirs
 * consult {@link EntityOverlayPolicies#defaultMaterialFor}.
 */
final class EntityEquipmentResolver {

    private final @NotNull ClassNodeCache cache;
    private final @NotNull EntitySubject subject;
    private final @NotNull LayerDefinitionIndex layerDefinitions;
    private final @NotNull GeometryManifest manifest;
    private final @NotNull Diagnostics diagnostics;

    EntityEquipmentResolver(
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
     * The equipment row of a call-site candidate: the window's {@code LayerType} static
     * names the subdir, the following {@code ModelLayers} statics the adult (first) and
     * baby (second) meshes.
     *
     * @param site the roster site the row belongs to
     * @param windowStart the first instruction of the site's call-site window
     * @return the row, or {@code null} when the window carries no equipment candidate
     */
    @Nullable JsonNode resolveCallSite(
        @NotNull EntityRendererResolver.LayerSite site,
        @NotNull AbstractInsnNode windowStart
    ) {
        String layerType = null;
        List<String> meshFields = new ArrayList<>(2);
        for (AbstractInsnNode in = windowStart; in != null && in != site.addLayer(); in = in.getNext()) {
            if (AsmKit.isGetStatic(in, VanillaSourceClasses.Types.EQUIPMENT_LAYER_TYPE)) {
                layerType = ((FieldInsnNode) in).name;
                meshFields.clear();
                continue;
            }
            if (layerType != null && AsmKit.isGetStatic(in, VanillaSourceClasses.Types.MODEL_LAYERS))
                meshFields.add(((FieldInsnNode) in).name);
        }
        if (layerType == null || meshFields.isEmpty()) return null;
        return buildRow(site, layerType, meshFields.getFirst(), meshFields.size() > 1 ? meshFields.get(1) : null);
    }

    /**
     * The equipment row of a bespoke layer: the class's own first {@code LayerType} +
     * {@code ModelLayers} references (wolf armor, llama decor).
     *
     * @param site the roster site the row belongs to
     * @param cn the bespoke layer class
     * @return the row, or {@code null} when the pair cannot be resolved
     */
    @Nullable JsonNode resolveBespoke(@NotNull EntityRendererResolver.LayerSite site, @NotNull ClassNode cn) {
        String layerType = null;
        String meshField = null;
        for (MethodNode method : cn.methods)
            for (AbstractInsnNode in = method.instructions.getFirst(); in != null; in = in.getNext()) {
                if (in.getOpcode() != Opcodes.GETSTATIC || !(in instanceof FieldInsnNode fi)) continue;
                if (layerType == null && VanillaSourceClasses.Types.EQUIPMENT_LAYER_TYPE.equals(fi.owner))
                    layerType = fi.name;
                else if (meshField == null && VanillaSourceClasses.Types.MODEL_LAYERS.equals(fi.owner)
                    && !fi.name.contains("BABY"))   // the first non-baby field is the adult mesh
                    meshField = fi.name;
            }
        if (layerType == null || meshField == null) return null;
        return buildRow(site, layerType, meshField, null);
    }

    /**
     * Assembles one {@code layers[]} row: {@code id} is the slot, the gate is
     * {@code when: {equipment: <slot>}}, and the overlay body carries the registered
     * adult mesh, the {@code equipment/<subdir>/<material>} template, the derived or
     * declared default material, and the captured baby mesh.
     */
    private @Nullable JsonNode buildRow(
        @NotNull EntityRendererResolver.LayerSite site,
        @NotNull String layerTypeConstant,
        @NotNull String adultField,
        @Nullable String babyField
    ) {
        String subdir = layerTypeSubdir(this.cache, layerTypeConstant);
        if (subdir == null) {
            this.diagnostics.warn("LayerType.%s has no <clinit> id literal [D33] - equipment row dropped", layerTypeConstant);
            return null;
        }
        // The layers-row slot vocabulary is {saddle, body}; mob-equipment subdirs follow
        // the <mob>_<slot> id grammar. A LayerType outside it (wings, humanoid armor) is
        // player-style runtime equipment, never a static-pose row.
        String slot = subdir.endsWith("_saddle") ? "saddle" : subdir.endsWith("_body") ? "body" : null;
        if (slot == null) {
            this.diagnostics.info("LayerType id '%s' outside the mob-equipment slot grammar - no row", subdir);
            return null;
        }
        String adultKey = registerMesh(adultField);
        if (adultKey == null) {
            this.diagnostics.info("equipment mesh ModelLayers.%s unresolved - row dropped", adultField);
            return null;
        }
        JsonNode overlay = JsonNode.object()
            .put("geometry", adultKey)
            .put("texture_template", VanillaSourceClasses.Paths.EQUIPMENT_DIR + subdir + "/<material>")
            .put("default_material", defaultMaterial(subdir));
        if (babyField != null) {
            String babyKey = registerMesh(babyField);
            if (babyKey != null) overlay.put("baby_geometry", babyKey);
        }
        this.diagnostics.info("equipment row '%s' (%s) meshes adult=%s baby=%s", slot, subdir, adultField, babyField);
        return JsonNode.object()
            .put("source", EntityOverlayResolver.simpleName(site.layerClass()))
            .putInt("layer_index", site.layerIndex())
            .put("id", slot)
            .put("when", JsonNode.object().put("equipment", slot))
            .put("overlay", overlay);
    }

    /** Registers an equipment mesh request off its index entry, or {@code null} when unindexed. */
    private @Nullable String registerMesh(@NotNull String meshField) {
        LayerDefinitionIndex.Entry entry = this.layerDefinitions.get(meshField);
        if (entry == null) return null;
        return this.manifest.register(GeometryRequest.equipment(
            entry.factoryClass(), entry.factoryMethod(), entry.factoryDesc(), this.subject.entityId(),
            entry.texWidthOverride(), entry.texHeightOverride(), entry.floatParam(),
            entry.grow(), entry.appliedMeshTransformerScale()));
    }

    /**
     * The default material: the sole material's basename when the subdir holds exactly one
     * (saddle, the wolf's armadillo scute), else the declared pick from
     * {@link EntityOverlayPolicies#defaultMaterialFor}. An {@code _overlay} companion is a
     * dyeable material's tint layer ({@code armadillo_scute_overlay}, {@code leather_overlay}),
     * never a material of its own.
     */
    private @NotNull String defaultMaterial(@NotNull String subdir) {
        String dir = VanillaSourceClasses.Paths.ASSETS_ROOT + VanillaSourceClasses.Paths.TEXTURES_ENTITY
            + VanillaSourceClasses.Paths.EQUIPMENT_DIR + subdir + "/";
        List<String> materials = new ArrayList<>();
        for (String entry : this.cache.list(dir, ".png")) {
            String basename = entry.substring(entry.lastIndexOf('/') + 1, entry.length() - ".png".length());
            if (!basename.endsWith("_overlay")) materials.add(basename);
        }
        if (materials.size() == 1) {
            this.diagnostics.info("default material '%s' via sole-material listing [D32]", materials.getFirst());
            return materials.getFirst();
        }
        return EntityOverlayPolicies.defaultMaterialFor(subdir);
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
        ClassNode cn = cache.load(VanillaSourceClasses.Types.EQUIPMENT_LAYER_TYPE);
        MethodNode clinit = cn == null ? null : AsmKit.findMethod(cn, AsmKit.CLINIT);
        if (clinit == null) return null;
        String pending = null;
        for (AbstractInsnNode in = clinit.instructions.getFirst(); in != null; in = in.getNext()) {
            String literal = AsmKit.readStringLiteral(in);
            if (literal != null) {
                pending = literal;
                continue;
            }
            if (AsmKit.isPutStatic(in, VanillaSourceClasses.Types.EQUIPMENT_LAYER_TYPE, constant)) return pending;
        }
        return null;
    }

}
