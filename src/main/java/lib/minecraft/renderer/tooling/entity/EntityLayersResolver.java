package lib.minecraft.renderer.tooling.entity;

import lib.minecraft.renderer.tooling.geometry.GeometryManifest;
import lib.minecraft.renderer.tooling.kernel.AsmKit;
import lib.minecraft.renderer.tooling.kernel.ClassNodeCache;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.tooling.kernel.ToolingSession;
import lib.minecraft.renderer.tooling.kernel.VanillaSourceClasses;
import lib.minecraft.renderer.tooling.vanilla.LayerDefinitionIndex;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Node {@code layers[]} - the option-gated conditional rows: collar, markings, equipment.
 * One roster pass; row order is roster order.
 *
 * <ul>
 *   <li><b>Collar</b> - structural detection (a null-gated {@code DyeColor} state read in
 *       the typed submit); the gate mirrors vanilla's actual
 *       {@code collarColor != null && !isInvisible} branch as
 *       {@code when: {collar_color: "set"}}, rather than approximating it from
 *       {@code state=tame}.</li>
 *   <li><b>Markings</b> - the enum-map shape whose axis token is the markings axis; emits
 *       {@code texture_by} plus the full {@code textures_by_value} map so textures are
 *       re-derived from the value at render rather than from a presence flag.</li>
 *   <li><b>Equipment</b> - {@link EntityEquipmentResolver} rows from call-site windows and
 *       bespoke layers.</li>
 * </ul>
 */
final class EntityLayersResolver {

    /** The markings axis name - the sole enum-map token routed to a layers row. */
    private static final @NotNull String MARKINGS_TOKEN = "markings";

    private final @NotNull ClassNodeCache cache;
    private final @NotNull List<EntityRendererResolver.LayerSite> roster;
    private final @NotNull EntityEquipmentResolver equipment;
    private final @NotNull Diagnostics diagnostics;

    EntityLayersResolver(
        @NotNull ToolingSession session,
        @NotNull EntitySubject subject,
        @NotNull List<EntityRendererResolver.LayerSite> roster,
        @NotNull LayerDefinitionIndex layerDefinitions,
        @NotNull EquipmentAssetIndex equipmentAssets,
        @NotNull GeometryManifest manifest,
        @NotNull Diagnostics diagnostics
    ) {
        this.cache = session.cache();
        this.roster = roster;
        this.equipment = new EntityEquipmentResolver(session.cache(), subject, layerDefinitions, equipmentAssets,
            manifest, diagnostics.child("equipment"));
        this.diagnostics = diagnostics;
    }

    /**
     * The {@code layers} array in roster order, or {@code null} to omit.
     *
     * @return the rows, or {@code null} when no site emits
     */
    @Nullable JsonTree resolve() {
        List<JsonTree> rows = new ArrayList<>();
        Map<MethodNode, AbstractInsnNode> lastAddLayer = new HashMap<>();
        for (EntityRendererResolver.LayerSite site : this.roster) {
            // The call-site window opens at the previous same-method site's addLayer (else
            // the method start) so candidate statics never bleed across sites.
            AbstractInsnNode windowStart = lastAddLayer.getOrDefault(site.method(), site.method().instructions.getFirst());
            lastAddLayer.put(site.method(), site.addLayer());

            // A HumanoidArmorLayer site emits the humanoid classification row here (in roster
            // order), keyed by the same exact class match used to detect the armor layer type.
            if (VanillaSourceClasses.Types.HUMANOID_ARMOR_LAYER.equals(site.layerClass())) {
                rows.add(armorRow(site));
                continue;
            }

            ClassNode cn = this.cache.load(site.layerClass());
            if (cn == null) continue;
            if (EntityOverlayResolver.isCollarShaped(cn)) {
                JsonTree collar = resolveCollar(site, cn);
                if (collar != null) rows.add(collar);
                continue;
            }
            EntityOverlayResolver.EnumMapOverlay enumMap = EntityOverlayResolver.findEnumMapOverlay(this.cache, cn);
            if (enumMap != null && isLayersRowToken(enumMap.token())) {
                rows.add(resolveMarkings(site, enumMap));
                continue;
            }
            if (EntityOverlayResolver.referencesEquipmentLayerType(cn)) {
                JsonTree bespoke = this.equipment.resolveBespoke(site, cn);
                if (bespoke != null) rows.add(bespoke);
                continue;
            }
            JsonTree callSite = this.equipment.resolveCallSite(site, windowStart);
            if (callSite != null) rows.add(callSite);
        }
        if (rows.isEmpty()) return null;
        JsonTree out = JsonTree.array();
        for (JsonTree row : rows) out.add(row);
        return out;
    }

