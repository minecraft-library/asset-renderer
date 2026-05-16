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
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;

/**
 * Bytecode-driven extraction of the entity texture reference declared by an
 * {@code EntityRenderer}'s {@code getTextureLocation(...)} method. Handles three vanilla
 * patterns:
 *
 * <ol>
 *   <li><b>Hardcoded constant</b> ({@code AllayRenderer}, {@code CreeperRenderer}): the
 *       renderer's {@code <clinit>} pairs an {@code LDC "textures/entity/X.png"} with a
 *       {@code PUTSTATIC FIELD}, and {@code getTextureLocation} returns
 *       {@code GETSTATIC FIELD}.</li>
 *   <li><b>Conditional baby/adult</b> ({@code AbstractZombieRenderer}, used by zombie / husk /
 *       drowned / zombie_villager): the {@code <clinit>} declares two fields
 *       ({@code ZOMBIE_LOCATION}, {@code BABY_ZOMBIE_LOCATION}) and {@code getTextureLocation}
 *       branches on a state field ({@code isBaby}). The walker prefers the non-baby field for
 *       the primary texture; the baby variant carries through as
 *       {@link Binding#babyTexturePath()}.</li>
 *   <li><b>Variant-driven</b> ({@code CowRenderer}, {@code PigRenderer}, etc.):
 *       {@code getTextureLocation} reads {@code state.variant.modelAndTexture().asset()
 *       .texturePath()} - no static constant is involved. The walker recognises the
 *       {@code Holder<XVariant>}/{@code modelAndTexture}/{@code texturePath} call chain and
 *       flags the binding as variant-driven; the actual texture paths come from
 *       {@link EntityVariantResolver}.</li>
 * </ol>
 *
 * <p>For all three patterns the walker traverses the renderer's superclass chain via
 * {@link AsmKit#findMethodInHierarchy} so subclasses that inherit {@code getTextureLocation}
 * (zombie / husk / drowned all inherit from {@code AbstractZombieRenderer}) resolve correctly.
 *
 * <p>Texture paths are returned as the raw resource location ({@code "textures/entity/X.png"}
 * with leading {@code textures/} but without a namespace prefix) - callers prepend
 * {@code minecraft:} if they need a namespaced id.
 */
@UtilityClass
public final class EntityTextureResolver {

    /** JVM internal name of {@code net.minecraft.resources.Identifier}. */
    private static final @NotNull String IDENTIFIER = "net/minecraft/resources/Identifier";

    /** The factory call most renderer {@code <clinit>}s use to wrap a path String into an Identifier. */
    private static final @NotNull String WITH_DEFAULT_NAMESPACE = "withDefaultNamespace";

    /** Method name shared by every renderer that exposes a texture binding. */
    private static final @NotNull String GET_TEXTURE_LOCATION = "getTextureLocation";

    /**
     * The texture binding extracted for one renderer.
     *
     * @param primaryTexturePath the adult / default texture path, e.g.
     *     {@code "textures/entity/zombie/zombie.png"}, or {@code null} when the binding is
     *     variant-driven (see {@link #isVariantDriven()})
     * @param babyTexturePath the baby variant texture path when the renderer's
     *     {@code getTextureLocation} branches on {@code isBaby}; {@code null} otherwise
     * @param variantSourceClass when variant-driven, the JVM internal name of the
     *     {@code XVariant} class (e.g. {@code "net/minecraft/world/entity/animal/cow/CowVariant"})
     *     so the variant resolver can be keyed off it; {@code null} for the hardcoded patterns
     * @param hierarchySource when the texture binding was found on a superclass rather than the
     *     renderer itself, the JVM internal name of that superclass; {@code null} when the
     *     binding lives on the renderer directly
     */
    public record Binding(
        @Nullable String primaryTexturePath,
        @Nullable String babyTexturePath,
        @Nullable String variantSourceClass,
        @Nullable String hierarchySource
    ) {
        /** {@code true} when {@link #variantSourceClass} is non-null - texture is data-driven. */
        public boolean isVariantDriven() {
            return this.variantSourceClass != null;
        }

        /** {@code true} when neither hardcoded nor variant-driven binding was extractable. */
        public boolean isUnresolved() {
            return this.primaryTexturePath == null && this.variantSourceClass == null;
        }
    }

