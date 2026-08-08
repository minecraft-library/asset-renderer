package lib.minecraft.refharness.mixin;

import net.minecraft.client.renderer.blockentity.EnchantTableRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Suppresses the enchanting-table's floating book so the harness reference matches asset-renderer's
 * icon-faithful render. Cancels {@link EnchantTableRenderer#submit submit}, whose sole output is the
 * animated {@code BookModel}.
 *
 * <h2>Why this exists</h2>
 * enchanting_table renders in two parts: the 16x12x16 table base comes from the block model
 * ({@code block/enchanting_table.json}, drawn by the harness's plain block-model submit), and the
 * floating open book is a block-entity render only ({@code EnchantTableRenderer} submits a
 * {@code BookModel}). The book never runs for the inventory item icon, so vanilla's own
 * enchanting_table INVENTORY icon is the base alone. asset-renderer renders the block as its GUI
 * icon (the block model base, no book), which is icon-faithful; the in-world harness capture added
 * the extra book, and that book was the entire parity divergence (base, obsidian sides and diamond
 * corners already matched pixel-for-pixel).
 *
 * <p>Cancelling {@code submit} at HEAD drops only the book: the table base is submitted separately
 * from the block model, before the block-entity renderer runs, so it survives untouched. Mirrors the
 * established BE-icon-composition fixes (banner flag-wave suppression via {@link BannerFlagModelMixin}).
 *
 * <h2>When to remove this mixin</h2>
 * <b>If asset-renderer ever renders the enchanting-table book, delete this mixin so the harness
 * reproduces the in-world base + book.</b> Same {@code refharness.headless} gate as the other harness
 * mixins so non-harness consumers of this jar keep vanilla behaviour.
 */
@Mixin(EnchantTableRenderer.class)
public abstract class EnchantTableRendererMixin {

    @Inject(method = "submit(Lnet/minecraft/client/renderer/blockentity/state/EnchantTableRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
        at = @At("HEAD"),
        cancellable = true)
    private void refharness$suppressBook(CallbackInfo ci) {
        if (!Boolean.getBoolean("refharness.headless")) return;
        ci.cancel();
    }
}
