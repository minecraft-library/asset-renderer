package lib.minecraft.refharness;

import java.io.IOException;
import java.nio.file.Path;

import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tick-driven player-reference sweep. Renders the vanilla {@link net.minecraft.client.model.player.PlayerModel}
 * (default steve skin) at the two scopes that have a vanilla ground truth - {@link PlayerFrameRenderer.Scope#FULL}
 * and {@link PlayerFrameRenderer.Scope#SKULL} - under the {@code ENTITY_IN_UI} lighting vanilla uses for its
 * inventory player-model preview. One PNG per scope lands at {@code players/<scope>.png}, the ground truth the
 * sibling asset-renderer's {@code PlayerRenderer} 3D output is diffed against.
 *
 * <p>Renders one scope per client tick - {@link PlayerFrameRenderer} reuses a single PIP colour texture whose
 * readback completes asynchronously, so firing the next render before the prior readback lands would corrupt it
 * (same reason {@link ItemSweeper} / {@link GlintSweeper} render one subject per tick).
 */
public final class PlayerSweeper implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger("refharness");

    private final PlayerFrameRenderer.Scope[] scopes;
    private final PlayerFrameRenderer renderer;
    private int index;
    private int rendered;
    private int failed;
    private boolean done;

    private PlayerSweeper() {
        this.scopes = PlayerFrameRenderer.Scope.values();
        this.renderer = new PlayerFrameRenderer();
    }

    public static PlayerSweeper build() {
        LOG.info("PlayerSweeper built: {} scopes", PlayerFrameRenderer.Scope.values().length);
        return new PlayerSweeper();
    }

    public boolean isDone() {
        return done;
    }

    public void step(Minecraft client) {
        if (done) return;
        if (index >= scopes.length) { finish(); return; }

        PlayerFrameRenderer.Scope scope = scopes[index];
        String name = scope.name().toLowerCase(java.util.Locale.ROOT);
        Path out = HarnessConfig.OUTPUT_DIR.resolve("players").resolve(name + ".png");
        try {
            renderer.renderAndWrite(client, scope, HarnessConfig.IMAGE_SIZE, out);
            rendered++;
        } catch (IOException ex) {
            LOG.error("PlayerSweeper: PNG write failed for {}", name, ex);
            failed++;
        } catch (RuntimeException ex) {
            LOG.error("PlayerSweeper: render failed for {}", name, ex);
            failed++;
        }

        index++;
        if (index >= scopes.length) finish();
    }

    private void finish() {
        LOG.info("PlayerSweeper: done. rendered={}, failed={}, total={}", rendered, failed, scopes.length);
        done = true;
        // Same reasoning as the other sweepers: the last render's PNG-write callback is still pending,
        // so closing the GPU textures here would crash it. Leak until JVM exit.
    }

    @Override
    public void close() {
        // Leak the frame renderer's textures rather than race in-flight readbacks (see finish()).
    }
}