    /** Whether an enum-map axis token rides a {@code layers[]} row instead of an overlay. */
    static boolean isLayersRowToken(@NotNull String token) {
        return MARKINGS_TOKEN.equals(token);
    }

    /**
     * Whether a roster site's call-site window consumes an
     * {@code EquipmentClientInfo$LayerType} static - the skip predicate for
     * {@code SimpleEquipmentLayer}-style sites (the statics may precede a factory-helper
     * allocation, so the walk also scans a bounded backward window).
     */
    static boolean consumesEquipmentLayerType(@NotNull EntityRendererResolver.LayerSite site) {
        for (AbstractInsnNode in = site.allocation(); in != null && in != site.addLayer(); in = in.getNext())
            if (AsmKit.isGetStatic(in, VanillaSourceClasses.Types.EQUIPMENT_LAYER_TYPE)) return true;
        AbstractInsnNode cursor = site.allocation().getPrevious();
        for (int depth = 0; cursor != null && depth < 16; depth++, cursor = cursor.getPrevious()) {
            if (AsmKit.isGetStatic(cursor, VanillaSourceClasses.Types.EQUIPMENT_LAYER_TYPE)) return true;
            if (cursor.getOpcode() == Opcodes.INVOKEVIRTUAL
                && cursor instanceof MethodInsnNode mi
                && VanillaSourceClasses.Methods.ADD_LAYER.equals(mi.name)) break;
        }
        return false;
    }

    /**
     * The collar row: the layer's clinit texture (adult) rides {@code overlay.texture}; the
     * tint is render-supplied via {@code tint_by}; the gate mirrors vanilla's actual
     * {@code collarColor != null} check rather than {@code state=tame}.
     */
    private @Nullable JsonTree resolveCollar(@NotNull EntityRendererResolver.LayerSite site, @NotNull ClassNode cn) {
        String texture = EntityOverlayResolver.findFirstNonBabyTextureLiteral(cn);
        if (texture == null) {
            this.diagnostics.warn("collar layer '%s' has no clinit texture - row dropped",
                EntityOverlayResolver.simpleName(site.layerClass()));
            return null;
        }
        this.diagnostics.info("collar row via null-gated DyeColor read [P6, D42]");
        return JsonTree.object()
            .put("source", EntityOverlayResolver.simpleName(site.layerClass()))
            .putInt("layer_index", site.layerIndex())
            .put("id", "collar")
            .put("when", JsonTree.object().put("collar_color", "set"))
            .put("overlay", JsonTree.object()
                .put("texture", VanillaSourceClasses.Paths.MINECRAFT_NAMESPACE + texture)
                .put("tint_by", "collar_color"));
    }

    /** The markings row: the full value map travels with the row. */
    private @NotNull JsonTree resolveMarkings(
        @NotNull EntityRendererResolver.LayerSite site,
        @NotNull EntityOverlayResolver.EnumMapOverlay enumMap
    ) {
        JsonTree byValue = JsonTree.object();
        for (Map.Entry<String, String> entry : enumMap.textures().entrySet())
            byValue.put(entry.getKey().toLowerCase(Locale.ROOT),
                VanillaSourceClasses.Paths.MINECRAFT_NAMESPACE + entry.getValue());
        this.diagnostics.info("markings row: %d values [D43]", enumMap.textures().size());
        return JsonTree.object()
            .put("source", EntityOverlayResolver.simpleName(site.layerClass()))
            .putInt("layer_index", site.layerIndex())
            .put("id", MARKINGS_TOKEN)
            .put("when", JsonTree.object().put(MARKINGS_TOKEN, "selected"))
            .put("overlay", JsonTree.object()
                .put("texture_by", MARKINGS_TOKEN)
                .put("textures_by_value", byValue));
    }

    /**
     * The armor classification row: humanoid armor is rendered by a vanilla
     * {@code HumanoidArmorLayer}, so the classification is a layer-roster fact. The row carries
     * {@code armor_type: "humanoid"} so the fact travels with the roster it derives from; the
     * native reader reads it off this row. A {@code none} family emits no armor row - absence IS
     * {@code none}.
     */
    private @NotNull JsonTree armorRow(@NotNull EntityRendererResolver.LayerSite site) {
        this.diagnostics.info("armor row: humanoid [LOCKED 3]");
        return JsonTree.object()
            .put("source", EntityOverlayResolver.simpleName(site.layerClass()))
            .putInt("layer_index", site.layerIndex())
            .put("id", "armor")
            .put("armor_type", "humanoid");
    }

}
