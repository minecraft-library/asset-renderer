package lib.minecraft.renderer;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.PixelBuffer;
import dev.simplified.image.pixel.BlendMode;
import dev.simplified.image.pixel.ColorMath;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.bake.armor.ElytraKit;
import lib.minecraft.renderer.bake.armor.EquipmentKit;
import lib.minecraft.renderer.bake.mesh.BlockGeometryKit;
import lib.minecraft.renderer.bake.mesh.EntityGeometryKit;
import lib.minecraft.renderer.content.index.RendererContext;
import lib.minecraft.renderer.engine.camera.Camera;
import lib.minecraft.renderer.engine.camera.FitFrame;
import lib.minecraft.renderer.engine.camera.Lens;
import lib.minecraft.renderer.engine.draw.PassDeclaration;
import lib.minecraft.renderer.engine.draw.SampledTexture;
import lib.minecraft.renderer.engine.draw.SurfaceTraits;
import lib.minecraft.renderer.engine.draw.VisibleTriangle;
import lib.minecraft.renderer.engine.math.Matrix4f;
import lib.minecraft.renderer.engine.math.Vector2f;
import lib.minecraft.renderer.engine.math.Vector3f;
import lib.minecraft.renderer.engine.raster.Rasterizer;
import lib.minecraft.renderer.support.ClientAssetsExtension;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * Coverage of the passes vanilla draws through a cutout pipeline, which declares no blend function:
 * an entity body whose model names no translucent render type, an equipment layer, worn armour, the
 * wings and a block entity's bones. Each is read off the producer that builds it, and a partly
 * transparent texel drawn through it over an opaque one is written whole - its colour and its alpha -
 * rather than blended at its alpha.
 * <p>
 * Read nearest, a vanilla texture makes such a fragment only where it carries a partly transparent
 * texel - the strider saddle and the black and cyan llama decor among the equipment textures - so
 * these cases are the composite's witness rather than any stored sweep.
 */
@ExtendWith(ClientAssetsExtension.class)
@DisplayName("A cutout pass writes a surviving fragment whole")
class CutoutPassTest {

    /** The canvas edge every case draws at. */
    private static final int SIZE = 32;

    /** The centre pixel, which both quads cover. */
    private static final int CENTRE = SIZE / 2;

    /** A square-on orthographic camera. */
    private static final @NotNull Camera CAMERA = Camera.identity(Lens.orthographic(0.5f));

    /** The opaque colour the backdrop is drawn in. */
    private static final int BACKDROP = 0xFF0000FF;

    /** The partly transparent texel drawn over it - above the alpha cutout and below opaque. */
    private static final int PARTIAL = 0x80FF0000;

    /** A plain opaque sheet, for producers whose pass rather than whose colour is under test. */
    private static final @NotNull SampledTexture SHEET = sheet(64, 64, 0xFFFFFFFF);

    private static RendererContext context;

    @BeforeAll
    static void loadContext() {
        context = ClientAssetsExtension.context();
    }

    /**
     * The passes vanilla draws through a cutout pipeline, each read off the producer that builds it.
     *
     * @return each pass kind's name against the pass its producer declares, in the order they run
     */
    private static @NotNull Map<String, Supplier<PassDeclaration>> cutoutPasses() {
        Map<String, Supplier<PassDeclaration>> passes = new LinkedHashMap<>();
        passes.put("a cutout entity body", () -> {
            Entity zombie = context.findEntity("minecraft:zombie").orElseThrow();
            return passOf(EntityGeometryKit.buildTriangles(zombie.model(), SHEET,
                new EntityGeometryKit.EntityBuildParams(FitFrame.IDENTITY, zombie.bodyPass(), ColorMath.WHITE)).triangles());
        });
        passes.put("an equipment layer", () -> {
            Entity strider = context.findEntity("minecraft:strider").orElseThrow();
            return passOf(EntityRenderer.equipmentLayerTriangles(strider.model(),
                new EquipmentKit.Layer(SHEET, ColorMath.WHITE), FitFrame.IDENTITY));
        });
        passes.put("worn armour", SurfaceTraits.WORN_SHELL::pass);
        passes.put("the wings", () -> passOf(ElytraKit.buildWings3D(context, false, FitFrame.IDENTITY, Optional.empty(), 0)));
        passes.put("a block entity's bones", () -> {
            Entity zombie = context.findEntity("minecraft:zombie").orElseThrow();
            return passOf(BlockGeometryKit.buildFromBones(zombie.model(), SHEET, ColorMath.WHITE));
        });
        passes.put("a block entity's bones turned", () -> {
            Entity zombie = context.findEntity("minecraft:zombie").orElseThrow();
            ConcurrentList<VisibleTriangle> bones = BlockGeometryKit.buildFromBones(zombie.model(), SHEET, ColorMath.WHITE);
            return passOf(BlockGeometryKit.applyRotation(bones, Matrix4f.createRotationY(1f)));
        });
        return passes;
    }

