package lib.minecraft.renderer;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.ImageData;
import dev.simplified.image.PixelBuffer;
import dev.simplified.image.frame.ImageFrame;
import dev.simplified.image.pixel.BlendMode;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.mesh.EntityMesh;
import lib.minecraft.renderer.asset.pose.EntityPose;
import lib.minecraft.renderer.asset.pose.PoseStyle;
import lib.minecraft.renderer.call.request.AnimationOptions;
import lib.minecraft.renderer.call.request.EntityOptions;
import lib.minecraft.renderer.content.index.RendererContext;
import lib.minecraft.renderer.engine.draw.PassDeclaration;
import lib.minecraft.renderer.engine.geometry.EulerRotation;
import lib.minecraft.renderer.engine.math.Vector2f;
import lib.minecraft.renderer.engine.math.Vector3f;
import lib.minecraft.renderer.engine.pose.PoseExpr;
import lib.minecraft.renderer.engine.pose.PoseOperator;
import lib.minecraft.renderer.engine.pose.PoseWidth;
import lib.minecraft.renderer.store.diff.RenderDigest;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a pass draws on a frame whose opacity is at or under vanilla's skip: no fragment, and no part
 * of the canvas.
 *
 * <p>An alpha-0 fragment is not nothing. It still reaches the composite and, on a pass that writes
 * depth, the depth buffer - so the skip is taken before the pass emits a triangle, and the canvas walk
 * takes the same test, measuring the canvas in what each frame draws. The fixture pass draws full-bright
 * through a cutout pipeline, whose composite writes the fragment over what is there with its alpha, so
 * a fragment let through at no opacity would leave the pass's colour at zero alpha in the frame; a
 * source-over pass composites such a fragment to the destination unchanged and would hide it.
 *
 * <p>The pass is a shell around the body, so it covers the body's pixels and reaches past its outline:
 * a frame that draws it shows its colour, and a canvas that measures it is larger than the body's own.
 * Its opacity ramps with elapsed age from zero, so frame 0 is at no opacity and frame 10, three ticks a
 * frame, is at three tenths.
 */
@DisplayName("a pass at no opacity draws nothing")
class EntityRendererOpacityTest {

    private static final @NotNull String ENTITY = "minecraft:test";

    /** The body's texture, grey. */
    private static final @NotNull String BODY = "test/body";

    /** The shell pass's texture, a red no other surface draws. */
    private static final @NotNull String SHELL = "test/shell";

    private static final int SHELL_RGB = 0xC02020;

    private static final @NotNull Map<String, PixelBuffer> TEXTURES = Map.of(
        "minecraft:entity/" + BODY, solid(0xFF808080),
        "minecraft:entity/" + SHELL, solid(0xFF000000 | SHELL_RGB));

    @Test
    @DisplayName("a frame at no opacity draws none of the pass and sizes no canvas by it, and a frame above it does both")
    void aPassAtNoOpacityDrawsNothing() {
        Entity bare = subject(false);
        Entity shelled = subject(true);

        List<Object> alone = frame(render(bare, 1).getFrames().getFirst());
        List<Object> still = frame(render(shelled, 1).getFrames().getFirst());
        assertEquals(alone.subList(0, 2), still.subList(0, 2),
            "a frame at no opacity sizes the canvas by the body alone");
        assertEquals(alone, still, "and draws the body alone");

        ImageData strip = render(shelled, 11);
        assertEquals(11, strip.getFrames().size(), "the named strip renders whole");
        assertEquals(0, shellPixels(strip.getFrames().getFirst()), "frame 0 draws none of the pass's colour");
        assertTrue(shellPixels(strip.getFrames().getLast()) > 0, "frame 10 draws some of it");
        ImageFrame first = strip.getFrames().getFirst();
        assertTrue(first.pixels().width() * first.pixels().height() > (int) alone.get(0) * (int) alone.get(1),
            "and the canvas grows to hold it through the frame that draws it");
    }

    // ------------------------------------------------------------------------------------

