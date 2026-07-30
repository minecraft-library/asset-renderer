package lib.minecraft.renderer.tooling.entity;

import lib.minecraft.renderer.tooling.kernel.AsmKit;
import lib.minecraft.renderer.tooling.kernel.ClassNodeCache;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lib.minecraft.renderer.tooling.kernel.VanillaSourceClasses;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * What a boolean render-state field reads as on a freshly spawned entity.
 *
 * <p>A model gates a bone on a render-state flag ({@code basePlate.visible = state.showBasePlate}),
 * and the flag's name says nothing about which way it points. Vanilla packs several of them into one
 * synched byte, and a packed flag is free to store the negative: an armor stand's arms are
 * {@code (flags & 4) != 0} while its base plate is {@code (flags & 8) == 0}, so on the same
 * all-zero byte one reads false and the other true. Assuming false for both hides a plate vanilla
 * shows.
 *
 * <p>Three hops answer it, each of which must match or the whole question goes unanswered:
 * {@code extractRenderState} names the entity accessor a state field is filled from, the accessor's
 * mask and branch say which way the bit points, and {@code defineSynchedData} says what the byte
 * holds before anything sets it. A flag backed by anything else - a plain boolean field, a computed
 * expression - answers {@code false}, which is what an unpacked flag reads as anyway.
 */
final class EntitySpawnFlagResolver {

    private final @NotNull ClassNodeCache cache;
    private final @NotNull EntitySubject subject;
    private final @NotNull Diagnostics diagnostics;

    EntitySpawnFlagResolver(@NotNull EntityContext context) {
        this.cache = context.cache();
        this.subject = context.subject();
        this.diagnostics = context.diagnostics();
    }

    /**
     * Whether {@code stateFlag} reads {@code true} on a freshly spawned entity.
     *
     * @param stateFlag the boolean render-state field name a model gates a bone on
     * @return whether a spawned entity reads it {@code true}, {@code false} when nothing derives it
     */
    boolean spawnValue(@NotNull String stateFlag) {
        Accessor accessor = findAccessor(stateFlag);
        if (accessor == null) return false;
        MethodNode method = AsmKit.findMethodInHierarchy(this.cache, accessor.entityClass(), accessor.name(), "()Z");
        if (method == null) return false;
        BitTest test = readBitTest(method);
        if (test == null) return false;
        Integer defaults = readSynchedDefault(accessor.entityClass(), test.dataField());
        if (defaults == null) return false;

        boolean value = test.valueAt(defaults);
        this.diagnostics.info("spawn flag: %s = %s (%s.%s, mask %d over default %d)",
            stateFlag, value, accessor.entityClass(), accessor.name(), test.mask(), defaults);
        return value;
    }

    /**
     * The entity accessor one render-state field is filled from.
     *
     * @param entityClass the entity class the accessor is called on
     * @param name the accessor method name
     */
    private record Accessor(@NotNull String entityClass, @NotNull String name) {}

    /**
     * A packed-bit read: the synched field it comes off, the mask, and the two results the branch
     * chooses between.
     *
     * @param dataField the {@code EntityDataAccessor} static field name the byte is read under
     * @param mask the bit mask tested
     * @param valueWhenSet what the accessor returns when the masked bits are non-zero
     */
    private record BitTest(@NotNull String dataField, int mask, boolean valueWhenSet) {

        /** What the accessor returns over a given raw value of the packed field. */
        boolean valueAt(int packed) {
            return ((packed & this.mask) != 0) == this.valueWhenSet;
        }
    }

    /**
     * Finds the {@code state.<flag> = entity.<accessor>()} assignment in the renderer's
     * {@code extractRenderState}, walking up the renderer chain because a subclass often leaves the
     * shared fields to its parent.
     */
    private @Nullable Accessor findAccessor(@NotNull String stateFlag) {
        Accessor[] found = new Accessor[1];
        AsmKit.walkSuperChain(this.cache, this.subject.rendererClass(), classNode -> {
            if (found[0] != null) return;
            for (MethodNode method : classNode.methods) {
                if (!VanillaSourceClasses.Methods.EXTRACT_RENDER_STATE.equals(method.name)) continue;
                Accessor accessor = findAccessor(method, stateFlag);
                if (accessor != null) {
                    found[0] = accessor;
                    return;
                }
            }
        });
        return found[0];
    }

