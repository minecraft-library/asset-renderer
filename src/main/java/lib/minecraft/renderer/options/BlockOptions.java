package lib.minecraft.renderer.options;

import dev.simplified.image.Background;
import lib.minecraft.renderer.BlockRenderer;
import lib.minecraft.renderer.Renderer;
import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.engine.RendererContext;
import lib.minecraft.renderer.engine.camera.Facing;
import lib.minecraft.renderer.engine.camera.Projection;
import lib.minecraft.renderer.engine.compose.layer.GeometryLayer;
import lib.minecraft.renderer.engine.compose.layer.LayerStack;
import lib.minecraft.renderer.face.BlockFace;
import lib.minecraft.renderer.options.slot.BlockSlot;
import lib.minecraft.renderer.request.Biome;
import lib.minecraft.renderer.request.EulerRotation;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

import java.util.function.UnaryOperator;

/**
 * Configures a single {@link BlockRenderer BlockRenderer} invocation.
 *
 * <p>Two output flavours, selected via {@link Type}:
 * <ul>
 *   <li><b>{@link Type#ISOMETRIC_3D}</b> - the full 3D block icon at the vanilla
 *       {@code [30, 225, 0]} {@code display.gui} pose. Six faces, supersampled by default at
 *       {@code 2x}, FXAA post-processing optional.</li>
 *   <li><b>{@link Type#BLOCK_FACE_2D}</b> - a single face blitted flat. Useful for atlas tiles
 *       that consume one face per output cell.</li>
 * </ul>
 *
 * <p><b>Biome / variant inputs.</b> {@link #getBiome biome} drives grass / foliage / water
 * tinting. {@link #getVariant variant} matches the vanilla blockstate property string
 * ({@code "facing=south,lit=false"}); an empty string selects the default variant. Both feed
 * into the {@link RendererContext RendererContext} lookups
 * during render, so the renderer itself stays scene-agnostic.
 *
 * <p><b>Block-entity composition.</b> {@link #isMergeParts mergeParts} controls whether
 * {@link Block.Entity Block.Entity} parts (bed foot onto bed head, decorated_pot sides onto
 * base, banner flag onto post) are merged into one tile or rendered as the per-part scene
 * geometry; see the field javadoc for the full semantics.
 *
 * @see lib.minecraft.renderer.BlockRenderer
 */
@Getter
@Builder(toBuilder = true, access = AccessLevel.PUBLIC)
public class BlockOptions implements Option {

    /**
     * Namespaced block id to render, e.g. {@code "minecraft:stone"}. Empty string by default,
     * which resolves to no block
     */
    @lombok.Builder.Default
    private final @NotNull String blockId = "";

    /**
     * Render type - isometric 3D icon or a single flat 2D face
     */
    @lombok.Builder.Default
    private final @NotNull Type type = Type.ISOMETRIC_3D;

    /**
     * Block face to blit in {@link Type#BLOCK_FACE_2D} mode; ignored in {@link Type#ISOMETRIC_3D}
     */
    @lombok.Builder.Default
    private final @NotNull BlockFace face = BlockFace.NORTH;

    /**
     * Blockstate variant properties string (e.g. {@code "facing=south,lit=false"}). When set,
     * the renderer applies the variant's whole-model X/Y rotation before the camera transform.
     * An empty string selects the default variant.
     */
    @lombok.Builder.Default
    private final @NotNull String variant = "";

    /**
     * Biome used for tinting grass, foliage and water textures, defaulting to
     * {@link Biome.Vanilla#PLAINS}
     */
    @lombok.Builder.Default
    private final @NotNull Biome biome = Biome.Vanilla.PLAINS;

    /**
     * User-override model rotation applied before the camera transform, in degrees. Composes on
     * top of any blockstate {@link #variant} rotation. Defaults to {@link EulerRotation#NONE}
     */
    @lombok.Builder.Default
    private final @NotNull EulerRotation rotation = EulerRotation.NONE;

