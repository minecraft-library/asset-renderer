package lib.minecraft.renderer.tooling.geometry;

import dev.simplified.annotations.UtilityClass;
import lib.minecraft.renderer.tooling.kernel.ClassKit;
import lib.minecraft.renderer.tooling.kernel.ClassNodeCache;
import lib.minecraft.renderer.tooling.walk.AsmWalker;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The class whose body actually builds a factory's mesh.
 *
 * <p>A geometry coordinate is headed with the class a mesh was baked at, and the parser follows a
 * cross-class {@code INVOKESTATIC} and inlines the callee's body - so a factory whose whole body
 * hands its arguments straight on yields the callee's mesh under the caller's name, and the two
 * coordinates carry byte-identical payloads because they are one method. Resolving the coordinate
 * here collapses them onto the one class that declares the body.
 *
 * <p>The predicate is deliberately narrow, because a factory that does anything at all after the
 * delegation builds a DIFFERENT mesh and must keep its own coordinate. A body qualifies only when it
 * is exactly the declared parameters loaded in ascending slot order, one {@code INVOKESTATIC} whose
 * descriptor is identical to the caller's, and the matching return - nothing else, in any position.
 */
@UtilityClass
public final class MeshDeclarationSite {

    /**
     * Resolves the class that declares a factory's mesh body, following pure tail delegations to a
     * fixpoint.
     *
     * <p>A chain is followed under a visiting set, so a cycle answers the class it was entered at
     * rather than looping. A method that cannot be read, or whose body is anything but a pure tail
     * delegation, answers the class it was asked about.
     *
     * @param cache the class source
     * @param owner the factory class's JVM internal name
     * @param method the factory method
     * @return the internal name of the class whose body builds the mesh
     */
    public static @NotNull String resolve(
        @NotNull ClassNodeCache cache, @NotNull String owner, @NotNull String method) {

        Set<String> visiting = new LinkedHashSet<>();
        String cursor = owner;
        while (visiting.add(cursor)) {
            String next = delegatedTo(cache, cursor, method);
            if (next == null || visiting.contains(next)) return cursor;
            cursor = next;
        }
        return cursor;
    }

    /**
     * The class a factory hands its whole body to, or {@code null} where it builds a mesh of its own.
     *
     * @param cache the class source
     * @param owner the factory class's JVM internal name
     * @param method the factory method
     * @return the callee's internal name, or {@code null} where this is not a pure tail delegation
     */
    private static @Nullable String delegatedTo(
        @NotNull ClassNodeCache cache, @NotNull String owner, @NotNull String method) {

        ClassNode classNode = cache.load(owner);
        if (classNode == null) return null;
        MethodNode node = ClassKit.findMethod(classNode, method);
        if (node == null || node.instructions == null) return null;

        Type[] parameters = Type.getArgumentTypes(node.desc);
        AbstractInsnNode cursor = firstReal(node);
        // The declared parameters, in ascending slot order and nothing else on the stack. A static
        // factory's slots start at zero, and a wide parameter takes two.
        int slot = 0;
        for (Type parameter : parameters) {
            if (!(cursor instanceof VarInsnNode load)) return null;
            if (load.getOpcode() != parameter.getOpcode(Opcodes.ILOAD) || load.var != slot) return null;
            slot += parameter.getSize();
            cursor = AsmWalker.nextReal(cursor);
        }

        // One INVOKESTATIC carrying exactly what was loaded, so the callee computes what this method
        // declared it would.
        if (!(cursor instanceof MethodInsnNode call)) return null;
        if (call.getOpcode() != Opcodes.INVOKESTATIC || !call.desc.equals(node.desc)) return null;
        if (call.owner.equals(owner)) return null;

        // And the return of what it answered, with nothing between the two.
        AbstractInsnNode returns = AsmWalker.nextReal(cursor);
        if (returns == null || returns.getOpcode() != Type.getReturnType(node.desc).getOpcode(Opcodes.IRETURN))
            return null;
        return AsmWalker.nextReal(returns) == null ? call.owner : null;
    }

    /** The first instruction of a body that is not a label, line number or frame. */
    private static @Nullable AbstractInsnNode firstReal(@NotNull MethodNode node) {
        AbstractInsnNode first = node.instructions.getFirst();
        if (first == null) return null;
        return first.getOpcode() >= 0 ? first : AsmWalker.nextReal(first);
    }

}