    /**
     * Extracts the texture binding for a renderer class. Returns an empty {@link Binding}
     * (every field {@code null}) when the renderer chain has no recognisable
     * {@code getTextureLocation} or when the binding pattern is unsupported.
     *
     * @param zip the deobfuscated client jar
     * @param rendererInternalName the renderer's JVM internal name
     * @param lambdaTypeArgs Type-enum constants pushed by the renderer's factory lambda
     *     (donkey vs mule both share {@code DonkeyRenderer} but each lambda passes a distinct
     *     {@code DonkeyRenderer$Type} constant). Used to resolve the instance-field-driven
     *     pattern by walking the Type enum's {@code <clinit>} for the constant's texture
     *     binding. Empty for renderers whose lambda is a direct constructor reference.
     * @param diagnostics the diagnostic sink shared with sibling discovery walks
     * @return the extracted binding
     */
    public static @NotNull Binding resolve(
        @NotNull ZipFile zip,
        @NotNull String rendererInternalName,
        @NotNull ConcurrentList<lib.minecraft.renderer.tooling.entity.EntityRendererDiscovery.TypeFieldRef> lambdaTypeArgs,
        @NotNull Diagnostics diagnostics
    ) {
        ResolvedMethod resolved = findGetTextureLocation(zip, rendererInternalName);
        if (resolved == null) {
            diagnostics.info("renderer '%s' has no getTextureLocation in its hierarchy - skipped", rendererInternalName);
            return new Binding(null, null, null, null);
        }

        // Data-driven variant check (Cow / Pig / Chicken / Frog): an INVOKEVIRTUAL on
        // X.modelAndTexture() / X.texturePath() / X.babyTexture() with owner ending in "Variant"
        // means the texture path lives in data/minecraft/X_variant/*.json - bypass the
        // bytecode walker and let EntityVariantResolver supply the path. This case
        // genuinely has no static Identifier in the renderer; the default-path walker would
        // return null anyway, so check first to keep the diagnostic accurate.
        String dataDrivenSource = detectDataDrivenVariant(resolved.method);
        if (dataDrivenSource != null)
            return new Binding(null, null, dataDrivenSource, sourceLabel(resolved, rendererInternalName));

        // Try the default-path walker first - it picks up the all-defaults branch's GETSTATIC
        // for both straight-hardcoded renderers (creeper / allay) and renderers whose
        // getTextureLocation has an enum-map / state-conditional structure that still falls
        // through to a default GETSTATIC (FoxRenderer's `if (texturesByState == null) return
        // RED_FOX_TEXTURE;`). When that returns null the body is a pure dispatch (parrot /
        // shulker / copper_golem) - chase the INVOKESTATIC into its target.
        Map<String, String> classFieldToPath = collectStaticTextureFields(zip, resolved.declaringClass, diagnostics);
        String primaryField = findPrimaryByDefaultPath(resolved.method);
        if (primaryField != null && classFieldToPath.containsKey(primaryField)) {
            String primaryPath = classFieldToPath.get(primaryField);
            String babyPath = findBabyPathByPair(primaryPath, classFieldToPath);
            if (babyPath == null) {
                ConcurrentList<FieldInsnNode> getStatics = collectIdentifierGetStatics(resolved.method);
                String babyField = pickBabyField(getStatics, primaryField);
                babyPath = babyField == null ? null : classFieldToPath.get(babyField);
            }
            return new Binding(primaryPath, babyPath, null, sourceLabel(resolved, rendererInternalName));
        }

        // No default-path GETSTATIC: try chasing INVOKESTATIC dispatch (parrot / shulker).
        Binding chased = chaseStaticDispatch(zip, resolved.method, rendererInternalName, diagnostics);
        if (chased != null) return chased;

        // Instance-field-driven (donkey / mule / skeleton_horse / zombified_horse): the
        // renderer's getTextureLocation reads {@code aload_0; getfield <field>:LIdentifier;}
        // where the field is initialised in {@code <init>} from a {@code XRenderer$Type.texture}
        // getfield off a constructor parameter. The lambda call site supplies the per-entity
        // Type-enum constant; walking the Type's {@code <clinit>} for the constant's texture
        // initialiser gives the resolved path.
        String variantSource = detectVariantPatternFlag(resolved.method);
        if ("(instance-field-driven)".equals(variantSource) && !lambdaTypeArgs.isEmpty()) {
            Binding instanceFieldResolved = resolveInstanceFieldDriven(zip, lambdaTypeArgs, rendererInternalName, diagnostics);
            if (instanceFieldResolved != null) return instanceFieldResolved;
        }
        if (variantSource != null) {
            // Enum-map (axolotl / horse / llama / mushroom_cow / panda / rabbit) and chained
            // method-dispatch (copper_golem - INVOKESTATIC then INVOKEVIRTUAL): the body has no
            // direct Identifier GETSTATIC; the binding lives in a lambda-initialised map or a
            // multi-step dispatch the static walker doesn't unfold. Fall back to the first
            // {@code "textures/entity/..."} LDC bound in the renderer's own {@code <clinit>}
            // so the base entity gets a sensible default texture - preferring a field whose
            // name does NOT contain BABY so the adult variant wins. Keeps the variant source
            // tag for diagnostic provenance via {@code hierarchySource}.
            String fallbackPath = pickFirstNonBabyTexturePath(classFieldToPath);
            if (fallbackPath != null) {
                String babyFallback = findBabyPathByPair(fallbackPath, classFieldToPath);
                return new Binding(fallbackPath, babyFallback, variantSource, sourceLabel(resolved, rendererInternalName));
            }
            // No PUTSTATIC-bound fields: HorseRenderer / LlamaRenderer / PandaRenderer /
            // MushroomCowRenderer all push their texture paths as constructor args to
            // {@code new HorseTextures(adult, baby)} (or similar wrapper) without ever binding
            // them to renderer-class static fields. The texture LDC strings are still in the
            // {@code <clinit>} bytecode; pluck the first non-baby one as a primary default and
            // pair its {@code _baby.png} sibling for the baby variant.
            // Try the canonical-default-variant path first: walk any lambda$static$N (or
            // <clinit>) for {@code GETSTATIC <Variant>.<NAME>} immediately followed by an LDC
            // texture literal, then look up the variant enum's {@code DEFAULT} static field to
            // pick the {@code <NAME>} corresponding to vanilla's canonical default. This keeps
            // mooshroom on RED (matching {@code MushroomCow$Variant.DEFAULT = RED}) instead of
            // BROWN (the first key the lambda happens to put). Falls through when no variant
            // enum has a {@code DEFAULT} field (parrot's enum uses the first ordinal).
            String defaultVariantPath = findDefaultVariantLiteral(zip, resolved.declaringClass);
            if (defaultVariantPath != null) {
                String defaultBaby = derivedBabyPath(defaultVariantPath);
                return new Binding(defaultVariantPath, defaultBaby, variantSource, sourceLabel(resolved, rendererInternalName));
            }
            ConcurrentList<String> rawLiterals = collectAllTextureLiterals(zip, resolved.declaringClass);
            String literalPrimary = pickFirstNonBabyLiteral(rawLiterals);
            if (literalPrimary != null) {
                String literalBaby = findBabyLiteralByPair(literalPrimary, rawLiterals);
                return new Binding(literalPrimary, literalBaby, variantSource, sourceLabel(resolved, rendererInternalName));
            }
            return new Binding(null, null, variantSource, sourceLabel(resolved, rendererInternalName));
        }

        // Last-resort: collect all GETSTATIC :LIdentifier; refs and pick the first non-BABY.
        // Fires for renderers whose getTextureLocation falls through every IFEQ but the
        // resulting field isn't bound by the class's <clinit> (rare, mostly defensive).
        ConcurrentList<FieldInsnNode> getStatics = collectIdentifierGetStatics(resolved.method);
        if (getStatics.isEmpty()) {
            diagnostics.info("renderer '%s' getTextureLocation has no Identifier GETSTATIC refs - unsupported pattern", rendererInternalName);
            return new Binding(null, null, null, sourceLabel(resolved, rendererInternalName));
        }
        if (classFieldToPath.isEmpty()) {
            diagnostics.info("renderer '%s' (binding on '%s') has GETSTATICs but no <clinit>-bound LDC paths - unsupported pattern", rendererInternalName, resolved.declaringClass);
            return new Binding(null, null, null, sourceLabel(resolved, rendererInternalName));
        }
        primaryField = pickPrimaryField(getStatics);
        String primaryPath = primaryField == null ? null : classFieldToPath.get(primaryField);
        // Baby derivation: prefer a path-pair match (primary "X.png" -> baby "X_baby.png" in
        // the same directory), since the lexically-first BABY-named field is the wrong choice
        // for renderers like BeeRenderer where the baby texture is itself overlay-conditional
        // (ANGRY_NECTAR_BEE_BABY_TEXTURE comes first, plain BEE_BABY_TEXTURE last). Falls
        // back to the pre-existing first-BABY-named heuristic for renderers whose baby field
        // doesn't follow the path-pair convention.
        String babyPath = primaryPath == null ? null : findBabyPathByPair(primaryPath, classFieldToPath);
        if (babyPath == null) {
            String babyField = pickBabyField(getStatics, primaryField);
            babyPath = babyField == null ? null : classFieldToPath.get(babyField);
        }

        return new Binding(primaryPath, babyPath, null, sourceLabel(resolved, rendererInternalName));
    }

