package lib.minecraft.renderer.content.pack;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.ColorMap;
import lib.minecraft.renderer.asset.pack.MCMeta;
import lib.minecraft.renderer.asset.pack.PackCapability;
import lib.minecraft.renderer.asset.pack.PackRoot;
import lib.minecraft.renderer.asset.pack.ResourcePack;
import lib.minecraft.renderer.exception.ColorMapException;
import lib.minecraft.renderer.exception.ContentException;
import lib.minecraft.renderer.vanilla.TintSource;
import lib.minecraft.renderer.vanilla.id.PackId;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Coverage of {@link ColorMapLoader}: colormaps resolve through the pack stack like any texture, a
 * stack that ships no copy of one a target names, or whose winning copy does not decode, fails to load
 * with a {@link ColorMapException}, and each PNG decodes to row-major big-endian ARGB bytes -
 * bit-identical to the bundled {@code color_maps.json} snapshot generation.
 */
class ColorMapLoaderTest {

    @Test
    @DisplayName("decode packs sRGB ARGB pixels big-endian, 4 bytes per pixel")
    void decodeProducesBigEndianArgb(@TempDir Path dir) throws IOException {
        BufferedImage image = new BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0xFF7FB238);
        image.setRGB(1, 0, 0xFF010203);
        Path png = dir.resolve("grass.png");
        ImageIO.write(image, "PNG", png.toFile());

