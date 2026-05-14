package lib.minecraft.renderer.tooling.entity;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import lib.minecraft.renderer.tooling.util.AsmKit;
import lib.minecraft.renderer.tooling.util.Diagnostics;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.Locale;
import java.util.zip.ZipFile;

/**
 * Walks each {@code RenderLayer} subclass attached to an entity renderer (per
 * {@link JavaEntityLayerScanner}) and extracts the data needed to emit a runtime overlay row
 * into {@code entity_models_java.json}. Phase E.4 first-pass scope is emissive eye overlays -
 * {@code SpiderEyesLayer}, {@code EnderEyesLayer}, {@code PhantomEyesLayer},
 * {@code BreezeEyesLayer}, plus any future vanilla mob whose layer's {@code <clinit>}
 * pre-builds a {@link net.minecraft.client.renderer.rendertype.RenderType} via an
 * {@code RenderTypes.*eyes*(Identifier)} static factory call and stores it in a
 * {@code static final} field. Detection runs on the {@code <clinit>} bytecode rather than
 * class hierarchy because not every eye layer extends {@code EyesLayer} -
 * {@code BreezeEyesLayer} extends {@code RenderLayer} directly but uses the same factory
 * shape ({@code RenderTypes.breezeEyes(...)}).
 *
 * <p>The bedrock pipeline carries a hardcoded {@code EMISSIVE_PNG_FANOUT} table with the same
 * information; deriving it from bytecode here removes the need to maintain that list as new
 * vanilla mobs gain emissive layers.
 *
 * <p>Layer types deliberately not handled by this first pass (will surface as additional
 * descriptors in later increments):
 * <ul>
 *   <li>{@code LivingEntityEmissiveLayer} (creaking, copper_golem) - texture comes from a
 *       {@code Function<S, Identifier>} passed via constructor lambda; needs walking the
 *       parent renderer's {@code addLayer(new LivingEntityEmissiveLayer(this, X::y, ...))}
 *       call site to bind the lambda's return value to a static path.</li>
 *   <li>{@code CreeperPowerLayer}, {@code SheepWoolUndercoatLayer}, {@code CopperGolemFlowerLayer}
 *       - have their own {@code LayerDefinition} requiring a separate geometry walk into
 *       {@code LayerDefinitions.createRoots} (kit work for a future increment).</li>
 *   <li>{@code HumanoidArmorLayer}, {@code SimpleEquipmentLayer}, {@code BeeStingerLayer},
 *       *ClothingLayer, *EquipmentLayer - driven by runtime player equipment, not part of the
 *       static-pose render contract.</li>
 * </ul>
 */
@UtilityClass
public final class JavaEntityOverlayResolver {

    /** Resource path prefix for entity texture LDCs - matches what the texture resolver uses. */
    private static final @NotNull String TEXTURE_PATH_PREFIX = "textures/entity/";

    /** JVM descriptor suffix for any method returning a {@code RenderType}. */
    private static final @NotNull String RENDER_TYPE_RETURN =
        ")Lnet/minecraft/client/renderer/rendertype/RenderType;";

    /**
     * Explicit allowlist of {@code ModelLayers.X} field names whose composite overlay we emit.
     * The detection in {@link #findOverlayModelLayerField} is intentionally generic so that any
     * vanilla layer with the {@code bakeLayer + new Model} shape surfaces - but emitting them
     * is gated here because most other composite overlays (charged-creeper armor, wither armor,
     * elytra, drowned outer slime, etc.) need translucent or additive blending the static
     * renderer doesn't reproduce, and would render OPAQUE on top of the base body, hiding it.
     * <p>Entries:
     * <ul>
     *   <li>{@code SHEEP_WOOL_UNDERCOAT} - the white/black undercoat under the dyed wool;
     *       cutout-rendered in vanilla Java but the overlay PNG is sparse so opaque-as-cutout
     *       reads correctly here.</li>
     *   <li>{@code SHEEP_WOOL} - the dyed wool fluff; same reasoning as the undercoat. Vanilla's
     *       per-bone cube_deformation inflate isn't captured by the parser yet so the wool sits
     *       at the same size as the body, but the texture overlay still improves parity vs no
     *       overlay at all.</li>
     *   <li>{@code SLIME_OUTER} - the 8x8x8 translucent outer shell over the inner 6x6x6 body.
     *       Vanilla renders it via {@code RenderTypes.entityTranslucent} (constant 180/255 alpha
     *       multiplier); the static renderer treats it as opaque so the visible result is a
     *       solid green cube larger than the bedrock reference. Geometry is correct relative
     *       to the Java client; the bedrock-pipeline delta is a known-divergence on rendering
     *       semantics rather than geometry. Maintainer can move {@code minecraft:slime} into
     *       {@code TestEntityParity.ACHIEVED_PARITY} once the geometry difference is reviewed.</li>
     * </ul>
     * Adding an entry needs a parity check before commit - opaque-overlay regressions on an
     * entity that renders worse than no-overlay (i.e. the overlay covers the base body without
     * adding visual signal) should stay out of this list.
     */
    private static final @NotNull java.util.Set<String> COMPOSITE_OVERLAY_ALLOWLIST = java.util.Set.of(
        "DROWNED_OUTER_LAYER",
        "SHEEP_WOOL",
        "SHEEP_WOOL_UNDERCOAT",
        "SLIME_OUTER"
    );