    /**
     * Graphical projection for the 3D isometric render. Defaults to {@link Projection#VANILLA_ISO} -
     * vanilla's iso block pose, byte-identical to the shipped render. Selecting another projection
     * (true isometric, dimetric, cabinet, ...) re-poses the camera and its orthographic flatten
     * together. Only consulted by the {@link Type#ISOMETRIC_3D} path
     */
    @lombok.Builder.Default
    private final @NotNull Projection projection = Projection.VANILLA_ISO;

    /**
     * View-facing reflection applied to the {@link #getProjection() projection}. Defaults to
     * {@link Facing#DEFAULT} (no reflection); {@link Facing#MIRRORED} mirrors the view horizontally and
     * {@link Facing#FLIPPED} flips it vertically. Only consulted by the {@link Type#ISOMETRIC_3D} path
     */
    @lombok.Builder.Default
    private final @NotNull Facing facing = Facing.DEFAULT;

    /**
     * Output image dimensions in pixels (square), defaulting to {@link Renderer#DEFAULT_OUTPUT_SIZE}
     */
    @lombok.Builder.Default
    private final int outputSize = Renderer.DEFAULT_OUTPUT_SIZE;

    /**
     * Whether to apply FXAA post-processing. Off by default: vanilla's GUI block icon path
     * does no post-process AA, so leaving FXAA on diverges from the {@code Lighting.ITEMS_3D}
     * harness baseline by blurring sub-texel edges that vanilla leaves sharp. Callers that
     * want soft edges for non-parity use cases can opt in via the builder.
     */
    @lombok.Builder.Default
    private final boolean antiAlias = false;

    /**
     * Supersample scale factor for isometric 3D rendering. The block is rasterized at
     * {@code outputSize * supersample} resolution, then downsampled for sharper output at small
     * tile sizes. A value of 1 disables supersampling.
     */
    @lombok.Builder.Default
    private final int supersample = 1;

    /**
     * Whether the renderer should compose a {@link Block.Entity}'s
     * {@link Block.Entity#parts() parts} into the tile output.
     * <p>
     * Atlas-view rendering (the default, {@code true}) merges every part (bed foot onto bed
     * head, decorated_pot sides onto its base, banner flag onto its post) so the icon shows
     * the full composed block. Scene-view rendering ({@code false}) skips the merge, so a
     * caller placing an individual {@code red_bed[part=head]} block in a 3D world gets just
     * the head geometry, and {@code red_bed[part=foot]} at the neighbouring position gets
     * just the foot. No-op on blocks that carry no entity or whose entity has no parts.
     */
    @lombok.Builder.Default
    private final boolean mergeParts = true;

    /**
     * Background fill composited behind the finished render (solid colour or checkerboard).
     * Defaults to {@link Background#TRANSPARENT}, a no-op that leaves the render's own alpha intact.
     */
    @lombok.Builder.Default
    private final @NotNull Background background = Background.TRANSPARENT;

    /**
     * Transform applied to the default {@link GeometryLayer} stack (primary model, additive entity,
     * merged parts) before it runs, letting callers splice custom layers relative to the
     * {@link BlockSlot} slots. Defaults to {@linkplain UnaryOperator#identity() identity}. Only
     * consulted by the 3D isometric path.
     */
    @lombok.Builder.Default
    private final @NotNull UnaryOperator<LayerStack<GeometryLayer>> layerDecorator = UnaryOperator.identity();

    /**
     * Opens a builder seeded from this instance's current values, for deriving a variant with a
     * few fields changed.
     *
     * @return a builder pre-populated from this instance
     */
    public @NotNull BlockOptionsBuilder mutate() {
        return this.toBuilder();
    }

    /**
     * Builds an instance with every field at its default value.
     *
     * @return the default options
     */
    public static @NotNull BlockOptions defaults() {
        return builder().build();
    }

    /**
     * The supported render types for {@link BlockRenderer}.
     */
    public enum Type {

        /**
         * Full 3D isometric block icon, all six faces, at the vanilla {@code display.gui} pose.
         */
        ISOMETRIC_3D,

        /**
         * A single flat 2D block face, selected by {@link #getFace() face}.
         */
        BLOCK_FACE_2D

    }

}
