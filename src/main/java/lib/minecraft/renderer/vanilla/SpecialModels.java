package lib.minecraft.renderer.vanilla;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.collection.ConcurrentSet;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Optional;

/**
 * The hardcoded-render kinds a vanilla {@code minecraft:special} item node may name - the sixteen
 * vanilla 26.1 registers, each with the fields its codec requires - and the eleven of them that map
 * onto an existing render path: {@code bed} / {@code chest} / {@code shulker_box} / {@code banner} /
 * {@code conduit} / {@code decorated_pot} render through the block-entity bone geometry,
 * {@code shield} / {@code head} / {@code player_head} through the hardcoded item paths, and
 * {@code copper_golem_statue} / {@code trident} through their special renderers.
 *
 * <p>A kind is read namespace-exact, as {@link ResourceId#vanillaPath(String)} reads an id: a bare or
 * {@code minecraft:} kind names one of vanilla's, and a kind in any other namespace is a mod's, which
 * names none of them even where its path spells one.
 *
 * <p>A fixed roster rather than pack state - a pack declares which kind a node names, never that a
 * new kind exists - which is why it is read here rather than carried on the decoded node.
 */
@UtilityClass
@Parity(claim = "asset-layer")
public class SpecialModels {

    /** The special kinds vanilla 26.1 registers, by path, each with the fields its codec requires. */
    private static final @NotNull ConcurrentMap<String, ConcurrentList<String>> REGISTERED = Concurrent.newUnmodifiableMap(Map.ofEntries(
        Map.entry("bed", Concurrent.newUnmodifiableList("texture", "part")),
        Map.entry("bell", Concurrent.<String>newUnmodifiableList()),
        Map.entry("banner", Concurrent.newUnmodifiableList("color")),
        Map.entry("book", Concurrent.newUnmodifiableList("open_angle", "page1", "page2")),
        Map.entry("conduit", Concurrent.<String>newUnmodifiableList()),
        Map.entry("chest", Concurrent.newUnmodifiableList("texture")),
        Map.entry("copper_golem_statue", Concurrent.newUnmodifiableList("texture", "pose")),
        Map.entry("head", Concurrent.newUnmodifiableList("kind")),
        Map.entry("player_head", Concurrent.<String>newUnmodifiableList()),
        Map.entry("shulker_box", Concurrent.newUnmodifiableList("texture")),
        Map.entry("shield", Concurrent.<String>newUnmodifiableList()),
        Map.entry("trident", Concurrent.<String>newUnmodifiableList()),
        Map.entry("decorated_pot", Concurrent.<String>newUnmodifiableList()),
        Map.entry("standing_sign", Concurrent.newUnmodifiableList("wood_type")),
        Map.entry("hanging_sign", Concurrent.newUnmodifiableList("wood_type")),
        Map.entry("end_cube", Concurrent.newUnmodifiableList("effect"))));

    /** The registered kinds that map onto an existing render path, by path. */
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
        return ResourceId.vanillaPath(kind).filter(KNOWN::contains).isPresent();
    }

    /**
     * Returns the fields a registered special kind's codec requires.
     *
     * @param path the kind's path under {@code minecraft:} (e.g. {@code bed})
     * @return the required field names, or empty when vanilla registers no kind at the path
     */
    public static @NotNull Optional<ConcurrentList<String>> requiredFields(@NotNull String path) {
        return Optional.ofNullable(REGISTERED.get(path));
    }

}