    /** JVM internal name of the {@code ModelLayers} constants holder; layer factory references key off it. */
    private static final @NotNull String MODEL_LAYERS = "net/minecraft/client/model/geom/ModelLayers";

    /** JVM internal name of {@code EntityModelSet} - layer constructors call {@code bakeLayer} on it. */
    private static final @NotNull String ENTITY_MODEL_SET = "net/minecraft/client/model/geom/EntityModelSet";

    /** JVM internal name of {@code Identifier} - texture fields and {@code withDefaultNamespace} return type. */
    private static final @NotNull String IDENTIFIER = "net/minecraft/resources/Identifier";

    /** Field-type descriptor for an {@code Identifier}; used to filter overlay-texture field references. */
    private static final @NotNull String IDENTIFIER_DESC = "L" + IDENTIFIER + ";";

    /**
     * One overlay descriptor extracted from a layer class. The runtime emission step in
     * {@link lib.minecraft.renderer.tooling.ToolingJavaEntityModels} maps this onto an
     * {@code overlays} entry in {@code entity_models_java.json}.
     *
     * @param layerClass JVM internal name of the source layer subclass (diagnostic provenance)
     * @param texturePath the raw texture path ({@code textures/entity/X/Y_eyes.png}) - callers
     *     strip the {@code textures/}+{@code entity/}+{@code .png} prefix/suffix to match the
     *     bundled-texture-ref convention before writing JSON
     * @param emissive {@code true} when the layer's render type is one of the emissive
     *     additive-blend variants ({@code RenderTypes.eyes} or {@code RenderTypes.breezeEyes});
     *     translates into the runtime {@code OverlayLayer.emissive} flag
     * @param modelLayerField the {@code ModelLayers.X} field name when the overlay's geometry comes
     *     from a separate {@code LayerDefinition} factory (e.g. {@code "SLIME_OUTER"},
     *     {@code "SHEEP_WOOL"}, {@code "SHEEP_WOOL_UNDERCOAT"}). {@code null} for eye overlays
     *     whose UVs reuse the base entity's geometry. {@link
     *     lib.minecraft.renderer.tooling.ToolingJavaEntityModels} resolves this against
     *     {@link JavaEntityLayerDefinitionResolver}'s layer-definition map to get an actual
     *     factory target, parses it as an extra geometry, and assigns a deduped geometry id
     */
    public record OverlayDescriptor(
        @NotNull String layerClass,
        @NotNull String texturePath,
        boolean emissive,
        @Nullable String modelLayerField
    ) {}

