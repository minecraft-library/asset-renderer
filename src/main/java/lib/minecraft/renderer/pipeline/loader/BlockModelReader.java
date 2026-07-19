package lib.minecraft.renderer.pipeline.loader;

import com.google.gson.annotations.SerializedName;
import lib.minecraft.renderer.exception.PipelineException;
import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.pipeline.util.BlockRendererOverrides;
import lib.minecraft.renderer.pipeline.util.BundledResource;
import lib.minecraft.renderer.pipeline.util.ResourceDocument;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The pure reader for {@code block_models.json}: the model-id-keyed catalog of block-entity model
 * entries decoded straight into raw {@link BlockModelEntry} records (1:1 with the file), then overlaid
 * with the pack override channel per model id (later-wins). The {@code geometry} join, the
 * model-id -> block-id pivot, the {@code parts} sibling join, and the leaf transforms (dye tint,
 * texture strip) are the assembler's concern, not this reader's.
 */
@UtilityClass
public final class BlockModelReader {

    private static final @NotNull String RESOURCE_NAME = "block_models.json";

    /**
     * Reads the raw model catalog from {@code block_models.json} and overlays the pack override channel
     * per model id.
     *
     * @param diagnostics the scope envelope warnings are recorded to
     * @param overrides the gathered pack override channel; {@link BlockRendererOverrides#EMPTY} for a
     *     vanilla-only stack
     * @return model id to its raw model entry, base-first with later packs winning per model id
     * @throws PipelineException if the resource is missing or malformed
     */
    static @NotNull Map<String, BlockModelEntry> load(@NotNull Diagnostics diagnostics, @NotNull BlockRendererOverrides overrides) {
        ResourceDocument document = BundledResource.read(RESOURCE_NAME, BundledResource.MissingPolicy.REQUIRED, diagnostics).orElseThrow();
        Map<String, BlockModelEntry> models = new LinkedHashMap<>(document.as(BlockModelsFile.class).models());
        for (Map.Entry<String, JsonTree> override : overrides.models().members().toList())
            models.put(override.getKey(), override.getValue().as(BlockModelEntry.class));
        return models;
    }

    /** The {@code block_models.json} payload: model id to its raw model entry. */
    record BlockModelsFile(@NotNull Map<String, BlockModelEntry> models) {}

    /**
     * One raw block-entity model entry, 1:1 with the file. The {@code geometry} coordinate joins into
     * {@code block_geometry.json}; {@code blocks} fans out to runtime block entries; a part-only submodel
     * carries no {@code blocks}.
     *
     * @param geometry the geometry coordinate into {@code block_geometry.json}
     * @param yAxis the source Y-axis convention ({@code "UP"} or {@code "DOWN"})
     * @param tinted whether the model's faces sample the block tint
     * @param inventory the inventory presentation object, or {@code null} when absent
     * @param icon the icon rotation / additive flags, or {@code null} when absent
     * @param parts the sub-model part references, or {@code null} for a leaf model
     * @param blocks the runtime block entries this model fans out to, or {@code null} for a part-only submodel
     */
    record BlockModelEntry(
        @Nullable String geometry,
        @SerializedName("y_axis") @Nullable String yAxis,
        boolean tinted,
        @Nullable InventoryDto inventory,
        @Nullable IconDto icon,
        @Nullable List<PartRef> parts,
        @Nullable List<BlockRef> blocks
    ) {}

    /**
     * The nested {@code inventory} presentation object.
     *
     * @param yRotation the inventory Y rotation in degrees
     * @param flip whether the entity model is flipped for the inventory pose
     * @param transform the presentation transform, 6 or 7 floats, or {@code null} when absent
     */
    record InventoryDto(@SerializedName("y_rotation") float yRotation, boolean flip, float @Nullable [] transform) {}

    /**
     * The nested {@code icon} object; both fields default to {@code 0} / {@code false} when absent.
     *
     * @param rotation the icon Y rotation in degrees
     * @param additive whether the model's geometry overlays the primary model rather than replacing it
     */
    record IconDto(int rotation, boolean additive) {}

    /**
     * One {@code parts[]} sub-model reference.
     *
     * @param model the sibling model id in the same catalog
     * @param offset the part's translation offset, or {@code null} for the origin
     * @param texture the part's own texture path, or {@code null} to inherit the parent's texture
     */
    record PartRef(@Nullable String model, float @Nullable [] offset, @Nullable String texture) {}

    /**
     * One {@code blocks[]} entry the model fans out to.
     *
     * @param block the runtime block id
     * @param texture the block's entity texture path
     * @param tint the block's dye-name tint, or {@code null} for untinted (white)
     * @param variant the blockstate variant key this geometry binds to, or {@code null} for the primary model
     */
    record BlockRef(@Nullable String block, @Nullable String texture, @Nullable String tint, @Nullable String variant) {}
}