    /** The accessor feeding one {@code PUTFIELD <state>.<flag>:Z} inside a single method. */
    private static @Nullable Accessor findAccessor(@NotNull MethodNode method, @NotNull String stateFlag) {
        for (AbstractInsnNode in : method.instructions) {
            if (in.getOpcode() != Opcodes.PUTFIELD
                || !(in instanceof FieldInsnNode put)
                || !stateFlag.equals(put.name)
                || !"Z".equals(put.desc)) continue;
            AbstractInsnNode source = AsmKit.previousReal(in);
            if (source == null
                || source.getOpcode() != Opcodes.INVOKEVIRTUAL
                || !(source instanceof MethodInsnNode call)
                || !"()Z".equals(call.desc)) continue;
            return new Accessor(call.owner, call.name);
        }
        return null;
    }

    /**
     * Reads the {@code <packed> & <mask>} test an accessor branches on, and which side of the
     * branch returns {@code true}. The two results are read off the branch itself rather than
     * assumed from its opcode, so a compiler that lays the arms out the other way still decodes.
     */
    private static @Nullable BitTest readBitTest(@NotNull MethodNode method) {
        for (AbstractInsnNode in : method.instructions) {
            if (in.getOpcode() != Opcodes.IAND) continue;
            AbstractInsnNode maskInsn = AsmKit.previousReal(in);
            Integer mask = maskInsn == null ? null : AsmKit.readIntLiteral(maskInsn);
            if (mask == null || mask == 0) continue;
            String dataField = readDataField(method);
            if (dataField == null) continue;

            AbstractInsnNode branch = AsmKit.nextReal(in);
            if (!(branch instanceof JumpInsnNode jump)) continue;
            boolean jumpsWhenSet = jump.getOpcode() == Opcodes.IFNE;
            if (!jumpsWhenSet && jump.getOpcode() != Opcodes.IFEQ) continue;

            Integer taken = AsmKit.readIntLiteral(AsmKit.nextReal(jump.label));
            Integer fallen = AsmKit.readIntLiteral(AsmKit.nextReal(jump));
            if (taken == null || fallen == null) continue;
            return new BitTest(dataField, mask, (jumpsWhenSet ? taken : fallen) != 0);
        }
        return null;
    }

    /** The {@code EntityDataAccessor} static field an accessor reads its packed value through. */
    private static @Nullable String readDataField(@NotNull MethodNode method) {
        for (AbstractInsnNode in : method.instructions) {
            if (in.getOpcode() == Opcodes.GETSTATIC
                && in instanceof FieldInsnNode get
                && VanillaSourceClasses.Descs.ENTITY_DATA_ACCESSOR_REF.equals(get.desc))
                return get.name;
        }
        return null;
    }

    /**
     * The value a synched field is registered with, read off the {@code define} call in
     * {@code defineSynchedData}. The walk climbs the entity chain because the field is registered by
     * whichever class in it declares the field.
     */
    private @Nullable Integer readSynchedDefault(@NotNull String entityClass, @NotNull String dataField) {
        Integer[] found = new Integer[1];
        AsmKit.walkSuperChain(this.cache, entityClass, classNode -> {
            if (found[0] != null) return;
            MethodNode define = AsmKit.findMethod(classNode, VanillaSourceClasses.Methods.DEFINE_SYNCHED_DATA);
            if (define == null) return;
            found[0] = readSynchedDefault(define, dataField);
        });
        return found[0];
    }

    /** The literal registered against one {@code EntityDataAccessor} inside a single method. */
    private static @Nullable Integer readSynchedDefault(@NotNull MethodNode define, @NotNull String dataField) {
        for (AbstractInsnNode in : define.instructions) {
            if (in.getOpcode() != Opcodes.GETSTATIC
                || !(in instanceof FieldInsnNode get)
                || !dataField.equals(get.name)) continue;
            AbstractInsnNode valueInsn = AsmKit.nextReal(in);
            Integer value = valueInsn == null ? null : AsmKit.readIntLiteral(valueInsn);
            if (value == null) continue;
            AbstractInsnNode box = AsmKit.nextReal(valueInsn);
            if (box == null || !AsmKit.isInvokeStatic(box, VanillaSourceClasses.Types.BYTE,
                VanillaSourceClasses.Methods.VALUE_OF)) continue;
            return value;
        }
        return null;
    }
}
