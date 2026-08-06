package lib.minecraft.renderer.tooling.snapshot;

import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.tooling.kernel.ClassKit;
import lib.minecraft.renderer.tooling.kernel.ClassNodeCache;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lib.minecraft.renderer.tooling.kernel.ToolingSession;
import lib.minecraft.renderer.tooling.kernel.VanillaSourceClasses;
import lib.minecraft.renderer.tooling.vanilla.BlockRegistryIndex;
import lib.minecraft.renderer.tooling.walk.AsmWalker;
import lib.minecraft.renderer.tooling.walk.Cells;
import lib.minecraft.renderer.tooling.walk.Insn;
import lib.minecraft.renderer.tooling.walk.Missing;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.MethodInsnNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Walks {@code BlockColors.createDefault()} and owns the {@code tints} + {@code dropped} nodes.
 * The walk is an {@link AsmWalker} chain: a four-deep int-literal window, a latch for the last
 * {@code BlockTintSources} factory call, a latch for the in-hand int literal
 * for {@code constant(...)}, the composed-source ({@code List.of} arity) flag, and the pending
 * {@code GETSTATIC Blocks.X} block-id list, committing at each {@code BlockColors.register}
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

        AsmWalker createDefault = AsmWalker.over(cache,
            VanillaSourceClasses.Types.BLOCK_COLORS, VanillaSourceClasses.Methods.CREATE_DEFAULT);
        Missing missing = createDefault.missing();
        if (missing != null) {
            if (missing == Missing.CLASS)
                diagnostics.error("'%s' class missing - %s unresolved", VanillaSourceClasses.Types.BLOCK_COLORS, "tint table");
            else
                diagnostics.error("'%s.%s' missing - %s unresolved", VanillaSourceClasses.Types.BLOCK_COLORS, VanillaSourceClasses.Methods.CREATE_DEFAULT, "tint table");
            return;
        }

        Map<String, JsonTree> tintRows = new LinkedHashMap<>();
        List<JsonTree> droppedRows = new ArrayList<>();

        Cells.Latch<String> pendingSource = Cells.latch();
        Cells.Latch<Integer> pendingInHand = Cells.latch();
        Cells.Flag multiSource = Cells.flag();
        Cells.ListCell<String> pendingBlocks = Cells.list();
        Cells.Window<Integer> intStack = Cells.window(AsmWalker::intLiteral, 4);

        createDefault
            .feed(intStack)
            .feed(pendingSource)
            .feed(pendingInHand)
            .feed(multiSource)
            .feed(pendingBlocks)
            .on(Insn.getStatic(VanillaSourceClasses.Types.BLOCKS), get -> {
                String field = get.name;
                BlockRegistryIndex.Entry entry = index.byField(field);
                if (entry != null) pendingBlocks.add(entry.id());
                else diagnostics.error("Blocks.%s referenced in a tint registration is not in the registry index", field);
            })
            .on(Insn.of(MethodInsnNode.class, call -> call.getOpcode() == Opcodes.INVOKESTATIC
                && call.owner.equals(VanillaSourceClasses.Types.BLOCK_TINT_SOURCES)), call -> {
                pendingSource.set(call.name);
                if (call.name.equals(VanillaSourceClasses.Methods.CONSTANT))
                    pendingInHand.set(pickInHand(intStack, ClassKit.argTypes(call.desc).length));
            })
            .on(Insn.invokeStatic(LIST_INTERNAL, LIST_OF).and(call -> !call.desc.equals(LIST_OF_SINGLE_DESC)),
                call -> multiSource.set())
            .commitAt(Insn.invokeVirtual(VanillaSourceClasses.Types.BLOCK_COLORS, VanillaSourceClasses.Methods.REGISTER),
                register -> commit(cache, diagnostics, pendingSource.get(), pendingInHand.get(),
                    multiSource.get(), pendingBlocks.values(), tintRows, droppedRows))
            .run();

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
     * Picks the in-hand constant from the int window per {@code TINT_CONSTANT_IN_HAND}: keep the
     * factory's argument at that index (the first), discarding the higher-index args on top.
     * Takes are from-top-consuming; the keep-read of an empty window answers zero.
     */
    private static @NotNull Integer pickInHand(@NotNull Cells.Window<Integer> stack, int arity) {
        int keepIndex = SnapshotShapePolicies.constantInHandArg();
        for (int discard = arity - 1 - keepIndex; discard > 0; discard--) stack.takeLast();
        Integer kept = stack.takeLast();
        return kept != null ? kept : 0;
    }

}
