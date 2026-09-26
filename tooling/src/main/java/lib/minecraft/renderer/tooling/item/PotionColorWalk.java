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
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.util.Map;
import java.util.TreeMap;

/**
 * Walks {@code MobEffects.<clinit>} and populates the {@code effects} node. Three shape
 * heuristics decode the effect colour table:
 * <ol>
 *   <li>the first LDC string since the last {@code register} is the effect id; every later string
 *       is an attribute-modifier id ({@code effect.speed});</li>
 *   <li>a fresh {@code NEW net/minecraft/world/effect/*} resets the int stack, so only the
 *       literals up to its {@code <init>} count as colour candidates;</li>
 *   <li>the colour rides the {@code (MobEffectCategory, int)V} ctor's trailing int, matched on the
 *       owner prefix so {@code MobEffect} subclasses decode too.</li>
 * </ol>
 *
 * <p>An effect whose colour was never captured (a non-standard ctor) emits a
 * {@link Diagnostics#warn} rather than being silently dropped. Output is sorted by effect id;
 * colour forced fully opaque.
 */
@UtilityClass
public final class PotionColorWalk {

    /**
     * Walks the effect colour table and populates {@code root}'s {@code effects} node.
     *
     * @param run the live run
     * @param root the envelope root
     */
    public static void run(@NotNull ToolingRun run, @NotNull JsonTree root) {
        ClassNodeCache cache = run.cache();
        Diagnostics diagnostics = run.diagnostics().child("effects");

        AsmWalker clinit = AsmWalker.over(cache, SourceClasses.Types.MOB_EFFECTS, ClassKit.CLINIT);
        Absent missing = clinit.missing();
        if (missing != null) {
            if (missing == Absent.CLASS)
                diagnostics.error("'%s' class missing - %s unresolved", SourceClasses.Types.MOB_EFFECTS, "effect colour table");
            else
                diagnostics.error("'%s.%s' missing - %s unresolved", SourceClasses.Types.MOB_EFFECTS, ClassKit.CLINIT, "effect colour table");
            return;
        }

        String colorCtorDesc = SourceClasses.Descs.of("V",
            SourceClasses.Descs.ref(SourceClasses.Types.MOB_EFFECT_CATEGORY), "I");

        Map<String, Integer> colors = new TreeMap<>();

        Cells.Latch<String> pendingEffectId = Cells.latch();
        Cells.Latch<Integer> pendingColor = Cells.latch();
        Cells.Window<Integer> intStack = Cells.window(AsmWalker::intLiteral, 8);

        clinit
            .feed(intStack)
            .feed(pendingEffectId)
            .feed(pendingColor)
            .on(Insn.of(AbstractInsnNode.class, node -> AsmWalker.stringLiteral(node) != null), node -> {
                if (pendingEffectId.get() == null) pendingEffectId.set(AsmWalker.stringLiteral(node));
            })
            .on(Insn.new_(SourceClasses.Types.EFFECT_PACKAGE_PREFIX), instance -> intStack.clear())
            .on(Insn.of(MethodInsnNode.class, init -> init.getOpcode() == Opcodes.INVOKESPECIAL
                && init.name.equals(ClassKit.INIT)
                && init.owner.startsWith(SourceClasses.Types.EFFECT_PACKAGE_PREFIX)
                && init.desc.equals(colorCtorDesc)), init -> {
                Integer top = intStack.takeLast();
                if (top != null) pendingColor.set(top);
            })
            .commitAt(Insn.invokeStatic(SourceClasses.Types.MOB_EFFECTS, SourceClasses.Methods.REGISTER),
                register -> {
                    String effectId = pendingEffectId.get();
                    if (effectId == null) return;
                    Integer color = pendingColor.get();
                    if (color != null)
                        colors.put(SourceClasses.Paths.MINECRAFT_NAMESPACE + effectId, color);
                    else
                        diagnostics.warn("effect '%s' registered without a decodable (MobEffectCategory, int) colour ctor", effectId);
                })
            .run();

        JsonTree effects = root.child("effects");
        colors.forEach((effectId, color) -> effects.putHex(effectId, color | 0xFF000000));
        diagnostics.info("%d effect colour rows from %s.<clinit>", colors.size(), SourceClasses.Types.MOB_EFFECTS);
    }

}
