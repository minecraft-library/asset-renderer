package lib.minecraft.renderer.tooling.blockentity;

import lib.minecraft.renderer.tooling.kernel.AsmKit;
import lib.minecraft.renderer.tooling.kernel.ClassNodeCache;
import lib.minecraft.renderer.tooling.kernel.VanillaSourceClasses;
import lib.minecraft.renderer.tooling.walk.AsmWalker;
import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.HashMap;
import java.util.Map;

/**
 * A split is tint-bearing when its renderer's hierarchy calls a dye/banner tint accessor AND
 * the split's mesh factory is a {@code *FlagModel} (the flag sub-model takes the dye; the
 * wood-brown pole / bar does not). The per-renderer tint-bearing verdict is memoised.
 *
 * <p>No allow-list - the only semantic judgment is "renderer calls a DyeColor / BannerPattern
 * API"; everything downstream is structural.
 */
final class BlockTintFlagResolver {

    private final @NotNull ClassNodeCache cache;

    /**
     * renderer internal name -> whether its hierarchy calls a tint accessor.
     */
    private final @NotNull Map<String, Boolean> tintBearing = new HashMap<>();

    BlockTintFlagResolver(@NotNull ClassNodeCache cache) {
        this.cache = cache;
    }

    /**
     * Whether a split carries the {@code tinted} flag: a tint-bearing renderer plus a
     * {@code *FlagModel} mesh factory.
     *
     * @param rendererClass the renderer internal name
     * @param factoryClass the split's mesh factory class internal name
     * @return {@code true} when the split renders tinted
     */
    boolean isTinted(@NotNull String rendererClass, @NotNull String factoryClass) {
        return isFlagModel(factoryClass) && isRendererTintBearing(rendererClass);
    }

    /**
     * A {@code *FlagModel} mesh (banner / wall-banner flag) - the dye-taking sub-model.
     */
    private static boolean isFlagModel(@NotNull String factoryClass) {
        return factoryClass.endsWith(BlockFamilyPolicies.dyeTargetModelSuffix());
    }

    private boolean isRendererTintBearing(@NotNull String rendererClass) {
        return this.tintBearing.computeIfAbsent(rendererClass,
            renderer -> AsmKit.walkSuperChainUntil(this.cache, renderer, BlockTintFlagResolver::classCallsTintAccessor) != null);
    }

    /**
     * Whether any method on a class invokes {@code DyeColor.getTextureDiffuseColor(s)},
     * {@code BannerPattern.getColor}, or returns {@code BannerPatternLayers}.
     */
    private static boolean classCallsTintAccessor(@NotNull ClassNode cn) {
        for (MethodNode method : cn.methods)
            if (AsmWalker.over(method).any(in ->
                AsmKit.isInvokeVirtual(in, VanillaSourceClasses.Types.DYE_COLOR, VanillaSourceClasses.Methods.GET_TEXTURE_DIFFUSE_COLOR)
                    || AsmKit.isInvokeStatic(in, VanillaSourceClasses.Types.DYE_COLOR, VanillaSourceClasses.Methods.GET_TEXTURE_DIFFUSE_COLORS)
                    || AsmKit.isInvokeVirtual(in, VanillaSourceClasses.Types.BANNER_PATTERN, VanillaSourceClasses.Methods.GET_COLOR)
                    || (in instanceof MethodInsnNode mi && AsmKit.descriptorReturns(mi.desc, VanillaSourceClasses.Types.BANNER_PATTERN_LAYERS))))
                return true;
        return false;
    }

}
