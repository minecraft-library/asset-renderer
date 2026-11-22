package lib.minecraft.renderer;

import lib.minecraft.renderer.call.request.AnimationOptions;
import lib.minecraft.renderer.call.request.AtlasOptions;
import lib.minecraft.renderer.call.request.BlockOptions;
import lib.minecraft.renderer.call.request.EntityOptions;
import lib.minecraft.renderer.call.request.FluidOptions;
import lib.minecraft.renderer.call.request.ItemOptions;
import lib.minecraft.renderer.call.request.MenuOptions;
import lib.minecraft.renderer.call.request.OutputOptions;
import lib.minecraft.renderer.call.request.PlayerOptions;
import lib.minecraft.renderer.call.request.PortalOptions;
import lib.minecraft.renderer.call.request.RenderOptions;
import lib.minecraft.renderer.content.index.RendererContext;
import lib.minecraft.renderer.engine.frame.RenderLimits;
import lib.minecraft.renderer.exception.RenderException;
import lib.minecraft.renderer.support.ClientAssetsExtension;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A request naming more than a render's ceilings is refused by the renderer it is handed to, with a
 * {@link RenderException} that names what was over, where it used to be allocated for.
 * <p>
 * Every subject renderer reads its size through one {@link OutputOptions} and its frame count through
 * one {@link AnimationOptions}, so each is held to the ceilings by those two, and the frames a render
 * bakes are held together to the pixel ceiling whatever sized them. The renderers that lay other
 * renders out are held where they size their own canvas. Each case here asks for one step past a
 * ceiling, and for a size far past it, on a context that holds no pack: the refusal is made before
 * anything is read or drawn.
 */
@DisplayName("A request past a render's ceilings is refused by its renderer")
class RequestCeilingTest {

    private static final @NotNull RendererContext EMPTY = RendererContext.builder().build();

    /** Every subject renderer but the entity's, as a render of the given output frame and animation. */
    private static @NotNull Map<String, Function<OutputOptions, Function<AnimationOptions, RenderOptions>>> subjects() {
        Map<String, Function<OutputOptions, Function<AnimationOptions, RenderOptions>>> subjects = new LinkedHashMap<>();
        subjects.put("block", output -> animation -> BlockOptions.builder().blockId("minecraft:stone").output(output).animation(animation).build());
        subjects.put("item", output -> animation -> ItemOptions.builder().itemId("minecraft:stick").output(output).animation(animation).build());
        subjects.put("fluid", output -> animation -> FluidOptions.builder().output(output).animation(animation).build());
        subjects.put("portal", output -> animation -> PortalOptions.builder().output(output).animation(animation).build());
        subjects.put("player", output -> animation -> PlayerOptions.builder().output(output).build());
        return subjects;
    }

    private static void render(@NotNull RenderOptions options) {
        switch (options) {
            case BlockOptions block -> new BlockRenderer(EMPTY).render(block);
            case ItemOptions item -> new ItemRenderer(EMPTY).render(item);
            case FluidOptions fluid -> new FluidRenderer(EMPTY).render(fluid);
            case PortalOptions portal -> new PortalRenderer(EMPTY).render(portal);
            case PlayerOptions player -> new PlayerRenderer(EMPTY).render(player);
            default -> throw new IllegalArgumentException(options.getClass().getName());
        }
    }

    @Test
    @DisplayName("a canvas past the edge ceiling is refused by every subject renderer")
    void aCanvasPastTheCeilingIsRefused() {
        for (int size : new int[] { RenderLimits.MAX_EDGE + 1, 40_000, Integer.MAX_VALUE })
            subjects().forEach((subject, options) -> {
                OutputOptions output = OutputOptions.builder().canvasSize(size).build();
                RenderException refusal = assertThrows(RenderException.class,
                    () -> render(options.apply(output).apply(AnimationOptions.defaults())), subject + " at " + size);
                assertThat(subject, refusal.getMessage(), containsString("Canvas size '" + size + "'"));
            });
    }

    @Test
    @DisplayName("a supersample factor that takes the raster past the edge ceiling is refused where a render rasterizes at it")
    void aRasterPastTheCeilingIsRefused() {
        for (String subject : new String[] { "block", "fluid", "portal", "player" }) {
            OutputOptions output = OutputOptions.builder().canvasSize(512).supersample(64).build();
            RenderException refusal = assertThrows(RenderException.class,
                () -> render(subjects().get(subject).apply(output).apply(AnimationOptions.defaults())), subject);
            assertThat(subject, refusal.getMessage(), containsString("Supersample '64'"));
        }
    }

    @Test
    @DisplayName("a frame count past the frame ceiling is refused by every animated renderer")
    void aFrameCountPastTheCeilingIsRefused() {
        for (int frames : new int[] { RenderLimits.MAX_FRAMES + 1, Integer.MAX_VALUE })
            for (String subject : new String[] { "block", "fluid", "portal" }) {
                AnimationOptions animation = AnimationOptions.builder().frameCount(frames).build();
                RenderException refusal = assertThrows(RenderException.class,
                    () -> render(subjects().get(subject).apply(OutputOptions.defaults()).apply(animation)), subject + " over " + frames);
                assertThat(subject, refusal.getMessage(), containsString("Frame count '" + frames + "'"));
            }
    }

