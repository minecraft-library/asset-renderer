package dev.sbs.renderer.tooling.blockentity;

import dev.sbs.renderer.tooling.asm.AsmKit;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipFile;

/**
 * NOTE: This is a hardcoded catalog with a bytecode-side drift sanity check, NOT a real
 * discovery module. The PR 4 todo for real {@code InventoryTransformDecomposer} is documented
 * below.
 *
 * <p>Maps each block-entity-model id to the 6-or-7-element {@code float[]} that encodes the
 * model's inventory-tile transform:
 * {@code [tx, ty, tz, pitchDeg, yawDeg, rollDeg, uniformScale?]}. The vanilla renderer's
 * {@code modelTransformation} method builds a {@code PoseStack} chain of
 * {@code translate(...) * mulPose(rotation) * scale(...)} calls that decompose cleanly into
 * this flattened form for the inventory-icon pose (yaw=0, no block-state dependent pitch).
 *
 * <pre>
 * // TODO PR 4: replace the CANONICAL_TRANSFORMS table below with a real
 * // InventoryTransformDecomposer that walks each renderer's submit / render /
 * // modelTransformation method, tracking:
 * //   - PoseStack.translate(x, y, z) additions
 * //   - PoseStack.mulPose(axis, angleDeg) rotations (or Axis.X/Y/Z.rotationDegrees then mulPose)
 * //   - PoseStack.scale(sx, sy, sz) uniform and non-uniform scales
 * // and folds scale(-1, -1, -1)-style sign flips into equivalent 180deg rotations so the
 * // resulting {tx, ty, tz, pitch, yaw, roll, uniformScale} tuple matches the geometry the
 * // atlas pipeline expects. Non-uniform scales (e.g. 2/3, -2/3, -2/3) decompose into
 * // uniform scale(2/3) + Rx(180). Block-to-mcPixel unit scaling (*16) is applied to the
 * // translate parts so the output is consistent with the inventoryYRotation entries in
 * // {@link SourceDiscovery}.
 * </pre>
 *
 * <p>The bytecode sanity check here only verifies that each renderer class still defines
 * a {@code submit}, {@code render}, or {@code modelTransformation} method; when Mojang
 * renames or removes them we surface a {@code diag.warn} so the catalog can be updated.
 */
@UtilityClass
public final class InventoryTransformCatalog {

    /**
     * Per-entity-model inventory transform tuples. Each {@code float[]} is
     * {@code [tx, ty, tz, pitch, yaw, roll]} or {@code [tx, ty, tz, pitch, yaw, roll, scale]}.
     * Identical to the hand-curated table that shipped in {@code ToolingBlockEntities.BlockModelConverter}
     * pre-PR-2.
     */
    private static final @NotNull Map<String, float[]> CANONICAL_TRANSFORMS = buildCanonical();

