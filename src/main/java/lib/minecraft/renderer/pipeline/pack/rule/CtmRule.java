package lib.minecraft.renderer.pipeline.pack.rule;

import dev.simplified.collection.ConcurrentList;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.pipeline.pack.PackId;
import org.jetbrains.annotations.NotNull;

import java.util.EnumSet;

/**
 * A parsed OptiFine / MCPatcher Connected Textures rule (03-rules §5.2) - the successor to HEAD's
 * {@code asset.rule.CtmRule}, rebuilt against the 07-optifine defect table. Fully typed and validated
 * at parse (range-expanded tiles, correct {@code top} / {@code bottom} face names, per-method tile-count
 * check), yet never evaluated: CTM renders nothing (locked decision 4), so this is a parse-and-store
 * record whose store has zero render-path callers (see {@link CtmNeighborResolver}).
 *
 * @param id the source {@code .properties} path - ordering and weight-tie key
 * @param pack the owning pack
 * @param target what the rule matches - tiles or blocks
 * @param method the CTM method
 * @param tiles the tile list, range-expanded and sentinel-resolved at parse
 * @param faces the target faces, {@code sides} / {@code all} expanded at parse
 * @param weight the sort weight; higher wins, ties broken by filename then pack priority
 * @param stored the parse-and-store tier
 */
public record CtmRule(
    @NotNull ResourceId id,
    @NotNull PackId pack,
    @NotNull CtmTarget target,
    @NotNull CtmMethod method,
    @NotNull ConcurrentList<TileRef> tiles,
    @NotNull EnumSet<CtmFace> faces,
    int weight,
    @NotNull CtmExtras stored
) {

    /**
     * The source {@code .properties} filename - the last path segment of {@link #id} - used as the
     * deterministic weight-tie sort key (03-rules §2).
     *
     * @return the source filename
     */
    public @NotNull String filename() {
        String path = this.id.name();
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    /**
     * Whether this rule matches vanilla tiles rather than blocks - the partition key the stack merge
     * sorts on (tile-target rules before block-target rules).
     *
     * @return {@code true} when the target is {@link CtmTarget.Tiles}
     */
    public boolean isTileTarget() {
        return this.target instanceof CtmTarget.Tiles;
    }

}