    @Test
    @DisplayName("frames that are each under the ceilings are refused once they pass the pixel ceiling together")
    void framesPastThePixelCeilingAreRefused() {
        OutputOptions output = OutputOptions.builder().canvasSize(4096).build();
        AnimationOptions animation = AnimationOptions.builder().frameCount(17).build();
        RenderException refusal = assertThrows(RenderException.class,
            () -> render(subjects().get("block").apply(output).apply(animation)));
        assertThat(refusal.getMessage(), containsString("'4096x4096' over '17' frames"));
    }

    @Test
    @DisplayName("a menu of more rows or columns than its ceiling is refused before a cell is laid out")
    void aMenuPastItsCeilingIsRefused() {
        int[][] grids = { { MenuOptions.MAX_GRID_SIDE + 1, 9 }, { 3, MenuOptions.MAX_GRID_SIDE + 1 }, { Integer.MAX_VALUE, Integer.MAX_VALUE } };
        for (int[] grid : grids) {
            MenuOptions options = MenuOptions.builder().rows(grid[0]).columns(grid[1]).build();
            RenderException refusal = assertThrows(RenderException.class, () -> new MenuRenderer(EMPTY).render(options));
            assertThat(refusal.getMessage(), containsString("Menu rows '" + grid[0] + "' and columns '" + grid[1] + "'"));
        }

        assertThat("the ceiling itself is laid out",
            MenuOptions.builder().rows(MenuOptions.MAX_GRID_SIDE).columns(MenuOptions.MAX_GRID_SIDE).build().screen().ownRows(),
            is(MenuOptions.MAX_GRID_SIDE));
        assertThat("and a screen that carries its own grid reads neither count",
            MenuOptions.builder().type(MenuOptions.Type.HOPPER).rows(Integer.MAX_VALUE).build().screen().ownRows(), is(1));
    }

    @Test
    @DisplayName("an atlas tile past the edge ceiling is refused before a subject is listed")
    void anAtlasTilePastTheCeilingIsRefused() {
        AtlasOptions options = AtlasOptions.builder().tileSize(RenderLimits.MAX_EDGE + 1).build();
        assertThat(assertThrows(RenderException.class, () -> new AtlasRenderer(EMPTY).render(options)).getMessage(),
            containsString("Atlas tile size '16385'"));
    }

    @Test
    @DisplayName("a text canvas past the pixel ceiling is refused, alone or over its frames")
    void aTextCanvasPastThePixelCeilingIsRefused() {
        TextRenderer.requireCanvas(8192f, 8192f, 1);
        TextRenderer.requireCanvas(200f, 100f, 20);
        TextRenderer.requireCanvas(200f, 100_000f, 1);

        assertThat(assertThrows(RenderException.class, () -> TextRenderer.requireCanvas(8193f, 8192f, 1)).getMessage(),
            containsString("Text canvas '16386x16384'"));
        assertThrows(RenderException.class, () -> TextRenderer.requireCanvas(10f, 1e9f, 1));
        assertThrows(RenderException.class, () -> TextRenderer.requireCanvas(4096f, 4096f, 5));
        assertThrows(RenderException.class, () -> TextRenderer.requireCanvas(Float.MAX_VALUE, Float.MAX_VALUE, Integer.MAX_VALUE));
    }

    /** The refusals that are made only once the vanilla pack has named a subject. */
    @Nested
    @DisplayName("over the vanilla pack")
    @ExtendWith(ClientAssetsExtension.class)
    class OverVanilla {

        @Test
        @DisplayName("an atlas sheet no buffer holds is refused before a tile is drawn")
        void anAtlasSheetNoBufferHoldsIsRefused() {
            AtlasOptions options = AtlasOptions.builder()
                .source(AtlasOptions.Scope.BLOCK)
                .filter(Optional.of("minecraft:stone"::equals))
                .tileSize(1024)
                .columns(Integer.MAX_VALUE)
                .progressLogging(false)
                .build();
            assertThat(assertThrows(RenderException.class, () -> new AtlasRenderer(ClientAssetsExtension.context()).render(options)).getMessage(),
                containsString("Atlas sheet"));
        }

        @Test
        @DisplayName("an entity canvas sized from its bounds is held to the ceilings whatever cap the request names")
        void anEntityCanvasSizedFromItsBoundsIsHeldToTheCeilings() {
            EntityOptions options = EntityOptions.builder()
                .entityId("minecraft:pig")
                .fitMode(EntityOptions.FitMode.UNION_BOUNDS)
                .pixelsPerBlock(Integer.MAX_VALUE)
                .maxCanvasSize(Integer.MAX_VALUE)
                .build();
            assertThat(assertThrows(RenderException.class, () -> new EntityRenderer(ClientAssetsExtension.context()).render(options)).getMessage(),
                containsString("Raster"));
        }

        @Test
        @DisplayName("an entity canvas past the edge ceiling is refused")
        void anEntityCanvasPastTheCeilingIsRefused() {
            EntityOptions options = EntityOptions.builder()
                .entityId("minecraft:pig")
                .output(OutputOptions.builder().canvasSize(RenderLimits.MAX_EDGE + 1).build())
                .build();
            assertThat(assertThrows(RenderException.class, () -> new EntityRenderer(ClientAssetsExtension.context()).render(options)).getMessage(),
                containsString("Canvas size '16385'"));
        }

    }

}
