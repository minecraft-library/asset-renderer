package lib.minecraft.renderer.tooling.entity;

import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.tooling.geometry.GeometryManifest;
import lib.minecraft.renderer.tooling.geometry.GeometryRequest;
import lib.minecraft.renderer.tooling.kernel.AsmKit;
import lib.minecraft.renderer.tooling.kernel.ClassNodeCache;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lib.minecraft.renderer.tooling.kernel.ToolingSession;
import lib.minecraft.renderer.tooling.kernel.VanillaSourceClasses;
import lib.minecraft.renderer.tooling.vanilla.ArmorMeshIndex;
import lib.minecraft.renderer.tooling.vanilla.LayerDefinitionIndex;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Node {@code layers[]} - the option-gated conditional rows: collar, markings, equipment, armor.
 * One roster pass; row order is roster order.
 *
 * <ul>
 *   <li><b>Armor</b> - a {@code HumanoidArmorLayer} site, carrying the worn-armor mesh as a
 *       plain {@code overlay.geometry} reference plus the armor set's two deformations.</li>
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

    /** The atlas every armor mesh is baked against, from the {@code LayerDefinition.create} wrap. */
    private static final int ARMOR_TEXTURE_WIDTH = 64;

    /** The armor atlas height - half the entity default, matching the player skin's base layer. */
    private static final int ARMOR_TEXTURE_HEIGHT = 32;

    private final @NotNull ClassNodeCache cache;
    private final @NotNull String entityId;
    private final @NotNull String rendererClass;
    private final @NotNull List<String> registrationLayerFields;
    private final @NotNull List<EntityRendererResolver.LayerSite> roster;
    private final @NotNull EntityEquipmentResolver equipment;
    private final @NotNull ArmorMeshIndex armorMeshes;
    private final @NotNull GeometryManifest manifest;
    private final @NotNull Diagnostics diagnostics;

    EntityLayersResolver(
        @NotNull ToolingSession session,
        @NotNull EntitySubject subject,
        @NotNull List<EntityRendererResolver.LayerSite> roster,
        @NotNull LayerDefinitionIndex layerDefinitions,
        @NotNull EquipmentAssetIndex equipmentAssets,
        @NotNull ArmorMeshIndex armorMeshes,
        @NotNull GeometryManifest manifest,
        @NotNull Diagnostics diagnostics
    ) {
        this.cache = session.cache();
        this.entityId = subject.entityId();
        this.rendererClass = subject.rendererClass();
        this.registrationLayerFields = subject.lambdaLayerFields();
        this.roster = roster;
        this.equipment = new EntityEquipmentResolver(session.cache(), subject, layerDefinitions, equipmentAssets,
            manifest, diagnostics.child("equipment"));
        this.armorMeshes = armorMeshes;
        this.manifest = manifest;
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
     * The armor row: worn armor is drawn by a vanilla {@code HumanoidArmorLayer}, so the row's
     * presence is a layer-roster fact and its {@code id} identifies it. The mesh the wearer is dressed
     * in rides a plain {@code geometry} reference in the row's {@code overlay} body, where every other
     * row keeps its payload, with the armor set's two deformations alongside it - the shell is
     * registered ungrown, so two wearers differing only in a deformation share one geometry entry.
     *
     * <p>Being armored IS wearing a resolved shell. A row whose mesh could not be resolved carries no
     * reference and is an ERROR, which drops the wearer off the roster loudly rather than dressing it
     * in a fallback that hides the failure.
     */
    private @NotNull JsonTree armorRow(@NotNull EntityRendererResolver.LayerSite site) {
        JsonTree row = JsonTree.object()
            .put("source", EntityOverlayResolver.simpleName(site.layerClass()))
            .putInt("layer_index", site.layerIndex())
            .put("id", "armor");

        String name = resolveArmorMesh();
        ArmorMeshIndex.Set set = name == null ? null : this.armorMeshes.get(name);
        if (set == null) {
            this.diagnostics.error("armor row names mesh '%s', which LayerDefinitions registers no armor set for - wearer left bare",
                name == null ? "<unnamed>" : name);
            return row;
        }
        String geometry = this.manifest.register(GeometryRequest.overlay(set.meshClass(), set.meshMethod(),
            this.entityId, ARMOR_TEXTURE_WIDTH, ARMOR_TEXTURE_HEIGHT, GeometryRequest.NO_GROW));
        this.diagnostics.info("armor row: mesh '%s' via set '%s'", geometry, name);
        return row.put("overlay", JsonTree.object()
            .put("geometry", geometry)
            .put("grow", growPair(set)));
    }

    /**
     * The armor row's {@code grow} pair - the two deformations the armor layers apply, each in the
     * scalar-or-array form a cube's own {@code grow} takes.
     */
    private static @NotNull JsonTree growPair(@NotNull ArmorMeshIndex.Set set) {
        JsonTree grow = JsonTree.object();
        putGrow(grow, "inner", set.innerGrow());
        putGrow(grow, "outer", set.outerGrow());
        return grow;
    }

    /** Writes one deformation - a scalar when uniform, a per-axis triple otherwise. */
    private static void putGrow(@NotNull JsonTree node, @NotNull String name, float @NotNull [] grow) {
        if (grow[0] == grow[1] && grow[1] == grow[2]) node.put(name, grow[0]);
        else node.putFloats(name, grow[0], grow[1], grow[2]);
    }

    /**
     * The name of the armor set this renderer dresses its subject in, or {@code null} when the
     * renderer does not name one itself. The name is a lookup key into the registrations
     * {@code LayerDefinitions} makes - it identifies the mesh and its deformations, and never ships.
     *
     * <p>Vanilla does not build worn armor from the wearer's own model - it hands the layer a shared
     * armor set, and most humanoids share one. A renderer that wears a different set holds it as a
     * static, so the first such field along the constructor chain names the mesh. Leaf-first, because
     * a subclass that passes the set down to its super's constructor is the one that names it, and
     * first-wins within a class because vanilla's layer takes the adult set before the baby one.
     *
     * <p>A renderer handed its set as a constructor argument (the piglin family) names no field of its
     * own - but the registration that hands it one does, so the walk falls back to the armor set among
     * the {@code ModelLayers} references in the renderer-factory lambda. First-wins there for the same
     * reason it wins within a class: the adult set is passed before the baby one.
     *
     * @return the lowercased field name, or {@code null} when no armor set is named
     */
    private @Nullable String resolveArmorMesh() {
        List<String> named = new ArrayList<>();
        AsmKit.walkSuperChain(this.cache, this.rendererClass, cn -> {
            if (!named.isEmpty()) return;
            for (MethodNode ctor : cn.methods) {
                if (!AsmKit.INIT.equals(ctor.name)) continue;
                for (AbstractInsnNode in = ctor.instructions.getFirst(); in != null; in = in.getNext()) {
                    if (in.getOpcode() != Opcodes.GETSTATIC) continue;
                    if (!(in instanceof FieldInsnNode field)) continue;
                    if (!VanillaSourceClasses.Descs.ARMOR_MODEL_SET_REF.equals(field.desc)) continue;
                    named.add(field.name.toLowerCase(Locale.ROOT));
                    return;
                }
            }
        });
        if (!named.isEmpty()) return named.getFirst();

        ClassNode modelLayers = this.cache.load(VanillaSourceClasses.Types.MODEL_LAYERS);
        if (modelLayers == null) return null;
        for (String layerField : this.registrationLayerFields) {
            FieldNode field = AsmKit.findField(modelLayers, layerField);
            if (field != null && VanillaSourceClasses.Descs.ARMOR_MODEL_SET_REF.equals(field.desc))
                return layerField.toLowerCase(Locale.ROOT);
        }
        return null;
    }

}
