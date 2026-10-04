package lib.minecraft.refharness.mixin;

import com.mojang.blaze3d.shaders.ShaderType;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Replaces the entity fragment shader's output with the raw bits of the interpolated texture
 * coordinate, so a render names the exact value the GPU's interpolation produced at every pixel.
 *
 * <h2>Why this exists</h2>
 * Where a texel boundary passes exactly through a pixel centre, the texel the GPU reads is decided
 * by the last bits of its interpolated coordinate. A colour reference only says which texel won; this
 * pass says what the coordinate was.
 *
 * <h2>What it writes</h2>
 * Armed by {@code refharness.texCoordProbe}, naming the axis - {@code u} or {@code v}. It patches
 * {@code core/entity}, the one fragment program every entity pass compiles - the energy swirl, the
 * breeze's wind, the eyes, translucent and armour passes each by its defines. The vertex shader is
 * untouched, so the coordinate is interpolated exactly as an ordinary render interpolates it, and a
 * pass with a texture matrix reports the transformed coordinate it samples. The fragment shader's
 * own {@code main} is renamed and called first, so every fragment vanilla discards is discarded here
 * too, and its colour is then replaced.
 *
 * <p>All 32 bits of the coordinate's {@code float} are written. The low 24 go to red, green and blue,
 * high byte first; the top byte - the sign and seven exponent bits - goes to alpha with its high bit
 * flipped, so {@code +0.0} writes alpha {@code 0x80} and {@code [0, 2)} writes {@code 0x80} to
 * {@code 0xBF}. Negative zero is written as positive zero, which leaves {@code (0, 0, 0, 0)} - the
 * cleared background - the one pattern no fragment writes. {@link TexCoordProbeBlendMixin} turns
 * blending off while the probe is armed, so the four bytes land as written.
 *
 * <p>It sits on the compilation cache rather than on {@code ShaderManager.getShader}, because the
 * pipeline precompile at resource load reads its sources from the cache directly and never through
 * that method.
 *
 * <h2>When to remove this mixin</h2>
 * It is inert unless the probe property is set, and a probe run writes outside the reference tree.
 */
@Mixin(targets = "net.minecraft.client.renderer.ShaderManager$CompilationCache")
public abstract class TexCoordProbeMixin {

    @Inject(method = "getShaderSource", at = @At("RETURN"), cancellable = true)
    private void refharness$probeTexCoord(Identifier id, ShaderType type, CallbackInfoReturnable<String> cir) {
        if (!Boolean.getBoolean("refharness.headless")) return;
        String axis = System.getProperty("refharness.texCoordProbe");
        if (axis == null || type != ShaderType.FRAGMENT || !"core/entity".equals(id.getPath())) return;
        String source = cir.getReturnValue();
        if (source == null) return;
        String component = "v".equalsIgnoreCase(axis) ? "y" : "x";
        cir.setReturnValue(source.replaceFirst("void\\s+main\\s*\\(\\s*\\)", "void refharnessVanillaMain()")
            + "\nvoid main() {\n"
            + "    refharnessVanillaMain();\n"
            + "    uint bits = floatBitsToUint(texCoord0." + component + ");\n"
            + "    if (bits == 0x80000000u) bits = 0u;\n"
            + "    fragColor = vec4(float((bits >> 16u) & 255u), float((bits >> 8u) & 255u), float(bits & 255u),\n"
            + "        float((bits >> 24u) ^ 128u)) / 255.0;\n"
            + "}\n");
    }
}
