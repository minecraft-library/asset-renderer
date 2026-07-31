package lib.minecraft.refharness.mixin;

import net.minecraft.client.model.animal.equine.DonkeyModel;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hides the {@code left_chest} / {@code right_chest} {@link ModelPart} children on every
 * {@link DonkeyModel} the harness bakes (donkey + mule share this model), gated on
 * {@code refharness.headless}.
 *
 * <h2>Why this exists</h2>
 * {@link DonkeyModel#createBodyLayer createBodyLayer} runs {@code DONKEY_TRANSFORMER} which
 * unconditionally adds {@code left_chest} and {@code right_chest} bones (8x8x3 cubes hanging
 * off the body at +/-6 X) to the equine mesh. The bones are equipment-driven:
 * {@code DonkeyModel.setupAnim} sets
 * {@code leftChest.visible = rightChest.visible = state.hasChest}, and
 * {@link net.minecraft.client.renderer.entity.DonkeyRenderer#extractRenderState extractRenderState}
 * populates {@code state.hasChest = entity.hasChest()}.
 *
 * <p>For a transient zero-state donkey or mule spawned via {@code EntityType.create} (which
 * the harness does), {@code entity.hasChest()} returns {@code false} so
 * {@code state.hasChest} is {@code false}, and a normally-rendered vanilla donkey/mule would
 * hide both chest bones. But {@link SkipSetupAnimMixin} suppresses the entire
 * {@code setupAnim} call in the harness's render path so the bind pose is what gets
 * rasterised. With {@code setupAnim} suppressed, the {@code visible} field never updates from
 * its {@code true} construction default, and the harness ships every donkey / mule with a
 * pair of brown saddlebag chests strapped to its sides as if it were a {@code hasChest=true}
 * adult. The asset-renderer parser doesn't see the {@code modifyMesh} chest bones at all so
 * Java renders the bare body - parity then shows ~47 mean delta from the silhouette mismatch.
 *
 * <p>Fix: clear {@code leftChest.visible} / {@code rightChest.visible} at constructor RETURN.
 * Donkeys / mules with an actually-equipped chest are not a thing in the harness (we never
 * give the entity any equipment), so unconditionally hiding both chests at zero state matches
 * what a freshly-spawned chest-less equine looks like before any chest is placed on it. The
 * matched parity case is then "bare donkey / mule body" on both pipelines.
 *
 * <h2>Per-variant scope</h2>
 * {@code DonkeyModel} is shared by both donkey ({@code DONKEY_SCALE = 0.87}) and mule
 * ({@code MULE_SCALE = 0.92}); the {@link net.minecraft.client.renderer.entity.DonkeyRenderer
 * DonkeyRenderer} uses it for both variants via the {@code Type.DONKEY} / {@code Type.MULE}
 * model-layer split. {@code BabyDonkeyModel} is a separate model class for the baby variant -
 * the static harness never renders babies so it's not affected. Same applies to
 * {@code AbstractEquineModel} (used by horse / skeleton_horse / zombie_horse) - those models
 * don't add chest bones at all so they don't need a mixin.
 *
 * <h2>When to remove this mixin</h2>
 * <b>Once {@link SkipSetupAnimMixin} no longer fires (i.e. when asset-renderer gains
 * animation support and the harness goes back to running setupAnim on every frame), delete
 * this mixin so vanilla's own visibility toggle takes over again.</b>
 *
 * <p>Same {@code refharness.headless} gate as the other harness mixins so non-harness
 * consumers of this jar keep vanilla donkey-with-chest behaviour intact.
 *
 * @see LlamaModelMixin same SkipSetupAnimMixin-induced visibility bug, fixed identically
 */
@Mixin(DonkeyModel.class)
public abstract class DonkeyModelMixin {

    @Inject(method = "<init>(Lnet/minecraft/client/model/geom/ModelPart;)V", at = @At("RETURN"))
    private void refharness$hideEquipmentChestByDefault(ModelPart root, CallbackInfo ci) {
        if (!Boolean.getBoolean("refharness.headless")) return;
        // Equivalent to DonkeyModel.setupAnim's "leftChest.visible = rightChest.visible =
        // state.hasChest" call but pinned to false (zero-state donkey has no chest).
        // Reach into the same body-child the model's field initialiser resolved, so the
        // mixin's hidden state matches the leftChest / rightChest references the model
        // itself owns.
        ModelPart body = root.getChild("body");
        body.getChild("left_chest").visible = false;
        body.getChild("right_chest").visible = false;
    }
}
