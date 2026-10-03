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
 * by the last bits of its interpolated coordinate, and the arithmetic that produces them is not
 * published. A colour reference only says which texel won; this pass says what the coordinate was.
 *
 * <h2>What it writes</h2>
 * Armed by {@code refharness.texCoordProbe}, naming the axis - {@code u} or {@code v}. The vertex
 * shader is untouched, so the coordinate is interpolated exactly as an ordinary render interpolates
 * it. The fragment shader's own {@code main} is renamed out of the way and replaced by one that keeps
 * its alpha cutout, so the fragments that survive are the ones an ordinary render shows and each
 * pixel reports the coordinate of the fragment whose texel it drew. The low 24 bits of the coordinate's {@code float} go to red, green and blue, high byte first, with
 * alpha at one, which a source-over blend passes through unchanged. Those bits are the mantissa and
 * the exponent's lowest bit, which recover the value uniquely within one binade of any expected one.
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
            + "#ifdef ALPHA_CUTOUT\n"
            + "    if (texture(Sampler0, texCoord0).a < ALPHA_CUTOUT) discard;\n"
            + "#endif\n"
            + "    uint bits = floatBitsToUint(texCoord0." + component + ");\n"
            + "    fragColor = vec4(float((bits >> 16u) & 255u), float((bits >> 8u) & 255u), float(bits & 255u), 255.0) / 255.0;\n"
            + "}\n");
    }
}
