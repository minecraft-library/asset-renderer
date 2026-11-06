package lib.minecraft.renderer;

import dev.simplified.collection.Concurrent;
import lib.minecraft.nbt.tag.CompoundTag;
import lib.minecraft.nbt.tag.FloatTag;
import lib.minecraft.nbt.tag.IntTag;
import lib.minecraft.nbt.tag.ListTag;
import lib.minecraft.nbt.tag.StringTag;
import lib.minecraft.renderer.asset.model.ModelData.GuiLight;
import lib.minecraft.renderer.asset.model.ModelTransform;
import lib.minecraft.renderer.call.request.ItemContext;
import lib.minecraft.renderer.call.request.ItemOptions;
import lib.minecraft.renderer.call.result.RenderResult;
import lib.minecraft.renderer.content.client.ClientAssets;
import lib.minecraft.renderer.content.client.ClientOptions;
import lib.minecraft.renderer.content.index.ItemModelDispatch.SpecialPose;
import lib.minecraft.renderer.content.index.ItemModelDispatch;
import lib.minecraft.renderer.content.index.RendererContext;
import lib.minecraft.renderer.engine.geometry.EulerRotation;
import lib.minecraft.renderer.store.diff.RenderDigest;
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
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * Coverage of a {@code minecraft:special} branch a stack picks, posed and lit in a slot by its own
 * {@code base} model as the client poses it: the base's {@code display.gui}, the nearest file up its
 * chain to declare one, none where none does or where no pack ships the base, and the base's
 * {@code gui_light}.
 * <p>
 * A fixture pack stacked over the client rewrites {@code minecraft:player_head} the way a pack hanging
 * many heads off one item does: a {@code range_dispatch} on {@code custom_model_data} whose fallback is
 * vanilla's own special, each threshold the same special naming another base, and two thresholds a
 * {@code composite} of specials, on one base and on two. No vanilla definition picks a special whose
 * base differs from its plain branch's, so these rows are the only witness.
 * <p>
 * Reads the client assets through {@link ClientAssetsExtension}, which abandons the class where nothing
 * has extracted the client yet.
 */
@DisplayName("A special branch a stack picks is posed and lit by its own base model")
@ExtendWith(ClientAssetsExtension.class)
class SpecialBasePoseTest {

    /** The block-backed item the fixture pack rewrites. */
    private static final String HEAD = "minecraft:player_head";

    /** The threshold whose base only inherits vanilla's skull template. */
    private static final float INHERITS = 1f;

    /** The threshold whose base, outside {@code item/}, declares no {@code gui} display. */
    private static final float NO_GUI = 2f;

    /** The threshold whose base no pack ships. */
    private static final float ABSENT = 3f;

    /** The threshold whose base declares a 4x {@code gui} display and {@code front} light. */
    private static final float MENU = 4f;

    /** The threshold whose base declares the same {@code gui} display and no light. */
    private static final float MENU_SIDE = 5f;

    /** The threshold drawing a {@code composite} of two specials on the menu base. */
    private static final float COMPOSITE_ONE_BASE = 6f;

    /** The threshold drawing a {@code composite} of a special on the menu base and one on the inheriting base. */
    private static final float COMPOSITE_TWO_BASES = 7f;

    /** The canvas every render here draws at. */
    private static final int SIZE = 128;

    /** The client with the fixture pack stacked over it. */
    private static RendererContext stacked;

