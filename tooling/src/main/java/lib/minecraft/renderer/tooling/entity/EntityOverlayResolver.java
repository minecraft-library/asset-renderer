package lib.minecraft.renderer.tooling.entity;

import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.tooling.geometry.GeometryManifest;
import lib.minecraft.renderer.tooling.geometry.GeometryRequest;
import lib.minecraft.renderer.tooling.kernel.ClassKit;
import lib.minecraft.renderer.tooling.kernel.ClassNodeCache;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lib.minecraft.renderer.tooling.kernel.ToolingException;
import lib.minecraft.renderer.tooling.kernel.VanillaSourceClasses;
import lib.minecraft.renderer.tooling.vanilla.LayerDefinitionIndex;
import lib.minecraft.renderer.tooling.walk.AsmWalker;
import lib.minecraft.renderer.tooling.walk.Cells;
import lib.minecraft.renderer.tooling.walk.CommitWalk;
import lib.minecraft.renderer.tooling.walk.Insn;
import lib.minecraft.renderer.tooling.walk.Interp;
import lib.minecraft.renderer.tooling.walk.Match;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Node {@code overlays[]} - the generic overlay engine. The per-entity handling dissolves
 * into structural arms: the collar arm (wolf, cat), the enum-map detector (crackiness), the
 * composite gate's superclass walk plus ADDITIVE probe (creeper, wither), the
 * emissive-provider arm with retain-subset detection (warden, creaking, copper golem), the
 * villager multi-pass arm, the parameterized binding (stray, bogged), and the
 * bespoke-equipment default-decor arm (trader llama, via constant-boolean predicate
 * evaluation on the subject's entity class).
 * Eyes classify by the clinit texture-to-{@code RenderTypes}-factory shape, the factory vanilla
 * names an eye overlay for, and the pipeline traits it declares - never by a layer class name.
 *
 * <p>Zero-state byte-identity is the load-bearing doctrine: every row's absent / default
 * option renders identically to vanilla (crackiness NONE draws nothing, the uncharged
 * creeper drops the swirl, the NONE profession drops its pass, the white sheep stays
 * untinted).
 *
 * <p>Geometry: a row whose mesh entry shares the FAMILY's factory coordinate emits the
 * family key plus a row-level {@code grow} (the real CubeDeformation - creeper 2.0, fish
 * pattern 0.008, llama decor 0.5; the 0.001 depth clearance is never data); a distinct
 * factory registers its own {@link GeometryRequest} with the grow baked. Equipment and
 * block-decoration sites are handled elsewhere and are skipped here.
 */
final class EntityOverlayResolver {

    /**
     * Field descriptor of a {@code java.util.function.Function} ctor arg (JDK name, kit-local).
     */
    private static final @NotNull String FUNCTION_DESC = "Ljava/util/function/Function;";

    /**
     * Field descriptor of a {@code java.util.Map} static field (JDK name, kit-local).
     */
    private static final @NotNull String MAP_DESC = "Ljava/util/Map;";

    /**
     * The no-tint sentinel (multiplicative identity).
     */
    private static final int NO_TINT = 0xFFFFFFFF;

    /**
     * Descriptor of a no-argument {@code Identifier} accessor - the shape a texture component is
     * read through.
     */
    private static final @NotNull String IDENTIFIER_ACCESSOR_DESC =
        VanillaSourceClasses.Descs.of(VanillaSourceClasses.Descs.IDENTIFIER_REF);

    /** The caller label a stale eye-overlay factory coordinate is reported under. */
    private static final @NotNull String EYE_FACTORY = "the eye-overlay render type";

    private final @NotNull ClassNodeCache cache;
    private final @NotNull EntitySubject subject;
    private final @NotNull List<EntityRendererResolver.LayerSite> roster;
    private final @NotNull LayerDefinitionIndex layerDefinitions;
    private final @NotNull EntityGeometryRefResolver geometryRef;
    private final @NotNull EntityPipelineTraits traits;
    private final @NotNull GeometryManifest manifest;
    private final @NotNull Diagnostics diagnostics;

    EntityOverlayResolver(
        @NotNull EntityContext context,
        @NotNull List<EntityRendererResolver.LayerSite> roster,
        @NotNull EntityGeometryRefResolver geometryRef
    ) {
        this.cache = context.cache();
        this.subject = context.subject();
        this.roster = roster;
        this.layerDefinitions = context.indexes().layerDefinitions();
        this.geometryRef = geometryRef;
        this.traits = context.indexes().pipelineTraits();
        this.manifest = context.indexes().manifest();
        this.diagnostics = context.diagnostics();
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
            // Equipment (call-site LayerType consumers) and block-decoration layers are
            // handled by other resolvers and never emit here.
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
            List<JsonTree> emitted = resolveSite(site, cn, enumMap);
            // What this layer's render type translates its texture by, which every pass the site
            // emits carries: the offset is built into the pipeline the layer submits through, so it
            // belongs to the layer rather than to any one mesh it draws.
            JsonTree scroll = new EntityTextureScrollResolver(this.cache).resolve(site.layerClass());
            for (JsonTree row : emitted) {
                if (scroll != null) row.put("texture_scroll", scroll);
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
        return rows.isEmpty() ? null : JsonTree.arrayOf(rows);
    }

    /**
     * Dispatches one roster site through the structural arms; first claim wins.
     */
    private @NotNull List<JsonTree> resolveSite(
        @NotNull EntityRendererResolver.LayerSite site,
        @NotNull ClassNode cn,
        @Nullable EnumMapOverlay enumMap
    ) {
        // The collar claims first: its layer bakes a ModelLayers field and cutout-submits, so
        // the composite arm would otherwise claim the site and paint a coloured collar at the
        // zero state vanilla draws none for.
        if (isCollarShaped(cn)) {
            JsonTree collar = resolveCollarRow(site, cn);
            return collar == null ? List.of() : List.of(collar);
        }
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
     *
     * <p>A match routes the site to that row, tinted at render from {@code collar_color}, rather
     * than to the composite-overlay arm: vanilla draws no collar at a null collar colour, where a
     * composite overlay paints a coloured one at the zero state.
     */
    static boolean isCollarShaped(@NotNull ClassNode cn) {
        MethodNode submit = typedSubmit(cn);
        if (submit == null) return false;
        String dyeRef = VanillaSourceClasses.Descs.ref(VanillaSourceClasses.Types.DYE_COLOR);
        return AsmWalker.over(submit).any(in -> in.getOpcode() == Opcodes.GETFIELD
            && in instanceof FieldInsnNode fi
            && dyeRef.equals(fi.desc)
            && AsmWalker.after(in).real().limit(4).first(
                cursor -> cursor.getOpcode() == Opcodes.IFNULL || cursor.getOpcode() == Opcodes.IFNONNULL,
                cursor -> cursor.getOpcode() == Opcodes.ASTORE || cursor.getOpcode() == Opcodes.ALOAD
                    || cursor.getOpcode() == Opcodes.DUP) != null);
    }

    /**
     * The block-decoration qualifier: the typed submit reads a {@code BlockModelRenderState}-typed field.
     */
    static boolean readsBlockModelRenderState(@NotNull ClassNode cn) {
        MethodNode submit = typedSubmit(cn);
        if (submit == null) return false;
        String stateRef = VanillaSourceClasses.Descs.ref(VanillaSourceClasses.Types.BLOCK_MODEL_RENDER_STATE);
        return AsmWalker.over(submit).any(in -> in.getOpcode() == Opcodes.GETFIELD
            && in instanceof FieldInsnNode fi && stateRef.equals(fi.desc));
    }

    /**
     * The bespoke-equipment discriminator: any method references the LayerType enum.
     */
    static boolean referencesEquipmentLayerType(@NotNull ClassNode cn) {
        for (MethodNode method : cn.methods)
            if (AsmWalker.over(method).any(in -> AsmWalker.isGetStatic(in, VanillaSourceClasses.Types.EQUIPMENT_LAYER_TYPE)))
                return true;
        return false;
    }

    /**
     * One enum-map overlay: the typed submit feeds an enum-typed state field into a
     * {@code Map.get} on a static map of the class; the {@code <clinit>} binds enum
     * constants to texture literals.
     *
     * @param token the state field name (the {@code texture_by} axis token)
     * @param textures enum-constant name to raw texture path, in declaration order
     * @param babyTextures the same keys' aged-down paths where the map's value is a pair, empty
     *     where each value is a single texture
     */
    record EnumMapOverlay(
        @NotNull String token,
        @NotNull Map<String, String> textures,
        @NotNull Map<String, String> babyTextures
    ) {}

    /**
     * The two textures one enum key binds, as vanilla declares them: a value carrying a single
     * texture leaves {@link #baby} null, and one carrying an aged-down pair fills it.
     */
    private record TexturePair(@NotNull String adult, @Nullable String baby) {}

    /**
     * An enum-constant read - a {@code GETSTATIC} whose field descriptor is its own owner, which is
     * how {@link AsmWalker#readStaticEnumMap} recognises the key that opens each entry.
     */
    private static final @NotNull Match<FieldInsnNode> ENUM_CONSTANT_READ = Insn.of(FieldInsnNode.class,
        field -> field.getOpcode() == Opcodes.GETSTATIC && field.desc.equals("L" + field.owner + ";"));

    /**
     * Detects the enum-map shape on a layer class and reads its constant-to-texture map, or
     * {@code null} when the shape is absent.
     */
    static @Nullable EnumMapOverlay findEnumMapOverlay(@NotNull ClassNodeCache cache, @NotNull ClassNode cn) {
        MethodNode submit = typedSubmit(cn);
        if (submit == null) return null;
        return AsmWalker.over(submit).firstNotNull(in -> {
            if (in.getOpcode() != Opcodes.INVOKEINTERFACE || !(in instanceof MethodInsnNode mi)) return null;
            if (!"java/util/Map".equals(mi.owner) || !"get".equals(mi.name)) return null;
            String stateField = AsmWalker.before(in).firstNotNull(back ->
                back.getOpcode() == Opcodes.GETFIELD && back instanceof FieldInsnNode read
                    && read.desc.startsWith("L") ? read.name : null);
            String mapField = AsmWalker.before(in).firstNotNull(back ->
                back.getOpcode() == Opcodes.GETSTATIC && back instanceof FieldInsnNode map
                    && cn.name.equals(map.owner) && MAP_DESC.equals(map.desc) ? map.name : null);
            if (stateField == null || mapField == null) return null;
            Map<String, TexturePair> pairs = AsmWalker.readStaticEnumMap(cache, cn.name, mapField,
                EntityOverlayResolver::readEntityTexturePair);
            if (pairs.isEmpty()) return null;
            Map<String, String> textures = new LinkedHashMap<>();
            Map<String, String> babyTextures = new LinkedHashMap<>();
            pairs.forEach((key, pair) -> {
                textures.put(key, pair.adult());
                if (pair.baby() != null) babyTextures.put(key, pair.baby());
            });
            return new EnumMapOverlay(stateField, textures, babyTextures);
        });
    }

    /**
     * The textures one entry of an enum-keyed map binds, read off the first texture literal the
     * entry pushes.
     *
     * <p>A value carrying an aged-down sibling declares the two side by side - vanilla's horse
     * markings are a record of an adult {@code Identifier} and a baby one - so the second literal
     * inside the same entry is that sibling. The entry is bounded by what closes it: the next enum
     * key, or the store of the finished map, so a map whose values are single textures never reads
     * the next key's texture as this key's baby.
     */
    private static @Nullable TexturePair readEntityTexturePair(@NotNull AbstractInsnNode in) {
        String adult = readEntityTextureLiteral(in);
        if (adult == null) return null;
        String baby = AsmWalker.after(in)
            .until(Insn.of(AbstractInsnNode.class, node ->
                ENUM_CONSTANT_READ.matches(node) || node.getOpcode() == Opcodes.PUTSTATIC))
            .firstNotNull(EntityOverlayResolver::readEntityTextureLiteral);
        return new TexturePair(adult, baby);
    }

    // ------------------------------------------------------------------------------------
    // row assembly helpers
    // ------------------------------------------------------------------------------------

    /**
     * A fresh row carrying its source-class and layer-index provenance pair.
     */
    private static @NotNull JsonTree row(@NotNull String sourceClass, int layerIndex) {
        return JsonTree.object()
            .put("source", simpleName(sourceClass))
            .putInt("layer_index", layerIndex);
    }

    /**
     * The simple class name of a JVM internal name (nested classes keep their {@code $} form).
     */
    static @NotNull String simpleName(@NotNull String internalName) {
        return ClassKit.simpleName(internalName);
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

    /**
     * The grow when any component is non-zero, else {@code null}.
     */
    private static float @Nullable [] nonZeroGrow(float @NotNull [] grow) {
        return grow[0] == 0f && grow[1] == 0f && grow[2] == 0f ? null : grow;
    }

    /**
     * Emits {@code grow} as a scalar when uniform, a triplet when asymmetric.
     */
    private static void putGrow(@NotNull JsonTree row, float @Nullable [] grow) {
        if (grow == null) return;
        if (grow[0] == grow[1] && grow[1] == grow[2]) row.put("grow", grow[0]);
        else row.putFloats("grow", grow[0], grow[1], grow[2]);
    }

    /**
     * The {@code pipeline} node - {@code emissive} is the walked NO_CARDINAL_LIGHTING trait
     * (absent = shaded), {@code blend} the additive / translucent classification (absent =
     * normal), {@code alpha} the fractional frozen-frame opacity (absent = 1.0),
     * {@code depth_write} the pipeline's {@code DepthStencilState.writeDepth} (absent = writes,
     * which is what {@code DepthStencilState.DEFAULT} declares) and {@code sorted} its
     * {@code RenderSetup.sortOnUpload} (absent = drawn in submission order). Each member omits its
     * identity, so the node is null when the pass is an ordinary shaded source-over one.
     *
     * @param emissive whether the pass skips cardinal lighting
     * @param blend the blend token, or {@code null} for source-over
     * @param alpha the opacity multiplier
     * @param writesDepth whether the pass writes the depth buffer
     * @param sorted whether the pass's quads are drawn back-to-front
     */
    private static @Nullable JsonTree pipelineNode(boolean emissive, @Nullable String blend, float alpha,
                                                   boolean writesDepth, boolean sorted) {
        if (!emissive && blend == null && alpha >= 1f && writesDepth && !sorted) return null;
        JsonTree node = JsonTree.object();
        if (emissive) node.put("emissive", true);
        if (blend != null) node.put("blend", blend);
        if (alpha < 1f) node.put("alpha", alpha);
        if (!writesDepth) node.put("depth_write", false);
        if (sorted) node.put("sorted", true);
        return node;
    }

    /**
     * A raw {@code textures/entity/...png} literal without a format placeholder, or {@code null}.
     */
    private static @Nullable String readEntityTextureLiteral(@NotNull AbstractInsnNode in) {
        String literal = AsmWalker.stringLiteral(in);
        if (literal == null) return null;
        if (!literal.startsWith(VanillaSourceClasses.Paths.TEXTURES_ENTITY) || !literal.endsWith(".png")) return null;
        return literal.contains("%") ? null : literal;
    }

    /**
     * The factory gate: a pre-built RenderType binding is an eye / glow overlay only through a
     * factory vanilla names one for. Each declared name is re-entered on the class, so a rename
     * raises rather than dropping every glow overlay in silence.
     *
     * @param factoryName the {@code RenderTypes} factory the binding commits at
     * @return whether the factory is one vanilla names an eye overlay for
     * @throws ToolingException if the class or any declared factory is absent
     */
    private boolean isEyeFactory(@NotNull String factoryName) {
        ClassNode renderTypes = ClassKit.requireClass(this.cache, VanillaSourceClasses.Types.RENDER_TYPES, EYE_FACTORY);
        boolean match = false;
        for (String declared : VanillaSourceClasses.Methods.EYE_RENDER_TYPES) {
            ClassKit.requireMethod(renderTypes, declared, EYE_FACTORY);
            if (declared.equals(factoryName)) match = true;
        }
        return match;
    }

    /**
     * Prefixes a raw jar texture path with the vanilla namespace.
     */
    private static @NotNull String namespaced(@NotNull String rawPath) {
        return VanillaSourceClasses.Paths.MINECRAFT_NAMESPACE + rawPath;
    }

    // ------------------------------------------------------------------------------------
    // collar arm
    // ------------------------------------------------------------------------------------

    /**
     * The collar row (wolf, cat): the wearer's own mesh under the layer's clinit texture, tinted
     * at render from {@code collar_color}. The {@code when} is the transcription of the null-gated
     * {@code DyeColor} state read {@link #isCollarShaped} detects - vanilla draws no collar at a
     * null collar colour, so the row renders only while a collar is worn.
     */
    private @Nullable JsonTree resolveCollarRow(@NotNull EntityRendererResolver.LayerSite site, @NotNull ClassNode cn) {
        String texture = findFirstNonBabyTextureLiteral(cn);
        if (texture == null) {
            this.diagnostics.warn("collar layer '%s' has no clinit texture - row dropped",
                simpleName(site.layerClass()));
            return null;
        }
        this.diagnostics.info("collar row via null-gated DyeColor read");
        return row(site.layerClass(), site.layerIndex())
            .put("when", JsonTree.object().put("flag", "collared").put("value", true))
            .putIf("geometry", this.geometryRef.primaryKey())
            .put("texture", namespaced(texture))
            .put("tint_by", "collar_color");
    }

    // ------------------------------------------------------------------------------------
    // eyes-binding arm
    // ------------------------------------------------------------------------------------

    /**
     * The pre-built-RenderType eye shape: the {@code <clinit>} binds a texture literal
     * flowing into a {@code RenderTypes} factory returning a {@code RenderType} - detected
     * by SHAPE, admitted by the factory vanilla names an eye overlay for, and classified by
     * pipeline traits, never by a class or field name match.
     * Works unchanged on a renderer's own {@code <clinit>} (the dragon tail).
     */
    private @Nullable JsonTree resolveEyesBinding(@NotNull String sourceClass, int layerIndex, @NotNull ClassNode cn) {
        MethodNode clinit = ClassKit.findMethod(cn, ClassKit.CLINIT);
        if (clinit == null) return null;
        String renderTypeReturn = ")" + VanillaSourceClasses.Descs.ref(VanillaSourceClasses.Types.RENDER_TYPE);
        return AsmWalker.over(clinit)
            .latch(EntityOverlayResolver::readEntityTextureLiteral)
            .retain()
            .commitAt(MethodInsnNode.class, mi -> mi.getOpcode() == Opcodes.INVOKESTATIC
                && VanillaSourceClasses.Types.RENDER_TYPES.equals(mi.owner)
                && mi.desc.endsWith(renderTypeReturn))
            .firstNotNull(commit -> {
                String pendingTexture = commit.value();
                MethodInsnNode mi = commit.node();
                if (pendingTexture == null || !isEyeFactory(mi.name)) return null;
                var factoryTraits = this.traits.traitsOf(mi.name);
                // A factory declaring no pipeline trait pre-builds no glow overlay - skip it
                // without consuming the pending texture.
                if (factoryTraits.isEmpty()) return null;
                JsonTree node = row(sourceClass, layerIndex)
                    .putIf("geometry", this.geometryRef.primaryKey())
                    .put("texture", namespaced(pendingTexture));
                node.putIf("pipeline", pipelineNode(
                    factoryTraits.contains(EntityPipelineTraits.Trait.NO_CARDINAL_LIGHTING),
                    this.traits.blendTokenOf(mi.name), 1f,
                    this.traits.factoryWritesDepth(mi.name), this.traits.factorySortsQuads(mi.name)));
                this.diagnostics.info("eyes overlay '%s' via clinit RenderTypes.%s", simpleName(sourceClass), mi.name);
                return node;
            });
    }

    /**
     * The renderer-tail eyes row: the dragon-style renderer binds its eye RenderType in its
     * OWN {@code <clinit>} and dispatches from {@code submit} with no {@code addLayer}
     * site. Runs only when no same-geometry emissive row emitted, with no {@code layer_index}
     * - the row is not in vanilla's addLayer list (empty-vs-absent).
     */
    private @Nullable JsonTree resolveRendererTailEyes() {
        String renderTypeReturn = ")" + VanillaSourceClasses.Descs.ref(VanillaSourceClasses.Types.RENDER_TYPE);
        return AsmWalker.clinit(this.cache, this.subject.rendererClass())
            .latch(EntityOverlayResolver::readEntityTextureLiteral)
            .retain()
            .commitAt(MethodInsnNode.class, mi -> mi.getOpcode() == Opcodes.INVOKESTATIC
                && VanillaSourceClasses.Types.RENDER_TYPES.equals(mi.owner)
                && mi.desc.endsWith(renderTypeReturn))
            .firstNotNull(commit -> {
                String pendingTexture = commit.value();
                MethodInsnNode mi = commit.node();
                if (pendingTexture == null || !isEyeFactory(mi.name)) return null;
                var factoryTraits = this.traits.traitsOf(mi.name);
                if (factoryTraits.isEmpty()) return null;   // default pipeline - not a glow binding
                JsonTree node = JsonTree.object()
                    .put("source", simpleName(this.subject.rendererClass()))
                    .putIf("geometry", this.geometryRef.primaryKey())
                    .put("texture", namespaced(pendingTexture));
                node.putIf("pipeline", pipelineNode(
                    factoryTraits.contains(EntityPipelineTraits.Trait.NO_CARDINAL_LIGHTING),
                    this.traits.blendTokenOf(mi.name), 1f,
                    this.traits.factoryWritesDepth(mi.name), this.traits.factorySortsQuads(mi.name)));
                this.diagnostics.info("renderer-tail eyes via clinit RenderTypes.%s", mi.name);
                return node;
            });
    }

    // ------------------------------------------------------------------------------------
    // enum-map arm
    // ------------------------------------------------------------------------------------

    /**
     * The enum-map row (crackiness, the horse marking): no baked texture - the zero-state enum
     * value is absent from the map, so the default draws nothing - with the full value-to-path map
     * and a bounds skip (a zero-state-none overlay never contributes silhouette).
     *
     * <p>A map whose values carry an aged-down sibling gets a {@code baby} node holding that half,
     * which is what makes the pass reach a baby at all - an overlay with no {@code baby} node is
     * absent from the baby list by design. It inherits the row's axis and materialises against the
     * {@code age.baby} mesh, so it names neither.
     */
    private @NotNull JsonTree resolveEnumMapRow(
        @NotNull EntityRendererResolver.LayerSite site,
        @NotNull EnumMapOverlay enumMap
    ) {
        JsonTree node = row(site.layerClass(), site.layerIndex())
            .putIf("geometry", this.geometryRef.primaryKey())
            .put("texture_by", axisToken(enumMap.token()))
            .put("textures_by_value", byValue(enumMap.textures()))
            .put("skip_bounds", true);
        if (!enumMap.babyTextures().isEmpty())
            node.put("baby", JsonTree.object().put("textures_by_value", byValue(enumMap.babyTextures())));
        this.diagnostics.info("enum-map overlay: texture_by '%s', %d values%s",
            enumMap.token(), enumMap.textures().size(),
            enumMap.babyTextures().isEmpty() ? "" : " (+%d baby)".formatted(enumMap.babyTextures().size()));
        return node;
    }

    /** One value-to-path map, keys lowercased and paths namespaced. */
    private static @NotNull JsonTree byValue(@NotNull Map<String, String> textures) {
        JsonTree node = JsonTree.object();
        for (Map.Entry<String, String> entry : textures.entrySet())
            node.put(entry.getKey().toLowerCase(Locale.ROOT), namespaced(entry.getValue()));
        return node;
    }

    /**
     * The snake_case axis token of a camelCase state member ({@code patternColor} to {@code pattern_color}).
     */
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
        ColorSource tint = extractOverlayTint(cn);
        if (tint.argb() != NO_TINT) node.putHex("tint", tint.argb());
        node.putIf("tint_by", tint.axisToken());
        node.putIf("pipeline", pipelineNode(
            this.traits.layerInvokes(cn.name, EntityPipelineTraits.Trait.NO_CARDINAL_LIGHTING),
            this.traits.classifyBlend(cn.name), 1f,
            this.traits.layerWritesDepth(cn.name), this.traits.layerSortsQuads(cn.name)));
        putGrow(node, mesh.grow());
        node.putIf("baby", compositeBabyForm(cn));
        return node;
    }

    /**
     * The composite row's {@code baby} delta, for a layer that bakes a {@code ModelLayers} baby mesh
     * beside its adult one and forks on {@code isBaby} inside {@code submit} - the drowned's outer
     * shell, the sheep's wool. Null when the layer bakes no baby mesh, which is most of them.
     *
     * <p>The mesh is named rather than inherited because a baby pass is not always the baby body: the
     * drowned's outer shell is its own {@code LayerDefinition}, and its factory hardcodes two of the
     * head cubes' deformations instead of driving them off the parameter, so no inflate of the body
     * reaches it. Where the two DO coincide - vanilla registers the sheep's baby wool against the
     * very {@code LayerDefinition} its baby body uses - the walk mints the key the age axis already
     * registered and the pass stays coincident with the body it dresses.
     *
     * @param cn the layer class
     * @return the baby delta node, or {@code null} when the layer bakes no baby mesh
     */
    private @Nullable JsonTree compositeBabyForm(@NotNull ClassNode cn) {
        String babyField = findCtorBakedBabyField(cn);
        if (babyField == null) return null;
        return babyForm(findFirstBabyTextureLiteral(cn), null, overlayMesh(babyField).key());
    }

    /**
     * The first ctor-baked non-baby {@code ModelLayers} field (name filter), or {@code null}.
     */
    private static @Nullable String findCtorBakedField(@NotNull ClassNode cn) {
        for (MethodNode method : cn.methods) {
            if (!ClassKit.INIT.equals(method.name)) continue;
            // naming fallback - the adult mesh carries the row
            String baked = AsmWalker.over(method)
                .latch(in -> AsmWalker.isGetStatic(in, VanillaSourceClasses.Types.MODEL_LAYERS)
                    ? ((FieldInsnNode) in).name : null)
                .commitAt(MethodInsnNode.class, mi -> AsmWalker.isInvokeVirtual(mi,
                    VanillaSourceClasses.Types.ENTITY_MODEL_SET, VanillaSourceClasses.Methods.BAKE_LAYER))
                .firstNotNull(commit -> commit.value() != null && !commit.value().contains("BABY")
                    ? commit.value() : null);
            if (baked != null) return baked;
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
            if (!ClassKit.INIT.equals(method.name)) continue;
            String baked = AsmWalker.over(method)
                .latch(in -> AsmWalker.isGetStatic(in, VanillaSourceClasses.Types.MODEL_LAYERS)
                    ? ((FieldInsnNode) in).name : null)
                .commitAt(MethodInsnNode.class, mi -> AsmWalker.isInvokeVirtual(mi,
                    VanillaSourceClasses.Types.ENTITY_MODEL_SET, VanillaSourceClasses.Methods.BAKE_LAYER))
                .firstNotNull(commit -> commit.value() != null && commit.value().contains("BABY")
                    ? commit.value() : null);
            if (baked != null) return baked;
        }
        return null;
    }

    /**
     * Whether any hierarchy INSTANCE method invokes a {@code RenderTypes} factory or the cutout helper.
     */
    private boolean compositeGateAccepts(@NotNull ClassNode cn) {
        boolean[] accepted = {false};
        ClassKit.walkSuperChain(this.cache, cn.name, level -> {
            if (accepted[0]) return;
            for (MethodNode method : level.methods) {
                if ((method.access & Opcodes.ACC_STATIC) != 0 || ClassKit.INIT.equals(method.name)) continue;
                if (AsmWalker.over(method).any(in -> in.getOpcode() == Opcodes.INVOKESTATIC
                    && in instanceof MethodInsnNode mi
                    && (VanillaSourceClasses.Types.RENDER_TYPES.equals(mi.owner)
                        || VanillaSourceClasses.Methods.COLORED_CUTOUT_HELPER.equals(mi.name)))) {
                    accepted[0] = true;
                    return;
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
        JsonTree tinted = AsmWalker.over(submit).firstNotNull(in -> {
            if (!AsmWalker.isGetStatic(in, VanillaSourceClasses.Types.DYE_COLOR)) return null;
            AbstractInsnNode next = AsmWalker.nextReal(in);
            if (next != null && (next.getOpcode() == Opcodes.IF_ACMPEQ || next.getOpcode() == Opcodes.IF_ACMPNE)) {
                AbstractInsnNode before = AsmWalker.previousReal(in);
                if (before instanceof FieldInsnNode read && dyeRef.equals(read.desc))
                    return JsonTree.object().put("tinted", true);
            }
            return null;
        });
        if (tinted != null) return tinted;
        return AsmWalker.over(submit).firstNotNull(in -> {
            if (in.getOpcode() != Opcodes.GETFIELD || !(in instanceof FieldInsnNode fi)) return null;
            if (!"Z".equals(fi.desc) || !fi.name.startsWith("is")) return null;
            AbstractInsnNode branch = AsmWalker.nextReal(in);
            if (branch == null || (branch.getOpcode() != Opcodes.IFEQ && branch.getOpcode() != Opcodes.IFNE)) return null;
            AbstractInsnNode body = AsmWalker.nextReal(branch);
            if (body == null || body.getOpcode() != Opcodes.RETURN) return null;
            String token = axisToken(fi.name.substring(2));
            if (!EntityAxisPolicies.AXIS_NAME_VOCABULARY.strings().contains(token)) return null;
            // IFEQ skips the RETURN when the flag is false - the row renders at flag ==
            // false (the wool renders UNsheared); IFNE is the inverse shape.
            return JsonTree.object().put("flag", token).put("value", branch.getOpcode() != Opcodes.IFEQ);
        });
    }

    /**
     * The switch-dispatch texture axis: the typed submit switches over an enum-typed state
     * field selecting among several static {@code Identifier} fields (tropical fish
     * pattern). The token is the state field name, gated by the axis-name vocabulary.
     */
    private @Nullable String switchDispatchTextureBy(@NotNull ClassNode cn) {
        MethodNode submit = typedSubmit(cn);
        if (submit == null) return null;
        AsmWalker body = AsmWalker.over(submit);
        boolean hasSwitch = body.opcode(Opcodes.TABLESWITCH, Opcodes.LOOKUPSWITCH).any();
        long identifierReads = body.opcode(Opcodes.GETSTATIC).ofType(FieldInsnNode.class)
            .where(fi -> cn.name.equals(fi.owner) && VanillaSourceClasses.Descs.IDENTIFIER_REF.equals(fi.desc))
            .count();
        FieldInsnNode enumRead = body.opcode(Opcodes.GETFIELD).ofType(FieldInsnNode.class)
            .where(fi -> fi.desc.startsWith("L")
                && fi.desc.contains("$"))   // vanilla state enums are nested types (TropicalFish$Pattern)
            .first();
        if (!hasSwitch || identifierReads < 2 || enumRead == null) return null;
        String token = axisToken(enumRead.name);
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
     * Statically resolves the colour a layer submits its overlay with - the render-supplied source
     * first, then the fixed literal - or the no-tint identity when neither resolves.
     *
     * @param layerCn the layer class
     * @return the resolved tint plus its {@code tint_by} axis, or the no-tint identity
     */
    private @NotNull ColorSource extractOverlayTint(@NotNull ClassNode layerCn) {
        ColorSource sourced = extractCutoutTint(layerCn);
        if (sourced.argb() != NO_TINT) return sourced;
        return new ColorSource(submittedColorLiteral(layerCn), null);
    }

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
            if (ClassKit.INIT.equals(method.name) || ClassKit.CLINIT.equals(method.name)) continue;
            ColorSource sourced = AsmWalker.over(method).firstNotNull(in -> {
                if (in.getOpcode() != Opcodes.INVOKESTATIC || !(in instanceof MethodInsnNode mi)) return null;
                if (!VanillaSourceClasses.Methods.COLORED_CUTOUT_HELPER.equals(mi.name)) return null;
                return AsmWalker.before(in).firstNotNull(prev -> {
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
                    return null;
                });
            });
            if (sourced != null) return sourced;
        }
        return new ColorSource(NO_TINT, null);
    }

    /**
     * The fixed {@code color} argument of a {@code submitModel} call along the layer's super chain -
     * the constant standing directly after the {@code OverlayTexture.NO_OVERLAY} argument it follows
     * in vanilla's signature. This is how the energy swirl carries its half strength: it submits at
     * {@code 0xFF808080} with full alpha rather than at the white the colour-less overload passes, so
     * every texel it adds is scaled by {@code 128/255} before the blend, and an aura added at white
     * comes out just under twice as bright. The walk climbs the super chain because the constant sits
     * on {@code EnergySwirlLayer} while the roster names its two subclasses.
     *
     * <p>A white literal reads as the no-tint identity, so a layer submitting the default colour stays
     * indistinguishable from one naming no colour at all.
     *
     * @param layerCn the layer class
     * @return the submitted ARGB literal, or the no-tint identity when none resolves
     */
    private int submittedColorLiteral(@NotNull ClassNode layerCn) {
        int[] argb = {NO_TINT};
        ClassKit.walkSuperChain(this.cache, layerCn.name, level -> {
            if (argb[0] != NO_TINT) return;
            for (MethodNode method : level.methods) {
                if ((method.access & Opcodes.ACC_STATIC) != 0 || ClassKit.INIT.equals(method.name)) continue;
                Integer literal = AsmWalker.over(method).firstNotNull(in -> {
                    if (!AsmWalker.isGetStatic(in, VanillaSourceClasses.Types.OVERLAY_TEXTURE)
                        || !VanillaSourceClasses.Fields.NO_OVERLAY.equals(((FieldInsnNode) in).name)) return null;
                    AbstractInsnNode color = AsmWalker.nextReal(in);
                    return color == null ? null : AsmWalker.intLiteral(color);
                });
                if (literal != null) {
                    argb[0] = literal;
                    return;
                }
            }
        });
        return argb[0];
    }

    /**
     * The vocabulary-gated tint axis of a color-source member name ({@code getWoolColor} to {@code wool_color}).
     */
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
            : ClassKit.findMethod(stateClass, stateColorCall.name, stateColorCall.desc);
        if (stateMethod == null) return NO_TINT;
        String dyeRef = VanillaSourceClasses.Descs.ref(VanillaSourceClasses.Types.DYE_COLOR);
        Integer white = AsmWalker.over(stateMethod).firstNotNull(in -> {
            if (!AsmWalker.isInvokeVirtual(in, VanillaSourceClasses.Types.COLOR_LERPER_TYPE, VanillaSourceClasses.Methods.GET_COLOR)) return null;
            String dyeField = AsmWalker.before(in).firstNotNull(prev ->
                prev.getOpcode() == Opcodes.GETFIELD && prev instanceof FieldInsnNode fi && dyeRef.equals(fi.desc)
                    ? fi.name : null);
            if (dyeField == null) return null;
            if (!"WHITE".equals(fieldDefaultDyeConstant(stateClass, dyeField))) return null;
            return colorLerperWhiteReturn();
        });
        return white == null ? NO_TINT : white;
    }

    /**
     * The {@code state.<field>:I} evaluation: the renderer chain's
     * {@code extractRenderState} binds the field from
     * {@code DyeColor.getTextureDiffuseColor()}; at the zero-state WHITE dye that is the
     * WHITE diffuse constant.
     */
    private int resolveStateColorField(@NotNull String fieldName) {
        boolean[] diffuseBound = {false};
        ClassKit.walkSuperChain(this.cache, this.subject.rendererClass(), cn -> {
            if (diffuseBound[0]) return;
            for (MethodNode method : cn.methods) {
                if (!VanillaSourceClasses.Methods.EXTRACT_RENDER_STATE.equals(method.name)) continue;
                Boolean bound = AsmWalker.over(method)
                    .latch(in -> AsmWalker.isInvokeVirtual(in, VanillaSourceClasses.Types.DYE_COLOR,
                        VanillaSourceClasses.Methods.GET_TEXTURE_DIFFUSE_COLOR) ? Boolean.TRUE : null)
                    .commitAt(FieldInsnNode.class, fi -> fi.getOpcode() == Opcodes.PUTFIELD)
                    .firstNotNull(commit -> fieldName.equals(commit.node().name) && commit.value() != null
                        ? Boolean.TRUE : null);
                if (bound != null) {
                    diffuseBound[0] = true;
                    return;
                }
            }
        });
        if (!diffuseBound[0]) return NO_TINT;
        return EntityRenderTraitsResolver.whiteTextureDiffuseColor(this.cache);
    }

    /**
     * The {@code GETSTATIC DyeColor.<NAME>; PUTFIELD <field>} default initialiser constant, or {@code null}.
     */
    private static @Nullable String fieldDefaultDyeConstant(@NotNull ClassNode stateClass, @NotNull String fieldName) {
        MethodNode init = ClassKit.findMethod(stateClass, ClassKit.INIT);
        if (init == null) return null;
        return AsmWalker.over(init)
            .latch(in -> AsmWalker.isGetStatic(in, VanillaSourceClasses.Types.DYE_COLOR)
                ? ((FieldInsnNode) in).name : null)
            .commitAt(FieldInsnNode.class, fi -> fi.getOpcode() == Opcodes.PUTFIELD
                && fieldName.equals(fi.name))
            .firstNotNull(CommitWalk.Commit::value);
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
            : ClassKit.findMethod(lerper, VanillaSourceClasses.Methods.GET_MODIFIED_COLOR);
        if (method == null) return null;
        AbstractInsnNode compare = AsmWalker.over(method).first(in -> {
            if (!AsmWalker.isGetStatic(in, VanillaSourceClasses.Types.DYE_COLOR, "WHITE")) return false;
            AbstractInsnNode branch = AsmWalker.nextReal(in);
            return branch != null && (branch.getOpcode() == Opcodes.IF_ACMPNE || branch.getOpcode() == Opcodes.IF_ACMPEQ);
        });
        if (compare == null) return null;
        return AsmWalker.after(compare).firstNotNull(in -> {
            Integer literal = AsmWalker.intLiteral(in);
            if (literal == null) return null;
            AbstractInsnNode ret = AsmWalker.nextReal(in);
            return ret != null && ret.getOpcode() == Opcodes.IRETURN ? literal : null;
        });
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
            if (ClassKit.INIT.equals(method.name) || ClassKit.CLINIT.equals(method.name)) continue;
            String chased = AsmWalker.over(method).firstNotNull(in ->
                in.getOpcode() == Opcodes.GETSTATIC && in instanceof FieldInsnNode fi
                    && VanillaSourceClasses.Descs.IDENTIFIER_REF.equals(fi.desc)
                    ? chaseTextureFieldOwner(fi.owner, fi.name) : null);
            if (chased != null) return chased;
        }
        return null;
    }

    /**
     * The first {@code <clinit>} texture literal flowing through
     * {@code withDefaultNamespace} into a non-baby {@code Identifier} field.
     *
     * @param cn the class whose {@code <clinit>} is walked
     * @return the raw jar path, or {@code null} when the class binds none
     */
    static @Nullable String findFirstNonBabyTextureLiteral(@NotNull ClassNode cn) {
        return findFirstTextureLiteral(cn, false);
    }

    /**
     * The baby counterpart of {@link #findFirstNonBabyTextureLiteral} - the literal bound to a
     * {@code BABY} {@code Identifier} field, which a layer holding one mesh per age draws its baby
     * pass against ({@code drowned_outer_layer_baby}, {@code sheep_wool_baby}).
     *
     * @param cn the class whose {@code <clinit>} is walked
     * @return the raw jar path, or {@code null} when the class binds no baby texture
     */
    static @Nullable String findFirstBabyTextureLiteral(@NotNull ClassNode cn) {
        return findFirstTextureLiteral(cn, true);
    }

    /**
     * The first {@code <clinit>} texture literal flowing through {@code withDefaultNamespace} into
     * an {@code Identifier} field of the requested age. One walk serves both polarities: a field of
     * the other age discards the pending literal rather than ending the walk, so neither can read
     * the other's path.
     *
     * @param cn the class whose {@code <clinit>} is walked
     * @param baby whether a {@code BABY} field is the one to accept rather than the one to reject
     * @return the raw jar path, or {@code null} when the class binds none of that age
     */
    private static @Nullable String findFirstTextureLiteral(@NotNull ClassNode cn, boolean baby) {
        MethodNode clinit = ClassKit.findMethod(cn, ClassKit.CLINIT);
        if (clinit == null) return null;
        Cells.Latch<String> pendingPath = Cells.latch();
        Cells.Flag pendingIdentifier = Cells.flag();
        Cells.Latch<String> found = Cells.latch();
        // The PUTSTATIC arm is a hook rather than a commit: it manages its own arming and
        // reset, so an unarmed Identifier field passes by with the pending pair intact and
        // only the wrong-age arm discards it. The first accepted bind wins.
        AsmWalker.over(clinit)
            .on(Insn.of(AbstractInsnNode.class, in -> readEntityTextureLiteral(in) != null), in -> {
                String literal = readEntityTextureLiteral(in);
                if (literal == null) return;
                pendingPath.set(literal);
                pendingIdentifier.clear();
            })
            .on(Insn.invokeStatic(VanillaSourceClasses.Types.IDENTIFIER, VanillaSourceClasses.Methods.WITH_DEFAULT_NAMESPACE),
                mi -> pendingIdentifier.set())
            .on(Insn.of(FieldInsnNode.class, fi -> fi.getOpcode() == Opcodes.PUTSTATIC
                && VanillaSourceClasses.Descs.IDENTIFIER_REF.equals(fi.desc)), fi -> {
                String path = pendingPath.get();
                if (path == null || !pendingIdentifier.get()) return;
                if (fi.name.contains("BABY") == baby) {
                    if (found.get() == null) found.set(path);
                    return;
                }
                pendingPath.clear();
                pendingIdentifier.clear();
            })
            .run();
        return found.get();
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
        Map<Integer, String> slotFields = bakedModelSlots(site.method());
        Cells.ListCell<Handle> providerCell = Cells.list();
        Cells.Latch<String> pendingModelLayers = Cells.latch();
        Cells.Latch<String> modelFieldCell = Cells.latch();
        AsmWalker.from(site.allocation())
            .until(site.addLayer())
            .on(Insn.lambdaIndy(), indy -> {
                Handle handle = AsmWalker.extractLambdaHandle(indy);
                if (handle != null) providerCell.add(handle);
            })
            .on(Insn.of(MethodInsnNode.class, mi -> mi.getOpcode() == Opcodes.INVOKESTATIC
                && mi.desc.endsWith(")" + FUNCTION_DESC)), mi -> {
                Handle provided = providerFactoryLambda(mi);
                if (provided != null) providerCell.add(provided);
            })
            .on(Insn.getStatic(VanillaSourceClasses.Types.MODEL_LAYERS), fi -> pendingModelLayers.set(fi.name))
            .on(Insn.invokeVirtual(VanillaSourceClasses.Types.RENDERER_PROVIDER_CONTEXT, VanillaSourceClasses.Methods.BAKE_LAYER),
                mi -> {
                    String pending = pendingModelLayers.get();
                    if (pending != null) modelFieldCell.set(pending);
                })
            .on(Insn.of(VarInsnNode.class, load -> load.getOpcode() == Opcodes.ALOAD), load -> {
                if (modelFieldCell.get() != null) return;
                String stored = slotFields.get(load.var);
                if (stored != null) modelFieldCell.set(stored);
            })
            .run();
        List<Handle> providers = providerCell.values();
        String modelField = modelFieldCell.get();
        if (providers.size() < 3) {
            this.diagnostics.info("provider-shaped layer '%s' resolved %d of 3 providers - dropped",
                simpleName(site.layerClass()), providers.size());
            return List.of();
        }

        float alpha = evaluateFrozenAlpha(providers.get(1));
        if (alpha <= 0f) {
            this.diagnostics.info("provider layer '%s' frozen-frame alpha 0 - pass drops", simpleName(site.layerClass()));
            return List.of();
        }
        ProviderTexture texture = resolveProviderTexture(providers.getFirst());
        if (texture == null) {
            this.diagnostics.info("provider layer '%s' texture unresolved - dropped", simpleName(site.layerClass()));
            return List.of();
        }
        String factoryName = renderTypeFactoryName(providers.get(2));
        var factoryTraits = this.traits.traitsOf(factoryName);
        boolean emissive = factoryTraits.contains(EntityPipelineTraits.Trait.NO_CARDINAL_LIGHTING);
        String blend = this.traits.blendTokenOf(factoryName);
        boolean writesDepth = this.traits.factoryWritesDepth(factoryName);
        boolean sorted = this.traits.factorySortsQuads(factoryName);

        boolean primaryMesh = modelField == null || modelField.equals(this.geometryRef.primaryFieldName());
        JsonTree node = row(site.layerClass(), site.layerIndex());
        if (primaryMesh) {
            node.putIf("geometry", this.geometryRef.primaryKey())
                .put("texture", texture.path())
                .putIf("texture_by", texture.textureBy())
                .putIf("pipeline", pipelineNode(emissive, blend, 1f, writesDepth, sorted));
            return List.of(node);
        }
        if (alpha >= EntityOverlayPolicies.FULL_MESH_REUSE_ALPHA_EPSILON.floatValue()) {
            // Full-opacity glow reuses the FAMILY mesh - exact because the glow texture is
            // transparent outside its retained parts (a deliberate policy call, not a fallback).
            node.putIf("geometry", this.geometryRef.primaryKey())
                .put("texture", texture.path())
                .putIf("texture_by", texture.textureBy())
                .putIf("pipeline", pipelineNode(emissive, blend, 1f, writesDepth, sorted))
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
            .putIf("pipeline", pipelineNode(emissive, blend, alpha, writesDepth, sorted))
            .put("skip_bounds", true);
        JsonTree bones = node.childArray("retain_bones");
        for (String bone : retain) bones.add(bone);
        return List.of(node);
    }

    /**
     * The provider ctor shape: two or more {@code Function} args plus a model-class arg.
     */
    private static boolean isEmissiveProviderShaped(@NotNull ClassNode cn) {
        for (MethodNode method : cn.methods) {
            if (!ClassKit.INIT.equals(method.name)) continue;
            int functions = 0;
            boolean model = false;
            for (Type arg : ClassKit.argTypes(method.desc)) {
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

    /**
     * The first lambda handle inside a provider-factory method ({@code getEyeTextureLocationProvider}).
     */
    private @Nullable Handle providerFactoryLambda(@NotNull MethodInsnNode factoryCall) {
        ClassNode owner = this.cache.load(factoryCall.owner);
        MethodNode method = owner == null ? null : ClassKit.findMethod(owner, factoryCall.name, factoryCall.desc);
        if (method == null) return null;
        return AsmWalker.over(method).firstNotNull(in ->
            AsmWalker.isLambdaInvokeDynamic(in) && in instanceof InvokeDynamicInsnNode indy
                ? AsmWalker.extractLambdaHandle(indy) : null);
    }

    /**
     * Slot-to-{@code ModelLayers}-field bindings of pre-baked models {@code ASTORE}d in the ctor (warden).
     */
    private static @NotNull Map<Integer, String> bakedModelSlots(@NotNull MethodNode ctor) {
        Map<Integer, String> out = new LinkedHashMap<>();
        Cells.Latch<String> pendingField = Cells.latch();
        Cells.Flag baked = Cells.flag();
        AsmWalker.over(ctor)
            .feed(pendingField)
            .feed(baked)
            .on(Insn.getStatic(VanillaSourceClasses.Types.MODEL_LAYERS), fi -> {
                pendingField.set(fi.name);
                baked.clear();
            })
            .on(Insn.invokeVirtual(VanillaSourceClasses.Types.RENDERER_PROVIDER_CONTEXT, VanillaSourceClasses.Methods.BAKE_LAYER),
                mi -> {
                    if (pendingField.get() != null) baked.set();
                })
            .commitAt(Insn.of(VarInsnNode.class, store -> store.getOpcode() == Opcodes.ASTORE), store -> {
                String field = pendingField.get();
                if (baked.get() && field != null) out.put(store.var, field);
            })
            .run();
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
            : ClassKit.findMethod(owner, textureProvider.getName(), textureProvider.getDesc());
        if (lambda == null) return null;
        // Field-return shape: GETSTATIC <Identifier field> chased through the owner clinit.
        FieldInsnNode identifierRead = AsmWalker.over(lambda)
            .ofType(FieldInsnNode.class)
            .where(fi -> fi.getOpcode() == Opcodes.GETSTATIC
                && VanillaSourceClasses.Descs.IDENTIFIER_REF.equals(fi.desc))
            .first();
        if (identifierRead != null) {
            String chased = chaseTextureFieldOwner(identifierRead.owner, identifierRead.name);
            return chased == null ? null : new ProviderTexture(namespaced(chased), null);
        }
        String stateField = AsmWalker.over(lambda)
            .ofType(FieldInsnNode.class)
            .where(fi -> fi.getOpcode() == Opcodes.GETFIELD)
            .names()
            .first();
        if (stateField == null) return null;
        // State-driven dispatch: the axis token is the state field; the zero-state texture is the
        // one the first instance the dispatched data class builds carries (a holder allocates its
        // default-state instance first).
        String zeroState = zeroStateLiteral(lambda);
        if (zeroState == null) return null;
        String token = axisToken(stateField);
        boolean vocab = EntityAxisPolicies.AXIS_NAME_VOCABULARY.strings().contains(token);
        this.diagnostics.info("provider texture via state dispatch: texture_by '%s'", token);
        return new ProviderTexture(namespaced(zeroState), vocab ? token : null);
    }

    /**
     * The zero-state texture a state-driven provider dispatches from. The accessor the lambda ends
     * on names a component of the dispatched data class, and the first instance that class's holder
     * builds carries the literal bound to it - a holder allocates its default-state instance first.
     *
     * @param lambda the provider lambda body
     * @return the zero-state texture literal, or {@code null} when the lambda ends on no component
     *     accessor the holder builds a literal for
     */
    private @Nullable String zeroStateLiteral(@NotNull MethodNode lambda) {
        MethodInsnNode accessor = AsmWalker.over(lambda)
            .ofType(MethodInsnNode.class)
            .where(mi -> mi.getOpcode() == Opcodes.INVOKEVIRTUAL
                && IDENTIFIER_ACCESSOR_DESC.equals(mi.desc)
                && mi.owner.startsWith(VanillaSourceClasses.Types.MINECRAFT_ROOT))
            .last();
        if (accessor == null) return null;
        ClassNode dispatched = this.cache.load(accessor.owner);
        if (dispatched == null) return null;
        int slot = textureComponentIndex(dispatched, accessor.name);
        if (slot < 0) return null;
        MethodInsnNode holder = AsmWalker.over(lambda)
            .ofType(MethodInsnNode.class)
            .where(mi -> mi.getOpcode() == Opcodes.INVOKESTATIC
                && mi.desc.endsWith(")" + VanillaSourceClasses.Descs.ref(accessor.owner)))
            .first();
        if (holder == null) return null;
        // Each allocation opens at its NEW and closes at its <init>, so the literals a commit
        // carries are that instance's own, in component order.
        return AsmWalker.clinit(this.cache, holder.owner)
            .gather(EntityOverlayResolver::readEntityTextureLiteral)
            .resetAt(Insn.new_(accessor.owner))
            .commitAt(Insn.invokeSpecial(accessor.owner, ClassKit.INIT))
            .firstNotNull(commit -> commit.values().size() > slot ? commit.values().get(slot) : null);
    }

    /**
     * The position of a named component among a class's {@code Identifier}-typed instance fields,
     * or {@code -1} when it declares none under that name.
     *
     * @param dispatched the data class the accessor is declared on
     * @param componentName the accessor name
     * @return the zero-based position, or {@code -1}
     */
    private static int textureComponentIndex(@NotNull ClassNode dispatched, @NotNull String componentName) {
        int index = 0;
        for (FieldNode field : dispatched.fields) {
            if ((field.access & Opcodes.ACC_STATIC) != 0) continue;
            if (!VanillaSourceClasses.Descs.IDENTIFIER_REF.equals(field.desc)) continue;
            if (field.name.equals(componentName)) return index;
            index++;
        }
        return -1;
    }

    /**
     * The {@code RenderTypes} factory a render-type provider resolves to ({@code ""} = unresolved).
     */
    private @NotNull String renderTypeFactoryName(@NotNull Handle renderTypeProvider) {
        if (VanillaSourceClasses.Types.RENDER_TYPES.equals(renderTypeProvider.getOwner()))
            return renderTypeProvider.getName();
        ClassNode owner = this.cache.load(renderTypeProvider.getOwner());
        MethodNode lambda = owner == null ? null
            : ClassKit.findMethod(owner, renderTypeProvider.getName(), renderTypeProvider.getDesc());
        if (lambda == null) return "";
        String factory = AsmWalker.over(lambda).firstNotNull(in ->
            in.getOpcode() == Opcodes.INVOKESTATIC && in instanceof MethodInsnNode mi
                && VanillaSourceClasses.Types.RENDER_TYPES.equals(mi.owner) ? mi.name : null);
        return factory == null ? "" : factory;
    }

    /**
     * The {@code retainExactParts} subset a factory's {@code MeshTransformer} lambda bakes:
     * the string literals its lambda pushes before the {@code retainExactParts} call.
     * {@code null} when the factory has no retain lambda.
     */
    private @Nullable List<String> findRetainSubset(@NotNull LayerDefinitionIndex.Entry entry) {
        ClassNode factoryOwner = this.cache.load(entry.factoryClass());
        MethodNode factory = factoryOwner == null ? null
            : ClassKit.findMethod(factoryOwner, entry.factoryMethod(), entry.factoryDesc());
        if (factory == null) return null;
        Handle transformer = AsmWalker.over(factory).firstNotNull(in ->
            AsmWalker.isLambdaInvokeDynamic(in) && in instanceof InvokeDynamicInsnNode indy
                ? AsmWalker.extractLambdaHandle(indy) : null);
        if (transformer == null) return null;
        MethodNode lambda = ClassKit.findMethod(factoryOwner, transformer.getName(), transformer.getDesc());
        if (lambda == null) return null;
        CommitWalk.Commit<MethodInsnNode, String> retained = AsmWalker.over(lambda)
            .gather(AsmWalker::stringLiteral)
            .commitAt(MethodInsnNode.class, mi -> mi.getOpcode() == Opcodes.INVOKEVIRTUAL
                && VanillaSourceClasses.Methods.RETAIN_EXACT_PARTS.equals(mi.name))
            .first();
        if (retained == null) return null;
        List<String> retain = retained.values();
        return retain.isEmpty() ? null : retain;
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
            : ClassKit.findMethod(owner, alphaProvider.getName(), alphaProvider.getDesc());
        if (lambda == null) return 0f;
        float frozen = EntityOverlayPolicies.FROZEN_FRAME.floatValue();
        Interp<Double> machine = Interp.of(new FrozenAlphaDomain(), Interp.OnUnknown.ZERO, Interp.Width.FLOAT_AS_DOUBLE);
        // The machine owns the stack - float-constant pushes, the fmul / fadd / fsub arithmetic
        // and the four pass-through width conversions all land in its step (an empty pop answers
        // the domain's zero) before the dispatch below sees the node. The dispatch recognises
        // what matters to the flow: the frozen value standing in for every load and field read -
        // a load swaps out the slot view the step pushed, a field read pops its receiver - the
        // name-matched intrinsics, the frozen-frame return off the stack top, and the 0f abort
        // for every other real opcode.
        Float alpha = AsmWalker.over(lambda)
            .drive(machine)
            .firstNotNull(in -> {
                int op = in.getOpcode();
                if (AsmWalker.floatLiteral(in) != null) return null;
                switch (op) {
                    case Opcodes.FLOAD, Opcodes.ALOAD, Opcodes.GETFIELD -> {
                        machine.pop();
                        machine.push((double) frozen);
                    }
                    case Opcodes.FMUL, Opcodes.FADD, Opcodes.FSUB,
                         Opcodes.F2D, Opcodes.D2F, Opcodes.I2F, Opcodes.F2I -> { }
                    case Opcodes.INVOKESTATIC -> {
                        if (!(in instanceof MethodInsnNode mi) || !applyFloatIntrinsic(mi.name, machine)) return 0f;
                    }
                    case Opcodes.FRETURN, Opcodes.DRETURN -> {
                        return (float) (double) machine.peek();
                    }
                    default -> {
                        if (op >= 0) return 0f;
                    }
                }
                return null;
            });
        return alpha == null ? 0f : alpha;
    }

    /**
     * Applies a recognised float intrinsic to the machine's stack, or reports an unknown call.
     */
    private static boolean applyFloatIntrinsic(@NotNull String name, @NotNull Interp<Double> machine) {
        switch (name) {
            case "cos" -> machine.push(Math.cos(machine.pop()));
            case "sin" -> machine.push(Math.sin(machine.pop()));
            case "max" -> { double b = machine.pop(), a = machine.pop(); machine.push(Math.max(a, b)); }
            case "min" -> { double b = machine.pop(), a = machine.pop(); machine.push(Math.min(a, b)); }
            case "clamp" -> {
                double hi = machine.pop(), lo = machine.pop(), v = machine.pop();
                machine.push(Math.max(lo, Math.min(hi, v)));
            }
            default -> { return false; }
        }
        return true;
    }

    /**
     * The frozen-alpha value model: float literals via {@link AsmWalker#floatLiteral} widened
     * to double, the {@code fmul} / {@code fadd} / {@code fsub} arithmetic, the four
     * pass-through width conversions, and a zero for every empty pop - the un-evaluable
     * answer, never a fault.
     */
    private static final class FrozenAlphaDomain implements Interp.Domain<Double> {

        /**
         * The unknown placeholder, recognised by identity - a distinct box no evaluated value
         * can alias. It reaches the stack only where the dispatch aborts the evaluation at the
         * same node, or under a load whose dispatch arm swaps it for the frozen value, so it
         * never survives into a completing evaluation.
         */
        private static final @NotNull Double UNKNOWN = Double.valueOf(Double.NaN);

        @Override
        public @Nullable Double decode(@NotNull AbstractInsnNode node) {
            Float constant = AsmWalker.floatLiteral(node);
            return constant == null ? null : (double) constant;
        }

        @Override
        public @NotNull Double unknown() {
            return UNKNOWN;
        }

        @Override
        public @NotNull Double underflow() {
            return 0.0;
        }

        @Override
        public @Nullable Double binary(int opcode, @NotNull Double left, @NotNull Double right) {
            return switch (opcode) {
                case Opcodes.FMUL -> left * right;
                case Opcodes.FADD -> left + right;
                case Opcodes.FSUB -> left - right;
                default -> null;
            };
        }

        @Override
        public @Nullable Double unary(int opcode, @NotNull Double operand) {
            return switch (opcode) {
                case Opcodes.F2D, Opcodes.D2F, Opcodes.I2F, Opcodes.F2I -> operand;
                default -> null;
            };
        }

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
        JsonTree baby = babyCategory == null ? null : babyForm(babyTexture, babyNoHatRoot, null);
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
        this.diagnostics.info("category passes: %s, prefix '%s'", categories, prefix);
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
     * The row's {@code baby} age delta - the members a baby render substitutes. Null when none
     * resolved, so the key is absent rather than empty.
     *
     * @param texture the raw jar path of the probed age texture, or {@code null} when none exists
     * @param noHatRoot the cleared-bone root of the age alternate mesh, or {@code null} when the
     *     site bakes no separate one
     * @param geometry the baby mesh coordinate, or {@code null} to materialise against the
     *     {@code age.baby} mesh the way a decor delta does
     * @return the delta node, or {@code null} when it would be empty
     */
    private static @Nullable JsonTree babyForm(
        @Nullable String texture, @Nullable String noHatRoot, @Nullable String geometry
    ) {
        if (texture == null && noHatRoot == null && geometry == null) return null;
        return JsonTree.object()
            .putIf("geometry", geometry)
            .putIf("texture", texture == null ? null : namespaced(texture))
            .putIf("no_hat_root", noHatRoot);
    }

    /**
     * Whether any ctor takes a plain {@code String} parameter (the path prefix).
     */
    private static boolean ctorTakesString(@NotNull ClassNode cn) {
        for (MethodNode method : cn.methods) {
            if (!ClassKit.INIT.equals(method.name)) continue;
            for (Type arg : ClassKit.argTypes(method.desc))
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
        boolean helperSeen = AsmWalker.over(submit).any(in ->
            in.getOpcode() == Opcodes.INVOKEVIRTUAL && in instanceof MethodInsnNode mi
                && layerClass.equals(mi.owner) && mi.desc.startsWith("(Ljava/lang/String;"));
        if (!helperSeen) return new CategoryTokens(List.of(), null, null);
        Cells.ListCell<String> categories = Cells.list();
        Cells.Latch<String> babyCategory = Cells.latch();
        Cells.Latch<String> substituted = Cells.latch();
        AsmWalker.over(submit)
            .on(Insn.of(AbstractInsnNode.class, in -> {
                String literal = AsmWalker.stringLiteral(in);
                return literal != null && isCategoryToken(literal);
            }), in -> {
                String literal = AsmWalker.stringLiteral(in);
                if (literal == null) return;
                AbstractInsnNode branch = AsmWalker.previousReal(in);
                AbstractInsnNode read = branch == null ? null : AsmWalker.previousReal(branch);
                boolean babyArm = branch != null && (branch.getOpcode() == Opcodes.IFEQ || branch.getOpcode() == Opcodes.IFNE)
                    && read instanceof FieldInsnNode fi && VanillaSourceClasses.Fields.IS_BABY.equals(fi.name);
                if (!babyArm) {
                    categories.add(literal);
                    return;
                }
                String elseArm = babyCategory.get() == null ? elseArmLiteral(in) : null;
                if (elseArm == null) return;
                babyCategory.set(literal);
                substituted.set(elseArm);
            })
            .run();
        return new CategoryTokens(new ArrayList<>(new LinkedHashSet<>(categories.values())),
            babyCategory.get(), substituted.get());
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
        AbstractInsnNode skip = AsmWalker.nextReal(babyLiteral);
        if (skip == null || skip.getOpcode() != Opcodes.GOTO) return null;
        AbstractInsnNode elseArm = AsmWalker.nextReal(skip);
        return elseArm == null ? null : AsmWalker.stringLiteral(elseArm);
    }

    /**
     * A category token: lowercase word characters only, never a path fragment.
     */
    private static boolean isCategoryToken(@NotNull String literal) {
        if (literal.isEmpty() || literal.indexOf('/') >= 0 || literal.indexOf('.') >= 0) return false;
        for (int i = 0; i < literal.length(); i++) {
            char c = literal.charAt(i);
            if (c != '_' && (c < 'a' || c > 'z')) return false;
        }
        return true;
    }

    /**
     * The plain string literal pushed in the site's ctor-arg region (the path prefix).
     */
    private static @Nullable String callSiteStringArg(@NotNull EntityRendererResolver.LayerSite site) {
        return AsmWalker.from(site.allocation())
            .until(site.addLayer())
            .mapNotNull(AsmWalker::stringLiteral)
            .first(EntityOverlayResolver::isCategoryToken);
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
        return AsmWalker.over(submit).first(
            in -> {
                if (in.getOpcode() != Opcodes.IFEQ) return false;
                AbstractInsnNode thenArm = AsmWalker.nextReal(in);
                if (thenArm == null || thenArm.getOpcode() != Opcodes.ALOAD) return false;
                AbstractInsnNode skip = AsmWalker.nextReal(thenArm);
                if (skip == null || skip.getOpcode() != Opcodes.GOTO) return false;
                AbstractInsnNode elseArm = AsmWalker.nextReal(skip);
                return elseArm != null && elseArm.getOpcode() == Opcodes.ALOAD;
            },
            in -> !(in.getOpcode() == Opcodes.INVOKESTATIC && in instanceof MethodInsnNode mi
                && VanillaSourceClasses.Methods.RENDER_COLORED_CUTOUT_MODEL.equals(mi.name))) != null;
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
        return AsmWalker.from(site.allocation())
            .until(site.addLayer())
            .ofType(FieldInsnNode.class)
            .where(fi -> fi.getOpcode() == Opcodes.GETSTATIC
                && fi.owner.equals(VanillaSourceClasses.Types.MODEL_LAYERS))
            .mapNotNull(fi -> this.layerDefinitions.get(fi.name))
            .mapNotNull(this::clearedChildName)
            .toList();
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
            : ClassKit.findMethod(factoryOwner, entry.factoryMethod(), entry.factoryDesc());
        if (factory == null) return null;
        String direct = clearedChildInBody(factory);
        if (direct != null) return direct;
        return AsmWalker.over(factory).firstNotNull(in -> {
            if (!AsmWalker.isLambdaInvokeDynamic(in) || !(in instanceof InvokeDynamicInsnNode indy)) return null;
            Handle handle = AsmWalker.extractLambdaHandle(indy);
            MethodNode lambda = handle == null || !handle.getOwner().equals(factoryOwner.name) ? null
                : ClassKit.findMethod(factoryOwner, handle.getName(), handle.getDesc());
            return lambda == null ? null : clearedChildInBody(lambda);
        });
    }

    /**
     * The name argument of the first {@code PartDefinition.clearChild} a body calls, or {@code null}.
     */
    private static @Nullable String clearedChildInBody(@NotNull MethodNode body) {
        CommitWalk.Commit<MethodInsnNode, String> commit = AsmWalker.over(body)
            .latch(AsmWalker::stringLiteral)
            .commitAt(Insn.invokeVirtual(VanillaSourceClasses.Types.PART_DEFINITION, VanillaSourceClasses.Methods.CLEAR_CHILD))
            .first();
        return commit == null ? null : commit.value();
    }

    /**
     * The zero-state id candidates: the data classes whose {@code Holder} accessors the
     * submit consumes declare their defaults as {@code ResourceKey} constants resolved
     * through a registry - each constant's owner {@code <clinit>} pairs it with its id
     * literal ({@code VillagerType.PLAINS} to {@code "plains"}).
     */
    private @NotNull List<String> dataClassDefaultIds(@NotNull MethodNode submit) {
        String holderReturn = ")" + VanillaSourceClasses.Descs.ref(VanillaSourceClasses.Types.HOLDER);
        Set<String> owners = AsmWalker.over(submit)
            .ofType(MethodInsnNode.class)
            .where(mi -> mi.getOpcode() == Opcodes.INVOKEVIRTUAL && mi.desc.endsWith(holderReturn))
            .map(mi -> mi.owner)
            .toSet();
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        String keyRef = VanillaSourceClasses.Descs.ref(VanillaSourceClasses.Types.RESOURCE_KEY);
        for (String ownerName : owners) {
            ClassNode dataClass = this.cache.load(ownerName);
            if (dataClass == null) continue;
            for (MethodNode method : dataClass.methods) {
                AsmWalker.over(method)
                    .ofType(FieldInsnNode.class)
                    .where(fi -> fi.getOpcode() == Opcodes.GETSTATIC && keyRef.equals(fi.desc))
                    .mapNotNull(fi -> clinitStringBinding(fi.owner, fi.name))
                    .forEach(ids::add);
            }
        }
        return new ArrayList<>(ids);
    }

    /**
     * The last string literal an owner {@code <clinit>} pairs with {@code PUTSTATIC <field>}.
     */
    private @Nullable String clinitStringBinding(@NotNull String ownerInternalName, @NotNull String fieldName) {
        CommitWalk.Commit<FieldInsnNode, String> commit = AsmWalker.clinit(this.cache, ownerInternalName)
            .latch(AsmWalker::stringLiteral)
            .commitAt(Insn.putStatic(ownerInternalName, fieldName))
            .first();
        return commit == null ? null : commit.value();
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
        FieldInsnNode modelLayerBinding = AsmWalker.from(site.allocation())
            .until(site.addLayer())
            .ofType(FieldInsnNode.class)
            .where(fi -> fi.getOpcode() == Opcodes.GETSTATIC
                && VanillaSourceClasses.Types.MODEL_LAYERS.equals(fi.owner)
                && mllRef.equals(fi.desc))
            .first();
        FieldInsnNode textureBinding = AsmWalker.from(site.allocation())
            .until(site.addLayer())
            .ofType(FieldInsnNode.class)
            .where(fi -> fi.getOpcode() == Opcodes.GETSTATIC
                && VanillaSourceClasses.Descs.IDENTIFIER_REF.equals(fi.desc))
            .first();
        if (modelLayerBinding == null || textureBinding == null) return null;
        String modelLayerField = modelLayerBinding.name;
        String texture = chaseTextureFieldOwner(textureBinding.owner, textureBinding.name);
        if (texture == null) return null;

        JsonTree node = row(site.layerClass(), site.layerIndex());
        MeshRef mesh = overlayMesh(modelLayerField);
        node.putIf("geometry", mesh.key());
        node.put("texture", namespaced(texture));
        ColorSource tint = extractOverlayTint(cn);
        if (tint.argb() != NO_TINT) node.putHex("tint", tint.argb());
        node.putIf("tint_by", tint.axisToken());
        node.putIf("pipeline", pipelineNode(
            this.traits.layerInvokes(cn.name, EntityPipelineTraits.Trait.NO_CARDINAL_LIGHTING),
            this.traits.classifyBlend(cn.name), 1f,
            this.traits.layerWritesDepth(cn.name), this.traits.layerSortsQuads(cn.name)));
        putGrow(node, mesh.grow());
        return node;
    }

    /**
     * The parameterized ctor shape: a location + identifier param pair, the location baked via {@code ALOAD}.
     */
    private static boolean bakesLocationParameter(@NotNull ClassNode cn) {
        String mllRef = VanillaSourceClasses.Descs.ref(VanillaSourceClasses.Types.MODEL_LAYER_LOCATION);
        for (MethodNode method : cn.methods) {
            if (!ClassKit.INIT.equals(method.name)) continue;
            Type[] args = ClassKit.argTypes(method.desc);
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
            int location = locationSlot;
            if (AsmWalker.over(method).any(in -> {
                if (!AsmWalker.isInvokeVirtual(in, VanillaSourceClasses.Types.ENTITY_MODEL_SET, VanillaSourceClasses.Methods.BAKE_LAYER)) return false;
                AbstractInsnNode prev = AsmWalker.previousReal(in);
                return prev instanceof VarInsnNode load && prev.getOpcode() == Opcodes.ALOAD && load.var == location;
            })) return true;
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
        // The adult arm is the zero state; the isBaby arm's *_BABY asset drives the baby age delta.
        FieldInsnNode assetRead = AsmWalker.over(submit)
            .getStatic(VanillaSourceClasses.Types.EQUIPMENT_ASSETS)
            .first(fi -> keyRef.equals(fi.desc) && !fi.name.contains("BABY"));
        if (assetRead == null) return null;
        String assetConstant = assetRead.name;
        String babyAssetConstant = AsmWalker.over(submit)
            .getStatic(VanillaSourceClasses.Types.EQUIPMENT_ASSETS)
            .where(fi -> keyRef.equals(fi.desc) && fi.name.contains("BABY"))
            .names()
            .first();
        String gateField = AsmWalker.over(submit)
            .until(assetRead)
            .ofType(FieldInsnNode.class)
            .where(fi -> fi.getOpcode() == Opcodes.GETFIELD && "Z".equals(fi.desc)
                && !VanillaSourceClasses.Fields.IS_BABY.equals(fi.name))
            .names()
            .last();
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

    /**
     * The first {@code EquipmentClientInfo$LayerType} constant any method reads.
     */
    private static @Nullable String firstLayerTypeConstant(@NotNull ClassNode cn) {
        for (MethodNode method : cn.methods) {
            String constant = AsmWalker.over(method).firstNotNull(in ->
                AsmWalker.isGetStatic(in, VanillaSourceClasses.Types.EQUIPMENT_LAYER_TYPE)
                    ? ((FieldInsnNode) in).name : null);
            if (constant != null) return constant;
        }
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
        ClassKit.walkSuperChain(this.cache, this.subject.rendererClass(), cn -> {
            if (predicate[0] != null) return;
            for (MethodNode method : cn.methods) {
                if (!VanillaSourceClasses.Methods.EXTRACT_RENDER_STATE.equals(method.name)) continue;
                String call = AsmWalker.over(method)
                    .latch(in -> in.getOpcode() == Opcodes.INVOKEVIRTUAL && in instanceof MethodInsnNode mi
                        && mi.desc.endsWith(")Z") ? mi.name : null)
                    .commitAt(FieldInsnNode.class, fi -> fi.getOpcode() == Opcodes.PUTFIELD)
                    .firstNotNull(commit -> stateGateField.equals(commit.node().name) && commit.value() != null
                        ? commit.value() : null);
                if (call != null) {
                    predicate[0] = call;
                    return;
                }
            }
        });
        if (predicate[0] == null) return false;
        MethodNode body = ClassKit.findMethodInHierarchy(this.cache, this.subject.entityClass(), predicate[0], "()Z");
        if (body == null) return false;
        Boolean constant = constantBooleanReturn(body);
        return constant != null && constant;
    }

    /**
     * The constant of an {@code iconst_0/1; ireturn} body, or {@code null} for computed returns.
     */
    private static @Nullable Boolean constantBooleanReturn(@NotNull MethodNode method) {
        AbstractInsnNode ret = AsmWalker.over(method).real()
            .first(in -> in.getOpcode() == Opcodes.IRETURN,
                in -> in.getOpcode() == Opcodes.ICONST_0 || in.getOpcode() == Opcodes.ICONST_1);
        return ret == null ? null : AsmWalker.booleanLiteral(AsmWalker.previousReal(ret));
    }

    /**
     * The texture literal an owner's {@code <clinit>} binds into the named
     * {@code Identifier} field via the {@code LDC; withDefaultNamespace; PUTSTATIC} chain.
     */
    private @Nullable String chaseTextureFieldOwner(@NotNull String ownerInternalName, @NotNull String fieldName) {
        MethodNode clinit = ClassKit.findClinit(this.cache, ownerInternalName);
        if (clinit == null) return null;
        Cells.Latch<String> pendingPath = Cells.latch();
        Cells.Flag pendingIdentifier = Cells.flag();
        Cells.Latch<String> found = Cells.latch();
        // The PUTSTATIC arm is a hook rather than a commit: it manages its own arming, so an
        // unarmed bind of the named field passes by with the pending pair intact and nothing
        // ever resets it. The first accepted bind wins.
        AsmWalker.over(clinit)
            .on(Insn.of(AbstractInsnNode.class, in -> readEntityTextureLiteral(in) != null), in -> {
                String literal = readEntityTextureLiteral(in);
                if (literal == null) return;
                pendingPath.set(literal);
                pendingIdentifier.clear();
            })
            .on(Insn.invokeStatic(VanillaSourceClasses.Types.IDENTIFIER, VanillaSourceClasses.Methods.WITH_DEFAULT_NAMESPACE),
                mi -> pendingIdentifier.set())
            .on(Insn.of(FieldInsnNode.class, fi -> fi.getOpcode() == Opcodes.PUTSTATIC && fieldName.equals(fi.name)), fi -> {
                String path = pendingPath.get();
                if (path != null && pendingIdentifier.get() && found.get() == null) found.set(path);
            })
            .run();
        return found.get();
    }

}
