package lib.minecraft.renderer;

import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.ImageData;
import dev.simplified.image.PixelBuffer;
import dev.simplified.image.frame.ImageFrame;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.pose.PoseStyle;
import lib.minecraft.renderer.bake.pose.StyleResolution;
import lib.minecraft.renderer.call.request.AnimationOptions;
import lib.minecraft.renderer.call.request.AppearanceOptions;
import lib.minecraft.renderer.call.request.EntityOptions;
import lib.minecraft.renderer.call.request.OutputOptions;
import lib.minecraft.renderer.content.index.EntityModelLoader;
import lib.minecraft.renderer.content.index.RendererContext;
import lib.minecraft.renderer.engine.frame.StripFit;
import lib.minecraft.renderer.engine.frame.Timeline;
import lib.minecraft.renderer.support.ClientAssetsExtension;
import lib.minecraft.renderer.vanilla.appearance.Age;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Asking for a moving entity and getting one.
 *
 * <p>A subject that holds still at rest and walks under a stride is the largest population in the
 * corpus, and the two halves of animating one fail in different ways. A style that does not resolve
 * renders a standing animal; a strip that does not is a single frame of a walking one, which is a
 * still picture of movement rather than movement. Both look like a working render and neither moves,
 * so each is asserted on its own.
 *
 * <p>An unnamed frame count means the subject decides - one frame for a style nothing moves, the
 * strip derived for its row for a moving one - and an explicit count is the caller's whichever way it
 * points, so a named {@code 1} is one frame OF the animation rather than a still request. A knob named
 * alone keeps the whole loop: a count keeps the row's own step, and a step fills as many frames as
 * cover the derived loop.
 *
 * <p>A group's canvas is a union of every member's silhouette, each measured over the strip its own
 * render draws, so the canvas is the same whichever member is drawn.
 *
 * <p>Frames are compared rather than counted. A count says a container holds eight images and says
 * nothing about whether they differ, and a strip of eight identical frames is exactly what a style
 * that failed to resolve produces.
 *
 * <p>Reads the client assets through {@link ClientAssetsExtension}, which abandons the class
 * where nothing has extracted the client yet.
 */
@DisplayName("an entity asked to move")
@ExtendWith(ClientAssetsExtension.class)
class EntityRendererAnimatedTest {

    /** Holds still at rest and walks under a stride, so it needs both halves to move at all. */
    private static final @NotNull String STRIDING = "minecraft:creeper";

    /** Writes nothing the tick drives, so no style and no strip would move it. */
    private static final @NotNull String STILL = "minecraft:armor_stand";

    /** Beats its wings faster than two coarse frames at rest, so its idle row derives the fine step. */
    private static final @NotNull String FLAPPING = "minecraft:bee";

    /**
     * Every shipped row carrying an empty source inventory, each against the row it composes over.
     *
     * <p>The frog's {@code jump} names no base at all, so {@code bind} is what it stands apart
     * from. The axolotl's two name {@code idle}, each age's own.
     */
    private static final @NotNull List<Held> HELD_ROWS = List.of(
        new Held("minecraft:bat", "rest", PoseStyle.IDLE, Optional.empty()),
        new Held("minecraft:frog", "jump", PoseStyle.BIND, Optional.empty()),
        new Held("minecraft:axolotl", "play_dead", PoseStyle.IDLE, Optional.of(Age.ADULT)),
        new Held("minecraft:axolotl", "play_dead", PoseStyle.IDLE, Optional.of(Age.BABY)));

    /**
     * One shipped row that moves nothing, and the row a render of it is expected to differ from.
     *
     * @param entityId the entity carrying the row
     * @param style the row's own id
     * @param base the id it composes over, which bind stands in for where it names none
     * @param age the age the row applies to, empty where it applies to both
     */
    private record Held(
        @NotNull String entityId,
        @NotNull String style,
        @NotNull String base,
        @NotNull Optional<Age> age
    ) {

        private EntityOptions.@NotNull Builder at(@NotNull String id) {
            EntityOptions.Builder built =
                EntityRendererAnimatedTest.base(this.entityId).style(id);
            return this.age
                .map(age -> built.appearance(AppearanceOptions.builder().age(age).build()))
                .orElse(built);
        }

        @Override
        public @NotNull String toString() {
            return this.entityId + " '" + this.style + "'"
                + this.age.map(age -> " at " + age).orElse("");
        }

    }

    private static RendererContext context;

    private static EntityRenderer renderer;

    @BeforeAll
    static void bootstrap() {
        ConcurrentMap<String, Entity> entities = EntityModelLoader.load();
        assumeTrue(!entities.isEmpty(), "entity_models.json not present - run entityModels first");
        context = ClientAssetsExtension.context();
        renderer = new EntityRenderer(context);
    }

