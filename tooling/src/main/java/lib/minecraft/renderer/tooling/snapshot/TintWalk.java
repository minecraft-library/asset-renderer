package lib.minecraft.renderer.tooling.snapshot;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.tooling.kernel.ClassKit;
import lib.minecraft.renderer.tooling.kernel.ClassNodeCache;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lib.minecraft.renderer.tooling.kernel.ToolingException;
import lib.minecraft.renderer.tooling.kernel.ToolingSession;
import lib.minecraft.renderer.tooling.kernel.TraceReplay;
import lib.minecraft.renderer.tooling.kernel.VanillaSourceClasses;
import lib.minecraft.renderer.tooling.policy.AsmContext;
import lib.minecraft.renderer.tooling.policy.Navigation;
import lib.minecraft.renderer.tooling.vanilla.BlockRegistryIndex;
import lib.minecraft.renderer.tooling.walk.AsmWalker;
import lib.minecraft.renderer.tooling.walk.Cells;
import lib.minecraft.renderer.tooling.walk.Insn;
import lib.minecraft.renderer.tooling.walk.Missing;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

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
 * <p>Which {@code constant} argument the in-hand latch keeps is not declared here - it is
 * {@link #resolveInHandArg resolved once per session} off the tint source's own bytecode, through
 * the policy SPI. A jar the resolution cannot read fails the run before a single row is written.
 *
 * <p>Output order is the {@code createDefault()} bytecode walk order (declared in the envelope):
 * tint rows key a {@link LinkedHashMap} by block id (last write wins, first position kept -
 * exactly the legacy {@code tints} map semantics), so the emitted array is byte-order-stable.
 */
@UtilityClass
public final class TintWalk {

    /** {@code java.util.List} - the composed-source factory owner (JDK, never a vanilla name). */
    private static final @NotNull String LIST_INTERNAL = "java/util/List";

    /** {@code List.of} - the source-list factory. */
    private static final @NotNull String LIST_OF = "of";

    /** {@code List.of(Object)} - the single-source overload; any other arity composes sources. */
    private static final @NotNull String LIST_OF_SINGLE_DESC = "(Ljava/lang/Object;)Ljava/util/List;";

    /** The caller label the in-hand re-entries tag their drift message with. */
    private static final @NotNull String IN_HAND_CONTEXT = "the constant tint's in-hand argument";

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

        int inHandArg = resolveInHandArg(session, diagnostics);

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
                    pendingInHand.set(pickInHand(intStack, ClassKit.argTypes(call.desc).length, inHandArg));
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
     * Picks the in-hand constant from the int window: keeps the factory's argument at the resolved
     * index, discarding the higher-index args on top. Takes are from-top-consuming; the keep-read of
     * an empty window answers zero.
     *
     * @param stack the int-literal window the walk fills
     * @param arity the factory call's argument count
     * @param keepIndex the factory argument index the GUI icon reads
     * @return the kept literal, or zero when the window held none
     */
    private static @NotNull Integer pickInHand(@NotNull Cells.Window<Integer> stack, int arity, int keepIndex) {
        for (int discard = arity - 1 - keepIndex; discard > 0; discard--) stack.takeLast();
        Integer kept = stack.takeLast();
        return kept != null ? kept : 0;
    }

    // ------------------------------------------------------------------------------------
    // the in-hand argument - the one row resolved through the policy SPI
    // ------------------------------------------------------------------------------------

    /**
     * Resolves which {@code constant} argument the GUI icon reads, once for the session. The policy
     * is consulted through {@link Navigation}, on a keyless frame because the row is a
     * session-lifetime fact with no subject, and the coordinate it answers is replayed rather than
     * read: the source's captured field names a constructor parameter, and
     * {@link #requireOrderedForwarding} is what carries that parameter's position back out to the
     * factory's own argument list.
     *
     * @param session the live session
     * @param diagnostics the walk's scope
     * @return the factory argument index the walk keeps
     * @throws ToolingException if the row declares no trace, the replay recovers no index, or the factory stops forwarding in order
     */
    static int resolveInHandArg(@NotNull ToolingSession session, @NotNull Diagnostics diagnostics) {
        Navigation.Dataflow coordinate = SnapshotShapePolicies.TINT_CONSTANT_IN_HAND.requireDataflow(
            AsmContext.keyless(session, diagnostics));

        ClassNodeCache cache = session.cache();
        Object recovered = new TraceReplay(cache).replay(coordinate);
        if (!(recovered instanceof Integer parameter))
            throw new ToolingException(
                "Trace at '%s.%s' recovered '%s' where a constructor parameter index was required",
                coordinate.owner(), coordinate.member(), recovered);

        requireOrderedForwarding(cache, coordinate.owner());
        return parameter;
    }

    /**
     * Proves the {@code constant} factory hands its own parameters to the tint source's constructor
     * in declaration order, which is the whole of why a recovered CONSTRUCTOR parameter index is
     * also the FACTORY argument index the int window is read at.
     *
     * <p>No trace step crosses that hop - the vocabulary steps into a callee and never back out to a
     * construction site with argument positions in hand - so the hop is proven here or nowhere. The
     * check is deliberately total: the loads standing between the factory's entry and the
     * construction must be exactly its own parameter slots, in order, so a reordered, computed or
     * inserted argument fails the run ahead of the table write rather than shifting every constant
     * tint by one column. The construction itself is looked up on the class the coordinate anchored
     * at, so a drifted anonymous-class ordinal fails here too.
     *
     * <p>The factory is reached by DESCRIPTOR. {@code constant} is overloaded - a one-colour form is
     * declared first and builds its source through a lambda, constructing nothing - so a name-only
     * lookup answers a method that forwards no parameters at all.
     *
     * @param cache the session cache
     * @param sourceClass the tint source's JVM internal name, as the coordinate names it
     * @throws ToolingException if the factory is absent, builds another class, or forwards out of order
     */
    private static void requireOrderedForwarding(@NotNull ClassNodeCache cache, @NotNull String sourceClass) {
        ClassNode sources = ClassKit.requireClass(cache, VanillaSourceClasses.Types.BLOCK_TINT_SOURCES, IN_HAND_CONTEXT);
        MethodNode factory = ClassKit.requireMethod(sources, VanillaSourceClasses.Methods.CONSTANT,
            VanillaSourceClasses.Descs.CONSTANT_TINT_DESC, IN_HAND_CONTEXT);

        MethodInsnNode construction = AsmWalker.over(factory).first(Insn.of(MethodInsnNode.class,
            call -> call.getOpcode() == Opcodes.INVOKESPECIAL
                && ClassKit.INIT.equals(call.name)
                && call.owner.equals(sourceClass)));
        if (construction == null)
            throw new ToolingException(
                "Factory '%s.%s' constructs no '%s' - the jar is either obfuscated or from an unsupported version",
                VanillaSourceClasses.Types.BLOCK_TINT_SOURCES, VanillaSourceClasses.Methods.CONSTANT, sourceClass);

        List<Integer> forwarded = AsmWalker.over(factory)
            .until(construction)
            .ofType(VarInsnNode.class)
            .map(load -> load.var)
            .toList();

        List<Integer> declared = new ArrayList<>();
        int slot = (factory.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;   // an instance factory holds `this` in slot 0
        for (Type argument : ClassKit.argTypes(factory.desc)) {
            declared.add(slot);
            slot += argument.getSize();
        }

        if (!forwarded.equals(declared))
            throw new ToolingException(
                "Factory '%s.%s' loads slots '%s' before constructing '%s' where its own parameters '%s' were required -"
                    + " the recovered constructor parameter index is not the factory argument index",
                VanillaSourceClasses.Types.BLOCK_TINT_SOURCES, VanillaSourceClasses.Methods.CONSTANT,
                forwarded, sourceClass, declared);
    }

}
