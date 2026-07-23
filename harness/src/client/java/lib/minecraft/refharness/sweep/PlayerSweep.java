package lib.minecraft.refharness.sweep;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import lib.minecraft.refharness.HarnessConfig;
import lib.minecraft.refharness.PlayerFrameRenderer;
import lib.minecraft.refharness.api.Canvas;
import lib.minecraft.refharness.api.RefKey;
import lib.minecraft.refharness.api.Sweep;
import lib.minecraft.refharness.api.SweepContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Player-reference sweep. Renders the vanilla player model at the two scopes that have a vanilla
 * ground truth - full body and head-only - under the inventory preview's lighting. One PNG per scope
 * lands at a bare {@code <scope>.png}, the ground truth the sibling asset-renderer's player output
 * is diffed against.
 */
public final class PlayerSweep implements Sweep<PlayerFrameRenderer.Scope> {

    private static final Logger LOG = LoggerFactory.getLogger("refharness");

    private final PlayerFrameRenderer frameRenderer = new PlayerFrameRenderer();

    @Override
    public String outputDir() {
        return "players";
    }

    @Override
    public List<PlayerFrameRenderer.Scope> enumerate(SweepContext ctx) {
        List<PlayerFrameRenderer.Scope> scopes = List.of(PlayerFrameRenderer.Scope.values());
        LOG.info("PlayerSweep built: {} scopes", scopes.size());
        return scopes;
    }

    @Override
    public RefKey key(PlayerFrameRenderer.Scope scope) {
        return RefKey.named(scope.name().toLowerCase(Locale.ROOT));
    }

    @Override
    public Canvas canvas(SweepContext ctx, PlayerFrameRenderer.Scope scope) {
        return Canvas.square(HarnessConfig.IMAGE_SIZE);
    }

    @Override
    public boolean render(SweepContext ctx, PlayerFrameRenderer.Scope scope, Canvas canvas, Path out)
        throws IOException {
        return frameRenderer.render(ctx.client(), scope, canvas, out);
    }

    /**
     * Returns {@code false} - a player scope has no registry id for the allowlist to match against,
     * so honouring the filter would make any scoped run write no player reference at all.
     */
    @Override
    public boolean honoursTargetFilter() {
        return false;
    }
}