    /**
     * Walks the superclass chain looking for {@code getTextureLocation(...)} - vanilla zombie /
     * husk / drowned all inherit it from {@code AbstractZombieRenderer}; cow inherits the
     * variant-driven shape from itself but variants of pig/chicken inherit from base classes.
     * Multiple overloads exist (one per render-state subtype); we want the most-derived one
     * whose return type is {@code Identifier}, which is always the renderer's own override.
     */
    private static @Nullable ResolvedMethod findGetTextureLocation(
        @NotNull ZipFile zip,
        @NotNull String rendererInternalName
    ) {
        String current = rendererInternalName;
        while (current != null && !"java/lang/Object".equals(current)) {
            ClassNode cn = AsmKit.loadClass(zip, current);
            if (cn == null) return null;
            // Prefer the non-bridge overload (the one taking the renderer's own state class
            // rather than the LivingEntityRenderState bridge). The bridge does
            // checkcast + invokevirtual + areturn and carries no real binding info.
            MethodNode best = null;
            for (MethodNode m : cn.methods) {
                if (!GET_TEXTURE_LOCATION.equals(m.name)) continue;
                if (!m.desc.endsWith(")L" + IDENTIFIER + ";")) continue;
                if (isBridgeMethod(m)) continue;
                best = m;
                break;
            }
            if (best != null) return new ResolvedMethod(best, current);
            current = cn.superName;
        }
        return null;
    }

    /**
     * A {@code getTextureLocation} bridge override delegates to the typed overload via
     * {@code aload_0; aload_1; checkcast X; invokevirtual ...; areturn}. Recognising this skips
     * the bridge and reaches the real binding on the next pass.
     */
    private static boolean isBridgeMethod(@NotNull MethodNode method) {
        for (AbstractInsnNode in = method.instructions.getFirst(); in != null; in = in.getNext()) {
            if (in.getOpcode() < 0) continue;
            if (in.getOpcode() == Opcodes.INVOKEVIRTUAL && in instanceof MethodInsnNode mi
                && GET_TEXTURE_LOCATION.equals(mi.name))
                return true;
        }
        return false;
    }

    /**
     * Returns the {@code XVariant} class internal name when {@code getTextureLocation} pulls
     * its texture from a data-driven variant call (vanilla 1.21+ cow / pig / chicken / frog -
     * {@code state.variant.modelAndTexture().asset().texturePath()} or
     * {@code state.variant.babyTexture()}). Returns {@code null} otherwise.
     *
     * <p>Genuinely impossible to resolve to a static path: the renderer reads from the
     * {@code Holder<XVariant>} at runtime; the actual paths live in
     * {@code data/minecraft/X_variant/*.json} and are loaded by
     * {@link EntityVariantResolver}. The base entity's primary texture is then defaulted
     * by {@link ToolingEntityModels} to the temperate / first variant.
     */
    private static @Nullable String detectDataDrivenVariant(@NotNull MethodNode method) {
        for (AbstractInsnNode in = method.instructions.getFirst(); in != null; in = in.getNext()) {
            if (in instanceof MethodInsnNode mi
                && in.getOpcode() == Opcodes.INVOKEVIRTUAL
                && (mi.name.endsWith("Texture") || "modelAndTexture".equals(mi.name))
                && mi.owner.endsWith("Variant"))
                return mi.owner;
        }
        return null;
    }

