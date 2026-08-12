package lib.minecraft.renderer.tooling.entity;

import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.tooling.kernel.ClassNodeCache;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lib.minecraft.renderer.tooling.kernel.ToolingSession;
import lib.minecraft.renderer.tooling.kernel.VanillaSourceClasses;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * The client's {@code assets/<ns>/equipment/*.json} corpus, inverted per layer type into the
 * {@code material -> asset id} table an equipment row needs to name its texture source. Read once per
 * run and shared by every subject.
 *
 * <p>An equipment asset file declares, per layer type, the ordered texture layers that composite for
 * that slot; the FIRST layer's bare texture stem is the material the render axis selects by, and the
 * file's own stem is the asset id that resolves those layers. Inverting the corpus on that pair is
 * what makes the mapping exact where it is not the identity - the llama's {@code white} carpet lives
 * in {@code white_carpet.json}, and all ten saddle layer types share the single {@code saddle.json} -
 * so no naming convention has to be guessed at render, where a wrong asset id silently drops the
 * texture.
 *
 * <p>Both levels are sorted by key so the emitted table is independent of zip directory order.
 */
final class EquipmentAssetIndex {

    /** The equipment-definition subtree of the jar ({@code assets/minecraft/equipment/}). */
    private static final @NotNull String ASSET_DIR =
        VanillaSourceClasses.Paths.ASSETS_ROOT + VanillaSourceClasses.Paths.EQUIPMENT_DIR;

    /** The {@code layers} map keying each layer type to its ordered texture layers. */
    private static final @NotNull String LAYERS_KEY = "layers";

    /** The bare texture stem of one texture layer. */
    private static final @NotNull String TEXTURE_KEY = "texture";

    private final @NotNull Map<String, Map<String, String>> byLayerType;

    private EquipmentAssetIndex(@NotNull Map<String, Map<String, String>> byLayerType) {
        this.byLayerType = byLayerType;
    }

    /**
     * Reads and inverts the whole equipment corpus, once.
     *
     * @param session the live session
     * @return the built index
     */
    static @NotNull EquipmentAssetIndex build(@NotNull ToolingSession session) {
        Diagnostics diagnostics = session.diagnostics().child("equipmentAssets");
        ClassNodeCache cache = session.cache();
        Map<String, Map<String, String>> byLayerType = new TreeMap<>();
        int assets = 0;
        for (String entryPath : cache.list(ASSET_DIR, VanillaSourceClasses.Paths.JSON_SUFFIX)) {
            String stem = entryPath.substring(entryPath.lastIndexOf('/') + 1,
                entryPath.length() - VanillaSourceClasses.Paths.JSON_SUFFIX.length());
            JsonTree root = cache.readJson(entryPath);
            if (root == null) continue;
            assets++;
            index(root, VanillaSourceClasses.Paths.MINECRAFT_NAMESPACE + stem, byLayerType, diagnostics);
        }
        diagnostics.info("inverted %d equipment assets into %d layer types %s",
            assets, byLayerType.size(), byLayerType.keySet());
        return new EquipmentAssetIndex(Collections.unmodifiableMap(byLayerType));
    }

    /**
     * The {@code material -> asset id} table for a layer type, empty when the corpus declares no
     * asset for it.
     *
     * @param layerTypeId the serialized layer-type id ({@code llama_body})
     * @return the sorted material table, or an empty map
     */
    @NotNull Map<String, String> materials(@NotNull String layerTypeId) {
        return this.byLayerType.getOrDefault(layerTypeId, Map.of());
    }

    /** Folds one asset file's layer types into the inverse table under its namespaced asset id. */
    private static void index(
        @NotNull JsonTree root,
        @NotNull String assetId,
        @NotNull Map<String, Map<String, String>> byLayerType,
        @NotNull Diagnostics diagnostics
    ) {
        JsonTree layers = root.findObject(LAYERS_KEY).orElse(null);
        if (layers == null) return;
        for (Map.Entry<String, JsonTree> member : layers.members().toList()) {
            String layerTypeId = member.getKey();
            // Every id the corpus declares is transcribed. Deciding which are modelled belongs to the
            // consumer and is already done there - both EntityIndexBuilder and EquipmentModelLoader
            // resolve the id through LayerType.fromId and skip what it does not answer - so a second
            // copy of that vocabulary here would only be one that could fall behind it.
            // The base (first) layer names the material; any further layer is that material's
            // composite companion (a dyeable base's tint pass), never a material of its own.
            String material = member.getValue()
                .findAt(0)
                .flatMap(layer -> layer.findString(TEXTURE_KEY))
                .map(EquipmentAssetIndex::stripNamespace)
                .orElse(null);
            if (material == null) {
                diagnostics.warn("equipment asset '%s' layer '%s' has no base texture - skipped", assetId, layerTypeId);
                continue;
            }
            String clash = byLayerType.computeIfAbsent(layerTypeId, key -> new TreeMap<>()).put(material, assetId);
            if (clash != null && !clash.equals(assetId))
                diagnostics.warn("layer '%s' material '%s' claimed by both '%s' and '%s'", layerTypeId, material, clash, assetId);
        }
    }

    /** Drops the {@code minecraft:} prefix from a texture stem, leaving a bare material name. */
    private static @NotNull String stripNamespace(@NotNull String texture) {
        int colon = texture.indexOf(':');
        return colon < 0 ? texture : texture.substring(colon + 1);
    }

}
