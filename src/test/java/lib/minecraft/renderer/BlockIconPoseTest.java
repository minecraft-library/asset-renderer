package lib.minecraft.renderer;

import dev.simplified.collection.Concurrent;
import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.asset.model.ModelTransform;
import lib.minecraft.renderer.content.client.ClientAssets;
import lib.minecraft.renderer.content.client.ClientOptions;
import lib.minecraft.renderer.content.index.RendererContext;
import lib.minecraft.renderer.engine.geometry.EulerRotation;
import lib.minecraft.renderer.support.ClientAssetsExtension;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Coverage of the pose a block's inventory icon is baked at where its item definition reaches a special.
 * Vanilla poses each special layer by its own base's {@code display.gui} whatever root reaches it, so the
 * chests under their {@code local_time} select, the copper golem statues under their block-state select
 * and the beds under their {@code composite} are posed as the copper chest under its plain special root,
 * by their bases. A pack that wraps a special in a dispatch keeps its pose, and a base that declares no
 * {@code display.gui}, or that no pack ships, poses the icon at no transform, as the client reads its
 * missing model.
 * <p>
 * Each expected pose is the base's own, read off the vanilla model files: {@code item/template_chest},
 * {@code item/template_copper_golem_statue} and {@code item/template_bed}.
 * <p>
 * Reads the client assets through {@link ClientAssetsExtension}, which abandons the class where nothing
 * has extracted the client yet.
 */
@DisplayName("A block icon reached through a special")
@ExtendWith(ClientAssetsExtension.class)
class BlockIconPoseTest {

    /** The pose {@code item/template_chest} declares. */
    private static final @NotNull List<Float> CHEST = List.of(30f, 45f, 0f, 0f, 0f, 0f, 0.625f, 0.625f, 0.625f);

    /** The pose {@code item/template_copper_golem_statue} declares. */
    private static final @NotNull List<Float> STATUE = List.of(30f, 45f, 180f, 0f, 2f, 0f, 0.55f, 0.55f, 0.55f);

    /** The pose {@code item/template_bed} declares. */
    private static final @NotNull List<Float> BED = List.of(30f, 160f, 0f, 2f, 3f, 0f, 0.5325f, 0.5325f, 0.5325f);

    /** The pose {@code block/block} declares, which a plain cube's icon inherits. */
    private static final @NotNull List<Float> BLOCK = List.of(30f, 225f, 0f, 0f, 0f, 0f, 0.625f, 0.625f, 0.625f);

    /** The eight copper golem statue blocks, each a select on its block state over one base. */
    private static final @NotNull List<String> STATUES = List.of(
        "minecraft:copper_golem_statue", "minecraft:exposed_copper_golem_statue",
        "minecraft:weathered_copper_golem_statue", "minecraft:oxidized_copper_golem_statue",
        "minecraft:waxed_copper_golem_statue", "minecraft:waxed_exposed_copper_golem_statue",
        "minecraft:waxed_weathered_copper_golem_statue", "minecraft:waxed_oxidized_copper_golem_statue");

    /** The sixteen bed colours, each a composite of two specials on one base. */
    private static final @NotNull List<String> BED_COLOURS = List.of(
        "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
        "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black");

    private static RendererContext context;

    @BeforeAll
    static void bootstrapPipeline() {
        context = ClientAssetsExtension.context();
    }

    @Test
    @DisplayName("the chests under their select are posed by their base, as the copper chest under its plain special is")
    void chestsArePosedByTheirBase() {
        for (String id : List.of("minecraft:chest", "minecraft:trapped_chest", "minecraft:copper_chest"))
            assertThat(id, pose(context, id), is(CHEST));
    }

    @Test
    @DisplayName("the copper golem statues under their block-state select are posed by their base")
    void statuesArePosedByTheirBase() {
        for (String id : STATUES)
            assertThat(id, pose(context, id), is(STATUE));
    }

