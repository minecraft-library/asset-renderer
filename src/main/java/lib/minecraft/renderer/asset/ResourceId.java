package lib.minecraft.renderer.asset;

import org.jetbrains.annotations.NotNull;

/**
 * A namespaced resource identifier - a {@code namespace:name} pair such as
 * {@code minecraft:grass_block}.
 * <p>
 * The asset DTOs ({@link Block}, {@link Item}, {@link Entity}, {@link BlockTag}) carry a
 * {@code ResourceId}; the full {@code namespace:name} string is derived on demand via {@link #id()},
 * and the factories {@link #parse(String)} and {@link #ofModelId(String)} centralise the id-parsing
 * used by the pipeline index loaders.
 *
 * @param namespace the resource namespace (e.g. {@code minecraft})
 * @param name the resource path/name within the namespace (e.g. {@code grass_block})
 */
public record ResourceId(@NotNull String namespace, @NotNull String name) {

    /**
     * The default namespace assumed when an id string carries no explicit {@code namespace:} prefix.
     */
    public static final @NotNull String DEFAULT_NAMESPACE = "minecraft";

    /**
     * The full {@code namespace:name} string form (e.g. {@code minecraft:grass_block}).
     *
     * @return the namespaced id string
     */
    public @NotNull String id() {
        return this.namespace + ':' + this.name;
    }

    /** {@inheritDoc} */
    @Override
    public @NotNull String toString() {
        return this.id();
    }

    /**
     * Parses a namespaced id string into its namespace and name. An id with no {@code :} is treated
     * as a name in the {@link #DEFAULT_NAMESPACE default namespace}.
     *
     * @param id the namespaced id (e.g. {@code minecraft:grass_block})
     * @return the parsed resource id
     */
    public static @NotNull ResourceId parse(@NotNull String id) {
        int colon = id.indexOf(':');
        if (colon < 0) return new ResourceId(DEFAULT_NAMESPACE, id);
        return new ResourceId(id.substring(0, colon), id.substring(colon + 1));
    }

    /**
     * Derives a resource id from a namespaced model id by taking the namespace before the first
     * {@code :} and the trailing path segment as the name. Example:
     * {@code minecraft:block/grass_block} yields {@code (minecraft, grass_block)}, so {@link #id()}
     * collapses to {@code minecraft:grass_block}.
     *
     * @param modelId the namespaced model id (e.g. {@code minecraft:block/grass_block})
     * @return the derived resource id
     */
    public static @NotNull ResourceId ofModelId(@NotNull String modelId) {
        int colon = modelId.indexOf(':');
        String namespace = colon < 0 ? DEFAULT_NAMESPACE : modelId.substring(0, colon);
        return new ResourceId(namespace, localName(modelId));
    }

    /**
     * Returns the trailing path segment of a namespaced model id - the part after the last
     * {@code /}, or after the last {@code :} when no slash is present. Example:
     * {@code minecraft:block/grass_block} yields {@code grass_block}.
     *
     * @param modelId the namespaced model id
     * @return the trailing path segment
     */
    public static @NotNull String localName(@NotNull String modelId) {
        int slash = modelId.lastIndexOf('/');
        if (slash >= 0) return modelId.substring(slash + 1);
        int colon = modelId.lastIndexOf(':');
        return colon >= 0 ? modelId.substring(colon + 1) : modelId;
    }

}
