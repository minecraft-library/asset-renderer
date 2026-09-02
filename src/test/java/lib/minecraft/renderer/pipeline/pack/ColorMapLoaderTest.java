package lib.minecraft.renderer.pipeline.pack;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.asset.ColorMap;
import lib.minecraft.renderer.asset.PackStack;
import lib.minecraft.renderer.asset.pack.MCMeta;
import lib.minecraft.renderer.asset.pack.PackCapability;
import lib.minecraft.renderer.asset.pack.PackContainer;
import lib.minecraft.renderer.asset.pack.PackId;
import lib.minecraft.renderer.asset.pack.PackRoot;
import lib.minecraft.renderer.asset.pack.ResourcePack;
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
import static org.hamcrest.Matchers.is;

/**
 * Coverage of {@link ColorMapLoader}: colormaps resolve through the pack stack like any texture, and
 * each PNG decodes to row-major big-endian ARGB bytes - bit-identical to the bundled
 * {@code color_maps.json} snapshot generation.
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

        ConcurrentMap<Block.TintTarget, ColorMap> maps = ColorMapLoader.load(stack);

        assertThat(maps.size(), is(3));
        assertThat(maps.containsKey(Block.TintTarget.GRASS), is(true));
        assertThat(maps.containsKey(Block.TintTarget.FOLIAGE), is(true));
        assertThat(maps.containsKey(Block.TintTarget.DRY_FOLIAGE), is(true));
        assertThat(maps.get(Block.TintTarget.GRASS).packId(), is("vanilla"));
        assertThat("decoded pixels must be non-empty",
            maps.get(Block.TintTarget.GRASS).pixels().length, is(greaterThan(0)));
    }

    @Test
    @DisplayName("load skips a colormap type no pack supplies (graceful)")
    void loadSkipsMissingColormap(@TempDir Path root) throws IOException {
        Path colormap = root.resolve("assets/minecraft/textures/colormap");
        png(colormap.resolve("grass.png"));

        ResourcePack vanilla = new ResourcePack(PackId.VANILLA, new PackContainer.Directory(root), MCMeta.EMPTY,
            Concurrent.newList(PackRoot.BASE), Concurrent.newUnmodifiableSet("minecraft"),
            Concurrent.newUnmodifiableSet(PackCapability.VANILLA_CORE));
        PackStack bare = PackStack.of(Concurrent.newList(vanilla));
        PackStack stack = bare.withTextureIndex(TextureIndexer.index(bare));

        ConcurrentMap<Block.TintTarget, ColorMap> maps = ColorMapLoader.load(stack);
        assertThat(maps.size(), is(1));
        assertThat(maps.containsKey(Block.TintTarget.GRASS), is(true));
    }

    private static void png(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB), "PNG", path.toFile());
    }

}
