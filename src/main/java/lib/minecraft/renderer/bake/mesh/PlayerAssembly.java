package lib.minecraft.renderer.bake.mesh;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.ImageData;
import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.bake.texture.GlintKit;
import lib.minecraft.renderer.engine.draw.GeometryLayer;
import lib.minecraft.renderer.engine.draw.VisibleTriangle;
import lib.minecraft.renderer.engine.frame.RasterPass;
import lib.minecraft.renderer.engine.frame.Timeline;
import lib.minecraft.renderer.engine.geometry.AxisSigns;
import lib.minecraft.renderer.engine.geometry.Box;
import lib.minecraft.renderer.engine.geometry.EulerRotation;
import lib.minecraft.renderer.engine.layer.LayerStack;
import lib.minecraft.renderer.engine.layer.Layers;
import lib.minecraft.renderer.engine.light.LightingFrame;
import lib.minecraft.renderer.engine.light.Shading;
import lib.minecraft.renderer.engine.mesh.BoxKit;
import lib.minecraft.renderer.engine.raster.Rasterizer;
import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.port.RendererContext;
import lib.minecraft.renderer.request.PlayerOptions;
import lib.minecraft.renderer.slot.PlayerSlot3D;
import lib.minecraft.renderer.vanilla.mesh.HumanoidPart;
import org.jetbrains.annotations.NotNull;

import java.util.function.Consumer;

/**
 * The player in three dimensions - the boxes a body scope is built from, the light they are read
 * under, and the raster that finishes them.
 */
@UtilityClass
@Parity(claim = "engine-renders", mode = Mode.DEMOTE)
@Parity(claim = "player-geometry")
public class PlayerAssembly {

    /**
     * Overlay (hat / hood / second layer) outset over the base cube, in the body scopes' frame.
     */
    private static final float OVERLAY_INFLATE = 0.01f;

    /**
     * Fraction of the canvas's smaller dimension the 3D silhouette spans after auto-fit. {@code 1.0}
     * fills the canvas to match the entity renderer's {@code OUTPUT_SIZE} fit (which fills the whole
     * canvas at {@code padding = 0}), so a player and an entity render at the same footprint on the same
     * canvas; outset overlays / armor that extend past the body may touch the frame edge, as they do for
     * entities.
     */
    private static final float PLAYER_FILL = 1.0f;

    /**
     * Whether the skin is wide enough to have overlay layers.
     *
     * @param skin the skin sheet to measure
     * @return whether the sheet carries the full overlay column
     */
    public static boolean hasOverlay(@NotNull PixelBuffer skin) {
        return skin.width() >= 64 && skin.height() >= 64;
    }

    /**
     * Whether the skin is wide enough to have hat overlay (smaller threshold than full overlay).
     *
     * @param skin the skin sheet to measure
     * @return whether the sheet carries the head's hat layer
     */
    public static boolean hasHatOverlay(@NotNull PixelBuffer skin) {
        return skin.width() >= 48 && skin.height() >= 16;
    }

    /**
     * Re-shades an assembled player stack under the lighting entry vanilla binds for a humanoid drawn in
     * a GUI, replacing the cardinal bucket {@link BoxKit#buildBox} bakes at emit time. Every 3D
     * scope goes through this after its stack is folded, so body, overlay, cape, wings and armour are lit
     * as one draw, the way the one {@code setupFor} vanilla issues per GUI entity lights them.
     * <p>
     * {@link AxisSigns#MIRROR_Z} rather than the {@link AxisSigns#MIRROR_Y} entity geometry takes: the player's
     * boxes are built upright where a vanilla mesh is Y-down, the two frames sit a {@link AxisSigns#HALF_X}
     * apart, and {@code MIRROR_Y} composed with that half turn is {@code MIRROR_Z}.
     *
     * @param triangles the folded player stack
     * @param lighting the frame the scope is lit at
     * @return the re-shaded triangles
     */
    public static @NotNull ConcurrentList<VisibleTriangle> relight(
        @NotNull ConcurrentList<VisibleTriangle> triangles,
        @NotNull LightingFrame lighting
    ) {
        return Shading.relightForEntityInUi(triangles, lighting, AxisSigns.MIRROR_Z);
    }

