package lib.minecraft.renderer.tooling.blockentity;

import lib.minecraft.renderer.tooling.kernel.VanillaSourceClasses;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * One registered block-entity type joined with its renderer - everything the per-subject
 * resolver chain needs, produced by {@link BlockEntityRegistryDiscovery} in
 * {@code BlockEntityRenderers.<clinit>} registration order.
 *
 * <p>The renderer class is retained here because discovery already knows the id-to-renderer
 * pairing from the registration walk, so no separate lookup is needed.
 *
 * @param beTypeId the namespaced block-entity type id, e.g. {@code minecraft:chest} - the
 *     {@code LDC} in {@code BlockEntityType.<clinit>}
 * @param beTypeField the {@code BlockEntityType} static field name, e.g. {@code CHEST} - the
 *     {@code PUTSTATIC} target and the join key
 * @param rendererClass the renderer class's JVM internal name, resolved from the
 *     {@code BlockEntityRenderers.<clinit>} registration lambda
 * @param blockFields the ordered {@code Blocks} static-field names this type's
 *     {@code validBlocks} lists (e.g. {@code CHEST}, {@code TRAPPED_CHEST}); resolved to
 *     namespaced ids at catalog time through {@code BlockRegistryIndex}
 */
public record BlockEntitySubject(
    @NotNull String beTypeId,
    @NotNull String beTypeField,
    @NotNull String rendererClass,
    @NotNull List<String> blockFields
) {

    /**
     * The namespace-less id ({@code chest} for {@code minecraft:chest}) - the basename the
     * family-split policies match against.
     */
    public @NotNull String localId() {
        return this.beTypeId.startsWith(VanillaSourceClasses.Paths.MINECRAFT_NAMESPACE)
            ? this.beTypeId.substring(VanillaSourceClasses.Paths.MINECRAFT_NAMESPACE.length())
            : this.beTypeId;
    }

}
