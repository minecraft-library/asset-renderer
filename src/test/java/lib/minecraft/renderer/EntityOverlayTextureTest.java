package lib.minecraft.renderer;

import dev.simplified.collection.Concurrent;
import dev.simplified.image.ImageData;
import dev.simplified.image.data.ImageFrame;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.content.index.EntityModelLoader;
import lib.minecraft.renderer.content.index.RendererContext;
import lib.minecraft.renderer.request.AppearanceOptions;
import lib.minecraft.renderer.request.EntityOptions;
import lib.minecraft.renderer.store.diff.RenderDigest;
import lib.minecraft.renderer.vanilla.appearance.IronGolemCrackiness;
import lib.minecraft.renderer.vanilla.appearance.TextureAxis;
import lib.minecraft.renderer.vanilla.appearance.TropicalFishPattern;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

/**
 * What a model overlay pass draws, read off the one answer its texture reference gives: an axis that
 * selects nothing skips the pass, a row naming no texture of its own borrows the base texture, and a
 * texture the axis or the row names is resolved and drawn.
 * <p>
 * Each case renders the shipped iron golem over solid-colour textures held in memory, its one pass
 * swapped for the row under test, and compares the render with one whose answer is known: the golem
 * drawing no pass at all, or a pass naming its texture outright. The crack pass draws the body's own
 * mesh, so a pass that draws covers the body and moves the render, and a skipped one leaves the body
 * as it draws alone.
 */
@DisplayName("A model overlay pass draws what its texture reference answers")
class EntityOverlayTextureTest {

    private static final @NotNull String GOLEM = "minecraft:iron_golem";

    /** The golem's own texture, which a pass naming none borrows. */
    private static final @NotNull String BASE = "iron_golem/iron_golem";

    /** The texture the crackiness axis selects at {@link IronGolemCrackiness#LOW}. */
    private static final @NotNull String CRACK_LOW = "iron_golem/iron_golem_crackiness_low";

    /** A texture a test row names as its own. */
    private static final @NotNull String OWN = "test/crack_of_its_own";

    /** A tint that makes a pass drawing the base texture over the base visible. */
    private static final int GREEN = 0xFF00FF00;

    /** The in-memory textures every render reads, one solid colour each. */
    private static final @NotNull Map<String, PixelBuffer> TEXTURES = Map.of(
        "minecraft:entity/" + BASE, solid(0xFF808080),
        "minecraft:entity/" + CRACK_LOW, solid(0xFF2040C0),
        "minecraft:entity/" + OWN, solid(0xFFC02020));

    /** The shipped golem row. */
    private static Entity golem;

    /** The shipped golem's one pass, on the crackiness axis and naming no texture of its own. */
    private static Entity.OverlayLayer crack;

    @BeforeAll
    static void load() {
        golem = EntityModelLoader.load().get(GOLEM);
        assertThat("the shipped table carries the golem", golem, is(notNullValue()));
        assertThat("which draws one pass", golem.overlays().size(), is(1));
        crack = golem.overlays().getFirst();
        assertThat("on the crackiness axis", crack.textureBy(), is(Optional.of(TextureAxis.CRACKINESS)));
        assertThat("naming no texture of its own", crack.textureRef(), is(Optional.empty()));
    }

    @Test
    @DisplayName("a texture the axis selects is resolved and drawn, as the same texture named outright is")
    void aSelectedTextureIsDrawn() {
        AppearanceOptions low = crackiness(IronGolemCrackiness.LOW);

        assertThat("the selected crack draws over the body",
            render(golem, low), is(not(render(bare(), low))));
        assertThat("and it is the texture the axis selected",
            render(golem, low), is(render(withPass(Optional.of(CRACK_LOW), Optional.empty(), crack.tintArgb()), low)));
    }

    @Test
    @DisplayName("an axis that selects nothing skips the pass, even where the row names a texture of its own")
    void anAxisSelectingNothingSkipsThePass() {
        AppearanceOptions none = crackiness(IronGolemCrackiness.NONE);
        Entity owning = withPass(Optional.of(OWN), Optional.of(TextureAxis.CRACKINESS), crack.tintArgb());

        assertThat("the shipped golem draws no crack", render(golem, none), is(render(bare(), none)));
        assertThat("the row's own texture would show if the pass drew it",
            render(withPass(Optional.of(OWN), Optional.empty(), crack.tintArgb()), none), is(not(render(bare(), none))));
        assertThat("but a row carrying a texture of its own draws no crack at NONE either",
            render(owning, none), is(render(bare(), none)));
        assertThat("and at a damaged level it draws the level's texture rather than its own",
            render(owning, crackiness(IronGolemCrackiness.LOW)), is(render(golem, crackiness(IronGolemCrackiness.LOW))));
    }

