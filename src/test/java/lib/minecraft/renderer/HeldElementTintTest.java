package lib.minecraft.renderer;

import com.google.gson.Gson;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.gson.GsonSettings;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.asset.Item.LayerTint;
import lib.minecraft.renderer.asset.Item;
import lib.minecraft.renderer.asset.model.ModelData;
import lib.minecraft.renderer.port.RendererContext;
import lib.minecraft.renderer.request.DecorationOptions;
import lib.minecraft.renderer.request.ItemOptions;
import lib.minecraft.renderer.store.diff.RenderDigest;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Coverage of the colour a held item model built from elements gives its faces: each face takes the
 * item definition's tint its tintindex names, a face at no tintindex takes none, and a caller's
 * custom colour stands at tintindex 0 only where the definition lists no tint of its own.
 * <p>
 * The fixture is synthetic: a white texture and a model of three separate cubes, each textured on all
 * six faces at one tintindex - {@code 0}, {@code 1} and none - so a row does not depend on which faces
 * the held camera sees. The model declares no display, so the held pose is the identity. Shading
 * scales the three channels together, so a pixel's class - red only, green only, blue only or grey -
 * survives it.
 */
@DisplayName("A held element model takes its definition's tints by tintindex")
class HeldElementTintTest {

    private static final @NotNull Gson GSON = GsonSettings.defaults().create();

    private static final @NotNull String ID = "test:cubes";

    private static final @NotNull String TEXTURE = "test:block/white";

    private static final int RED = 0xFFFF0000;

    private static final int GREEN = 0xFF00FF00;

    private static final int BLUE = 0xFF0000FF;

    /** A pixel's colour class, which shading cannot change. */
    private enum Hue { RED, GREEN, BLUE, GREY, MIXED }

    @Test
    @DisplayName("each face takes the definition tint its tintindex names, and a face at none stays untinted")
    void facesTakeTheirDefinitionTints() {
        Set<Hue> hues = hues(render(threeCubes(), Concurrent.newUnmodifiableList(
            new LayerTint.Constant(RED), new LayerTint.Constant(BLUE)), null));

        assertThat("tintindex 0 takes the first tint", hues.contains(Hue.RED), is(true));
        assertThat("tintindex 1 takes the second tint", hues.contains(Hue.BLUE), is(true));
        assertThat("no tintindex stays untinted", hues.contains(Hue.GREY), is(true));
        assertThat("nothing takes a colour neither names", hues.contains(Hue.GREEN), is(false));
    }

    @Test
    @DisplayName("a caller's colour fills tintindex 0 alone where the definition lists no tint")
    void aCallerColourFillsSlotZeroAlone() {
        Set<Hue> hues = hues(render(threeCubes(), Concurrent.newUnmodifiableList(), GREEN));

        assertThat("tintindex 0 takes the caller's colour", hues.contains(Hue.GREEN), is(true));
        assertThat("tintindex 1 and no tintindex stay untinted", hues.contains(Hue.GREY), is(true));
        assertThat("no definition colour appears", hues.contains(Hue.RED) || hues.contains(Hue.BLUE), is(false));
    }

    @Test
    @DisplayName("a model whose faces declare no tintindex ignores a caller's colour")
    void aModelWithNoColourableFaceIgnoresTheCallersColour() {
        ModelData untinted = model(cube(12, null));
        int[] plain = render(untinted, Concurrent.newUnmodifiableList(), null);

        assertThat("the model draws", Arrays.stream(plain).anyMatch(pixel -> (pixel >>> 24) != 0), is(true));
        assertThat(render(untinted, Concurrent.newUnmodifiableList(), GREEN), is(plain));
    }

    /**
     * Renders the fixture item held at the small test canvas.
     *
     * @param model the item's model
     * @param tints the item definition's tints
     * @param tintColor the caller's custom colour, or {@code null} for none
     * @return the first frame's ARGB texels
     */
    private static int @NotNull [] render(
        @NotNull ModelData model, @NotNull ConcurrentList<LayerTint> tints, @Nullable Integer tintColor) {
        ConcurrentMap<String, String> sprites = Concurrent.newMap();
        model.getTextures().forEach((slot, texture) -> sprites.put(slot, texture.sprite()));
        Item item = new Item(ResourceId.parse(ID), model, sprites, 0, tints, false);

        int[] white = new int[16 * 16];
        Arrays.fill(white, 0xFFFFFFFF);
        RendererContext context = RendererContext.builder()
            .textures(Map.of(TEXTURE, PixelBuffer.of(white, 16, 16)))
            .items(Map.of(ID, item))
            .build();

        ItemOptions.Builder options = ItemOptions.builder()
            .itemId(ID)
            .type(ItemOptions.Type.HELD_3D)
            .output(ItemOptions.DEFAULT_OUTPUT.mutate().canvasSize(64).build())
            .substituteMissing(false);
        if (tintColor != null)
            options.decoration(DecorationOptions.builder().tintColor(tintColor).build());

        return RenderDigest.firstFramePixels(new ItemRenderer(context).render(options.build()));
    }

    /**
     * Classifies every opaque pixel of a frame.
     *
     * @param pixels the frame's ARGB texels
     * @return the classes present
     */
    private static @NotNull Set<Hue> hues(int @NotNull [] pixels) {
        Set<Hue> hues = EnumSet.noneOf(Hue.class);
        for (int pixel : pixels) {
            if ((pixel >>> 24) != 0xFF) continue;
            int r = pixel >>> 16 & 0xFF;
            int g = pixel >>> 8 & 0xFF;
            int b = pixel & 0xFF;
            if (r == g && g == b) hues.add(Hue.GREY);
            else if (g == 0 && b == 0) hues.add(Hue.RED);
            else if (r == 0 && b == 0) hues.add(Hue.GREEN);
            else if (r == 0 && g == 0) hues.add(Hue.BLUE);
            else hues.add(Hue.MIXED);
        }
        return hues;
    }

    /**
     * The three-cube model: tintindex 0 on the left, 1 in the middle, none on the right.
     *
     * @return the model
     */
    private static @NotNull ModelData threeCubes() {
        return model(cube(0, 0) + "," + cube(6, 1) + "," + cube(12, null));
    }

    /**
     * Parses a model over the white texture from its element list.
     *
     * @param elements the elements' JSON, comma separated
     * @return the model
     */
    private static @NotNull ModelData model(@NotNull String elements) {
        return GSON.fromJson("{\"textures\":{\"t\":\"" + TEXTURE + "\"},\"elements\":[" + elements + "]}", ModelData.class);
    }

    /**
     * One four-pixel cube at a horizontal offset, all six faces at one tintindex.
     *
     * @param x the cube's left edge, in pixels
     * @param tintIndex the tintindex every face declares, or {@code null} for none
     * @return the element's JSON
     */
    private static @NotNull String cube(int x, @Nullable Integer tintIndex) {
        String tint = tintIndex == null ? "" : ",\"tintindex\":" + tintIndex;
        StringBuilder faces = new StringBuilder();
        for (String face : new String[]{ "north", "south", "east", "west", "up", "down" }) {
            if (!faces.isEmpty()) faces.append(',');
            faces.append('"').append(face).append("\":{\"texture\":\"#t\"").append(tint).append('}');
        }
        return "{\"from\":[" + x + ",6,6],\"to\":[" + (x + 4) + ",10,10],\"faces\":{" + faces + "}}";
    }

}
