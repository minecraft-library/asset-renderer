package lib.minecraft.renderer.tooling.entity;

import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.tooling.geometry.GeometryManifest;
import lib.minecraft.renderer.tooling.geometry.GeometryRequest;
import lib.minecraft.renderer.tooling.kernel.ClassKit;
import lib.minecraft.renderer.tooling.kernel.ClassNodeCache;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lib.minecraft.renderer.tooling.kernel.VanillaSourceClasses;
import lib.minecraft.renderer.tooling.policy.AsmContext;
import lib.minecraft.renderer.tooling.vanilla.ArmorMeshIndex;
import lib.minecraft.renderer.tooling.walk.AsmWalker;
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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The four option-gated decoration members - {@code collar}, {@code markings}, {@code armor} and
 * {@code equipment[]} - each named for what it is, presence being its own gate. One roster pass;
 * the {@code equipment} rows keep roster order and every node carries its {@code source} /
 * {@code layer_index} authoring hints.
 *
 * <ul>
 *   <li><b>Armor</b> - a {@code HumanoidArmorLayer} site, carrying the worn-armor mesh as a
 *       plain {@code geometry} reference plus the armor set's two deformations, and the
 *       same shape again under {@code alternate} for the wearers vanilla hands a second,
 *       genuinely distinct shell. That node names the appearance selection that reaches it, since
 *       vanilla reaches both its second sets through one flag but two of this pipeline's axes.</li>
 *   <li><b>Collar</b> - structural detection (a null-gated {@code DyeColor} state read in
 *       the typed submit); the member's presence mirrors vanilla's actual
 *       {@code collarColor != null && !isInvisible} branch rather than approximating it from
 *       {@code state=tame}.</li>
 *   <li><b>Markings</b> - the enum-map shape whose axis token is the markings axis; emits
 *       {@code texture_by} plus the full {@code textures_by_value} map so textures are
 *       re-derived from the value at render rather than from a presence flag.</li>
 *   <li><b>Equipment</b> - {@link EntityEquipmentResolver} rows from call-site windows and
 *       bespoke layers, each flattened to {@code slot} plus its payload.</li>
 * </ul>
 */
final class EntityLayersResolver {

    /**
     * The markings axis name - the sole enum-map token routed to a layers row.
     */
    private static final @NotNull String MARKINGS_TOKEN = "markings";

    /**
     * The two axes a second armor set can be selected by, and the age axis's aged-down option.
     */
    private static final @NotNull String AGE_AXIS = "age";
    private static final @NotNull String SIZE_AXIS = "size";
    private static final @NotNull String BABY_OPTION = "baby";

    /**
     * The two shells vanilla builds, named as {@code ArmorForm} spells them.
     */
    private static final @NotNull String ADULT_FORM = "adult";
    private static final @NotNull String BABY_FORM = "baby";

    private final @NotNull ClassNodeCache cache;
    private final @NotNull List<String> sizeDomain;
    private final @NotNull String entityId;
    private final @NotNull String rendererClass;
    private final @NotNull List<String> registrationLayerFields;
    private final @NotNull List<EntityRendererResolver.LayerSite> roster;
    private final @NotNull EntityEquipmentResolver equipment;
    private final @NotNull ArmorMeshIndex armorMeshes;
    private final @NotNull GeometryManifest manifest;
    private final @NotNull Diagnostics diagnostics;

    EntityLayersResolver(
        @NotNull EntityContext context,
        @NotNull List<EntityRendererResolver.LayerSite> roster
    ) {
        this.cache = context.cache();
        this.sizeDomain = EntitySizeAxisResolver.sizeDomain(this.cache,
            new AsmContext(context.session(), context.subject().entityId(), null, context.diagnostics()));
        this.entityId = context.subject().entityId();
        this.rendererClass = context.subject().rendererClass();
        this.registrationLayerFields = context.subject().lambdaLayerFields();
        this.roster = roster;
        this.equipment = new EntityEquipmentResolver(context.scope("equipment"));
        this.armorMeshes = context.indexes().armorMeshes();
        this.manifest = context.indexes().manifest();
        this.diagnostics = context.diagnostics();
    }