    @Test
    @DisplayName("the beds under their composite are posed by the one base their two specials share")
    void bedsArePosedByTheirBase() {
        for (String colour : BED_COLOURS)
            assertThat(colour + " bed", pose(context, "minecraft:" + colour + "_bed"), is(BED));
    }

    @Test
    @DisplayName("a plain cube's icon keeps the pose its model inherits")
    void aPlainCubeKeepsItsPose() {
        assertThat(pose(context, "minecraft:stone"), is(BLOCK));
    }

    @Test
    @DisplayName("a special a pack wraps in a dispatch keeps its pose, and a base declaring no display.gui, or shipped by no pack, poses at no transform")
    void aWrappedSpecialKeepsItsPoseAndABareBasePosesAtNoTransform(@TempDir Path work) throws IOException {
        Path pack = work.resolve("specialpack");
        write(pack.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":84,\"description\":\"special fixture\"}}");
        write(pack.resolve("assets/minecraft/items/player_head.json"),
            "{\"model\":{\"type\":\"minecraft:range_dispatch\",\"property\":\"minecraft:custom_model_data\",\"entries\":[],"
                + "\"fallback\":{\"type\":\"minecraft:special\",\"base\":\"minecraft:item/template_skull\","
                + "\"model\":{\"type\":\"minecraft:player_head\"}}}}");
        write(pack.resolve("assets/minecraft/items/chest.json"),
            "{\"model\":{\"type\":\"minecraft:special\",\"base\":\"minecraft:item/bare_base\","
                + "\"model\":{\"type\":\"minecraft:chest\",\"texture\":\"minecraft:normal\"}}}");
        write(pack.resolve("assets/minecraft/models/item/bare_base.json"), "{\"textures\":{\"particle\":\"minecraft:block/oak_planks\"}}");
        write(pack.resolve("assets/minecraft/items/trapped_chest.json"),
            "{\"model\":{\"type\":\"minecraft:special\",\"base\":\"minecraft:item/nowhere\","
                + "\"model\":{\"type\":\"minecraft:chest\",\"texture\":\"minecraft:trapped\"}}}");

        ClientOptions options = ClientOptions.builder()
            .cacheRoot(work.resolve("cache").toFile())
            .texturePacks(Concurrent.adoptList(List.of(pack.toFile())))
            .build();
        RendererContext shadowed = RendererContext.load(new ClientAssets(options, ClientAssetsExtension.vanilla()));

        assertThat("the wrapped head is posed as the plain one",
            pose(shadowed, "minecraft:player_head"), is(pose(context, "minecraft:player_head")));
        assertThat("a base declaring no display.gui poses at no transform",
            pose(shadowed, "minecraft:chest"), is(pose(ModelTransform.IDENTITY)));
        assertThat("and so does a base no pack ships",
            pose(shadowed, "minecraft:trapped_chest"), is(pose(ModelTransform.IDENTITY)));
    }

    /**
     * The pose one block's icon is baked at.
     *
     * @param context the context the block is indexed in
     * @param id the block id
     * @return the icon's rotation, translation and scale, nine floats
     */
    private static @NotNull List<Float> pose(@NotNull RendererContext context, @NotNull String id) {
        Block block = context.findBlock(id).orElseThrow(() -> new AssertionError(id + " is expected in the block index"));
        return pose(block.iconGui().orElseThrow(() -> new AssertionError(id + " bakes an icon pose")));
    }

    /**
     * A transform's rotation, translation and scale, in that order.
     *
     * @param gui the transform
     * @return its nine floats
     */
    private static @NotNull List<Float> pose(@NotNull ModelTransform gui) {
        EulerRotation rotation = gui.getRotation();
        return List.of(rotation.pitch(), rotation.yaw(), rotation.roll(),
            gui.getTranslationX(), gui.getTranslationY(), gui.getTranslationZ(),
            gui.getScaleX(), gui.getScaleY(), gui.getScaleZ());
    }

    /**
     * Writes a fixture file, creating its parent directories.
     *
     * @param file the file
     * @param content its text
     * @throws IOException where it cannot be written
     */
    private static void write(@NotNull Path file, @NotNull String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

}
