package lib.minecraft.renderer.option;

import lib.minecraft.renderer.BlockRenderer;
import lib.minecraft.renderer.FluidRenderer;
import lib.minecraft.renderer.ItemRenderer;
import lib.minecraft.renderer.PortalRenderer;
import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.pipeline.loader.BlockModelLoader;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * One row of an {@link AtlasSidecar}: the subject a tile was rendered from, how that tile was
 * classified, and where it sits in the composed grid.
 *
 * <p>A row carries coordinates and no pixels - the grid position only exists once every tile has
 * been laid out, and the composed atlas image is what holds the pixels. The x/y-vs-col/row
 * redundancy is kept because external consumers walk it.
 *
 * @param id the namespaced block or item id the tile was rendered from
 * @param kind whether the tile holds a block or an item
 * @param source the pipeline path that produced the tile
 * @param col the grid column the tile occupies
 * @param row the grid row the tile occupies
 * @param x the tile's left pixel edge in the atlas ({@code col * tileSize})
 * @param y the tile's top pixel edge in the atlas ({@code row * tileSize})
 * @param width the tile's pixel width
 * @param height the tile's pixel height
 */
public record AtlasTile(
    @NotNull String id,
    @NotNull Kind kind,
    @NotNull Source source,
    int col,
    int row,
    int x,
    int y,
    int width,
    int height
) {

    /**
     * Kind tag emitted alongside each tile in the sidecar JSON. Serialised via {@link #jsonName()}
     * so the on-disk format stays lowercase ({@code "block"} / {@code "item"}).
     */
    public enum Kind {

        /**
         * A block tile rendered via {@link BlockRenderer}.
         */
        BLOCK,
        /**
         * An item tile rendered via {@link ItemRenderer}.
         */
        ITEM;

        private static final @NotNull Map<String, Kind> BY_JSON_NAME =
            Arrays.stream(values()).collect(Collectors.toUnmodifiableMap(Kind::jsonName, Function.identity()));

        /**
         * Resolves the kind a serialised token names, empty when the token names no kind.
         *
         * @param jsonName the lowercase token read from a sidecar row
         * @return the matching kind, or empty for an unknown token
         */
        public static @NotNull Optional<Kind> fromJsonName(@NotNull String jsonName) {
            return Optional.ofNullable(BY_JSON_NAME.get(jsonName));
        }

        /**
         * The lowercase kind name used in the sidecar JSON schema.
         */
        public @NotNull String jsonName() {
            return this.name().toLowerCase(Locale.ROOT);
        }

    }

    /**
     * Registration source tag emitted alongside {@link Kind} so diagnostics can filter tiles
     * by the pipeline path that produced them.
     * <ul>
     * <li>{@link #BLOCK_MODEL} - primary {@code blockModels} iteration (plain blocks whose
     *     geometry is fully described by {@code block.json}).</li>
     * <li>{@link #BLOCKSTATE_ONLY} - blocks resolved via blockstate when no block-model file
     *     matches the id (fences, walls, small_dripleaf, etc.).</li>
     * <li>{@link #TILE_ENTITY} - blocks whose geometry comes from a {@link Block.Entity} -
     *     vanilla {@code BlockEntityRenderer} geometry baked into block model elements by
     *     {@link BlockModelLoader} (beds, chests, banners, shulkers, signs, skulls, conduit,
     *     decorated_pot, etc.).</li>
     * <li>{@link #FLUID} - block rendered through {@link FluidRenderer} from the still fluid
     *     texture (water, lava). Vanilla {@code block/water.json} and {@code block/lava.json}
     *     carry no elements, so the fluid renderer supplies the atlas tile instead.</li>
     * <li>{@link #PORTAL} - block rendered through {@link PortalRenderer} via a CPU-baked
     *     parallax star-field (end_portal, end_gateway). Vanilla ships only a
     *     particle-texture block model for end_portal and no block model at all for
     *     end_gateway, so the portal renderer supplies the atlas tile instead.</li>
     * <li>{@link #ITEM_MODEL} - primary {@code itemModels} iteration.</li>
     * </ul>
     *
     * <p>Distinct from {@link AtlasOptions.Source}, which selects the model kinds an atlas render
     * covers: this one records where an emitted tile came from.
     */
    public enum Source {

        /**
         * Primary {@code blockModels} iteration.
         */
        BLOCK_MODEL,
        /**
         * Transient block resolved via blockstate only (fence, wall, small_dripleaf, etc.).
         */
        BLOCKSTATE_ONLY,
        /**
         * Block carrying a {@link Block.Entity} - tile-entity geometry baked into block elements.
         */
        TILE_ENTITY,
        /**
         * Block rendered via {@link FluidRenderer.FluidFace2D} (water, lava).
         */
        FLUID,
        /**
         * Block rendered via {@link PortalRenderer.PortalFace2D} (end_portal, end_gateway).
         */
        PORTAL,
        /**
         * Primary {@code itemModels} iteration.
         */
        ITEM_MODEL;

        private static final @NotNull Map<String, Source> BY_JSON_NAME =
            Arrays.stream(values()).collect(Collectors.toUnmodifiableMap(Source::jsonName, Function.identity()));

        /**
         * Resolves the source a serialised token names, empty when the token names no source.
         *
         * @param jsonName the lowercase token read from a sidecar row
         * @return the matching source, or empty for an unknown token
         */
        public static @NotNull Optional<Source> fromJsonName(@NotNull String jsonName) {
            return Optional.ofNullable(BY_JSON_NAME.get(jsonName));
        }

        /**
         * The lowercase source name used in the sidecar JSON schema.
         */
        public @NotNull String jsonName() {
            return this.name().toLowerCase(Locale.ROOT);
        }

    }

}