    /**
     * The fixture subject - a body cube, and with {@code shelled} the shell pass around it.
     *
     * @param shelled whether the subject carries the shell pass
     * @return the subject
     */
    private static @NotNull Entity subject(boolean shelled) {
        EntityMesh body = mesh("body", -4f, 8f);
        PoseExpr ramp = new PoseExpr.Op(PoseOperator.MUL, Concurrent.newUnmodifiableList(
            new PoseExpr.Input("ageInTicks"), new PoseExpr.Constant(0.01f, PoseWidth.FLOAT)));
        Entity.OverlayLayer shell = new Entity.OverlayLayer(mesh("shell", -6f, 12f), Optional.of(SHELL),
            new PassDeclaration(true, BlendMode.REPLACE, PassDeclaration.drawnAlpha(0f), true, false),
            0xFFFFFFFF, false, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
            EntityPose.NONE, Optional.empty(), Optional.of(ramp));
        return Entity.builder()
            .id(ResourceId.parse(ENTITY))
            .model(body)
            .baseTintArgb(0xFFFFFFFF)
            .rendererScale(1f)
            .overlays(shelled ? Concurrent.newUnmodifiableList(shell) : Concurrent.newUnmodifiableList())
            .axes(new Entity.Axes(Optional.empty(), Entity.Variation.none(),
                new Entity.Variation<>(Concurrent.newUnmodifiableMap(Map.of(Entity.BASE_STATE, BODY)),
                    Optional.of(Entity.BASE_STATE)),
                Entity.Variation.none(), Entity.Variation.none(), Optional.empty(), Entity.Variation.none()))
            .build();
    }

    /** A mesh of one bone holding one cube centred on the bone, its near corner at {@code min}. */
    private static @NotNull EntityMesh mesh(@NotNull String bone, float min, float size) {
        ConcurrentList<EntityMesh.Cube> cubes = Concurrent.newList();
        cubes.add(new EntityMesh.Cube(new Vector3f(min, min, min), new Vector3f(size, size, size),
            Vector2f.ZERO, Vector3f.ZERO, false, Vector3f.ZERO, EulerRotation.NONE, Concurrent.newMap()));
        EntityMesh mesh = new EntityMesh();
        mesh.getBones().put(bone, new EntityMesh.Bone(Vector3f.ZERO, EulerRotation.NONE, EulerRotation.NONE, 1f,
            cubes, null));
        return mesh;
    }

    /**
     * Renders the subject posed under its standing row over a strip of the given length, three ticks a
     * frame, on a canvas sized from what the strip measures rather than one the subject is scaled into.
     */
    private static @NotNull ImageData render(@NotNull Entity subject, int frames) {
        RendererContext context = RendererContext.builder()
            .entities(Map.of(ENTITY, subject))
            .textures(TEXTURES)
            .build();
        return new EntityRenderer(context).render(EntityOptions.builder()
            .entityId(ENTITY)
            .style(PoseStyle.IDLE)
            .fitMode(EntityOptions.FitMode.UNION_BOUNDS)
            .pixelsPerBlock(32)
            .animation(AnimationOptions.builder().frameCount(frames).ticksPerFrame(3).build())
            .build()).image();
    }

    /** A frame spelled as its width, its height and its pixels, so two frames compare by all three. */
    private static @NotNull List<Object> frame(@NotNull ImageFrame frame) {
        return List.of(frame.pixels().width(), frame.pixels().height(),
            RenderDigest.crc32(frame.pixels().data()));
    }

    /** How many of a frame's pixels carry the shell's colour, at whatever alpha. */
    private static int shellPixels(@NotNull ImageFrame frame) {
        return (int) Arrays.stream(frame.pixels().data())
            .filter(argb -> (argb & 0xFFFFFF) == SHELL_RGB)
            .count();
    }

    /** A 64 by 64 texture of one colour. */
    private static @NotNull PixelBuffer solid(int argb) {
        int[] pixels = new int[64 * 64];
        Arrays.fill(pixels, argb);
        return PixelBuffer.ofStraight(pixels, 64, 64);
    }

}
