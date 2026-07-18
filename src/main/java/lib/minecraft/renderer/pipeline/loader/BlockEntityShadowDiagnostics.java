package lib.minecraft.renderer.pipeline.loader;

import lib.minecraft.renderer.asset.PackStack;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.asset.pack.PackId;
import lib.minecraft.renderer.asset.pack.ResourcePack;
import lib.minecraft.renderer.pipeline.pack.VanillaSourcePaths;
import lib.minecraft.renderer.pipeline.util.BlockRendererOverrides;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.util.Set;

/**
 * The shadowed-model diagnostic: warns when a non-vanilla pack ships a vanilla-form
 * {@code models/block/<id>.json} or {@code blockstates/<id>.json} for a block-entity-backed id (chests,
 * beds, banners, signs, skulls, shulkers, conduit, decorated_pot, copper_golem_statue, ...).
 *
 * <p>Those ids render through code-driven block-entity geometry - the non-additive {@code Block.Entity}
 * precedence in {@code BlockIndexBuilder} hides any pack-supplied model, exactly as the vanilla client
 * ignores a stray {@code chest.json}. Rather than silently discarding the pack's file, this pass names
 * the pack and points at the {@code renderer/*.json} override channel ({@link BlockRendererOverrides})
 * that CAN deliberately replace block-entity geometry. It changes no precedence and moves no output
 * byte: a vanilla-only stack (no non-vanilla pack) emits nothing.
 */
@UtilityClass
public class BlockEntityShadowDiagnostics {

    /**
     * Reports every block-entity-backed id a non-vanilla pack shadows with a vanilla-form model or
     * blockstate file.
     *
     * @param stack the resolved pack stack
     * @param blockEntityBackedIds the namespaced ids that render through code-driven block-entity
     *     geometry (the block-entity primary models plus their state-conditional variants)
     */
    public static void report(@NotNull PackStack stack, @NotNull Set<String> blockEntityBackedIds) {
        for (ResourcePack pack : stack.ascending()) {
            if (pack.id().equals(PackId.VANILLA)) continue;
            for (String beId : blockEntityBackedIds) {
                ResourceId id = ResourceId.parse(beId);
                warnIfShipped(pack, beId, id, VanillaSourcePaths.MODELS_BLOCK_SUBDIR, "model");
                warnIfShipped(pack, beId, id, VanillaSourcePaths.BLOCKSTATES_SUBDIR, "blockstate");
            }
        }
    }

    /**
     * Warns once when the pack ships {@code assets/<ns>/<subdir>/<localName>.json} for a block-entity id,
     * across any of the pack's active roots. Container-agnostic ({@code exists} works for the
     * materialized directory and archive containers alike).
     */
    private static void warnIfShipped(@NotNull ResourcePack pack, @NotNull String beId, @NotNull ResourceId id,
                                      @NotNull String subdir, @NotNull String kind) {
        String relative = VanillaSourcePaths.assetSubdir(id.namespace(), subdir) + "/" + id.name() + ".json";
        boolean shipped = pack.roots().stream().anyMatch(root -> pack.container().exists(root.prefix() + relative));
        if (shipped)
            System.err.printf("Pack '%s' ships a %s for block-entity-backed id '%s', which is SHADOWED by "
                + "code-driven block-entity geometry (a resource pack cannot change its geometry this way, "
                + "as in the vanilla client). To deliberately replace it, use the renderer/*.json override "
                + "channel (renderer/block_models.json + renderer/block_geometry.json at the pack root).%n",
                pack.id(), kind, beId);
    }

}
