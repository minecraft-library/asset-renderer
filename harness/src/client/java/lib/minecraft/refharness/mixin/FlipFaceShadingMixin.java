package lib.minecraft.refharness.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.CardinalLighting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Swaps the N/S and W/E entries of {@link CardinalLighting#DEFAULT} when
 * {@code -Drefharness.headless=true}, so the in-world block render produced by the
 * harness matches the inventory-style shading that {@code asset-renderer}'s
 * {@code BlockFace.lighting} reproduces for ground truth.
 *
 * <p>Vanilla 26.1's {@link CardinalLighting#DEFAULT} for the overworld is
 * {@code (down=0.5, up=1.0, N=S=0.8, W=E=0.6)} - that's the world-rendering shade where
 * N/S faces are brighter than E/W faces. Vanilla's inventory pipeline ({@code Lighting.ITEMS_3D})
 * produces the opposite axis brightness because it uses two directional lights offset in X,
 * which after the standard {@code [30, 225, 0]} GUI rotation makes E/W (the model's
 * left/right) brighter than N/S (the model's front/back). asset-renderer's {@code BlockFace}
 * reproduces that inventory output: {@code N=S=0.6, W=E=0.8}. Swapping the level's
 * {@code cardinalLighting()} return value gives the harness output the same axis brightness
 * as asset-renderer, so the per-face shade matches in A/B comparison.
 *
 * <p>Affects every renderer that reads from {@code level.cardinalLighting()}:
 * {@code BlockModelLighter} (chunk-mesh block models), {@code FluidRenderer},
 * {@code PistonHeadRenderer}, {@code RenderSectionRegion}. Block-entity renderers (chest,
 * sign, banner) compute their own lighting from packed light + model normals; if their
 * front-vs-side shading still doesn't match after this mixin, BER lighting is a separate
 * follow-up.
 */
@Mixin(ClientLevel.class)
public abstract class FlipFaceShadingMixin {

    private static final CardinalLighting REFHARNESS_FLIPPED = new CardinalLighting(
        0.5f, 1.0f,
        0.6f, 0.6f,
        0.8f, 0.8f);

    @Inject(method = "cardinalLighting", at = @At("HEAD"), cancellable = true)
    private void refharness$flipShade(CallbackInfoReturnable<CardinalLighting> cir) {
        if (Boolean.getBoolean("refharness.headless")) {
            cir.setReturnValue(REFHARNESS_FLIPPED);
        }
    }
}
