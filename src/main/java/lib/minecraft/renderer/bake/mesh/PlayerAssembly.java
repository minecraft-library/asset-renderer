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
import lib.minecraft.renderer.engine.geometry.Face;
import lib.minecraft.renderer.engine.geometry.FaceTextures;
import lib.minecraft.renderer.engine.geometry.Unwrap;
import lib.minecraft.renderer.engine.layer.LayerStack;
import lib.minecraft.renderer.engine.layer.Layers;
import lib.minecraft.renderer.engine.light.LightingFrame;
import lib.minecraft.renderer.engine.light.Shading;
import lib.minecraft.renderer.engine.math.Vector3f;
import lib.minecraft.renderer.engine.mesh.BoxKit;
import lib.minecraft.renderer.engine.raster.Rasterizer;
import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.port.RendererContext;
import lib.minecraft.renderer.request.PlayerOptions;
import lib.minecraft.renderer.request.slot.PlayerSlot3D;
import lib.minecraft.renderer.vanilla.mesh.CapeMesh;
import lib.minecraft.renderer.vanilla.mesh.HumanoidPart;
import org.jetbrains.annotations.NotNull;

import java.util.function.Consumer;

/**
 * The player in three dimensions - the boxes a body scope is built from and the cape seated on its
 * torso, the light they are read under, and the raster that finishes them.
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

    /**
     * The frame the cape's strips are read in, relative to the frame its box is built in - the half
     * turn about Z.
     * <p>
     * It is {@code HALF_X.then(HALF_Y)}: the {@link AxisSigns#HALF_X upright turn} every body part is
     * read through, composed with the half turn about Y the cape's cube is posed at, since vanilla hangs
     * the cape off the body at a yaw of {@code PI}. So the box's top edge reads the cube's {@code DOWN}
     * strip and its hem the {@code UP} strip, its two side edges read each other's column, and the
     * {@code NORTH} design and the {@code SOUTH} lining stay on the faces that name them.
     * <p>
     * A face map moves a strip between faces and cannot turn one in its own plane. The yaw also turns
     * the two cap strips half a turn in theirs, which {@link #capeTextures} applies after the crop.
     */
    private static final @NotNull AxisSigns CAPE_FRAME = AxisSigns.HALF_Z;

    /**
     * Reads each face of the cape cube out of a cape texture, through the cube's own atlas unwrap in
     * the {@link #CAPE_FRAME cape frame}. The {@link CapeMesh cape model} is a 10x16x1 box at UV
     * origin (0,0), so the vanilla cube unwrap lays it out as:
     * <pre>
     * y=0:  [1px unused][10px DOWN ][10px UP   ]
     * y=1:  [1px WEST  ][10px NORTH][1px EAST][10px SOUTH]  (16 rows)
     * </pre>
     * The {@code NORTH} region ({@code x 1..10}) carries the visible cape design and the {@code SOUTH}
     * region ({@code x 12..21}) the plain lining. The cape hangs on the player's back - its {@code -Z}
     * / {@link Face#NORTH NORTH} face points outward, away from the body - so the design lands
     * outward and the lining against the back.
     * <p>
     * The two {@code 10x1} cap strips are turned half a turn after the crop, the in-plane part of the
     * cape's yaw that the frame cannot carry. On a strip one texel tall that reverses it left to right,
     * which lays the top strip's texels against the design's top row column for column.
     */
    private static @NotNull FaceTextures capeTextures(@NotNull PixelBuffer cape) {
        Unwrap.Atlas unwrap = new Unwrap.Atlas(CapeMesh.CAPE_UV, CapeMesh.CAPE_SIZE, false);
        return face -> {
            PixelBuffer strip = unwrap.crop(cape, CAPE_FRAME.apply(face));
            return face.axis() == 1 ? strip.rotate180() : strip;
        };
    }

    /**
     * Builds cape triangles as a thin box positioned behind and below the torso top edge.
     * The cape width and height are proportional to the torso dimensions.
     *
     * @param triangles the sink the cape's triangles are appended to
     * @param capeTexture the cape sheet the box's faces are cut from
     * @param torsoMin the torso box's minimum corner
     * @param torsoMax the torso box's maximum corner
     */
    public static void addCape(
        @NotNull ConcurrentList<VisibleTriangle> triangles,
        @NotNull PixelBuffer capeTexture,
        @NotNull Vector3f torsoMin,
        @NotNull Vector3f torsoMax
    ) {
        float torsoW = torsoMax.x() - torsoMin.x();
        float torsoH = torsoMax.y() - torsoMin.y();
        float capeW = torsoW * 10f / 8f;
        float capeH = torsoH * 16f / 12f;
        float capeD = torsoW * 1f / 8f;

        float cx = (torsoMin.x() + torsoMax.x()) / 2f;
        float capeTop = torsoMax.y();
        // The cape hangs on the player's back (the north / -Z torso face), the side the iso
        // block-icon pose presents to the camera.
        float capeBack = torsoMin.z();

        Box cape = new Box(cx - capeW / 2f, capeTop - capeH, capeBack - capeD, cx + capeW / 2f, capeTop, capeBack);

        triangles.addAll(BoxKit.buildBox(cape, capeTextures(capeTexture), ColorMath.WHITE));
    }

}