    /**
     * Classifies an unresolved {@code getTextureLocation} body by the bytecode pattern it
     * uses, returning one of three synthetic labels for the diagnostic JSON:
     * <ul>
     *   <li><b>{@code "(enum-map-driven)"}</b> ({@code AxolotlRenderer}, {@code PandaRenderer},
     *       {@code LlamaRenderer}, etc.) - {@code GETSTATIC <map>} + {@code INVOKEINTERFACE
     *       Map.get}, the texture binding lives in a {@code lambda$static$N} initializer keyed
     *       by enum constant.</li>
     *   <li><b>{@code "(method-dispatch-driven)"}</b> - any {@code INVOKESTATIC} or
     *       {@code INVOKEVIRTUAL} (excluding {@link #WITH_DEFAULT_NAMESPACE}) returning
     *       Identifier. The simpler subset (parrot / shulker pure-dispatch through INVOKESTATIC)
     *       is resolved earlier by {@link #chaseStaticDispatch}; this label remains for the
     *       chained-dispatch cases (copper_golem's two-level oxidation walk).</li>
     *   <li><b>{@code "(instance-field-driven)"}</b> ({@code DonkeyRenderer},
     *       {@code UndeadHorseRenderer}) - {@code aload_0; getfield <field>:LIdentifier;}, the
     *       binding comes from an instance field initialised in the renderer's constructor
     *       from a Type-enum constant.</li>
     * </ul>
     *
     * <p>Returns {@code null} when the body matches none of the patterns. Called only after
     * the default-path walker and {@link #chaseStaticDispatch} have failed; provides
     * provenance in the diagnostic JSON without claiming to have resolved a path.
     */
    private static @Nullable String detectVariantPatternFlag(@NotNull MethodNode method) {
        boolean sawMapGet = false;
        boolean sawStaticMap = false;
        boolean sawIdentifierReturningCall = false;
        for (AbstractInsnNode in = method.instructions.getFirst(); in != null; in = in.getNext()) {
            if (in.getOpcode() == Opcodes.GETSTATIC
                && in instanceof FieldInsnNode fi
                && fi.desc.equals("Ljava/util/Map;"))
                sawStaticMap = true;

            if (in.getOpcode() == Opcodes.INVOKEINTERFACE
                && in instanceof MethodInsnNode mi
                && "java/util/Map".equals(mi.owner)
                && "get".equals(mi.name))
                sawMapGet = true;

            if (in instanceof MethodInsnNode mi
                && (in.getOpcode() == Opcodes.INVOKESTATIC || in.getOpcode() == Opcodes.INVOKEVIRTUAL)
                && mi.desc.endsWith(")L" + IDENTIFIER + ";")
                && !(IDENTIFIER.equals(mi.owner) && WITH_DEFAULT_NAMESPACE.equals(mi.name)))
                sawIdentifierReturningCall = true;
        }
        if (sawStaticMap && sawMapGet) return "(enum-map-driven)";
        if (sawIdentifierReturningCall) return "(method-dispatch-driven)";
        for (AbstractInsnNode in = method.instructions.getFirst(); in != null; in = in.getNext()) {
            if (in.getOpcode() == Opcodes.GETFIELD
                && in instanceof FieldInsnNode fi
                && ("L" + IDENTIFIER + ";").equals(fi.desc)
                && in.getPrevious() != null
                && in.getPrevious().getOpcode() == Opcodes.ALOAD
                && in.getPrevious() instanceof org.objectweb.asm.tree.VarInsnNode v
                && v.var == 0)
                return "(instance-field-driven)";
        }
        return null;
    }

    /**
     * Returns every {@code GETSTATIC <field>:LIdentifier;} reference in the method, in source
     * order. The conditional pattern produces 2+ entries (adult, baby); the hardcoded pattern
     * produces exactly one.
     */
    private static @NotNull ConcurrentList<FieldInsnNode> collectIdentifierGetStatics(@NotNull MethodNode method) {
        ConcurrentList<FieldInsnNode> out = Concurrent.newList();
        for (AbstractInsnNode in = method.instructions.getFirst(); in != null; in = in.getNext()) {
            if (in.getOpcode() == Opcodes.GETSTATIC
                && in instanceof FieldInsnNode fi
                && ("L" + IDENTIFIER + ";").equals(fi.desc))
                out.add(fi);
        }
        return out;
    }

    /**
     * Walks the owner class's {@code <clinit>} for the canonical
     * {@code LDC "textures/entity/X.png"; INVOKESTATIC Identifier.withDefaultNamespace; PUTSTATIC
     * FIELD} triplet and returns the {@code field name -&gt; texture path} map.
     */
    private static @NotNull Map<String, String> collectStaticTextureFields(
        @NotNull ZipFile zip,
        @NotNull String classInternalName,
        @NotNull Diagnostics diagnostics
    ) {
        ClassNode cn = AsmKit.loadClass(zip, classInternalName);
        if (cn == null) return Map.of();
        MethodNode clinit = AsmKit.findMethod(cn, "<clinit>");
        if (clinit == null) return Map.of();

        Map<String, String> out = new LinkedHashMap<>();
        String pendingPath = null;
        boolean expectingPutStatic = false;

        for (AbstractInsnNode in = clinit.instructions.getFirst(); in != null; in = in.getNext()) {
            String literal = AsmKit.readStringLiteral(in);
            if (literal != null && literal.startsWith("textures/entity/")) {
                pendingPath = literal;
                expectingPutStatic = false;
                continue;
            }

            if (in instanceof MethodInsnNode mi
                && in.getOpcode() == Opcodes.INVOKESTATIC
                && IDENTIFIER.equals(mi.owner)
                && WITH_DEFAULT_NAMESPACE.equals(mi.name)
                && pendingPath != null) {
                expectingPutStatic = true;
                continue;
            }

            if (in instanceof FieldInsnNode fi
                && in.getOpcode() == Opcodes.PUTSTATIC
                && classInternalName.equals(fi.owner)
                && ("L" + IDENTIFIER + ";").equals(fi.desc)
                && expectingPutStatic
                && pendingPath != null) {
                out.put(fi.name, pendingPath);
                pendingPath = null;
                expectingPutStatic = false;
            }
        }
        return out;
    }

