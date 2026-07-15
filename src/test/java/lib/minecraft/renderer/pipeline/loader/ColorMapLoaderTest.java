package lib.minecraft.renderer.pipeline.loader;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.ColorMap;
import lib.minecraft.renderer.pipeline.pack.Capability;
import lib.minecraft.renderer.pipeline.pack.MCMeta;
import lib.minecraft.renderer.pipeline.pack.PackContainer;
import lib.minecraft.renderer.pipeline.pack.PackId;
import lib.minecraft.renderer.pipeline.pack.PackRoot;
import lib.minecraft.renderer.pipeline.pack.PackStack;
import lib.minecraft.renderer.pipeline.pack.ResourcePack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link ColorMapLoader}: colormaps resolve through the pack stack like any
 * texture, and each PNG decodes to row-major big-endian ARGB bytes - bit-identical to the bundled
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

        byte[] pixels = ColorMapLoader.decode(png);
        assertArrayEquals(new byte[]{
            (byte) 0xFF, (byte) 0x7F, (byte) 0xB2, (byte) 0x38,
            (byte) 0xFF, 0x01, 0x02, 0x03
        }, pixels);
    }

    @Test
    @DisplayName("load resolves every colormap the stack supplies, attributed to its pack")
    void loadResolvesColormapsFromStack(@TempDir Path root) throws IOException {
        Path colormap = root.resolve("assets/minecraft/textures/colormap");
        png(colormap.resolve("grass.png"));
        png(colormap.resolve("foliage.png"));
        png(colormap.resolve("dry_foliage.png"));

        ResourcePack vanilla = new ResourcePack(PackId.VANILLA, new PackContainer.Directory(root), MCMeta.EMPTY,
            Concurrent.newList(PackRoot.BASE), Set.of("minecraft"), Set.of(Capability.VANILLA_CORE));
        PackStack bare = PackStack.of(Concurrent.newList(vanilla));
        PackStack stack = bare.withTextureIndex(TextureIndexer.index(bare));

        ConcurrentMap<ColorMap.Type, ColorMap> maps = ColorMapLoader.load(stack);

        assertEquals(3, maps.size());
        assertTrue(maps.containsKey(ColorMap.Type.GRASS));
        assertTrue(maps.containsKey(ColorMap.Type.FOLIAGE));
        assertTrue(maps.containsKey(ColorMap.Type.DRY_FOLIAGE));
        assertEquals("vanilla", maps.get(ColorMap.Type.GRASS).packId());
        assertTrue(maps.get(ColorMap.Type.GRASS).pixels().length > 0, "decoded pixels must be non-empty");
    }

    @Test
    @DisplayName("load skips a colormap type no pack supplies (graceful)")
    void loadSkipsMissingColormap(@TempDir Path root) throws IOException {
        Path colormap = root.resolve("assets/minecraft/textures/colormap");
        png(colormap.resolve("grass.png"));

        ResourcePack vanilla = new ResourcePack(PackId.VANILLA, new PackContainer.Directory(root), MCMeta.EMPTY,
            Concurrent.newList(PackRoot.BASE), Set.of("minecraft"), Set.of(Capability.VANILLA_CORE));
        PackStack bare = PackStack.of(Concurrent.newList(vanilla));
        PackStack stack = bare.withTextureIndex(TextureIndexer.index(bare));

        ConcurrentMap<ColorMap.Type, ColorMap> maps = ColorMapLoader.load(stack);
        assertEquals(1, maps.size());
        assertTrue(maps.containsKey(ColorMap.Type.GRASS));
    }

    private static void png(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB), "PNG", path.toFile());
    }

}
