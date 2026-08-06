package lib.minecraft.renderer.tooling.vanilla;

import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.tooling.kernel.AsmKit;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lib.minecraft.renderer.tooling.kernel.ToolingSession;
import lib.minecraft.renderer.tooling.kernel.VanillaSourceClasses;
import lib.minecraft.renderer.tooling.walk.AsmWalker;
import lib.minecraft.renderer.tooling.walk.CommitWalk;
import lib.minecraft.renderer.tooling.walk.Insn;
import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.Map;
import java.util.TreeMap;

/**
 * The {@code Items.<clinit>} block-to-item alias walk - the map from a secondary block that owns no
 * item of its own to the standing block whose item both share.
 *
 * <p>Vanilla registers a single {@code BlockItem} for a family of blocks. Wall banners and wall
 * skulls / heads route through the standing block's {@code BannerItem} /
 * {@code StandingAndWallBlockItem}, and {@code big_dripleaf_stem} / the filled cauldrons route
 * through the {@code big_dripleaf} / {@code cauldron} item. Those secondary blocks therefore have no
 * item id of their own; their inventory icon poses through the standing block item's
 * {@code display.gui}. This walk recovers the mapping so the icon renderer resolves the correct gui.
 *
 * <p>Two registration shapes carry it, both keyed by the {@code Blocks} static field the getstatic
 * pushes:
 * <ul>
 *   <li><b>{@code registerBlock(Block, BiFunction[, Properties])}</b> - the factory is a
 *       {@code lambda$static$N} that news a two-block item ({@code BannerItem} /
 *       {@code StandingAndWallBlockItem}); the primary is the getstatic immediately before the
 *       factory indy, and the secondary is the lone {@code Blocks} getstatic inside the lambda body
 *       (the standing block arrives as the lambda's first parameter, not a getstatic).</li>
 *   <li><b>{@code registerBlock(Block, Block...)}</b> - the primary is the getstatic before the
 *       {@code Block[]} allocation, and the secondaries are the array-store getstatics.</li>
 * </ul>
 */
public final class BlockItemAliasWalk {

    /** The factory-lambda return descriptor the two-block item registrations produce. */
    private static final @NotNull String BIFUNCTION_RETURN_SUFFIX = ")Ljava/util/function/BiFunction;";

    /** {@code Items.registerBlock} - the registration commit call both shapes terminate on. */
    private static final @NotNull String REGISTER_BLOCK = "registerBlock";

    /** The {@code registerBlock(Block, Block...)} varargs descriptor whose array elements are the secondaries. */
    private static final @NotNull String REGISTER_BLOCK_VARARGS_DESC = VanillaSourceClasses.Descs.of(
        VanillaSourceClasses.Descs.ref(VanillaSourceClasses.Types.ITEM),
        VanillaSourceClasses.Descs.ref(VanillaSourceClasses.Types.BLOCK),
        "[" + VanillaSourceClasses.Descs.ref(VanillaSourceClasses.Types.BLOCK));

    private final @NotNull BlockRegistryIndex index;
    private final @NotNull Diagnostics diagnostics;

    /** Secondary block id to its standing block's id, sorted by secondary id for byte-stable output. */
    private final @NotNull TreeMap<String, String> aliases = new TreeMap<>();

    private BlockItemAliasWalk(@NotNull BlockRegistryIndex index, @NotNull Diagnostics diagnostics) {
        this.index = index;
        this.diagnostics = diagnostics;
    }

    /**
     * Walks {@code Items.<clinit>} and writes the {@code aliases} object into {@code root}: each
     * secondary block id mapped to the standing block id whose item it shares, sorted by secondary
     * id for byte-stable output. An absent {@code Items} class or {@code <clinit>} records an ERROR
     * and leaves an empty {@code aliases} object.
     *
     * @param session the live session
     * @param index the block registry index, used to convert {@code Blocks} field names to namespaced ids
     * @param root the envelope root the {@code aliases} object is written into
     */
    public static void run(@NotNull ToolingSession session, @NotNull BlockRegistryIndex index, @NotNull JsonTree root) {
        new BlockItemAliasWalk(index, session.diagnostics().child("blockItems")).walk(session, root);
    }

