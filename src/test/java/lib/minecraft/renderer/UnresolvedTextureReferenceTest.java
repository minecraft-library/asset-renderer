package lib.minecraft.renderer;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.ImageData;
import dev.simplified.image.data.ImageFrame;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.asset.model.ModelData;
import lib.minecraft.renderer.bake.texture.TextureRefusal;
import lib.minecraft.renderer.content.client.ClientAssets;
import lib.minecraft.renderer.content.client.ClientOptions;
import lib.minecraft.renderer.content.index.RendererContext;
import lib.minecraft.renderer.engine.geometry.Face;
import lib.minecraft.renderer.engine.texture.MissingSprite;
import lib.minecraft.renderer.exception.RenderException;
import lib.minecraft.renderer.request.AppearanceOptions;
import lib.minecraft.renderer.request.BlockOptions;
import lib.minecraft.renderer.request.EntityOptions;
import lib.minecraft.renderer.request.ItemOptions;
import lib.minecraft.renderer.request.OutputOptions;
import lib.minecraft.renderer.store.diff.RenderDigest;
import lib.minecraft.renderer.support.ClientAssetsExtension;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Coverage of what a face draws whose {@code #variable} chain resolves to no texture, as vanilla draws
 * its missing sprite there: the model walk looks the face up by its raw reference, which no pack
 * supplies, so with the flag on it draws exactly what a face naming a texture no pack ships draws - the
 * checkerboard on that face, the rest of the model as it is - and reports the reference once, and with
 * the flag off the render is refused naming the reference.
 * <p>
 * Held through both block types, the held item and an entity's carried block. Each subject is a cube a
 * pack laid over the vanilla stack declares, whose top face names a variable nothing binds and whose
 * other faces name stone. It is compared with the same cube whose top names a texture no pack ships,
 * and with one whose every face names stone. The reporting sets are static and live as long as the
 * process, so each reference is left unresolved by one subject alone.
 * <p>
 * Reads the client assets through {@link ClientAssetsExtension}, which abandons the class where nothing
 * has extracted the client yet.
 */
@ExtendWith(ClientAssetsExtension.class)
@DisplayName("A face whose texture reference resolves nowhere draws what a missing texture draws, or refuses")
class UnresolvedTextureReferenceTest {

    /**
     * The texture every face but the top one names.
     */
    private static final String STONE = "minecraft:block/stone";

    /**
     * A texture no pack ships, which the reference cubes name on their top face.
     */
    private static final String ABSENT_TEXTURE = "minecraft:block/unresolved_reference_absent";

    /**
     * The block whose top face names a texture no pack ships.
     */
    private static final String MISSING_BLOCK = "minecraft:unresolved_reference_missing";

    /**
     * The block whose every face names stone.
     */
    private static final String INTACT_BLOCK = "minecraft:unresolved_reference_intact";

    /**
     * The block whose model the element walk is read off directly.
     */
    private static final String WALKED_BLOCK = "minecraft:unresolved_reference_walk";

    /**
     * The item whose top face names a texture no pack ships.
     */
    private static final String MISSING_ITEM = "minecraft:unresolved_reference_held_missing";

    /**
     * The item whose every face names stone.
     */
    private static final String INTACT_ITEM = "minecraft:unresolved_reference_held_intact";

    /**
     * The canvas every render here is drawn at - large enough for a carried block to cover pixels.
     */
    private static final OutputOptions OUTPUT = OutputOptions.builder().canvasSize(128).build();

    @TempDir
    static Path work;

    /**
     * The vanilla stack with the fixture pack laid over it.
     */
    private static RendererContext packed;

    /**
     * Writes the fixture pack - a block cube per subject, its two reference cubes, the walked cube, and
     * the three item cubes - and loads it over the vanilla stack through the real pipeline.
     *
     * @throws IOException if writing a fixture fails
     */
    @BeforeAll
    static void bootstrapPipeline() throws IOException {
        Path pack = work.resolve("unresolved_reference");
        write(pack.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":84,\"description\":\"unresolved reference fixture\"}}");

        for (String subject : List.of("isometric", "face", "carried", "walk"))
            write(blockModel("unresolved_reference_" + subject, pack), cube(reference(subject), "", ""));
        write(blockModel("unresolved_reference_missing", pack), cube("#gone", ", \"gone\": \"" + ABSENT_TEXTURE + "\"", ""));
        write(blockModel("unresolved_reference_intact", pack), cube("#all", "", ""));

        // The held pose reads the model's display, which the block parent supplies as it does for a block item.
        String held = "\"parent\": \"minecraft:block/block\", ";
        write(itemModel("unresolved_reference_held", pack), cube(reference("held"), "", held));
        write(itemModel("unresolved_reference_held_missing", pack), cube("#gone", ", \"gone\": \"" + ABSENT_TEXTURE + "\"", held));
        write(itemModel("unresolved_reference_held_intact", pack), cube("#all", "", held));

        ClientOptions options = ClientOptions.builder()
            .cacheRoot(work.resolve("cache").toFile())
            .texturePacks(Concurrent.adoptList(List.of(pack.toFile())))
            .build();
        packed = RendererContext.load(new ClientAssets(options, ClientAssetsExtension.vanillaRoot()));
    }

    // ------------------------------------------------------------------------------------
    // The subjects.
    // ------------------------------------------------------------------------------------

    /**
     * Every subject, each with its own unresolved reference and the render that draws it under the
     * request's flag.
     *
     * @return the subjects
     */
    private static @NotNull Stream<Subject> subjects() {
        return Stream.of(
            new Subject("isometric block", "minecraft:unresolved_reference_isometric", reference("isometric"),
                MISSING_BLOCK, INTACT_BLOCK, (id, substitute) -> new BlockRenderer(packed).render(
                    block(id, substitute).type(BlockOptions.Type.ISOMETRIC_3D).build())),
            new Subject("block face", "minecraft:unresolved_reference_face", reference("face"),
                MISSING_BLOCK, INTACT_BLOCK, (id, substitute) -> new BlockRenderer(packed).render(
                    block(id, substitute).type(BlockOptions.Type.BLOCK_FACE_2D).face(Face.UP).build())),
            new Subject("held item", "minecraft:unresolved_reference_held", reference("held"),
                MISSING_ITEM, INTACT_ITEM, (id, substitute) -> new ItemRenderer(packed).render(ItemOptions.builder()
                    .itemId(id)
                    .type(ItemOptions.Type.HELD_3D)
                    .substituteMissing(substitute)
                    .output(ItemOptions.DEFAULT_OUTPUT.mutate().canvasSize(128).build())
                    .build())),
            new Subject("carried block", "minecraft:unresolved_reference_carried", reference("carried"),
                MISSING_BLOCK, INTACT_BLOCK, (id, substitute) -> new EntityRenderer(packed).render(EntityOptions.builder()
                    .entityId("minecraft:enderman")
                    .appearance(AppearanceOptions.builder().carried(id).build())
                    .substituteMissing(substitute)
                    .output(OUTPUT)
                    .build()))
        );
    }

    @TestFactory
    @DisplayName("with the flag on, the face draws what a missing texture draws, reported once by its reference")
    @NotNull Stream<DynamicTest> theFaceDrawsTheCheckerboard() {
        return perSubject(subject -> {
            AtomicReference<ImageData> drawn = new AtomicReference<>();
            String reported = errDuring(() -> drawn.set(subject.render().draw(subject.id(), true)));

            assertThat(subject + " draws what a face naming a missing texture draws",
                picture(drawn.get()), is(picture(subject.render().draw(subject.missing(), true))));
            assertThat(subject + " draws the face rather than stone on it",
                picture(drawn.get()), is(not(picture(subject.render().draw(subject.intact(), true)))));
            assertThat(subject + " reports the reference once",
                occurrences(reported, "Missing texture '" + subject.reference() + "' - drawing the checkerboard"), is(1));
        });
    }

    @TestFactory
    @DisplayName("with the flag off, the render is refused naming the reference")
    @NotNull Stream<DynamicTest> theRenderIsRefusedWithTheFlagOff() {
        return perSubject(subject -> {
            assertDoesNotThrow(() -> subject.render().draw(subject.intact(), false), subject + " draws its intact cube");

            RenderException refused = assertThrows(RenderException.class, () -> subject.render().draw(subject.id(), false));
            assertThat(refused.getMessage(), is("No texture registered for id '" + subject.reference() + "'"));
        });
    }

    @TestFactory
    @DisplayName("a model whose references all resolve draws the same on either arm and reports nothing")
    @NotNull Stream<DynamicTest> aResolvingModelIsUnchanged() {
        return perSubject(subject -> {
            AtomicReference<ImageData> drawn = new AtomicReference<>();
            String reported = errDuring(() -> drawn.set(subject.render().draw(subject.intact(), true)));

            assertThat(subject + " draws the same with the flag off",
                picture(drawn.get()), is(picture(subject.render().draw(subject.intact(), false))));
            assertThat(subject + " reports nothing", reported, not(containsString("drawing the checkerboard")));
        });
    }

    @Test
    @DisplayName("the walk hands a resolved face its id and an unresolved one its raw reference, through the shared stand-in")
    void theWalkHandsAnUnresolvedFaceToItsSecondFunction() {
        ModelData model = packed.findBlock(WALKED_BLOCK).orElseThrow().model();
        String unresolved = reference("walk");

        assertThat(model.resolveTextureReference("#all"), is(Optional.of(STONE)));
        assertThat(model.resolveTextureReference(unresolved), is(Optional.empty()));

        Set<String> resolvedIds = new LinkedHashSet<>();
        Set<String> unresolvedRefs = new LinkedHashSet<>();
        model.loadElementFaceTextures(id -> {
            resolvedIds.add(id);
            return Optional.empty();
        }, ref -> {
            unresolvedRefs.add(ref);
            return Optional.empty();
        });
        assertThat("a resolving face is asked for by its id", resolvedIds, contains(STONE));
        assertThat("the unresolved face is asked for by its raw reference", unresolvedRefs, contains(unresolved));

        RendererContext substituting = packed.withMissingTexture();
        Function<String, Optional<PixelBuffer>> faces = id -> Optional.of(TextureRefusal.require(substituting.resolveTexture(id), id));
        ConcurrentMap<String, PixelBuffer> loaded = model.loadElementFaceTextures(faces, faces);
        assertThat("the unresolved face holds the one shared stand-in", loaded.get(unresolved), is(sameInstance(MissingSprite.sprite())));
        assertThat("a resolving face holds its texture", loaded.get("#all"), is(not(sameInstance(MissingSprite.sprite()))));

        packed.findBlock(INTACT_BLOCK).orElseThrow().model().loadElementFaceTextures(id -> Optional.empty(), ref -> {
            throw new AssertionError("a model whose references all resolve asked after '" + ref + "'");
        });
    }

    // ------------------------------------------------------------------------------------

    /**
     * One dynamic test per subject, named for it.
     *
     * @param body what each subject is held to
     * @return the tests
     */
    private static @NotNull Stream<DynamicTest> perSubject(@NotNull Consumer<Subject> body) {
        return subjects().map(subject -> DynamicTest.dynamicTest(subject.name(), () -> body.accept(subject)));
    }

    /**
     * The variable a subject's top face names and nothing binds.
     *
     * @param subject the subject's name
     * @return the reference
     */
    private static @NotNull String reference(@NotNull String subject) {
        return "#nowhere_" + subject;
    }

    /**
     * Spells a full cube's model file: its top face naming {@code up}, every other face naming stone.
     *
     * @param up the reference the top face names
     * @param bindings further bindings, each led by a comma
     * @param lead members written ahead of the bindings, each followed by a comma
     * @return the model json
     */
    private static @NotNull String cube(@NotNull String up, @NotNull String bindings, @NotNull String lead) {
        return "{" + lead + "\"textures\": {\"all\": \"" + STONE + "\"" + bindings + "}, \"elements\": [{"
            + "\"from\": [0, 0, 0], \"to\": [16, 16, 16], \"faces\": {"
            + "\"down\": {\"texture\": \"#all\"}, \"up\": {\"texture\": \"" + up + "\"}, "
            + "\"north\": {\"texture\": \"#all\"}, \"south\": {\"texture\": \"#all\"}, "
            + "\"west\": {\"texture\": \"#all\"}, \"east\": {\"texture\": \"#all\"}}}]}";
    }

    private static @NotNull Path blockModel(@NotNull String name, @NotNull Path pack) {
        return pack.resolve("assets/minecraft/models/block/" + name + ".json");
    }

    private static @NotNull Path itemModel(@NotNull String name, @NotNull Path pack) {
        return pack.resolve("assets/minecraft/models/item/" + name + ".json");
    }

    private static @NotNull BlockOptions.Builder block(@NotNull String id, boolean substitute) {
        return BlockOptions.builder().blockId(id).substituteMissing(substitute).output(OUTPUT);
    }

    /**
     * Spells a render as its frame count, its first frame's size and every frame's pixels, so two renders
     * compare by all three.
     *
     * @param image the rendered image
     * @return the render's picture
     */
    private static @NotNull List<Object> picture(@NotNull ImageData image) {
        ImageFrame first = image.getFrames().getFirst();
        return List.of(image.getFrames().size(), first.pixels().width(), first.pixels().height(),
            RenderDigest.frameCrcs(image));
    }

    /**
     * Counts the times a line appears in captured output.
     *
     * @param output the captured output
     * @param line the line looked for
     * @return how many times it appears
     */
    private static int occurrences(@NotNull String output, @NotNull String line) {
        int count = 0;
        for (int at = output.indexOf(line); at >= 0; at = output.indexOf(line, at + line.length()))
            count++;
        return count;
    }

    /**
     * Runs a body with {@code System.err} captured, restoring the real stream afterwards.
     *
     * @param body the call whose diagnostic output is being read
     * @return everything the body wrote to {@code System.err}
     */
    private static @NotNull String errDuring(@NotNull Runnable body) {
        PrintStream original = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));

        try {
            body.run();
        } finally {
            System.setErr(original);
        }

        return captured.toString(StandardCharsets.UTF_8);
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
     * Draws one subject under the request's flag.
     */
    @FunctionalInterface
    private interface Render {

        /**
         * Draws a subject id through the fixture stack.
         *
         * @param id the block or item id drawn, or the block the entity carries
         * @param substitute whether the request substitutes a texture it cannot read
         * @return the render
         */
        @NotNull ImageData draw(@NotNull String id, boolean substitute);

    }

    /**
     * One subject: the id whose top face resolves nowhere, the reference it names, the two cubes it is
     * compared with, and the render drawing any of the three.
     *
     * @param name the subject's name, which the parameterised display reads
     * @param id the id whose top face names {@code reference}
     * @param reference the variable the top face names and nothing binds
     * @param missing the id whose top face names a texture no pack ships
     * @param intact the id whose every face names stone
     * @param render the render drawing any of the three ids under the request's flag
     */
    private record Subject(
        @NotNull String name,
        @NotNull String id,
        @NotNull String reference,
        @NotNull String missing,
        @NotNull String intact,
        @NotNull Render render
    ) {

        @Override
        public @NotNull String toString() {
            return this.name;
        }

    }

}
