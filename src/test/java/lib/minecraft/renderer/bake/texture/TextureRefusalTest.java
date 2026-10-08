package lib.minecraft.renderer.bake.texture;

import dev.simplified.collection.Concurrent;
import dev.simplified.image.pixel.PixelBuffer;
import dev.simplified.util.Possible;
import lib.minecraft.renderer.asset.pack.MCMeta;
import lib.minecraft.renderer.asset.pack.PackCapability;
import lib.minecraft.renderer.asset.pack.PackRoot;
import lib.minecraft.renderer.asset.pack.ResourcePack;
import lib.minecraft.renderer.content.pack.PackContainer;
import lib.minecraft.renderer.content.pack.PackStack;
import lib.minecraft.renderer.content.pack.TextureIndexer;
import lib.minecraft.renderer.exception.RenderException;
import lib.minecraft.renderer.exception.RendererException;
import lib.minecraft.renderer.vanilla.id.PackId;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Coverage of {@link TextureRefusal}: the pixels of a texture lookup that holds some are handed back,
 * and each of the two answers that hold none is refused in its own words - an id no pack serves as
 * unregistered, and a texture that yields no pixels as one that could not be read - a file that does not
 * decode, and as much a texture whose sidecar does not parse or whose frame size does not divide its
 * strip.
 * <p>
 * The unregistered wording is pinned byte-for-byte, for a caller outside the renderer that matches it.
 */
@DisplayName("A texture refusal is worded by the state the lookup answered")
class TextureRefusalTest {

    private static final String ID = "minecraft:block/texture_refusal_test";

    private static final PixelBuffer PIXELS = PixelBuffer.create(2, 2);

    @Test
    @DisplayName("a lookup holding pixels hands them back")
    void presentIsHandedBack() {
        assertThat(TextureRefusal.require(Possible.of(PIXELS), ID), is(sameInstance(PIXELS)));
    }

    @Test
    @DisplayName("an id no pack serves is refused as unregistered")
    void absentIsUnregistered() {
        RenderException refusal = assertThrows(RenderException.class,
            () -> TextureRefusal.require(Possible.absent(), ID));

        assertThat(refusal.getMessage(), is("No texture registered for id '" + ID + "'"));
    }

    @Test
    @DisplayName("a served id with no pixels is refused as unreadable")
    void emptyIsUndecodable() {
        RenderException refusal = assertThrows(RenderException.class,
            () -> TextureRefusal.require(Possible.empty(), ID));

        assertThat(refusal.getMessage(), is("Texture '" + ID + "' could not be read"));
    }

    @Test
    @DisplayName("a reader's own refusal words the unserved id, and the unreadable one keeps its wording")
    void aReaderWordsOnlyTheUnservedId() {
        RenderException own = new RenderException("Window chrome sprite '%s' does not resolve", ID);

        assertThat(assertThrows(RenderException.class, () -> TextureRefusal.require(Possible.absent(), ID, () -> own)),
            is(sameInstance(own)));
        assertThat(assertThrows(RenderException.class, () -> TextureRefusal.require(Possible.empty(), ID, () -> own)).getMessage(),
            is("Texture '" + ID + "' could not be read"));
    }

    @Test
    @DisplayName("the refusal is a renderer exception, which a batch caller skips")
    void theRefusalIsSkippable() {
        RenderException refusal = assertThrows(RenderException.class,
            () -> TextureRefusal.require(Possible.empty(), ID));

        assertThat(refusal, is(instanceOf(RendererException.class)));
    }

    @Test
    @DisplayName("a texture vanilla's sprite loader refuses, read by a render that does not substitute, is refused as unreadable")
    void aSpriteVanillaRefusesIsRefusedAsUndecodable(@TempDir Path root) throws IOException {
        // The pack stack's own answer, as a refusing reader takes it: a texture beside a sidecar that is
        // not JSON, and a strip its undeclared square frame does not divide.
        Path blockDir = root.resolve("assets/minecraft/textures/block");
        Files.createDirectories(blockDir);
        writePng(blockDir.resolve("refusal_sidecar.png"), 16, 16);
        Files.writeString(blockDir.resolve("refusal_sidecar.png.mcmeta"), "{ \"animation\": ");
        writePng(blockDir.resolve("refusal_strip.png"), 16, 40);
        Files.writeString(blockDir.resolve("refusal_strip.png.mcmeta"), "{\"animation\":{}}");

        ResourcePack vanilla = new ResourcePack(
            PackId.VANILLA, new PackContainer.Directory(root), MCMeta.EMPTY,
            Concurrent.newList(PackRoot.BASE), Concurrent.newUnmodifiableTreeSet("minecraft"),
            Concurrent.newUnmodifiableLinkedSet(PackCapability.VANILLA_CORE));
        PackStack bare = PackStack.of(Concurrent.newList(vanilla));
        PackStack stack = bare.withTextureIndex(TextureIndexer.index(bare));

        for (String id : List.of("minecraft:block/refusal_sidecar", "minecraft:block/refusal_strip")) {
            Possible<PixelBuffer> texture = stack.pixels(ResourceId.parse(id));
            RenderException refusal = assertThrows(RenderException.class, () -> TextureRefusal.require(texture, id));

            assertThat(refusal.getMessage(), is("Texture '" + id + "' could not be read"));
        }
    }

    /**
     * Writes an opaque image of the given size as a PNG.
     *
     * @param path the file to write
     * @param width the image width
     * @param height the image height
     * @throws IOException if writing fails
     */
    private static void writePng(@NotNull Path path, int width, int height) throws IOException {
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "PNG", path.toFile());
    }

}
