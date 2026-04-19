package dev.sbs.renderer.tooling.blockentity;

import dev.sbs.renderer.tooling.asm.AsmKit;
import dev.simplified.collection.ConcurrentList;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;

/**
 * Bytecode-driven discovery of which entity-model ids should receive a {@code tintindex=0}
 * marker in the generated block-entity elements. A renderer is deemed <i>tint-bearing</i>
 * when its bytecode invokes one of the known tint-accessor APIs:
 * <ul>
 *   <li>{@code DyeColor.getTextureDiffuseColor} (INVOKEVIRTUAL)</li>
 *   <li>{@code DyeColor.getTextureDiffuseColors} (INVOKESTATIC)</li>
 *   <li>{@code BannerPattern.getColor} (INVOKEVIRTUAL)</li>
 *   <li>any method returning {@code BannerPatternLayers}, which flags a patterned-tint
 *       pipeline</li>
 * </ul>
 *
 * <p>Once a renderer is tint-bearing, the choice of which of its {@link Source}s to tint is
 * derived from the {@link Source#classEntry() classEntry} naming convention: classes whose
 * name ends in {@code FlagModel} (as in {@code BannerFlagModel}) are tinted, while body-only
 * classes (e.g. {@code BannerModel}) are not. The conduit/shulker-style mono-layer renderers
 * aren't tint-bearing today, so the heuristic produces an empty set for them; if a future MC
 * version grows a tint-accessor call in e.g. {@code ConduitRenderer}, discovery picks it up
 * automatically without any policy-table edits.
 *
 * <p>No allow-list. The signal is "renderer calls a DyeColor API". That's the only
 * semantic judgment required - everything downstream is structural (renderer owns a Source
 * whose model class name contains {@code Flag}).
 */
@UtilityClass
public final class TintDiscovery {

    private static final @NotNull String DYE_COLOR = "net/minecraft/world/item/DyeColor";
    private static final @NotNull String BANNER_PATTERN = "net/minecraft/world/level/block/entity/BannerPattern";
    private static final @NotNull String BANNER_PATTERN_LAYERS_DESC = "Lnet/minecraft/world/level/block/entity/BannerPatternLayers;";

    /**
     * Scans every unique renderer in {@code entityIdToRenderer.values()} for tint-accessor
     * calls; for each tint-bearing renderer, emits the entity ids of its {@code Flag}-suffixed
     * model-class Sources.
     *
     * @param zip the cached client jar
     * @param sources the full Source list (used to find which classEntries belong to each
     *     renderer)
     * @param entityIdToRenderer {@code entityId -> renderer internal name} (from
     *     {@link SourceDiscovery}'s wire-up)
     * @param diag diagnostics sink (missing renderer classes surface as warns)
     * @return the insertion-ordered set of entity ids that should carry {@code tintindex=0}
     */
    public static @NotNull Set<String> discover(
        @NotNull ZipFile zip,
        @NotNull ConcurrentList<Source> sources,
        @NotNull Map<String, String> entityIdToRenderer,
        @NotNull Diagnostics diag
    ) {
        // Build: renderer -> set of entity ids (from the provided sources list).
        Map<String, Set<String>> rendererToEntityIds = new java.util.LinkedHashMap<>();
        Map<String, String> entityIdToClassEntry = new java.util.HashMap<>();
        for (Source s : sources) entityIdToClassEntry.put(s.entityId(), s.classEntry());
        for (Map.Entry<String, String> e : entityIdToRenderer.entrySet())
            rendererToEntityIds.computeIfAbsent(e.getValue(), k -> new LinkedHashSet<>()).add(e.getKey());

        Set<String> tinted = new LinkedHashSet<>();
        for (Map.Entry<String, Set<String>> e : rendererToEntityIds.entrySet()) {
            String rendererInternal = e.getKey();
            if (!isRendererTintBearing(zip, rendererInternal, diag)) continue;
            for (String entityId : e.getValue()) {
                String classEntry = entityIdToClassEntry.get(entityId);
                if (classEntry == null) continue;
                // Tint only the Flag-family sub-models. Body / base / shell / head sources stay
                // untinted so they render in their authored texture colour; banners render with
                // a wood-brown pole+bar and a dye-coloured flag.
                if (classEntry.contains("Flag") && classEntry.endsWith("Model.class"))
                    tinted.add(entityId);
            }
        }
        return tinted;
    }

    /**
     * Returns {@code true} when {@code rendererInternalName}'s bytecode (including superclass
     * chain) calls any of the tint-accessor APIs. Uses
     * {@link dev.sbs.renderer.tooling.asm.AsmKit#isInvoke} primitives so the match is
     * case-sensitive and exact - no partial-name heuristics.
     */
    static boolean isRendererTintBearing(@NotNull ZipFile zip, @NotNull String rendererInternalName, @NotNull Diagnostics diag) {
        String current = rendererInternalName;
        while (current != null) {
            ClassNode cn = AsmKit.loadClass(zip, current);
            if (cn == null) {
                diag.warn("tint discovery: renderer class '%s' not in jar", rendererInternalName);
                return false;
            }
            for (MethodNode m : cn.methods) {
                for (AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext()) {
                    if (AsmKit.isInvoke(in, Opcodes.INVOKEVIRTUAL, DYE_COLOR, "getTextureDiffuseColor")) return true;
                    if (AsmKit.isInvoke(in, Opcodes.INVOKESTATIC, DYE_COLOR, "getTextureDiffuseColors")) return true;
                    if (AsmKit.isInvoke(in, Opcodes.INVOKEVIRTUAL, BANNER_PATTERN, "getColor")) return true;
                    // Patterned-tint pipeline signal: any method returning BannerPatternLayers.
                    if (in instanceof MethodInsnNode mi && mi.desc.endsWith(BANNER_PATTERN_LAYERS_DESC)) return true;
                }
            }
            current = cn.superName;
            if ("java/lang/Object".equals(current)) break;
        }
        return false;
    }

}