        byte[] pixels = ColorMapLoader.decode(Files.readAllBytes(png));
        assertThat(pixels, is(new byte[]{
            (byte) 0xFF, (byte) 0x7F, (byte) 0xB2, (byte) 0x38,
            (byte) 0xFF, 0x01, 0x02, 0x03
        }));
    }

    @Test
    @DisplayName("load resolves every colormap the stack supplies, attributed to its pack")
    void loadResolvesColormapsFromStack(@TempDir Path root) throws IOException {
        Path colormap = root.resolve("assets/minecraft/textures/colormap");
        png(colormap.resolve("grass.png"));
        png(colormap.resolve("foliage.png"));
        png(colormap.resolve("dry_foliage.png"));

        ResourcePack vanilla = new ResourcePack(PackId.VANILLA, new PackContainer.Directory(root), MCMeta.EMPTY,
            Concurrent.newList(PackRoot.BASE), Concurrent.newUnmodifiableSet("minecraft"),
            Concurrent.newUnmodifiableSet(PackCapability.VANILLA_CORE));
        PackStack bare = PackStack.of(Concurrent.newList(vanilla));
        PackStack stack = bare.withTextureIndex(TextureIndexer.index(bare));

        ConcurrentMap<TintSource, ColorMap> maps = ColorMapLoader.load(stack);

        assertThat(maps.size(), is(3));
        assertThat(maps.containsKey(TintSource.GRASS), is(true));
        assertThat(maps.containsKey(TintSource.FOLIAGE), is(true));
        assertThat(maps.containsKey(TintSource.DRY_FOLIAGE), is(true));
        assertThat(maps.get(TintSource.GRASS).packId(), is("vanilla"));
        assertThat("decoded pixels must be non-empty",
            maps.get(TintSource.GRASS).pixels().length, is(greaterThan(0)));
    }

    @Test
    @DisplayName("load refuses a stack in which no pack ships a colormap a target names, naming the first one missing")
    void loadRaisesOnMissingColormap(@TempDir Path root) throws IOException {
        Path colormap = root.resolve("assets/minecraft/textures/colormap");
        png(colormap.resolve("grass.png"));

        ResourcePack vanilla = new ResourcePack(PackId.VANILLA, new PackContainer.Directory(root), MCMeta.EMPTY,
            Concurrent.newList(PackRoot.BASE), Concurrent.newUnmodifiableSet("minecraft"),
            Concurrent.newUnmodifiableSet(PackCapability.VANILLA_CORE));
        PackStack bare = PackStack.of(Concurrent.newList(vanilla));
        PackStack stack = bare.withTextureIndex(TextureIndexer.index(bare));

        ColorMapException refused = assertThrows(ColorMapException.class, () -> ColorMapLoader.load(stack));
        assertThat(refused.getMessage(), is("No pack ships colormap 'foliage'"));
    }

    @Test
    @DisplayName("load refuses a stack whose higher pack's filter.block erases a colormap and ships none")
    void loadRaisesWhenAFilterErasesAColormap(@TempDir Path root) throws IOException {
        Path base = root.resolve("vanilla");
        Path colormap = base.resolve("assets/minecraft/textures/colormap");
        png(colormap.resolve("grass.png"));
        png(colormap.resolve("foliage.png"));
        png(colormap.resolve("dry_foliage.png"));
        ResourcePack vanilla = new ResourcePack(PackId.VANILLA, new PackContainer.Directory(base), MCMeta.EMPTY,
            Concurrent.newList(PackRoot.BASE), Concurrent.newUnmodifiableSet("minecraft"),
            Concurrent.newUnmodifiableSet(PackCapability.VANILLA_CORE));

        Path top = root.resolve("filterpack");
        Files.createDirectories(top.resolve("assets/minecraft"));
        MCMeta filtering = MCMetaParser.parse(
            "{\"pack\":{\"pack_format\":84},\"filter\":{\"block\":[{\"path\":\"colormap/grass\"}]}}",
            new ResourceId("filterpack", "pack"));
        ResourcePack filterPack = new ResourcePack(new PackId("filterpack"), new PackContainer.Directory(top), filtering,
            Concurrent.newList(PackRoot.BASE), Concurrent.newUnmodifiableSet("minecraft"),
            Concurrent.newUnmodifiableSet(PackCapability.VANILLA_CORE));

        PackStack bare = PackStack.of(Concurrent.newList(vanilla, filterPack));
        PackStack stack = bare.withTextureIndex(TextureIndexer.index(bare));
        assertThat("the filter hides the lower pack's grass colormap",
            stack.resolve(new ResourceId("minecraft", "colormap/grass")).isPresent(), is(false));

        ColorMapException refused = assertThrows(ColorMapException.class, () -> ColorMapLoader.load(stack));
        assertThat(refused.getMessage(), is("No pack ships colormap 'grass'"));
    }

    @Test
    @DisplayName("load refuses a stack whose winning copy of a colormap does not decode, naming the colormap and its pack")
    void loadRaisesOnAnUndecodableColormap(@TempDir Path root) throws IOException {
        Path base = root.resolve("vanilla");
        Path colormap = base.resolve("assets/minecraft/textures/colormap");
        png(colormap.resolve("grass.png"));
        png(colormap.resolve("foliage.png"));
        png(colormap.resolve("dry_foliage.png"));
        ResourcePack vanilla = new ResourcePack(PackId.VANILLA, new PackContainer.Directory(base), MCMeta.EMPTY,
            Concurrent.newList(PackRoot.BASE), Concurrent.newUnmodifiableSet("minecraft"),
            Concurrent.newUnmodifiableSet(PackCapability.VANILLA_CORE));

        Path top = root.resolve("brokenpack");
        Files.createDirectories(top.resolve("assets/minecraft/textures/colormap"));
        Files.writeString(top.resolve("assets/minecraft/textures/colormap/foliage.png"), "not a png");
        ResourcePack brokenPack = new ResourcePack(new PackId("brokenpack"), new PackContainer.Directory(top), MCMeta.EMPTY,
            Concurrent.newList(PackRoot.BASE), Concurrent.newUnmodifiableSet("minecraft"),
            Concurrent.newUnmodifiableSet(PackCapability.VANILLA_CORE));

        PackStack bare = PackStack.of(Concurrent.newList(vanilla, brokenPack));
        PackStack stack = bare.withTextureIndex(TextureIndexer.index(bare));

        ColorMapException refused = assertThrows(ColorMapException.class, () -> ColorMapLoader.load(stack));
        assertThat(refused.getMessage(),
            is("Colormap 'foliage' from pack 'brokenpack' cannot be read: Colormap bytes could not be decoded"));
        assertThat(refused.getCause(), is(instanceOf(ContentException.class)));
    }

    private static void png(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB), "PNG", path.toFile());
    }

}