    /**
     * Renders the body-plus-armour form of a multi-part scope - the scope's own body parts, then
     * whatever it wears over them.
     * <p>
     * {@link PlayerOptions.Type#BUST} and {@link PlayerOptions.Type#FULL} differ in nothing but the scope
     * token, which is why it is a parameter here rather than two bodies that have to be kept in step.
     * {@link PlayerOptions.Type#SKULL} deliberately does not route through this: it draws one box with its
     * own wider-gated hat overlay rather than {@link #addBody}, and it seats no back layer.
     *
     * @param skin the resolved player skin sheet
     * @param engine the rasterizer the scope is drawn through
     * @param options the render options
     * @param type the player render scope
     * @param worn appends what the scope wears over its body, in draw order - the armour, then the cape
     *     or elytra seated on the scope's torso box
     * @param lighting the frame the scope is lit at
     * @param context the renderer context the armour foil resolves its texture through
     * @return the finished image
     */
    public static @NotNull ImageData renderScope3D(
        @NotNull PixelBuffer skin,
        @NotNull Rasterizer engine,
        @NotNull PlayerOptions options,
        @NotNull PlayerOptions.Type type,
        @NotNull Consumer<LayerStack<GeometryLayer>> worn,
        @NotNull LightingFrame lighting,
        @NotNull RendererContext context
    ) {
        ConcurrentList<VisibleTriangle> triangles = Concurrent.newList();

        LayerStack<GeometryLayer> stack = new LayerStack<>();
        stack.append(PlayerSlot3D.BODY, sink -> addBody(sink, skin, type, options));
        worn.accept(stack);

        Layers.foldInto(stack, options.getGeometryLayerDecorator(), triangles);

        return rasterize3D(engine, relight(triangles, lighting), options, context);
    }

    /**
     * Rasterizes the assembled body + armor triangles to a finished image: auto-fits the silhouette
     * to fill the canvas ({@link #PLAYER_FILL}), applies supersampling (SSAA) and optional FXAA,
     * then composites the armor glint. Shared by all three 3D sub-renderers.
     *
     * @param engine the rasterizer the scope is drawn through
     * @param triangles the folded, re-lit player stack
     * @param options the render options
     * @param context the renderer context the armour foil resolves its texture through
     * @return the finished image
     */
    public static @NotNull ImageData rasterize3D(
        @NotNull Rasterizer engine,
        @NotNull ConcurrentList<VisibleTriangle> triangles,
        @NotNull PlayerOptions options,
        @NotNull RendererContext context
    ) {
        int size = options.getOutput().getCanvasSize();
        boolean enchanted = options.getArmor().hasEnchanted();
        int ssaa = options.getOutput().getSupersample();
        // The glint mask is recorded at the raster size, then box-downsampled to the output so the
        // foil is confined to the armor (not the bare body) after the SSAA blit.
        // The caller's rotation is composed into the engine's camera pose at construction, so the
        // fitted rasterize applies no separate model-spin - EulerRotation.NONE. Default renders leave
        // the base player pose.
        return Timeline.Static.ZERO.bake(
            RasterPass.of(size, size, ssaa, options.getOutput().isAntiAlias(),
                    (target, tick) -> engine.rasterizeFitted(triangles, target, EulerRotation.NONE, PLAYER_FILL))
                .withMask(enchanted)
                .finishing(GlintKit.Foil.armor(context::resolveTexture, enchanted)));
    }

    /**
     * Adds every part a scope draws, in that scope's own draw order.
     * <p>
     * The single-part {@link PlayerOptions.Type#SKULL} scope does not route through here: its head is
     * a plain unit cube whose overlay carries its own hardcoded inflation and its own, wider
     * sheet-format test, so folding the two together would change what a legacy skin draws.
     *
     * @param triangles the sink the scope's boxes are appended to
     * @param skin the resolved player skin sheet
     * @param type the player render scope
     * @param options the render options
     */
    public static void addBody(
        @NotNull ConcurrentList<VisibleTriangle> triangles,
        @NotNull PixelBuffer skin,
        @NotNull PlayerOptions.Type type,
        @NotNull PlayerOptions options
    ) {
        for (HumanoidPart part : type.lattice().parts())
            addBodyPart(triangles, skin, part, type.lattice().boxOf(part), options);
    }

    /**
     * Adds a body part's base skin cube and optional overlay to the triangle list.
     */
    private static void addBodyPart(
        @NotNull ConcurrentList<VisibleTriangle> triangles,
        @NotNull PixelBuffer skin,
        @NotNull HumanoidPart part,
        @NotNull Box box,
        @NotNull PlayerOptions options
    ) {
        triangles.addAll(BoxKit.buildBox(box, part.textures(skin, false), ColorMath.WHITE));
        if (options.getSkin().isRenderOverlay() && hasOverlay(skin))
            triangles.addAll(BoxKit.buildBox(
                box.expand(OVERLAY_INFLATE), part.textures(skin, true), ColorMath.WHITE));
    }

}