    /**
     * Resolves overlay descriptors from the layer class names produced by
     * {@link JavaEntityLayerScanner}. Layer classes that don't match any known overlay shape
     * are silently dropped - they're either runtime-driven (armor / equipment / item-in-hand)
     * or deferred to a later phase.
     *
     * @param zip the deobfuscated client jar
     * @param layerClasses ordered list of layer-class internal names from
     *     {@link JavaEntityLayerScanner#scan(ZipFile, String, Diagnostics)}
     * @param entityId the entity-id this layer set belongs to (diagnostic context only)
     * @param diagnostics the diagnostic sink shared with sibling discovery walks
     * @return overlay descriptors in source order; empty when no recognised overlay was found
     */
    public static @NotNull ConcurrentList<OverlayDescriptor> resolve(
        @NotNull ZipFile zip,
        @NotNull String rendererInternalName,
        @NotNull ConcurrentList<String> layerClasses,
        @NotNull String entityId,
        @NotNull Diagnostics diagnostics
    ) {
        ConcurrentList<OverlayDescriptor> out = Concurrent.newList();
        for (String layerClass : layerClasses) {
            ClassNode cn = AsmKit.loadClass(zip, layerClass);
            if (cn == null) continue;
            // Eye overlay first - shares the base entity's geometry, so no extra parse.
            String eyesTexture = findEyesOverlayTexture(cn);
            if (eyesTexture != null) {
                out.add(new OverlayDescriptor(layerClass, eyesTexture, true, null));
                continue;
            }
            // Composite-model overlay (sheep wool, sheep wool undercoat) - detected by an
            // {@code <init>(RenderLayerParent, EntityModelSet)} that calls
            // {@code modelSet.bakeLayer(ModelLayers.X)}. The matching texture comes from the layer's
            // own {@code <clinit>} ({@code SHEEP_WOOL_LOCATION}) or from a sibling renderer's
            // {@code <clinit>}. Limited to {@link #COMPOSITE_OVERLAY_ALLOWLIST} because most other
            // composite overlays need translucent or additive blending the static renderer doesn't
            // honour - they would render opaque on top of the base body, hiding it (slime's outer
            // shell is the canonical regression - vanilla renders it via {@code RenderTypes
            // .entityTranslucent} which the parity test's auto-fit cutout sampler doesn't replicate).
            String modelLayerField = findOverlayModelLayerField(cn);
            if (modelLayerField == null || !COMPOSITE_OVERLAY_ALLOWLIST.contains(modelLayerField)) continue;
            String compositeTexture = findCompositeOverlayTexture(zip, cn, rendererInternalName);
            if (compositeTexture == null) {
                diagnostics.info("entity '%s' overlay '%s' bakes ModelLayers.%s but no texture path resolved", entityId, layerClass, modelLayerField);
                continue;
            }
            out.add(new OverlayDescriptor(layerClass, compositeTexture, false, modelLayerField));
        }
        return out;
    }

    /**
     * Returns the texture path bound in {@code cn.<clinit>} when the class qualifies as an
     * emissive eye overlay - that is, the {@code <clinit>} contains an {@code INVOKESTATIC}
     * to a method whose name contains {@code "eyes"} (case-insensitive) and which returns
     * a {@code RenderType}, with a {@code textures/entity/...png} {@code LDC} literal
     * preceding the call. Returns {@code null} when the class has no {@code <clinit>}, no
     * eye-typed factory call, or no preceding texture literal.
     *
     * <p>Detection runs on the bytecode shape rather than class hierarchy so layers that
     * skip the {@code EyesLayer} base ({@code BreezeEyesLayer} extends {@code RenderLayer}
     * directly and uses {@code RenderTypes.breezeEyes}) still resolve. The "eyes" name match
     * doubles as the emissive flag - if the call name doesn't contain "eyes" the layer is a
     * non-emissive overlay we don't emit yet.
     */
    /**
     * Returns the {@code ModelLayers.X} field name baked by the layer class's
     * {@code <init>(RenderLayerParent, EntityModelSet)} constructor, or {@code null} when the
     * layer doesn't bake its own model. Detection: walk the constructor for
     * {@code GETSTATIC ModelLayers.X} immediately preceding an
     * {@code INVOKEVIRTUAL EntityModelSet.bakeLayer(ModelLayerLocation) ModelPart} call. The
     * returned field name is what {@link JavaEntityLayerDefinitionResolver#loadLayerDefinitions}
     * uses as its map key, so the caller can resolve it to a factory target.
     *
     * <p>Skips layers whose field name contains {@code "BABY"} - the babe-sized variants share the
     * same texture as the adult and only the adult layer needs an entry. (The Phase E.4 first pass
     * doesn't emit baby-distinct entities; if that changes, the BABY filter would move into the
     * caller.)
     */
    private static @Nullable String findOverlayModelLayerField(@NotNull ClassNode cn) {
        for (MethodNode method : cn.methods) {
            if (!"<init>".equals(method.name)) continue;
            String pendingField = null;
            for (AbstractInsnNode in = method.instructions.getFirst(); in != null; in = in.getNext()) {
                if (in.getOpcode() == Opcodes.GETSTATIC
                    && in instanceof FieldInsnNode fi
                    && MODEL_LAYERS.equals(fi.owner)) {
                    pendingField = fi.name;
                    continue;
                }
                if (in.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && in instanceof MethodInsnNode mi
                    && ENTITY_MODEL_SET.equals(mi.owner)
                    && "bakeLayer".equals(mi.name)
                    && pendingField != null
                    && !pendingField.contains("BABY"))
                    return pendingField;
            }
        }
        return null;
    }

