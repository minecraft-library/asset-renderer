package lib.minecraft.renderer.content.pack;

import dev.simplified.collection.Concurrent;
import lib.minecraft.renderer.asset.pack.MCMeta;
import lib.minecraft.renderer.asset.pack.PackCapability;
import lib.minecraft.renderer.asset.pack.PackRoot;
import lib.minecraft.renderer.asset.pack.ResourcePack;
import lib.minecraft.renderer.content.container.PackContainer;
import lib.minecraft.renderer.vanilla.id.PackId;
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
import java.time.Duration;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * What an atlas's {@code filter} sources cost to run: every one of an atlas is matched through one
 * budget, and a source the budget cannot pay for is refused whole.
 *
 * <p>The stack is two directory packs of three textures, small enough that the atlas's budget is the
 * least any budget holds. The lower pack stitches its {@code item/} textures; the upper one's atlas
 * file holds the filters under test.
 */
@DisplayName("An atlas's filter sources are matched within a budget")
class SpriteAtlasFilterTest {

    /** A sprite whose name the costly filter below takes too long to match against. */
    private static final String REPETITIVE = "item/" + "x".repeat(40);

    @TempDir
    Path tmp;

    @Test
    @DisplayName("a filter that is cheap to match removes what it names and nothing else")
    void aCheapFilterRemovesWhatItNames() throws IOException {
        SpriteAtlas atlas = SpriteAtlas.load(stackFiltering("{\"type\":\"filter\",\"pattern\":{\"path\":\"^item/emerald$\"}}"), SpriteAtlas.ITEMS);

        assertThat(atlas.contains("minecraft:item/emerald"), is(false));
        assertThat(atlas.contains("minecraft:item/diamond"), is(true));
        assertThat(atlas.contains("minecraft:" + REPETITIVE), is(true));
    }

    @Test
    @DisplayName("a filter that costs too much to match removes nothing, nor does any after it, and is reported once")
    void aCostlyFilterRemovesNothing() throws IOException {
        PackStack stack = stackFiltering(
            "{\"type\":\"filter\",\"pattern\":{\"path\":\"" + "x*".repeat(9) + "y\"}},"
                + "{\"type\":\"filter\",\"pattern\":{\"path\":\"^item/emerald$\"}},"
                + "{\"type\":\"filter\",\"pattern\":{\"path\":\"^item/diamond$\"}}");

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream original = System.err;
        SpriteAtlas atlas;
        try {
            System.setErr(new PrintStream(buffer, true, StandardCharsets.UTF_8));
            atlas = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> SpriteAtlas.load(stack, SpriteAtlas.ITEMS));
        } finally {
            System.setErr(original);
        }

        assertThat(atlas.contains("minecraft:" + REPETITIVE), is(true));
        assertThat("the atlas's budget is spent, so the filters after it are refused too",
            atlas.contains("minecraft:item/emerald"), is(true));
        assertThat(atlas.contains("minecraft:item/diamond"), is(true));

        String err = buffer.toString(StandardCharsets.UTF_8);
        assertThat(err, containsString("userpack"));
        assertThat(err, containsString("atlases/items.json"));
        assertThat("one line, not one per refused source", err.lines().count(), is(1L));
    }

    /** A lower pack stitching three item textures under an upper pack whose items atlas holds the given sources. */
    private PackStack stackFiltering(String sources) throws IOException {
        Path vanilla = this.tmp.resolve("vanilla");
        for (String texture : new String[] {"item/emerald", "item/diamond", REPETITIVE}) {
            Path png = vanilla.resolve("assets/minecraft/textures/" + texture + ".png");
            Files.createDirectories(png.getParent());
            ImageIO.write(new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB), "png", png.toFile());
        }
        write(vanilla.resolve("assets/minecraft/atlases/items.json"),
            "{\"sources\":[{\"type\":\"directory\",\"source\":\"item\",\"prefix\":\"item/\"}]}");

        Path user = this.tmp.resolve("user");
        write(user.resolve("assets/minecraft/atlases/items.json"), "{\"sources\":[" + sources + "]}");

        PackStack bare = PackStack.of(Concurrent.newList(pack(PackId.VANILLA, vanilla), pack(new PackId("userpack"), user)));
        return bare.withTextureIndex(TextureIndexer.index(bare));
    }

    private static ResourcePack pack(PackId id, Path root) {
        return new ResourcePack(id, new PackContainer.Directory(root), MCMeta.EMPTY,
            Concurrent.newList(PackRoot.BASE).toUnmodifiable(), Concurrent.newUnmodifiableSet("minecraft"),
            Concurrent.newUnmodifiableSet(PackCapability.VANILLA_CORE));
    }

    private static void write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

}