    /** One run's walk over {@code Items.<clinit>}, accumulating into {@link #aliases}. */
    private void walk(@NotNull ToolingSession session, @NotNull JsonTree root) {
        ClassNode items = session.cache().load(VanillaSourceClasses.Types.ITEMS);
        if (items == null) {
            this.diagnostics.error("'%s' class missing - block-item alias map unresolved", VanillaSourceClasses.Types.ITEMS);
            writeAliases(root);
            return;
        }
        MethodNode clinit = AsmKit.findMethod(items, AsmKit.CLINIT);
        if (clinit == null) {
            this.diagnostics.error("'%s.<clinit>' missing - block-item alias map unresolved", VanillaSourceClasses.Types.ITEMS);
            writeAliases(root);
            return;
        }

        AsmWalker.over(clinit)
            .on(Insn.of(InvokeDynamicInsnNode.class, indy -> indy.desc.endsWith(BIFUNCTION_RETURN_SUFFIX)), indy -> {
                AbstractInsnNode primary = AsmWalker.previousReal(indy);
                if (primary instanceof FieldInsnNode field && AsmKit.isGetStatic(primary, VanillaSourceClasses.Types.BLOCKS))
                    collectLambdaSecondaries(items, indy, field.name);
            })
            .on(Insn.of(TypeInsnNode.class, arrayType -> arrayType.getOpcode() == Opcodes.ANEWARRAY
                && arrayType.desc.equals(VanillaSourceClasses.Types.BLOCK)), this::collectVarargsSecondaries)
            .run();

        writeAliases(root);
        this.diagnostics.info("mapped %d secondary blocks to their standing-block item", this.aliases.size());
    }

    /**
     * Opens the two-block item factory lambda and emits an alias for each {@code Blocks} getstatic in
     * its body (the wall counterpart), keyed to {@code primaryField} (the standing block). A generic
     * single-block factory lambda has no such getstatic and emits nothing.
     */
    private void collectLambdaSecondaries(
        @NotNull ClassNode items,
        @NotNull InvokeDynamicInsnNode indy,
        @NotNull String primaryField
    ) {
        Handle handle = AsmWalker.extractLambdaHandle(indy);
        if (handle == null || handle.getTag() != Opcodes.H_INVOKESTATIC || !handle.getOwner().equals(items.name))
            return;
        MethodNode lambda = AsmKit.findMethod(items, handle.getName(), handle.getDesc());
        if (lambda == null) return;
        AsmWalker.over(lambda)
            .getStatic(VanillaSourceClasses.Types.BLOCKS)
            .names()
            .forEach(secondary -> emit(secondary, primaryField));
    }

    /**
     * Emits an alias for each array-store {@code Blocks} getstatic between {@code anewarray} and its
     * {@code registerBlock(Block, Block...)} call, keyed to the primary getstatic that precedes the
     * array's length push.
     */
    private void collectVarargsSecondaries(@NotNull AbstractInsnNode anewarray) {
        AbstractInsnNode lengthPush = AsmWalker.previousReal(anewarray);
        AbstractInsnNode primary = AsmWalker.previousReal(lengthPush);
        if (!(primary instanceof FieldInsnNode field) || !AsmKit.isGetStatic(primary, VanillaSourceClasses.Types.BLOCKS))
            return;

        CommitWalk.Commit<MethodInsnNode, String> register = AsmWalker.after(anewarray)
            .gather(node -> AsmKit.isGetStatic(node, VanillaSourceClasses.Types.BLOCKS)
                ? ((FieldInsnNode) node).name : null)
            .commitAt(MethodInsnNode.class, call -> AsmKit.isInvokeStatic(
                call, VanillaSourceClasses.Types.ITEMS, REGISTER_BLOCK, REGISTER_BLOCK_VARARGS_DESC))
            .first();
        if (register == null) return;
        for (String secondary : register.values()) emit(secondary, field.name);
    }

    /**
     * Records {@code secondaryField -> primaryField} as namespaced ids, resolving both through the
     * block registry. A field the registry does not know records a WARN and is skipped; a
     * secondary that resolves to its own primary (never expected) is dropped.
     */
    private void emit(@NotNull String secondaryField, @NotNull String primaryField) {
        BlockRegistryIndex.Entry secondary = this.index.byField(secondaryField);
        BlockRegistryIndex.Entry primary = this.index.byField(primaryField);
        if (secondary == null || primary == null) {
            this.diagnostics.warn("unresolved block-item alias '%s' -> '%s' - field absent from block registry", secondaryField, primaryField);
            return;
        }
        if (secondary.id().equals(primary.id())) return;
        this.aliases.put(secondary.id(), primary.id());
    }

    private void writeAliases(@NotNull JsonTree root) {
        JsonTree node = root.child("aliases");
        for (Map.Entry<String, String> alias : this.aliases.entrySet())
            node.put(alias.getKey(), alias.getValue());
    }

}
