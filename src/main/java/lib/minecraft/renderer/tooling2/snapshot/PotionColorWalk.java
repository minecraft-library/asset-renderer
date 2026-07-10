package lib.minecraft.renderer.tooling2.snapshot;

import lib.minecraft.renderer.tooling2.kernel.AsmKit;
import lib.minecraft.renderer.tooling2.kernel.ClassNodeCache;
import lib.minecraft.renderer.tooling2.kernel.Diagnostics;
import lib.minecraft.renderer.tooling2.kernel.JsonNode;
import lib.minecraft.renderer.tooling2.kernel.ToolingSession;
import lib.minecraft.renderer.tooling2.kernel.VanillaSourceClasses;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.Map;
import java.util.TreeMap;

/**
 * Walks {@code MobEffects.<clinit>} and owns the {@code effects} node (SPINE 3.5). The three
 * legacy shape heuristics survive as declared policies (P46, their comments as provenance):
 * <ol>
 *   <li>{@code POTION_EFFECT_ID_FIRST_LDC} - the first LDC string since the last {@code register}
 *       is the effect id; later strings are attribute-modifier ids;</li>
 *   <li>{@code POTION_NEW_RESETS_STACK} - a fresh {@code NEW net/minecraft/world/effect/*} resets
 *       the int stack so only literals up to its {@code <init>} count as colour candidates;</li>
 *   <li>{@code POTION_COLOR_CTOR} - the colour rides the {@code (MobEffectCategory, int)V} ctor's
 *       trailing int, prefix-owner matched for {@code MobEffect} subclasses.</li>
 * </ol>
 *
 * <p>Record-not-drop (09 SS6): an effect whose colour was never captured (a non-standard ctor -
 * silently vanished in the legacy) emits a {@link Diagnostics#warn} rather than an in-file row
 * (SPINE 3.5). Output is sorted by effect id; colour forced fully opaque.
 */
public final class PotionColorWalk {

    private PotionColorWalk() {
    }

    /**
     * Walks the effect colour table and populates {@code root}'s {@code effects} node.
     *
     * @param session the live session
     * @param root the envelope root
     */
    public static void run(@NotNull ToolingSession session, @NotNull JsonNode root) {
        ClassNodeCache cache = session.cache();
        Diagnostics diagnostics = session.diagnostics().child("effects");

        ClassNode mobEffects = cache.load(VanillaSourceClasses.Types.MOB_EFFECTS);
        if (mobEffects == null) {
            diagnostics.error("'%s' class missing - effect colour table unresolved", VanillaSourceClasses.Types.MOB_EFFECTS);
            return;
        }
        MethodNode clinit = AsmKit.findMethod(mobEffects, AsmKit.CLINIT);
        if (clinit == null) {
            diagnostics.error("'%s.<clinit>' missing - effect colour table unresolved", VanillaSourceClasses.Types.MOB_EFFECTS);
            return;
        }

        String colorCtorDesc = VanillaSourceClasses.Descs.of("V",
            VanillaSourceClasses.Descs.ref(VanillaSourceClasses.Types.MOB_EFFECT_CATEGORY), "I");

        Map<String, Integer> colors = new TreeMap<>();

        String pendingEffectId = null;
        Integer pendingColor = null;
        AsmKit.LiteralStack intStack = new AsmKit.LiteralStack(8);

        for (AbstractInsnNode node = clinit.instructions.getFirst(); node != null; node = node.getNext()) {
            Integer literal = AsmKit.readIntLiteral(node);
            if (literal != null) {
                intStack.push(literal);
                continue;
            }

            String string = AsmKit.readStringLiteral(node);
            if (string != null) {
                if (pendingEffectId == null) pendingEffectId = string;
                continue;
            }

            if (AsmKit.isNewInstance(node, VanillaSourceClasses.Types.EFFECT_PACKAGE_PREFIX)) {
                intStack.reset();
                continue;
            }

            if (node.getOpcode() == Opcodes.INVOKESPECIAL
                && node instanceof MethodInsnNode init
                && init.name.equals(AsmKit.INIT)
                && init.owner.startsWith(VanillaSourceClasses.Types.EFFECT_PACKAGE_PREFIX)
                && init.desc.equals(colorCtorDesc)) {
                Integer top = intStack.popInt();
                if (top != null) pendingColor = top;
                continue;
            }

            if (AsmKit.isInvokeStatic(node, VanillaSourceClasses.Types.MOB_EFFECTS, VanillaSourceClasses.Methods.REGISTER)) {
                if (pendingEffectId != null) {
                    if (pendingColor != null)
                        colors.put(VanillaSourceClasses.Paths.MINECRAFT_NAMESPACE + pendingEffectId, pendingColor);
                    else
                        diagnostics.warn("effect '%s' registered without a decodable (MobEffectCategory, int) colour ctor", pendingEffectId);
                }
                pendingEffectId = null;
                pendingColor = null;
                intStack.reset();
            }
        }

        JsonNode effects = root.childArray("effects");
        colors.forEach((effectId, color) -> effects.add(row(effectId, color)));
        diagnostics.info("%d effect colour rows from %s.<clinit>", colors.size(), VanillaSourceClasses.Types.MOB_EFFECTS);
    }

    /** One {@code {effect, color}} row with the colour forced fully opaque. */
    private static @NotNull JsonNode row(@NotNull String effectId, int color) {
        JsonNode row = JsonNode.object().put("effect", effectId);
        row.putHex("color", color | 0xFF000000);
        return row;
    }

}