    @Test
    @DisplayName("a subject a stride moves comes back as a strip whose frames differ")
    void aStridingSubjectMoves() {
        List<String> frames = framesOf(animated(STRIDING).build());
        assertTrue(frames.size() > 1, "a subject asked to move is expected to render a strip");
        // Every frame distinct rather than merely some: the strip is one loop of the walk, so two
        // equal frames would mean the style never moved.
        assertEquals(frames.size(), new LinkedHashSet<>(frames).size(),
            "every frame of a walking creeper is expected to differ");
    }

    @Test
    @DisplayName("a subject nothing moves stays a single frame rather than a repeated one")
    void aStillSubjectStaysOneFrame() {
        // The honest answer, and the one that costs something to get wrong in both directions: a
        // strip of eight identical armour stands is a bigger file claiming a movement that is not
        // there, and it is also what a broken style resolution looks like on a subject that does move.
        assertEquals(1, framesOf(animated(STILL).build()).size(),
            "a subject nothing drives is expected to render one frame");
    }

    @Test
    @DisplayName("a style nothing moves renders one frame under an unnamed count")
    void aStillStyleStaysOneFrame() {
        // The creeper's idle inventory is a charged-gated scroll, so an uncharged creeper's idle
        // holds still - and a still style with no named count is one frame, exactly as bind is.
        for (String style : List.of(PoseStyle.BIND, PoseStyle.IDLE))
            assertEquals(1, framesOf(base(STRIDING).style(style).build()).size(),
                style + " on an uncharged creeper is expected to render one frame");
    }

    @Test
    @DisplayName("a moving style with nothing named plays the strip derived for its row")
    void aMovingStylePlaysItsDerivedStrip() {
        StripFit strip = derived(STRIDING, PoseStyle.STRIDE);
        ImageData data = renderer.render(base(STRIDING).style(PoseStyle.STRIDE).build()).image();
        List<String> frames = framesOf(data);
        assertTrue(strip.frames() > 1, "a striding creeper's row derives a moving strip");
        assertEquals(strip.frames(), frames.size(),
            "a striding creeper with nothing named is expected to render the strip its row derives");
        assertEquals(frames.size(), new LinkedHashSet<>(frames).size(),
            "and every frame of it is expected to differ");
        for (ImageFrame frame : data.getFrames()) {
            assertEquals(strip.ticksPerFrame() * Timeline.MILLIS_PER_TICK, frame.delayMs(),
                "each frame is held for the strip's own step");
        }
    }

    @Test
    @DisplayName("a row whose strip takes the fine step plays a tick a frame with nothing named")
    void aFineStripPlaysATickAFrame() {
        StripFit strip = derived(FLAPPING, PoseStyle.IDLE);
        assertEquals(StripFit.FINE_STEP, strip.ticksPerFrame(), "a bee's idle row derives the fine step");
        ImageData data = renderer.render(base(FLAPPING).style(PoseStyle.IDLE).build()).image();
        assertEquals(strip.frames(), data.getFrames().size(),
            "a bee at rest with nothing named is expected to render the strip its row derives");
        for (ImageFrame frame : data.getFrames()) {
            assertEquals(Timeline.MILLIS_PER_TICK, frame.delayMs(),
                "each frame is held for the fine strip's one tick rather than the coarse step");
        }
    }

    @Test
    @DisplayName("a step named alone fills as many frames as cover the derived loop")
    void aNamedStepAloneCoversTheWholeLoop() {
        StripFit strip = derived(STRIDING, PoseStyle.STRIDE);
        ImageData data = renderer.render(base(STRIDING)
            .style(PoseStyle.STRIDE)
            .animation(AnimationOptions.builder().ticksPerFrame(1).build())
            .build()).image();
        assertEquals(Math.ceilDiv(strip.loopTicks(), 1), data.getFrames().size(),
            "a one-tick step is expected to draw every tick of the derived loop");
        for (ImageFrame frame : data.getFrames())
            assertEquals(Timeline.MILLIS_PER_TICK, frame.delayMs(), "each frame held for the named tick");
    }

    @Test
    @DisplayName("a count named alone keeps the row's own step")
    void aNamedCountAloneKeepsTheRowsStep() {
        StripFit strip = derived(STRIDING, PoseStyle.STRIDE);
        ImageData data = renderer.render(base(STRIDING)
            .style(PoseStyle.STRIDE)
            .animation(AnimationOptions.builder().frameCount(4).build())
            .build()).image();
        assertEquals(4, data.getFrames().size(), "the named count is the caller's");
        for (ImageFrame frame : data.getFrames()) {
            assertEquals(strip.ticksPerFrame() * Timeline.MILLIS_PER_TICK, frame.delayMs(),
                "and each frame is held for the derived strip's step");
        }
    }

