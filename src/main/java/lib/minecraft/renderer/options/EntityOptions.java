package lib.minecraft.renderer.options;

import dev.simplified.image.Background;
import lib.minecraft.renderer.Renderer;
import lib.minecraft.renderer.engine.camera.Facing;
import lib.minecraft.renderer.engine.camera.Projection;
import lib.minecraft.renderer.engine.compose.GeometryLayer;
import lib.minecraft.renderer.engine.compose.LayerSlot;
import lib.minecraft.renderer.engine.compose.LayerStack;
import lib.minecraft.renderer.request.ArmorPiece;
import lib.minecraft.renderer.request.EulerRotation;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * Configures a single {@code EntityRenderer} invocation for mob entities. The entity is resolved
 * by {@link #getEntityId() entityId} through the active {@code RendererContext} and rendered as a
 * 3D icon via its {@code EntityModelData} bone/cube tree, posed by {@link #getProjection()
 * projection} (default {@link Projection#VANILLA_ISO}). The {@link #getRotation() rotation} field
 * is the user-override layer applied on top of the projection's baked pose.
 *
 * <p>The {@link #getFitMode() fitMode} field selects how the output canvas is sized:
 * {@link FitMode#OUTPUT_SIZE} (default) renders into a fixed {@code outputSize x outputSize}
 * square with the entity scaled to fit and {@code padding} pixels of clear space inside;
 * {@link FitMode#UNION_BOUNDS} and {@link FitMode#FAMILY_BOUNDS} size the canvas dynamically
 * from the entity's bounds at native pixel resolution and are intended for vanilla-reference
 * parity work. See each enum constant's javadoc for the precise math.
 *
 * <p>{@link #getSupersample() supersample} composes orthogonally with {@link #isAntiAlias()
 * antiAlias}: supersample renders at {@code supersample x} the final canvas dim then
 * downsamples (SSAA), while antiAlias applies an FXAA post-process on whichever buffer the
 * rasterizer wrote into. Defaults are {@code supersample = 1} and {@code antiAlias = false},
 * so an end-user one-off render ships with no AA unless explicitly opted into.
 */
@Getter
@Builder(toBuilder = true, access = AccessLevel.PUBLIC)
public class EntityOptions {

    /**
     * Namespaced entity id for lookup, e.g. {@code "minecraft:zombie"}. Empty (default) resolves
     * to no entity.
     */
    @lombok.Builder.Default
    private final @NotNull Optional<String> entityId = Optional.empty();

    /**
     * Optional texture id override, resolvable through the active pack stack. Empty (default)
     * uses the entity's own default texture.
     */
    @lombok.Builder.Default
    private final @NotNull Optional<String> textureId = Optional.empty();

    /**
     * Helmet armor piece to render on humanoid entities.
     */
    @lombok.Builder.Default
    private final @NotNull Optional<ArmorPiece> helmet = Optional.empty();

    /**
     * Chestplate armor piece to render on humanoid entities.
     */
    @lombok.Builder.Default
    private final @NotNull Optional<ArmorPiece> chestplate = Optional.empty();

    /**
     * Leggings armor piece to render on humanoid entities.
     */
    @lombok.Builder.Default
    private final @NotNull Optional<ArmorPiece> leggings = Optional.empty();

    /**
     * Boots armor piece to render on humanoid entities.
     */
    @lombok.Builder.Default
    private final @NotNull Optional<ArmorPiece> boots = Optional.empty();

    /**
     * Canvas-sizing strategy. {@link FitMode#OUTPUT_SIZE} (default) honours {@link #outputSize}
     * and centres the entity inside a fixed square canvas; {@link FitMode#UNION_BOUNDS} and
     * {@link FitMode#FAMILY_BOUNDS} size the canvas dynamically from the entity's screen
     * bounds for parity work. See each constant's javadoc for the precise sizing math.
     */
    @lombok.Builder.Default
    private final @NotNull FitMode fitMode = FitMode.OUTPUT_SIZE;

    /**
     * Output image dimensions in pixels (square). Only consumed when {@link #fitMode} is
     * {@link FitMode#OUTPUT_SIZE}; ignored by the two BOUNDS modes (which derive canvas dims
     * from the entity's own bounds).
     */
    @lombok.Builder.Default
    private final int outputSize = Renderer.DEFAULT_OUTPUT_SIZE;

    /**
     * Clear-space padding in canvas pixels. Universal across every {@link FitMode}, with
     * mode-specific semantics:
     * <ul>
     *   <li>{@link FitMode#OUTPUT_SIZE} - shrinks the available silhouette area inside the
     *       fixed {@link #outputSize} canvas by {@code padding} pixels on each side.</li>
     *   <li>{@link FitMode#UNION_BOUNDS}, {@link FitMode#FAMILY_BOUNDS} - expands the
     *       dynamically-computed canvas by {@code padding} pixels on each side around the
     *       native-sized silhouette.</li>
     * </ul>
     * Default {@code 0} so the BOUNDS modes are unchanged from current parity behaviour
     * without an explicit override.
     */
    @lombok.Builder.Default
    private final int padding = 0;

    /**
     * Texel resolution in image-pixels per Minecraft block-unit, consumed by the two
     * {@code BOUNDS} {@link FitMode}s to size the canvas (the per-axis ratio is
     * {@code pixelsPerBlock / 16} since vanilla authors cubes in entity-pixels). Ignored by
     * {@link FitMode#OUTPUT_SIZE}. Defaults to the {@code -Drefharness.pixelsPerBlock} system
     * property, or {@code 256} when unset, matching the vanilla-reference-harness scale.
     */
    @lombok.Builder.Default
    private final int pixelsPerBlock = Integer.getInteger("refharness.pixelsPerBlock", 256);

    /**
     * Hard cap in pixels on the longer canvas axis for the two {@code BOUNDS} {@link FitMode}s.
     * An entity whose bounds would exceed this (ender_dragon, giant) is scaled down uniformly so
     * the longer side equals the cap. Ignored by {@link FitMode#OUTPUT_SIZE}. Defaults to the
     * {@code -Drefharness.maxCanvasSize} system property, or {@code 1024} when unset.
     */
    @lombok.Builder.Default
    private final int maxCanvasSize = Integer.getInteger("refharness.maxCanvasSize", 1024);

    /**
     * User-override model rotation applied on top of the projection pose, before the camera
     * transform, in degrees. Defaults to {@link EulerRotation#NONE}.
     */
    @lombok.Builder.Default
    private final @NotNull EulerRotation rotation = EulerRotation.NONE;

    /**
     * Graphical projection for the render. Defaults to {@link Projection#VANILLA_ISO} - the vanilla
     * iso preview pose, byte-identical to the shipped render. The entity's model-to-world facing /
     * chirality is applied separately (as a {@code Placement}), so selecting another projection
     * re-poses the camera while keeping the entity upright and facing; the canvas-fit and centring
     * track the chosen projection.
     */
    @lombok.Builder.Default
    private final @NotNull Projection projection = Projection.VANILLA_ISO;

    /**
     * View-facing reflection applied to the {@link #getProjection() projection}. Defaults to
     * {@link Facing#DEFAULT} (no reflection); {@link Facing#MIRRORED} mirrors the view horizontally and
     * {@link Facing#FLIPPED} flips it vertically. Reflects the <b>camera / view</b>, not the entity's
     * model-spin: an entity with a built-in yaw addend (e.g. shulker) mirrors about the camera axis, and a
     * facing combined with a non-zero {@link #getRotation() rotation} reflects the camera but not that spin
     * (so mirror-then-spin differs from spin-then-mirror).
     */
    @lombok.Builder.Default
    private final @NotNull Facing facing = Facing.DEFAULT;

    /**
     * Supersample scale factor. The entity is rasterized at {@code (canvas dims) * supersample}
     * resolution, then downsampled to the final canvas size for sharper output (SSAA). A value
     * of {@code 1} (default) disables supersampling. Composes orthogonally with
     * {@link #antiAlias} - when both are set, FXAA runs on the hi-res buffer before
     * downsampling, which gives the cleanest silhouette at the cost of extra cycles.
     */
    @lombok.Builder.Default
    private final int supersample = 1;

    /**
     * Whether to apply FXAA post-processing on the rasterized buffer. Default {@code false}
     * so end-user one-off renders ship without FXAA blur; opt in for soft edges on small
     * thumbnails or when supersample alone isn't enough. When {@link #supersample} is
     * {@code > 1}, FXAA runs on the hi-res buffer before downsampling.
     */
    @lombok.Builder.Default
    private final boolean antiAlias = false;

    /**
     * Background fill composited behind the finished render (solid colour or checkerboard).
     * Defaults to {@link Background#TRANSPARENT}, a no-op that leaves the render's own alpha intact.
     */
    @lombok.Builder.Default
    private final @NotNull Background background = Background.TRANSPARENT;

    /**
     * Transform applied to the default geometry {@link GeometryLayer} stack (model overlays, block
     * overlays, worn armor) before it runs, letting callers splice custom layers relative to the
     * built-in {@link Slot} slots, or replace the stack. The base body is built separately
     * and is always emitted first. Defaults to {@linkplain UnaryOperator#identity() identity}.
     */
    @lombok.Builder.Default
    private final @NotNull UnaryOperator<LayerStack<GeometryLayer>> layerDecorator = UnaryOperator.identity();

    /**
     * Opens a builder seeded from this instance's current values, for deriving a variant with a
     * few fields changed.
     *
     * @return a builder pre-populated from this instance
     */
    public @NotNull EntityOptionsBuilder mutate() {
        return this.toBuilder();
    }

    /**
     * Builds an instance with every field at its default value.
     *
     * @return the default options
     */
    public static @NotNull EntityOptions defaults() {
        return builder().build();
    }

    /**
     * Emission-order slots for the entity {@code GeometryLayer} stack. The base body is built
     * separately and is always emitted first; these are the appended contributors.
     */
    public enum Slot implements LayerSlot {

        /** Base entity body geometry. */
        BASE_BODY,
        /** Cube-tree model overlays sharing the entity frame (eyes, saddles, coats). */
        MODEL_OVERLAY,
        /** Block-model overlays placed on the body (mooshroom mushrooms, copper-golem flower). */
        BLOCK_OVERLAY,
        /** Worn-armor geometry receiving the enchantment foil. */
        ARMOR;

        /** {@inheritDoc} */
        @Override
        public int order() {
            return ordinal();
        }

        /** {@inheritDoc} */
        @Override
        public @NotNull String id() {
            return name();
        }
    }

    /**
     * Canvas-sizing strategy for {@code EntityRenderer}. The three modes share the same
     * per-entity / family bounds computation but derive canvas dimensions differently.
     */
    public enum FitMode {

        /**
         * Canvas is {@code outputSize x outputSize}. The entity's union silhouette (base
         * model plus non-{@code skipBounds} overlays) is scaled to fit, leaving
         * {@link EntityOptions#getPadding() padding} pixels of clear space inside the canvas on
         * each side. Family siblings are not considered. No upper cap on canvas dimensions - the
         * caller is in control. Use for one-off renders (web API call, webpage icon, catalog
         * tile) where output dimensions are dictated by the consumer.
         */
        OUTPUT_SIZE,

        /**
         * Canvas is sized to this entity's union silhouette at native
         * {@link EntityOptions#getPixelsPerBlock() pixelsPerBlock}{@code / 16} ratio (mirroring
         * the vanilla-reference-harness's per-entity bounds), then expanded by
         * {@link EntityOptions#getPadding() padding} pixels on each side. The longer axis is
         * uniformly capped at {@link EntityOptions#getMaxCanvasSize() maxCanvasSize} post-padding
         * so large entities (ender_dragon) stay manageable. {@link EntityOptions#getOutputSize()
         * outputSize} is ignored. Use for native-resolution single-entity renders.
         */
        UNION_BOUNDS,

        /**
         * Canvas is sized to the union across this entity AND every family member from
         * {@code EntityModelLoader.loadFamilies()} (e.g. cow + cow_cold + cow_warm +
         * mooshroom all share cow's family canvas). Same native ratio +
         * {@link EntityOptions#getPadding() padding} expansion +
         * {@link EntityOptions#getMaxCanvasSize() maxCanvasSize} cap as {@link #UNION_BOUNDS}.
         * Required by {@code TestEntityParityVanilla} since the harness sizes by family-union
         * too; keep {@code padding = 0} to preserve byte-equal output against the harness PNGs.
         */
        FAMILY_BOUNDS

    }

}
