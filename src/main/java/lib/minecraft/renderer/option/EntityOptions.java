package lib.minecraft.renderer.option;

import dev.simplified.annotations.BuildFlag;
import dev.simplified.annotations.ClassBuilder;
import dev.simplified.annotations.Getter;
import dev.simplified.image.Background;
import lib.minecraft.renderer.engine.compose.layer.GeometryLayer;
import lib.minecraft.renderer.engine.compose.layer.LayerStack;
import lib.minecraft.renderer.option.slot.EntitySlot;
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
 * {@link FitMode#UNION_BOUNDS} and {@link FitMode#GROUP_BOUNDS} size the canvas dynamically
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
@ClassBuilder
public class EntityOptions implements RenderOptions {

    /**
     * The required namespaced id of the entity to render, e.g. {@code "minecraft:zombie"},
     * resolved through the active {@code RendererContext}.
     */
    @BuildFlag(nonNull = true, notEmpty = true)
    private final @NotNull String entityId;

    /**
     * Optional texture id override, resolvable through the active pack stack. Empty (default)
     * uses the entity's own default texture.
     */
    private final @NotNull Optional<String> textureId = Optional.empty();

    /**
     * The entity-specific axis selections (age, state, carried, dyed collar) as one cohesive value,
     * so this class does not accrete a loose field per axis. Empty / default {@link AppearanceOptions}
     * (the default) has no effect on the render. Only consulted under the model form. Lower
     * precedence than {@link #getTextureId() textureId} for texture resolution.
     */
    private final @NotNull AppearanceOptions appearance = AppearanceOptions.defaults();

    /** The worn armor pieces (helmet, chestplate, leggings, boots). */
    private final @NotNull ArmorOptions armor = ArmorOptions.defaults();

    /**
     * Canvas-sizing strategy. {@link FitMode#OUTPUT_SIZE} (default) honours
     * {@link OutputOptions#getCanvasSize() canvasSize} and centres the entity inside a fixed
     * square canvas; {@link FitMode#UNION_BOUNDS} and {@link FitMode#GROUP_BOUNDS} size the
     * canvas dynamically from the entity's screen bounds for parity work. See each constant's
     * javadoc for the precise sizing math.
     */
    private final @NotNull FitMode fitMode = FitMode.OUTPUT_SIZE;

    /**
     * Clear-space padding in canvas pixels. Universal across every {@link FitMode}, with
     * mode-specific semantics:
     * <ul>
     *   <li>{@link FitMode#OUTPUT_SIZE} - shrinks the available silhouette area inside the
     *       fixed {@link OutputOptions#getCanvasSize() canvasSize} canvas by {@code padding}
     *       pixels on each side.</li>
     *   <li>{@link FitMode#UNION_BOUNDS}, {@link FitMode#GROUP_BOUNDS} - expands the
     *       dynamically-computed canvas by {@code padding} pixels on each side around the
     *       native-sized silhouette.</li>
     * </ul>
     * Default {@code 0} so the BOUNDS modes are unchanged from current parity behaviour
     * without an explicit override.
     */
    private final int padding = 0;

    /**
     * Texel resolution in image-pixels per Minecraft block-unit, consumed by the two
     * {@code BOUNDS} {@link FitMode}s to size the canvas (the per-axis ratio is
     * {@code pixelsPerBlock / 16} since vanilla authors cubes in entity-pixels). Ignored by
     * {@link FitMode#OUTPUT_SIZE}. Defaults to {@code 256}.
     *
     * <p>The vanilla-reference-harness's {@code HarnessConfig.PIXELS_PER_BLOCK} holds this same
     * number and sizes the same canvas from the other side, so changing it means editing both
     * constants in one commit.
     */
    private final int pixelsPerBlock = 256;

    /**
     * Hard cap in pixels on the longer canvas axis for the two {@code BOUNDS} {@link FitMode}s.
     * An entity whose bounds would exceed this (ender_dragon, giant) is scaled down uniformly so
     * the longer side equals the cap. Ignored by {@link FitMode#OUTPUT_SIZE}. Defaults to
     * {@code 1024}.
     *
     * <p>The vanilla-reference-harness's {@code HarnessConfig.MAX_CANVAS_SIZE} holds this same number
     * and applies the same cap from the other side, so changing it means editing both constants in
     * one commit.
     */
    private final int maxCanvasSize = 1024;

    /**
     * The default output frame for an entity icon - neutral output size, {@code VANILLA_ISO}
     * projection, no supersampling and no FXAA.
     */
    public static final @NotNull OutputOptions DEFAULT_OUTPUT = OutputOptions.defaults();

    /** The shared output frame - output size, projection, facing, rotation, and SSAA / FXAA. */
    private final @NotNull OutputOptions output = DEFAULT_OUTPUT;

    /**
     * Texture-animation timeline for animated entity textures. Defaults to a single static frame
     * ({@link AnimationOptions#defaults()}); entity texture resolution is tick-aware, so a
     * sidecar-carrying entity texture samples frame 0
     * when static instead of baking the raw vertical strip into the geometry, and plays its flipbook
     * when the caller opts in with {@code frameCount > 1}. Sidecar-less entity textures (the whole
     * vanilla roster) resolve unchanged, so the default render is byte-identical.
     */
    private final @NotNull AnimationOptions animation = AnimationOptions.defaults();

    /**
     * The id of the output style this render selects on the entity's shipped style catalog - which
     * mechanisms move the subject and which appearance bone toggles the selection entails. The
     * four universal ids {@code "bind"}, {@code "idle"}, {@code "stride"} and {@code "animated"}
     * resolve on every entity; any other id is the entity's own and is validated against the
     * catalog at render, failing loud with the supported set. Defaults to {@code "bind"}, the
     * authored still pose, so a caller that asks for nothing renders the still subject.
     *
     * <p>A free string deliberately - the id set is open per entity, so no enum can hold it, and
     * the typed constants for the universal ids live on the catalog row type.
     */
    private final @NotNull String style = "bind";

    /**
     * Whether the entity's bones stand where its mesh authors them or where its model puts them at
     * each frame's tick. Defaults to {@link PoseMode#BIND}, the authored pose, so a caller that asks
     * for nothing renders the still subject it always did.
     *
     * <p>The three named presets are orthogonal to {@link #getAnimation() animation}, which chooses
     * the instants that are sampled rather than whether anything moves between them: a caller wanting
     * a subject that moves sets both, and one setting a named preset alone gets a single frame of a
     * subject posed at one tick.
     *
     * <p><b>{@link PoseMode#ANIMATED} is the exception, and is the whole of why it exists.</b> It
     * answers both halves - the gait that moves this subject, and the strip that movement plays over -
     * so a caller asking for a moving entity is not also obliged to know how long its movement takes.
     * A frame count the caller named is theirs and is kept.
     */
    private final @NotNull PoseMode poseMode = PoseMode.BIND;

    /**
     * Background fill composited behind the finished render (solid colour or checkerboard).
     * Defaults to {@link Background#TRANSPARENT}, a no-op that leaves the render's own alpha intact.
     */
    private final @NotNull Background background = Background.TRANSPARENT;

    /**
     * Transform applied to the default geometry {@link GeometryLayer} stack (model overlays, block
     * overlays, worn armor) before it runs, letting callers splice custom layers relative to the
     * built-in {@link EntitySlot} slots, or replace the stack. The base body is built separately
     * and is always emitted first. Defaults to {@linkplain UnaryOperator#identity() identity}.
     */
    private final @NotNull UnaryOperator<LayerStack<GeometryLayer>> layerDecorator = UnaryOperator.identity();

    /**
     * Builds options for one entity with every other knob at its default.
     *
     * @param entityId the namespaced id of the entity to render
     * @return the options
     */
    public static @NotNull EntityOptions of(@NotNull String entityId) {
        return builder().entityId(entityId).build();
    }

    /**
     * Canvas-sizing strategy for {@code EntityRenderer}. The three modes share the same
     * per-entity / group bounds computation but derive canvas dimensions differently.
     */
    public enum FitMode {

        /**
         * Canvas is {@code canvasSize x canvasSize}. The entity's union silhouette (base
         * model plus non-{@code skipBounds} overlays) is scaled to fit, leaving
         * {@link EntityOptions#getPadding() padding} pixels of clear space inside the canvas on
         * each side. Group siblings are not considered. No upper cap on canvas dimensions - the
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
         * Canvas is sized to the union across this entity AND every group member from the
         * definition's {@code Entity.members()} canvas group (e.g. camel + camel_husk share one
         * group canvas). Same native ratio +
         * {@link EntityOptions#getPadding() padding} expansion +
         * {@link EntityOptions#getMaxCanvasSize() maxCanvasSize} cap as {@link #UNION_BOUNDS}.
         * Required by {@code TestEntityParityVanilla} since the harness sizes by group-union
         * too; keep {@code padding = 0} to preserve byte-equal output against the harness PNGs.
         */
        GROUP_BOUNDS

    }

    /**
     * Whether an entity's bones are drawn where they are authored or where its model puts them, and
     * what the subject is taken to be doing while they are.
     *
     * <p>The mesh a frame is built from is the whole of what varies between them - the canvas, the
     * layers, the textures and the lighting are reached the same way whichever is chosen.
     *
     * <p><b>What separates the two moving presets is which figures stop answering their resting
     * value.</b> A pose is a function of what the caller says about the subject, and a subject that
     * is merely standing there answers everything but elapsed time with what it rests at. Naming a
     * gait is naming the further figures that stop resting; it is not a second pose mechanism, and
     * every one of them goes through {@code PoseKit} the same way.
     */
    public enum PoseMode {

        /**
         * The authored bind pose - every bone at the pivot, rotation and scale its mesh declares.
         *
         * <p>The default, and what every subject draws whose model poses nothing, whose pose could
         * not be read, and which carries no pose at all. It hands back the very mesh it was given
         * rather than an equal one, so a caller that asks for nothing allocates nothing and rounds
         * nothing.
         */
        BIND,

        /**
         * A subject standing where it is, at each frame's tick.
         *
         * <p>Elapsed time is the only figure that stops resting, so what moves is what the shipped
         * pose table drives from it - a head that bobs, a tail that sways, a wing that beats. The
         * subject walks at no speed, swings at nothing and holds nothing.
         */
        IDLE,

        /**
         * A subject walking on the spot, at each frame's tick.
         *
         * <p>{@link #IDLE} plus the two figures a stride is carried on: the amplitude a limb swings
         * through and the phase it is at. Vanilla accumulates the phase by the amplitude once a
         * tick rather than deriving it from the clock, so the two are one schedule and not two
         * inputs - which is why the preset owns them and a caller does not set them separately.
         *
         * <p>The amplitude is the full one. Vanilla clamps what it accumulates to one, so a subject
         * at that value is walking as hard as anything in the corpus ever walks, and every lesser
         * gait is a fraction of the same curve rather than a different one.
         *
         */
        WALK,

        /**
         * Whatever moves this subject, chosen per subject instead of named by the caller.
         *
         * <p>Resolves to the least preset that reaches the subject's own movement - {@link #IDLE}
         * where elapsed age already moves it, {@link #WALK} where everything it animates rides a
         * stride resting at zero. What it resolves to is read off the subject's own poses, evaluated
         * across one excursion at both, so a caller asking for movement needs to know nothing about
         * which figures the subject happens to read.
         *
         * <p><b>It supplies the strip as well as the gait</b>, so what comes back is an animation
         * rather than one instant of one. A caller who named a frame count keeps it; one who named
         * none is given the excursion the subject's own movement plays over, which loops.
         *
         * <p><b>A subject nothing drives resolves to {@link #IDLE}, stands still, and stays a single
         * frame.</b> That is the honest answer rather than a defect: some subjects animate on a figure
         * only a ticking world fills, and others write nothing the tick drives at all. Walking one of
         * those would invent a gait its own model never asked for, and wrapping it in an animated
         * container would claim a movement that is not there.
         */
        ANIMATED

    }

}