    @TestFactory
    @DisplayName("writes a partly transparent texel whole over an opaque one")
    @NotNull Stream<DynamicTest> writesAPartlyTransparentTexelWhole() {
        return cutoutPasses().entrySet().stream().map(entry -> DynamicTest.dynamicTest(entry.getKey(), () -> {
            PassDeclaration pass = entry.getValue().get();
            assertThat(entry.getKey() + " is the cutout pass", pass.blend(), is(BlendMode.REPLACE));
            assertThat(entry.getKey() + " writes the texel whole", drawnOverBackdrop(pass), is(PARTIAL));
        }));
    }

    @Test
    @DisplayName("a body whose model names a translucent render type still blends")
    void aTranslucentBodyStillBlends() {
        Entity allay = context.findEntity("minecraft:allay").orElseThrow();
        assertThat(allay.bodyPass(), is(PassDeclaration.DEFAULT));
        int drawn = drawnOverBackdrop(allay.bodyPass());
        assertThat("the backdrop shows through", drawn, is(not(PARTIAL)));
        assertThat("the composite is opaque", ColorMath.alpha(drawn), is(255));
    }

    /**
     * The pass every triangle a producer built carries, failing where they do not share one.
     *
     * @param triangles what the producer built
     * @return the one pass they carry
     */
    private static @NotNull PassDeclaration passOf(@NotNull List<VisibleTriangle> triangles) {
        assertThat("the producer built something", triangles.isEmpty(), is(false));
        PassDeclaration pass = triangles.getFirst().traits().pass();
        assertThat(triangles.stream().map(triangle -> triangle.traits().pass()).toList(), everyItem(is(pass)));
        return pass;
    }

    /**
     * Draws an opaque backdrop, then the partly transparent texel nearer the camera through the given
     * pass, both unshaded, and answers the centre pixel.
     *
     * @param pass the pass the nearer quad is drawn through
     * @return the centre pixel
     */
    private static int drawnOverBackdrop(@NotNull PassDeclaration pass) {
        ConcurrentList<VisibleTriangle> scene = Concurrent.newList();
        scene.addAll(quad(-0.1f, BACKDROP, PassDeclaration.DEFAULT));
        scene.addAll(quad(0.1f, PARTIAL, pass));
        PixelBuffer canvas = PixelBuffer.canvas(SIZE, SIZE);
        new Rasterizer(CAMERA).rasterize(scene, canvas);
        return canvas.getPixel(CENTRE, CENTRE);
    }

    /**
     * Builds a square across the centre in one colour, two-sided and unshaded.
     *
     * @param z the depth, larger being nearer
     * @param argb the colour of its one texel
     * @param pass the pass it is drawn through
     * @return the square's two triangles
     */
    private static @NotNull List<VisibleTriangle> quad(float z, int argb, @NotNull PassDeclaration pass) {
        SampledTexture texture = sheet(2, 2, argb);
        SurfaceTraits traits = new SurfaceTraits(false, false, false, true, pass);
        Vector3f normal = new Vector3f(0f, 0f, 1f);
        Vector3f topLeft = new Vector3f(-0.25f, 0.25f, z);
        Vector3f bottomLeft = new Vector3f(-0.25f, -0.25f, z);
        Vector3f bottomRight = new Vector3f(0.25f, -0.25f, z);
        Vector3f topRight = new Vector3f(0.25f, 0.25f, z);
        return List.of(
            new VisibleTriangle(topLeft, bottomLeft, bottomRight,
                new Vector2f(0f, 0f), new Vector2f(0f, 1f), new Vector2f(1f, 1f), texture, ColorMath.WHITE, normal, 1f, traits),
            new VisibleTriangle(topLeft, bottomRight, topRight,
                new Vector2f(0f, 0f), new Vector2f(1f, 1f), new Vector2f(1f, 0f), texture, ColorMath.WHITE, normal, 1f, traits));
    }

    /**
     * Builds a sheet of one colour, read nearest and repeating.
     *
     * @param width the sheet width
     * @param height the sheet height
     * @param argb the colour
     * @return the sheet
     */
    private static @NotNull SampledTexture sheet(int width, int height, int argb) {
        PixelBuffer pixels = PixelBuffer.straight(width, height);
        pixels.fill(argb);
        return SampledTexture.of(pixels);
    }

}
