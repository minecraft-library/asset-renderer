package lib.minecraft.renderer;

import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.ImageData;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.option.AnimationOptions;
import lib.minecraft.renderer.option.EntityOptions;
import lib.minecraft.renderer.option.OutputOptions;
import lib.minecraft.renderer.pipeline.loader.EntityModelLoader;
import lib.minecraft.renderer.support.ClientAssetsExtension;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Asking for a moving entity and getting one.
 *
 * <p>A subject that holds still at rest and walks under a stride is the largest population in the
 * corpus, and the two halves of animating one fail in different ways. A gait that does not resolve
 * renders a standing animal; a strip that does not is a single frame of a walking one, which is a
 * still picture of movement rather than movement. Both look like a working render and neither moves,
 * so each is asserted on its own.
 *
 * <p>Frames are compared rather than counted. A count says a container holds eight images and says
 * nothing about whether they differ, and a strip of eight identical frames is exactly what a gait
 * that failed to resolve produces.
 *
 * <p>Tagged {@code slow} - boots the full pipeline.
 */
@Tag("slow")
@DisplayName("an entity asked to move")
@ExtendWith(ClientAssetsExtension.class)
class EntityRendererAnimatedTest {

    /** Holds still at rest and walks under a stride, so it needs both halves to move at all. */
    private static final @NotNull String STRIDING = "minecraft:creeper";

    /** Writes nothing the tick drives, so no gait and no strip would move it. */
    private static final @NotNull String STILL = "minecraft:armor_stand";

    private static EntityRenderer renderer;

    @BeforeAll
    static void bootstrap() {
        ConcurrentMap<String, Entity> entities = EntityModelLoader.load();
        assumeTrue(!entities.isEmpty(), "entity_models.json not present - run entityModels first");
        renderer = new EntityRenderer(ClientAssetsExtension.context(), entities);
    }

    @Test
    @DisplayName("a subject a stride moves comes back as a strip whose frames differ")
    void aStridingSubjectMoves() {
        List<String> frames = framesOf(animated(STRIDING).build());
        assertTrue(frames.size() > 1, "a subject asked to move is expected to render a strip");
        // Every frame distinct rather than merely some: the gait sweeps one whole excursion and the
        // strip divides it evenly, so two equal frames would mean the sampling landed on a period
        // that divides the strip rather than on the excursion it was cut to cover.
        assertEquals(frames.size(), new LinkedHashSet<>(frames).size(),
            "every frame of a walking creeper is expected to differ");
    }

    @Test
    @DisplayName("a subject nothing moves stays a single frame rather than a repeated one")
    void aStillSubjectStaysOneFrame() {
        // The honest answer, and the one that costs something to get wrong in both directions: a
        // strip of eight identical armour stands is a bigger file claiming a movement that is not
        // there, and it is also what a broken gait resolution looks like on a subject that does move.
        assertEquals(1, framesOf(animated(STILL).build()).size(),
            "a subject nothing drives is expected to render one frame");
    }

    @Test
    @DisplayName("naming a preset still renders one frame, so nothing that named one changes")
    void aNamedPresetIsUnchanged() {
        // The three named presets stay orthogonal to the animation options - they choose where the
        // bones stand, never how many instants are sampled - so the whole of what this addition does
        // is reachable only by asking for it.
        for (EntityOptions.PoseMode named : List.of(EntityOptions.PoseMode.BIND,
            EntityOptions.PoseMode.IDLE, EntityOptions.PoseMode.WALK))
            assertEquals(1, framesOf(base(STRIDING).poseMode(named).build()).size(),
                named + " is expected to render the single frame it always did");
    }

    @Test
    @DisplayName("a frame count the caller named is the one they get")
    void aCallersOwnStripIsKept() {
        // The supplied strip fills a gap rather than overriding a decision, so a caller who has said
        // how they want their movement sampled keeps it.
        ImageData data = renderer.render(animated(STRIDING)
            .animation(AnimationOptions.builder().frameCount(4).ticksPerFrame(2).build())
            .build());
        assertEquals(4, data.getFrames().size(), "a caller's own frame count is expected to survive");
    }

    // ------------------------------------------------------------------------------------

    /** One signature per rendered frame, in order, so two frames are comparable by value. */
    private static @NotNull List<String> framesOf(@NotNull EntityOptions options) {
        return renderer.render(options).getFrames().stream()
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
        return base(id).poseMode(EntityOptions.PoseMode.ANIMATED);
    }

    private static EntityOptions.@NotNull Builder base(@NotNull String id) {
        return EntityOptions.builder()
            .entityId(Optional.of(id))
            .output(OutputOptions.builder().canvasSize(96).supersample(1).antiAlias(false).build())
            .padding(6)
            .fitMode(EntityOptions.FitMode.OUTPUT_SIZE);
    }

}
