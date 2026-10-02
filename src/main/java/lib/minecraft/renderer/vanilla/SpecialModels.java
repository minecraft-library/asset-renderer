package lib.minecraft.renderer.vanilla;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentSet;
import lib.minecraft.renderer.parity.Parity;
import org.jetbrains.annotations.NotNull;

/**
 * The hardcoded-render kinds a vanilla {@code minecraft:special} item node may name, each mapping
 * onto an existing render path: {@code bed} / {@code chest} / {@code shulker_box} / {@code banner} /
 * {@code conduit} / {@code decorated_pot} render through the block-entity bone geometry,
 * {@code shield} / {@code head} / {@code player_head} through the hardcoded item paths, and
 * {@code copper_golem_statue} / {@code trident} through their special renderers.
 *
 * <p>A fixed roster rather than pack state - a pack declares which kind a node names, never that a
 * new kind exists - which is why it is read here rather than carried on the decoded node.
 */
@UtilityClass
@Parity(claim = "asset-layer")
public class SpecialModels {

    /** The special kinds vanilla 26.1 ships, each mapped onto an existing render path. */
    private static final @NotNull ConcurrentSet<String> KNOWN = Concurrent.newUnmodifiableSet(
        "bed", "chest", "shulker_box", "banner", "conduit", "decorated_pot",
        "shield", "head", "player_head", "copper_golem_statue", "trident");

    /**
     * Whether a special kind maps onto an existing render path.
     *
     * @param kind the special-node kind (the inner {@code model.type}, with or without the
     *     {@code minecraft:} prefix)
     * @return whether this renderer knows how to dispatch the kind
     */
    public static boolean isRenderable(@NotNull String kind) {
        return KNOWN.contains(strip(kind));
    }

    /** Strips a leading {@code minecraft:} namespace so kind matching accepts both id forms. */
    private static @NotNull String strip(@NotNull String kind) {
        int colon = kind.indexOf(':');
        return colon < 0 ? kind : kind.substring(colon + 1);
    }

}
