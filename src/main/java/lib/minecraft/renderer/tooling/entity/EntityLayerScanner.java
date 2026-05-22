package lib.minecraft.renderer.tooling.entity;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.tooling.util.AsmKit;
import lib.minecraft.renderer.tooling.util.Diagnostics;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.zip.ZipFile;

/**
 * Discovers the {@code net.minecraft.client.renderer.entity.layers.RenderLayer}
 * subclasses an {@code EntityRenderer} composes onto its base mesh via
 * {@code addLayer(new XLayer(...))} calls in its constructor (and parent-chain constructors).
 * These are the source of overlay layers in the runtime
 * {@link Entity#getOverlays Entity.overlays} list:
 *
 * <ul>
 *   <li>{@code HumanoidArmorLayer} - armor overlays for humanoid mobs (zombie / skeleton / piglin / etc.)</li>
 *   <li>{@code EyesLayer} subclasses - emissive eye PNGs (spider / enderman / breeze / phantom / ...)</li>
 *   <li>Per-mob overlay layers - {@code CreeperPowerLayer}, {@code SheepWoolLayer},
 *       {@code CopperGolemFlowerLayer}, etc.</li>
 * </ul>
 *
 * <p>The scanner records each layer's JVM internal name in source order. Walking <i>into</i>
 * each layer class to extract its texture binding and geometry contribution is Phase C+ work;
 * Phase B's diagnostic JSON only needs the enumeration so coverage gaps surface early.
 *
 * <p>Pattern recognised:
 * <pre>
 *   NEW LayerClass
 *   DUP
 *   ... constructor args ...
 *   INVOKESPECIAL LayerClass.&lt;init&gt;
 *   INVOKEVIRTUAL addLayer(RenderLayer)Z
 *   POP
 * </pre>
 * The scanner pairs each {@code INVOKEVIRTUAL addLayer} with the most recent {@code NEW
 * LayerClass} (after walking past the constructor args), which is robust to renderer code that
 * fans into helper methods or builds layer args via further constructor chains.
 */
@UtilityClass
public final class EntityLayerScanner {

    /**
     * Method name used by every {@code LivingEntityRenderer.addLayer} call.
     */
    private static final @NotNull String ADD_LAYER = "addLayer";

    /**
     * Walks the renderer class's constructors, plus every superclass constructor, for
     * {@code addLayer(new XLayer(...))} call sites. Returns the unique set of layer class
     * internal names in insertion order so downstream emission is stable.
     *
     * @param zip the deobfuscated client jar
     * @param rendererInternalName the renderer's JVM internal name
     * @param diagnostics the diagnostic sink shared with sibling discovery walks
     * @return the unique set of layer class internal names attached by this renderer
     */
    public static @NotNull ConcurrentList<String> scan(
        @NotNull ZipFile zip,
        @NotNull String rendererInternalName,
        @NotNull Diagnostics diagnostics
    ) {
        Set<String> seen = new LinkedHashSet<>();
        AsmKit.walkConstructorChain(zip, rendererInternalName, method -> scanMethod(method, seen));
        ConcurrentList<String> out = Concurrent.newList();
        seen.forEach(out::add);
        return out;
    }

    /**
     * Scans one constructor for {@code INVOKEVIRTUAL addLayer} calls. For each, walks backwards
     * to find the most recent {@code NEW XLayer} TypeInsnNode and records its {@code desc}.
     */
    private static void scanMethod(@NotNull MethodNode method, @NotNull Set<String> out) {
        for (AbstractInsnNode in = method.instructions.getFirst(); in != null; in = in.getNext()) {
            // Owner-agnostic addLayer match - the renderer's super may be any of several
            // LivingEntityRenderer subclasses, so AsmKit's owner-qualified isInvokeVirtual is
            // too narrow. Inline the predicate and gate on the canonical descriptor shape
            // (single Layer arg, boolean return).
            if (in.getOpcode() != Opcodes.INVOKEVIRTUAL) continue;
            if (!(in instanceof MethodInsnNode mi)) continue;
            if (!ADD_LAYER.equals(mi.name)) continue;
            if (!mi.desc.startsWith("(L") || !mi.desc.endsWith(";)Z")) continue;

            String layerClass = findPrecedingLayerNew(in);
            if (layerClass != null) out.add(layerClass);
        }
    }

    /**
     * Walks backwards from an {@code addLayer} call site looking for the most recent
     * {@code NEW XLayer}. Bounded at 64 instructions so a long tangle of nested constructor
     * args doesn't infinite-loop into earlier addLayer constructions.
     */
    private static String findPrecedingLayerNew(@NotNull AbstractInsnNode addLayerInsn) {
        AbstractInsnNode cursor = addLayerInsn.getPrevious();
        int depth = 0;
        // Track NEW / INVOKESPECIAL <init> nesting so the matching NEW for the OUTER layer
        // construction is the one we return. Forward-order construction emits {NEW outer; ... NEW
        // inner_arg; INVOKESPECIAL inner_arg.<init>; INVOKESPECIAL outer.<init>; addLayer}, so in
        // reverse we see the INVOKESPECIALs first (each pushes pendingInits +1) and the NEWs after
        // (each pops pendingInits -1). Start at 0 because the outer addLayer call itself
        // doesn't need any NEW unbalanced - the first <init> we see going back is the outer's
        // and arms the search for one matching NEW.
        int pendingInits = 0;
        while (cursor != null && depth < 64) {
            depth++;
            if (cursor.getOpcode() == Opcodes.INVOKESPECIAL
                && cursor instanceof MethodInsnNode mi
                && "<init>".equals(mi.name))
                pendingInits++;
            if (cursor.getOpcode() == Opcodes.NEW && cursor instanceof TypeInsnNode type) {
                pendingInits--;
                if (pendingInits == 0) return type.desc;
            }
            cursor = cursor.getPrevious();
        }
        return null;
    }

}
