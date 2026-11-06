package lib.minecraft.renderer.content.index;

import dev.simplified.collection.Concurrent;
import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.content.client.ClientAssets;
import lib.minecraft.renderer.content.client.ClientOptions;
import lib.minecraft.renderer.support.ClientAssetsExtension;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

/**
 * Coverage of the model a blockstate variant draws, over the real vanilla corpus and a fixture pack
 * stacked over it. A variant names its model by id, and vanilla reads an id written without a
 * namespace as {@code minecraft:}; the index holds every model under its qualified id, so a variant
 * that kept the id as written would find nothing and draw the element-less stand-in an unresolved id
 * gets.
 * <p>
 * Vanilla's {@code item_frame} and {@code glow_item_frame} are the blockstates that write their ids
 * that way, so they are the ones that show it: at {@code map=true} each draws its map frame.
 * <p>
 * A model's id is its path under {@code models/}, whatever folder it sits in, and the client resolves
 * a variant's id against every model it lists, so a variant naming a model under {@code item/} or in a
 * folder of its own draws it. The fixture pack hijacks two {@code note_block} states for two such
 * models - the shape ItemsAdder packs ship every custom block in - and gives {@code tripwire}, which no
 * {@code models/block/} file is named after, one variant naming the second; its {@code oak_fence} is one
 * multipart part naming the first, since a multipart apply resolves its model the same way. No vanilla
 * blockstate names a model outside {@code block/}, so nothing the parity store renders can see this;
 * these rows are the only witness.
 * <p>
 * Needs a real client, which it reads through the shared client-assets extension rather than
 * acquiring one of its own.
 */
@DisplayName("A blockstate variant draws the model it names")
@ExtendWith(ClientAssetsExtension.class)
class BlockIndexBuilderVariantModelTest {

    /** The fixture block model under {@code item/}, as ItemsAdder writes one. */
    private static final String ITEM_FOLDER_MODEL = "fixture:item/ia_auto/red_block";

    /** The fixture block model in a folder that is neither {@code block/} nor {@code item/}. */
    private static final String OWN_FOLDER_MODEL = "fixture:blocks/red_plant";

    /** The {@code note_block} state the fixture pack draws from {@link #ITEM_FOLDER_MODEL}. */
    private static final String ITEM_FOLDER_STATE = "instrument=basedrum,note=4,powered=false";

    /** The {@code note_block} state the fixture pack draws from {@link #OWN_FOLDER_MODEL}. */
    private static final String OWN_FOLDER_STATE = "instrument=basedrum,note=5,powered=false";

    /** The {@code note_block} state the fixture pack leaves on vanilla's model. */
    private static final String PLAIN_STATE = "instrument=harp,note=0,powered=false";

    private static RendererContext context;

    /** The client with the fixture pack stacked over it. */
    private static RendererContext stacked;

    /**
     * Reads the vanilla context, writes the fixture pack and stacks it over the client.
     *
     * @param work the directory the pack and the cache root are written under
     * @throws IOException if a fixture file cannot be written
     */
    @BeforeAll
    static void setup(@TempDir Path work) throws IOException {
        context = ClientAssetsExtension.context();

        Path pack = work.resolve("variantpack");
        Path assets = pack.resolve("assets");
        write(pack.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":84,\"description\":\"variant model fixture\"}}");
        png(assets.resolve("fixture/textures/block/red.png"));
        write(assets.resolve("fixture/models/item/ia_auto/red_block.json"),
            "{\"parent\":\"block/cube_all\",\"textures\":{\"all\":\"fixture:block/red\"}}");
        write(assets.resolve("fixture/models/blocks/red_plant.json"),
            "{\"textures\":{\"0\":\"fixture:block/red\",\"particle\":\"fixture:block/red\"},"
                + "\"elements\":[{\"from\":[4,0,4],\"to\":[12,8,12],\"faces\":{"
                + face("down") + "," + face("up") + "," + face("north") + ","
                + face("south") + "," + face("west") + "," + face("east") + "}}]}");
        write(assets.resolve("minecraft/blockstates/note_block.json"), "{\"variants\":{"
            + "\"" + PLAIN_STATE + "\":{\"model\":\"block/note_block\"},"
            + "\"" + ITEM_FOLDER_STATE + "\":{\"model\":\"" + ITEM_FOLDER_MODEL + "\"},"
            + "\"" + OWN_FOLDER_STATE + "\":{\"model\":\"" + OWN_FOLDER_MODEL + "\"}}}");
        write(assets.resolve("minecraft/blockstates/tripwire.json"),
            "{\"variants\":{\"\":{\"model\":\"" + OWN_FOLDER_MODEL + "\"}}}");
        write(assets.resolve("minecraft/blockstates/oak_fence.json"),
            "{\"multipart\":[{\"apply\":{\"model\":\"" + ITEM_FOLDER_MODEL + "\"}}]}");

        ClientOptions options = ClientOptions.builder()
            .cacheRoot(work.resolve("cache").toFile())
            .texturePacks(Concurrent.adoptList(List.of(pack.toFile())))
            .build();
        stacked = RendererContext.load(new ClientAssets(options, ClientAssetsExtension.vanilla()));
    }

