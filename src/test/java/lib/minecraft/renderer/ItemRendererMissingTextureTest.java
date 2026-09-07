package lib.minecraft.renderer;

import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.engine.RendererContext;
import lib.minecraft.renderer.engine.texture.MissingTexture;
import lib.minecraft.renderer.exception.RenderException;
import lib.minecraft.renderer.option.ItemOptions;
import lib.minecraft.renderer.parity.RenderDigest;
import lib.minecraft.renderer.support.ClientAssetsExtension;
import lib.minecraft.renderer.support.HidingRendererContext;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Coverage of all five texture lookups {@link ItemRenderer} substitutes through, each driven by hiding
 * the one texture id that only that lookup fetches.
 * <p>
 * Every row proves the same three things: the id really is absent from the context the render sees, so
 * the completed render can only have substituted; the render completes at all, where it refused before;
 * and hiding nothing leaves the wrapper byte-identical to the raw context, so the harness itself moves
 * no pixel.
 * <p>
 * Tagged {@code slow} because it boots the full asset pipeline; run with {@code ./gradlew slowTest}.
 */
@Tag("slow")
@DisplayName("ItemRenderer missing-texture substitution")
@ExtendWith(ClientAssetsExtension.class)
class ItemRendererMissingTextureTest {

    private static final int SIZE = 64;

    private static RendererContext context;

    @BeforeAll
    static void bootstrapPipeline() {
        context = ClientAssetsExtension.context();
    }

    @Test
    @DisplayName("the no-pattern shield base substitutes")
    void shieldBaseSubstitutes() {
        // The shield's 3D plate is drawn into the GUI buffer from the flat path's layer stack, not
        // from the held path, so GUI_2D is the mode that reaches it.
        assertSubstitutes("minecraft:shield", ItemOptions.Type.GUI_2D,
            "minecraft:entity/shield/shield_base_nopattern");
    }

    @Test
    @DisplayName("the tinted composite's base layer substitutes")
    void compositeBaseLayerSubstitutes() {
        assertSubstitutes("minecraft:stick", ItemOptions.Type.HELD_3D, "minecraft:item/stick");
    }

    @Test
    @DisplayName("a layer inside the tinted composite loop substitutes")
    void compositeOverlayLayerSubstitutes() {
        // Hiding layer1 rather than layer0 puts the miss inside the loop, so the base still resolves
        // and the substitution has to happen on the second pass.
        assertSubstitutes("minecraft:leather_helmet", ItemOptions.Type.HELD_3D,
            "minecraft:item/leather_helmet_overlay");
    }

    @Test
    @DisplayName("the flat GUI layer loop substitutes, drawing the bare checkerboard")
    void flatLayerLoopSubstitutes() {
        RendererContext hidden = assertSubstitutes("minecraft:stick", ItemOptions.Type.GUI_2D,
            "minecraft:item/stick");

        // The flat path applies neither tint nor shade to an untinted item, so the substituted texels
        // survive to the canvas as the sprite's own two colours rather than a product of them.
        int[] pixels = RenderDigest.firstFramePixels(
            new ItemRenderer(hidden).render(item("minecraft:stick", ItemOptions.Type.GUI_2D)));
        assertThat("the checkerboard's magenta reaches the canvas",
            contains(pixels, MissingTexture.MAGENTA_ARGB), is(true));
        assertThat("the checkerboard's black reaches the canvas",
            contains(pixels, MissingTexture.BLACK_ARGB), is(true));
    }

    @Test
    @DisplayName("an element model's face textures substitute")
    void elementFaceTexturesSubstitute() {
        // The subject is discovered rather than named. Which indexed items carry model elements is a
        // property of the shipped assets, and naming one couples this row to a layout that moves - the
        // held path takes the element branch for whichever item has them, and that is what is pinned.
        String itemId = context.knownItemIds().stream()
            .filter(id -> !ItemRenderer.isBannerOrShield(id))
            .filter(id -> context.findItem(id)
                .map(item -> !item.model().getElements().isEmpty())
                .orElse(false))
            .sorted()
            .findFirst()
            .orElseThrow(() -> new AssertionError("no indexed item carries model elements"));

        assertSubstitutes(itemId, ItemOptions.Type.HELD_3D, firstFaceTextureOf(itemId));
    }

