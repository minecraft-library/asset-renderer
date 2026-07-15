package lib.minecraft.renderer.pipeline.pack.item;

import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.Set;

/**
 * Classifies {@code special}-node kinds against the render paths this repo already owns. Every 26.1
 * vanilla special kind maps onto an existing dispatcher: {@code bed} / {@code chest}
 * / {@code shulker_box} / {@code banner} / {@code conduit} / {@code decorated_pot} render through the
 * block-entity bone geometry; {@code shield} / {@code head} / {@code player_head} through the hardcoded
 * {@code ItemRenderer} paths; {@code copper_golem_statue} / {@code trident} through their special
 * renderers. An unrecognised kind (a future or pack-invented one) is diagnosed and dropped - there is
 * no fallback for a special leaf.
 *
 * <p>Special rendering is parse-and-hold: at the neutral context every vanilla special item
 * is already served by its existing path (shield by its id, block-entity items by the block path), so
 * no special leaf reaches a new render seam. The classifier exists so the seam, when wired, has the
 * kind map + drop contract ready.
 */
@UtilityClass
public class SpecialKinds {

    /** The special kinds vanilla 26.1 ships, each mapped onto an existing render path. */
    private static final @NotNull Set<String> KNOWN = Set.of(
        "bed", "chest", "shulker_box", "banner", "conduit", "decorated_pot",
        "shield", "head", "player_head", "copper_golem_statue", "trident");

    /**
     * Whether the kind maps onto an existing render path.
     *
     * @param kind the special-node kind (the inner {@code model.type}, with or without the
     *     {@code minecraft:} prefix)
     * @return whether this renderer knows how to dispatch the kind
     */
    public static boolean isRenderable(@NotNull String kind) {
        return KNOWN.contains(strip(kind));
    }

    /**
     * Returns the special leaf when its kind is renderable, else logs a pack-facing diagnostic and
     * drops it (empty) - the no-fallback contract for special nodes.
     *
     * @param leaf the resolved special leaf
     * @return the leaf when renderable, or empty (dropped with a diagnostic) for an unknown kind
     */
    public static @NotNull Optional<ItemModelNode.Special> resolveOrDrop(@NotNull ItemModelNode.Special leaf) {
        if (isRenderable(leaf.kind())) return Optional.of(leaf);
        System.err.printf("Dropping item special-node of unknown kind '%s' (base '%s')%n", leaf.kind(), leaf.base());
        return Optional.empty();
    }

    /** Strips a leading {@code minecraft:} namespace so kind matching accepts both id forms. */
    private static @NotNull String strip(@NotNull String kind) {
        int colon = kind.indexOf(':');
        return colon < 0 ? kind : kind.substring(colon + 1);
    }

}
