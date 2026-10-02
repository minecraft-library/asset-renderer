package lib.minecraft.renderer.tooling.item;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.diagnostic.Diagnostics;
import lib.minecraft.renderer.tooling.asm.ClassKit;
import lib.minecraft.renderer.tooling.asm.ClassNodeCache;
import lib.minecraft.renderer.tooling.asm.Insn;
import lib.minecraft.renderer.tooling.interp.Absent;
import lib.minecraft.renderer.tooling.interp.Cells;
import lib.minecraft.renderer.tooling.names.SourceClasses;
import lib.minecraft.renderer.tooling.run.ToolingRun;
import lib.minecraft.renderer.tooling.walk.AsmWalker;
import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;

import java.util.TreeSet;

/**
 * Walks {@code Items.<clinit>} and populates the {@code items} node with the always-foil
 * items, those whose registration sets {@code DataComponents.ENCHANTMENT_GLINT_OVERRIDE = true}.
 * <ul>
 *   <li>A {@code GETSTATIC ENCHANTMENT_GLINT_OVERRIDE} immediately followed by {@code iconst_1} is
 *       {@code component(..., true)};</li>
 *   <li>the {@code PUTSTATIC Items.<field>:LItem;} registration terminator commits the pending
 *       {@code (id, glint)} pair and resets, so glint set on one item never bleeds into the
 *       next.</li>
 * </ul>
 * The item id is the first LDC string since the last registration. Output is sorted namespaced
 * ids (7 at 26.1).
 */
@UtilityClass
public final class GlintItemsWalk {

    /**
     * Walks the always-glinted item set and populates {@code root}'s {@code items} node.
     *
     * @param run the live run
     * @param root the envelope root
     */
    public static void run(@NotNull ToolingRun run, @NotNull JsonTree root) {
        ClassNodeCache cache = run.cache();
        Diagnostics diagnostics = run.diagnostics().child("items");

        AsmWalker clinit = AsmWalker.clinit(cache, SourceClasses.Types.ITEMS);
        Absent missing = clinit.missing();
        if (missing == Absent.CLASS) {
            diagnostics.error("'%s' class missing - %s unresolved", SourceClasses.Types.ITEMS, "glint set");
            return;
        }
        if (missing == Absent.MEMBER) {
            diagnostics.error("'%s.%s' missing - %s unresolved", SourceClasses.Types.ITEMS, ClassKit.CLINIT, "glint set");
            return;
        }

        String itemFieldDesc = SourceClasses.Descs.ref(SourceClasses.Types.ITEM);
        TreeSet<String> glintItems = new TreeSet<>();

        Cells.Latch<String> pendingItemId = Cells.latch();
        Cells.Flag pendingGlint = Cells.flag();

        clinit.feed(pendingItemId)
            .feed(pendingGlint)
            .on(Insn.of(LdcInsnNode.class, ldc -> ldc.cst instanceof String), ldc -> {
                if (pendingItemId.get() == null) pendingItemId.set((String) ldc.cst);
            })
            .on(Insn.getStatic(SourceClasses.Types.DATA_COMPONENTS, SourceClasses.Fields.ENCHANTMENT_GLINT_OVERRIDE), get -> {
                AbstractInsnNode next = AsmWalker.nextReal(get);
                Integer value = AsmWalker.intLiteral(next);
                if (value != null && value == 1) pendingGlint.set();
            })
            .commitAt(Insn.putStatic(SourceClasses.Types.ITEMS).and(put -> put.desc.equals(itemFieldDesc)), put -> {
                if (pendingGlint.get() && pendingItemId.get() != null)
                    glintItems.add(SourceClasses.Paths.MINECRAFT_NAMESPACE + pendingItemId.get());
            })
            .run();

        JsonTree itemsNode = root.childArray("items");
        glintItems.forEach(itemsNode::add);
        diagnostics.info("%d always-glinted items from %s.<clinit>", glintItems.size(), SourceClasses.Types.ITEMS);
    }

}
