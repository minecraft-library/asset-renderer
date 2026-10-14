package lib.minecraft.refharness.mixin;

import lib.minecraft.refharness.HarnessConfig;
import net.minecraft.client.Options;
import net.minecraft.server.packs.repository.PackRepository;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Selects the resource packs {@link HarnessConfig#PACKS} names on top of the client's own selection,
 * once the client has applied the one {@code options.txt} holds.
 *
 * <p>The client constructor discovers the available packs, applies this selection, and only then runs
 * the first resource load, so the packs are in force from the first resource read on - no reload, and
 * no window in which the client holds vanilla alone.
 *
 * <p><b>The repository is the only thing changed.</b> {@code Options.resourcePacks} is what
 * {@code options.txt} is written from, and it is never touched, so the run directory every reference
 * run shares keeps no record of a pack - a selection persisted there would load into the next vanilla
 * render. A later reload rebuilds from the repository's selection, so one selection here holds for the
 * whole run.
 *
 * <p>Applied as asked even to a pack whose declared format is not the client's. The selection screen
 * and {@code options.txt}'s load refuse one of those, but the repository itself does not, and what a
 * pack run asks is how the client resolves and draws a pack it has loaded.
 */
@Mixin(Options.class)
public abstract class SelectHarnessPacksMixin {

    /**
     * Appends the requested packs to the selection, in the order given, above everything already
     * selected.
     *
     * @param repository the client's resource-pack repository
     * @param ci the callback
     */
    @Inject(method = "loadSelectedResourcePacks", at = @At("TAIL"))
    private void refharness$selectPacks(PackRepository repository, CallbackInfo ci) {
        if (!Boolean.getBoolean("refharness.headless")) return;
        List<String> requested = HarnessConfig.packIds();
        if (requested.isEmpty()) return;
        List<String> selected = new ArrayList<>(repository.getSelectedIds());
        selected.removeAll(requested);
        selected.addAll(requested);
        repository.setSelected(selected);
    }
}