    /**
     * Picks the primary (adult / non-baby) field. Heuristic: prefer the first GETSTATIC whose
     * field name does NOT contain {@code "BABY"}. Falls back to the first GETSTATIC.
     */
    private static @Nullable String pickPrimaryField(@NotNull ConcurrentList<FieldInsnNode> refs) {
        if (refs.isEmpty()) return null;
        for (FieldInsnNode ref : refs)
            if (!ref.name.contains("BABY")) return ref.name;
        return refs.get(0).name;
    }

    /**
     * Resolves the instance-field-driven pattern (donkey / mule / skeleton_horse /
     * zombie_horse). Inspects {@code lambdaTypeArgs} for the per-entity Type-enum constants the
     * renderer-factory lambda passes into the constructor, walks the Type enum's {@code <clinit>}
     * for the constant's adjacent {@code LDC "textures/..."; INVOKESTATIC withDefaultNamespace}
     * pair, and pairs adult and baby variants by name (the lambda passes both, distinguished by
     * the {@code _BABY} suffix).
     *
     * <p>The Type enum's constructor signature is recovered by inspecting its declared fields:
     * any field of type {@code Identifier} is the texture; {@link #typeConstantTextureMap}
     * walks the {@code <clinit>} for {@code new Type ... ldc "..." ... putstatic NAME} chains and
     * returns {@code (constantName -> texturePath)}. Returns {@code null} when no Type-enum
     * arg is present, the Type class is unloadable, or no constant's texture path can be
     * extracted.
     */
    private static @Nullable Binding resolveInstanceFieldDriven(
        @NotNull ZipFile zip,
        @NotNull ConcurrentList<lib.minecraft.renderer.tooling.entity.EntityRendererDiscovery.TypeFieldRef> lambdaTypeArgs,
        @NotNull String rendererInternalName,
        @NotNull Diagnostics diagnostics
    ) {
        // Find the first Type-enum reference whose name does not end in BABY (the adult
        // constant). Different lambdas may push multiple Type fields - the adult is always
        // the first one whose name lacks _BABY.
        lib.minecraft.renderer.tooling.entity.EntityRendererDiscovery.TypeFieldRef adult = null;
        lib.minecraft.renderer.tooling.entity.EntityRendererDiscovery.TypeFieldRef baby = null;
        for (lib.minecraft.renderer.tooling.entity.EntityRendererDiscovery.TypeFieldRef ref : lambdaTypeArgs) {
            if (ref.name().endsWith("_BABY") && baby == null) baby = ref;
            else if (!ref.name().endsWith("_BABY") && adult == null) adult = ref;
        }
        if (adult == null) return null;

        Map<String, String> typeConstantToPath = typeConstantTextureMap(zip, adult.owner(), diagnostics);
        if (typeConstantToPath.isEmpty()) return null;
        String adultPath = typeConstantToPath.get(adult.name());
        if (adultPath == null) return null;
        String babyPath = baby == null ? null : typeConstantToPath.get(baby.name());
        return new Binding(adultPath, babyPath, null, adult.owner());
    }

    /**
     * Walks {@code <clinit>} of a {@code XRenderer$Type} enum class and returns a
     * {@code (constantName -> ModelLayers field name)} map. Each enum constant init carries a
     * {@code GETSTATIC ModelLayers.X} immediately before its {@code invokespecial Type.<init>}
     * (the model layer is the constructor's last reference-type argument); pairing the most-
     * recent ModelLayers GETSTATIC with the following PUTSTATIC yields the per-constant
     * model layer. Returns an empty map when the class has no {@code <clinit>} or no
     * matching pattern.
     *
     * <p>Used by the layer-resolver to add per-constant {@code ModelLayers.X} as additional
     * candidate fields when a renderer's lambda only pushes a saddle / equipment layer
     * (DonkeyRenderer's lambda exposes {@code DONKEY_SADDLE} but the body model lives
     * behind {@code Type.DONKEY.model = ModelLayers.DONKEY}).
     */
    public static @NotNull Map<String, String> typeConstantModelLayerMap(
        @NotNull ZipFile zip,
        @NotNull String typeOwner
    ) {
        ClassNode cn = AsmKit.loadClass(zip, typeOwner);
        if (cn == null) return Map.of();
        MethodNode clinit = AsmKit.findMethod(cn, "<clinit>");
        if (clinit == null) return Map.of();

        Map<String, String> out = new LinkedHashMap<>();
        String pendingModelLayer = null;
        for (AbstractInsnNode in = clinit.instructions.getFirst(); in != null; in = in.getNext()) {
            if (in.getOpcode() == Opcodes.GETSTATIC
                && in instanceof FieldInsnNode fi
                && "net/minecraft/client/model/geom/ModelLayers".equals(fi.owner)) {
                pendingModelLayer = fi.name;
                continue;
            }
            if (in instanceof FieldInsnNode fi
                && in.getOpcode() == Opcodes.PUTSTATIC
                && typeOwner.equals(fi.owner)
                && pendingModelLayer != null) {
                out.put(fi.name, pendingModelLayer);
                pendingModelLayer = null;
            }
        }
        return out;
    }