    /**
     * Renders an item three ways - over the raw context, over a wrapper hiding nothing, and over a
     * wrapper hiding one texture id - and asserts the id is genuinely absent, that the wrapper is
     * inert when it hides nothing, and that hiding the id both completes and changes the picture.
     *
     * @param itemId the item to render
     * @param type the render mode to dispatch through
     * @param textureId the namespaced texture id to hide
     * @return the hiding context, for a caller wanting to assert on its pixels
     */
    private static @NotNull RendererContext assertSubstitutes(
        @NotNull String itemId, @NotNull ItemOptions.@NotNull Type type, @NotNull String textureId) {
        assertThat(itemId + " is carried by the item index", context.findItem(itemId).isPresent(), is(true));

        // A model's face reference may or may not carry its namespace, so both sides of the match are
        // canonicalised - a raw string compare silently hides nothing and the render then passes for
        // having substituted nowhere.
        String canonical = ResourceId.parse(textureId).id();
        RendererContext inert = HidingRendererContext.hiding(context);
        RendererContext hidden = HidingRendererContext.hiding(context, canonical);

        assertThat(canonical + " resolves before it is hidden",
            context.resolveTexture(canonical).isPresent(), is(true));
        assertThrows(RenderException.class, () -> hidden.requireTexture(canonical),
            canonical + " must be absent from the context the render sees");

        int[] raw = RenderDigest.firstFramePixels(new ItemRenderer(context).render(item(itemId, type)));
        int[] unhidden = RenderDigest.firstFramePixels(new ItemRenderer(inert).render(item(itemId, type)));
        assertThat("hiding nothing moves no pixel", unhidden, is(raw));

        int[] substituted = RenderDigest.firstFramePixels(assertDoesNotThrow(
            () -> new ItemRenderer(hidden).render(item(itemId, type)),
            "a missing texture must draw the checkerboard rather than refuse"));
        assertThat("hiding the id changes the picture", substituted, is(not(raw)));

        return hidden;
    }

    /**
     * Harvests the first texture reference an item's model elements name, by walking the same loader
     * the held path walks and answering empty for every id rather than decoding one.
     *
     * @param itemId the item whose element faces are being read
     * @return the first resolved face texture id
     */
    private static @NotNull String firstFaceTextureOf(@NotNull String itemId) {
        Set<String> refs = new LinkedHashSet<>();
        context.findItem(itemId).orElseThrow().model().loadElementFaceTextures(id -> {
            refs.add(id);
            return Optional.empty();
        });

        return refs.stream()
            .findFirst()
            .orElseThrow(() -> new AssertionError(itemId + " declares elements but references no texture"));
    }

    /**
     * Builds item options fixed to the small test canvas so the slow render stays cheap.
     *
     * @param id the item id to render
     * @param type the render mode to dispatch through
     * @return the item options
     */
    private static @NotNull ItemOptions item(@NotNull String id, @NotNull ItemOptions.Type type) {
        return ItemOptions.builder()
            .itemId(id)
            .type(type)
            .output(ItemOptions.DEFAULT_OUTPUT.mutate().canvasSize(SIZE).build())
            .build();
    }

    /**
     * Whether a colour appears anywhere in a rendered frame.
     *
     * @param pixels the frame's ARGB texels
     * @param argb the colour to look for
     * @return whether the frame carries it
     */
    private static boolean contains(int[] pixels, int argb) {
        for (int pixel : pixels)
            if (pixel == argb) return true;

        return false;
    }

}