    private static @NotNull Map<String, float[]> buildCanonical() {
        LinkedHashMap<String, float[]> m = new LinkedHashMap<>();
        // BedRenderer: translate(0, 9, 0) * Rx(90) in model units.
        m.put("minecraft:bed_head", new float[]{ 0, 9, 0, 90, 0, 0 });
        m.put("minecraft:bed_foot", new float[]{ 0, 9, 0, 90, 0, 0 });
        // ShulkerBoxRenderer: translate(0.5, 0.5, 0.5) * scale(1, -1, -1) * translate(0, -1, 0)
        // in block units. scale(1, -1, -1) is Rx(180).
        m.put("minecraft:shulker_box", new float[]{ 8, 24, 8, 180, 0, 0 });
        // SkullBlockRenderer: translate(0.5, 0, 0.5) * scale(-1, -1, 1) * translate(-0.5, 0, -0.5).
        m.put("minecraft:skull_head", new float[]{ 8, 0, 8, 180, 0, 0 });
        m.put("minecraft:skull_humanoid_head", new float[]{ 8, 0, 8, 180, 0, 0 });
        m.put("minecraft:skull_piglin_head", new float[]{ 8, 0, 8, 180, 0, 0 });
        // Dragon skull: tz=1.25 shifts +6.75 so bbox midpoint lands at block centre 8.
        m.put("minecraft:skull_dragon_head", new float[]{ 8, 0, 1.25f, 180, 0, 0 });
        // DecoratedPot: neutral transform (authored in block-space Y-up with runtime rotation around block centre).
        m.put("minecraft:decorated_pot", new float[]{ 0, 0, 0, 0, 0, 0 });
        m.put("minecraft:decorated_pot_sides", new float[]{ 0, 0, 0, 0, 0, 0 });
        // Conduit: translate(0.5, 0.5, 0.5), 6x6x6 cube centred at origin.
        m.put("minecraft:conduit", new float[]{ 8, 8, 8, 0, 0, 0 });
        // Signs: translate(0.5, 0.5, 0.5) * scale(2/3, -2/3, -2/3) -> uniform 2/3 + Rx(180).
        m.put("minecraft:sign", new float[]{ 8, 8, 8, 180, 0, 0, 0.6666667f });
        // HangingSign: translate(0.5, 0.9375, 0.5) * translate(0, -0.3125, 0) * scale(1, -1, -1) -> translate(8, 10, 8) + Rx(180).
        m.put("minecraft:hanging_sign", new float[]{ 8, 10, 8, 180, 0, 0 });
        // Banner: MODEL_TRANSLATION (0.5, 0, 0.5) * MODEL_SCALE (2/3, -2/3, -2/3).
        m.put("minecraft:banner", new float[]{ 8, 0, 8, 180, 0, 0, 0.6666667f });
        m.put("minecraft:banner_flag", new float[]{ 8, 0, 8, 180, 0, 0, 0.6666667f });
        m.put("minecraft:wall_banner", new float[]{ 8, 0, 8, 180, 0, 0, 0.6666667f });
        m.put("minecraft:wall_banner_flag", new float[]{ 8, 0, 8, 180, 0, 0, 0.6666667f });
        return m;
    }

    /**
     * Returns the canonical per-entity inventory transform map. Each
     * {@code entityId -> rendererInternalName} input is used for a bytecode sanity check: the
     * referenced renderer class must still exist in the jar and must contain at least one of
     * {@code submit}, {@code render}, or {@code modelTransformation}. Missing targets surface
     * as {@code diag.warn}s so the hand-crafted tuples can be revisited on a renaming.
     *
     * @param zip the cached client jar
     * @param entityIdToRenderer {@code entityId -> renderer internal name}
     * @param diag diagnostics sink
     * @return the canonical catalog (same for every invocation; the map identity is stable)
     */
    public static @NotNull Map<String, float[]> lookup(
        @NotNull ZipFile zip,
        @NotNull Map<String, String> entityIdToRenderer,
        @NotNull Diagnostics diag
    ) {
        for (Map.Entry<String, String> e : entityIdToRenderer.entrySet()) {
            String entityId = e.getKey();
            if (!CANONICAL_TRANSFORMS.containsKey(entityId)) continue; // no transform expected
            String rendererInternal = e.getValue();
            if (!hasTransformMethodInHierarchy(zip, rendererInternal))
                diag.warn("inventory-transform catalog: renderer '%s' has no submit/render/modelTransformation method - drift suspected", rendererInternal);
        }
        return CANONICAL_TRANSFORMS;
    }

    /**
     * Walks the renderer's superclass chain looking for any of the standard block-entity
     * transform-method names ({@code submit}, {@code render}, {@code modelTransformation}, or
     * a {@code submit*} prefix used by the sign renderers). Returns {@code true} when one is
     * found - the sanity check only cares about presence, not specific signature.
     */
    private static boolean hasTransformMethodInHierarchy(@NotNull ZipFile zip, @NotNull String internalName) {
        String current = internalName;
        while (current != null) {
            ClassNode cn = AsmKit.loadClass(zip, current);
            if (cn == null) return false;
            for (MethodNode m : cn.methods) {
                if (m.name.equals("submit") || m.name.equals("render") || m.name.equals("modelTransformation")
                    || m.name.equals("createModelTransform") || m.name.startsWith("submit"))
                    return true;
            }
            current = cn.superName;
            if ("java/lang/Object".equals(current)) break;
        }
        return false;
    }

}
