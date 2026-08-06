package lib.minecraft.renderer.tooling.vanilla;

import lib.minecraft.renderer.tooling.kernel.ClassKit;
import lib.minecraft.renderer.tooling.kernel.ClassNodeCache;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lib.minecraft.renderer.tooling.kernel.ToolingSession;
import lib.minecraft.renderer.tooling.kernel.VanillaSourceClasses;
import lib.minecraft.renderer.tooling.walk.AsmWalker;
import lib.minecraft.renderer.tooling.walk.Cells;
import lib.minecraft.renderer.tooling.walk.Insn;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The {@code Blocks.<clinit>} registration walk, built once per session - the single shared
 * block-id derivation used in place of a namespace-less {@code field.toLowerCase()} (no
 * {@code Locale.ROOT}) derivation and a separate register-literal mapping.
 *
 * <p>Both id arms live here: the {@code register("id", ...)} string-literal form and the 26.x
 * {@code register(ResourceKey, Function, Properties)} form whose id comes from a
 * {@code GETSTATIC BlockIds.FOO} mapped back through {@code BlockIds.<clinit>}. The
 * constructor-reference adjacency guard ({@code isPendingIdSource}) and the
 * registration-helper lambda resolution ({@code registerBed}-class helpers) handle both forms.
 */
public final class BlockRegistryIndex {

    /** {@code Blocks.register*} overload prefix (vanilla member-name pattern, method-local grammar). */
    private static final @NotNull String REGISTER_PREFIX = "register";

    private static final @NotNull String BLOCK_RETURN_SUFFIX =
        ")" + VanillaSourceClasses.Descs.ref(VanillaSourceClasses.Types.BLOCK);
    private static final @NotNull String STRING_ARG_PREFIX = "(Ljava/lang/String;";
    private static final @NotNull String RESOURCE_KEY_ARG_PREFIX =
        "(" + VanillaSourceClasses.Descs.ref(VanillaSourceClasses.Types.RESOURCE_KEY);
    private static final @NotNull String FUNCTION_RETURN_SUFFIX = ")Ljava/util/function/Function;";

    /**
     * One registered block: the {@code Blocks} static field, its namespaced id, and the block
     * class its registration instantiates.
     *
     * @param field the {@code Blocks} static field name, e.g. {@code CHEST}
     * @param id the namespaced block id, e.g. {@code minecraft:chest}
     * @param blockClass the instantiated block class's JVM internal name, or {@code null}
     *     when the registration shape hides it (helper without a resolvable inner lambda) -
     *     consumers route these to their unresolved channel
     */
    public record Entry(@NotNull String field, @NotNull String id, @Nullable String blockClass) {}

    private final @NotNull Map<String, Entry> byField;

    private BlockRegistryIndex(@NotNull Map<String, Entry> byField) {
        this.byField = byField;
    }

    /**
     * Walks {@code BlockIds.<clinit>} + {@code Blocks.<clinit>} once and builds the index.
     * The registry walk is a state machine over the instruction stream carrying the pending
     * id (LDC literal or mapped {@code BlockIds} field) and the block class resolved from the
     * {@code Foo::new} constructor-reference indy that immediately follows the id source;
     * both commit at the following {@code Blocks.register*} call.
     *
     * @param session the live session
     * @return the built index (empty on a missing {@code Blocks} class, ERROR recorded)
     */
    public static @NotNull BlockRegistryIndex build(@NotNull ToolingSession session) {
        ClassNodeCache cache = session.cache();
        Diagnostics diagnostics = session.diagnostics().child("blockRegistry");
        Map<String, Entry> byField = new LinkedHashMap<>();

        Map<String, String> blockIdsNames = buildBlockIdsMap(cache);

        ClassNode blocks = cache.load(VanillaSourceClasses.Types.BLOCKS);
        if (blocks == null) {
            diagnostics.error("'%s' class missing - block registry index unresolved", VanillaSourceClasses.Types.BLOCKS);
            return new BlockRegistryIndex(byField);
        }
        MethodNode clinit = ClassKit.findMethod(blocks, ClassKit.CLINIT);
        if (clinit == null) {
            diagnostics.error("'%s.<clinit>' missing - block registry index unresolved", VanillaSourceClasses.Types.BLOCKS);
            return new BlockRegistryIndex(byField);
        }

        Map<String, String> helperClassCache = new LinkedHashMap<>();
        Cells.Latch<String> pendingId = Cells.latch();
        Cells.Latch<String> ctorClass = Cells.latch();

        AsmWalker.over(clinit)
            .feed(pendingId)
            .feed(ctorClass)
            .on(Insn.of(LdcInsnNode.class, ldc -> ldc.cst instanceof String), ldc -> {
                pendingId.set((String) ldc.cst);
                ctorClass.clear();
            })
            // The 26.x register(ResourceKey, Function, Properties) overload sources its id from
            // a GETSTATIC BlockIds.FOO rather than a string literal (stems, copper bars /
            // chains / lanterns, item frames). Map the field back to its serialised id so these
            // registrations index like the string-literal form; an unmapped field leaves the
            // prior pending id armed.
            .on(Insn.getStatic(VanillaSourceClasses.Types.BLOCK_IDS), field -> {
                String mapped = blockIdsNames.get(field.name);
                if (mapped != null) {
                    pendingId.set(mapped);
                    ctorClass.clear();
                }
            })
            .on(Insn.of(InvokeDynamicInsnNode.class, indy -> indy.desc.endsWith(FUNCTION_RETURN_SUFFIX)), indy -> {
                String id = pendingId.get();
                if (id == null || !isPendingIdSource(AsmWalker.previousReal(indy), id, blockIdsNames)) return;
                ctorClass.clear();
                String resolved = AsmWalker.resolveLambdaTargetClass(indy, blocks);
                if (resolved != null) ctorClass.set(resolved);
            })
            .commitAt(Insn.of(MethodInsnNode.class, call -> call.getOpcode() == Opcodes.INVOKESTATIC
                && call.owner.equals(VanillaSourceClasses.Types.BLOCKS)
                && call.name.startsWith(REGISTER_PREFIX)
                && (call.desc.startsWith(STRING_ARG_PREFIX) || call.desc.startsWith(RESOURCE_KEY_ARG_PREFIX))
                && call.desc.endsWith(BLOCK_RETURN_SUFFIX)), call -> {
                String id = pendingId.get();
                if (id == null) return;
                String ctor = ctorClass.get();
                String blockClass = ctor != null ? ctor : resolveHelperClass(blocks, call.name, helperClassCache);
                AbstractInsnNode put = AsmWalker.after(call)
                    .first(Insn.putStatic(VanillaSourceClasses.Types.BLOCKS)::matches,
                        node -> !(node instanceof MethodInsnNode next
                            && next.getOpcode() == Opcodes.INVOKESTATIC
                            && next.owner.equals(VanillaSourceClasses.Types.BLOCKS)
                            && next.name.startsWith(REGISTER_PREFIX)));
                if (put != null) {
                    String fieldName = ((FieldInsnNode) put).name;
                    byField.put(fieldName, new Entry(fieldName, VanillaSourceClasses.Paths.MINECRAFT_NAMESPACE + id, blockClass));
                }
            })
            .run();

        diagnostics.info("indexed %d block registrations from Blocks.<clinit>", byField.size());
        return new BlockRegistryIndex(byField);
    }