    @Test
    @DisplayName("the crackiness axis answers nothing at NONE whatever the row names, and the pattern axis keeps its baked default")
    void theCrackinessAxisKeepsItsNone() {
        AppearanceOptions unset = AppearanceOptions.builder().build();

        assertThat("a deliberate NONE is not replaced by the row's texture",
            unset.texture(TextureAxis.CRACKINESS, "iron_golem", Optional.of(OWN)), is(Optional.empty()));
        assertThat("an unset pattern draws the row's baked one",
            unset.texture(TextureAxis.PATTERN, "tropical_fish", Optional.of("tropical_fish/kob")),
            is(Optional.of("tropical_fish/kob")));
        assertThat("and a set one draws its own",
            AppearanceOptions.builder().pattern(Optional.of(TropicalFishPattern.SUNSTREAK)).build()
                .texture(TextureAxis.PATTERN, "tropical_fish", Optional.of("tropical_fish/kob")),
            is(Optional.of(TropicalFishPattern.SUNSTREAK.overlayTexture())));
    }

    @Test
    @DisplayName("a row naming no texture of its own, on no axis, borrows the base texture")
    void aRowNamingNoTextureBorrowsTheBase() {
        AppearanceOptions none = crackiness(IronGolemCrackiness.NONE);
        Entity borrowing = withPass(Optional.empty(), Optional.empty(), GREEN);

        assertThat("the pass draws rather than being skipped",
            render(borrowing, none), is(not(render(bare(), none))));
        assertThat("and draws the base texture, as a pass naming it outright does",
            render(borrowing, none), is(render(withPass(Optional.of(BASE), Optional.empty(), GREEN), none)));
        assertThat("rather than any other",
            render(borrowing, none), is(not(render(withPass(Optional.of(OWN), Optional.empty(), GREEN), none))));
    }

    // ------------------------------------------------------------------------------------

    /**
     * The golem with no pass at all.
     *
     * @return the golem drawing its body alone
     */
    private static @NotNull Entity bare() {
        return golem.mutate().overlays(Concurrent.newUnmodifiableList()).build();
    }

    /**
     * The golem with its one pass replaced by one differing from the shipped crack only in its texture
     * reference and tint.
     *
     * @param textureRef the texture the pass names as its own, or empty for none
     * @param textureBy the texture axis the pass is on, or empty for none
     * @param tint the pass's multiplicative tint
     * @return the golem drawing that pass
     */
    private static @NotNull Entity withPass(
        @NotNull Optional<String> textureRef, @NotNull Optional<TextureAxis> textureBy, int tint) {

        Entity.OverlayLayer pass = new Entity.OverlayLayer(crack.model(), textureRef, crack.pass(), tint,
            crack.skipBounds(), crack.tintBy(), textureBy, crack.gate(), crack.noHatModel(), crack.pose(),
            crack.textureScroll());
        return golem.mutate().overlays(Concurrent.newUnmodifiableList(pass)).build();
    }

    /**
     * An appearance selecting one crackiness level.
     *
     * @param level the level
     * @return the appearance
     */
    private static @NotNull AppearanceOptions crackiness(@NotNull IronGolemCrackiness level) {
        return AppearanceOptions.builder().crackiness(level).build();
    }

    /**
     * Renders one golem row over the in-memory textures and spells its first frame as its size and
     * its pixels, so two renders compare by both.
     *
     * @param row the golem row to draw
     * @param appearance the appearance to draw it with
     * @return the first frame's width, height and pixel checksum
     */
    private static @NotNull List<Object> render(@NotNull Entity row, @NotNull AppearanceOptions appearance) {
        RendererContext context = RendererContext.builder()
            .entities(Map.of(GOLEM, row))
            .textures(TEXTURES)
            .build();
        ImageData image = new EntityRenderer(context).render(
            EntityOptions.builder().entityId(GOLEM).appearance(appearance).build());
        ImageFrame first = image.getFrames().getFirst();
        return List.of(first.pixels().width(), first.pixels().height(),
            RenderDigest.crc32(RenderDigest.firstFramePixels(image)));
    }

    /**
     * A texture of one colour, at the golem sheet's size.
     *
     * @param argb the colour
     * @return the texture
     */
    private static @NotNull PixelBuffer solid(int argb) {
        int[] pixels = new int[128 * 128];
        Arrays.fill(pixels, argb);
        return PixelBuffer.of(pixels, 128, 128);
    }

}
