package lib.minecraft.refharness;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.resources.Identifier;
import org.joml.Quaternionf;
import org.joml.Vector3fc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Renders the vanilla {@link PlayerModel} (default steve skin) to an offscreen PNG under the same
 * {@link Lighting.Entry#ENTITY_IN_UI ENTITY_IN_UI} lighting vanilla uses for its inventory
 * player-model preview - the ground truth the sibling asset-renderer's {@code PlayerRenderer} 3D
 * output is diffed against.
 *
 * <p>Unlike {@link EntityFrameRenderer} (which drives a real {@code Entity} through the
 * {@code EntityRenderDispatcher}), a player is not a spawnable entity, so this bakes the
 * {@link ModelLayers#PLAYER} layer and submits the model directly via
 * {@link SubmitNodeCollector#submitModel}. The pose chain replicates the entity harness's iso
 * presentation - {@code scale(1,1,-1)} chirality, {@link #ISO_ROTATION}, then the
 * {@code LivingEntityRenderer.submit} humanoid chain ({@code R_Y(180)}, {@code scale(-1,-1,1)},
 * {@code translate(0,-1.501,0)}) - so the Y-down vanilla model lands upright, front-facing, at the
 * shared iso pose, matching asset-renderer's presented player pose ({@code [30,45,0]}).
 *
 * <p>The silhouette is measured through {@link ModelPart#getExtentsForGui} and scaled to
 * {@link #FILL} of the canvas, mirroring asset-renderer's {@code PLAYER_FILL} auto-fit. Under
 * {@code refharness.headless} the {@code SkipSetupAnimMixin} suppresses the model's {@code setupAnim},
 * so the authored bind pose is rendered (consistent with every other harness subject).
 */
public final class PlayerFrameRenderer implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger("refharness");

    /** Packed light value for "fully lit" (skylight 15 &lt;&lt; 20 | blocklight 15 &lt;&lt; 4). */
    private static final int FULL_BRIGHT_LIGHT = 15728880;

    /** Fraction of the canvas the fitted silhouette spans - matches asset-renderer {@code PLAYER_FILL}. */
    private static final float FILL = 1.0f;

    /**
     * Iso pose quaternion, shared with {@link EntityFrameRenderer#ISO_ROTATION}: {@code rotationXYZ(210, 45, 0)}
     * which, composed with the humanoid {@code scale(-1,-1,1)} chirality below, presents a Y-down vanilla
     * model upright + front-facing at the shared iso angle asset-renderer renders its player at.
     */
    static final Quaternionf ISO_ROTATION = new Quaternionf().rotationXYZ(
        (float) Math.toRadians(210.0),
        (float) Math.toRadians(45.0),
        0f);

    /** The two body scopes with a vanilla ground truth: full body and head-only. */
    public enum Scope { FULL, SKULL }

    private final Projection projection = new Projection();
    private final ProjectionMatrixBuffer projectionMatrixBuffer = new ProjectionMatrixBuffer("refharness player PIP");

    private PlayerModel playerModel;
    private RenderType renderType;
    private AvatarRenderState renderState;

    private GpuTexture colorTexture;
    private GpuTextureView colorTextureView;
    private GpuTexture depthTexture;
    private GpuTextureView depthTextureView;
    private int textureWidth;
    private int textureHeight;

    /**
     * Renders the player at the given {@code scope} into the internal texture and writes it to
     * {@code outputPath} as a {@code size × size} PNG.
     */
    public void renderAndWrite(Minecraft client, Scope scope, int size, Path outputPath) throws IOException {
        ensureModel(client);
        ensureTextures(size, size);

        // Measure the silhouette in the presentation frame (no fit yet), then scale to fill the canvas.
        PoseStack measure = new PoseStack();
        appendPresentationChain(measure);
        Bounds bounds = new Bounds();
        walkExtents(scope, measure, v -> bounds.expand(v.x(), v.y()));
        float w = bounds.width();
        float h = bounds.height();
        float scale = (w <= 0f || h <= 0f) ? size : FILL * Math.min(size / w, size / h);
        float translateX = size / 2.0f - bounds.centerX() * scale;
        float translateY = size / 2.0f - bounds.centerY() * scale;

        FeatureRenderDispatcher fed = client.gameRenderer.getFeatureRenderDispatcher();
        SubmitNodeStorage storage = fed.getSubmitNodeStorage();
        MultiBufferSource.BufferSource bufferSource = client.renderBuffers().bufferSource();
        Lighting lighting = client.gameRenderer.getLighting();

        GpuDevice device = RenderSystem.getDevice();
        device.createCommandEncoder().clearColorAndDepthTextures(colorTexture, 0, depthTexture, 1.0);

        RenderSystem.outputColorTextureOverride = colorTextureView;
        RenderSystem.outputDepthTextureOverride = depthTextureView;
        try {
            projection.setupOrtho(-10000.0f, 10000.0f, textureWidth, textureHeight, /*invertY*/ true);
            RenderSystem.setProjectionMatrix(projectionMatrixBuffer.getBuffer(projection), ProjectionType.ORTHOGRAPHIC);

            lighting.setupFor(Lighting.Entry.ENTITY_IN_UI);

            PoseStack poseStack = new PoseStack();
            poseStack.translate(translateX, translateY, 0.0f);
            poseStack.scale(scale, scale, scale);
            appendPresentationChain(poseStack);

            submit(scope, poseStack, storage);
            fed.renderAllFeatures();
            bufferSource.endBatch();
        } finally {
            RenderSystem.outputColorTextureOverride = null;
            RenderSystem.outputDepthTextureOverride = null;
        }

        writeTextureToPng(outputPath);
    }

    /**
     * Appends the iso presentation transform: PIP chirality compensation, the iso rotation, then the
     * vanilla {@code LivingEntityRenderer.submit} humanoid chain that lifts a Y-down model upright and
     * turns its front to the camera. Mirrors {@link EntityFrameRenderer}'s render chain for a humanoid
     * at zero body-rotation and unit scale.
     */
    private static void appendPresentationChain(PoseStack ps) {
        ps.scale(1.0f, 1.0f, -1.0f);
        ps.mulPose(ISO_ROTATION);
        ps.mulPose(Axis.YP.rotationDegrees(180.0f)); // setupRotations: face the camera
        ps.scale(-1.0f, -1.0f, 1.0f);                // humanoid chirality (Y-down -> Y-up)
        ps.translate(0.0f, -1.501f, 0.0f);           // model offset (absorbed by the bbox re-centre)
    }

    private void walkExtents(Scope scope, PoseStack ps, Consumer<Vector3fc> out) {
        if (scope == Scope.FULL) {
            playerModel.root().getExtentsForGui(ps, out);
        } else {
            playerModel.head.getExtentsForGui(ps, out);
            playerModel.hat.getExtentsForGui(ps, out);
        }
    }

    private void submit(Scope scope, PoseStack ps, SubmitNodeCollector storage) {
        if (scope == Scope.FULL) {
            storage.submitModel(playerModel, renderState, ps, renderType,
                FULL_BRIGHT_LIGHT, OverlayTexture.NO_OVERLAY, 0xFFFFFFFF, /*crumbling*/ null);
        } else {
            storage.submitModelPart(playerModel.head, ps, renderType,
                FULL_BRIGHT_LIGHT, OverlayTexture.NO_OVERLAY, /*sprite*/ null);
            storage.submitModelPart(playerModel.hat, ps, renderType,
                FULL_BRIGHT_LIGHT, OverlayTexture.NO_OVERLAY, /*sprite*/ null);
        }
    }

    private void ensureModel(Minecraft client) {
        if (playerModel != null) return;
        playerModel = new PlayerModel(client.getEntityModels().bakeLayer(ModelLayers.PLAYER), /*slim*/ false);
        Identifier skin = DefaultPlayerSkin.getDefaultTexture();
        renderType = playerModel.renderType(skin);
        renderState = new AvatarRenderState();
        renderState.skin = DefaultPlayerSkin.getDefaultSkin();
        renderState.showHat = true;
        renderState.showJacket = true;
    }

    /** Mutable screen-space bounding box accumulated over transformed model-part corners. */
    private static final class Bounds {
        private float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        private float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;

        void expand(float x, float y) {
            minX = Math.min(minX, x); maxX = Math.max(maxX, x);
            minY = Math.min(minY, y); maxY = Math.max(maxY, y);
        }

        float width() { return maxX - minX; }

        float height() { return maxY - minY; }

        float centerX() { return (minX + maxX) * 0.5f; }

        float centerY() { return (minY + maxY) * 0.5f; }
    }

    private void ensureTextures(int width, int height) {
        if (colorTexture != null && textureWidth == width && textureHeight == height) return;
        closeTextures();
        GpuDevice device = RenderSystem.getDevice();
        colorTexture = device.createTexture(() -> "refharness player color", 13,
            TextureFormat.RGBA8, width, height, 1, 1);
        colorTextureView = device.createTextureView(colorTexture);
        depthTexture = device.createTexture(() -> "refharness player depth", 9,
            TextureFormat.DEPTH32, width, height, 1, 1);
        depthTextureView = device.createTextureView(depthTexture);
        textureWidth = width;
        textureHeight = height;
    }

    private void writeTextureToPng(Path outputPath) throws IOException {
        Files.createDirectories(outputPath.getParent());
        final int width = textureWidth;
        final int height = textureHeight;
        final int pixelSize = colorTexture.getFormat().pixelSize();
        long byteSize = (long) width * height * pixelSize;
        GpuBuffer buffer = RenderSystem.getDevice().createBuffer(
            () -> "refharness-player-readback", /*usage*/ 9, byteSize);
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        IOException[] err = new IOException[1];
        encoder.copyTextureToBuffer(colorTexture, buffer, 0L, () -> {
            try (GpuBuffer.MappedView read = encoder.mapBuffer(buffer, true, false);
                 NativeImage image = new NativeImage(width, height, false)) {
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        int argb = read.data().getInt((x + y * width) * pixelSize);
                        image.setPixelABGR(x, height - y - 1, argb);
                    }
                }
                image.writeToFile(outputPath);
            } catch (IOException ex) {
                err[0] = ex;
            } finally {
                buffer.close();
            }
        }, /*mipLevel*/ 0);
        if (err[0] != null) throw err[0];
    }

    private void closeTextures() {
        if (colorTexture != null) { colorTexture.close(); colorTexture = null; }
        if (colorTextureView != null) { colorTextureView.close(); colorTextureView = null; }
        if (depthTexture != null) { depthTexture.close(); depthTexture = null; }
        if (depthTextureView != null) { depthTextureView.close(); depthTextureView = null; }
        textureWidth = 0;
        textureHeight = 0;
    }

    @Override
    public void close() {
        closeTextures();
        projectionMatrixBuffer.close();
    }
}