    /**
     * Resolves the texture path for a composite-model overlay. Tries two strategies in order:
     *
     * <ol>
     *   <li><b>Own-clinit LDC</b>: walks the layer's {@code <clinit>} for the first
     *       {@code LDC textures/...png; INVOKESTATIC withDefaultNamespace; PUTSTATIC FIELD} chain
     *       whose target field name doesn't contain {@code "BABY"}. Catches
     *       {@code SheepWoolLayer.SHEEP_WOOL_LOCATION} and
     *       {@code SheepWoolUndercoatLayer.SHEEP_WOOL_UNDERCOAT_LOCATION}.</li>
     *   <li><b>Sibling-renderer GETSTATIC</b>: walks the layer's non-init non-clinit methods for
     *       the first {@code GETSTATIC OtherClass.X_LOCATION} of an {@code Identifier} field.
     *       Then recursively resolves the texture path bound to that field by walking the owner's
     *       own {@code <clinit>} for the same LDC pattern. Catches
     *       {@code SlimeOuterLayer}, which renders against {@code SlimeRenderer.SLIME_LOCATION}
     *       (no own LDC).</li>
     * </ol>
     *
     * <p>Returns {@code null} when neither strategy yields a texture - the caller logs a
     * diagnostic and drops the overlay.
     */
    private static @Nullable String findCompositeOverlayTexture(
        @NotNull ZipFile zip,
        @NotNull ClassNode layerClass,
        @NotNull String rendererInternalName
    ) {
        String own = findFirstNonBabyTextureLiteral(layerClass);
        if (own != null) return own;
        // Strategy 2: chase a sibling-renderer GETSTATIC out of any non-init method (the render /
        // submit method body is where the layer pulls its texture). Try the layer's own methods
        // first so renderer-shared textures are resolved by following the field link, falling back
        // to the parent renderer if no GETSTATIC of an Identifier field surfaces.
        for (MethodNode method : layerClass.methods) {
            if ("<init>".equals(method.name) || "<clinit>".equals(method.name)) continue;
            for (AbstractInsnNode in = method.instructions.getFirst(); in != null; in = in.getNext()) {
                if (in.getOpcode() != Opcodes.GETSTATIC) continue;
                if (!(in instanceof FieldInsnNode fi)) continue;
                if (!IDENTIFIER_DESC.equals(fi.desc)) continue;
                String chased = chaseTextureFieldOwner(zip, fi.owner, fi.name);
                if (chased != null) return chased;
            }
        }
        // Last-ditch: try the parent renderer's own clinit. Some layers reference texture fields
        // through inheritance only; if the layer's render code doesn't surface a GETSTATIC, the
        // parent renderer is still the most likely owner.
        ClassNode renderer = AsmKit.loadClass(zip, rendererInternalName);
        if (renderer != null) {
            String parentTexture = findFirstNonBabyTextureLiteral(renderer);
            if (parentTexture != null) return parentTexture;
        }
        return null;
    }

