package lib.minecraft.renderer.option;

import dev.simplified.image.Background;
import lib.minecraft.renderer.BlockRenderer;
import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.engine.RendererContext;
import lib.minecraft.renderer.engine.compose.layer.GeometryLayer;
import lib.minecraft.renderer.engine.compose.layer.LayerStack;
import lib.minecraft.renderer.face.BlockFace;
import lib.minecraft.renderer.option.slot.BlockSlot;
import lib.minecraft.renderer.option.spec.OutputOptions;
import lib.minecraft.renderer.engine.texture.Biome;
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
public class BlockOptions implements RenderOptions {

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
     * The default output frame for a block icon - neutral output size, {@code VANILLA_ISO}
     * projection, no supersampling and no FXAA.
     */
    public static final @NotNull OutputOptions DEFAULT_OUTPUT = OutputOptions.defaults();

    /** The shared output frame - output size, projection, facing, rotation, and SSAA / FXAA. */
    @lombok.Builder.Default
    private final @NotNull OutputOptions output = DEFAULT_OUTPUT;

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
