package lib.minecraft.refharness;

import dev.simplified.annotations.UtilityClass;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.GenericWaitingScreen;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.ProgressScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Programmatic flat-world creator. When the client first settles on a screen that is not itself a
 * loading step, fires a one-shot call to
 * {@code WorldOpenFlows.createFreshLevel("refharness_world", ...)} with a flat preset, peaceful
 * difficulty, and a fixed seed. The Gradle {@code resetRefharnessWorld} task wipes the prior save
 * before each run so this always starts clean.
 *
 * <p>Avoids the {@code --quickPlaySingleplayer} command-line arg because that arg
 * fails silently when the named world doesn't exist; programmatic creation is the
 * cheapest way to get a guaranteed-fresh {@code ClientLevel} on every invocation.
 */
@UtilityClass
public final class WorldBootstrap {

    private static final Logger LOG = LoggerFactory.getLogger("refharness");

    private static final String LEVEL_ID = "refharness_world";

    /** Whether world creation has been fired, so the hook takes the one shot at most once. */
    private static final AtomicBoolean SCHEDULED = new AtomicBoolean(false);

    /**
     * Simple name of the last screen {@link ScreenEvents#AFTER_INIT} reported, {@code none} until it
     * reports one - the watchdog prints it to name the screen a stuck run settled on.
     */
    private static volatile String lastScreen = "none";

    /**
     * Hooks {@link ScreenEvents#AFTER_INIT} so the first screen that is not a loading step triggers
     * world creation.
     */
    public static void install() {
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            lastScreen = screen.getClass().getSimpleName();
            if (isLoadingScreen(screen)) return;
            if (!SCHEDULED.compareAndSet(false, true)) return;

            // Defer one tick so the screen's own init has fully settled before we
            // swap it for the world-loading screen.
            client.execute(() -> openFlatWorld(client));
        });
    }

    /**
     * Answers whether a screen is one the client shows while something else is still loading, so the
     * bootstrap has to wait for whatever replaces it.
     *
     * <p>Everything else is a screen the client has settled on and is waiting for input at - the
     * title screen, the first-run accessibility onboarding, a datapack or resource-reload failure
     * notice - and any of those is a point where {@code createFreshLevel} can be called. Keying on
     * the title screen alone made a client that stops on any other first screen hang with no error
     * and no output.
     *
     * @param screen the screen that just finished initialising
     * @return whether world creation must keep waiting
     */
    private static boolean isLoadingScreen(Screen screen) {
        return screen instanceof LevelLoadingScreen
            || screen instanceof ProgressScreen
            || screen instanceof GenericMessageScreen
            || screen instanceof GenericWaitingScreen;
    }

    static String lastScreenSeen() {
        return lastScreen;
    }

    static boolean hasScheduled() {
        return SCHEDULED.get();
    }

    private static void openFlatWorld(Minecraft client) {
        LOG.info("WorldBootstrap: settled on {}, creating fresh flat world '{}'.", lastScreen, LEVEL_ID);

        // NORMAL (not PEACEFUL) - peaceful auto-removes hostile mobs (creeper, zombie, ...)
        // each tick, which makes the entity sweep unable to capture them. Player invulnerability
        // is set elsewhere so the spawned hostiles don't actually attack.
        LevelSettings settings = new LevelSettings(
            LEVEL_ID,
            GameType.CREATIVE,
            new LevelSettings.DifficultySettings(Difficulty.NORMAL, /*hardcore*/ false, /*locked*/ true),
            /*allowCommands*/ true,
            WorldDataConfiguration.DEFAULT
        );
        WorldOptions options = new WorldOptions(/*seed*/ 0L, /*generateStructures*/ false, /*bonusChest*/ false);

        try {
            client.createWorldOpenFlows().createFreshLevel(
                LEVEL_ID,
                settings,
                options,
                WorldPresets::createFlatWorldDimensions,
                /*parentScreen*/ null
            );
        } catch (Throwable t) {
            LOG.error("WorldBootstrap: createFreshLevel threw", t);
            // Reset so the next settled screen (e.g. after an error toast) can retry.
            SCHEDULED.set(false);
        }
    }
}
