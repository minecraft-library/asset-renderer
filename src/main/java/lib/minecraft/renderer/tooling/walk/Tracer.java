package lib.minecraft.renderer.tooling.walk;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;

/**
 * The advance hook of a traced walk: answers the node to continue at, or {@code null} to halt
 * the walk with {@link Exit#HALTED}. A linear continue is {@code current.getNext()}, a taken
 * jump is the {@link JumpInsnNode}'s label, a halt is {@code null}. The engine arms an identity
 * visited set for every traced walk, so a revisited node ends the walk with {@link Exit#CYCLE}
 * instead of looping.
 */
@FunctionalInterface
public interface Tracer {

    /**
     * Answers the node the walk continues at.
     *
     * @param current the node just offered to the walk
     * @return the next cursor position, or {@code null} to halt
     */
    @Nullable AbstractInsnNode advance(@NotNull AbstractInsnNode current);

}
