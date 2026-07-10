package lib.minecraft.renderer.tooling2.snapshot;

import lib.minecraft.renderer.tooling2.kernel.AsmKit;
import lib.minecraft.renderer.tooling2.kernel.ClassNodeCache;
import lib.minecraft.renderer.tooling2.kernel.Diagnostics;
import lib.minecraft.renderer.tooling2.kernel.JsonNode;
import lib.minecraft.renderer.tooling2.kernel.ToolingSession;
import lib.minecraft.renderer.tooling2.kernel.VanillaSourceClasses;
import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.TreeSet;

/**
 * Walks {@code Items.<clinit>} and owns the {@code items} node (SPINE 3.5) - the always-foil
 * items, those whose registration sets {@code DataComponents.ENCHANTMENT_GLINT_OVERRIDE = true}.
 * Shape knowledge (P46):
 * <ul>
 *   <li>{@code GLINT_TRUE_ADJACENCY} - {@code GETSTATIC ENCHANTMENT_GLINT_OVERRIDE} followed by
 *       {@code iconst_1} is {@code component(..., true)};</li>
 *   <li>{@code GLINT_ITEM_FIELD_RESET} - the {@code PUTSTATIC Items.<field>:LItem;} registration
 *       terminator commits the pending {@code (id, glint)} pair and resets, so glint set on one
 *       item never bleeds into the next.</li>
 * </ul>
 * The item id is the first LDC string since the last registration. Output is sorted namespaced
 * ids (7 at 26.1).
 */
public final class GlintItemsWalk {

    private GlintItemsWalk() {
    }

    /**
     * Walks the always-glinted item set and populates {@code root}'s {@code items} node.
     *
     * @param session the live session
     * @param root the envelope root
     */
    public static void run(@NotNull ToolingSession session, @NotNull JsonNode root) {
        ClassNodeCache cache = session.cache();
        Diagnostics diagnostics = session.diagnostics().child("items");

        ClassNode items = cache.load(VanillaSourceClasses.Types.ITEMS);
        if (items == null) {
            diagnostics.error("'%s' class missing - glint set unresolved", VanillaSourceClasses.Types.ITEMS);
            return;
        }
        MethodNode clinit = AsmKit.findMethod(items, AsmKit.CLINIT);
        if (clinit == null) {
            diagnostics.error("'%s.<clinit>' missing - glint set unresolved", VanillaSourceClasses.Types.ITEMS);
            return;
        }

        String itemFieldDesc = VanillaSourceClasses.Descs.ref(VanillaSourceClasses.Types.ITEM);
        TreeSet<String> glintItems = new TreeSet<>();

        String pendingItemId = null;
        boolean pendingGlint = false;

        for (AbstractInsnNode node = clinit.instructions.getFirst(); node != null; node = node.getNext()) {
            String string = AsmKit.readStringLiteral(node);
            if (string != null) {
                if (pendingItemId == null) pendingItemId = string;
                continue;
            }

            if (AsmKit.isGetStatic(node, VanillaSourceClasses.Types.DATA_COMPONENTS, VanillaSourceClasses.Fields.ENCHANTMENT_GLINT_OVERRIDE)) {
                AbstractInsnNode next = AsmKit.nextReal(node);
                Integer value = next == null ? null : AsmKit.readIntLiteral(next);
                if (value != null && value == 1) pendingGlint = true;
                continue;
            }

            if (AsmKit.isPutStatic(node, VanillaSourceClasses.Types.ITEMS)
                && node instanceof FieldInsnNode field
                && field.desc.equals(itemFieldDesc)) {
                if (pendingGlint && pendingItemId != null)
                    glintItems.add(VanillaSourceClasses.Paths.MINECRAFT_NAMESPACE + pendingItemId);
                pendingItemId = null;
                pendingGlint = false;
            }
        }

        JsonNode itemsNode = root.childArray("items");
        glintItems.forEach(itemsNode::add);
        diagnostics.info("%d always-glinted items from %s.<clinit>", glintItems.size(), VanillaSourceClasses.Types.ITEMS);
    }

}