    /**
     * The four decoration members as one carrier, or {@code null} to omit them all.
     *
     * @return a node holding {@code collar} / {@code markings} / {@code armor} / {@code equipment}
     *     where the roster emits each, or {@code null} when no site emits
     */
    @Nullable JsonTree resolve() {
        JsonTree carrier = JsonTree.object();
        JsonTree equipmentRows = JsonTree.array();
        Map<MethodNode, AbstractInsnNode> lastAddLayer = new HashMap<>();
        for (EntityRendererResolver.LayerSite site : this.roster) {
            // The call-site window opens at the previous same-method site's addLayer (else
            // the method start) so candidate statics never bleed across sites.
            AbstractInsnNode windowStart = lastAddLayer.getOrDefault(site.method(), site.method().instructions.getFirst());
            lastAddLayer.put(site.method(), site.addLayer());

            // A HumanoidArmorLayer site emits the humanoid classification node here, keyed by the
            // same exact class match used to detect the armor layer type.
            if (VanillaSourceClasses.Types.HUMANOID_ARMOR_LAYER.equals(site.layerClass())) {
                carrier.put("armor", armorNode(site));
                continue;
            }

            ClassNode cn = this.cache.load(site.layerClass());
            if (cn == null) continue;
            if (EntityOverlayResolver.isCollarShaped(cn)) {
                carrier.putIf("collar", resolveCollar(site, cn));
                continue;
            }
            EntityOverlayResolver.EnumMapOverlay enumMap = EntityOverlayResolver.findEnumMapOverlay(this.cache, cn);
            if (enumMap != null && isLayersRowToken(enumMap.token())) {
                carrier.put(MARKINGS_TOKEN, resolveMarkings(site, enumMap));
                continue;
            }
            if (EntityOverlayResolver.referencesEquipmentLayerType(cn)) {
                JsonTree bespoke = this.equipment.resolveBespoke(site, cn);
                if (bespoke != null) equipmentRows.add(flattenedEquipment(bespoke));
                continue;
            }
            JsonTree callSite = this.equipment.resolveCallSite(site, windowStart);
            if (callSite != null) equipmentRows.add(flattenedEquipment(callSite));
        }
        if (!equipmentRows.isEmpty()) carrier.put("equipment", equipmentRows);
        return carrier.isEmpty() ? null : carrier;
    }

    /**
     * One equipment row flattened: the {@code when.equipment} gate becomes {@code slot} - the
     * member's own home saying what gates it - and the overlay payload rises to the row.
     */
    private static @NotNull JsonTree flattenedEquipment(@NotNull JsonTree row) {
        JsonTree flat = JsonTree.object();
        row.findString("source").ifPresent(source -> flat.put("source", source));
        row.findInt("layer_index").ifPresent(index -> flat.putInt("layer_index", index));
        row.findPath("when", "equipment").flatMap(JsonTree::asString)
            .ifPresent(slot -> flat.put("slot", slot));
        row.find("overlay").ifPresent(overlay ->
            overlay.members().forEach(flat::put));
        return flat;
    }