    /**
     * Walks {@code <clinit>} of a {@code XRenderer$Type} enum class and returns a
     * {@code (constantName -> texturePath)} map. Each enum constant init follows the canonical
     * pattern {@code new Type; dup; ldc "NAME"; iconst_<ordinal>; ldc "textures/..."; invokestatic
     * Identifier.withDefaultNamespace; ...; invokespecial Type.<init>; putstatic <NAME>}; the
     * walker pairs the most-recent {@code textures/} LDC with the immediately-following
     * {@code PUTSTATIC} of a Type field.
     */
    private static @NotNull Map<String, String> typeConstantTextureMap(
        @NotNull ZipFile zip,
        @NotNull String typeOwner,
        @NotNull Diagnostics diagnostics
    ) {
        ClassNode cn = AsmKit.loadClass(zip, typeOwner);
        if (cn == null) {
            diagnostics.info("instance-field-driven Type owner '%s' not loadable - skipped", typeOwner);
            return Map.of();
        }
        MethodNode clinit = AsmKit.findMethod(cn, "<clinit>");
        if (clinit == null) return Map.of();

        Map<String, String> out = new LinkedHashMap<>();
        String pendingPath = null;
        boolean expectingPutStatic = false;
        for (AbstractInsnNode in = clinit.instructions.getFirst(); in != null; in = in.getNext()) {
            String literal = AsmKit.readStringLiteral(in);
            if (literal != null && literal.startsWith("textures/entity/")) {
                pendingPath = literal;
                expectingPutStatic = false;
                continue;
            }
            if (in instanceof MethodInsnNode mi
                && in.getOpcode() == Opcodes.INVOKESTATIC
                && IDENTIFIER.equals(mi.owner)
                && WITH_DEFAULT_NAMESPACE.equals(mi.name)
                && pendingPath != null) {
                expectingPutStatic = true;
                continue;
            }
            if (in instanceof FieldInsnNode fi
                && in.getOpcode() == Opcodes.PUTSTATIC
                && typeOwner.equals(fi.owner)
                && expectingPutStatic
                && pendingPath != null) {
                out.put(fi.name, pendingPath);
                pendingPath = null;
                expectingPutStatic = false;
            }
        }
        return out;
    }

    /**
     * Resolves the method-dispatch pattern (parrot / shulker / copper_golem / fox) by chasing
     * the single {@code INVOKESTATIC ...:Lidentifier;} call in {@code getTextureLocation} into
     * the target method, finding its first {@code GETSTATIC :LIdentifier;} field via the same
     * default-path walker used for direct bindings, then looking up the texture path in the
     * dispatched class's {@code <clinit>}.
     *
     * <p>Returns a primary {@link Binding} on success, {@code null} when the dispatch couldn't
     * be resolved (no INVOKESTATIC, target method missing, target method returns no static
     * Identifier, or the field is wired through an array indirection like Shulker's
     * {@code TEXTURE_LOCATION[]} that this walker does not unfold). The returned binding's
     * {@code hierarchySource} is the dispatched method's owner class so diagnostics can trace
     * which class supplied the texture path.
     */
    private static @Nullable Binding chaseStaticDispatch(
        @NotNull ZipFile zip,
        @NotNull MethodNode method,
        @NotNull String rendererInternalName,
        @NotNull Diagnostics diagnostics
    ) {
        MethodInsnNode dispatch = null;
        for (AbstractInsnNode in = method.instructions.getFirst(); in != null; in = in.getNext()) {
            if (in.getOpcode() == Opcodes.INVOKESTATIC
                && in instanceof MethodInsnNode mi
                && mi.desc.endsWith(")L" + IDENTIFIER + ";")
                && !(IDENTIFIER.equals(mi.owner) && WITH_DEFAULT_NAMESPACE.equals(mi.name))) {
                dispatch = mi;
                break;
            }
        }
        if (dispatch == null) return null;

        ClassNode targetCn = AsmKit.loadClass(zip, dispatch.owner);
        if (targetCn == null) return null;
        MethodNode target = null;
        for (MethodNode m : targetCn.methods) {
            if (m.name.equals(dispatch.name) && m.desc.equals(dispatch.desc)) {
                target = m;
                break;
            }
        }
        if (target == null) return null;

        String primaryField = findPrimaryByDefaultPath(target);
        if (primaryField == null) return null;
        Map<String, String> classFieldToPath = collectStaticTextureFields(zip, dispatch.owner, diagnostics);
        String primaryPath = classFieldToPath.get(primaryField);
        if (primaryPath == null) return null;
        String babyPath = findBabyPathByPair(primaryPath, classFieldToPath);
        return new Binding(primaryPath, babyPath, null,
            dispatch.owner.equals(rendererInternalName) ? null : dispatch.owner);
    }

    /**
     * Traces the {@code getTextureLocation} body assuming every boolean state-flag is false:
     * each {@code IFEQ} (jump-if-zero) is taken, each {@code GOTO} is followed, every other
     * branch type falls through linearly. Returns the field name of the first
     * {@code GETSTATIC X:LIdentifier;} reached on that path, or {@code null} when no
     * {@code Identifier} GETSTATIC is reachable.
     *
     * <p>For renderers whose {@code getTextureLocation} is a deeply nested
     * {@code if (flag) { ... if (flag2) { ... } else { ... } } else { ... }} chain
     * (BeeRenderer is the canonical case: {@code isAngry / hasNectar / isBaby}), this surfaces
     * the field on the all-defaults branch - the texture rendered when the entity is in its
     * neutral idle state - which is the right choice for "primary" texture binding. The
     * lexically-first non-BABY {@link #pickPrimaryField} fallback would instead pick the
     * first overlay-state texture (ANGRY_NECTAR_BEE_TEXTURE for bee), which is wrong.
     *
     * <p>The visited set prevents infinite loops if the bytecode contains a cycle (none of the
     * vanilla {@code getTextureLocation} bodies do, but defensive). Returning {@code null}
     * triggers the {@link #pickPrimaryField} fallback, preserving the prior behaviour for any
     * renderer whose body shape doesn't match this assumption.
     */
    private static @Nullable String findPrimaryByDefaultPath(@NotNull MethodNode method) {
        Set<AbstractInsnNode> visited = new HashSet<>();
        AbstractInsnNode in = method.instructions.getFirst();
        while (in != null && visited.add(in)) {
            if (in.getOpcode() == Opcodes.GETSTATIC
                && in instanceof FieldInsnNode fi
                && ("L" + IDENTIFIER + ";").equals(fi.desc))
                return fi.name;
            if (in instanceof JumpInsnNode jin
                && (in.getOpcode() == Opcodes.IFEQ || in.getOpcode() == Opcodes.GOTO)) {
                in = jin.label;
                continue;
            }
            in = in.getNext();
        }
        return null;
    }

