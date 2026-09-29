package lib.minecraft.refharness.mixin;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Renders {@code "shade": false} block-model faces full-bright, matching their in-world
 * appearance, when {@code -Drefharness.headless=true}.
 *
 * <h2>Why this exists</h2>
 * The harness {@code BlockFrameRenderer} lights block models with the GUI
 * {@code Lighting.Entry#ITEMS_3D} two-directional diffuse, applied in the shader to each quad's
 * normal. The submit path ({@code BlockFeatureRenderer.putQuad}) emits each quad via
 * {@code VertexConsumer#putBakedQuad}, which derives that normal from the quad's
 * {@code direction().getUnitVec3f()} (the nominal cardinal face direction). Vanilla's GUI diffuse
 * does NOT honor a quad's {@code shade} flag, so a {@code shade:false} face is darkened by the
 * diffuse just like any other - a vertical plane facing neither light lands at the ambient floor
 * ({@code 0.40}): the ladder, cobweb, all {@code cross}/{@code crop}/vine planes render far darker
 * than they appear in the world.
 *
 * <p>In the actual world render a {@code shade:false} face skips the directional shade entirely
 * ({@code BlockAndTintGetter.getShade(direction, false) == 1.0}); it renders full-bright. That is
 * the correct ground truth for a block-parity comparison, and what asset-renderer's
 * {@code BlockGeometryKit} reproduces (shade:false faces -> shade 1.0). Coral fans only looked
 * right by luck - their {@code up}/{@code down} faces happen to point at a light, so the diffuse
 * already saturated to {@code 1.0}.
 *
 * <h2>What it does</h2>
 * Redirects the {@code BakedQuad.direction()} call inside {@code putBakedQuad} (its only use, to
 * build the lighting normal) to return {@link Direction#UP} for {@code shade:false} quads. An up
 * normal saturates the ITEMS_3D diffuse to {@code 1.0} (verified: a full cube's top face renders at
 * the raw texel), so every shade:false face renders full-bright regardless of its real orientation.
 * shade:true quads keep their real direction and lighting. Gated on {@code refharness.headless}, so
 * non-harness consumers keep vanilla's behaviour.
 */
@Mixin(VertexConsumer.class)
public interface ShadeFalseFullBrightMixin {

    @Redirect(
        method = "putBakedQuad(Lcom/mojang/blaze3d/vertex/PoseStack$Pose;Lnet/minecraft/client/resources/model/geometry/BakedQuad;Lcom/mojang/blaze3d/vertex/QuadInstance;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/resources/model/geometry/BakedQuad;direction()Lnet/minecraft/core/Direction;")
    )
    private Direction refharness$shadeFalseUpNormal(BakedQuad quad) {
        if (Boolean.getBoolean("refharness.headless") && !quad.materialInfo().shade())
            return Direction.UP;
        return quad.direction();
    }
}
