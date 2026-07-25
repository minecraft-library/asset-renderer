package lib.minecraft.refharness.frame;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import lib.minecraft.refharness.api.Canvas;
import lib.minecraft.refharness.pip.PipScope;
import lib.minecraft.refharness.pip.PipTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.DefaultPlayerSkin;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Measures how finely the GPU can tell two depths apart, by rendering two overlapping quads a swept
 * distance apart and reading off which one the depth test kept.
 *
 * <p>Every reference-side depth number rests on an unverified model of the hardware: that clip
 * {@code z} is a {@code float32}, that the viewport transform {@code 0.5z + 0.5} is evaluated in
 * {@code float32}, and that the result is then converted to the depth format. Under that model the
 * binding quantum is a {@code float32} ULP beside {@code 0.5}, {@code 2^-24}. Two departures are
 * plausible and they disagree with it by two orders of magnitude, so the question is worth an
 * experiment rather than an argument. This is that experiment, and it needs no model of the hardware
 * at all - only the separation at which the second quad stops winning.
 *
 * <h2>What one frame is</h2>
 *
 * A red quad covering the left of the frame is drawn first, then a green quad covering the right,
 * offset {@code 2^-k} of the full window-depth range <em>further from the camera</em>. They overlap
 * across the middle third, and that band is the whole measurement:
 *
 * <ul>
 *   <li><b>green</b> - the two depths rounded to the same value, so the later draw won on
 *       {@code GL_LEQUAL}. The separation is below the quantum.</li>
 *   <li><b>red</b> - the green quad was rejected as further away. The separation is at or above the
 *       quantum.</li>
 * </ul>
 *
 * The outer thirds always show one quad each, whatever the depth test did, so a frame that fails to
 * draw is distinguishable from one whose contest went either way.
 *
 * <h2>Why the separation is a power of two of the window range</h2>
 *
 * The orthographic set-up maps eye {@code z} in {@code [-range, +range]} onto window depth
 * {@code [0, 1]}, so an eye-space step of {@code 2 * range * 2^-k} is a window-depth step of exactly
 * {@code 2^-k}. Naming frames by {@code k} therefore names the answer directly, and it is
 * comparable across depth ranges without converting anything:
 *
 * <ul>
 *   <li>{@code k} around <b>24</b> - the assumed model. The viewport transform is evaluated in
 *       {@code float32} and a ULP beside {@code 0.5} is what binds.</li>
 *   <li>{@code k} around <b>32</b> - the whole chain is evaluated above {@code float32} and only the
 *       {@code DEPTH32} store rounds. The reference resolves 256x finer than assumed.</li>
 *   <li><b>no transition at all</b> at {@link Cell#baseDepth} zero - the viewport transform is
 *       evaluated above {@code float32} but clip {@code z} is still a {@code float32}. There the
 *       quantum scales with {@code |z_ndc|}, which is zero at the middle of the range, so a pair
 *       there could never be separated however far apart it is put.</li>
 * </ul>
 *
 * <h2>What the two sweep axes are for</h2>
 *
 * The {@link Cell#baseDepth} axis is the discriminator: it moves the pair off the middle of the
 * range, where the third model predicts a quantum and at the centre predicts none. The
 * {@link Cell#depthRange} axis is the control: in window units all three models are
 * range-independent, so a transition that moves with the range falsifies all three at once.
 *
 * <p><b>The offset cells have a floor of their own, and it is not the GPU's.</b> A vertex position
 * is a {@code float32}, so a pair sitting at eye depth {@code 0.1 * range} cannot express a
 * separation below that magnitude's own ULP - about {@code 2^-28} of the window range. Read those
 * cells as bounded below rather than as a measurement, and take the fine end of the answer from the
 * centre cells, whose separation is representable all the way down.
 */
public final class DepthQuantumFrameRenderer implements AutoCloseable {

    /**
     * The render type both quads are submitted through - the plain entity cutout, which is
     * less-or-equal with the depth write on and no face culling, so the contest is decided by depth
     * alone and neither quad can be dropped for facing the wrong way. Every entity reference's
     * surfaces land in a pipeline built from the same snippet.
     */
    private static final RenderType QUAD_TYPE = RenderTypes.entityCutout(DefaultPlayerSkin.getDefaultTexture());

    /**
     * The texel both quads sample - one point on the default skin's face, which is opaque and bright
     * in all three channels. A single point rather than a mapped rectangle, so each quad is flat and
     * the winner is read off the hue rather than off a texture pattern.
     */
    private static final float SAMPLE_U = 10.5f / 64f;

    /** The V half of {@link #SAMPLE_U}'s texel. */
    private static final float SAMPLE_V = 10.5f / 64f;

    /** Colour of the first-drawn quad. Its red channel survives the texture; its green does not. */
    private static final int FIRST_COLOR = 0xFFFF0000;

    /** Colour of the second-drawn quad - the one whose survival is the measurement. */
    private static final int SECOND_COLOR = 0xFF00FF00;

    /** One {@link PipTarget} per depth range, since the range is fixed at construction. */
    private final Map<Float, PipTarget> targets = new LinkedHashMap<>();

    /**
     * One probe frame.
     *
     * @param depthRange the orthographic half-extent the frame is projected through, matching the
     *     {@code DEPTH_RANGE} a reference renderer would use
     * @param baseDepth the eye-space depth the first quad sits at, as a fraction of
     *     {@code depthRange} - zero is the middle of the range, where window depth is {@code 0.5}
     * @param exponent the {@code k} of the {@code 2^-k} window-depth separation between the two
     *     quads
     */
    public record Cell(float depthRange, float baseDepth, int exponent) {

        /** The eye-space depth the first quad is drawn at. */
        float firstZ() {
            return this.baseDepth * this.depthRange;
        }

        /**
         * The eye-space depth the second quad is drawn at - further from the camera by exactly
         * {@code 2^-exponent} of the window range. Further is <em>lower</em> eye {@code z}: the
         * projection maps eye {@code z} to {@code 0.5 - z / (2 * range)}, so a larger window depth
         * is a smaller eye one.
         *
         * @return the second quad's eye-space depth
         */
        float secondZ() {
            return firstZ() - 2f * this.depthRange * (float) Math.pow(2.0, -this.exponent);
        }
    }

    /**
     * Renders one probe frame.
     *
     * @param client the running client
     * @param cell the frame to render
     * @param canvas the canvas to render onto
     * @param out the PNG path to write
     * @return whether a PNG was written
     * @throws IOException if the PNG write fails
     */
    public boolean render(Minecraft client, Cell cell, Canvas canvas, Path out) throws IOException {
        PipTarget pip = this.targets.computeIfAbsent(cell.depthRange(),
            range -> new PipTarget("depth-quantum-" + range.intValue(), range));
        return pip.draw(client, canvas, out, scope -> {
            scope.lighting().setupFor(Lighting.Entry.ENTITY_IN_UI);
            // Identity: the orthographic box is already in canvas pixels for x and y and in eye
            // units for z, so vertices are written in the frame the measurement is expressed in and
            // nothing scales the separation on its way to the depth buffer.
            PoseStack poseStack = new PoseStack();
            float top = scope.height() * 0.25f;
            float bottom = scope.height() * 0.75f;
            scope.storage().submitCustomGeometry(poseStack, QUAD_TYPE, (pose, buffer) -> {
                quad(buffer, pose, scope.width() * 0.05f, scope.width() * 0.70f, top, bottom,
                    cell.firstZ(), FIRST_COLOR);
                quad(buffer, pose, scope.width() * 0.30f, scope.width() * 0.95f, top, bottom,
                    cell.secondZ(), SECOND_COLOR);
            });
            return true;
        });
    }

    /** Emits one flat, camera-facing quad at a fixed depth. */
    private static void quad(com.mojang.blaze3d.vertex.VertexConsumer buffer, PoseStack.Pose pose,
                             float left, float right, float top, float bottom, float z, int color) {
        vertex(buffer, pose, left, bottom, z, color);
        vertex(buffer, pose, right, bottom, z, color);
        vertex(buffer, pose, right, top, z, color);
        vertex(buffer, pose, left, top, z, color);
    }

    /** Emits one vertex in the entity vertex format. */
    private static void vertex(com.mojang.blaze3d.vertex.VertexConsumer buffer, PoseStack.Pose pose,
                               float x, float y, float z, int color) {
        buffer.addVertex(pose, x, y, z)
            .setColor(color)
            .setUv(SAMPLE_U, SAMPLE_V)
            .setOverlay(OverlayTexture.NO_OVERLAY)
            .setLight(PipScope.FULL_BRIGHT_LIGHT)
            .setNormal(0f, 0f, 1f);
    }

    @Override
    public void close() {
        for (PipTarget target : this.targets.values()) target.close();
        this.targets.clear();
    }
}
