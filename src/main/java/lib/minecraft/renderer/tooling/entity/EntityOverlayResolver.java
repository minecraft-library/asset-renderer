package lib.minecraft.renderer.tooling.entity;

import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.tooling.geometry.GeometryManifest;
import lib.minecraft.renderer.tooling.geometry.GeometryRequest;
import lib.minecraft.renderer.tooling.kernel.AsmKit;
import lib.minecraft.renderer.tooling.kernel.ClassNodeCache;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lib.minecraft.renderer.tooling.kernel.VanillaSourceClasses;
import lib.minecraft.renderer.tooling.vanilla.LayerDefinitionIndex;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Node {@code overlays[]} - the generic overlay engine. The per-entity handling dissolves
 * into structural arms: the enum-map detector (crackiness), the composite gate's superclass
 * walk plus ADDITIVE probe (creeper, wither), the emissive-provider arm with retain-subset
 * detection (warden, creaking, copper golem), the villager multi-pass arm, the
 * parameterized binding (stray, bogged), and the bespoke-equipment default-decor arm
 * (trader llama, via constant-boolean predicate evaluation on the subject's entity class).
 * Eyes classify by the clinit texture-to-{@code RenderTypes}-factory SHAPE plus pipeline
 * traits, not by name.
 *
 * <p>Zero-state byte-identity is the load-bearing doctrine: every row's absent / default
 * option renders identically to vanilla (crackiness NONE draws nothing, the uncharged
 * creeper drops the swirl, the NONE profession drops its pass, the white sheep stays
 * untinted).
 *
 * <p>Geometry: a row whose mesh entry shares the FAMILY's factory coordinate emits the
 * family key plus a row-level {@code grow} (the real CubeDeformation - creeper 2.0, fish
 * pattern 0.008, llama decor 0.5; the 0.001 depth clearance is never data); a distinct
 * factory registers its own {@link GeometryRequest} with the grow baked. Collar / markings /
 * equipment / block-decoration sites are handled elsewhere and are skipped here.
 */
final class EntityOverlayResolver {

    /** Field descriptor of a {@code java.util.function.Function} ctor arg (JDK name, kit-local). */
    private static final @NotNull String FUNCTION_DESC = "Ljava/util/function/Function;";

    /** Field descriptor of a {@code java.util.Map} static field (JDK name, kit-local). */
    private static final @NotNull String MAP_DESC = "Ljava/util/Map;";

    /** The no-tint sentinel (multiplicative identity). */
    private static final int NO_TINT = 0xFFFFFFFF;

    private final @NotNull ClassNodeCache cache;
    private final @NotNull EntitySubject subject;
    private final @NotNull List<EntityRendererResolver.LayerSite> roster;
    private final @NotNull LayerDefinitionIndex layerDefinitions;
    private final @NotNull EntityGeometryRefResolver geometryRef;
    private final @NotNull EntityPipelineTraits traits;
    private final @NotNull GeometryManifest manifest;
    private final @NotNull Diagnostics diagnostics;

    EntityOverlayResolver(
        @NotNull ClassNodeCache cache,
        @NotNull EntitySubject subject,
        @NotNull List<EntityRendererResolver.LayerSite> roster,
        @NotNull LayerDefinitionIndex layerDefinitions,
        @NotNull EntityGeometryRefResolver geometryRef,
        @NotNull EntityPipelineTraits traits,
        @NotNull GeometryManifest manifest,
        @NotNull Diagnostics diagnostics
    ) {
        this.cache = cache;
        this.subject = subject;
        this.roster = roster;
        this.layerDefinitions = layerDefinitions;
        this.geometryRef = geometryRef;
        this.traits = traits;
        this.manifest = manifest;
        this.diagnostics = diagnostics;
    }

    /**
     * The {@code overlays} array in roster order (multi-pass rows share their site's
     * {@code layer_index}; within one index the emission order is the render order), or
     * {@code null} to omit.
     *
     * @return the rows, or {@code null} when no site emits
     */
    @Nullable JsonTree resolve() {
        List<JsonTree> rows = new ArrayList<>();
        boolean sameGeometryEmissive = false;
        for (EntityRendererResolver.LayerSite site : this.roster) {
            ClassNode cn = this.cache.load(site.layerClass());
            if (cn == null) continue;
            // Collar, markings-token enum maps, equipment (call-site LayerType consumers),
            // and block-decoration layers are handled by other resolvers and never emit here.
            if (isCollarShaped(cn)) continue;
            if (readsBlockModelRenderState(cn)) continue;
            if (EntityLayersResolver.consumesEquipmentLayerType(site)) continue;
            if (referencesEquipmentLayerType(cn)) {
                // Bespoke equipment is handled elsewhere; only its DEFAULT decor (an
                // EquipmentAssets constant gated on a truthy entity predicate) overlays here.
                JsonTree decor = resolveDefaultDecor(site, cn);
                if (decor != null) rows.add(decor);
                continue;
            }
            EnumMapOverlay enumMap = findEnumMapOverlay(this.cache, cn);
            if (enumMap != null && EntityLayersResolver.isLayersRowToken(enumMap.token())) continue;

            List<JsonTree> emitted = resolveSite(site, cn, enumMap);
            for (JsonTree row : emitted) {
                rows.add(row);
                JsonTree pipeline = row.find("pipeline").orElse(null);
                if (pipeline != null && pipeline.getBoolean("emissive", false)
                    && Objects.equals(row.findString("geometry").orElse(null), this.geometryRef.primaryKey()))
                    sameGeometryEmissive = true;
            }
        }
        if (!sameGeometryEmissive) {
            JsonTree tail = resolveRendererTailEyes();
            if (tail != null) rows.add(tail);
        }
        if (rows.isEmpty()) return null;
        JsonTree out = JsonTree.array();
        for (JsonTree row : rows) out.add(row);
        return out;
    }

    /** Dispatches one roster site through the structural arms; first claim wins. */
    private @NotNull List<JsonTree> resolveSite(
        @NotNull EntityRendererResolver.LayerSite site,
        @NotNull ClassNode cn,
        @Nullable EnumMapOverlay enumMap
    ) {
        List<JsonTree> emissive = resolveEmissiveProviderSite(site, cn);
        if (emissive != null) return emissive;
        JsonTree eyes = resolveEyesBinding(site.layerClass(), site.layerIndex(), cn);
        if (eyes != null) return List.of(eyes);
        if (enumMap != null) return List.of(resolveEnumMapRow(site, enumMap));
        List<JsonTree> villager = resolveVillagerPasses(site, cn);
        if (villager != null) return villager;
        JsonTree parameterized = resolveParameterizedBinding(site, cn);
        if (parameterized != null) return List.of(parameterized);
        JsonTree composite = resolveComposite(site, cn);
        if (composite != null) return List.of(composite);
        return List.of();
    }

    // ------------------------------------------------------------------------------------
    // routing predicates (shared with collar / markings / equipment / block-decoration resolvers)
    // ------------------------------------------------------------------------------------

    /**
     * The typed-RenderState {@code submit} overload - the bridge overload takes the
     * {@code EntityRenderState} base and only delegates.
     */
    static @Nullable MethodNode typedSubmit(@NotNull ClassNode cn) {
        String bridgeParam = VanillaSourceClasses.Descs.ref(VanillaSourceClasses.Types.ENTITY_RENDER_STATE);
        for (MethodNode method : cn.methods) {
            if (!VanillaSourceClasses.Methods.SUBMIT.equals(method.name)) continue;
            if (method.desc.contains(bridgeParam)) continue;
            return method;
        }
        return null;
    }

    /**
     * The collar shape: the typed submit reads a {@code DyeColor}-typed state field
     * null-gated at the top - the option-gated {@code layers[]} row's discriminator (the
     * undercoat's WHITE-compare gate is {@code if_acmp*}, never {@code ifnull}). The read
     * may pass through a local ({@code astore; aload}) before the null branch, so the probe
     * scans a small forward window over store / load / dup shuffles.
     */
    static boolean isCollarShaped(@NotNull ClassNode cn) {
        MethodNode submit = typedSubmit(cn);
        if (submit == null) return false;
        String dyeRef = VanillaSourceClasses.Descs.ref(VanillaSourceClasses.Types.DYE_COLOR);
        for (AbstractInsnNode in = submit.instructions.getFirst(); in != null; in = in.getNext()) {
            if (in.getOpcode() != Opcodes.GETFIELD || !(in instanceof FieldInsnNode fi)) continue;
            if (!dyeRef.equals(fi.desc)) continue;
            AbstractInsnNode cursor = in;
            for (int step = 0; step < 4; step++) {
                cursor = AsmKit.nextReal(cursor);
                if (cursor == null) break;
                int opcode = cursor.getOpcode();
                if (opcode == Opcodes.IFNULL || opcode == Opcodes.IFNONNULL) return true;
                if (opcode != Opcodes.ASTORE && opcode != Opcodes.ALOAD && opcode != Opcodes.DUP) break;
            }
        }
        return false;
    }

    /** The block-decoration qualifier: the typed submit reads a {@code BlockModelRenderState}-typed field. */
    static boolean readsBlockModelRenderState(@NotNull ClassNode cn) {
        MethodNode submit = typedSubmit(cn);
        if (submit == null) return false;
        String stateRef = VanillaSourceClasses.Descs.ref(VanillaSourceClasses.Types.BLOCK_MODEL_RENDER_STATE);
        for (AbstractInsnNode in = submit.instructions.getFirst(); in != null; in = in.getNext())
            if (in.getOpcode() == Opcodes.GETFIELD && in instanceof FieldInsnNode fi && stateRef.equals(fi.desc))
                return true;
        return false;
    }

    /** The bespoke-equipment discriminator: any method references the LayerType enum. */
    static boolean referencesEquipmentLayerType(@NotNull ClassNode cn) {
        for (MethodNode method : cn.methods)
            for (AbstractInsnNode in = method.instructions.getFirst(); in != null; in = in.getNext())
                if (AsmKit.isGetStatic(in, VanillaSourceClasses.Types.EQUIPMENT_LAYER_TYPE)) return true;
        return false;
    }

    /**
     * One enum-map overlay: the typed submit feeds an enum-typed state field into a
     * {@code Map.get} on a static map of the class; the {@code <clinit>} binds enum
     * constants to texture literals.
     *
     * @param token the state field name (the {@code texture_by} axis token)
     * @param textures enum-constant name to raw texture path, in declaration order
     */
    record EnumMapOverlay(@NotNull String token, @NotNull Map<String, String> textures) {}

    /**
     * Detects the enum-map shape on a layer class and reads its constant-to-texture map, or
     * {@code null} when the shape is absent (also covers markings-shaped layers - the
     * caller routes those to the markings resolver).
     */
    static @Nullable EnumMapOverlay findEnumMapOverlay(@NotNull ClassNodeCache cache, @NotNull ClassNode cn) {
        MethodNode submit = typedSubmit(cn);
        if (submit == null) return null;
        for (AbstractInsnNode in = submit.instructions.getFirst(); in != null; in = in.getNext()) {
            if (in.getOpcode() != Opcodes.INVOKEINTERFACE || !(in instanceof MethodInsnNode mi)) continue;
            if (!"java/util/Map".equals(mi.owner) || !"get".equals(mi.name)) continue;
            String stateField = null;
            String mapField = null;
            for (AbstractInsnNode back = in.getPrevious(); back != null && (stateField == null || mapField == null);
                 back = back.getPrevious()) {
                if (back.getOpcode() == Opcodes.GETFIELD && back instanceof FieldInsnNode read
                    && stateField == null && read.desc.startsWith("L"))
                    stateField = read.name;
                if (back.getOpcode() == Opcodes.GETSTATIC && back instanceof FieldInsnNode map
                    && mapField == null && cn.name.equals(map.owner) && MAP_DESC.equals(map.desc))
                    mapField = map.name;
            }
            if (stateField == null || mapField == null) continue;
            Map<String, String> textures = AsmKit.readStaticEnumMap(cache, cn.name, mapField,
                EntityOverlayResolver::readEntityTextureLiteral);
            if (!textures.isEmpty()) return new EnumMapOverlay(stateField, textures);
        }
        return null;
    }

    // ------------------------------------------------------------------------------------
    // row assembly helpers
    // ------------------------------------------------------------------------------------

    /** A fresh row carrying its source-class and layer-index provenance pair. */
    private static @NotNull JsonTree row(@NotNull String sourceClass, int layerIndex) {
        return JsonTree.object()
            .put("source", simpleName(sourceClass))
            .putInt("layer_index", layerIndex);
    }

    /** The simple class name of a JVM internal name (nested classes keep their {@code $} form). */
    static @NotNull String simpleName(@NotNull String internalName) {
        return internalName.substring(internalName.lastIndexOf('/') + 1);
    }

    /**
     * The mesh reference of an overlay's baked {@code ModelLayers} field: the family key
     * plus a row-level grow when the entry shares the family's factory coordinate, else a
     * freshly registered request with the grow baked (the 0.001 clearance is never data).
     *
     * @param key the geometry key to embed
     * @param grow the row-level grow to emit, or {@code null} when baked / absent
     */
    private record MeshRef(@Nullable String key, float @Nullable [] grow) {}

    private @NotNull MeshRef overlayMesh(@Nullable String bakedField) {
        if (bakedField == null) return new MeshRef(this.geometryRef.primaryKey(), null);
        LayerDefinitionIndex.Entry entry = this.layerDefinitions.get(bakedField);
        if (entry == null) {
            this.diagnostics.info("overlay ModelLayers.%s has no createRoots entry - family mesh reused", bakedField);
            return new MeshRef(this.geometryRef.primaryKey(), null);
        }
        LayerDefinitionIndex.Entry primary = this.geometryRef.resolvedEntry();
        boolean familyFactory = primary != null
            && entry.factoryClass().equals(primary.factoryClass())
            && entry.factoryMethod().equals(primary.factoryMethod())
            && Objects.equals(entry.floatParam(), primary.floatParam())
            && entry.appliedMeshTransformerScale() == primary.appliedMeshTransformerScale();
        if (familyFactory) return new MeshRef(this.geometryRef.primaryKey(), nonZeroGrow(entry.grow()));
        String key = this.manifest.register(GeometryRequest.overlay(
            entry.factoryClass(), entry.factoryMethod(), this.subject.entityId(),
            entry.texWidthOverride(), entry.texHeightOverride(), entry.grow()));
        return new MeshRef(key, null);
    }

    /** The grow when any component is non-zero, else {@code null}. */
    private static float @Nullable [] nonZeroGrow(float @NotNull [] grow) {
        return grow[0] == 0f && grow[1] == 0f && grow[2] == 0f ? null : grow;
    }

    /** Emits {@code grow} as a scalar when uniform, a triplet when asymmetric. */
    private static void putGrow(@NotNull JsonTree row, float @Nullable [] grow) {
        if (grow == null) return;
        if (grow[0] == grow[1] && grow[1] == grow[2]) row.put("grow", grow[0]);
        else row.putFloats("grow", grow[0], grow[1], grow[2]);
    }

    /**
     * The {@code pipeline} node - {@code emissive} is the walked NO_CARDINAL_LIGHTING trait
     * (absent = shaded), {@code blend} the additive / translucent classification (absent =
     * normal), {@code alpha} the fractional frozen-frame opacity (absent = 1.0). Null when
     * every member is at its default.
     */
    private static @Nullable JsonTree pipelineNode(boolean emissive, @Nullable String blend, float alpha) {
        if (!emissive && blend == null && alpha >= 1f) return null;
        JsonTree node = JsonTree.object();
        if (emissive) node.put("emissive", true);
        if (blend != null) node.put("blend", blend);
        if (alpha < 1f) node.put("alpha", alpha);
        return node;
    }

    /** A raw {@code textures/entity/...png} literal without a format placeholder, or {@code null}. */
    private static @Nullable String readEntityTextureLiteral(@NotNull AbstractInsnNode in) {
        String literal = AsmKit.readStringLiteral(in);
        if (literal == null) return null;
        if (!literal.startsWith(VanillaSourceClasses.Paths.TEXTURES_ENTITY) || !literal.endsWith(".png")) return null;
        return literal.contains("%") ? null : literal;
    }

    /** The stem gate: a pre-built RenderType binding is an eye / glow overlay only on an eye-stem texture. */
    private static boolean hasEyeStem(@NotNull String texturePath) {
        String stem = texturePath.substring(0, texturePath.length() - ".png".length());
        for (String suffix : EntityOverlayPolicies.EYE_STEM_FIRST_LITERAL.strings())
            if (stem.endsWith(suffix)) return true;
        return false;
    }

    /** Prefixes a raw jar texture path with the vanilla namespace. */
    private static @NotNull String namespaced(@NotNull String rawPath) {
        return VanillaSourceClasses.Paths.MINECRAFT_NAMESPACE + rawPath;
    }

    // ------------------------------------------------------------------------------------
    // eyes-binding arm
    // ------------------------------------------------------------------------------------

    /**
     * The pre-built-RenderType eye shape: the {@code <clinit>} binds a texture literal
     * flowing into a {@code RenderTypes} factory returning a {@code RenderType} - detected
     * by SHAPE and classified by pipeline traits, never by a class or field name match.
     * Works unchanged on a renderer's own {@code <clinit>} (the dragon tail).
     */
    private @Nullable JsonTree resolveEyesBinding(@NotNull String sourceClass, int layerIndex, @NotNull ClassNode cn) {
        MethodNode clinit = AsmKit.findMethod(cn, AsmKit.CLINIT);
        if (clinit == null) return null;
        String renderTypeReturn = ")" + VanillaSourceClasses.Descs.ref(VanillaSourceClasses.Types.RENDER_TYPE);
        String pendingTexture = null;
        for (AbstractInsnNode in = clinit.instructions.getFirst(); in != null; in = in.getNext()) {
            String literal = readEntityTextureLiteral(in);
            if (literal != null) {
                pendingTexture = literal;
                continue;
            }
            if (in.getOpcode() == Opcodes.INVOKESTATIC
                && in instanceof MethodInsnNode mi
                && VanillaSourceClasses.Types.RENDER_TYPES.equals(mi.owner)
                && mi.desc.endsWith(renderTypeReturn)
                && pendingTexture != null
                && hasEyeStem(pendingTexture)) {
                var factoryTraits = this.traits.traitsOf(mi.name);
                // A default-pipeline factory (the dragon's dying entityCutoutDissolve) never
                // pre-builds a glow overlay - skip it without consuming the pending texture.
                if (factoryTraits.isEmpty()) continue;
                JsonTree node = row(sourceClass, layerIndex)
                    .putIf("geometry", this.geometryRef.primaryKey())
                    .put("texture", namespaced(pendingTexture));
                node.putIf("pipeline", pipelineNode(
                    factoryTraits.contains(EntityPipelineTraits.Trait.NO_CARDINAL_LIGHTING),
                    EntityPipelineTraits.blendToken(factoryTraits), 1f));
                this.diagnostics.info("eyes overlay '%s' via clinit RenderTypes.%s [D20]", simpleName(sourceClass), mi.name);
                return node;
            }
        }
        return null;
    }

    /**
     * The renderer-tail eyes row: the dragon-style renderer binds its eye RenderType in its
     * OWN {@code <clinit>} and dispatches from {@code submit} with no {@code addLayer}
     * site. Runs only when no same-geometry emissive row emitted, with no {@code layer_index}
     * - the row is not in vanilla's addLayer list (empty-vs-absent).
     */
    private @Nullable JsonTree resolveRendererTailEyes() {
        ClassNode renderer = this.cache.load(this.subject.rendererClass());
        if (renderer == null) return null;
        MethodNode clinit = AsmKit.findMethod(renderer, AsmKit.CLINIT);
        if (clinit == null) return null;
        String renderTypeReturn = ")" + VanillaSourceClasses.Descs.ref(VanillaSourceClasses.Types.RENDER_TYPE);
        String pendingTexture = null;
        for (AbstractInsnNode in = clinit.instructions.getFirst(); in != null; in = in.getNext()) {
            String literal = readEntityTextureLiteral(in);
            if (literal != null) {
                pendingTexture = literal;
                continue;
            }
            if (in.getOpcode() == Opcodes.INVOKESTATIC
                && in instanceof MethodInsnNode mi
                && VanillaSourceClasses.Types.RENDER_TYPES.equals(mi.owner)
                && mi.desc.endsWith(renderTypeReturn)
                && pendingTexture != null
                && hasEyeStem(pendingTexture)) {
                var factoryTraits = this.traits.traitsOf(mi.name);
                if (factoryTraits.isEmpty()) continue;   // default pipeline - not a glow binding
                JsonTree node = JsonTree.object()
                    .put("source", simpleName(this.subject.rendererClass()))
                    .putIf("geometry", this.geometryRef.primaryKey())
                    .put("texture", namespaced(pendingTexture));
                node.putIf("pipeline", pipelineNode(
                    factoryTraits.contains(EntityPipelineTraits.Trait.NO_CARDINAL_LIGHTING),
                    EntityPipelineTraits.blendToken(factoryTraits), 1f));
                this.diagnostics.info("renderer-tail eyes via clinit RenderTypes.%s", mi.name);
                return node;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------------------------
    // enum-map arm
    // ------------------------------------------------------------------------------------

    /**
     * The enum-map row (crackiness): no baked texture - the zero-state enum value is absent
     * from the map, so the default draws nothing - with the full value-to-path map and a
     * bounds skip (a zero-state-none overlay never contributes silhouette).
     */
    private @NotNull JsonTree resolveEnumMapRow(
        @NotNull EntityRendererResolver.LayerSite site,
        @NotNull EnumMapOverlay enumMap
    ) {
        JsonTree node = row(site.layerClass(), site.layerIndex())
            .putIf("geometry", this.geometryRef.primaryKey())
            .put("texture_by", axisToken(enumMap.token()));
        JsonTree byValue = JsonTree.object();
        for (Map.Entry<String, String> entry : enumMap.textures().entrySet())
            byValue.put(entry.getKey().toLowerCase(Locale.ROOT), namespaced(entry.getValue()));
        node.put("textures_by_value", byValue);
        node.put("skip_bounds", true);
        this.diagnostics.info("enum-map overlay: texture_by '%s', %d values [D11]",
            enumMap.token(), enumMap.textures().size());
        return node;
    }

    /** The snake_case axis token of a camelCase state member ({@code patternColor} to {@code pattern_color}). */
    static @NotNull String axisToken(@NotNull String memberName) {
        StringBuilder out = new StringBuilder(memberName.length() + 4);
        for (int i = 0; i < memberName.length(); i++) {
            char c = memberName.charAt(i);
            if (Character.isUpperCase(c)) {
                if (!out.isEmpty()) out.append('_');
                out.append(Character.toLowerCase(c));
            } else out.append(c);
        }
        return out.toString();
    }

    // ------------------------------------------------------------------------------------
    // generic composite arm
    // ------------------------------------------------------------------------------------

    /**
     * The composite gate: the ctor bakes a {@code ModelLayers} field AND the layer
     * hierarchy's instance methods establish their own render type ({@code RenderTypes}
     * factory or the cutout-copy helper) - the superclass walk is what admits the
     * energy-swirl subclasses a class-name-only gate would reject. Emits one row with the
     * walked gates, tint, pipeline, and mesh reference.
     */
    private @Nullable JsonTree resolveComposite(@NotNull EntityRendererResolver.LayerSite site, @NotNull ClassNode cn) {
        String bakedField = findCtorBakedField(cn);
        if (bakedField == null) return null;
        if (!compositeGateAccepts(cn)) return null;
        String texture = findCompositeTexture(cn);
        if (texture == null) {
            this.diagnostics.info("overlay '%s' bakes ModelLayers.%s but no texture path resolved",
                simpleName(site.layerClass()), bakedField);
            return null;
        }

        boolean additive = this.traits.layerInvokes(cn.name, EntityPipelineTraits.Trait.ADDITIVE);
        JsonTree node = row(site.layerClass(), site.layerIndex());
        node.putIf("when", compositeWhen(cn, additive));
        MeshRef mesh = overlayMesh(bakedField);
        node.putIf("geometry", mesh.key());
        node.put("texture", namespaced(texture));
        node.putIf("texture_by", switchDispatchTextureBy(cn));
        ColorSource tint = extractCutoutTint(cn);
        if (tint.argb() != NO_TINT) node.putHex("tint", tint.argb());
        node.putIf("tint_by", tint.axisToken());
        node.putIf("pipeline", pipelineNode(
            this.traits.layerInvokes(cn.name, EntityPipelineTraits.Trait.NO_CARDINAL_LIGHTING),
            this.traits.classifyBlend(cn.name), 1f));
        putGrow(node, mesh.grow());
        return node;
    }

    /** The first ctor-baked non-baby {@code ModelLayers} field (name filter), or {@code null}. */
    private static @Nullable String findCtorBakedField(@NotNull ClassNode cn) {
        for (MethodNode method : cn.methods) {
            if (!AsmKit.INIT.equals(method.name)) continue;
            String pending = null;
            for (AbstractInsnNode in = method.instructions.getFirst(); in != null; in = in.getNext()) {
                if (AsmKit.isGetStatic(in, VanillaSourceClasses.Types.MODEL_LAYERS)) {
                    pending = ((FieldInsnNode) in).name;
                    continue;
                }
                if (AsmKit.isInvokeVirtual(in, VanillaSourceClasses.Types.ENTITY_MODEL_SET, VanillaSourceClasses.Methods.BAKE_LAYER)
                    && pending != null
                    && !pending.contains("BABY"))   // naming fallback - the adult mesh carries the row
                    return pending;
            }
        }
        return null;
    }

    /**
     * The first ctor-baked {@code ModelLayers.*_BABY} field - the layer's baby mesh, whose own
     * {@code CubeDeformation} the baby decor delta carries. The complement of {@link #findCtorBakedField}.
     *
     * @param cn the layer class
     * @return the baby {@code ModelLayers} field name, or {@code null} when the layer bakes no baby mesh
     */
    private static @Nullable String findCtorBakedBabyField(@NotNull ClassNode cn) {
        for (MethodNode method : cn.methods) {
            if (!AsmKit.INIT.equals(method.name)) continue;
            String pending = null;
            for (AbstractInsnNode in = method.instructions.getFirst(); in != null; in = in.getNext()) {
                if (AsmKit.isGetStatic(in, VanillaSourceClasses.Types.MODEL_LAYERS)) {
                    pending = ((FieldInsnNode) in).name;
                    continue;
                }
                if (AsmKit.isInvokeVirtual(in, VanillaSourceClasses.Types.ENTITY_MODEL_SET, VanillaSourceClasses.Methods.BAKE_LAYER)
                    && pending != null
                    && pending.contains("BABY"))
                    return pending;
            }
        }
        return null;
    }

    /** Whether any hierarchy INSTANCE method invokes a {@code RenderTypes} factory or the cutout helper. */
    private boolean compositeGateAccepts(@NotNull ClassNode cn) {
        boolean[] accepted = {false};
        AsmKit.walkSuperChain(this.cache, cn.name, level -> {
            if (accepted[0]) return;
            for (MethodNode method : level.methods) {
                if ((method.access & Opcodes.ACC_STATIC) != 0 || AsmKit.INIT.equals(method.name)) continue;
                for (AbstractInsnNode in = method.instructions.getFirst(); in != null; in = in.getNext()) {
                    if (in.getOpcode() != Opcodes.INVOKESTATIC || !(in instanceof MethodInsnNode mi)) continue;
                    if (VanillaSourceClasses.Types.RENDER_TYPES.equals(mi.owner)
                        || VanillaSourceClasses.Methods.COLORED_CUTOUT_HELPER.equals(mi.name)) {
                        accepted[0] = true;
                        return;
                    }
                }
            }
        });
        return accepted[0];
    }

    /**
     * The composite row's {@code when} gate: the additive energy-swirl shape is the
     * {@code charged} boolean; a {@code DyeColor}-vs-constant compare is the {@code tinted}
     * gate (wool undercoat); a boolean flag read branching to an early return is the
     * {@code flag} gate when its stripped name is in the axis-name vocabulary (sheep
     * {@code isSheared} - {@code isBaby} / {@code isInvisible} are universal render gates,
     * not option axes, and fall out of the vocabulary filter).
     */
    private @Nullable JsonTree compositeWhen(@NotNull ClassNode cn, boolean additive) {
        if (additive) return JsonTree.object().put("charged", true);
        MethodNode submit = typedSubmit(cn);
        if (submit == null) return null;
        String dyeRef = VanillaSourceClasses.Descs.ref(VanillaSourceClasses.Types.DYE_COLOR);
        for (AbstractInsnNode in = submit.instructions.getFirst(); in != null; in = in.getNext()) {
            if (!AsmKit.isGetStatic(in, VanillaSourceClasses.Types.DYE_COLOR)) continue;
            AbstractInsnNode next = AsmKit.nextReal(in);
            if (next != null && (next.getOpcode() == Opcodes.IF_ACMPEQ || next.getOpcode() == Opcodes.IF_ACMPNE)) {
                AbstractInsnNode before = AsmKit.previousReal(in);
                if (before instanceof FieldInsnNode read && dyeRef.equals(read.desc))
                    return JsonTree.object().put("tinted", true);
            }
        }
        for (AbstractInsnNode in = submit.instructions.getFirst(); in != null; in = in.getNext()) {
            if (in.getOpcode() != Opcodes.GETFIELD || !(in instanceof FieldInsnNode fi)) continue;
            if (!"Z".equals(fi.desc) || !fi.name.startsWith("is")) continue;
            AbstractInsnNode branch = AsmKit.nextReal(in);
            if (branch == null || (branch.getOpcode() != Opcodes.IFEQ && branch.getOpcode() != Opcodes.IFNE)) continue;
            AbstractInsnNode body = AsmKit.nextReal(branch);
            if (body == null || body.getOpcode() != Opcodes.RETURN) continue;
            String token = axisToken(fi.name.substring(2));
            if (!EntityAxisPolicies.AXIS_NAME_VOCABULARY.strings().contains(token)) continue;
            // IFEQ skips the RETURN when the flag is false - the row renders at flag ==
            // false (the wool renders UNsheared); IFNE is the inverse shape.
            return JsonTree.object().put("flag", token).put("value", branch.getOpcode() != Opcodes.IFEQ);
        }
        return null;
    }

    /**
     * The switch-dispatch texture axis: the typed submit switches over an enum-typed state
     * field selecting among several static {@code Identifier} fields (tropical fish
     * pattern). The token is the state field name, gated by the axis-name vocabulary.
     */
    private @Nullable String switchDispatchTextureBy(@NotNull ClassNode cn) {
        MethodNode submit = typedSubmit(cn);
        if (submit == null) return null;
        boolean hasSwitch = false;
        int identifierReads = 0;
        String enumField = null;
        for (AbstractInsnNode in = submit.instructions.getFirst(); in != null; in = in.getNext()) {
            int opcode = in.getOpcode();
            if (opcode == Opcodes.TABLESWITCH || opcode == Opcodes.LOOKUPSWITCH) hasSwitch = true;
            if (opcode == Opcodes.GETFIELD && in instanceof FieldInsnNode fi
                && enumField == null && fi.desc.startsWith("L")
                && fi.desc.contains("$"))   // vanilla state enums are nested types (TropicalFish$Pattern)
                enumField = fi.name;
            if (opcode == Opcodes.GETSTATIC && in instanceof FieldInsnNode fi
                && cn.name.equals(fi.owner)
                && VanillaSourceClasses.Descs.IDENTIFIER_REF.equals(fi.desc))
                identifierReads++;
        }
        if (!hasSwitch || identifierReads < 2 || enumField == null) return null;
        String token = axisToken(enumField);
        return EntityAxisPolicies.AXIS_NAME_VOCABULARY.strings().contains(token) ? token : null;
    }

    // ------------------------------------------------------------------------------------
    // tint evaluation (shared by the composite + parameterized arms)
    // ------------------------------------------------------------------------------------

    /**
     * A statically resolved overlay tint plus its user-selectable axis token.
     *
     * @param argb the baked default ARGB, or the no-tint identity
     * @param axisToken the {@code tint_by} token, or {@code null} for a fixed tint
     */
    private record ColorSource(int argb, @Nullable String axisToken) {}

    /**
     * Statically resolves the {@code color} argument of a cutout-copy helper call: a
     * parameterless state getter chases into the {@code ColorLerper.getColor(DyeColor)}
     * evaluation at the WHITE default (the wool layers); a plain int state field chases the
     * {@code extractRenderState} bind ending in {@code DyeColor.getTextureDiffuseColor()}
     * at the WHITE default (the fish pattern). Unresolvable sources keep the no-tint
     * identity (slightly over-bright beats failing the render entirely).
     */
    private @NotNull ColorSource extractCutoutTint(@NotNull ClassNode layerCn) {
        for (MethodNode method : layerCn.methods) {
            if (AsmKit.INIT.equals(method.name) || AsmKit.CLINIT.equals(method.name)) continue;
            for (AbstractInsnNode in = method.instructions.getFirst(); in != null; in = in.getNext()) {
                if (in.getOpcode() != Opcodes.INVOKESTATIC || !(in instanceof MethodInsnNode mi)) continue;
                if (!VanillaSourceClasses.Methods.COLORED_CUTOUT_HELPER.equals(mi.name)) continue;
                for (AbstractInsnNode prev = in.getPrevious(); prev != null; prev = prev.getPrevious()) {
                    if (prev.getOpcode() == Opcodes.INVOKEVIRTUAL
                        && prev instanceof MethodInsnNode colorCall
                        && "()I".equals(colorCall.desc)) {
                        int argb = resolveStateColorMethod(colorCall);
                        return new ColorSource(argb, tintAxisOf(colorCall.name));
                    }
                    if (prev.getOpcode() == Opcodes.GETFIELD
                        && prev instanceof FieldInsnNode colorField
                        && "I".equals(colorField.desc)) {
                        int argb = resolveStateColorField(colorField.name);
                        return new ColorSource(argb, tintAxisOf(colorField.name));
                    }
                }
            }
        }
        return new ColorSource(NO_TINT, null);
    }

    /** The vocabulary-gated tint axis of a color-source member name ({@code getWoolColor} to {@code wool_color}). */
    private static @Nullable String tintAxisOf(@NotNull String memberName) {
        String stem = memberName.startsWith("get") ? memberName.substring(3) : memberName;
        String token = axisToken(stem);
        return EntityAxisPolicies.AXIS_NAME_VOCABULARY.strings().contains(token) ? token : null;
    }

    /**
     * The {@code state.get<X>Color()} evaluation: the getter's {@code ColorLerper$Type
     * .getColor(<dye field>)} chain evaluated at the field's declared WHITE default - the
     * WHITE branch literal is DERIVED from {@code ColorLerper.getModifiedColor} (the
     * {@code if_acmpne WHITE; ldc; ireturn} arm) rather than hardcoded, so it tracks vanilla
     * if the constant ever changes. Non-WHITE defaults are not statically folded
     * (srgb-linear math) - no-tint identity.
     */
    private int resolveStateColorMethod(@NotNull MethodInsnNode stateColorCall) {
        ClassNode stateClass = this.cache.load(stateColorCall.owner);
        MethodNode stateMethod = stateClass == null ? null
            : AsmKit.findMethod(stateClass, stateColorCall.name, stateColorCall.desc);
        if (stateMethod == null) return NO_TINT;
        String dyeRef = VanillaSourceClasses.Descs.ref(VanillaSourceClasses.Types.DYE_COLOR);
        for (AbstractInsnNode in = stateMethod.instructions.getFirst(); in != null; in = in.getNext()) {
            if (!AsmKit.isInvokeVirtual(in, VanillaSourceClasses.Types.COLOR_LERPER_TYPE, VanillaSourceClasses.Methods.GET_COLOR)) continue;
            String dyeField = null;
            for (AbstractInsnNode prev = in.getPrevious(); prev != null; prev = prev.getPrevious())
                if (prev.getOpcode() == Opcodes.GETFIELD && prev instanceof FieldInsnNode fi && dyeRef.equals(fi.desc)) {
                    dyeField = fi.name;
                    break;
                }
            if (dyeField == null) continue;
            if (!"WHITE".equals(fieldDefaultDyeConstant(stateClass, dyeField))) continue;
            Integer white = colorLerperWhiteReturn();
            if (white != null) return white;
        }
        return NO_TINT;
    }

    /**
     * The {@code state.<field>:I} evaluation: the renderer chain's
     * {@code extractRenderState} binds the field from
     * {@code DyeColor.getTextureDiffuseColor()}; at the zero-state WHITE dye that is the
     * WHITE diffuse constant.
     */
    private int resolveStateColorField(@NotNull String fieldName) {
        boolean[] diffuseBound = {false};
        AsmKit.walkSuperChain(this.cache, this.subject.rendererClass(), cn -> {
            if (diffuseBound[0]) return;
            for (MethodNode method : cn.methods) {
                if (!VanillaSourceClasses.Methods.EXTRACT_RENDER_STATE.equals(method.name)) continue;
                boolean pendingDiffuse = false;
                for (AbstractInsnNode in = method.instructions.getFirst(); in != null; in = in.getNext()) {
                    if (AsmKit.isInvokeVirtual(in, VanillaSourceClasses.Types.DYE_COLOR,
                            VanillaSourceClasses.Methods.GET_TEXTURE_DIFFUSE_COLOR)) {
                        pendingDiffuse = true;
                        continue;
                    }
                    if (in.getOpcode() == Opcodes.PUTFIELD && in instanceof FieldInsnNode fi) {
                        if (fieldName.equals(fi.name) && pendingDiffuse) {
                            diffuseBound[0] = true;
                            return;
                        }
                        pendingDiffuse = false;
                    }
                }
            }
        });
        if (!diffuseBound[0]) return NO_TINT;
        return EntityRenderTraitsResolver.whiteTextureDiffuseColor(this.cache);
    }

    /** The {@code GETSTATIC DyeColor.<NAME>; PUTFIELD <field>} default initialiser constant, or {@code null}. */
    private static @Nullable String fieldDefaultDyeConstant(@NotNull ClassNode stateClass, @NotNull String fieldName) {
        MethodNode init = AsmKit.findMethod(stateClass, AsmKit.INIT);
        if (init == null) return null;
        String pending = null;
        for (AbstractInsnNode in = init.instructions.getFirst(); in != null; in = in.getNext()) {
            if (AsmKit.isGetStatic(in, VanillaSourceClasses.Types.DYE_COLOR)) {
                pending = ((FieldInsnNode) in).name;
                continue;
            }
            if (in.getOpcode() == Opcodes.PUTFIELD && in instanceof FieldInsnNode fi
                && fieldName.equals(fi.name) && pending != null)
                return pending;
        }
        return null;
    }

    /**
     * The literal {@code ColorLerper.getModifiedColor} returns on its WHITE branch -
     * {@code GETSTATIC DyeColor.WHITE; if_acmpne; ldc <int>; ireturn} - derived, not
     * declared (the brightness parameter is ignored for WHITE).
     */
    private @Nullable Integer colorLerperWhiteReturn() {
        String lerperOwner = VanillaSourceClasses.Types.COLOR_LERPER_TYPE;
        int nested = lerperOwner.lastIndexOf('$');
        ClassNode lerper = this.cache.load(nested < 0 ? lerperOwner : lerperOwner.substring(0, nested));
        MethodNode method = lerper == null ? null
            : AsmKit.findMethod(lerper, VanillaSourceClasses.Methods.GET_MODIFIED_COLOR);
        if (method == null) return null;
        boolean pastWhiteCompare = false;
        for (AbstractInsnNode in = method.instructions.getFirst(); in != null; in = in.getNext()) {
            if (AsmKit.isGetStatic(in, VanillaSourceClasses.Types.DYE_COLOR, "WHITE")) {
                AbstractInsnNode branch = AsmKit.nextReal(in);
                if (branch != null && (branch.getOpcode() == Opcodes.IF_ACMPNE || branch.getOpcode() == Opcodes.IF_ACMPEQ))
                    pastWhiteCompare = true;
                continue;
            }
            if (!pastWhiteCompare) continue;
            Integer literal = AsmKit.readIntLiteral(in);
            if (literal != null) {
                AbstractInsnNode ret = AsmKit.nextReal(in);
                if (ret != null && ret.getOpcode() == Opcodes.IRETURN) return literal;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------------------------
    // composite texture chase
    // ------------------------------------------------------------------------------------

    /**
     * The composite texture: the layer's own {@code <clinit>} first non-baby literal, else
     * the first sibling {@code Identifier} field its methods read, chased through the
     * owner's {@code <clinit>} (the slime shell renders against
     * {@code SlimeRenderer.SLIME_LOCATION}).
     */
    private @Nullable String findCompositeTexture(@NotNull ClassNode cn) {
        String own = findFirstNonBabyTextureLiteral(cn);
        if (own != null) return own;
        for (MethodNode method : cn.methods) {
            if (AsmKit.INIT.equals(method.name) || AsmKit.CLINIT.equals(method.name)) continue;
            for (AbstractInsnNode in = method.instructions.getFirst(); in != null; in = in.getNext()) {
                if (in.getOpcode() != Opcodes.GETSTATIC || !(in instanceof FieldInsnNode fi)) continue;
                if (!VanillaSourceClasses.Descs.IDENTIFIER_REF.equals(fi.desc)) continue;
                String chased = chaseTextureFieldOwner(fi.owner, fi.name);
                if (chased != null) return chased;
            }
        }
        return null;
    }

    /**
     * The first {@code <clinit>} texture literal flowing through
     * {@code withDefaultNamespace} into a non-baby {@code Identifier} field.
     */
    static @Nullable String findFirstNonBabyTextureLiteral(@NotNull ClassNode cn) {
        MethodNode clinit = AsmKit.findMethod(cn, AsmKit.CLINIT);
        if (clinit == null) return null;
        String pendingPath = null;
        boolean pendingIdentifier = false;
        for (AbstractInsnNode in = clinit.instructions.getFirst(); in != null; in = in.getNext()) {
            String literal = readEntityTextureLiteral(in);
            if (literal != null) {
                pendingPath = literal;
                pendingIdentifier = false;
                continue;
            }
            if (AsmKit.isInvokeStatic(in, VanillaSourceClasses.Types.IDENTIFIER, VanillaSourceClasses.Methods.WITH_DEFAULT_NAMESPACE)) {
                pendingIdentifier = true;
                continue;
            }
            if (in.getOpcode() == Opcodes.PUTSTATIC
                && in instanceof FieldInsnNode fi
                && VanillaSourceClasses.Descs.IDENTIFIER_REF.equals(fi.desc)
                && pendingPath != null && pendingIdentifier) {
                if (!fi.name.contains("BABY")) return pendingPath;
                pendingPath = null;
                pendingIdentifier = false;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------------------------
    // emissive-provider arm
    // ------------------------------------------------------------------------------------

    /**
     * The provider-driven emissive layer shape (warden, creaking, copper golem): the layer
     * ctor takes two {@code Function}-typed providers (texture + render type), a functional
     * alpha hook, and a model - detected by ctor descriptor, never by class name. Per
     * allocation: the alpha provider evaluates at the frozen frame (a non-positive result
     * drops the pass - the anti-phase spots, the creaking's glow-gated eyes); the texture
     * provider resolves a static field or a state-driven dispatch (the weathering axis); the
     * model argument picks same-geometry, full-mesh reuse, or the retain-subset registration.
     *
     * @return the emitted rows, or {@code null} when the site is not provider-shaped
     */
    private @Nullable List<JsonTree> resolveEmissiveProviderSite(
        @NotNull EntityRendererResolver.LayerSite site,
        @NotNull ClassNode cn
    ) {
        if (!isEmissiveProviderShaped(cn)) return null;
        List<Handle> providers = new ArrayList<>();
        String modelField = null;
        Map<Integer, String> slotFields = bakedModelSlots(site.method());
        String pendingModelLayers = null;
        for (AbstractInsnNode in = site.allocation(); in != null && in != site.addLayer(); in = in.getNext()) {
            if (AsmKit.isLambdaInvokeDynamic(in) && in instanceof InvokeDynamicInsnNode indy) {
                Handle handle = AsmKit.extractLambdaHandle(indy);
                if (handle != null) providers.add(handle);
                continue;
            }
            if (in.getOpcode() == Opcodes.INVOKESTATIC
                && in instanceof MethodInsnNode mi
                && mi.desc.endsWith(")" + FUNCTION_DESC)) {
                Handle provided = providerFactoryLambda(mi);
                if (provided != null) providers.add(provided);
                continue;
            }
            if (AsmKit.isGetStatic(in, VanillaSourceClasses.Types.MODEL_LAYERS)) {
                pendingModelLayers = ((FieldInsnNode) in).name;
                continue;
            }
            if (AsmKit.isInvokeVirtual(in, VanillaSourceClasses.Types.RENDERER_PROVIDER_CONTEXT, VanillaSourceClasses.Methods.BAKE_LAYER)
                && pendingModelLayers != null) {
                modelField = pendingModelLayers;
                continue;
            }
            if (in.getOpcode() == Opcodes.ALOAD && in instanceof VarInsnNode load && modelField == null) {
                String stored = slotFields.get(load.var);
                if (stored != null) modelField = stored;
            }
        }
        if (providers.size() < 3) {
            this.diagnostics.info("provider-shaped layer '%s' resolved %d of 3 providers - dropped",
                simpleName(site.layerClass()), providers.size());
            return List.of();
        }

        float alpha = evaluateFrozenAlpha(providers.get(1));
        if (alpha <= 0f) {
            this.diagnostics.info("provider layer '%s' frozen-frame alpha 0 - pass drops (P12)", simpleName(site.layerClass()));
            return List.of();
        }
        ProviderTexture texture = resolveProviderTexture(providers.get(0));
        if (texture == null) {
            this.diagnostics.info("provider layer '%s' texture unresolved - dropped", simpleName(site.layerClass()));
            return List.of();
        }
        var factoryTraits = this.traits.traitsOf(renderTypeFactoryName(providers.get(2)));
        boolean emissive = factoryTraits.contains(EntityPipelineTraits.Trait.NO_CARDINAL_LIGHTING);
        String blend = EntityPipelineTraits.blendToken(factoryTraits);

        boolean primaryMesh = modelField == null || modelField.equals(this.geometryRef.primaryFieldName());
        JsonTree node = row(site.layerClass(), site.layerIndex());
        if (primaryMesh) {
            node.putIf("geometry", this.geometryRef.primaryKey())
                .put("texture", texture.path())
                .putIf("texture_by", texture.textureBy())
                .putIf("pipeline", pipelineNode(emissive, blend, 1f));
            return List.of(node);
        }
        if (alpha >= EntityOverlayPolicies.FULL_MESH_REUSE_ALPHA_EPSILON.floatValue()) {
            // Full-opacity glow reuses the FAMILY mesh - exact because the glow texture is
            // transparent outside its retained parts (a deliberate policy call, not a fallback).
            node.putIf("geometry", this.geometryRef.primaryKey())
                .put("texture", texture.path())
                .putIf("texture_by", texture.textureBy())
                .putIf("pipeline", pipelineNode(emissive, blend, 1f))
                .put("skip_bounds", true);
            return List.of(node);
        }
        LayerDefinitionIndex.Entry entry = this.layerDefinitions.get(modelField);
        List<String> retain = entry == null ? null : findRetainSubset(entry);
        if (retain == null) {
            // Better no pass than a full-mesh over-draw of a fractional-alpha subset.
            this.diagnostics.warn("provider layer '%s' subset ModelLayers.%s has no retainExactParts set - pass dropped",
                simpleName(site.layerClass()), modelField);
            return List.of();
        }
        String key = this.manifest.register(GeometryRequest.overlay(
            entry.factoryClass(), entry.factoryMethod(), this.subject.entityId(),
            entry.texWidthOverride(), entry.texHeightOverride(), entry.grow()));
        node.put("geometry", key)
            .put("texture", texture.path())
            .putIf("texture_by", texture.textureBy())
            .putIf("pipeline", pipelineNode(emissive, blend, alpha))
            .put("skip_bounds", true);
        JsonTree bones = node.childArray("retain_bones");
        for (String bone : retain) bones.add(bone);
        return List.of(node);
    }

    /** The provider ctor shape: two or more {@code Function} args plus a model-class arg. */
    private static boolean isEmissiveProviderShaped(@NotNull ClassNode cn) {
        for (MethodNode method : cn.methods) {
            if (!AsmKit.INIT.equals(method.name)) continue;
            int functions = 0;
            boolean model = false;
            for (Type arg : AsmKit.argTypes(method.desc)) {
                if (arg.getSort() != Type.OBJECT) continue;
                if (FUNCTION_DESC.equals(arg.getDescriptor())) functions++;
                else if (arg.getInternalName().startsWith(VanillaSourceClasses.Types.CLIENT_MODEL_ROOT)
                    && !arg.getInternalName().startsWith(VanillaSourceClasses.Types.CLIENT_MODEL_GEOM_ROOT))
                    model = true;
            }
            if (functions >= 2 && model) return true;
        }
        return false;
    }

    /** The first lambda handle inside a provider-factory method ({@code getEyeTextureLocationProvider}). */
    private @Nullable Handle providerFactoryLambda(@NotNull MethodInsnNode factoryCall) {
        ClassNode owner = this.cache.load(factoryCall.owner);
        MethodNode method = owner == null ? null : AsmKit.findMethod(owner, factoryCall.name, factoryCall.desc);
        if (method == null) return null;
        for (AbstractInsnNode in = method.instructions.getFirst(); in != null; in = in.getNext())
            if (AsmKit.isLambdaInvokeDynamic(in) && in instanceof InvokeDynamicInsnNode indy) {
                Handle handle = AsmKit.extractLambdaHandle(indy);
                if (handle != null) return handle;
            }
        return null;
    }

    /** Slot-to-{@code ModelLayers}-field bindings of pre-baked models {@code ASTORE}d in the ctor (warden). */
    private static @NotNull Map<Integer, String> bakedModelSlots(@NotNull MethodNode ctor) {
        Map<Integer, String> out = new LinkedHashMap<>();
        String pendingField = null;
        boolean baked = false;
        for (AbstractInsnNode in = ctor.instructions.getFirst(); in != null; in = in.getNext()) {
            if (AsmKit.isGetStatic(in, VanillaSourceClasses.Types.MODEL_LAYERS)) {
                pendingField = ((FieldInsnNode) in).name;
                baked = false;
                continue;
            }
            if (AsmKit.isInvokeVirtual(in, VanillaSourceClasses.Types.RENDERER_PROVIDER_CONTEXT, VanillaSourceClasses.Methods.BAKE_LAYER)
                && pendingField != null) {
                baked = true;
                continue;
            }
            if (in.getOpcode() == Opcodes.ASTORE && in instanceof VarInsnNode store) {
                if (baked && pendingField != null) out.put(store.var, pendingField);
                pendingField = null;
                baked = false;
            }
        }
        return out;
    }

    /**
     * A resolved provider texture: the zero-state full path plus the state axis overriding
     * it at render, when the provider dispatches on a state field.
     *
     * @param path the namespaced zero-state texture path
     * @param textureBy the axis token, or {@code null} for a fixed texture
     */
    private record ProviderTexture(@NotNull String path, @Nullable String textureBy) {}

    private @Nullable ProviderTexture resolveProviderTexture(@NotNull Handle textureProvider) {
        ClassNode owner = this.cache.load(textureProvider.getOwner());
        MethodNode lambda = owner == null ? null
            : AsmKit.findMethod(owner, textureProvider.getName(), textureProvider.getDesc());
        if (lambda == null) return null;
        // Field-return shape: GETSTATIC <Identifier field> chased through the owner clinit.
        String stateField = null;
        for (AbstractInsnNode in = lambda.instructions.getFirst(); in != null; in = in.getNext()) {
            if (in.getOpcode() == Opcodes.GETSTATIC && in instanceof FieldInsnNode fi
                && VanillaSourceClasses.Descs.IDENTIFIER_REF.equals(fi.desc)) {
                String chased = chaseTextureFieldOwner(fi.owner, fi.name);
                return chased == null ? null : new ProviderTexture(namespaced(chased), null);
            }
            if (in.getOpcode() == Opcodes.GETFIELD && in instanceof FieldInsnNode read && stateField == null)
                stateField = read.name;
        }
        if (stateField == null) return null;
        // State-driven dispatch: the axis token is the state field; the zero-state texture
        // is the first eye-stem literal over the lambda's reachable classes (the data class
        // allocates its default-state instance first).
        String zeroState = firstEyeLiteral(owner, lambda);
        if (zeroState == null) return null;
        String token = axisToken(stateField);
        boolean vocab = EntityAxisPolicies.AXIS_NAME_VOCABULARY.strings().contains(token);
        this.diagnostics.info("provider texture via state dispatch: texture_by '%s' [D23]", token);
        return new ProviderTexture(namespaced(zeroState), vocab ? token : null);
    }

    /** The first eye-stem texture literal over the lambda-reachable clinits. */
    private @Nullable String firstEyeLiteral(@NotNull ClassNode lambdaOwner, @NotNull MethodNode lambda) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        candidates.add(lambdaOwner.name);
        for (AbstractInsnNode in = lambda.instructions.getFirst(); in != null; in = in.getNext())
            if (in instanceof MethodInsnNode mi
                && (in.getOpcode() == Opcodes.INVOKESTATIC || in.getOpcode() == Opcodes.INVOKEVIRTUAL)
                && mi.owner.startsWith(VanillaSourceClasses.Types.MINECRAFT_ROOT))
                candidates.add(mi.owner);
        List<String> stems = EntityOverlayPolicies.EYE_STEM_FIRST_LITERAL.strings();
        for (String candidate : candidates) {
            ClassNode cn = this.cache.load(candidate);
            MethodNode clinit = cn == null ? null : AsmKit.findMethod(cn, AsmKit.CLINIT);
            if (clinit == null) continue;
            for (AbstractInsnNode in = clinit.instructions.getFirst(); in != null; in = in.getNext()) {
                String literal = readEntityTextureLiteral(in);
                if (literal == null) continue;
                String stem = literal.substring(0, literal.length() - ".png".length());
                for (String suffix : stems)
                    if (stem.endsWith(suffix)) return literal;
            }
        }
        return null;
    }

    /** The {@code RenderTypes} factory a render-type provider resolves to ({@code ""} = unresolved). */
    private @NotNull String renderTypeFactoryName(@NotNull Handle renderTypeProvider) {
        if (VanillaSourceClasses.Types.RENDER_TYPES.equals(renderTypeProvider.getOwner()))
            return renderTypeProvider.getName();
        ClassNode owner = this.cache.load(renderTypeProvider.getOwner());
        MethodNode lambda = owner == null ? null
            : AsmKit.findMethod(owner, renderTypeProvider.getName(), renderTypeProvider.getDesc());
        if (lambda == null) return "";
        for (AbstractInsnNode in = lambda.instructions.getFirst(); in != null; in = in.getNext())
            if (in.getOpcode() == Opcodes.INVOKESTATIC && in instanceof MethodInsnNode mi
                && VanillaSourceClasses.Types.RENDER_TYPES.equals(mi.owner))
                return mi.name;
        return "";
    }

    /**
     * The {@code retainExactParts} subset a factory's {@code MeshTransformer} lambda bakes:
     * the string literals its lambda pushes before the {@code retainExactParts} call.
     * {@code null} when the factory has no retain lambda.
     */
    private @Nullable List<String> findRetainSubset(@NotNull LayerDefinitionIndex.Entry entry) {
        ClassNode factoryOwner = this.cache.load(entry.factoryClass());
        MethodNode factory = factoryOwner == null ? null
            : AsmKit.findMethod(factoryOwner, entry.factoryMethod(), entry.factoryDesc());
        if (factory == null) return null;
        Handle transformer = null;
        for (AbstractInsnNode in = factory.instructions.getFirst(); in != null; in = in.getNext())
            if (AsmKit.isLambdaInvokeDynamic(in) && in instanceof InvokeDynamicInsnNode indy) {
                transformer = AsmKit.extractLambdaHandle(indy);
                if (transformer != null) break;
            }
        if (transformer == null) return null;
        MethodNode lambda = AsmKit.findMethod(factoryOwner, transformer.getName(), transformer.getDesc());
        if (lambda == null) return null;
        List<String> retain = new ArrayList<>();
        boolean retained = false;
        for (AbstractInsnNode in = lambda.instructions.getFirst(); in != null; in = in.getNext()) {
            if (in.getOpcode() == Opcodes.INVOKEVIRTUAL && in instanceof MethodInsnNode mi
                && VanillaSourceClasses.Methods.RETAIN_EXACT_PARTS.equals(mi.name)) {
                retained = true;
                break;
            }
            String literal = AsmKit.readStringLiteral(in);
            if (literal != null) retain.add(literal);
        }
        return retained && !retain.isEmpty() ? retain : null;
    }

    /**
     * Evaluates an alpha-provider lambda at the frozen frame ({@code ageInTicks == 0},
     * state fields {@code 0}) with a small operand-stack interpreter - float constants,
     * zeroed loads / field reads, {@code fmul} / {@code fadd} / {@code fsub}, and the
     * {@code cos} / {@code sin} / {@code max} / {@code min} / {@code clamp} intrinsics. An
     * unsupported op returns {@code 0} so an un-evaluable pass drops rather than emitting a
     * guessed alpha.
     */
    private float evaluateFrozenAlpha(@NotNull Handle alphaProvider) {
        ClassNode owner = this.cache.load(alphaProvider.getOwner());
        MethodNode lambda = owner == null ? null
            : AsmKit.findMethod(owner, alphaProvider.getName(), alphaProvider.getDesc());
        if (lambda == null) return 0f;
        float frozen = EntityOverlayPolicies.FROZEN_FRAME.floatValue();
        Deque<Double> stack = new ArrayDeque<>();
        for (AbstractInsnNode in = lambda.instructions.getFirst(); in != null; in = in.getNext()) {
            int op = in.getOpcode();
            Float constant = AsmKit.readFloatLiteral(in);
            if (constant != null) {
                stack.push((double) constant);
                continue;
            }
            switch (op) {
                case Opcodes.FLOAD, Opcodes.ALOAD -> stack.push((double) frozen);
                case Opcodes.GETFIELD -> { popOrZero(stack); stack.push((double) frozen); }
                case Opcodes.FMUL -> { double b = popOrZero(stack), a = popOrZero(stack); stack.push(a * b); }
                case Opcodes.FADD -> { double b = popOrZero(stack), a = popOrZero(stack); stack.push(a + b); }
                case Opcodes.FSUB -> { double b = popOrZero(stack), a = popOrZero(stack); stack.push(a - b); }
                case Opcodes.F2D, Opcodes.D2F, Opcodes.I2F, Opcodes.F2I -> { }
                case Opcodes.INVOKESTATIC -> {
                    if (!(in instanceof MethodInsnNode mi) || !applyFloatIntrinsic(mi.name, stack)) return 0f;
                }
                case Opcodes.FRETURN, Opcodes.DRETURN -> {
                    return stack.isEmpty() ? 0f : (float) (double) stack.peek();
                }
                default -> {
                    if (op >= 0) return 0f;
                }
            }
        }
        return 0f;
    }

    /** Applies a recognised float intrinsic to the interpreter stack, or reports an unknown call. */
    private static boolean applyFloatIntrinsic(@NotNull String name, @NotNull Deque<Double> stack) {
        switch (name) {
            case "cos" -> stack.push(Math.cos(popOrZero(stack)));
            case "sin" -> stack.push(Math.sin(popOrZero(stack)));
            case "max" -> { double b = popOrZero(stack), a = popOrZero(stack); stack.push(Math.max(a, b)); }
            case "min" -> { double b = popOrZero(stack), a = popOrZero(stack); stack.push(Math.min(a, b)); }
            case "clamp" -> {
                double hi = popOrZero(stack), lo = popOrZero(stack), v = popOrZero(stack);
                stack.push(Math.max(lo, Math.min(hi, v)));
            }
            default -> { return false; }
        }
        return true;
    }

    /** Pops a value from the interpreter stack, treating an underflow as {@code 0}. */
    private static double popOrZero(@NotNull Deque<Double> stack) {
        Double value = stack.poll();
        return value == null ? 0.0 : value;
    }

    // ------------------------------------------------------------------------------------
    // villager multi-pass arm
    // ------------------------------------------------------------------------------------

    /**
     * The stacked path-category shape: one layer whose typed submit feeds two or more
     * string-literal categories into its own {@code (String, ...)} helpers, composing
     * {@code textures/entity/<prefix>/<category>/<id>.png} paths from a ctor-supplied
     * prefix. One row per category in bytecode order (the render order - the coplanar
     * tie-break); the zero-state id derives from the category's data-class default
     * {@code ResourceKey} and is existence-probed, so a NONE-defaulted pass (profession)
     * carries no texture and drops at zero state.
     *
     * <p>A submit that swaps one category's directory token on its age arm carries that
     * substitution as a {@code baby} node: the same probe run with the age token, plus the
     * alternate mesh's own cleared-bone root. The node is a delta - it names no geometry,
     * because a baby overlay materialises against the age axis' baby mesh, and everything it
     * omits is inherited from the row. It rides the FIRST pass, the one vanilla's mesh select
     * applies to and the one that already owns {@code no_hat_root}; a substitution naming any
     * other category is an unexpected vanilla shape, warned about and dropped rather than
     * attached to a later row.
     *
     * @return the pass rows, or {@code null} when the site is not category-shaped
     */
    private @Nullable List<JsonTree> resolveVillagerPasses(
        @NotNull EntityRendererResolver.LayerSite site,
        @NotNull ClassNode cn
    ) {
        MethodNode submit = typedSubmit(cn);
        if (submit == null || !ctorTakesString(cn)) return null;
        CategoryTokens tokens = collectCategoryTokens(submit, cn.name);
        List<String> categories = tokens.categories();
        if (categories.size() < 2) return null;
        String prefix = callSiteStringArg(site);
        if (prefix == null) {
            this.diagnostics.warn("category-pass layer '%s' has no call-site prefix literal - dropped",
                simpleName(site.layerClass()));
            return List.of();
        }
        List<String> defaultIds = dataClassDefaultIds(submit);
        String noHatRoot = null;
        String babyNoHatRoot = null;
        if (selectsModelBeforeFirstPass(submit)) {
            List<String> clearedRoots = noHatRootBones(site);
            if (clearedRoots.isEmpty()) {
                this.diagnostics.warn("category-pass layer '%s' selects a model but no ModelLayers field resolves a"
                    + " cleared bone - no_hat_root omitted", simpleName(site.layerClass()));
            } else {
                noHatRoot = clearedRoots.getFirst();
                if (clearedRoots.size() > 1) babyNoHatRoot = clearedRoots.getLast();
                if (clearedRoots.size() > 2) {
                    this.diagnostics.info("category-pass layer '%s' bakes %d cleared-bone meshes - the last is taken"
                        + " as the age root", simpleName(site.layerClass()), clearedRoots.size());
                }
            }
        } else {
            this.diagnostics.info("category-pass layer '%s' has no conditional model select before its first pass"
                + " - no_hat_root omitted", simpleName(site.layerClass()));
        }
        // The alternate mesh and the age substitution both belong to the FIRST pass - vanilla selects the
        // mesh once, before that pass, and its age arm only picks WHICH alternate mesh, never which row.
        // One predicate for both, so the two can never drift onto different rows.
        String babyCategory = tokens.babyCategory();
        if (babyCategory != null && !categories.getFirst().equals(tokens.substituted())) {
            this.diagnostics.warn("category-pass layer '%s' substitutes directory '%s' for '%s', which is not its"
                + " first pass - baby form dropped", simpleName(site.layerClass()), babyCategory, tokens.substituted());
            babyCategory = null;
        }
        String babyTexture = babyCategory == null ? null : probeCategoryTexture(prefix, babyCategory, defaultIds);
        JsonTree baby = babyCategory == null ? null : babyForm(babyTexture, babyNoHatRoot);
        List<JsonTree> rows = new ArrayList<>(categories.size());
        for (String category : categories) {
            boolean firstPass = rows.isEmpty();
            String texture = probeCategoryTexture(prefix, category, defaultIds);
            JsonTree node = row(site.layerClass(), site.layerIndex())
                .putIf("geometry", this.geometryRef.primaryKey())
                .putIf("texture", texture == null ? null : namespaced(texture))
                .put("texture_by", category)
                .putIf("no_hat_root", firstPass ? noHatRoot : null)
                .putIf("baby", firstPass ? baby : null);
            if (texture == null) node.put("skip_bounds", true);
            rows.add(node);
        }
        this.diagnostics.info("category passes: %s, prefix '%s' [D17]", categories, prefix);
        if (babyCategory != null) {
            this.diagnostics.info("category pass '%s' substitutes directory '%s' on its age arm -> %s",
                tokens.substituted(), babyCategory, babyTexture);
        }
        return rows;
    }

    /**
     * Probes the category's zero-state texture: the first
     * {@code textures/entity/<prefix>/<category>/<id>.png} the jar actually ships, in
     * default-id order.
     *
     * @param prefix the ctor-supplied path prefix
     * @param category the directory token to probe under
     * @param defaultIds the zero-state id candidates in declaration order
     * @return the raw jar path, or {@code null} when no candidate exists
     */
    private @Nullable String probeCategoryTexture(
        @NotNull String prefix,
        @NotNull String category,
        @NotNull List<String> defaultIds
    ) {
        for (String id : defaultIds) {
            String candidate = VanillaSourceClasses.Paths.TEXTURES_ENTITY + prefix + "/" + category + "/" + id + ".png";
            if (this.cache.hasEntry(VanillaSourceClasses.Paths.ASSETS_ROOT + candidate)) return candidate;
        }
        return null;
    }

    /**
     * The row's {@code baby} age delta - the members a baby render substitutes, geometry never
     * among them. Null when neither member resolved, so the key is absent rather than empty.
     *
     * @param texture the raw jar path of the probed age texture, or {@code null} when none exists
     * @param noHatRoot the cleared-bone root of the age alternate mesh, or {@code null} when the
     *     site bakes no separate one
     * @return the delta node, or {@code null} when it would be empty
     */
    private static @Nullable JsonTree babyForm(@Nullable String texture, @Nullable String noHatRoot) {
        if (texture == null && noHatRoot == null) return null;
        return JsonTree.object()
            .putIf("texture", texture == null ? null : namespaced(texture))
            .putIf("no_hat_root", noHatRoot);
    }

    /** Whether any ctor takes a plain {@code String} parameter (the path prefix). */
    private static boolean ctorTakesString(@NotNull ClassNode cn) {
        for (MethodNode method : cn.methods) {
            if (!AsmKit.INIT.equals(method.name)) continue;
            for (Type arg : AsmKit.argTypes(method.desc))
                if ("Ljava/lang/String;".equals(arg.getDescriptor())) return true;
        }
        return false;
    }

    /**
     * The category roster of a typed submit together with its age substitution.
     *
     * @param categories the pass categories in first-use order
     * @param babyCategory the directory token substituted on the {@code isBaby} arm, or
     *     {@code null} when the submit carries no age arm
     * @param substituted the category that token stands in for, or {@code null} when the else arm
     *     did not resolve to a literal
     */
    private record CategoryTokens(
        @NotNull List<String> categories,
        @Nullable String babyCategory,
        @Nullable String substituted
    ) {}

    /**
     * The distinct category literals of the typed submit, in first-use order - the pass
     * roster. A literal on the {@code isBaby}-true ternary arm (the {@code baby} texture
     * directory) is an age concern, not a pass category, and is excluded from the roster by its
     * {@code GETFIELD isBaby; IF*; LDC} shape; it is kept aside instead, paired with the
     * category it substitutes for, so the row that owns it can carry an age delta. Requires the
     * self String-arg helper gate the caller already applied.
     */
    private static @NotNull CategoryTokens collectCategoryTokens(@NotNull MethodNode submit, @NotNull String layerClass) {
        boolean helperSeen = false;
        for (AbstractInsnNode in = submit.instructions.getFirst(); in != null && !helperSeen; in = in.getNext())
            helperSeen = in.getOpcode() == Opcodes.INVOKEVIRTUAL && in instanceof MethodInsnNode mi
                && layerClass.equals(mi.owner) && mi.desc.startsWith("(Ljava/lang/String;");
        if (!helperSeen) return new CategoryTokens(List.of(), null, null);
        LinkedHashSet<String> out = new LinkedHashSet<>();
        String babyCategory = null;
        String substituted = null;
        for (AbstractInsnNode in = submit.instructions.getFirst(); in != null; in = in.getNext()) {
            String literal = AsmKit.readStringLiteral(in);
            if (literal == null || !isCategoryToken(literal)) continue;
            AbstractInsnNode branch = AsmKit.previousReal(in);
            AbstractInsnNode read = branch == null ? null : AsmKit.previousReal(branch);
            boolean babyArm = branch != null && (branch.getOpcode() == Opcodes.IFEQ || branch.getOpcode() == Opcodes.IFNE)
                && read instanceof FieldInsnNode fi && VanillaSourceClasses.Fields.IS_BABY.equals(fi.name);
            if (!babyArm) {
                out.add(literal);
                continue;
            }
            String elseArm = babyCategory == null ? elseArmLiteral(in) : null;
            if (elseArm == null) continue;
            babyCategory = literal;
            substituted = elseArm;
        }
        return new CategoryTokens(new ArrayList<>(out), babyCategory, substituted);
    }

    /**
     * The literal on the else arm of the ternary whose then arm pushed {@code babyLiteral} - the
     * category the age directory token stands in for. Reached structurally, through the
     * {@code GOTO} that skips the else arm.
     *
     * @param babyLiteral the {@code LDC} on the age arm
     * @return the else arm's own literal, or {@code null} when the chain is not a two-arm
     *     literal ternary
     */
    private static @Nullable String elseArmLiteral(@NotNull AbstractInsnNode babyLiteral) {
        AbstractInsnNode skip = AsmKit.nextReal(babyLiteral);
        if (skip == null || skip.getOpcode() != Opcodes.GOTO) return null;
        AbstractInsnNode elseArm = AsmKit.nextReal(skip);
        return elseArm == null ? null : AsmKit.readStringLiteral(elseArm);
    }

    /** A category token: lowercase word characters only, never a path fragment. */
    private static boolean isCategoryToken(@NotNull String literal) {
        if (literal.isEmpty() || literal.indexOf('/') >= 0 || literal.indexOf('.') >= 0) return false;
        for (int i = 0; i < literal.length(); i++) {
            char c = literal.charAt(i);
            if (c != '_' && (c < 'a' || c > 'z')) return false;
        }
        return true;
    }

    /** The plain string literal pushed in the site's ctor-arg region (the path prefix). */
    private static @Nullable String callSiteStringArg(@NotNull EntityRendererResolver.LayerSite site) {
        for (AbstractInsnNode in = site.allocation(); in != null && in != site.addLayer(); in = in.getNext()) {
            String literal = AsmKit.readStringLiteral(in);
            if (literal != null && isCategoryToken(literal)) return literal;
        }
        return null;
    }

    /**
     * Whether the typed submit picks between two models before its first pass - the shape that
     * proves an alternate mesh applies to the FIRST category row and to that row only. The pick
     * compiles to a boolean-branch ternary over two model references: an {@code IFEQ} whose
     * fall-through is an {@code ALOAD}, followed by the {@code GOTO} that skips the else arm and
     * the else arm's own {@code ALOAD}. Reaching the first cutout submit without that quad means
     * every pass draws the same mesh.
     *
     * @param submit the layer's typed submit
     * @return {@code true} when the model select precedes the first pass
     */
    private static boolean selectsModelBeforeFirstPass(@NotNull MethodNode submit) {
        for (AbstractInsnNode in = submit.instructions.getFirst(); in != null; in = in.getNext()) {
            if (in.getOpcode() == Opcodes.INVOKESTATIC && in instanceof MethodInsnNode mi
                && VanillaSourceClasses.Methods.RENDER_COLORED_CUTOUT_MODEL.equals(mi.name))
                return false;
            if (in.getOpcode() != Opcodes.IFEQ) continue;
            AbstractInsnNode thenArm = AsmKit.nextReal(in);
            if (thenArm == null || thenArm.getOpcode() != Opcodes.ALOAD) continue;
            AbstractInsnNode skip = AsmKit.nextReal(thenArm);
            if (skip == null || skip.getOpcode() != Opcodes.GOTO) continue;
            AbstractInsnNode elseArm = AsmKit.nextReal(skip);
            if (elseArm != null && elseArm.getOpcode() == Opcodes.ALOAD) return true;
        }
        return false;
    }

    /**
     * The bones whose subtrees the site's alternate meshes clear, in bake order, derived from the
     * renderer's own ctor-arg region: the alternate models are baked there from
     * {@code ModelLayers} fields, so each field in {@code [allocation .. addLayer)} is resolved
     * through the layer-definition index to its factory and the factory is read for its
     * cleared-child name. Bake order is the only discriminator vanilla gives - the adult mesh is
     * baked first and the age mesh last, the same convention the age axis' own pick relies on.
     *
     * @param site the layer site whose ctor-arg region holds the bakes
     * @return the cleared bone names in bake order, empty when no field resolves one
     */
    private @NotNull List<String> noHatRootBones(@NotNull EntityRendererResolver.LayerSite site) {
        List<String> bones = new ArrayList<>();
        for (AbstractInsnNode in = site.allocation(); in != null && in != site.addLayer(); in = in.getNext()) {
            if (!AsmKit.isGetStatic(in, VanillaSourceClasses.Types.MODEL_LAYERS) || !(in instanceof FieldInsnNode fi))
                continue;
            LayerDefinitionIndex.Entry entry = this.layerDefinitions.get(fi.name);
            if (entry == null) continue;
            String bone = clearedChildName(entry);
            if (bone != null) bones.add(bone);
        }
        return bones;
    }

    /**
     * The {@code PartDefinition.clearChild} argument a layer factory bakes - the subtree root the
     * mesh empties. Read from the factory body first, then from its inline {@code MeshTransformer}
     * lambdas, which is where a factory that clears through
     * {@code LayerDefinition.apply(mesh -> ...)} keeps the call. Only an owner-local synthetic
     * lambda body is walked; a method reference to a foreign class is skipped rather than read out
     * of the factory's own class.
     *
     * @param entry the layer-definition entry naming the factory
     * @return the cleared bone name, or {@code null} when the factory clears nothing
     */
    private @Nullable String clearedChildName(@NotNull LayerDefinitionIndex.Entry entry) {
        ClassNode factoryOwner = this.cache.load(entry.factoryClass());
        MethodNode factory = factoryOwner == null ? null
            : AsmKit.findMethod(factoryOwner, entry.factoryMethod(), entry.factoryDesc());
        if (factory == null) return null;
        String direct = clearedChildInBody(factory);
        if (direct != null) return direct;
        for (AbstractInsnNode in = factory.instructions.getFirst(); in != null; in = in.getNext()) {
            if (!AsmKit.isLambdaInvokeDynamic(in) || !(in instanceof InvokeDynamicInsnNode indy)) continue;
            Handle handle = AsmKit.extractLambdaHandle(indy);
            MethodNode lambda = handle == null || !handle.getOwner().equals(factoryOwner.name) ? null
                : AsmKit.findMethod(factoryOwner, handle.getName(), handle.getDesc());
            String nested = lambda == null ? null : clearedChildInBody(lambda);
            if (nested != null) return nested;
        }
        return null;
    }

    /** The name argument of the first {@code PartDefinition.clearChild} a body calls, or {@code null}. */
    private static @Nullable String clearedChildInBody(@NotNull MethodNode body) {
        String pending = null;
        for (AbstractInsnNode in = body.instructions.getFirst(); in != null; in = in.getNext()) {
            String literal = AsmKit.readStringLiteral(in);
            if (literal != null) {
                pending = literal;
                continue;
            }
            if (in.getOpcode() == Opcodes.INVOKEVIRTUAL && in instanceof MethodInsnNode mi
                && VanillaSourceClasses.Types.PART_DEFINITION.equals(mi.owner)
                && VanillaSourceClasses.Methods.CLEAR_CHILD.equals(mi.name))
                return pending;
        }
        return null;
    }

    /**
     * The zero-state id candidates: the data classes whose {@code Holder} accessors the
     * submit consumes declare their defaults as {@code ResourceKey} constants resolved
     * through a registry - each constant's owner {@code <clinit>} pairs it with its id
     * literal ({@code VillagerType.PLAINS} to {@code "plains"}).
     */
    private @NotNull List<String> dataClassDefaultIds(@NotNull MethodNode submit) {
        LinkedHashSet<String> owners = new LinkedHashSet<>();
        String holderReturn = ")" + VanillaSourceClasses.Descs.ref(VanillaSourceClasses.Types.HOLDER);
        for (AbstractInsnNode in = submit.instructions.getFirst(); in != null; in = in.getNext())
            if (in.getOpcode() == Opcodes.INVOKEVIRTUAL && in instanceof MethodInsnNode mi
                && mi.desc.endsWith(holderReturn))
                owners.add(mi.owner);
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        String keyRef = VanillaSourceClasses.Descs.ref(VanillaSourceClasses.Types.RESOURCE_KEY);
        for (String ownerName : owners) {
            ClassNode dataClass = this.cache.load(ownerName);
            if (dataClass == null) continue;
            for (MethodNode method : dataClass.methods)
                for (AbstractInsnNode in = method.instructions.getFirst(); in != null; in = in.getNext()) {
                    if (in.getOpcode() != Opcodes.GETSTATIC || !(in instanceof FieldInsnNode fi)) continue;
                    if (!keyRef.equals(fi.desc)) continue;
                    String id = clinitStringBinding(fi.owner, fi.name);
                    if (id != null) ids.add(id);
                }
        }
        return new ArrayList<>(ids);
    }

    /** The last string literal an owner {@code <clinit>} pairs with {@code PUTSTATIC <field>}. */
    private @Nullable String clinitStringBinding(@NotNull String ownerInternalName, @NotNull String fieldName) {
        ClassNode owner = this.cache.load(ownerInternalName);
        MethodNode clinit = owner == null ? null : AsmKit.findMethod(owner, AsmKit.CLINIT);
        if (clinit == null) return null;
        String pending = null;
        for (AbstractInsnNode in = clinit.instructions.getFirst(); in != null; in = in.getNext()) {
            String literal = AsmKit.readStringLiteral(in);
            if (literal != null) {
                pending = literal;
                continue;
            }
            if (AsmKit.isPutStatic(in, ownerInternalName, fieldName)) return pending;
        }
        return null;
    }

    // ------------------------------------------------------------------------------------
    // parameterized-binding arm (stray / bogged)
    // ------------------------------------------------------------------------------------

    /**
     * The clothing shape: the layer ctor takes {@code ModelLayerLocation} +
     * {@code Identifier} parameters and bakes the location PARAMETER; the concrete field
     * and texture come from the call-site region, order-independent by type. Any future
     * layer wired to the shape auto-resolves without an allowlist.
     */
    private @Nullable JsonTree resolveParameterizedBinding(
        @NotNull EntityRendererResolver.LayerSite site,
        @NotNull ClassNode cn
    ) {
        if (!bakesLocationParameter(cn)) return null;
        String mllRef = VanillaSourceClasses.Descs.ref(VanillaSourceClasses.Types.MODEL_LAYER_LOCATION);
        String modelLayerField = null;
        String textureOwner = null;
        String textureField = null;
        for (AbstractInsnNode in = site.allocation(); in != null && in != site.addLayer(); in = in.getNext()) {
            if (in.getOpcode() != Opcodes.GETSTATIC || !(in instanceof FieldInsnNode fi)) continue;
            if (modelLayerField == null
                && VanillaSourceClasses.Types.MODEL_LAYERS.equals(fi.owner)
                && mllRef.equals(fi.desc))
                modelLayerField = fi.name;
            else if (textureField == null && VanillaSourceClasses.Descs.IDENTIFIER_REF.equals(fi.desc)) {
                textureOwner = fi.owner;
                textureField = fi.name;
            }
        }
        if (modelLayerField == null || textureField == null) return null;
        String texture = chaseTextureFieldOwner(textureOwner, textureField);
        if (texture == null) return null;

        JsonTree node = row(site.layerClass(), site.layerIndex());
        MeshRef mesh = overlayMesh(modelLayerField);
        node.putIf("geometry", mesh.key());
        node.put("texture", namespaced(texture));
        ColorSource tint = extractCutoutTint(cn);
        if (tint.argb() != NO_TINT) node.putHex("tint", tint.argb());
        node.putIf("tint_by", tint.axisToken());
        node.putIf("pipeline", pipelineNode(
            this.traits.layerInvokes(cn.name, EntityPipelineTraits.Trait.NO_CARDINAL_LIGHTING),
            this.traits.classifyBlend(cn.name), 1f));
        putGrow(node, mesh.grow());
        return node;
    }

    /** The parameterized ctor shape: a location + identifier param pair, the location baked via {@code ALOAD}. */
    private static boolean bakesLocationParameter(@NotNull ClassNode cn) {
        String mllRef = VanillaSourceClasses.Descs.ref(VanillaSourceClasses.Types.MODEL_LAYER_LOCATION);
        for (MethodNode method : cn.methods) {
            if (!AsmKit.INIT.equals(method.name)) continue;
            Type[] args = AsmKit.argTypes(method.desc);
            int locationSlot = -1;
            boolean identifier = false;
            int slot = 1;   // slot 0 = this
            for (Type arg : args) {
                String desc = arg.getDescriptor();
                if (locationSlot < 0 && mllRef.equals(desc)) locationSlot = slot;
                else if (VanillaSourceClasses.Descs.IDENTIFIER_REF.equals(desc)) identifier = true;
                slot += arg.getSize();
            }
            if (locationSlot < 0 || !identifier) continue;
            for (AbstractInsnNode in = method.instructions.getFirst(); in != null; in = in.getNext()) {
                if (!AsmKit.isInvokeVirtual(in, VanillaSourceClasses.Types.ENTITY_MODEL_SET, VanillaSourceClasses.Methods.BAKE_LAYER)) continue;
                AbstractInsnNode prev = AsmKit.previousReal(in);
                if (prev instanceof VarInsnNode load && prev.getOpcode() == Opcodes.ALOAD && load.var == locationSlot)
                    return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------------------------
    // bespoke-equipment default-decor arm (trader llama)
    // ------------------------------------------------------------------------------------

    /**
     * The default-decor shape on a bespoke equipment layer: the typed submit reads a
     * default {@code EquipmentAssets} constant gated on a boolean state field whose
     * populating entity predicate is constant-true for THIS subject (the
     * {@code isTraderLlama()} dispatch - llama returns false, trader llama true; both share
     * the renderer). The decor row renders unconditionally at zero state, so the harness
     * reference keeps its carpet.
     */
    private @Nullable JsonTree resolveDefaultDecor(
        @NotNull EntityRendererResolver.LayerSite site,
        @NotNull ClassNode cn
    ) {
        MethodNode submit = typedSubmit(cn);
        if (submit == null) return null;
        String keyRef = VanillaSourceClasses.Descs.ref(VanillaSourceClasses.Types.RESOURCE_KEY);
        String assetConstant = null;
        String babyAssetConstant = null;
        String gateField = null;
        for (AbstractInsnNode in = submit.instructions.getFirst(); in != null; in = in.getNext()) {
            if (in.getOpcode() == Opcodes.GETFIELD && in instanceof FieldInsnNode fi && "Z".equals(fi.desc)
                && assetConstant == null
                && !VanillaSourceClasses.Fields.IS_BABY.equals(fi.name))
                gateField = fi.name;
            if (in.getOpcode() == Opcodes.GETSTATIC && in instanceof FieldInsnNode fi
                && VanillaSourceClasses.Types.EQUIPMENT_ASSETS.equals(fi.owner)
                && keyRef.equals(fi.desc)) {
                // The adult arm is the zero state; the isBaby arm's *_BABY asset drives the baby age delta.
                if (fi.name.contains("BABY")) { if (babyAssetConstant == null) babyAssetConstant = fi.name; }
                else if (assetConstant == null) assetConstant = fi.name;
            }
        }
        if (assetConstant == null) return null;
        if (gateField != null && !entityPredicateTrue(gateField)) {
            this.diagnostics.info("default decor gate '%s' constant-false for this subject - no decor row", gateField);
            return null;
        }
        String assetId = clinitStringBinding(VanillaSourceClasses.Types.EQUIPMENT_ASSETS, assetConstant);
        String layerTypeConstant = firstLayerTypeConstant(cn);
        String subdir = layerTypeConstant == null ? null
            : EntityEquipmentResolver.layerTypeSubdir(this.cache, layerTypeConstant);
        if (assetId == null || subdir == null) {
            this.diagnostics.warn("default decor asset/subdir unresolved (asset '%s', layerType '%s')",
                assetConstant, layerTypeConstant);
            return null;
        }

        JsonTree node = row(site.layerClass(), site.layerIndex());
        MeshRef mesh = overlayMesh(findCtorBakedField(cn));
        node.putIf("geometry", mesh.key());
        node.put("texture", namespaced(VanillaSourceClasses.Paths.TEXTURES_ENTITY
            + VanillaSourceClasses.Paths.EQUIPMENT_DIR + subdir + "/" + assetId + ".png"));
        putGrow(node, mesh.grow());
        if (EntityOverlayPolicies.DECOR_SKIP_BOUNDS.booleanValue()) node.put("skip_bounds", true);
        JsonTree baby = babyDecorNode(cn, subdir, babyAssetConstant);
        if (baby != null) node.put("baby", baby);
        this.diagnostics.info("default decor: %s/%s (gate '%s' constant-true)", subdir, assetId, gateField);
        return node;
    }

    /**
     * The {@code baby} age delta for a default-decor row: the {@code isBaby}-arm {@code *_BABY} equipment
     * asset's texture plus the baby decoration mesh's own inflate. Vanilla dresses a baby trader llama in
     * a distinct baby caparison ({@code BabyLlamaModel#createBodyLayer} at {@code CubeDeformation(0.2)},
     * asset {@code trader_llama_baby}) rather than the adult one ({@code LlamaModel#createBodyLayer} at
     * {@code 0.5}, asset {@code trader_llama}); the baby mesh itself is the row's {@code age.baby}
     * coordinate, so only the texture and grow differ. Null when the subject has no baby arm.
     *
     * @param cn the decor layer class
     * @param subdir the equipment-texture sub-directory shared with the adult row
     * @param babyAssetConstant the {@code *_BABY} {@code EquipmentAssets} constant, or {@code null} when absent
     * @return the baby delta node, or {@code null} when there is no baby arm
     */
    private @Nullable JsonTree babyDecorNode(
        @NotNull ClassNode cn,
        @NotNull String subdir,
        @Nullable String babyAssetConstant
    ) {
        if (babyAssetConstant == null) return null;
        String babyAssetId = clinitStringBinding(VanillaSourceClasses.Types.EQUIPMENT_ASSETS, babyAssetConstant);
        if (babyAssetId == null) {
            this.diagnostics.warn("baby decor asset '%s' unresolved - no baby decoration", babyAssetConstant);
            return null;
        }
        JsonTree baby = JsonTree.object();
        baby.put("texture", namespaced(VanillaSourceClasses.Paths.TEXTURES_ENTITY
            + VanillaSourceClasses.Paths.EQUIPMENT_DIR + subdir + "/" + babyAssetId + ".png"));
        String babyField = findCtorBakedBabyField(cn);
        LayerDefinitionIndex.Entry babyEntry = babyField == null ? null : this.layerDefinitions.get(babyField);
        if (babyEntry == null)
            this.diagnostics.warn("baby decor mesh for '%s' unresolved - inheriting the adult grow", babyAssetId);
        else
            putGrow(baby, nonZeroGrow(babyEntry.grow()));
        return baby;
    }

    /** The first {@code EquipmentClientInfo$LayerType} constant any method reads. */
    private static @Nullable String firstLayerTypeConstant(@NotNull ClassNode cn) {
        for (MethodNode method : cn.methods)
            for (AbstractInsnNode in = method.instructions.getFirst(); in != null; in = in.getNext())
                if (AsmKit.isGetStatic(in, VanillaSourceClasses.Types.EQUIPMENT_LAYER_TYPE))
                    return ((FieldInsnNode) in).name;
        return null;
    }

    /**
     * Whether the state boolean's populating entity predicate is constant-true for this
     * subject: {@code extractRenderState} binds the field from an
     * {@code entity.<m>()Z} dispatch, and the subject's entity-class hierarchy resolves
     * {@code <m>} to a constant {@code iconst_1; ireturn} body.
     */
    private boolean entityPredicateTrue(@NotNull String stateGateField) {
        String[] predicate = new String[1];
        AsmKit.walkSuperChain(this.cache, this.subject.rendererClass(), cn -> {
            if (predicate[0] != null) return;
            for (MethodNode method : cn.methods) {
                if (!VanillaSourceClasses.Methods.EXTRACT_RENDER_STATE.equals(method.name)) continue;
                String pendingCall = null;
                for (AbstractInsnNode in = method.instructions.getFirst(); in != null; in = in.getNext()) {
                    if (in.getOpcode() == Opcodes.INVOKEVIRTUAL && in instanceof MethodInsnNode mi
                        && mi.desc.endsWith(")Z")) {
                        pendingCall = mi.name;
                        continue;
                    }
                    if (in.getOpcode() == Opcodes.PUTFIELD && in instanceof FieldInsnNode fi) {
                        if (stateGateField.equals(fi.name) && pendingCall != null) {
                            predicate[0] = pendingCall;
                            return;
                        }
                        pendingCall = null;
                    }
                }
            }
        });
        if (predicate[0] == null) return false;
        MethodNode body = AsmKit.findMethodInHierarchy(this.cache, this.subject.entityClass(), predicate[0], "()Z");
        if (body == null) return false;
        Boolean constant = constantBooleanReturn(body);
        return constant != null && constant;
    }

    /** The constant of an {@code iconst_0/1; ireturn} body, or {@code null} for computed returns. */
    private static @Nullable Boolean constantBooleanReturn(@NotNull MethodNode method) {
        Boolean pending = null;
        for (AbstractInsnNode in = method.instructions.getFirst(); in != null; in = in.getNext()) {
            if (AsmKit.isPseudoNode(in)) continue;
            if (in.getOpcode() == Opcodes.ICONST_0) pending = Boolean.FALSE;
            else if (in.getOpcode() == Opcodes.ICONST_1) pending = Boolean.TRUE;
            else if (in.getOpcode() == Opcodes.IRETURN) return pending;
            else return null;
        }
        return null;
    }

    /**
     * The texture literal an owner's {@code <clinit>} binds into the named
     * {@code Identifier} field via the {@code LDC; withDefaultNamespace; PUTSTATIC} chain.
     */
    private @Nullable String chaseTextureFieldOwner(@NotNull String ownerInternalName, @NotNull String fieldName) {
        ClassNode owner = this.cache.load(ownerInternalName);
        MethodNode clinit = owner == null ? null : AsmKit.findMethod(owner, AsmKit.CLINIT);
        if (clinit == null) return null;
        String pendingPath = null;
        boolean pendingIdentifier = false;
        for (AbstractInsnNode in = clinit.instructions.getFirst(); in != null; in = in.getNext()) {
            String literal = readEntityTextureLiteral(in);
            if (literal != null) {
                pendingPath = literal;
                pendingIdentifier = false;
                continue;
            }
            if (AsmKit.isInvokeStatic(in, VanillaSourceClasses.Types.IDENTIFIER, VanillaSourceClasses.Methods.WITH_DEFAULT_NAMESPACE)) {
                pendingIdentifier = true;
                continue;
            }
            if (in.getOpcode() == Opcodes.PUTSTATIC
                && in instanceof FieldInsnNode fi
                && fieldName.equals(fi.name)
                && pendingPath != null && pendingIdentifier)
                return pendingPath;
        }
        return null;
    }

}
