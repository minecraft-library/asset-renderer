package lib.minecraft.refharness.mixin;

import net.minecraft.client.model.animal.llama.LlamaModel;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hides the {@code right_chest} / {@code left_chest} {@link ModelPart} children on every
 * {@link LlamaModel} the harness bakes, gated on {@code refharness.headless}.
 *
 * <h2>Why this exists</h2>
 * {@link LlamaModel#createBodyLayer createBodyLayer} unconditionally adds {@code right_chest}
 * and {@code left_chest} bones (8x8x3 cubes hanging off the body) to the llama mesh. The bones
 * are equipment-driven: {@code LlamaModel.setupAnim} sets
 * {@code rightChest.visible = leftChest.visible = state.hasChest}, and
 * {@link net.minecraft.client.renderer.entity.LlamaRenderer#extractRenderState extractRenderState}
 * populates {@code state.hasChest = !entity.isBaby() && entity.hasChest()}.
 *
 * <p>For a transient zero-state llama spawned via {@code EntityType.create} (which the harness
 * does), {@code entity.hasChest()} returns {@code false} so {@code state.hasChest} is
 * {@code false}, and a normally-rendered vanilla llama would hide both chest bones. But
 * {@link SkipSetupAnimMixin} suppresses the entire {@code setupAnim} call in the harness's
 * render path so the bind pose is what gets rasterised. With {@code setupAnim} suppressed, the
 * {@code visible} field never updates from its {@code true} construction default, and the
 * harness ships every llama / trader_llama / variant with a pair of brown chests strapped to
 * its sides as if it were a {@code hasChest=true} adult.
 *
 * <p>Fix: clear {@code rightChest.visible} / {@code leftChest.visible} at constructor RETURN.
 * Llamas with an actually-equipped chest item are not a thing in the harness (we never give
 * the entity any equipment), so unconditionally hiding both chests at zero state matches
 * what a freshly-spawned llama looks like before any chest is placed on it. Vanilla's
 * {@link net.minecraft.client.renderer.entity.layers.LlamaDecorLayer LlamaDecorLayer}
 * separately renders the {@code TRADER_LLAMA} carpet on top of the inflated decor model when
 * {@code state.isTraderLlama} is true; that path is untouched here.
 *
 * <h2>When to remove this mixin</h2>
 * <b>Once {@link SkipSetupAnimMixin} no longer fires (i.e. when asset-renderer gains
 * animation support and the harness goes back to running setupAnim on every frame), delete
 * this mixin so vanilla's own visibility toggle takes over again.</b>
 *
 * <p>Same {@code refharness.headless} gate as the other harness mixins so non-harness
 * consumers of this jar keep vanilla llama-with-chest behaviour intact.
 */
@Mixin(LlamaModel.class)
public abstract class LlamaModelMixin {

    @Inject(method = "<init>(Lnet/minecraft/client/model/geom/ModelPart;)V", at = @At("RETURN"))
    private void refharness$hideEquipmentChestByDefault(ModelPart root, CallbackInfo ci) {
        if (!Boolean.getBoolean("refharness.headless")) return;
        root.getChild("right_chest").visible = false;
        root.getChild("left_chest").visible = false;
    }
}