    /**
     * Writes the fixture pack and stacks it over the client.
     *
     * @param work the directory the pack and the cache root are written under
     * @throws IOException if a fixture file cannot be written
     */
    @BeforeAll
    static void stackThePack(@TempDir Path work) throws IOException {
        Path pack = work.resolve("basepose");
        Path fixture = pack.resolve("assets/fixture/models");
        write(pack.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":84,\"description\":\"special base fixture\"}}");

        write(pack.resolve("assets/minecraft/items/player_head.json"), "{\"model\":{"
            + "\"type\":\"minecraft:range_dispatch\",\"property\":\"minecraft:custom_model_data\",\"index\":0,"
            + "\"fallback\":" + head("minecraft:item/template_skull") + ",\"entries\":["
            + entry(INHERITS, "fixture:item/inherits") + ","
            + entry(NO_GUI, "fixture:entity/no_gui") + ","
            + entry(ABSENT, "fixture:item/absent") + ","
            + entry(MENU, "fixture:item/menu") + ","
            + entry(MENU_SIDE, "fixture:item/menu_side") + ","
            + composite(COMPOSITE_ONE_BASE, "fixture:item/menu", "fixture:item/menu") + ","
            + composite(COMPOSITE_TWO_BASES, "fixture:item/menu", "fixture:item/inherits") + "]}}");
        write(fixture.resolve("item/inherits.json"), "{\"parent\":\"minecraft:item/template_skull\"}");
        write(fixture.resolve("entity/no_gui.json"), "{\"textures\":{\"particle\":\"minecraft:block/soul_sand\"},"
            + "\"display\":{\"thirdperson_righthand\":{\"scale\":[0,0,0]}}}");
        write(fixture.resolve("item/menu.json"), "{\"gui_light\":\"front\",\"parent\":\"minecraft:item/template_skull\","
            + "\"display\":{\"gui\":{\"translation\":[21,-6,0],\"scale\":[4,4,4]}}}");
        write(fixture.resolve("item/menu_side.json"), "{\"parent\":\"minecraft:item/template_skull\","
            + "\"display\":{\"gui\":{\"translation\":[21,-6,0],\"scale\":[4,4,4]}}}");

        ClientOptions options = ClientOptions.builder()
            .cacheRoot(work.resolve("cache").toFile())
            .texturePacks(Concurrent.adoptList(List.of(pack.toFile())))
            .build();
        stacked = RendererContext.load(new ClientAssets(options, ClientAssetsExtension.vanilla()));
    }

    @Test
    @DisplayName("a base inheriting the skull template draws vanilla's own player head")
    void anInheritingBaseDrawsVanillasHead() {
        RenderResult picked = render(stacked, Optional.of(INHERITS));

        assertThat(picked.substitutions(), is(empty()));
        assertThat(digest(picked), is(digest(render(ClientAssetsExtension.context(), Optional.empty()))));
    }

    @Test
    @DisplayName("a base with no gui display and a base no pack ships draw alike, unturned, on the slot's lower half")
    void noGuiAndNoBaseDrawUnturned() {
        RenderResult noGui = render(stacked, Optional.of(NO_GUI));
        RenderResult absent = render(stacked, Optional.of(ABSENT));

        assertThat(noGui.substitutions(), is(empty()));
        assertThat(absent.substitutions(), is(empty()));
        assertThat(digest(absent), is(digest(noGui)));
        assertThat(digest(noGui), is(not(digest(render(stacked, Optional.of(INHERITS))))));
        assertThat(Box.of(noGui), is(new Box(32, 64, 96, 128)));
    }

    @Test
    @DisplayName("a base with a 4x gui display draws the corner it leaves in the slot, front light apart from side light")
    void theMenuBaseDrawsItsCorner() {
        RenderResult front = render(stacked, Optional.of(MENU));
        RenderResult side = render(stacked, Optional.of(MENU_SIDE));

        assertThat(front.substitutions(), is(empty()));
        assertThat(side.substitutions(), is(empty()));
        assertThat(Box.of(front), is(new Box(104, 112, 128, 128)));
        assertThat(Box.of(side), is(new Box(104, 112, 128, 128)));
        assertThat(digest(front), is(not(digest(side))));
    }