    /**
     * Picks the baby variant field if present. Returns the first GETSTATIC whose field name
     * contains {@code "BABY"} and differs from {@code primaryField}.
     */
    private static @Nullable String pickBabyField(
        @NotNull ConcurrentList<FieldInsnNode> refs,
        @Nullable String primaryField
    ) {
        for (FieldInsnNode ref : refs)
            if (ref.name.contains("BABY") && !ref.name.equals(primaryField)) return ref.name;
        return null;
    }

    /**
     * Returns the first {@code textures/entity/...} path bound in the renderer's
     * {@code <clinit>} whose corresponding field name does not contain {@code BABY}, or
     * {@code null} when the map is empty or every non-BABY field is missing. Used as the
     * last-resort fallback for renderers whose binding pattern (enum-map / chained dispatch)
     * defeats the structural walkers but whose {@code <clinit>} does declare standard
     * texture-field constants for the variant set. Returning the first non-BABY path keeps
     * the base entity textured rather than null.
     */
    private static @Nullable String pickFirstNonBabyTexturePath(@NotNull Map<String, String> classFieldToPath) {
        if (classFieldToPath.isEmpty()) return null;
        for (Map.Entry<String, String> e : classFieldToPath.entrySet())
            if (!e.getKey().contains("BABY")) return e.getValue();
        return classFieldToPath.values().iterator().next();
    }

    /**
     * Collects every {@code LDC "textures/entity/..."} literal in the given class's
     * {@code <clinit>} and any synthetic {@code lambda$static$*} methods, in walk order.
     * Used as the deepest-fallback for renderers that push texture paths directly into a
     * wrapper-class constructor without binding them to static fields (HorseRenderer /
     * LlamaRenderer / PandaRenderer) and for those that build the variant map inside a
     * {@code Util.make(newHashMap, lambda$static$0)} populator (MushroomCowRenderer).
     * Returns an empty list when the class is unloadable, has no {@code <clinit>}, or
     * declares no matching literals anywhere.
     */
    private static @NotNull ConcurrentList<String> collectAllTextureLiterals(
        @NotNull ZipFile zip,
        @NotNull String classInternalName
    ) {
        ConcurrentList<String> out = Concurrent.newList();
        ClassNode cn = AsmKit.loadClass(zip, classInternalName);
        if (cn == null) return out;
        MethodNode clinit = AsmKit.findMethod(cn, "<clinit>");
        if (clinit != null) collectTextureLiteralsFromMethod(clinit, out);
        for (MethodNode m : cn.methods)
            if (m.name.startsWith("lambda$static$"))
                collectTextureLiteralsFromMethod(m, out);
        return out;
    }

    /**
     * Appends every {@code "textures/entity/...png"} string literal in the method to
     * {@code out}, skipping format-string templates ({@code %s}-bearing paths) which are not
     * real on-disk files - AxolotlRenderer holds {@code "textures/entity/axolotl/axolotl_%s.png"}
     * and computes per-variant paths via {@link String#format(String, Object...)} at runtime.
     */
    private static void collectTextureLiteralsFromMethod(
        @NotNull MethodNode method,
        @NotNull ConcurrentList<String> out
    ) {
        for (AbstractInsnNode in = method.instructions.getFirst(); in != null; in = in.getNext()) {
            String literal = AsmKit.readStringLiteral(in);
            if (literal != null
                && literal.startsWith("textures/entity/")
                && literal.endsWith(".png")
                && !literal.contains("%"))
                out.add(literal);
        }
    }

    /**
     * Resolves the texture path for the canonical default variant by combining two ASM walks:
     * <ol>
     *   <li>Walk every {@code lambda$static$N} (and {@code <clinit>}) of the renderer for the
     *       pattern {@code GETSTATIC <Variant>.<NAME>; ...; LDC <texture>} - building a map of
     *       {@code <NAME> -> texture path} keyed by the most recent variant GETSTATIC seen
     *       before each non-baby texture LDC.</li>
     *   <li>Walk the variant enum's {@code <clinit>} for the {@code DEFAULT} static field
     *       initialisation pattern ({@code GETSTATIC <NAME>; PUTSTATIC DEFAULT}) and pull the
     *       constant name.</li>
     * </ol>
     * Returns the texture path bound to that constant, or {@code null} when the variant enum
     * has no {@code DEFAULT} field (parrot's {@code Parrot$Variant.DEFAULT = RED_BLUE} resolves
     * to RED_BLUE; mushroom_cow's {@code MushroomCow$Variant.DEFAULT = RED} picks
     * {@code mooshroom_red.png}). Falls through when no variant-keyed map is built in any of
     * the renderer's lambdas (renderers that don't use the {@code Util.make(newHashMap, lambda)}
     * pattern).
     */
    private static @Nullable String findDefaultVariantLiteral(
        @NotNull ZipFile zip,
        @NotNull String rendererInternalName
    ) {
        ClassNode cn = AsmKit.loadClass(zip, rendererInternalName);
        if (cn == null) return null;
        Map<String, String> variantToTexture = new LinkedHashMap<>();
        String variantClass = collectVariantToTextureMap(cn, variantToTexture);
        if (variantClass == null || variantToTexture.isEmpty()) return null;
        String defaultName = findEnumDefaultName(zip, variantClass);
        if (defaultName == null) return null;
        return variantToTexture.get(defaultName);
    }

