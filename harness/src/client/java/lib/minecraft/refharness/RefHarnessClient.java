package lib.minecraft.refharness;

import java.util.concurrent.atomic.AtomicBoolean;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mod entry point. Headless flag, world bootstrap, tick-based sweep driver.
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>Title screen detected -&gt; {@code WorldBootstrap} programmatically creates a
 *       fresh flat normal-difficulty world.</li>
 *   <li>{@code client.level} loads -&gt; the harness waits {@link #WARMUP_TICKS} ticks for
 *       the loading-terrain overlay to clear and chunk uploads to finish.</li>
 *   <li>{@code RefHarnessRenderer.start} parks player + camera at the iso pose.</li>
 *   <li>Sweeper steps run one block / one entity per tick until both are done.</li>
 *   <li>{@code Minecraft.stop()} fires next tick.</li>
 * </ol>
 */
public final class RefHarnessClient implements ClientModInitializer {

    private static final Logger LOG = LoggerFactory.getLogger("refharness");

    private static final AtomicBoolean STARTED = new AtomicBoolean(false);
    private static final AtomicBoolean STOPPING = new AtomicBoolean(false);

    private static final int WARMUP_TICKS = 60;

    private static int ticksSinceWorldReady = -1;

    @Override
    public void onInitializeClient() {
        LOG.info("RefHarness loaded. enabled={}, outputDir={}, size={}",
            HarnessConfig.ENABLED, HarnessConfig.OUTPUT_DIR, HarnessConfig.IMAGE_SIZE);

        if (!HarnessConfig.ENABLED) return;

        WorldBootstrap.install();

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (STOPPING.get()) return;
            if (client.level == null || client.player == null) {
                ticksSinceWorldReady = -1;
                return;
            }
            if (ticksSinceWorldReady < 0) {
                LOG.info("World loaded. Warming up for {} ticks before sweep.", WARMUP_TICKS);
                // Pin noon + freeze the day/night cycle now, while warming up, so the change has
                // fully propagated to the client lightmap before the first block-entity renders.
                RefHarnessRenderer.pinNoonLighting(client);
                ticksSinceWorldReady = 0;
                return;
            }
            ticksSinceWorldReady++;
            if (ticksSinceWorldReady < WARMUP_TICKS) return;

            if (STARTED.compareAndSet(false, true)) {
                LOG.info("RefHarness: starting render sweep.");
                try {
                    RefHarnessRenderer.start(client);
                } catch (Throwable t) {
                    LOG.error("RefHarness: sweep start crashed", t);
                    requestStop(client);
                    return;
                }
            }

            try {
                RefHarnessRenderer.tick(client);
            } catch (Throwable t) {
                LOG.error("RefHarness: sweep tick crashed", t);
                requestStop(client);
                return;
            }

            if (RefHarnessRenderer.isDone()) {
                LOG.info("RefHarness: sweep complete, stopping client.");
                requestStop(client);
            }
        });
    }

    private static void requestStop(Minecraft client) {
        if (STOPPING.compareAndSet(false, true)) {
            client.execute(client::stop);
        }
    }
}
