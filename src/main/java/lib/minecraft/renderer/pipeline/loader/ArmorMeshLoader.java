package lib.minecraft.renderer.pipeline.loader;

import lib.minecraft.renderer.asset.model.ArmorMeshData;
import lib.minecraft.renderer.asset.model.ArmorMeshes;
import lib.minecraft.renderer.exception.PipelineException;
import lib.minecraft.renderer.pipeline.util.BundledResource;
import lib.minecraft.renderer.pipeline.util.ResourceDocument;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The reader for the worn-armor meshes: one pure read of {@code entity_armor.json}, resolving each
 * armor-set name onto the mesh it is registered against.
 *
 * <p>The file holds the meshes once, keyed by the factory coordinate and deformations they were cut
 * from, plus the set names pointing at them - most humanoids share one mesh, so the names outnumber
 * the meshes several to one. This reader collapses that indirection so a caller looks a wearer's
 * armor up by the name its renderer holds.
 */
public final class ArmorMeshLoader {

    private static final @NotNull String ARMOR_RESOURCE = "entity_armor.json";

    private ArmorMeshLoader() {}

    /**
     * Loads the bundled armor meshes with a default console diagnostics scope.
     *
     * @return the indexed meshes ({@link ArmorMeshes#NONE} when the resource is absent)
     * @throws PipelineException when the resource is present but unparseable
     */
    public static @NotNull ArmorMeshes load() {
        return load(Diagnostics.root("entity_armor", Diagnostics.Output.CONSOLE, null));
    }

    /**
     * Reads the armor catalog natively from the bundled resources and resolves the name indirection.
     *
     * @param diagnostics the scope envelope and read warnings are recorded to
     * @return the indexed meshes ({@link ArmorMeshes#NONE} when the resource is absent)
     * @throws PipelineException when the resource is present but unparseable
     */
    public static @NotNull ArmorMeshes load(@NotNull Diagnostics diagnostics) {
        Optional<ResourceDocument> document =
            BundledResource.read(ARMOR_RESOURCE, BundledResource.MissingPolicy.GRACEFUL_EMPTY, diagnostics);
        if (document.isEmpty()) return ArmorMeshes.NONE;

        ArmorFile raw = document.get().as(ArmorFile.class);
        if (raw == null || raw.meshes() == null) return ArmorMeshes.NONE;

        Map<String, ArmorMeshData> byName = new LinkedHashMap<>();
        if (raw.sets() != null)
            for (Map.Entry<String, String> set : raw.sets().entrySet()) {
                ArmorMeshData mesh = raw.meshes().get(set.getValue());
                if (mesh == null) {
                    diagnostics.warn("armor set '%s' names mesh '%s', which the file does not carry - "
                        + "its wearers fall back to the shared mesh", set.getKey(), set.getValue());
                    continue;
                }
                byName.put(set.getKey(), mesh);
            }
        return new ArmorMeshes(Map.copyOf(byName),
            Optional.ofNullable(raw.shared()).map(raw.meshes()::get));
    }

    /**
     * The {@code entity_armor.json} payload: the meshes keyed by factory coordinate, the armor-set
     * names pointing at them, and the key of the mesh an unnamed wearer is dressed in.
     */
    private record ArmorFile(
        @NotNull Map<String, ArmorMeshData> meshes,
        @Nullable Map<String, String> sets,
        @Nullable String shared
    ) {}

}
