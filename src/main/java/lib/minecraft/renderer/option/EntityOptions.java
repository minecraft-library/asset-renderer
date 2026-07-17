package lib.minecraft.renderer.option;

import dev.simplified.image.Background;
import lib.minecraft.renderer.engine.compose.layer.GeometryLayer;
import lib.minecraft.renderer.engine.compose.layer.LayerStack;
import lib.minecraft.renderer.option.slot.EntitySlot;
import lib.minecraft.renderer.option.spec.AnimationOptions;
import lib.minecraft.renderer.option.spec.ArmorOptions;
import lib.minecraft.renderer.option.spec.OutputOptions;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * Configures a single {@code EntityRenderer} invocation for mob entities. The entity is resolved
 * by {@link #getEntityId() entityId} through the active {@code RendererContext} and rendered as a
 * 3D icon via its {@code EntityModelData} bone/cube tree, posed by {@link OutputOptions#getProjection()
 * projection} (default {@code VANILLA_ISO}). The {@link OutputOptions#getRotation() rotation} field
 * is the user-override layer applied on top of the projection's baked pose.
 *
 * <p>The {@link #getFitMode() fitMode} field selects how the output canvas is sized:
 * {@link FitMode#OUTPUT_SIZE} (default) renders into a fixed {@code canvasSize x canvasSize}
 * square with the entity scaled to fit and {@code padding} pixels of clear space inside;
 * {@link FitMode#UNION_BOUNDS} and {@link FitMode#FAMILY_BOUNDS} size the canvas dynamically
 * from the entity's bounds at native pixel resolution and are intended for vanilla-reference
 * parity work. See each enum constant's javadoc for the precise math.
 *
 * <p>{@link OutputOptions#getSupersample() supersample} composes orthogonally with
 * {@link OutputOptions#isAntiAlias() antiAlias}: supersample renders at {@code supersample x} the
 * final canvas dim then downsamples (SSAA), while antiAlias applies an FXAA post-process on
 * whichever buffer the rasterizer wrote into. Defaults are {@code supersample = 1} and
 * {@code antiAlias = false}, so an end-user one-off render ships with no AA unless explicitly
 * opted into.
 */
@Getter
@Builder(toBuilder = true, access = AccessLevel.PUBLIC)
public class EntityOptions implements RenderOptions {

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
     * The entity-specific axis selections (age, state, carried, dyed collar) as one cohesive value,
     * so this class does not accrete a loose field per axis. Empty / default {@link EntityAppearance}
     * (the default) has no effect on the render. Only consulted under the family form. Lower
     * precedence than {@link #getTextureId() textureId} for texture resolution.
     */
    @lombok.Builder.Default
    private final @NotNull EntityAppearance appearance = EntityAppearance.defaults();

    /** The worn armor pieces (helmet, chestplate, leggings, boots). */
    @lombok.Builder.Default
    private final @NotNull ArmorOptions armor = ArmorOptions.defaults();

    /**
     * Canvas-sizing strategy. {@link FitMode#OUTPUT_SIZE} (default) honours
     * {@link OutputOptions#getCanvasSize() canvasSize} and centres the entity inside a fixed
     * square canvas; {@link FitMode#UNION_BOUNDS} and {@link FitMode#FAMILY_BOUNDS} size the
     * canvas dynamically from the entity's screen bounds for parity work. See each constant's
     * javadoc for the precise sizing math.
     */
    @lombok.Builder.Default
    private final @NotNull FitMode fitMode = FitMode.OUTPUT_SIZE;

    /**
     * Clear-space padding in canvas pixels. Universal across every {@link FitMode}, with
     * mode-specific semantics:
     * <ul>
     *   <li>{@link FitMode#OUTPUT_SIZE} - shrinks the available silhouette area inside the
     *       fixed {@link OutputOptions#getCanvasSize() canvasSize} canvas by {@code padding}
     *       pixels on each side.</li>
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
     * The default output frame for an entity icon - neutral output size, {@code VANILLA_ISO}
     * projection, no supersampling and no FXAA.
     */
    public static final @NotNull OutputOptions DEFAULT_OUTPUT = OutputOptions.defaults();

    /** The shared output frame - output size, projection, facing, rotation, and SSAA / FXAA. */
    @lombok.Builder.Default
    private final @NotNull OutputOptions output = DEFAULT_OUTPUT;

    /**
     * Texture-animation timeline for animated entity textures. Defaults to a single static frame
     * ({@link AnimationOptions#defaults()}); entity texture resolution is tick-aware, so a
     * sidecar-carrying entity texture samples frame 0
     * when static instead of baking the raw vertical strip into the geometry, and plays its flipbook
     * when the caller opts in with {@code frameCount > 1}. Sidecar-less entity textures (the whole
     * vanilla roster) resolve unchanged, so the default render is byte-identical.
     */
    @lombok.Builder.Default
    private final @NotNull AnimationOptions animation = AnimationOptions.defaults();

    /**
     * Background fill composited behind the finished render (solid colour or checkerboard).
     * Defaults to {@link Background#TRANSPARENT}, a no-op that leaves the render's own alpha intact.
     */
    @lombok.Builder.Default
    private final @NotNull Background background = Background.TRANSPARENT;

    /**
     * Transform applied to the default geometry {@link GeometryLayer} stack (model overlays, block
     * overlays, worn armor) before it runs, letting callers splice custom layers relative to the
     * built-in {@link EntitySlot} slots, or replace the stack. The base body is built separately
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
     * Canvas-sizing strategy for {@code EntityRenderer}. The three modes share the same
     * per-entity / family bounds computation but derive canvas dimensions differently.
     */
    public enum FitMode {

        /**
         * Canvas is {@code canvasSize x canvasSize}. The entity's union silhouette (base
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
         * so large entities (ender_dragon) stay manageable. {@link OutputOptions#getCanvasSize()
         * canvasSize} is ignored. Use for native-resolution single-entity renders.
         */
        UNION_BOUNDS,

        /**
         * Canvas is sized to the union across this entity AND every family member from the
         * definition's {@code Entity.members()} canvas group (e.g. camel + camel_husk share one
         * family canvas). Same native ratio +
         * {@link EntityOptions#getPadding() padding} expansion +
         * {@link EntityOptions#getMaxCanvasSize() maxCanvasSize} cap as {@link #UNION_BOUNDS}.
         * Required by {@code TestEntityParityVanilla} since the harness sizes by family-union
         * too; keep {@code padding = 0} to preserve byte-equal output against the harness PNGs.
         */
        FAMILY_BOUNDS

    }

}
