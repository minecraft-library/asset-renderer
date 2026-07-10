package lib.minecraft.renderer.tooling2.entity;

import lib.minecraft.renderer.tooling2.geometry.GeometryManifest;
import lib.minecraft.renderer.tooling2.geometry.GeometryRequest;
import lib.minecraft.renderer.tooling2.kernel.AsmKit;
import lib.minecraft.renderer.tooling2.kernel.ClassNodeCache;
import lib.minecraft.renderer.tooling2.kernel.Diagnostics;
import lib.minecraft.renderer.tooling2.kernel.JsonNode;
import lib.minecraft.renderer.tooling2.kernel.VanillaSourceClasses;
import lib.minecraft.renderer.tooling2.vanilla.LayerDefinitionIndex;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * Node {@code axes.variant} - the id-encoded variant axis (SPINE 3.1 row 8). Two source
 * arms, tried in order:
 *
 * <ul>
 *   <li><b>Data-driven tables</b> - the {@code data/minecraft/<stem>_variant/} JSONs already
 *       held by {@link VariantIndex} (cow / pig / chicken / frog / wolf / cat /
 *       zombie_nautilus). Per-option {@code spawn_conditions} are carried VERBATIM [D64];
 *       {@code model}-discriminator options register their own {@code GeometryRequest} via
 *       the {@code ModelType}-to-{@code ModelLayers} bytecode pairing that beats naming
 *       (zombie_nautilus {@code WARM} to {@code ZOMBIE_NAUTILUS_CORAL} - legacy
 *       {@code EntityVariantResolver.java:360-382}, comment carried).</li>
 *   <li><b>Enum-map {@code <clinit>} coats</b> - GENERIC [D1], dissolving the legacy horse
 *       fixed-entity injection: any renderer holding a static {@code Map} field keyed by an
 *       enum (from the field's generic signature) whose construction pairs keys with
 *       {@code textures/entity/} literals qualifies - horse / mooshroom / llama / panda /
 *       rabbit, plus axolotl through the {@code %s}-template arm [D66] (options enumerated
 *       from the enum constants, paths formatted and existence-probed [D28]). Texture paths
 *       come from the map construction, never naming reconstruction [D6]; fox stays
 *       excluded (its field is {@code EnumMap}-typed and state-keyed, not a coat table).</li>
 * </ul>
 *
 * <p>Variant ids come from the enum {@code <clinit>} id LDCs ({@code StringRepresentable}),
 * not {@code toLowerCase} [D31]. Defaults: data arm = holder {@code DEFAULT} [P15] then the
 * alphabetical-unconditional spawn-conditions derivation [D30] with the P27 tiebreak;
 * enum-map arm = enum {@code DEFAULT} constant [P15] (mooshroom's lambda puts BROWN first
 * but vanilla defaults RED) then the first walked key [D1].
 */
final class EntityVariantAxisResolver {

    private final @NotNull ClassNodeCache cache;
    private final @NotNull EntitySubject subject;
    private final @NotNull VariantIndex variants;
    private final @NotNull LayerDefinitionIndex layerDefinitions;
    private final @NotNull EntityGeometryRefResolver geometryRef;
    private final @NotNull GeometryManifest manifest;
    private final @NotNull Diagnostics diagnostics;

    EntityVariantAxisResolver(
        @NotNull ClassNodeCache cache,
        @NotNull EntitySubject subject,
        @NotNull VariantIndex variants,
        @NotNull LayerDefinitionIndex layerDefinitions,
        @NotNull EntityGeometryRefResolver geometryRef,
        @NotNull GeometryManifest manifest,
        @NotNull Diagnostics diagnostics
    ) {
        this.cache = cache;
        this.subject = subject;
        this.variants = variants;
        this.layerDefinitions = layerDefinitions;
        this.geometryRef = geometryRef;
        this.manifest = manifest;
        this.diagnostics = diagnostics;
    }

    /**
     * The variant node, or {@code null} when the entity has neither a data-driven table nor
     * an enum-map coat table.
     *
     * @return the node, or {@code null} to omit
     */
    @Nullable JsonNode resolve() {
        List<VariantIndex.Variant> table = this.variants.table(this.subject.localId());
        if (table != null) return resolveDataDriven(table);
        return resolveEnumMap();
    }

    // ------------------------------------------------------------------------------------
    // data-driven arm
    // ------------------------------------------------------------------------------------

    private @NotNull JsonNode resolveDataDriven(@NotNull List<VariantIndex.Variant> table) {
        String stem = this.subject.localId();
        String dflt = this.variants.holderDefault(stem);
        if (dflt == null) {
            dflt = alphaFirstUnconditional(table);
            if (dflt != null)
                this.diagnostics.info("variant default '%s' via alpha-first-unconditional derivation [D30/P27]", dflt);
        }
        if (dflt == null) {
            dflt = table.getFirst().variantId();
            this.diagnostics.info("variant default '%s' via first table entry (no holder DEFAULT, no unconditional variant)", dflt);
        }

        Map<String, String> modelTypeLayers = modelTypeToModelLayerField();
        JsonNode node = JsonNode.object().put("id_encoded", true).put("default", dflt);
        JsonNode options = node.child("options");
        for (VariantIndex.Variant variant : table) {
            JsonNode option = JsonNode.object()
                .put("textures", texturesNode(variant.textures()))
                .putIf("baby_texture", fullPath(pickByStatePrecedence(variant.babyTextures())))
                .putIf("geometry", resolveModelDiscriminator(variant, modelTypeLayers))
                .putIf("spawn_conditions", variant.spawnConditions());
            options.put(variant.variantId(), option);
        }
        this.diagnostics.info("variant axis (data-driven): %d options, default '%s'", table.size(), dflt);
        return node;
    }

    /**
     * The alphabetically-first variant id whose {@code spawn_conditions} entries are all
     * unconditional (each carries no {@code condition} sub-object) - vanilla's fresh-spawn
     * zero-state selection [D30], tiebreak = P27 (consulted:
     * {@link EntityAxisPolicies#ALPHA_FIRST_UNCONDITIONAL_TIEBREAK}). Conditions are read
     * from the retained table [D64] - no jar re-scan.
     */
    private static @Nullable String alphaFirstUnconditional(@NotNull List<VariantIndex.Variant> table) {
        TreeSet<String> unconditional = new TreeSet<>();
        for (VariantIndex.Variant variant : table)
            if (isUnconditional(variant.spawnConditions())) unconditional.add(variant.variantId());
        return unconditional.isEmpty() ? null : unconditional.first();
    }

    /** Whether every spawn-condition entry lacks a {@code condition} sub-object (absent list = unconditional). */
    private static boolean isUnconditional(@Nullable JsonNode spawnConditions) {
        if (spawnConditions == null) return true;
        for (JsonNode entry : spawnConditions.elements())
            if (entry.get(VanillaSourceClasses.DataKeys.CONDITION) != null) return false;
        return true;
    }

    /**
     * The state-keyed textures node: the P22-precedent key first (single-asset
     * {@code primary} folds onto the legacy {@code wild} key - d17), then the remaining
     * state keys in table walk order, values as full namespaced paths (d21).
     */
    private static @NotNull JsonNode texturesNode(@NotNull Map<String, String> textures) {
        JsonNode node = JsonNode.object();
        String precedentKey = null;
        for (String state : EntityAxisPolicies.STATE_PRECEDENCE.strings())
            if (textures.containsKey(state)) {
                precedentKey = state;
                break;
            }
        if (precedentKey == null && !textures.isEmpty()) precedentKey = textures.keySet().iterator().next();
        if (precedentKey != null) node.put("wild", fullPath(textures.get(precedentKey)));
        for (Map.Entry<String, String> entry : textures.entrySet()) {
            String state = entry.getKey();
            if (state.equals(precedentKey) || "primary".equals(state) || "wild".equals(state)) continue;
            node.put(state, fullPath(entry.getValue()));
        }
        return node;
    }

    /**
     * The P22 precedence pick over a state-keyed texture map: {@code wild}, then
     * {@code primary}, then the first entry ({@code null} on an empty map).
     */
    static @Nullable String pickByStatePrecedence(@NotNull Map<String, String> textures) {
        for (String state : EntityAxisPolicies.STATE_PRECEDENCE.strings()) {
            String path = textures.get(state);
            if (path != null) return path;
        }
        for (String path : textures.values()) return path;
        return null;
    }

    /** Prefixes the vanilla namespace onto a jar-relative texture path (d21), null-tolerant. */
    private static @Nullable String fullPath(@Nullable String texturePath) {
        return texturePath == null ? null : VanillaSourceClasses.Paths.MINECRAFT_NAMESPACE + texturePath;
    }

    /**
     * Resolves a variant's {@code model} discriminator to a registered geometry key, or
     * {@code null} when the variant has no discriminator, the layer is unindexed, or the
     * mesh collapses onto the family primary (the key is emitted only as an override).
     */
    private @Nullable String resolveModelDiscriminator(
        @NotNull VariantIndex.Variant variant,
        @NotNull Map<String, String> modelTypeLayers
    ) {
        if (variant.model() == null) return null;
        // Prefer the bytecode-derived ModelType -> ModelLayers pairing over the
        // <MODEL>_<STEM> naming convention (P20 fallback): cow's WARM -> WARM_COW matches
        // the convention, zombie_nautilus's WARM -> ZOMBIE_NAUTILUS_CORAL does not.
        String layerField = modelTypeLayers.get(variant.model().toUpperCase(Locale.ROOT));
        if (layerField == null) {
            layerField = (variant.model() + "_" + this.subject.localId()).toUpperCase(Locale.ROOT);
            this.diagnostics.info("variant '%s' model '%s' resolved by P20 naming convention -> ModelLayers.%s",
                variant.variantId(), variant.model(), layerField);
        }
        LayerDefinitionIndex.Entry entry = this.layerDefinitions.get(layerField);
        if (entry == null) {
            this.diagnostics.info("variant '%s' references model '%s' but ModelLayers.%s is not in LayerDefinitions",
                variant.variantId(), variant.model(), layerField);
            return null;
        }
        String key = this.manifest.register(GeometryRequest.body(
            entry.factoryClass(), entry.factoryMethod(),
            this.subject.entityId() + "/" + variant.variantId(),
            entry.texWidthOverride(), entry.texHeightOverride(),
            entry.floatParam(), entry.appliedMeshTransformerScale()));
        return key.equals(this.geometryRef.primaryKey()) ? null : key;
    }

    /**
     * Walks every renderer method for the model-map construction pairing each
     * {@code $ModelType} constant (P20 inner-enum suffix) with the first following
     * {@code ModelLayers} reference (the adult layer - the baby pair member is skipped by
     * consuming the pending key). Empty when the renderer builds no such map.
     */
    private @NotNull Map<String, String> modelTypeToModelLayerField() {
        ClassNode cn = this.cache.load(this.subject.rendererClass());
        if (cn == null) return Map.of();
        String modelTypeSuffix = EntityNamingPolicies.VARIANT_ENUM_CONVENTIONS.strings().get(1);
        Map<String, String> out = new LinkedHashMap<>();
        for (MethodNode method : cn.methods) {
            String pendingModelType = null;
            for (AbstractInsnNode in = method.instructions.getFirst(); in != null; in = in.getNext()) {
                if (in.getOpcode() != Opcodes.GETSTATIC || !(in instanceof FieldInsnNode fi)) continue;
                if (fi.owner.endsWith(modelTypeSuffix)) {
                    pendingModelType = fi.name;
                    continue;
                }
                if (VanillaSourceClasses.Types.MODEL_LAYERS.equals(fi.owner) && pendingModelType != null) {
                    out.putIfAbsent(pendingModelType, fi.name);
                    pendingModelType = null;
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------------------------
    // enum-map arm [D1]
    // ------------------------------------------------------------------------------------

    private @Nullable JsonNode resolveEnumMap() {
        ClassNode cn = this.cache.load(this.subject.rendererClass());
        if (cn == null) return null;

        for (String enumInternal : enumKeyedMapFieldEnums(cn)) {
            CoatTable coats = walkCoatTable(cn, enumInternal);
            if (coats == null) continue;

            Map<String, String> ids = enumSerializedIds(enumInternal);
            String defaultConstant = AsmKit.findEnumDefaultName(this.cache, enumInternal,
                EntityNamingPolicies.ENUM_DEFAULT_FIELD.stringValue());
            String firstConstant = coats.byConstant().keySet().iterator().next();
            String dflt = variantId(defaultConstant != null ? defaultConstant : firstConstant, ids);
            if (defaultConstant == null)
                this.diagnostics.info("variant default '%s' via first map key [D1] (enum has no DEFAULT)", dflt);

            JsonNode node = JsonNode.object().put("id_encoded", true).put("default", dflt);
            JsonNode options = node.child("options");
            for (Map.Entry<String, List<String>> coat : coats.byConstant().entrySet()) {
                String id = variantId(coat.getKey(), ids);
                options.put(id, JsonNode.object()
                    .put("textures", JsonNode.object().put("wild", fullPath(coat.getValue().getFirst())))
                    .putIf("baby_texture", fullPath(coat.getValue().size() > 1 ? coat.getValue().get(1) : null)));
            }
            this.diagnostics.info("variant axis (enum-map '%s'): %d options, default '%s' [D1]",
                enumInternal, coats.byConstant().size(), dflt);
            return node;
        }
        return null;
    }

    /**
     * The walked coat table of one enum-map renderer: constant name to its ordered texture
     * paths (adult first, baby second - the vanilla adult-first construction order pinned
     * by HorseTextures / the TEXTURES-before-BABY_TEXTURES twin-map declaration order).
     *
     * @param byConstant constant name to ordered texture paths, in first-encounter order
     */
    private record CoatTable(@NotNull Map<String, List<String>> byConstant) {}

    /**
     * The enum key classes of the renderer's static {@code java.util.Map}-typed fields, in
     * declaration order - the D1 detection gate. The declared-type test deliberately
     * excludes {@code EnumMap}-typed fields (fox's state-keyed
     * {@code TEXTURES_BY_VARIANT} - per-state textures, not a coat table).
     */
    private @NotNull LinkedHashSet<String> enumKeyedMapFieldEnums(@NotNull ClassNode cn) {
        String mapDesc = "Ljava/util/Map;";
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (FieldNode field : cn.fields) {
            if ((field.access & Opcodes.ACC_STATIC) == 0 || !mapDesc.equals(field.desc) || field.signature == null) continue;
            String keyInternal = firstTypeArgument(field.signature);
            if (keyInternal == null) continue;
            ClassNode keyNode = this.cache.load(keyInternal);
            if (keyNode != null && (keyNode.access & Opcodes.ACC_ENUM) != 0) out.add(keyInternal);
        }
        return out;
    }

    /** The first generic type argument's internal name from a field signature, or {@code null}. */
    private static @Nullable String firstTypeArgument(@NotNull String signature) {
        int open = signature.indexOf('<');
        if (open < 0 || open + 1 >= signature.length() || signature.charAt(open + 1) != 'L') return null;
        int end = signature.indexOf(';', open + 2);
        return end < 0 ? null : signature.substring(open + 2, end);
    }

    /**
     * Walks the renderer's {@code <clinit>} plus its {@code lambda$static$*} bodies [D63]
     * pairing each {@code GETSTATIC <enum>.<CONST>} with the texture literals that follow
     * it. Literal-less tables fall through to the {@code %s}-template arm [D66]: options
     * are enumerated from the enum constants, paths formatted per constant id and
     * existence-probed [D28]. Returns {@code null} when neither arm yields textures.
     */
    private @Nullable CoatTable walkCoatTable(@NotNull ClassNode cn, @NotNull String enumInternal) {
        Map<String, List<String>> byConstant = new LinkedHashMap<>();
        List<String> templates = new ArrayList<>();
        List<MethodNode> bodies = new ArrayList<>();
        MethodNode clinit = AsmKit.findMethod(cn, AsmKit.CLINIT);
        if (clinit != null) bodies.add(clinit);
        for (MethodNode method : cn.methods)
            if (method.name.startsWith(AsmKit.LAMBDA_STATIC_PREFIX)) bodies.add(method);

        for (MethodNode body : bodies) {
            String pendingConstant = null;
            for (AbstractInsnNode in = body.instructions.getFirst(); in != null; in = in.getNext()) {
                if (in.getOpcode() == Opcodes.GETSTATIC
                    && in instanceof FieldInsnNode fi
                    && enumInternal.equals(fi.owner)
                    && fi.desc.equals(VanillaSourceClasses.Descs.ref(enumInternal))) {
                    pendingConstant = fi.name;
                    continue;
                }
                String literal = AsmKit.readStringLiteral(in);
                if (literal == null || !literal.startsWith(VanillaSourceClasses.Paths.TEXTURES_ENTITY)) continue;
                if (literal.contains("%")) {
                    templates.add(literal);
                    continue;
                }
                if (pendingConstant != null && literal.endsWith(".png"))
                    byConstant.computeIfAbsent(pendingConstant, key -> new ArrayList<>()).add(literal);
            }
        }
        if (!byConstant.isEmpty()) return new CoatTable(byConstant);
        if (!templates.isEmpty()) return templateCoatTable(enumInternal, templates);
        return null;
    }

    /**
     * The [D66] template arm: one option per enum constant (declaration order, the
     * {@code DEFAULT} alias filtered by its missing {@code ACC_ENUM} flag), adult / baby
     * paths formatted from the collected templates (adult = the first template whose stem
     * is not {@code _baby}-suffixed) and existence-probed; a constant whose adult texture
     * is not shipped drops out.
     */
    private @Nullable CoatTable templateCoatTable(@NotNull String enumInternal, @NotNull List<String> templates) {
        String adultTemplate = null;
        String babyTemplate = null;
        for (String template : templates) {
            if (template.substring(0, template.length() - ".png".length()).endsWith("_baby")) {
                if (babyTemplate == null) babyTemplate = template;
            } else if (adultTemplate == null) adultTemplate = template;
        }
        if (adultTemplate == null) return null;

        ClassNode enumNode = this.cache.load(enumInternal);
        if (enumNode == null) return null;
        Map<String, String> ids = enumSerializedIds(enumInternal);
        Map<String, List<String>> byConstant = new LinkedHashMap<>();
        for (FieldNode field : enumNode.fields) {
            if ((field.access & Opcodes.ACC_ENUM) == 0) continue;
            String id = variantId(field.name, ids);
            String adult = adultTemplate.replace("%s", id);
            if (!this.cache.hasEntry(VanillaSourceClasses.Paths.ASSETS_ROOT + adult)) {
                this.diagnostics.info("template variant '%s' dropped - '%s' not shipped [D28]", id, adult);
                continue;
            }
            List<String> paths = new ArrayList<>();
            paths.add(adult);
            if (babyTemplate != null) {
                String baby = babyTemplate.replace("%s", id);
                if (this.cache.hasEntry(VanillaSourceClasses.Paths.ASSETS_ROOT + baby)) paths.add(baby);
            }
            byConstant.put(field.name, paths);
        }
        return byConstant.isEmpty() ? null : new CoatTable(byConstant);
    }

    /**
     * The serialized-id map of a {@code StringRepresentable} variant enum [D31]: per
     * constant allocation in the {@code <clinit>}, the first string LDC is the synthetic
     * constant name and the second is the serialized id ({@code DARK_BROWN} carries
     * {@code "dark_brown"}).
     */
    private @NotNull Map<String, String> enumSerializedIds(@NotNull String enumInternal) {
        ClassNode enumNode = this.cache.load(enumInternal);
        MethodNode clinit = enumNode == null ? null : AsmKit.findMethod(enumNode, AsmKit.CLINIT);
        if (clinit == null) return Map.of();

        Map<String, String> out = new LinkedHashMap<>();
        List<String> pendingStrings = new ArrayList<>();
        for (AbstractInsnNode in = clinit.instructions.getFirst(); in != null; in = in.getNext()) {
            String literal = AsmKit.readStringLiteral(in);
            if (literal != null) {
                pendingStrings.add(literal);
                continue;
            }
            if (in.getOpcode() == Opcodes.PUTSTATIC
                && in instanceof FieldInsnNode fi
                && enumInternal.equals(fi.owner)
                && fi.desc.equals(VanillaSourceClasses.Descs.ref(enumInternal))) {
                if (pendingStrings.size() >= 2) out.put(fi.name, pendingStrings.get(1));
                pendingStrings.clear();
            }
        }
        return out;
    }

    /** A constant's serialized id [D31], falling back to the lowercase constant name at INFO. */
    private @NotNull String variantId(@NotNull String constantName, @NotNull Map<String, String> ids) {
        String id = ids.get(constantName);
        if (id != null) return id;
        this.diagnostics.info("enum constant '%s' has no serialized-id LDC - lowercase name used", constantName);
        return constantName.toLowerCase(Locale.ROOT);
    }

}
