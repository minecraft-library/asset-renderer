package lib.minecraft.renderer.tooling.blockentity;

import lib.minecraft.renderer.tooling.kernel.AsmKit;
import lib.minecraft.renderer.tooling.kernel.ClassNodeCache;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lib.minecraft.renderer.tooling.kernel.ToolingSession;
import lib.minecraft.renderer.tooling.kernel.VanillaSourceClasses;
import lib.minecraft.renderer.tooling.walk.AsmWalker;
import lib.minecraft.renderer.tooling.walk.Cells;
import lib.minecraft.renderer.tooling.walk.Insn;
import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Single-pass discovery of every registered block-entity type joined with its renderer -
 * the two-{@code <clinit>} registry join. {@code BlockEntityRenderers.<clinit>} registration
 * order == on-disk subject order.
 *
 * <p>Two independent walks feed the join:
 * <ol>
 *   <li><b>{@code BlockEntityType.<clinit>} scan.</b> {@code LDC "<id>"} +
 *       {@code GETSTATIC Blocks.X}* + {@code PUTSTATIC BlockEntityType.<FIELD>} pairs each
 *       type field with its registry id and its ordered valid-blocks fields.</li>
 *   <li><b>{@code BlockEntityRenderers.<clinit>} scan.</b> {@code GETSTATIC BlockEntityType.X}
 *       paired with the next {@code INVOKEDYNAMIC}, resolving the lambda handle to the
 *       renderer class - the id-to-renderer pairing is EXPOSED on the subject rather than
 *       hard-coded per caller.</li>
 * </ol>
 *
 * <p>Shared-renderer dedupe: chest / trapped_chest / ender_chest all register
 * {@code ChestRenderer}; only the first registration (chest) becomes a subject - the rest
 * share the renderer + geometry, and the chest family split (BlockFamilyPolicies) fans the
 * blocks back out at catalog time.
 */
public final class BlockEntityRegistryDiscovery {

    private BlockEntityRegistryDiscovery() {
    }

    /**
     * Walks both registries, joins the type registrations against the renderer registrations,
     * and returns the joined subjects in {@code BlockEntityRenderers} registration order.
     *
     * @param session the live session
     * @return the joined subjects in registry order
     */
    public static @NotNull List<BlockEntitySubject> discover(@NotNull ToolingSession session) {
        ClassNodeCache cache = session.cache();
        Diagnostics diagnostics = session.diagnostics().child("discovery");

        Map<String, TypeRegistration> types = collectTypeRegistrations(cache, diagnostics);
        Map<String, String> renderers = collectRendererRegistrations(cache, diagnostics);

        // Shared-renderer collapse: one subject per renderer class, at its first-registration
        // position (chest wins over trapped/ender - same geometry). The deduped types' valid
        // blocks are MERGED into the surviving subject in registration order so the catalog
        // fans the full family back out (the union of validBlocks across chest, trapped_chest,
        // and ender_chest).
        Map<String, Pending> byRenderer = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : renderers.entrySet()) {
            String typeField = entry.getKey();
            String rendererClass = entry.getValue();
            TypeRegistration type = types.get(typeField);
            if (type == null) {
                diagnostics.warn("renderer '%s' registered for BlockEntityType.%s but the type field has no LDC id - skipped",
                    rendererClass, typeField);
                continue;
            }
            Pending pending = byRenderer.get(rendererClass);
            if (pending == null)
                byRenderer.put(rendererClass, pending = new Pending(
                    VanillaSourceClasses.Paths.MINECRAFT_NAMESPACE + type.id(), rendererClass));
            pending.blockFields.addAll(type.blockFields());
        }

        List<BlockEntitySubject> subjects = new ArrayList<>();
        for (Pending pending : byRenderer.values())
            subjects.add(new BlockEntitySubject(pending.id, pending.rendererClass, List.copyOf(pending.blockFields)));

