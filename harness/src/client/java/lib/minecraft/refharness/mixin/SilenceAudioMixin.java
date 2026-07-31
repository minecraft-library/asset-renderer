package lib.minecraft.refharness.mixin;

import net.minecraft.client.Options;
import net.minecraft.sounds.SoundSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Forces every sound category to zero volume.
 *
 * <p>A headless sweep has no listener, so its audio is pure noise on whatever machine is driving it -
 * and the sweep opens with the title screen, whose music starts before any harness code could turn it
 * down. Overriding the volume the sound engine actually reads closes that window entirely: there is no
 * instant at which a sound plays audibly, whatever the run directory's saved options happen to say.
 */
@Mixin(Options.class)
public abstract class SilenceAudioMixin {

    /**
     * Returns silence for every sound source.
     *
     * @param source the category being queried
     * @param cir the returnable callback carrying the volume
     */
    @Inject(method = "getFinalSoundSourceVolume", at = @At("HEAD"), cancellable = true)
    private void refharness$silence(SoundSource source, CallbackInfoReturnable<Float> cir) {
        if (!Boolean.getBoolean("refharness.headless")) return;
        cir.setReturnValue(0.0f);
    }
}
