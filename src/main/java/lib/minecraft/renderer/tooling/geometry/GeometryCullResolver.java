package lib.minecraft.renderer.tooling.geometry;

import lib.minecraft.renderer.tooling.kernel.AsmKit;
import lib.minecraft.renderer.tooling.kernel.ClassNodeCache;
import lib.minecraft.renderer.tooling.walk.AsmWalker;
import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * The per-geometry back-face-culling flag - whether a model class wires vanilla's
 * {@code RenderTypes.entityCutoutCull} render type instead of the no-cull default
 * ({@code entityCutout}). Cull is per-geometry data: the flow stamps {@code cull} at
 * emit, never mutating parsed JSON post-hoc. The runtime kit reads it to cull
 * zero-thickness plane cubes - a culled plane shows only its camera-facing side (the
 * bat ear's pink inner face) rather than drawing both coincident sides and letting the
 * LEQUAL depth tie-break pick the away side.
 */
final class GeometryCullResolver {

    /**
     * The vanilla render-type factory name whose presence flags back-face culling.
     */
    private static final @NotNull String ENTITY_CUTOUT_CULL = "entityCutoutCull";

    private GeometryCullResolver() {
    }

    /**
     * Returns whether the model class references the {@code entityCutoutCull} render type -
     * either as a direct call or as a method-reference bootstrap argument.
     *
     * @param cache the session's jar cache
     * @param factoryClass the model class's JVM internal name
     * @return {@code true} when the class wires the culling render type
     */
    static boolean usesCullRenderType(@NotNull ClassNodeCache cache, @NotNull String factoryClass) {
        ClassNode modelClass = cache.load(factoryClass);
        if (modelClass == null) return false;
        for (MethodNode method : modelClass.methods)
            if (AsmWalker.over(method).any(insn ->
                (insn instanceof InvokeDynamicInsnNode indy && AsmKit.findBsmHandleByName(indy, ENTITY_CUTOUT_CULL) != null)
                    || (insn instanceof MethodInsnNode call && ENTITY_CUTOUT_CULL.equals(call.name))))
                return true;
        return false;
    }

}
