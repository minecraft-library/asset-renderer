package lib.minecraft.renderer.tooling.entity;

import dev.simplified.annotations.UtilityClass;
import lib.minecraft.renderer.tooling.kernel.VanillaSourceClasses;
import lib.minecraft.renderer.tooling.walk.AsmWalker;
import lib.minecraft.renderer.tooling.walk.Cells;
import lib.minecraft.renderer.tooling.walk.Insn;
import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.Map;

/**
 * The join between the field name a vanilla model calls a bone and the name its mesh does.
 *
 * <p>Every model caches its bones into fields in its constructor, and the two halves of the name
 * disagree - {@code rightChest} against {@code right_chest} - so the constructor is the only place
 * the mapping is written down. Two walks need it and they need different amounts of it: bone
 * visibility reads the scalar fields alone, while a skeletal pose also has to name the elements of
 * an array of parts. Whichever asks, they have to agree about what a field is called, which is why
 * the shape lives here rather than once in each.
 */
@UtilityClass
public final class EntityBoneNames {

    /**
     * Collects one constructor's {@code LDC "<bone>"; INVOKEVIRTUAL ModelPart.getChild; PUTFIELD
     * <field>} chains into a field-to-bone map.
     *
     * <p>The three stages latch rather than match adjacently, because the bone name is pushed
     * before the receiver and the store lands after the call. A store reached without both a name
     * and a {@code getChild} in hand is some other field being written and is passed over.
     *
     * @param owner the declaring class, which the store has to name for the binding to be its own
     * @param ctor the constructor to read
     * @param out the map each binding is added to, first binding winning
     */
    public static void collectFieldToBoneNameMap(
        @NotNull ClassNode owner, @NotNull MethodNode ctor, @NotNull Map<String, String> out) {

        Cells.Latch<String> pendingBoneName = Cells.latch();
        Cells.Flag pendingChildCall = Cells.flag();
        AsmWalker.over(ctor)
            .feed(pendingBoneName)
            .feed(pendingChildCall)
            .on(Insn.of(LdcInsnNode.class, ldc -> ldc.cst instanceof String), ldc -> {
                pendingBoneName.set((String) ldc.cst);
                pendingChildCall.clear();
            })
            .on(Insn.invokeVirtual(VanillaSourceClasses.Types.MODEL_PART, VanillaSourceClasses.Methods.GET_CHILD),
                childCall -> {
                    if (pendingBoneName.get() != null) pendingChildCall.set();
                })
            .on(Insn.of(FieldInsnNode.class, put -> put.getOpcode() == Opcodes.PUTFIELD && owner.name.equals(put.owner)),
                put -> {
                    String boneName = pendingBoneName.get();
                    if (!pendingChildCall.get() || boneName == null) return;
                    out.putIfAbsent(put.name, boneName);
                    pendingBoneName.clear();
                    pendingChildCall.clear();
                })
            .run();
    }

}
