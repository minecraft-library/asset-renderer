package lib.minecraft.refharness.api;

import com.mojang.blaze3d.platform.Lighting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;

import java.nio.file.Path;

/**
 * The per-tick handles a sweep works from, plus the run-wide output root and target filter.
 *
 * <p>The render handles are accessors that re-fetch rather than fields captured once. Every frame
 * renderer fetches them inside each draw, so caching them on a context that outlives a tick would
 * be a real behaviour change where delegating is an identity.
 *
 * @param client the running client
 * @param outputRoot the reference tree root every sweep writes below
 * @param targets the parsed target allowlist
 */
public record SweepContext(Minecraft client, Path outputRoot, TargetFilter targets) {

    /** The loaded client level, which entity construction needs even though no render reads it. */
    public ClientLevel level() {
        return client.level;
    }

    /** The feature dispatcher a draw is submitted through. */
    public FeatureRenderDispatcher features() {
        return client.gameRenderer.getFeatureRenderDispatcher();
    }

    /** The submit-node storage geometry lands in. */
    public SubmitNodeStorage storage() {
        return features().getSubmitNodeStorage();
    }

    /** The buffer source batched render types are flushed from. */
    public MultiBufferSource.BufferSource buffers() {
        return client.renderBuffers().bufferSource();
    }

    /** The lighting a subject's entry is selected on. */
    public Lighting lighting() {
        return client.gameRenderer.getLighting();
    }
}
