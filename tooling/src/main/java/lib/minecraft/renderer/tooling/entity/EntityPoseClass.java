package lib.minecraft.renderer.tooling.entity;

import dev.simplified.annotations.UtilityClass;
import lib.minecraft.renderer.tooling.kernel.ClassKit;
import lib.minecraft.renderer.tooling.kernel.ClassNodeCache;
import lib.minecraft.renderer.tooling.kernel.VanillaSourceClasses;
import lib.minecraft.renderer.tooling.walk.AsmWalker;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.TypeInsnNode;

/**
 * The model class a renderer poses its subject's body with - the first model its constructor chain
 * allocates.
 *
 * <p><b>The class that bakes a mesh is not always the class that poses it</b>, and a geometry
 * coordinate is headed with the first. A zombie's mesh is {@code HumanoidModel#createMesh} because
 * {@code ZombieModel} declares no layer of its own, but the renderer hands the layer a
 * {@code ZombieModel}, and it is that class whose {@code setupAnim} runs - so keying its pose off the
 * coordinate poses it as a plain humanoid and loses the arms-out stance every zombie stands in.
 *
 * <p>The same rule an equipment layer already goes through, read at a different site: a layer's
 * posing class is the first model allocated as part of the layer's own construction, and a body's is
 * the first the renderer allocates at all. A renderer builds its own model into the {@code super}
 * call's arguments, which is evaluated before any {@code addLayer}, so first is the body's.
 *
 * <p>The walk goes up the superclass chain because a renderer is free to allocate nothing and hand
 * the job to the one it extends - a husk builds no model and delegates to the zombie's constructor.
 * A renderer that allocates none anywhere answers nothing, and the caller keeps the coordinate.
 */
@UtilityClass
public final class EntityPoseClass {

    /**
     * The model class a renderer poses with.
     *
     * @param cache the per-session class cache
     * @param rendererClass the renderer's JVM internal name
     * @return the model class's internal name, or {@code null} when its chain allocates none
     */
    public static @Nullable String of(@NotNull ClassNodeCache cache, @NotNull String rendererClass) {
        String[] posedBy = {null};
        ClassKit.walkConstructorChain(cache, rendererClass, ctor -> {
            if (posedBy[0] != null) return;
            posedBy[0] = AsmWalker.over(ctor).firstNotNull(node -> node.getOpcode() == Opcodes.NEW
                && node instanceof TypeInsnNode type
                && ClassKit.extendsClass(cache, type.desc, VanillaSourceClasses.Types.ENTITY_MODEL)
                ? type.desc : null);
        });
        return posedBy[0];
    }

}