    @Test
    @DisplayName("a group's canvas is the same whichever member is drawn")
    void aGroupCanvasDoesNotDependOnWhichMemberIsDrawn() {
        // Each member and each coat is measured over its own strip rather than at the drawn
        // subject's ticks, so the union is one set of silhouettes whichever member leads it. The
        // villager and the wandering trader amble at paces of their own, so their strides part - and
        // each baby is framed with the whole adult cohort, which the baby trader's six frames at a
        // tick a frame would catch short of its full swing.
        assertOneCanvas(PoseStyle.STRIDE, Age.ADULT, "minecraft:camel", "minecraft:camel_husk");
        assertOneCanvas(PoseStyle.IDLE, Age.ADULT, "minecraft:piglin", "minecraft:piglin_brute", "minecraft:zombified_piglin");
        assertOneCanvas(PoseStyle.STRIDE, Age.ADULT, "minecraft:villager", "minecraft:wandering_trader");
        assertOneCanvas(PoseStyle.STRIDE, Age.BABY, "minecraft:villager", "minecraft:wandering_trader");
    }

    @Test
    @DisplayName("an explicit count of one is one frame of the animation, not a still request")
    void anExplicitOneIsOneFrameOfTheAnimation() {
        assertEquals(1, framesOf(base(STRIDING)
                .style(PoseStyle.STRIDE)
                .animation(AnimationOptions.builder().frameCount(1).build())
                .build()).size(),
            "a caller who named one frame is expected to get exactly one");
    }

    @Test
    @DisplayName("a frame count the caller named is the one they get")
    void aCallersOwnStripIsKept() {
        // The supplied strip fills a gap rather than overriding a decision, so a caller who has said
        // how they want their movement sampled keeps it.
        ImageData data = renderer.render(animated(STRIDING)
            .animation(AnimationOptions.builder().frameCount(4).ticksPerFrame(2).build())
            .build()).image();
        assertEquals(4, data.getFrames().size(), "a caller's own frame count is expected to survive");
    }

    @Test
    @DisplayName("every shipped row moving nothing still renders a picture its base does not")
    void everyHeldRowRendersDistinctFromItsBase() {
        // What the emitter measures to ship one of these is that the row's binding plays a clip its
        // base does not, which is a statement about clip sets rather than about pixels. That two
        // bindings play different clips does not by itself say the renders differ, so the picture is
        // asserted here instead of inferred from the table.
        for (Held held : HELD_ROWS) {
            List<String> own = framesOf(held.at(held.style()).build());
            List<String> against = framesOf(held.at(held.base()).build());
            assertEquals(1, own.size(),
                held + " carries no source, so it is expected to render a single frame");
            assertNotEquals(own.getFirst(), against.getFirst(),
                held + " is expected to render a picture '" + held.base() + "' does not");
        }
    }

    // ------------------------------------------------------------------------------------

    /** The strip a render of one entity's style naming no knob draws, as the render resolves it. */
    private static @NotNull StripFit derived(@NotNull String id, @NotNull String style) {
        return StyleResolution.of(context.findEntity(id), id, style, AppearanceOptions.defaults()).strip();
    }

    /** Renders one style of each member at one age under the group scope and holds every canvas to the first's. */
    private static void assertOneCanvas(@NotNull String style, @NotNull Age age, @NotNull String @NotNull ... members) {
        List<String> sizes = new ArrayList<>(members.length);
        for (String member : members) {
            PixelBuffer first = renderer.render(EntityOptions.builder()
                    .entityId(member)
                    .style(style)
                    .appearance(AppearanceOptions.builder().age(age).build())
                    .fitMode(EntityOptions.FitMode.GROUP_BOUNDS)
                    .pixelsPerBlock(32)
                    .build())
                .image().getFrames().getFirst().pixels();
            sizes.add(first.width() + "x" + first.height());
        }
        assertEquals(1, new LinkedHashSet<>(sizes).size(),
            "every member of one group is expected to share one canvas under '" + style + "' at " + age + ": "
                + List.of(members) + " drew " + sizes);
    }

    /** One signature per rendered frame, in order, so two frames are comparable by value. */
    private static @NotNull List<String> framesOf(@NotNull EntityOptions options) {
        return framesOf(renderer.render(options).image());
    }

    /** One signature per frame of a rendered image, in order. */
    private static @NotNull List<String> framesOf(@NotNull ImageData image) {
        return image.getFrames().stream()
            .map(frame -> {
                StringBuilder out = new StringBuilder();
                var pixels = frame.pixels().toBufferedImage();
                for (int y = 0; y < pixels.getHeight(); y += 2)
                    for (int x = 0; x < pixels.getWidth(); x += 2)
                        out.append(Integer.toHexString(pixels.getRGB(x, y)));
                return Integer.toHexString(out.toString().hashCode());
            })
            .toList();
    }

    private static EntityOptions.@NotNull Builder animated(@NotNull String id) {
        return base(id).style(PoseStyle.ANIMATED);
    }

    private static EntityOptions.@NotNull Builder base(@NotNull String id) {
        return EntityOptions.builder()
            .entityId(id)
            .output(OutputOptions.builder().canvasSize(96).supersample(1).antiAlias(false).build())
            .padding(6)
            .fitMode(EntityOptions.FitMode.OUTPUT_SIZE);
    }

}
