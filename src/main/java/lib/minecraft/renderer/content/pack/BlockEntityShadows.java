package lib.minecraft.renderer.content.pack;

import dev.simplified.annotations.UtilityClass;
import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.asset.pack.ResourcePack;
import lib.minecraft.renderer.content.read.BlockRendererOverrides;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.vanilla.VanillaPaths;
import lib.minecraft.renderer.vanilla.id.PackId;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import org.jetbrains.annotations.NotNull;

import java.util.Set;

/**
 * The shadowed-model report for block-entity-backed ids - a probe of every non-vanilla pack's roots for a
 * vanilla-form model or blockstate that code-driven block-entity geometry hides. It asks each pack's
 * container whether the file exists and reads none of it.
 */
@UtilityClass
@Parity(claim = "index-and-loader")
public class BlockEntityShadows {

    /**
     * Warns when a non-vanilla pack ships a vanilla-form {@code models/block/<id>.json} or
     * {@code blockstates/<id>.json} for a block-entity-backed id (chests, beds, banners, signs, skulls,
     * shulkers, conduit, decorated_pot, copper_golem_statue, ...).
     *
     * <p>Those ids render through code-driven block-entity geometry - the non-additive
     * {@link Block.BlockEntity} precedence in the block index builder hides any pack-supplied model, exactly
     * as the vanilla client ignores a stray {@code chest.json}. Rather than discarding the pack's file
     * in silence, this names the pack and points at the {@code renderer/*.json} override channel
     * ({@link BlockRendererOverrides}) that CAN deliberately replace block-entity geometry. It changes
     * no precedence and moves no output byte: a vanilla-only stack emits nothing.
     *
     * <p>Takes the id set rather than a loaded catalog, so the warning can be exercised against a chosen
     * id without loading the shipped one.
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
                warnIfShipped(pack, beId, id, VanillaPaths.MODELS_BLOCK_SUBDIR, "model");
                warnIfShipped(pack, beId, id, VanillaPaths.BLOCKSTATES_SUBDIR, "blockstate");
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
        String relative = VanillaPaths.assetSubdir(id.namespace(), subdir) + "/" + id.name() + ".json";
        boolean shipped = pack.roots().stream().anyMatch(root -> pack.container().exists(root.prefix() + relative));
        if (shipped)
            System.err.printf("Pack '%s' ships a %s for block-entity-backed id '%s', which is SHADOWED by "
                + "code-driven block-entity geometry (a resource pack cannot change its geometry this way, "
                + "as in the vanilla client). To deliberately replace it, use the renderer/*.json override "
                + "channel (renderer/block_models.json + renderer/block_geometry.json at the pack root).%n",
                pack.id(), kind, beId);
    }

}
