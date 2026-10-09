package lib.minecraft.renderer.content.index;

import dev.simplified.collection.Concurrent;
import dev.simplified.util.Possible;
import lib.minecraft.renderer.asset.ColorMap;
import lib.minecraft.renderer.content.client.ClientAssets;
import lib.minecraft.renderer.content.client.ClientOptions;
import lib.minecraft.renderer.exception.ColorMapException;
import lib.minecraft.renderer.vanilla.TintSource;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Coverage of what a context load does with a colormap a pack stack cannot supply: a stack carrying
 * packs above vanilla is dropped whole and loaded as the vanilla pack alone, logging the failure once,
 * and a stack whose vanilla pack cannot supply the colormap either raises.
 * <p>
 * Each case writes a synthetic vanilla root and loads it through the real pipeline, so no client
 * extraction is read, and compares the fallback against a context loaded from the same root with no
 * pack selected.
 */
@DisplayName("A colormap a selected pack leaves unloadable loads the vanilla pack alone")
class ColorMapFallbackTest {

    /**
     * The pack that drops out of the stack, by the id its directory name derives.
     */
    private static final String PACK = "colormapbreaker";

    /**
     * A texture the vanilla pack ships, which the pack overrides with other pixels.
     */
    private static final String STONE = "minecraft:block/stone";

    /**
     * A texture only the pack ships.
     */
    private static final String PACK_ONLY = "minecraft:block/colormap_breaker_only";

    /**
     * The colour of the vanilla pack's stone.
     */
    private static final int VANILLA_STONE = 0xFFFF0000;

    /**
     * The colour of every texture the pack ships.
     */
    private static final int PACK_STONE = 0xFF0000FF;

    /**
     * The three colormaps, by the tint target each serves.
     */
    private static final List<TintSource> COLOR_MAP_TARGETS = List.of(TintSource.GRASS, TintSource.FOLIAGE, TintSource.DRY_FOLIAGE);

    @Test
    @DisplayName("a pack whose filter erases a colormap and ships none is dropped, the context answering as vanilla alone")
    void aFilterErasingAColormapDropsThePack(@TempDir Path dir) throws IOException {
        Path vanilla = vanilla(dir, List.of("grass", "foliage", "dry_foliage"));
        Path pack = pack(dir, "{\"pack\":{\"pack_format\":84},\"filter\":{\"block\":[{\"path\":\"colormap/grass\"}]}}");

        assertFallsBack(dir, vanilla, pack, "No pack ships colormap 'grass'");
    }

    @Test
    @DisplayName("a pack shipping a colormap that cannot be decoded is dropped, the context answering as vanilla alone")
    void anUndecodableColormapDropsThePack(@TempDir Path dir) throws IOException {
        Path vanilla = vanilla(dir, List.of("grass", "foliage", "dry_foliage"));
        Path pack = pack(dir, "{\"pack\":{\"pack_format\":84}}");
        write(pack.resolve("assets/minecraft/textures/colormap/foliage.png"), "not a png");

        assertFallsBack(dir, vanilla, pack, "Colormap 'foliage' from pack '" + PACK + "' cannot be read");
    }

    @Test
    @DisplayName("a vanilla pack that cannot supply a colormap raises, with no pack selected and after dropping the selected one")
    void aVanillaPackMissingAColormapRaises(@TempDir Path dir) throws IOException {
        Path vanilla = vanilla(dir, List.of("grass", "foliage"));
        Path pack = pack(dir, "{\"pack\":{\"pack_format\":84}}");

        Logged<ColorMapException> alone = logged(() -> assertThrows(ColorMapException.class,
            () -> RendererContext.load(assets(dir, vanilla, List.of()))));
        assertThat(alone.value().getMessage(), is("No pack ships colormap 'dry_foliage'"));
        assertThat("nothing is dropped where vanilla is the only pack", alone.lines(), is(List.of()));

        Logged<ColorMapException> dropped = logged(() -> assertThrows(ColorMapException.class,
            () -> RendererContext.load(assets(dir, vanilla, List.of(pack)))));
        assertThat(dropped.value().getMessage(), is("No pack ships colormap 'dry_foliage'"));
        assertThat("the selected pack is dropped before vanilla alone raises", dropped.lines(), hasSize(1));
        assertThat(dropped.lines().getFirst(), containsString("'" + PACK + "'"));
    }

