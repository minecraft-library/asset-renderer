package lib.minecraft.refharness.mixin;

import com.mojang.blaze3d.platform.Window;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hides the Minecraft window when {@code -Drefharness.headless=true} so render runs
 * don't flash a window onto the user's desktop. Injected at {@code Window.<init>} HEAD,
 * which runs <em>before</em> {@code glfwCreateWindow}; the visibility hint is global state
 * picked up by the next window creation. The OpenGL context is still real, so all
 * vanilla rendering APIs continue to work.
 *
 * <p>Note on cross-platform: this works natively on Windows. On a true headless Linux
 * box (no X / Wayland session) this is not enough; you'd also need Mesa software
 * rendering or Xvfb. That's out of scope per the plan.
 */
@Mixin(Window.class)
public abstract class HeadlessWindowMixin {

    @Inject(method = "<init>", at = @At("HEAD"))
    private static void refharness$hideIfHeadless(CallbackInfo ci) {
        // Must be static: @At("HEAD") on a constructor injects before super(), where
        // `this` is not yet initialized.
        if (Boolean.getBoolean("refharness.headless")) {
            GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        }
    }
}
