package lib.minecraft.renderer.tooling.snapshot;

import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.tooling.kernel.AsmKit;
import lib.minecraft.renderer.tooling.kernel.ClassNodeCache;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lib.minecraft.renderer.tooling.kernel.ToolingSession;
import lib.minecraft.renderer.tooling.kernel.VanillaSourceClasses;
import lib.minecraft.renderer.tooling.vanilla.BlockRegistryIndex;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Walks {@code BlockColors.createDefault()} and owns the {@code tints} + {@code dropped} nodes.
 * The walk is a self-contained state machine over AsmKit primitives: it tracks the last
 * {@code BlockTintSources} factory call, the in-hand int literal
 * for {@code constant(...)}, the composed-source ({@code List.of} arity) flag, and the pending
 * {@code GETSTATIC Blocks.X} block ids, committing at each {@code BlockColors.register}
 * INVOKEVIRTUAL. Classification is delegated to {@link TintRegistrationResolver} - the walk
 * decides nothing about targets or drops.
 *
 * <p>Output order is the {@code createDefault()} bytecode walk order (declared in the envelope):
 * tint rows key a {@link LinkedHashMap} by block id (last write wins, first position kept -
 * exactly the legacy {@code tints} map semantics), so the emitted array is byte-order-stable.
 */
public final class TintWalk {

    /** {@code java.util.List} - the composed-source factory owner (JDK, never a vanilla name). */
    private static final @NotNull String LIST_INTERNAL = "java/util/List";

    /** {@code List.of} - the source-list factory. */
    private static final @NotNull String LIST_OF = "of";

    /** {@code List.of(Object)} - the single-source overload; any other arity composes sources. */
    private static final @NotNull String LIST_OF_SINGLE_DESC = "(Ljava/lang/Object;)Ljava/util/List;";

    private TintWalk() {
    }

    /**
     * Walks the tint table and populates {@code root}'s {@code tints} (+ {@code dropped}) nodes.
     *
     * @param session the live session
     * @param index the shared block-registry index (block-id derivation)
     * @param root the envelope root
     */
    public static void run(@NotNull ToolingSession session, @NotNull BlockRegistryIndex index, @NotNull JsonTree root) {
        ClassNodeCache cache = session.cache();
        Diagnostics diagnostics = session.diagnostics().child("tints");

        ClassNode blockColors = cache.load(VanillaSourceClasses.Types.BLOCK_COLORS);
        if (blockColors == null) {
            diagnostics.error("'%s' class missing - tint table unresolved", VanillaSourceClasses.Types.BLOCK_COLORS);
            return;
        }
        MethodNode createDefault = AsmKit.findMethod(blockColors, VanillaSourceClasses.Methods.CREATE_DEFAULT);
        if (createDefault == null) {
            diagnostics.error("'%s.%s' missing - tint table unresolved",
                VanillaSourceClasses.Types.BLOCK_COLORS, VanillaSourceClasses.Methods.CREATE_DEFAULT);
            return;
        }

        Map<String, JsonTree> tintRows = new LinkedHashMap<>();
        List<JsonTree> droppedRows = new ArrayList<>();

        String pendingSource = null;
        Integer pendingInHand = null;
        boolean multiSource = false;
        List<String> pendingBlocks = new ArrayList<>();
        AsmKit.LiteralStack intStack = new AsmKit.LiteralStack(4);

        for (AbstractInsnNode node = createDefault.instructions.getFirst(); node != null; node = node.getNext()) {
            Integer literal = AsmKit.readIntLiteral(node);
            if (literal != null) {
                intStack.push(literal);
                continue;
            }

            if (AsmKit.isGetStatic(node, VanillaSourceClasses.Types.BLOCKS)) {
                String field = ((FieldInsnNode) node).name;
                BlockRegistryIndex.Entry entry = index.byField(field);
                if (entry != null) pendingBlocks.add(entry.id());
                else diagnostics.error("Blocks.%s referenced in a tint registration is not in the registry index", field);
                continue;
            }

            if (node instanceof MethodInsnNode call && node.getOpcode() == Opcodes.INVOKESTATIC) {
                if (call.owner.equals(VanillaSourceClasses.Types.BLOCK_TINT_SOURCES)) {
                    pendingSource = call.name;
                    if (call.name.equals(VanillaSourceClasses.Methods.CONSTANT))
                        pendingInHand = pickInHand(intStack, AsmKit.argTypes(call.desc).length);
                    continue;
                }
                if (call.owner.equals(LIST_INTERNAL) && call.name.equals(LIST_OF) && !call.desc.equals(LIST_OF_SINGLE_DESC)) {
                    multiSource = true;
                    continue;
                }
            }

            if (AsmKit.isInvokeVirtual(node, VanillaSourceClasses.Types.BLOCK_COLORS, VanillaSourceClasses.Methods.REGISTER)) {
                commit(cache, diagnostics, pendingSource, pendingInHand, multiSource, pendingBlocks, tintRows, droppedRows);
                pendingSource = null;
                pendingInHand = null;
                multiSource = false;
                pendingBlocks.clear();
                intStack.reset();
            }
        }

        JsonTree tints = root.child("tints");
        tintRows.forEach(tints::put);
        if (!droppedRows.isEmpty()) {
            JsonTree dropped = root.childArray("dropped");
            for (JsonTree row : droppedRows) dropped.add(row);
        }
        diagnostics.info("%d tint rows, %d dropped rows from %s.%s",
            tintRows.size(), droppedRows.size(), VanillaSourceClasses.Types.BLOCK_COLORS, VanillaSourceClasses.Methods.CREATE_DEFAULT);
    }

    /**
     * Commits one registration: classifies its source and appends a tint row (keyed by block id)
     * or a dropped row per pending block. A missing source (no factory call before {@code register})
     * is a walk anomaly recorded loudly.
     */
    private static void commit(
        @NotNull ClassNodeCache cache,
        @NotNull Diagnostics diagnostics,
        @Nullable String pendingSource,
        @Nullable Integer pendingInHand,
        boolean multiSource,
        @NotNull List<String> pendingBlocks,
        @NotNull Map<String, JsonTree> tintRows,
        @NotNull List<JsonTree> droppedRows
    ) {
        if (pendingBlocks.isEmpty()) return;
        if (pendingSource == null) {
            diagnostics.error("tint registration for %s committed with no source factory", pendingBlocks);
            return;
        }
        TintRegistrationResolver.Resolution resolution =
            TintRegistrationResolver.resolve(cache, pendingSource, pendingInHand, multiSource, diagnostics);
        if (resolution == null) return;

        for (String blockId : pendingBlocks) {
            if (resolution.isDrop()) {
                droppedRows.add(JsonTree.object()
                    .put("block", blockId)
                    .put("source", resolution.sourceLabel())
                    .put("reason", resolution.dropReason()));
                continue;
            }
            JsonTree row = JsonTree.object().put("target", resolution.target());
            if (resolution.constant() != null) row.putHex("constant", resolution.constant());
            row.put("source", resolution.sourceLabel());
            tintRows.put(blockId, row);
        }
    }

    /**
     * Picks the in-hand constant from the int stack per {@code TINT_CONSTANT_IN_HAND}: keep the
     * factory's argument at that index (the first), discarding the higher-index args on top.
     */
    private static @NotNull Integer pickInHand(@NotNull AsmKit.LiteralStack stack, int arity) {
        int keepIndex = SnapshotShapePolicies.constantInHandArg();
        for (int discard = arity - 1 - keepIndex; discard > 0; discard--) stack.popIntOrZero();
        return stack.popIntOrZero();
    }

}
