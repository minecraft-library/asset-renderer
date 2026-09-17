package lib.minecraft.renderer.showcase;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.Background;
import dev.simplified.image.ImageData;
import dev.simplified.image.ImageFactory;
import dev.simplified.image.ImageFormat;
import dev.simplified.image.codec.gif.GifWriteOptions;
import lib.minecraft.renderer.AtlasRenderer;
import lib.minecraft.renderer.BlockRenderer;
import lib.minecraft.renderer.EntityRenderer;
import lib.minecraft.renderer.FluidRenderer;
import lib.minecraft.renderer.GridRenderer;
import lib.minecraft.renderer.ItemRenderer;
import lib.minecraft.renderer.LayoutRenderer;
import lib.minecraft.renderer.MenuRenderer;
import lib.minecraft.renderer.PlayerRenderer;
import lib.minecraft.renderer.PortalRenderer;
import lib.minecraft.renderer.TextRenderer;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.equipment.ArmorMaterial;
import lib.minecraft.renderer.asset.equipment.ArmorPiece;
import lib.minecraft.renderer.asset.equipment.ArmorTrim;
import lib.minecraft.renderer.asset.pose.PoseStyle;
import lib.minecraft.renderer.engine.RendererContext;
import lib.minecraft.renderer.engine.camera.Projection;
import lib.minecraft.renderer.engine.compose.TooltipChrome;
import lib.minecraft.renderer.engine.texture.Biome;
import lib.minecraft.renderer.option.AnimationOptions;
import lib.minecraft.renderer.option.ArmorOptions;
import lib.minecraft.renderer.option.AtlasOptions;
import lib.minecraft.renderer.option.BlockOptions;
import lib.minecraft.renderer.option.EntityOptions;
import lib.minecraft.renderer.option.FluidOptions;
import lib.minecraft.renderer.option.GridOptions;
import lib.minecraft.renderer.option.ItemOptions;
import lib.minecraft.renderer.option.LayoutOptions;
import lib.minecraft.renderer.option.MenuOptions;
import lib.minecraft.renderer.option.OutputOptions;
import lib.minecraft.renderer.option.PlayerOptions;
import lib.minecraft.renderer.option.PortalOptions;
import lib.minecraft.renderer.option.SkinOptions;
import lib.minecraft.renderer.option.TextOptions;
import lib.minecraft.renderer.option.TextureOptions;
import lib.minecraft.renderer.pipeline.loader.EntityModelLoader;
import lib.minecraft.renderer.support.ClientAssetsExtension;
import lib.minecraft.renderer.support.MinecraftFontsExtension;
import lib.minecraft.text.LineSegment;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;

/**
 * The README's showcase images, rendered by the library they illustrate.
 *
 * <p>Each case owns one image under {@code docs/images/} and names the renderer that draws it, so
 * the README's Renderers section is a set of claims this class can make good on rather than a set of
 * screenshots somebody took once. A subject whose render breaks stops being a picture of a working
 * renderer the moment it is regenerated, which is the whole reason the images are produced here and
 * not by hand.
 *
 * <p><b>Two arms.</b> Unset, every case asserts only that its committed image is present and carries
 * the container the README expects - no render runs, so the fast suite pays nothing for eleven
 * subjects. With {@code -Dasset.showcase.regenerate=true} each case renders its subject and
 * overwrites the image, which dirties tracked files on purpose. {@link #REGENERATE_COMMAND} is the
 * command, and it is quoted in every failure that wants it.
 *
 * <p>The cases are ORDERED, and only for the regenerating arm: the last one holds the README's image
 * references against the directory's contents, which it can only do once the others have written
 * what they own. Asserting, the order is immaterial - every image is already committed.
 *
 * <p>Reads the client assets through {@link ClientAssetsExtension}, which abandons the class where
 * nothing has extracted the client yet, and the Minecraft OTFs through
 * {@link MinecraftFontsExtension}, which the tooltip subject needs and no other does. The two are
 * installed as two annotations rather than one array, which is what {@code SlowTagRuleTest} reads: it
 * looks for the literal {@code @ExtendWith(ClientAssetsExtension.class)} as the gate that keeps an
 * untagged class out of the network, and the array spelling is not that string.
 */
