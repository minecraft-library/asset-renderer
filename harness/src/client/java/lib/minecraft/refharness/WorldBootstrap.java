package lib.minecraft.refharness;

import java.util.concurrent.atomic.AtomicBoolean;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Programmatic flat-world creator. When the {@link TitleScreen} first appears,
 * fires a one-shot call to {@code WorldOpenFlows.createFreshLevel("refharness_world", ...)}
 * with a flat preset, peaceful difficulty, and a fixed seed. The Gradle
 * {@code resetRefharnessWorld} task wipes the prior save before each run so this
 * always starts clean.
 *
 * <p>Avoids the {@code --quickPlaySingleplayer} command-line arg because that arg
 * fails silently when the named world doesn't exist; programmatic creation is the
 * cheapest way to get a guaranteed-fresh {@code ClientLevel} on every invocation.
 */
public final class WorldBootstrap {

    private static final Logger LOG = LoggerFactory.getLogger("refharness");

    private static final String LEVEL_ID = "refharness_world";

    private static final AtomicBoolean SCHEDULED = new AtomicBoolean(false);

    private WorldBootstrap() {}

    /** Hooks {@link ScreenEvents#AFTER_INIT} so the first {@link TitleScreen} triggers world creation. */
    public static void install() {
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (!(screen instanceof TitleScreen)) return;
            if (!SCHEDULED.compareAndSet(false, true)) return;

            // Defer one tick so the title screen's own init has fully settled before we
            // swap it for the world-loading screen.
            client.execute(() -> openFlatWorld(client));
        });
    }

    private static void openFlatWorld(Minecraft client) {
        LOG.info("WorldBootstrap: TitleScreen detected, creating fresh flat world '{}'.", LEVEL_ID);

        // NORMAL (not PEACEFUL) - peaceful auto-removes hostile mobs (creeper, zombie, ...)
        // each tick, which makes EntitySweeper unable to capture them. Player invulnerability
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
            // Reset so a subsequent TitleScreen (e.g. after error toast) can retry.
            SCHEDULED.set(false);
        }
    }
}
