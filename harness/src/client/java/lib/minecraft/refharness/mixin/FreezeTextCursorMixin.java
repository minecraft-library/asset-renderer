package lib.minecraft.refharness.mixin;

import net.minecraft.client.gui.components.TextCursorUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Pins a focused text field's caret visible so a screen carrying one renders deterministically.
 *
 * <h2>Why this exists</h2>
 * Vanilla {@code TextCursorUtils.isCursorVisible(long)} is {@code (sinceFocus / 300) % 2 == 0}, and
 * the argument reaching it is {@code Util.getMillis() - focusedTime} - real wall-clock time. The
 * anvil focuses its name field during {@code init} and the capture draws immediately after, so the
 * caret is visible on every ordinary run; a stall of three hundred milliseconds anywhere between
 * those two points would blink it out and move the reference by the ten pixels the caret paints.
 * That is the same class of hazard {@code FreezeSpriteAnimationMixin} removes from texture
 * animation, and it is pinned the same way rather than left to timing.
 *
 * <p>Visible is the pin rather than hidden, because a freeze here is meant to hold what the client
 * draws still, not to decide what it draws. A field the player is typing into shows a caret, so that
 * is the state the ground truth carries and the state asset-renderer answers for.
 *
 * <p>Gated on {@code refharness.headless}; outside the harness the blink is untouched.
 */
@Mixin(TextCursorUtils.class)
public abstract class FreezeTextCursorMixin {

    @Inject(method = "isCursorVisible(J)Z", at = @At("HEAD"), cancellable = true)
    private static void refharness$pinCursorVisible(long sinceFocus, CallbackInfoReturnable<Boolean> cir) {
        if (!Boolean.getBoolean("refharness.headless")) return;
        cir.setReturnValue(true);
    }
}