    /**
     * Scans every lambda and the {@code <clinit>} of {@code cn} for the variant-map population
     * pattern. Tracks the most recent {@code GETSTATIC} of an enum-typed field; pairs the next
     * non-baby texture LDC seen with that variant constant name. Returns the JVM internal name
     * of the variant enum (the owner of the GETSTATICs we tracked), or {@code null} when no
     * variant-typed map is built.
     */
    private static @Nullable String collectVariantToTextureMap(
        @NotNull ClassNode cn,
        @NotNull Map<String, String> out
    ) {
        String variantClass = null;
        for (MethodNode m : cn.methods) {
            if (!m.name.startsWith("lambda$static$") && !"<clinit>".equals(m.name)) continue;
            String pendingVariantName = null;
            for (AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext()) {
                if (in.getOpcode() == Opcodes.GETSTATIC && in instanceof FieldInsnNode fi
                    && fi.desc.startsWith("L")
                    && fi.desc.endsWith(";")
                    && (fi.owner.endsWith("$Variant") || fi.owner.endsWith("Variant"))) {
                    pendingVariantName = fi.name;
                    if (variantClass == null) variantClass = fi.owner;
                    continue;
                }
                String literal = AsmKit.readStringLiteral(in);
                if (literal != null
                    && literal.startsWith("textures/entity/")
                    && literal.endsWith(".png")
                    && !literal.contains("%")
                    && !literal.endsWith("_baby.png")
                    && pendingVariantName != null
                    && !out.containsKey(pendingVariantName)) {
                    out.put(pendingVariantName, literal);
                }
            }
        }
        return variantClass;
    }

    /**
     * Walks the enum class's {@code <clinit>} for the {@code DEFAULT} static field initialiser
     * pattern: {@code GETSTATIC <CONSTANT_NAME>; PUTSTATIC DEFAULT}. Returns the constant name
     * referenced (e.g. {@code "RED"} for {@code MushroomCow$Variant.DEFAULT = RED}), or
     * {@code null} when no {@code DEFAULT} field exists or its initialiser doesn't follow the
     * standard pattern.
     */
    private static @Nullable String findEnumDefaultName(@NotNull ZipFile zip, @NotNull String enumInternalName) {
        ClassNode cn = AsmKit.loadClass(zip, enumInternalName);
        if (cn == null) return null;
        MethodNode clinit = AsmKit.findMethod(cn, "<clinit>");
        if (clinit == null) return null;
        String pendingFieldName = null;
        for (AbstractInsnNode in = clinit.instructions.getFirst(); in != null; in = in.getNext()) {
            if (in.getOpcode() == Opcodes.GETSTATIC
                && in instanceof FieldInsnNode fi
                && enumInternalName.equals(fi.owner)) {
                pendingFieldName = fi.name;
                continue;
            }
            if (in.getOpcode() == Opcodes.PUTSTATIC
                && in instanceof FieldInsnNode fi
                && enumInternalName.equals(fi.owner)
                && "DEFAULT".equals(fi.name)
                && pendingFieldName != null)
                return pendingFieldName;
        }
        return null;
    }

    /**
     * Derives the {@code _baby.png} sibling of a primary texture path. {@code "X/Y.png"} ->
     * {@code "X/Y_baby.png"}. Used when the texture LDC list isn't available so
     * {@link #findBabyLiteralByPair} can't run; trusts vanilla's naming convention.
     */
    private static @Nullable String derivedBabyPath(@NotNull String primaryPath) {
        if (!primaryPath.endsWith(".png")) return null;
        return primaryPath.substring(0, primaryPath.length() - ".png".length()) + "_baby.png";
    }

    /** Picks the first literal whose path stem (filename minus {@code .png}) does not end in {@code _baby}. */
    private static @Nullable String pickFirstNonBabyLiteral(@NotNull ConcurrentList<String> literals) {
        for (String l : literals) {
            String base = l.substring(0, l.length() - ".png".length());
            if (!base.endsWith("_baby")) return l;
        }
        return literals.isEmpty() ? null : literals.get(0);
    }

    /** Looks up the {@code _baby.png} sibling of the primary in a list of LDC-collected paths. */
    private static @Nullable String findBabyLiteralByPair(
        @NotNull String primaryPath,
        @NotNull ConcurrentList<String> literals
    ) {
        if (!primaryPath.endsWith(".png")) return null;
        String stem = primaryPath.substring(0, primaryPath.length() - ".png".length());
        String derived = stem + "_baby.png";
        for (String l : literals)
            if (derived.equals(l)) return derived;
        return null;
    }

    /**
     * Derives the baby texture path from the primary by appending {@code _baby} to the file
     * stem ({@code .../bee.png -> .../bee_baby.png}) and looking it up in the class's
     * texture-field map. Returns the matching path or {@code null} when no field declares the
     * derived path. Used for renderers whose baby field is not the lexically-first BABY-named
     * field (BeeRenderer's BEE_BABY_TEXTURE comes after three angry / nectar variants).
     */
    private static @Nullable String findBabyPathByPair(
        @NotNull String primaryPath,
        @NotNull Map<String, String> classFieldToPath
    ) {
        if (!primaryPath.endsWith(".png")) return null;
        String stem = primaryPath.substring(0, primaryPath.length() - ".png".length());
        String derived = stem + "_baby.png";
        for (String path : classFieldToPath.values())
            if (derived.equals(path)) return derived;
        return null;
    }

    /** Returns the hierarchy-source label or {@code null} when binding lives on the renderer itself. */
    private static @Nullable String sourceLabel(@NotNull ResolvedMethod resolved, @NotNull String rendererInternal) {
        return resolved.declaringClass.equals(rendererInternal) ? null : resolved.declaringClass;
    }

    /** Internal record pairing a {@link MethodNode} with its declaring class's internal name. */
    private record ResolvedMethod(@NotNull MethodNode method, @NotNull String declaringClass) {}

}