    /**
     * Loads the vanilla root with and without the pack and asserts the packed load logged the failure
     * once, ahead of whatever the vanilla-only load logs, and answers every lookup the fixtures
     * exercise as the vanilla-only context does.
     *
     * @param dir the test's working directory
     * @param vanilla the synthetic vanilla root
     * @param pack the pack that leaves a colormap unloadable
     * @param cause what the logged line names as the failure
     */
    private static void assertFallsBack(@NotNull Path dir, @NotNull Path vanilla, @NotNull Path pack, @NotNull String cause) {
        Logged<RendererContext> packed = logged(() -> RendererContext.load(assets(dir, vanilla, List.of(pack))));
        Logged<RendererContext> alone = logged(() -> RendererContext.load(assets(dir, vanilla, List.of())));

        List<String> lines = packed.lines();
        assertThat("the fallback logs one line ahead of the vanilla-only load's", lines, hasSize(alone.lines().size() + 1));
        assertThat(lines.subList(1, lines.size()), is(alone.lines()));
        assertThat(lines.getFirst(), containsString(cause));
        assertThat(lines.getFirst(), containsString("dropping every selected pack ('" + PACK + "')"));
        assertThat(lines.getFirst(), containsString("loading the vanilla pack alone"));

        for (TintSource target : COLOR_MAP_TARGETS) {
            ColorMap colorMap = packed.value().findColorMap(target).get();
            assertThat(target + " comes from the vanilla pack", colorMap.packId(), is("vanilla"));
            assertThat(target + " is the vanilla-only context's", colorMap, is(alone.value().findColorMap(target).get()));
        }

        assertThat("a texture only the pack ships is absent",
            packed.value().resolveTexture(PACK_ONLY).getState(), is(Possible.State.ABSENT));
        assertThat("a texture the pack overrides answers vanilla's pixels",
            packed.value().resolveTexture(STONE).get().data()[0], is(VANILLA_STONE));
        assertThat(packed.value().resolveTexture(STONE).get().data(), is(alone.value().resolveTexture(STONE).get().data()));
        assertThat(packed.value().knownBlockIds(), is(alone.value().knownBlockIds()));
        assertThat(packed.value().knownItemIds(), is(alone.value().knownItemIds()));
    }

    /**
     * Writes a synthetic vanilla root holding the named colormaps and a stone texture.
     *
     * @param dir the test's working directory
     * @param colorMaps the colormaps the root ships, by name under {@code textures/colormap/}
     * @return the vanilla root
     * @throws IOException if a fixture file cannot be written
     */
    private static @NotNull Path vanilla(@NotNull Path dir, @NotNull List<String> colorMaps) throws IOException {
        Path root = dir.resolve("vanilla");
        write(root.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":84,\"description\":\"synthetic vanilla\"}}");

        for (String name : colorMaps)
            png(root.resolve("assets/minecraft/textures/colormap/" + name + ".png"), 256, 0xFF7FB238);

        png(root.resolve("assets/minecraft/textures/block/stone.png"), 16, VANILLA_STONE);
        return root;
    }

    /**
     * Writes the pack: its metadata, an override of vanilla's stone and a texture only it ships.
     *
     * @param dir the test's working directory
     * @param mcmeta the pack's {@code pack.mcmeta}
     * @return the pack root
     * @throws IOException if a fixture file cannot be written
     */
    private static @NotNull Path pack(@NotNull Path dir, @NotNull String mcmeta) throws IOException {
        Path root = dir.resolve(PACK);
        write(root.resolve("pack.mcmeta"), mcmeta);
        png(root.resolve("assets/minecraft/textures/block/stone.png"), 16, PACK_STONE);
        png(root.resolve("assets/minecraft/textures/block/colormap_breaker_only.png"), 16, PACK_STONE);
        return root;
    }

    /**
     * Builds the client assets over a vanilla root with the given packs selected.
     *
     * @param dir the test's working directory, which holds the cache root
     * @param vanilla the vanilla root
     * @param packs the packs selected above vanilla
     * @return the client assets
     */
    private static @NotNull ClientAssets assets(@NotNull Path dir, @NotNull Path vanilla, @NotNull List<Path> packs) {
        ClientOptions options = ClientOptions.builder()
            .cacheRoot(dir.resolve("cache").toFile())
            .texturePacks(Concurrent.adoptList(packs.stream().map(Path::toFile).toList()))
            .build();
        return new ClientAssets(options, vanilla);
    }

    /**
     * Runs a call with {@code System.err} captured, restoring the real stream afterwards.
     *
     * @param call the call whose diagnostic output is being read
     * @param <T> the type the call answers
     * @return what the call answered, with the lines it wrote to {@code System.err}
     */
    private static <T> @NotNull Logged<T> logged(@NotNull Supplier<T> call) {
        PrintStream original = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        T value;

        try {
            value = call.get();
        } finally {
            System.setErr(original);
        }

        return new Logged<>(value, captured.toString(StandardCharsets.UTF_8).lines().toList());
    }

    /**
     * Writes a square PNG filled with one colour, creating its parent directories.
     *
     * @param path the file to write
     * @param size the edge length in pixels
     * @param argb the fill colour
     * @throws IOException if the file cannot be written
     */
    private static void png(@NotNull Path path, int size, int argb) throws IOException {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < size; y++)
            for (int x = 0; x < size; x++)
                image.setRGB(x, y, argb);

        Files.createDirectories(path.getParent());
        ImageIO.write(image, "PNG", path.toFile());
    }

    /**
     * Writes one fixture file, creating its parent directories.
     *
     * @param path the file to write
     * @param content the text it holds
     * @throws IOException if the file cannot be written
     */
    private static void write(@NotNull Path path, @NotNull String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    /**
     * What a call answered and the lines it logged.
     *
     * @param value what the call answered
     * @param lines the lines it wrote to {@code System.err}, in order
     * @param <T> the type the call answers
     */
    private record Logged<T>(@NotNull T value, @NotNull List<String> lines) {}

}
