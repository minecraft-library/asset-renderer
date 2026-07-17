package lib.minecraft.renderer.pipeline.pack.rule;

import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.pipeline.pack.Capability;
import lib.minecraft.renderer.pipeline.pack.PackContainer;
import lib.minecraft.renderer.pipeline.pack.PackRoot;
import lib.minecraft.renderer.pipeline.pack.ResourcePack;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Discovers the SkyBlock item-model-definition documents a Catharsis pack ships - the
 * {@code assets/skyblock/items/<id>.json} files (and the shared-base-item
 * sub-identifier directories {@code attributes/ enchantments/ pets/ potions/ runes/}). Each is a plain
 * vanilla item-model definition addressed under the synthetic {@code skyblock} namespace.
 *
 * <p><b>Discovery only</b>: the walk is capability-gated on
 * {@link Capability#CATHARSIS_CONVENTIONS} and returns the discovered ids; nothing renders them. The
 * selection-tree walker that CONSUMES these (degrading unresolvable {@code catharsis:*} predicates to
 * their {@code fallback} branch) lives in the item-definition tree support - a vanilla-only
 * renderer never reaches this code, so it stays inert.
 */
@UtilityClass
public class SkyblockDiscovery {

    /** The synthetic namespace Catharsis SkyBlock item definitions are addressed under. */
    public static final @NotNull String SKYBLOCK_NAMESPACE = "skyblock";

    private static final @NotNull String ITEMS_ROOT = "assets/skyblock/items";
    private static final @NotNull String ITEMS_MARKER = ITEMS_ROOT + "/";
    private static final @NotNull String JSON_SUFFIX = ".json";

    /**
     * Discovers the SkyBlock item ids a pack ships across its active roots (base plus every activated
     * overlay). Returns empty for a pack without {@link Capability#CATHARSIS_CONVENTIONS}, so the
     * discovery cost is only paid by Catharsis packs.
     *
     * @param pack the pack to scan
     * @return the discovered {@code skyblock:<id>} ids, or empty when the pack carries no Catharsis conventions
     */
    public static @NotNull Set<ResourceId> discover(@NotNull ResourcePack pack) {
        if (!pack.has(Capability.CATHARSIS_CONVENTIONS)) return Set.of();

        PackContainer container = pack.container();
        Set<ResourceId> ids = new LinkedHashSet<>();
        for (PackRoot root : pack.roots()) {
            container.entries(root.prefix() + ITEMS_ROOT)
                .filter(entry -> entry.endsWith(JSON_SUFFIX))
                .forEach(entry -> toId(entry).ifPresent(ids::add));
        }
        return Set.copyOf(ids);
    }

    /** Maps an {@code .../assets/skyblock/items/<name>.json} entry (under any root prefix) to a {@code skyblock:<name>} id. */
    private static @NotNull Optional<ResourceId> toId(@NotNull String entry) {
        int marker = entry.indexOf(ITEMS_MARKER);
        if (marker < 0) return Optional.empty();
        String name = entry.substring(marker + ITEMS_MARKER.length(), entry.length() - JSON_SUFFIX.length());
        return name.isBlank() ? Optional.empty() : Optional.of(new ResourceId(SKYBLOCK_NAMESPACE, name));
    }

}