    @Test
    @DisplayName("vanilla's item frame, whose blockstate names its models without a namespace, draws its map model at map=true")
    void itemFrameDrawsItsMapModel() {
        assertVariantDraws(context, "minecraft:item_frame", "map=true", "minecraft:block/item_frame_map");
        assertVariantDraws(context, "minecraft:item_frame", "map=false", "minecraft:block/item_frame");
    }

    @Test
    @DisplayName("vanilla's glow item frame draws its map model at map=true the same way")
    void glowItemFrameDrawsItsMapModel() {
        assertVariantDraws(context, "minecraft:glow_item_frame", "map=true", "minecraft:block/glow_item_frame_map");
        assertVariantDraws(context, "minecraft:glow_item_frame", "map=false", "minecraft:block/glow_item_frame");
    }

    @Test
    @DisplayName("a variant naming a model under item/ or in a folder of its own draws that model")
    void variantDrawsAModelOutsideBlock() {
        assertVariantDraws(stacked, "minecraft:note_block", ITEM_FOLDER_STATE, ITEM_FOLDER_MODEL);
        assertVariantDraws(stacked, "minecraft:note_block", OWN_FOLDER_STATE, OWN_FOLDER_MODEL);
        assertVariantDraws(stacked, "minecraft:note_block", PLAIN_STATE, "minecraft:block/note_block");
    }

    @Test
    @DisplayName("a block no models/block file is named after takes its first variant's model whatever its folder")
    void blockstateOnlyBlockTakesAModelOutsideBlock() {
        Block tripwire = stacked.findBlock("minecraft:tripwire").orElseThrow();

        assertThat("row model", tripwire.model(), is(sameInstance(stacked.findItemModel(OWN_FOLDER_MODEL).orElseThrow())));
        assertVariantDraws(stacked, "minecraft:tripwire", "", OWN_FOLDER_MODEL);
    }

    @Test
    @DisplayName("a multipart apply naming a model under item/ draws that model")
    void multipartApplyDrawsAModelOutsideBlock() {
        Block.Variant apply = stacked.findBlock("minecraft:oak_fence").orElseThrow()
            .multipart().orElseThrow().parts().getFirst().apply();

        assertThat("apply model id", apply.modelId(), is(ITEM_FOLDER_MODEL));
        assertThat("apply geometry", apply.geometry(), is(instanceOf(Block.ElementGeometry.class)));
        assertThat("apply model", ((Block.ElementGeometry) apply.geometry()).model(),
            is(sameInstance(stacked.findItemModel(ITEM_FOLDER_MODEL).orElseThrow())));
    }

    /**
     * Asserts that a block's variant names a model id and draws that model - the same loaded instance
     * the context answers for the id, rather than the element-less stand-in an unresolved id gets.
     *
     * @param in the context whose block index is read
     * @param blockId the block whose variant is read
     * @param key the variant key
     * @param modelId the qualified model id the variant names
     */
    private static void assertVariantDraws(@NotNull RendererContext in, @NotNull String blockId, @NotNull String key, @NotNull String modelId) {
        Block.Variant variant = in.findBlock(blockId).orElseThrow().variants().get(key);
        String label = blockId + "[" + key + "]";

        assertThat(label + " model id", variant.modelId(), is(modelId));
        assertThat(label + " geometry", variant.geometry(), is(instanceOf(Block.ElementGeometry.class)));
        assertThat(label + " model", ((Block.ElementGeometry) variant.geometry()).model(),
            is(sameInstance(in.findItemModel(modelId).orElseThrow())));
    }

    /**
     * Returns one face of the fixture plant, drawing the whole fixture texture.
     *
     * @param direction the face's direction
     * @return the face as model JSON
     */
    private static @NotNull String face(@NotNull String direction) {
        return "\"" + direction + "\":{\"uv\":[0,0,16,16],\"texture\":\"#0\"}";
    }

    /**
     * Writes a solid red 16x16 PNG.
     *
     * @param file the file to write
     * @throws IOException if the file cannot be written
     */
    private static void png(@NotNull Path file) throws IOException {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++)
            for (int x = 0; x < 16; x++)
                image.setRGB(x, y, 0xFFFF0000);
        Files.createDirectories(file.getParent());
        ImageIO.write(image, "PNG", file.toFile());
    }

    /**
     * Writes a UTF-8 text file, creating its directories.
     *
     * @param file the file to write
     * @param text the file's content
     * @throws IOException if the file cannot be written
     */
    private static void write(@NotNull Path file, @NotNull String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

}