    /**
     * Walks {@code cn.<clinit>} for the first {@code LDC "textures/...png"} that flows through a
     * {@code withDefaultNamespace} call into a {@code PUTSTATIC} of a non-{@code BABY} field.
     * Returns the LDC's literal value, or {@code null} when no such pattern is present.
     */
    private static @Nullable String findFirstNonBabyTextureLiteral(@NotNull ClassNode cn) {
        MethodNode clinit = AsmKit.findMethod(cn, "<clinit>");
        if (clinit == null) return null;
        String pendingPath = null;
        boolean pendingIdentifier = false;
        for (AbstractInsnNode in = clinit.instructions.getFirst(); in != null; in = in.getNext()) {
            String literal = AsmKit.readStringLiteral(in);
            if (literal != null && literal.startsWith(TEXTURE_PATH_PREFIX) && literal.endsWith(".png")
                && !literal.contains("%")) {
                pendingPath = literal;
                pendingIdentifier = false;
                continue;
            }
            if (in.getOpcode() == Opcodes.INVOKESTATIC
                && in instanceof MethodInsnNode mi
                && IDENTIFIER.equals(mi.owner)
                && "withDefaultNamespace".equals(mi.name)) {
                pendingIdentifier = true;
                continue;
            }
            if (in.getOpcode() == Opcodes.PUTSTATIC
                && in instanceof FieldInsnNode fi
                && IDENTIFIER_DESC.equals(fi.desc)
                && pendingPath != null
                && pendingIdentifier) {
                if (!fi.name.contains("BABY")) return pendingPath;
                pendingPath = null;
                pendingIdentifier = false;
            }
        }
        return null;
    }

    /**
     * Resolves an {@code Identifier} static field on {@code owner} by walking its {@code <clinit>}
     * for the {@code LDC + withDefaultNamespace + PUTSTATIC <fieldName>} chain. Returns the LDC
     * literal bound to {@code fieldName}, or {@code null} when the owner's clinit doesn't bind
     * the field with the standard pattern.
     *
     * <p>Used by the sibling-renderer chase: {@code SlimeOuterLayer} renders against
     * {@code SlimeRenderer.SLIME_LOCATION}, so this walks {@code SlimeRenderer.<clinit>} and
     * picks up {@code "textures/entity/slime/slime.png"}.
     */
    private static @Nullable String chaseTextureFieldOwner(
        @NotNull ZipFile zip,
        @NotNull String ownerInternalName,
        @NotNull String fieldName
    ) {
        ClassNode owner = AsmKit.loadClass(zip, ownerInternalName);
        if (owner == null) return null;
        MethodNode clinit = AsmKit.findMethod(owner, "<clinit>");
        if (clinit == null) return null;
        String pendingPath = null;
        boolean pendingIdentifier = false;
        for (AbstractInsnNode in = clinit.instructions.getFirst(); in != null; in = in.getNext()) {
            String literal = AsmKit.readStringLiteral(in);
            if (literal != null && literal.startsWith(TEXTURE_PATH_PREFIX) && literal.endsWith(".png")
                && !literal.contains("%")) {
                pendingPath = literal;
                pendingIdentifier = false;
                continue;
            }
            if (in.getOpcode() == Opcodes.INVOKESTATIC
                && in instanceof MethodInsnNode mi
                && IDENTIFIER.equals(mi.owner)
                && "withDefaultNamespace".equals(mi.name)) {
                pendingIdentifier = true;
                continue;
            }
            if (in.getOpcode() == Opcodes.PUTSTATIC
                && in instanceof FieldInsnNode fi
                && fi.name.equals(fieldName)
                && pendingPath != null
                && pendingIdentifier)
                return pendingPath;
        }
        return null;
    }

    private static @Nullable String findEyesOverlayTexture(@NotNull ClassNode cn) {
        MethodNode clinit = AsmKit.findMethod(cn, "<clinit>");
        if (clinit == null) return null;
        String pendingTexturePath = null;
        for (AbstractInsnNode in = clinit.instructions.getFirst(); in != null; in = in.getNext()) {
            String literal = AsmKit.readStringLiteral(in);
            if (literal != null && literal.startsWith(TEXTURE_PATH_PREFIX) && literal.endsWith(".png")
                && !literal.contains("%")) {
                pendingTexturePath = literal;
                continue;
            }
            if (in.getOpcode() == Opcodes.INVOKESTATIC
                && in instanceof MethodInsnNode mi
                && mi.desc.endsWith(RENDER_TYPE_RETURN)
                && mi.name.toLowerCase(Locale.ROOT).contains("eyes")
                && pendingTexturePath != null)
                return pendingTexturePath;
        }
        return null;
    }

}
