package lib.minecraft.refharness.mixin;

import net.minecraft.client.model.animal.turtle.AdultTurtleModel;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hides the pregnancy egg-belly bulge on every harness-rendered turtle by forcing the
 * {@link AdultTurtleModel} {@code eggBelly} part invisible at model construction, gated on
 * {@code refharness.headless}.
 *
 * <h2>Why this exists</h2>
 * The asset-renderer's v2 turtle model toggles the {@code egg_belly} bone off by default (the
 * {@code egg} toggle defaults false), so its render is the bare turtle without the swollen belly.
 * A default turtle carries no egg ({@code hasEgg = false}), so vanilla's default render hides the
 * bulge too - but the harness leaks it in as extra silhouette that diverges from the Java render.
 *
 * <p>{@link AdultTurtleModel#setupAnim setupAnim} normally sets
 * {@code eggBelly.visible = state.hasEgg}, but {@link SkipSetupAnimMixin} cancels {@code setupAnim}
 * (the broad bind-pose freeze), so the egg belly keeps its constructed default of visible. Pin the
 * part's {@code visible} flag directly at {@code <init>} RETURN instead - it survives the
 * {@code setupAnim} skip and is measured by {@code EntityFrameRenderer.walkVisibleExtents} (so both
 * the family-fit canvas and the render exclude the bulge).
 *
 * <h2>When to remove this mixin</h2>
 * <b>Delete when the asset-renderer renders the turtle egg belly</b> - at that point the pregnant
 * ({@code hasEgg = true}) state becomes the matching ground truth again.
 */
@Mixin(AdultTurtleModel.class)
public abstract class TurtleEggBellyMixin {

    @Shadow @Final private ModelPart eggBelly;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void refharness$hideEggBelly(ModelPart root, CallbackInfo ci) {
        if (!Boolean.getBoolean("refharness.headless")) return;
        this.eggBelly.visible = false;
    }
}
