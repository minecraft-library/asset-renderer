package lib.minecraft.refharness;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Mod entry point. Headless flag, world bootstrap, tick-based sweep driver.
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>First non-loading screen detected -&gt; {@code WorldBootstrap} programmatically creates a
 *       fresh flat normal-difficulty world.</li>
 *   <li>{@code client.level} loads -&gt; the harness waits {@link #WARMUP_TICKS} ticks for
 *       the loading-terrain overlay to clear and chunk uploads to finish.</li>
 *   <li>{@code RefHarnessRenderer.start} parks player + camera at the iso pose.</li>
 *   <li>Sweep steps run one subject per tick until every sweep is done.</li>
 *   <li>{@code Minecraft.stop()} fires next tick.</li>
 *   <li>A shutdown hook forces a non-zero exit if any subject failed or the sweep crashed.</li>
 * </ol>
 *
 * <p>That is the run that finishes. The other way one ends is {@link #reportNoWorld}: once
 * {@link #WORLD_TIMEOUT_TICKS} consecutive ticks have passed with no level or no player it names
 * what the bootstrap saw and halts, and halting is what keeps the shutdown hook above from
 * answering for it.
 */
public final class RefHarnessClient implements ClientModInitializer {

    private static final Logger LOG = LoggerFactory.getLogger("refharness");

    private static final AtomicBoolean STARTED = new AtomicBoolean(false);
    private static final AtomicBoolean STOPPING = new AtomicBoolean(false);
    private static final AtomicBoolean CRASHED = new AtomicBoolean(false);

    private static final int WARMUP_TICKS = 60;

    /**
     * Exit status for a run that finished without rendering everything it enumerated. Distinct from
     * {@code 1} so a console reader can tell a partial sweep from a JVM that died on its own; Gradle
     * itself only distinguishes zero from non-zero. It survives as far as this build's own
     * {@code runRenderReferences} failure message and no further - asset-renderer drives the wrapper
     * through {@code cmd /c}, which reports its own {@code 1} - so the number is for a human reading
     * the console, never for a caller to branch on.
     */
    private static final int EXIT_INCOMPLETE_SWEEP = 3;

    /**
     * Exit status for a run whose world never loaded. Distinct from
     * {@link #EXIT_INCOMPLETE_SWEEP} so a console reader can tell a sweep that ran and dropped
     * subjects from one that never got a world to render into; the same caveat applies, the number
     * is for a human reading the console rather than for a caller to branch on.
     */
    private static final int EXIT_NO_WORLD = 4;

    /**
     * Consecutive client ticks carrying no level or no player before the harness calls the bootstrap
     * stuck. The count starts at the first tick after this initialiser runs, and the client ticks at
     * twenty a second through the resource reload as well as on a settled screen, so the three
     * minutes it buys covers the whole boot and not the wait alone - a machine slower than that to
     * reach any screen trips it. A level and a player together reset the count to zero.
     */
    private static final int WORLD_TIMEOUT_TICKS = 3600;

    private static int ticksSinceWorldReady = -1;

    private static int ticksWithoutWorld = 0;

    @Override
    public void onInitializeClient() {
        LOG.info("RefHarness loaded. enabled={}, outputDir={}, size={}",
            HarnessConfig.ENABLED, HarnessConfig.OUTPUT_DIR, HarnessConfig.IMAGE_SIZE);

        if (!HarnessConfig.ENABLED) return;

        Runtime.getRuntime().addShutdownHook(
            new Thread(RefHarnessClient::reportIncompleteSweep, "refharness-exit-status"));

        WorldBootstrap.install();

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (STOPPING.get()) return;
            if (client.level == null || client.player == null) {
                ticksSinceWorldReady = -1;
                ticksWithoutWorld++;
                if (ticksWithoutWorld >= WORLD_TIMEOUT_TICKS) reportNoWorld();
                return;
            }
            ticksWithoutWorld = 0;
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
                    CRASHED.set(true);
                    requestStop(client);
                    return;
                }
            }

            try {
                RefHarnessRenderer.tick(client);
            } catch (Throwable t) {
                LOG.error("RefHarness: sweep tick crashed", t);
                CRASHED.set(true);
                requestStop(client);
                return;
            }

            if (RefHarnessRenderer.isDone()) {
                LOG.info("RefHarness: sweep complete, stopping client.");
                requestStop(client);
            }
        });
    }

    /**
     * Halts a run whose world never arrived, naming what the bootstrap saw.
     *
     * <p>Every other failure in this build announces itself; this one used to be an indefinite wait
     * with no error, no output and no timeout, because a client sitting on a screen the bootstrap
     * declines to act on goes on ticking forever. The two facts that separate the two ways that can
     * happen are printed: the last screen the bootstrap's hook saw, and whether it ever fired. World
     * creation scheduled and no world means the creation itself failed; not scheduled means the
     * screen it settled on is one the predicate skipped, and the name is what says which.
     *
     * <p>{@code halt} rather than {@code stop} because the shutdown hook would otherwise report a
     * sweep that never started as an incomplete sweep, and its status would win.
     */
    private static void reportNoWorld() {
        System.err.printf("refharness: no world after %d client ticks. Last screen: %s; world "
                + "creation scheduled: %s. Exiting %d.%n",
            WORLD_TIMEOUT_TICKS, WorldBootstrap.lastScreenSeen(), WorldBootstrap.hasScheduled(),
            EXIT_NO_WORLD);
        System.err.flush();
        Runtime.getRuntime().halt(EXIT_NO_WORLD);
    }

    private static void requestStop(Minecraft client) {
        if (STOPPING.compareAndSet(false, true)) {
            client.execute(client::stop);
        }
    }

    /**
     * Forces a non-zero exit status when the run did not render everything it enumerated.
     *
     * <p>A subject whose render or write threw leaves the previous run's PNG standing, and a sweep
     * that crashed mid-tick leaves every subject after it standing, so both produce a reference tree
     * that hashes cleanly minus the files nobody refreshed - which a manifest cannot tell from a
     * whole one. The tally was previously reachable only as a {@code failed=} field in a log written
     * under the gitignored run directory, so the caller's own exit code said the run succeeded.
     *
     * <p>It runs as a shutdown hook rather than at the moment the sweep finishes because the last
     * subject's read-back callback is still in flight when a sweep completes; halting there would
     * truncate the PNG the run exists to produce. The client is asked to stop normally and the status
     * is overridden at the very end, which is the only point where the exit code is still ours to
     * set. {@code halt} may cut a logging framework's own hook short, so the message goes to
     * {@code System.err} directly rather than through the logger.
     */
    private static void reportIncompleteSweep() {
        int failed = RefHarnessRenderer.failedSubjects();
        if (failed == 0 && !CRASHED.get()) return;
        System.err.printf("refharness: sweep incomplete - %d subject(s) failed%s. Exiting %d; the "
                + "reference tree holds whatever the previous run left where they should be.%n",
            failed, CRASHED.get() ? " and the sweep crashed" : "", EXIT_INCOMPLETE_SWEEP);
        System.err.flush();
        Runtime.getRuntime().halt(EXIT_INCOMPLETE_SWEEP);
    }
}
