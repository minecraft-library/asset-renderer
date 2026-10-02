package lib.minecraft.renderer.content.table;

import com.google.gson.JsonParseException;
import dev.simplified.annotations.UtilityClass;
import dev.simplified.gson.JsonTree;
import dev.simplified.gson.exception.JsonException;
import lib.minecraft.renderer.asset.mesh.EntityMesh;
import lib.minecraft.renderer.content.read.BlockRendererOverrides;
import lib.minecraft.renderer.content.read.BundledResource;
import lib.minecraft.renderer.content.read.ResourceDocument;
import lib.minecraft.renderer.exception.ContentException;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The pure reader for {@code block_geometry.json}: the geometry-coordinate to bone-tree map decoded
 * straight into {@link EntityMesh} values, then overlaid with the pack override channel per
 * coordinate (later-wins). The {@code geometry} coordinate join against {@code block_models.json} is
 * the assembler's concern, not this reader's.
 */
@UtilityClass
public final class BlockGeometryReader {

    private static final @NotNull String RESOURCE_NAME = "block_geometry.json";

    /**
     * Reads the geometry table from {@code block_geometry.json} and overlays the pack override channel
     * per coordinate.
     *
     * @param overrides the gathered pack override channel; {@link BlockRendererOverrides#EMPTY} for a
     *     vanilla-only stack
     * @return geometry coordinate to bone tree, base-first with later packs winning per coordinate
     * @throws ContentException if the resource is missing or malformed, or a pack override entry does
     *     not bind
     */
    public static @NotNull Map<String, EntityMesh> load(@NotNull BlockRendererOverrides overrides) {
        ResourceDocument document = BundledResource.require(RESOURCE_NAME);
        Map<String, EntityMesh> geometries = new LinkedHashMap<>(document.as(BlockGeometryFile.class).geometries());
        for (Map.Entry<String, JsonTree> override : overrides.geometries().members().toList()) {
            try {
                geometries.put(override.getKey(), override.getValue().as(EntityMesh.class));
            } catch (JsonParseException | JsonException ex) {
                throw new ContentException(ex, "Renderer override geometry '%s' does not bind", override.getKey());
            }
        }
        return geometries;
    }

    /** The {@code block_geometry.json} payload: geometry coordinate to its bone tree. */
    record BlockGeometryFile(@NotNull Map<String, EntityMesh> geometries) {}
}