    /**
     * The entry registered into a {@code Blocks} static field, or {@code null} when the walk
     * saw no registration for it.
     *
     * @param blocksField the {@code Blocks} static field name
     * @return the entry, or {@code null}
     */
    public @Nullable Entry byField(@NotNull String blocksField) {
        return this.byField.get(blocksField);
    }

    /**
     * The full index, field to entry, in registration ({@code <clinit>}) order (unmodifiable).
     */
    public @NotNull Map<String, Entry> entries() {
        return Collections.unmodifiableMap(this.byField);
    }

    /**
     * Reports whether {@code node} is the id source for {@code pendingId} - either an
     * {@code LDC} of the literal id or a {@code GETSTATIC BlockIds.FOO} whose mapped id
     * matches. Confirms a block-constructor reference indy immediately follows its block-id
     * source rather than a stray {@code Function}-producing lambda elsewhere in the chain.
     */
    private static boolean isPendingIdSource(
        @Nullable AbstractInsnNode node,
        @NotNull String pendingId,
        @NotNull Map<String, String> blockIdsNames
    ) {
        if (node instanceof LdcInsnNode ldc && pendingId.equals(ldc.cst)) return true;
        return node instanceof FieldInsnNode field
            && node.getOpcode() == Opcodes.GETSTATIC
            && field.owner.equals(VanillaSourceClasses.Types.BLOCK_IDS)
            && pendingId.equals(blockIdsNames.get(field.name));
    }

    /**
     * Walks {@code BlockIds.<clinit>} pairing each id string literal with the following
     * {@code PUTSTATIC BlockIds.FOO}. Absent {@code BlockIds} (older jars) leaves the map
     * empty - the {@code register(ResourceKey, ...)} arm then discovers nothing extra.
     */
    private static @NotNull Map<String, String> buildBlockIdsMap(@NotNull ClassNodeCache cache) {
        return AsmWalker.clinit(cache, VanillaSourceClasses.Types.BLOCK_IDS)
            .latch(AsmWalker::stringLiteral)
            .commitAt(FieldInsnNode.class, put -> put.getOpcode() == Opcodes.PUTSTATIC
                && put.owner.equals(VanillaSourceClasses.Types.BLOCK_IDS))
            .toMap(put -> put.name, ids -> ids.isEmpty() ? null : ids.getFirst());
    }

    /**
     * Resolves the block class a registration helper ({@code registerBed}-style) instantiates
     * by walking the helper's body for its first {@code Function}-producing indy and resolving
     * that lambda's target class. Memoized per helper name; the bare {@code register}
     * overloads resolve to {@code null} (the ctor indy arm handles those).
     */
    private static @Nullable String resolveHelperClass(
        @NotNull ClassNode blocks,
        @NotNull String helperName,
        @NotNull Map<String, String> helperClassCache
    ) {
        if (helperName.equals(REGISTER_PREFIX)) return null;
        if (helperClassCache.containsKey(helperName)) return helperClassCache.get(helperName);

        MethodNode helper = ClassKit.findMethod(blocks, helperName);
        String resolved = helper == null ? null : AsmWalker.over(helper)
            .ofType(InvokeDynamicInsnNode.class)
            .where(indy -> indy.desc.endsWith(FUNCTION_RETURN_SUFFIX))
            .mapNotNull(indy -> AsmWalker.resolveLambdaTargetClass(indy, blocks))
            .first();
        helperClassCache.put(helperName, resolved);
        return resolved;
    }

}