    @Test
    @DisplayName("a composite of specials on one base draws at that base, and one on two bases draws the plain branch")
    void aCompositeOfSpecialsTakesTheirOneBase() {
        RenderResult oneBase = render(stacked, Optional.of(COMPOSITE_ONE_BASE));
        RenderResult twoBases = render(stacked, Optional.of(COMPOSITE_TWO_BASES));

        assertThat(oneBase.substitutions(), is(empty()));
        assertThat(twoBases.substitutions(), is(empty()));
        assertThat(digest(oneBase), is(digest(render(stacked, Optional.of(MENU)))));
        assertThat(digest(twoBases), is(digest(render(stacked, Optional.empty()))));
    }

    @Test
    @DisplayName("a stack below every threshold draws the plain branch, as the render with no stack does")
    void aStackBelowEveryThresholdDrawsThePlainBranch() {
        RenderResult below = render(stacked, Optional.of(0f));

        assertThat(below.substitutions(), is(empty()));
        assertThat(digest(below), is(digest(render(stacked, Optional.empty()))));
    }

    @Test
    @DisplayName("specialPose reads the picked base's nearest gui and light, the identity and side light where none, and nothing for the plain branch's base")
    void specialPoseReadsThePickedBase() {
        SpecialPose unturned = new SpecialPose(ModelTransform.IDENTITY, GuiLight.SIDE);
        ModelTransform corner = new ModelTransform(EulerRotation.NONE, new float[]{ 21f, -6f, 0f }, new float[]{ 4f, 4f, 4f });

        assertThat(pose(Optional.empty()), is(Optional.empty()));
        assertThat(pose(Optional.of(0f)), is(Optional.empty()));
        assertThat(pose(Optional.of(INHERITS)), is(Optional.of(new SpecialPose(
            new ModelTransform(new EulerRotation(30f, 45f, 0f), new float[]{ 0f, 3f, 0f }, new float[]{ 1f, 1f, 1f }), GuiLight.SIDE))));
        assertThat(pose(Optional.of(NO_GUI)), is(Optional.of(unturned)));
        assertThat(pose(Optional.of(ABSENT)), is(Optional.of(unturned)));
        assertThat(pose(Optional.of(MENU)), is(Optional.of(new SpecialPose(corner, GuiLight.FRONT))));
        assertThat(pose(Optional.of(MENU_SIDE)), is(Optional.of(new SpecialPose(corner, GuiLight.SIDE))));
        assertThat(pose(Optional.of(COMPOSITE_ONE_BASE)), is(Optional.of(new SpecialPose(corner, GuiLight.FRONT))));
        assertThat(pose(Optional.of(COMPOSITE_TWO_BASES)), is(Optional.empty()));
    }

    /**
     * Resolves the pose the player head's slot icon takes, with or without a stack carrying a
     * {@code custom_model_data} float.
     *
     * @param threshold the stack's {@code custom_model_data} float at index 0, empty for no stack
     * @return the picked special's pose, empty where the block's own pose stands
     */
    private static @NotNull Optional<SpecialPose> pose(@NotNull Optional<Float> threshold) {
        ItemOptions.Builder options = ItemOptions.builder().itemId(HEAD).type(ItemOptions.Type.GUI_ICON);
        threshold.ifPresent(value -> options.context(ItemContext.ofStack(stack(value))));
        ItemOptions built = options.build();
        return ItemModelDispatch.specialPose(stacked, built, built.itemModelAt(ItemOptions.Type.GUI_ICON));
    }

    /**
     * Draws the player head as an inventory icon, with or without a stack carrying a
     * {@code custom_model_data} float.
     *
     * @param context the context the render reads through
     * @param threshold the stack's {@code custom_model_data} float at index 0, empty for no stack
     * @return the render
     */
    private static @NotNull RenderResult render(@NotNull RendererContext context, @NotNull Optional<Float> threshold) {
        ItemOptions.Builder options = ItemOptions.builder()
            .itemId(HEAD)
            .type(ItemOptions.Type.GUI_ICON)
            .output(ItemOptions.DEFAULT_OUTPUT.mutate().canvasSize(SIZE).build());
        threshold.ifPresent(value -> options.context(ItemContext.ofStack(stack(value))));
        return new ItemRenderer(context).render(options.build());
    }

