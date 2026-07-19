package lib.minecraft.renderer.pipeline.loader;

import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.exception.PipelineException;
import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.pipeline.util.BlockRendererOverrides;
import lib.minecraft.renderer.pipeline.util.BundledResource;
import lib.minecraft.renderer.pipeline.util.ResourceDocument;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The pure reader for {@code block_geometry.json}: the geometry-coordinate to bone-tree map decoded
 * straight into {@link EntityModelData} values, then overlaid with the pack override channel per
 * coordinate (later-wins). The {@code geometry_ref} join against {@code block_models.json} is the
 * assembler's concern, not this reader's.
 */
@UtilityClass
public final class BlockGeometryReader {

    private static final @NotNull String RESOURCE_NAME = "block_geometry.json";

    /**
     * Reads the geometry table from {@code block_geometry.json} and overlays the pack override channel
     * per coordinate.
     *
     * @param diagnostics the scope envelope warnings are recorded to
     * @param overrides the gathered pack override channel; {@link BlockRendererOverrides#EMPTY} for a
     *     vanilla-only stack
     * @return geometry coordinate to bone tree, base-first with later packs winning per coordinate
     * @throws PipelineException if the resource is missing or malformed
     */
    static @NotNull Map<String, EntityModelData> load(@NotNull Diagnostics diagnostics, @NotNull BlockRendererOverrides overrides) {
        ResourceDocument document = BundledResource.read(RESOURCE_NAME, BundledResource.MissingPolicy.REQUIRED, diagnostics).orElseThrow();
        Map<String, EntityModelData> geometries = new LinkedHashMap<>(document.as(BlockGeometryFile.class).geometries());
        for (Map.Entry<String, JsonTree> override : overrides.geometries().members().toList())
            geometries.put(override.getKey(), override.getValue().as(EntityModelData.class));
        return geometries;
    }

    /** The {@code block_geometry.json} payload: geometry coordinate to its bone tree. */
    record BlockGeometryFile(@NotNull Map<String, EntityModelData> geometries) {}
}