        diagnostics.info("discovered %d block-entity types, %d subjects after shared-renderer dedupe",
            types.size(), subjects.size());
        return subjects;
    }

    /**
     * Walks {@code BlockEntityType.<clinit>} once and returns a map from type field name to its
     * registration (namespace-less id + ordered valid-blocks fields), in static-initializer
     * order. Each {@code LDC "<id>"} opens a fresh registration (clearing the block list); the
     * {@code GETSTATIC Blocks.X} references between it and the closing
     * {@code PUTSTATIC BlockEntityType.<field>} are the type's valid blocks in bytecode order.
     */
    private static @NotNull Map<String, TypeRegistration> collectTypeRegistrations(
        @NotNull ClassNodeCache cache,
        @NotNull Diagnostics diagnostics
    ) {
        ClassNode blockEntityType = cache.load(VanillaSourceClasses.Types.BLOCK_ENTITY_TYPE);
        if (blockEntityType == null) {
            diagnostics.error("'%s' class missing - cannot discover block-entity types", VanillaSourceClasses.Types.BLOCK_ENTITY_TYPE);
            return Map.of();
        }
        MethodNode clinit = AsmKit.findMethod(blockEntityType, AsmKit.CLINIT);
        if (clinit == null) {
            diagnostics.error("'%s.<clinit>' missing - cannot discover block-entity types", VanillaSourceClasses.Types.BLOCK_ENTITY_TYPE);
            return Map.of();
        }

        Map<String, TypeRegistration> out = new LinkedHashMap<>();
        Cells.Latch<String> pendingId = Cells.latch();
        Cells.ListCell<String> pendingBlocks = Cells.list();

        // The armed test rides the commit recognizer: an id-less PUTSTATIC is not a
        // registration, so it neither emits nor resets the cells.
        AsmWalker.over(clinit)
            .feed(pendingId)
            .feed(pendingBlocks)
            .on(Insn.of(LdcInsnNode.class, ldc -> ldc.cst instanceof String), ldc -> {
                pendingId.set((String) ldc.cst);
                pendingBlocks.clear();
            })
            .on(Insn.getStatic(VanillaSourceClasses.Types.BLOCKS), get -> {
                if (pendingId.get() != null) pendingBlocks.add(get.name);
            })
            .commitAt(Insn.putStatic(VanillaSourceClasses.Types.BLOCK_ENTITY_TYPE).and(put -> pendingId.get() != null),
                put -> {
                    String id = pendingId.get();
                    if (id != null) out.put(put.name, new TypeRegistration(id, pendingBlocks.values()));
                })
            .run();
        return out;
    }

    /**
     * Walks {@code BlockEntityRenderers.<clinit>} and returns a map from
     * {@code BlockEntityType} field name to the resolved renderer class internal name, in
     * registration order. Each registration is {@code GETSTATIC BlockEntityType.X} followed by
     * an {@code INVOKEDYNAMIC} producing the renderer provider; the walk carries the field name
     * forward and pairs it with the next indy (the trailing {@code register} call is passed
     * over - the pairing commits on the indy). Unresolvable lambdas log INFO and leave the type
     * unmapped (it surfaces as the WARN in {@link #discover}).
     */
    private static @NotNull Map<String, String> collectRendererRegistrations(
        @NotNull ClassNodeCache cache,
        @NotNull Diagnostics diagnostics
    ) {
        ClassNode registryClass = cache.load(VanillaSourceClasses.Types.BLOCK_ENTITY_RENDERERS);
        if (registryClass == null) {
            diagnostics.error("'%s' class missing - cannot discover block-entity renderers", VanillaSourceClasses.Types.BLOCK_ENTITY_RENDERERS);
            return Map.of();
        }
        MethodNode clinit = AsmKit.findMethod(registryClass, AsmKit.CLINIT);
        if (clinit == null) {
            diagnostics.error("'%s.<clinit>' missing - cannot discover block-entity renderers", VanillaSourceClasses.Types.BLOCK_ENTITY_RENDERERS);
            return Map.of();
        }

        Map<String, String> out = new LinkedHashMap<>();
        AsmWalker.over(clinit)
            .latch(in -> AsmWalker.isGetStatic(in, VanillaSourceClasses.Types.BLOCK_ENTITY_TYPE)
                ? ((FieldInsnNode) in).name : null)
            .commitAt(Insn.ofType(InvokeDynamicInsnNode.class))
            .forEach(commit -> {
                String typeField = commit.value();
                if (typeField == null) return;
                String rendererClass = AsmWalker.resolveLambdaTargetClass(commit.node(), registryClass);
                if (rendererClass != null)
                    out.put(typeField, rendererClass);
                else
                    diagnostics.info("BlockEntityRenderers.<clinit>: could not resolve renderer class for BlockEntityType.%s - skipped", typeField);
            });
        return out;
    }

    /**
     * {@code BlockEntityType.<clinit>}-derived registration metadata paired with each type
     * field.
     *
     * @param id the namespace-less registry id
     * @param blockFields the ordered {@code Blocks} static-field names in {@code validBlocks}
     */
    private record TypeRegistration(@NotNull String id, @NotNull List<String> blockFields) {}

    /** A subject under construction, accumulating the block fields of every type sharing its renderer. */
    private static final class Pending {

        private final @NotNull String id;
        private final @NotNull String rendererClass;
        private final @NotNull List<String> blockFields = new ArrayList<>();

        private Pending(@NotNull String id, @NotNull String rendererClass) {
            this.id = id;
            this.rendererClass = rendererClass;
        }

    }

}
