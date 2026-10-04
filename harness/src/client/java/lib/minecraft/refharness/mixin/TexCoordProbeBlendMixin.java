package lib.minecraft.refharness.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Optional;

/**
 * The texture-coordinate probe's blend switch - every pipeline draws with blending off while the
 * probe is armed, so a pixel holds exactly the bytes of the last fragment written to it.
 *
 * <h2>Why this exists</h2>
 * The probe writes a coordinate's bits as a colour, and a blend combines them with what is already
 * there. The energy swirl on a charged creeper or wither blends additively and would add its bits to
 * the body's, and with the coordinate's top byte in alpha every translucent pass would mix as well.
 *
 * <h2>How</h2>
 * {@code GlCommandEncoder.applyPipelineState} is the only place the client enables blending: it
 * enables it when the pipeline's colour target names a blend function and disables it otherwise.
 * Answering that no function is named sends every pipeline down the disabling branch; the write
 * mask, depth state, cull and polygon mode are still applied from the pipeline. The method skips a
 * pipeline it has just applied, which leaves no stale state here because nothing else enables
 * blending.
 *
 * <h2>When to remove this mixin</h2>
 * With {@link TexCoordProbeMixin}. It is inert unless {@code refharness.texCoordProbe} is set.
 */
@Mixin(targets = "com.mojang.blaze3d.opengl.GlCommandEncoder")
public abstract class TexCoordProbeBlendMixin {

    @Redirect(
        method = "applyPipelineState(Lcom/mojang/blaze3d/pipeline/RenderPipeline;)V",
        at = @At(value = "INVOKE", target = "Ljava/util/Optional;isPresent()Z"))
    private boolean refharness$blendOffUnderProbe(Optional<?> blendFunction) {
        if (Boolean.getBoolean("refharness.headless") && System.getProperty("refharness.texCoordProbe") != null)
            return false;

        return blendFunction.isPresent();
    }
}