    /**
     * Builds a player head stack whose {@code custom_model_data} holds one float.
     *
     * @param value the float at index 0
     * @return the stack
     */
    private static @NotNull CompoundTag stack(float value) {
        ListTag<FloatTag> floats = new ListTag<>();
        floats.add(new FloatTag(value));
        CompoundTag data = new CompoundTag();
        data.put("floats", floats);
        CompoundTag components = new CompoundTag();
        components.put("minecraft:custom_model_data", data);
        CompoundTag stack = new CompoundTag();
        stack.put("id", new StringTag(HEAD));
        stack.put("count", new IntTag(1));
        stack.put("components", components);
        return stack;
    }

    /**
     * Digests a render's first frame.
     *
     * @param rendered the render
     * @return the CRC-32 of the first frame's pixels
     */
    private static long digest(@NotNull RenderResult rendered) {
        return RenderDigest.crc32(RenderDigest.firstFramePixels(rendered.image()));
    }

    /**
     * Writes a range dispatch entry picking a player head special with the given base.
     *
     * @param threshold the entry's threshold
     * @param base the special's base model id
     * @return the entry object
     */
    private static @NotNull String entry(float threshold, @NotNull String base) {
        return "{\"threshold\":" + threshold + ",\"model\":" + head(base) + "}";
    }

    /**
     * Writes a range dispatch entry picking a {@code composite} of player head specials, one per base.
     *
     * @param threshold the entry's threshold
     * @param bases each special's base model id, in the composite's order
     * @return the entry object
     */
    private static @NotNull String composite(float threshold, @NotNull String... bases) {
        List<String> heads = Arrays.stream(bases).map(SpecialBasePoseTest::head).toList();
        return "{\"threshold\":" + threshold + ",\"model\":{\"type\":\"minecraft:composite\",\"models\":["
            + String.join(",", heads) + "]}}";
    }

    /**
     * Writes vanilla's player head special with the given base - its kind and its transformation.
     *
     * @param base the special's base model id
     * @return the special node
     */
    private static @NotNull String head(@NotNull String base) {
        return "{\"type\":\"minecraft:special\",\"base\":\"" + base + "\",\"model\":{\"type\":\"minecraft:player_head\"},"
            + "\"transformation\":{\"left_rotation\":[1.0,0.0,0.0,-0.0],\"right_rotation\":[0.0,0.0,0.0,1.0],"
            + "\"scale\":[1.0,1.0,1.0],\"translation\":[0.5,0.0,0.5]}}";
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
     * The smallest box holding every pixel a render's first frame draws, its far edges exclusive.
     *
     * @param x0 the leftmost drawn column
     * @param y0 the topmost drawn row
     * @param x1 one past the rightmost drawn column
     * @param y1 one past the bottommost drawn row
     */
    private record Box(int x0, int y0, int x1, int y1) {

        /**
         * Measures the box of every pixel whose alpha is not zero.
         *
         * @param rendered the render
         * @return the box, every edge {@code -1} where nothing is drawn
         */
        static @NotNull Box of(@NotNull RenderResult rendered) {
            int[] pixels = RenderDigest.firstFramePixels(rendered.image());
            int x0 = SIZE, y0 = SIZE, x1 = -1, y1 = -1;
            for (int i = 0; i < pixels.length; i++) {
                if ((pixels[i] >>> 24) == 0) continue;

                int x = i % SIZE, y = i / SIZE;
                x0 = Math.min(x0, x);
                y0 = Math.min(y0, y);
                x1 = Math.max(x1, x + 1);
                y1 = Math.max(y1, y + 1);
            }

            return x1 < 0 ? new Box(-1, -1, -1, -1) : new Box(x0, y0, x1, y1);
        }

    }

}
