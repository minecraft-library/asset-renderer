package lib.minecraft.renderer.tooling.entity;

import lib.minecraft.renderer.tooling.util.ClassNodeCache;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.Handle;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Detects whether an entity's {@code *Model} class wires vanilla's back-face-culling render type.
 *
 * <p>A model sets its render type by handing a {@code Function<Identifier, RenderType>} to the
 * two-arg {@code EntityModel} constructor. The no-cull default ({@code RenderTypes.entityCutout},
 * supplied by the one-arg constructor) leaves GL back-face culling OFF, so a zero-thickness plane
 * cube draws both of its coincident sides; the few models that pass {@code RenderTypes::entityCutoutCull}
 * (bat, baby turtle, arrow, bee stinger, chest) request culling, and a culled plane shows only its
 * camera-facing side.
 *
 * <p>The asset's runtime kit force-no-culls plane cubes by default (cod fins, warden tendrils), so it
 * needs this per-model signal to instead cull the planes of {@code entityCutoutCull} models. In
 * bytecode the {@code RenderTypes::entityCutoutCull} method reference surfaces as an
 * {@code invokedynamic} whose bootstrap arguments carry a method {@link Handle} named
 * {@code entityCutoutCull}; a direct method call to a member of that name (typically the
 * {@code INVOKESTATIC} factory) is matched too for robustness. Every method of the class is scanned so
 * a render type wired in a helper / factory is still caught.
 */
@UtilityClass
public final class EntityRenderTypeResolver {

    private static final @NotNull String ENTITY_CUTOUT_CULL = "entityCutoutCull";

    /**
     * Resolves whether the model class at {@code classEntry} requests the {@code entityCutoutCull}
     * (back-face-culling) render type.
     *
     * @param classNodes the shared class-node cache
     * @param classEntry the model class file entry (internal name, optionally suffixed {@code .class})
     * @return {@code true} when the class references {@code RenderTypes.entityCutoutCull}; {@code false}
     *     for the no-cull default or when the class can't be loaded
     */
    public static boolean usesCullRenderType(@NotNull ClassNodeCache classNodes, @NotNull String classEntry) {
        String internalName = classEntry.endsWith(".class")
            ? classEntry.substring(0, classEntry.length() - ".class".length())
            : classEntry;
        ClassNode modelClass = classNodes.load(internalName);
        if (modelClass == null) return false;
        for (MethodNode method : modelClass.methods)
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof InvokeDynamicInsnNode indy)
                    for (Object bsmArg : indy.bsmArgs)
                        if (bsmArg instanceof Handle handle && ENTITY_CUTOUT_CULL.equals(handle.getName()))
                            return true;
                if (insn instanceof MethodInsnNode call && ENTITY_CUTOUT_CULL.equals(call.name))
                    return true;
            }
        return false;
    }

}