@ExtendWith(ClientAssetsExtension.class)
@ExtendWith(MinecraftFontsExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("The README's showcase images are what the renderers draw")
final class ReadmeShowcaseTest {

    /** where the README loads its showcase images from, relative to the repo root a Test fork runs in */
    private static final @NotNull Path IMAGES = Path.of("docs", "images");

    /** the README itself, which is the roster - a case names its file and this file decides the set */
    private static final @NotNull Path README = Path.of("README.md");

    /** what rewrites every image rather than asserting against the committed one */
    private static final @NotNull String REGENERATE_COMMAND =
        "./gradlew :test --tests \"*ReadmeShowcaseTest\" -Dasset.showcase.regenerate=true --rerun";

    /** whether this run renders and overwrites; unset, each case asserts the committed image instead */
    private static final boolean REGENERATE = Boolean.getBoolean("asset.showcase.regenerate");

    /** the GIF shape every animated render in this repository is written with */
    private static final @NotNull GifWriteOptions ANIMATED = GifWriteOptions.builder()
        .withLoopCount(0)
        .isTransparent(true)
        .withAlphaThreshold(8)
        .build();

    /** the GIF shape a wholly translucent subject wants instead - flatten onto black, no transparent slot */
    private static final @NotNull GifWriteOptions FLATTENED = GifWriteOptions.builder()
        .withLoopCount(0)
        .withBackgroundRgb(0x000000)
        .build();

    /** the near-black the two composed sheets sit on, so their gutters and cells read as deliberate */
    private static final int SHEET_BACKGROUND = 0xFF1B1B1F;

    /** every image reference the README makes, as {@code <img src="docs/images/NAME"} or {@code ](docs/images/NAME)} */
    private static final @NotNull Pattern REFERENCED = Pattern.compile("docs/images/([A-Za-z0-9._-]+)");

    /** the file extensions this class writes, so the sidecar of a future subject is not read as an orphan */
    private static final @NotNull List<String> IMAGE_SUFFIXES = List.of(".png", ".gif", ".webp");

    /** the writer every case emits through */
    private static final @NotNull ImageFactory FACTORY = new ImageFactory();

    @Test
    @Order(1)
    @DisplayName("BlockRenderer draws a grass block at the vanilla inventory tint")
    void blockRenderer() throws IOException {
        emit("block-grass-block.png", ImageFormat.PNG, null, () -> new BlockRenderer(context()).render(
            BlockOptions.builder()
                .blockId("minecraft:grass_block")
                .type(BlockOptions.Type.ISOMETRIC_3D)
                .biome(Biome.INVENTORY_DEFAULT)
                .output(OutputOptions.builder().canvasSize(512).supersample(2).antiAlias(true).build())
                .build()));
    }

    @Test
    @Order(1)
    @DisplayName("ItemRenderer scrolls an enchantment glint over a diamond sword")
    void itemRenderer() throws IOException {
        emit("item-diamond-sword.gif", ImageFormat.GIF, ANIMATED, () -> new ItemRenderer(context()).render(
            ItemOptions.builder()
                .itemId("minecraft:diamond_sword")
                .type(ItemOptions.Type.GUI_2D)
                .enchanted(true)
                .output(ItemOptions.DEFAULT_OUTPUT.mutate().canvasSize(256).build())
                .build()));
    }

    @Test
    @Order(1)
    @DisplayName("EntityRenderer walks a creeper through one stride")
    void entityRenderer() throws IOException {
        emit("entity-creeper-stride.gif", ImageFormat.GIF, ANIMATED, () -> newEntityRenderer().render(
            EntityOptions.builder()
                .entityId("minecraft:creeper")
                .style(PoseStyle.STRIDE)
                .fitMode(EntityOptions.FitMode.OUTPUT_SIZE)
                .padding(16)
                .output(OutputOptions.builder().canvasSize(512).supersample(4).build())
                .build()));
    }

    @Test
    @Order(1)
    @DisplayName("PlayerRenderer wears trimmed diamond armour over a skin")
    void playerRenderer() throws IOException {
        ArmorPiece diamond = ArmorPiece.of(ArmorMaterial.DIAMOND, ArmorTrim.Color.GOLD, ArmorTrim.Pattern.SENTRY);

        emit("player-trimmed-diamond.png", ImageFormat.PNG, null, () -> new PlayerRenderer(context()).render(
            PlayerOptions.builder()
                .type(PlayerOptions.Type.FULL)
                .dimension(PlayerOptions.Dimension.THREE_D)
                .skin(SkinOptions.builder()
                    .skin(TextureOptions.builder().id("minecraft:entity/player/wide/steve").build())
                    .build())
                .armor(ArmorOptions.builder()
                    .helmet(diamond)
                    .chestplate(diamond)
                    .leggings(diamond)
                    .boots(diamond)
                    .build())
                .output(OutputOptions.builder()
                    .projection(Projection.PORTRAIT)
                    .canvasSize(512)
                    .supersample(4)
                    .build())
                .build()));
    }

    @Test
    @Order(1)
    @DisplayName("FluidRenderer flows a sloped warm-ocean water cube")
    void fluidRenderer() throws IOException {
        emit("fluid-water-flowing.gif", ImageFormat.GIF, ANIMATED, () -> new FluidRenderer(context()).render(
            FluidOptions.builder()
                .fluid(FluidOptions.Fluid.WATER)
                .type(FluidOptions.Type.ISOMETRIC_3D)
                .biome(Biome.Vanilla.WARM_OCEAN)
                .cornerHeights(new FluidOptions.CornerHeights(0.875f, 0.5f, 0.375f, 0.75f))
                .flowAngleRadians((float) Math.toRadians(45))
                .output(OutputOptions.builder().canvasSize(256).supersample(2).build())
                .animation(AnimationOptions.builder().frameCount(32).ticksPerFrame(2).build())
                .build()));
    }

    @Test
    @Order(1)
    @DisplayName("PortalRenderer drifts the end-gateway star field across three faces")
    void portalRenderer() throws IOException {
        emit("portal-end-gateway.gif", ImageFormat.GIF, ANIMATED, () -> new PortalRenderer(context()).render(
            PortalOptions.builder()
                .portal(PortalOptions.Portal.END_GATEWAY)
                .type(PortalOptions.Type.ISOMETRIC_3D)
                .output(OutputOptions.builder().canvasSize(256).supersample(2).build())
                .animation(AnimationOptions.builder().frameCount(40).ticksPerFrame(1).build())
                .build()));
    }

    @Test
    @Order(1)
    @DisplayName("TextRenderer shimmers an obfuscated rarity footer under vanilla tooltip chrome")
    void textRenderer() throws IOException {
        emit("text-lore-tooltip.gif", ImageFormat.GIF, FLATTENED, () -> new TextRenderer().render(
            TextOptions.builder()
                .style(TextOptions.Style.LORE)
                .lines(LineSegment.fromLegacy(String.join("\n",
                    "&5Ender Reaver",
                    "&7Damage: &c+210",
                    "&7Strength: &c+80",
                    "",
                    "&7Right-click to &5blink &7eight blocks.",
                    "",
                    "&d&l&ka &r&d&lMYTHIC SWORD &d&l&ka"), '&'))
                .chrome(TooltipChrome.Vanilla.SPRITE)
                .chromeSprites(TooltipChrome.ChromeSprites.resolve(context(), null).orElseThrow())
                .build()));
    }

    @Test
    @Order(1)
    @DisplayName("AtlasRenderer sheets every ore in the game onto one indexed grid")
    void atlasRenderer() throws IOException {
        emit("atlas-ores.png", ImageFormat.PNG, null, () -> new AtlasRenderer(context()).render(
            AtlasOptions.builder()
                .filter(id -> id.endsWith("_ore"))
                .tileSize(128)
                .columns(5)
                .background(Background.checkerboard())
                .progressLogging(false)
                .build()));
    }

    @Test
    @Order(1)
    @DisplayName("GridRenderer lays eight block renders into a gutter-separated contact sheet")
    void gridRenderer() throws IOException {
        emit("grid-block-sheet.png", ImageFormat.PNG, null, () -> {
            BlockRenderer blocks = new BlockRenderer(context());
            String[] ids = {
                "minecraft:grass_block", "minecraft:diamond_block", "minecraft:oak_log", "minecraft:crafting_table",
                "minecraft:furnace", "minecraft:glowstone", "minecraft:bookshelf", "minecraft:redstone_lamp"
            };

            ConcurrentList<GridOptions.GridTile> tiles = Concurrent.newList();
            for (int index = 0; index < ids.length; index++)
                tiles.add(new GridOptions.GridTile(index % 4, index / 4, blocks.render(BlockOptions.builder()
                    .blockId(ids[index])
                    .type(BlockOptions.Type.ISOMETRIC_3D)
                    .output(OutputOptions.builder().canvasSize(128).supersample(2).antiAlias(true).build())
                    .build())));

            return new GridRenderer().render(GridOptions.builder()
                .tiles(tiles)
                .cellSize(128)
                .columns(4)
                .rows(2)
                .separation(8)
                .background(Background.solid(SHEET_BACKGROUND))
                .build());
        });
    }

    @Test
    @Order(1)
    @DisplayName("LayoutRenderer stands three different renderers on one baseline")
    void layoutRenderer() throws IOException {
        emit("layout-mixed-row.png", ImageFormat.PNG, null, () -> new LayoutRenderer().render(
            LayoutOptions.builder()
                .layout(new LayoutOptions.Layout.Row(16, LayoutOptions.Layout.Alignment.END))
                .child(newEntityRenderer(), EntityOptions.builder()
                    .entityId("minecraft:creeper")
                    .output(OutputOptions.builder().canvasSize(256).supersample(2).build())
                    .build())
                .child(new BlockRenderer(context()), BlockOptions.builder()
                    .blockId("minecraft:tnt")
                    .type(BlockOptions.Type.ISOMETRIC_3D)
                    .output(OutputOptions.builder().canvasSize(192).supersample(2).antiAlias(true).build())
                    .build())
                .child(new ItemRenderer(context()), ItemOptions.builder()
                    .itemId("minecraft:flint_and_steel")
                    .type(ItemOptions.Type.GUI_ICON)
                    .output(ItemOptions.DEFAULT_OUTPUT.mutate().canvasSize(128).build())
                    .build())
                .background(Background.solid(SHEET_BACKGROUND))
                .build()));
    }

    @Test
    @Order(1)
    @DisplayName("MenuRenderer draws a crafting table holding the diamond-sword recipe")
    void menuRenderer() throws IOException {
        emit("menu-crafting-table.png", ImageFormat.PNG, null, () -> {
            ConcurrentMap<Integer, MenuOptions.MenuSlotContent> slots = Concurrent.newMap();
            slots.put(1, MenuOptions.MenuSlotContent.of("minecraft:diamond"));
            slots.put(4, MenuOptions.MenuSlotContent.of("minecraft:diamond"));
            slots.put(7, MenuOptions.MenuSlotContent.of("minecraft:stick"));
            slots.put(9, MenuOptions.MenuSlotContent.of("minecraft:diamond_sword"));

            return new MenuRenderer(context()).render(MenuOptions.builder()
                .type(MenuOptions.Type.CRAFTING_TABLE)
                .title("Crafting")
                .playerInventory(true)
                .slots(slots)
                .build());
        });
    }

    @Test
    @Order(2)
    @DisplayName("the README and the image directory name the same set of files")
    void theReadmeAndTheDirectoryAgree() throws IOException {
        List<String> referenced = referencedImages();
        List<String> present = presentImages();

        List<String> missing = referenced.stream().filter(name -> !present.contains(name)).sorted().toList();
        List<String> orphaned = present.stream().filter(name -> !referenced.contains(name)).sorted().toList();

        assertThat("images the README shows that " + IMAGES + " does not hold - regenerate with "
            + REGENERATE_COMMAND, missing, is(empty()));
        assertThat("images in " + IMAGES + " that the README shows nowhere, so nothing renders them "
            + "and nothing reads them", orphaned, is(empty()));
        assertThat("the README shows no showcase image at all, which is not a state this class has a "
            + "reason to pass in", referenced.size(), is(greaterThan(0)));
    }

    /**
     * Renders and overwrites one showcase image, or asserts the committed one is there and is what it
     * claims to be.
     *
     * <p>The assert arm deliberately does not render. Eleven subjects - two of them sixty-frame
     * strips and one of them an atlas - is not a cost the fast suite should carry to learn that a PNG
     * is still on disk, and what it can learn without rendering is the half that goes wrong in
     * practice: a file deleted, truncated, or written in the wrong container.
     *
     * @param name the file name under {@link #IMAGES}, which is also what the README must reference
     * @param format the container the image is written in
     * @param options the write options, or {@code null} for the format's defaults
     * @param render the render, invoked only when this run regenerates
     * @throws IOException if the image cannot be written or read back
     */
    private static void emit(@NotNull String name, @NotNull ImageFormat format,
                             GifWriteOptions options, @NotNull Supplier<ImageData> render) throws IOException {
        Path file = IMAGES.resolve(name);
        if (!REGENERATE) {
            assertThat(name + " is shown by the README and is not in " + IMAGES + " - regenerate with "
                + REGENERATE_COMMAND, Files.isRegularFile(file), is(true));
            byte[] committed = Files.readAllBytes(file);
            assertThat(name + " is empty", committed.length, is(greaterThan(0)));
            assertThat(name + " is not a " + format.getFormatName() + ", so the README shows a container "
                + "this case does not write", format.matches(committed), is(true));
            return;
        }

        ImageData image = render.get();
        assertThat(name + " is written as " + format.getFormatName() + ", which carries "
            + (format.isSupportsAnimation() ? "many frames" : "one frame")
            + ", and the render came back with " + image.getFrames().size(),
            image.isAnimated(), is(format.isSupportsAnimation()));

        Files.createDirectories(IMAGES);
        Files.write(file, options == null
            ? FACTORY.toByteArray(image, format)
            : FACTORY.toByteArray(image, format, options));
    }

    /**
     * Builds the entity renderer over the shipped entity index.
     *
     * @return a renderer over every entity {@code entity_models.json} declares
     */
    private static @NotNull EntityRenderer newEntityRenderer() {
        ConcurrentMap<String, Entity> entities = EntityModelLoader.load();
        assertThat("the entity index is empty, so no entity subject can render - run './gradlew entityModels'",
            entities.size(), is(greaterThan(0)));
        return new EntityRenderer(context(), entities);
    }

    /**
     * The shared production context, built once per test JVM by the extension.
     *
     * @return the context every renderer here is constructed over
     */
    private static @NotNull RendererContext context() {
        return ClientAssetsExtension.context();
    }

    /**
     * Every image name the README references under {@link #IMAGES}, in first-appearance order.
     *
     * @return the referenced file names, deduplicated
     * @throws IOException if the README cannot be read
     */
    private static @NotNull List<String> referencedImages() throws IOException {
        List<String> names = new ArrayList<>();
        Matcher matcher = REFERENCED.matcher(Files.readString(README));
        while (matcher.find())
            if (!names.contains(matcher.group(1))) names.add(matcher.group(1));

        return names;
    }

    /**
     * Every image file the directory holds, which is empty rather than absent before the first run.
     *
     * @return the file names carrying one of {@link #IMAGE_SUFFIXES}
     * @throws IOException if the directory cannot be listed
     */
    private static @NotNull List<String> presentImages() throws IOException {
        if (!Files.isDirectory(IMAGES)) return List.of();

        try (Stream<Path> files = Files.list(IMAGES)) {
            return files.map(file -> file.getFileName().toString())
                .filter(name -> IMAGE_SUFFIXES.stream().anyMatch(name::endsWith))
                .toList();
        }
    }

}