    /**
     * Whether an enum-map axis token rides a {@code layers[]} row instead of an overlay.
     */
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
        if (AsmWalker.from(site.allocation()).until(site.addLayer())
            .getStatic(VanillaSourceClasses.Types.EQUIPMENT_LAYER_TYPE)
            .any()) return true;
        return AsmWalker.before(site.allocation()).limit(16)
            .first(cursor -> AsmWalker.isGetStatic(cursor, VanillaSourceClasses.Types.EQUIPMENT_LAYER_TYPE),
                cursor -> !(cursor.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && cursor instanceof MethodInsnNode mi
                    && VanillaSourceClasses.Methods.ADD_LAYER.equals(mi.name))) != null;
    }

    /**
     * The collar node: the layer's clinit texture (adult) rides {@code texture}; the tint is
     * render-supplied via {@code tint_by}; the member's presence mirrors vanilla's actual
     * {@code collarColor != null} check rather than {@code state=tame}.
     */
    private @Nullable JsonTree resolveCollar(@NotNull EntityRendererResolver.LayerSite site, @NotNull ClassNode cn) {
        String texture = EntityOverlayResolver.findFirstNonBabyTextureLiteral(cn);
        if (texture == null) {
            this.diagnostics.warn("collar layer '%s' has no clinit texture - row dropped",
                EntityOverlayResolver.simpleName(site.layerClass()));
            return null;
        }
        this.diagnostics.info("collar row via null-gated DyeColor read");
        return JsonTree.object()
            .put("source", EntityOverlayResolver.simpleName(site.layerClass()))
            .putInt("layer_index", site.layerIndex())
            .put("texture", VanillaSourceClasses.Paths.MINECRAFT_NAMESPACE + texture)
            .put("tint_by", "collar_color");
    }

    /**
     * The markings node: the full value map travels with it.
     */
    private @NotNull JsonTree resolveMarkings(
        @NotNull EntityRendererResolver.LayerSite site,
        @NotNull EntityOverlayResolver.EnumMapOverlay enumMap
    ) {
        JsonTree byValue = JsonTree.object();
        for (Map.Entry<String, String> entry : enumMap.textures().entrySet())
            byValue.put(entry.getKey().toLowerCase(Locale.ROOT),
                VanillaSourceClasses.Paths.MINECRAFT_NAMESPACE + entry.getValue());
        this.diagnostics.info("markings row: %d values", enumMap.textures().size());
        return JsonTree.object()
            .put("source", EntityOverlayResolver.simpleName(site.layerClass()))
            .putInt("layer_index", site.layerIndex())
            .put("texture_by", MARKINGS_TOKEN)
            .put("textures_by_value", byValue);
    }

    /**
     * The armor node: worn armor is drawn by a vanilla {@code HumanoidArmorLayer}, so the node's
     * presence is a layer-roster fact and its name identifies it. The mesh the wearer is dressed in
     * rides a plain {@code geometry} reference with the armor set's two deformations alongside it -
     * the shell is registered ungrown, so two wearers differing only in a deformation share one
     * geometry entry.
     *
     * <p>Being armored IS wearing a resolved shell. A node whose mesh could not be resolved carries
     * no reference and is an ERROR, which drops the wearer off the roster loudly rather than
     * dressing it in a fallback that hides the failure.
     */
    private @NotNull JsonTree armorNode(@NotNull EntityRendererResolver.LayerSite site) {
        JsonTree row = JsonTree.object()
            .put("source", EntityOverlayResolver.simpleName(site.layerClass()))
            .putInt("layer_index", site.layerIndex());

        List<String> named = resolveArmorMeshes();
        String name = named.isEmpty() ? null : named.getFirst();
        ArmorMeshIndex.Set set = name == null ? null : this.armorMeshes.get(name);
        if (set == null) {
            this.diagnostics.error("armor row names mesh '%s', which LayerDefinitions registers no armor set for - wearer left bare",
                name == null ? "<unnamed>" : name);
            return row;
        }
        this.diagnostics.info("armor row: set '%s'", name);
        row.putAll(shellNode(set));

        // A second set is a distinct shell only when it is a distinct shell. Vanilla hands the
        // layer one set twice to say a wearer has only the one, and the piglin brute passes the
        // same field twice and means nothing by it.
        if (named.size() > 1) {
            String alternateName = named.get(1);
            ArmorMeshIndex.Set alternate = this.armorMeshes.get(alternateName);
            if (alternate == null)
                this.diagnostics.error("armor row names second mesh '%s', which LayerDefinitions registers no armor set for - wearer left in one shell",
                    alternateName);
            else if (!alternate.sameShellAs(set)) {
                String option = distinguishingToken(name, alternateName);
                String axis = option == null ? null : axisOf(option);
                if (axis == null)
                    this.diagnostics.error("armor row's second set '%s' names no axis option against '%s' - wearer left in one shell",
                        alternateName, name);
                else {
                    row.put("alternate", alternateNode(alternate, axis, option));
                    this.diagnostics.info("armor row: second set '%s' selected by %s=%s", alternateName, axis, option);
                }
            }
        }
        return row;
    }

    /**
     * The one name token the second armor set carries that the first does not - the option that
     * selects it.
     *
     * <p>Vanilla names every armor set {@code <wearer>_ARMOR} and every second one by inserting a
     * single word: {@code ZOMBIE_ARMOR} against {@code ZOMBIE_BABY_ARMOR}, {@code ARMOR_STAND_ARMOR}
     * against {@code ARMOR_STAND_SMALL_ARMOR}. The inserted word is the appearance option, so the
     * row can say which selection reaches its second shell instead of naming one of them and making
     * the other read as it.
     *
     * @return the single distinguishing token, or {@code null} when the two names differ by
     *     anything other than exactly one
     */
    private static @Nullable String distinguishingToken(@NotNull String first, @NotNull String second) {
        List<String> base = List.of(first.split("_"));
        List<String> extra = Arrays.stream(second.split("_")).filter(token -> !base.contains(token)).toList();
        return extra.size() == 1 ? extra.getFirst() : null;
    }

    /**
     * The axis an option belongs to - the size domain holds its own members and the age axis holds
     * the aged-down one. {@code null} for a token neither axis names, which is an unrecognised
     * registration rather than a new axis.
     */
    private @Nullable String axisOf(@NotNull String option) {
        if (this.sizeDomain.contains(option)) return SIZE_AXIS;
        return BABY_OPTION.equals(option) ? AGE_AXIS : null;
    }

    /**
     * The second shell's payload: the same members the adult shell writes, plus the selection that
     * reaches it and which of vanilla's two shells it is.
     *
     * <p>The form is read off the set's own part table rather than off the option that selects it,
     * because they do not have to agree - the small armor stand's shell is aged-down geometry built
     * from the <em>adult</em> factory, so it keeps the adult per-slot parts, the full-size sheets
     * and its trim, which is exactly the carve-out {@code HumanoidArmorLayer} spends a branch on.
     */
    private @NotNull JsonTree alternateNode(@NotNull ArmorMeshIndex.Set set, @NotNull String axis, @NotNull String option) {
        JsonTree node = JsonTree.object()
            .put("when", JsonTree.object().put(axis, option))
            .put("geometry", registerShell(set))
            .put("grow", growPair(set));
        if (set.meshScale() != 1f) node.put("scaled", set.meshScale());
        return node.put("form", set.babyParts() ? BABY_FORM : ADULT_FORM);
    }

    /**
     * One shell's payload - its registered mesh, the two deformations the armor layers apply to it,
     * and the whole-mesh scale it is registered through. The adult shell writes this into the armor
     * node itself and a second shell writes the same shape under {@code alternate}.
     */
    private @NotNull JsonTree shellNode(@NotNull ArmorMeshIndex.Set set) {
        JsonTree node = JsonTree.object()
            .put("geometry", registerShell(set))
            .put("grow", growPair(set));
        // Omitted at the identity, the way every other scale in the tree is - the eleven wearers
        // vanilla registers unscaled write no key.
        if (set.meshScale() != 1f) node.put("scaled", set.meshScale());
        return node;
    }

    /**
     * Registers one shell's mesh and returns its geometry coordinate. The mesh is registered ungrown
     * and unscaled - both ride the row - but an aged-down whole-mesh transformer is baked, because it
     * builds different boxes rather than resizing the same ones.
     */
    private @NotNull String registerShell(@NotNull ArmorMeshIndex.Set set) {
        return this.manifest.register(GeometryRequest.armor(set.meshClass(), set.meshMethod(),
                this.entityId, set.textureWidth(), set.textureHeight(), poseParamOf(set))
            .withBabyTransform(set.babyTransform()));
    }

    /**
     * The pose binding a shell's mesh factory is evaluated at, or {@code null} when the factory takes
     * no pose. The adult factories take none; the baby ones are built at the pose their set was
     * registered with, which is what makes the piglin family's baby shell a distinct mesh.
     */
    private static GeometryRequest.@Nullable PoseParam poseParamOf(@NotNull ArmorMeshIndex.Set set) {
        int slot = set.poseSlot();
        return slot < 0 ? null : new GeometryRequest.PoseParam(slot, set.armOffset());
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

    /**
     * Writes one deformation - a scalar when uniform, a per-axis triple otherwise.
     */
    private static void putGrow(@NotNull JsonTree node, @NotNull String name, float @NotNull [] grow) {
        if (grow[0] == grow[1] && grow[1] == grow[2]) node.put(name, grow[0]);
        else node.putFloats(name, grow[0], grow[1], grow[2]);
    }

    /**
     * The armor sets this renderer dresses its subject in, in the order it hands them to its armor
     * layer - the adult shell first and the baby one, when it names a second, after it. The names are
     * lookup keys into the registrations {@code LayerDefinitions} makes - they identify the meshes and
     * their deformations, and never ship.
     *
     * <p>Vanilla does not build worn armor from the wearer's own model - it hands the layer a shared
     * armor set, and most humanoids share one. A renderer that wears a different set holds it as a
     * static, so the fields along the constructor chain name the meshes. Leaf-first, because a
     * subclass that passes its sets down to its super's constructor is the one that names them, and
     * declaration order is the layer's own argument order: adult, then baby.
     *
     * <p>A renderer handed its sets as constructor arguments (the piglin family) names no field of its
     * own - but the registration that hands them over does, so the walk falls back to the armor sets
     * among the {@code ModelLayers} references in the renderer-factory lambda, in the same order.
     *
     * @return the lowercased field names in layer-argument order, empty when no armor set is named
     */
    private @NotNull List<String> resolveArmorMeshes() {
        List<String> named = new ArrayList<>();
        ClassKit.walkSuperChain(this.cache, this.rendererClass, cn -> {
            if (!named.isEmpty()) return;
            for (MethodNode ctor : cn.methods) {
                if (!ClassKit.INIT.equals(ctor.name)) continue;
                AsmWalker.over(ctor)
                    .ofType(FieldInsnNode.class)
                    .where(field -> field.getOpcode() == Opcodes.GETSTATIC
                        && VanillaSourceClasses.Descs.ARMOR_MODEL_SET_REF.equals(field.desc))
                    .map(field -> field.name.toLowerCase(Locale.ROOT))
                    .forEach(named::add);
                if (!named.isEmpty()) return;
            }
        });
        if (!named.isEmpty()) return named;

        ClassNode modelLayers = this.cache.load(VanillaSourceClasses.Types.MODEL_LAYERS);
        if (modelLayers == null) return named;
        for (String layerField : this.registrationLayerFields) {
            FieldNode field = ClassKit.findField(modelLayers, layerField);
            if (field != null && VanillaSourceClasses.Descs.ARMOR_MODEL_SET_REF.equals(field.desc))
                named.add(layerField.toLowerCase(Locale.ROOT));
        }
        return named;
    }

}
