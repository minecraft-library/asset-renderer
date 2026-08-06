package lib.minecraft.renderer.tooling.entity;

import lib.minecraft.renderer.tooling.kernel.AsmKit;
import lib.minecraft.renderer.tooling.kernel.ClassNodeCache;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lib.minecraft.renderer.tooling.kernel.ToolingSession;
import lib.minecraft.renderer.tooling.kernel.VanillaSourceClasses;
import lib.minecraft.renderer.tooling.walk.AsmWalker;
import lib.minecraft.renderer.tooling.walk.Cells;
import lib.minecraft.renderer.tooling.walk.Insn;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Node {@code texture} - the family's primary texture as a FULL namespaced vanilla asset
 * path ({@code minecraft:textures/entity/wolf/wolf.png} - what the bytecode LDCs say).
 *
 * <p>The resolution cascade covers: data-driven-variant detection (hands off to the variant
 * axis, node returns {@code null}), entityId-basename match for shared renderers (piglin /
 * piglin_brute), the all-flags-false default-path walker (bee), static-dispatch chase
 * (parrot / fox), instance-field / Type-enum walk (donkey family), {@code <clinit>} literal
 * fallbacks, and the state-field-driven variant fallback (wolf / cat - texture table exists,
 * binding unresolved).
 *
 * <p>The 14-suffix {@code NON_BASE_STEM_SUFFIXES} denylist is derived from the live texture
 * universe ({@link #deriveNonBaseSuffixes}); {@code %}-template literals are filtered by
 * {@code cache.hasEntry} existence probes rather than a {@code contains("%")} heuristic;
 * unresolved bindings are reported via typed diagnostics.
 */
final class EntityTextureResolver {

    /**
     * Recurrence threshold for the derived non-base-suffix set: a suffix qualifies when a
     * base + suffixed sibling pair co-exists in at least this many distinct texture
     * directories (state overlays recur; data-variant names appear once), see
     * {@link EntityNamingPolicies#SUFFIX_MIN_RECURRENCE}.
     */
    private static final int SUFFIX_MIN_RECURRENCE = EntityNamingPolicies.SUFFIX_MIN_RECURRENCE.intValue();

    /**
     * The variant enums' canonical-default static field name ({@code Axolotl$Variant.DEFAULT}),
     * see {@link EntityNamingPolicies#ENUM_DEFAULT_FIELD}.
     */
    private static final @NotNull String DEFAULT_FIELD = EntityNamingPolicies.ENUM_DEFAULT_FIELD.stringValue();

    /**
     * The render-state variant field name vanilla uses on every variant RenderState class.
     */
    private static final @NotNull String VARIANT_FIELD = VanillaSourceClasses.Fields.VARIANT;

    private final @NotNull ClassNodeCache cache;
    private final @NotNull EntitySubject subject;
    private final @NotNull VariantIndex variants;
    private final @NotNull Set<String> nonBaseSuffixes;
    private final @NotNull Diagnostics diagnostics;

    EntityTextureResolver(@NotNull EntityContext context) {
        this.cache = context.cache();
        this.subject = context.subject();
        this.variants = context.indexes().variants();
        this.nonBaseSuffixes = context.indexes().nonBaseSuffixes();
        this.diagnostics = context.diagnostics();
    }

    /**
     * Resolves the primary texture path, or {@code null} on variant-axis families (per-option
     * textures live in {@code axes.variant}) and unresolvable bindings (typed diagnostics
     * recorded).
     *
     * @return the full namespaced asset path, or {@code null} to omit the node
     */
    @Nullable String resolve() {
        Binding binding = resolveBinding();

        // Enum-default refinement (axolotl / rabbit): the renderer reads an enum-typed
        // state.variant whose DEFAULT names the canonical texture per the
        // <entity>/<entity>_<default> stem convention, existence-gated so only
        // conforming entities take it.
        String enumDefault = resolveEnumDefaultName();
        if (enumDefault != null) {
            String localId = this.subject.localId();
            String candidate = VanillaSourceClasses.Paths.TEXTURES_ENTITY + localId + "/" + localId + "_" + enumDefault + ".png";
            if (entryExists(candidate)) {
                this.diagnostics.info("texture via enum-default '%s'", enumDefault);
                binding = new Binding(candidate, binding.variantDriven());
            }
        }

        // Class-bytes fallback for renderers the cascade leaves unresolved (ender dragon's
        // submit-time static fields, copper golem's wrapper-type dispatch, shulker's Sheets
        // indirection).
        if (binding.primary() == null && !binding.variantDriven()) {
            String stem = findBaseTextureFallback();
            if (stem != null) {
                this.diagnostics.info("texture via class-bytes fallback");
                binding = new Binding(VanillaSourceClasses.Paths.TEXTURES_ENTITY + stem + ".png", false);
            }
        }

        // State-field-driven variant fallback (wolf / cat): getTextureLocation returns
        // state.texture populated upstream from a variant lookup this walk can't trace; the
        // variant table's existence is the reliable signal the family is variant-driven.
        if (binding.primary() == null && !binding.variantDriven() && this.variants.hasTable(this.subject.localId())) {
            this.diagnostics.info("texture deferred to variant axis (state-field-driven; variant table exists)");
            binding = new Binding(null, true);
        }

        if (binding.variantDriven()) return null;
        if (binding.primary() == null) {
            this.diagnostics.warn("texture binding unresolved for renderer '%s'", this.subject.rendererClass());
            return null;
        }
        return VanillaSourceClasses.Paths.MINECRAFT_NAMESPACE + binding.primary();
    }

    /**
     * The extracted binding: the primary path (jar-relative, no namespace) or the
     * variant-driven marker.
     *
     * @param primary the adult / default texture path, or {@code null}
     * @param variantDriven whether per-option textures come from the variant axis instead
     */
    private record Binding(@Nullable String primary, boolean variantDriven) {}

    // ------------------------------------------------------------------------------------
    // the resolution cascade
    // ------------------------------------------------------------------------------------

    private @NotNull Binding resolveBinding() {
        ResolvedMethod resolved = findGetTextureLocation();
        if (resolved == null) {
            this.diagnostics.info("renderer '%s' has no getTextureLocation in its hierarchy", this.subject.rendererClass());
            return new Binding(null, false);
        }

        // Data-driven variant (cow / pig / chicken / frog): the texture lives in
        // data/minecraft/<X>_variant/*.json - the variant axis supplies per-option paths.
        if (detectDataDrivenVariant(resolved.method()) != null) {
            this.diagnostics.info("texture deferred to variant axis (data-driven variant call chain)");
            return new Binding(null, true);
        }

        Map<String, String> classFieldToPath = collectStaticTextureFields(resolved.declaringClass());

        // EntityId-basename match: a renderer shared across entities
        // (PiglinRenderer serves piglin + piglin_brute) branches on a render-state field; a
        // field whose path basename matches the entity id is that entity's branch.
        String entityMatched = pickFieldByEntityIdMatch(classFieldToPath);
        if (entityMatched != null) return new Binding(classFieldToPath.get(entityMatched), false);

        // All-flags-false default-path trace (bee): the field on the neutral-idle branch.
        String primaryField = findPrimaryByDefaultPath(resolved.method());
        if (primaryField != null && classFieldToPath.containsKey(primaryField))
            return new Binding(classFieldToPath.get(primaryField), false);

        // Pure static dispatch (parrot / shulker / fox): chase the INVOKESTATIC into its
        // target and re-run the default-path walk there.
        Binding chased = chaseStaticDispatch(resolved.method());
        if (chased != null) return chased;

        String patternFlag = detectVariantPatternFlag(resolved.method());
        if ("instance-field".equals(patternFlag) && !this.subject.lambdaTypeArgs().isEmpty()) {
            // Instance-field-driven (donkey / mule / skeleton_horse): the texture is a ctor
            // field initialised from the lambda-supplied Type constant; the Type enum's
            // <clinit> binds each constant's texture literal.
            String instancePath = resolveInstanceFieldDriven();
            if (instancePath != null) {
                this.diagnostics.info("texture via Type-enum constant (instance-field-driven)");
                return new Binding(instancePath, false);
            }
        }
        if (patternFlag != null) {
            // Enum-map / chained-dispatch renderers (horse / llama / panda / mushroom_cow /
            // parrot / copper_golem): no direct Identifier GETSTATIC - fall back to the
            // declaring class's <clinit>-bound paths, preferring the canonical enum DEFAULT
            // over whichever key the populating lambda puts first (mooshroom RED, not BROWN).
            this.diagnostics.info("texture pattern '%s' - falling back to <clinit> literals", patternFlag);
            String fallbackPath = pickFirstNonBabyTexturePath(classFieldToPath);
            if (fallbackPath != null) return new Binding(fallbackPath, false);
            String defaultVariantPath = findDefaultVariantLiteral(resolved.declaringClass());
            if (defaultVariantPath != null) return new Binding(defaultVariantPath, false);
            String literalPrimary = pickFirstNonBabyLiteral(collectAllTextureLiterals(resolved.declaringClass()));
            if (literalPrimary != null) return new Binding(literalPrimary, false);
            return new Binding(null, false);
        }

        // Last resort: the first non-BABY Identifier GETSTATIC bound in the declaring class.
        List<FieldInsnNode> getStatics = collectIdentifierGetStatics(resolved.method());
        if (getStatics.isEmpty() || classFieldToPath.isEmpty()) {
            this.diagnostics.info("renderer '%s' getTextureLocation matches no supported binding pattern", this.subject.rendererClass());
            return new Binding(null, false);
        }
        for (FieldInsnNode ref : getStatics)
            if (!ref.name.contains("BABY") && classFieldToPath.containsKey(ref.name))
                return new Binding(classFieldToPath.get(ref.name), false);
        return new Binding(classFieldToPath.get(getStatics.getFirst().name), false);
    }

    /**
     * Walks the renderer's superclass chain for the first non-bridge
     * {@code getTextureLocation} returning {@code Identifier}, paired with its declaring
     * class (zombie / husk / drowned inherit it from {@code AbstractZombieRenderer}).
     * Open-coded early-out walk - the chain helper would load every ancestor eagerly.
     */
    private @Nullable ResolvedMethod findGetTextureLocation() {
        String current = this.subject.rendererClass();
        while (current != null && !AsmKit.OBJECT_INTERNAL.equals(current)) {
            ClassNode cn = this.cache.load(current);
            if (cn == null) return null;
            for (MethodNode m : cn.methods) {
                if (!VanillaSourceClasses.Methods.GET_TEXTURE_LOCATION.equals(m.name)) continue;
                if (!AsmKit.descriptorReturns(m.desc, VanillaSourceClasses.Types.IDENTIFIER)) continue;
                if (isBridgeMethod(m)) continue;
                return new ResolvedMethod(m, current);
            }
            current = cn.superName;
        }
        return null;
    }

    /**
     * A bridge override delegates to the typed overload via checkcast + invokevirtual and
     * carries no binding info of its own.
     */
    private static boolean isBridgeMethod(@NotNull MethodNode method) {
        return AsmWalker.over(method).any(in -> in.getOpcode() == Opcodes.INVOKEVIRTUAL
            && in instanceof MethodInsnNode mi
            && VanillaSourceClasses.Methods.GET_TEXTURE_LOCATION.equals(mi.name));
    }

    /**
     * A resolved {@code getTextureLocation} paired with the class that declares it.
     *
     * @param method the resolved method node
     * @param declaringClass the declaring class's JVM internal name
     */
    private record ResolvedMethod(@NotNull MethodNode method, @NotNull String declaringClass) {}

    // ------------------------------------------------------------------------------------
    // pattern detectors
    // ------------------------------------------------------------------------------------

    /**
     * The {@code XVariant} class internal name when the method pulls its texture from a
     * data-driven variant call ({@code state.variant.modelAndTexture().asset().texturePath()}
     * or {@code state.variant.babyTexture()}), or {@code null} otherwise. Suffix policy is
     * {@link EntityNamingPolicies#DATA_VARIANT_SUFFIXES} (owner suffix, exact accessor,
     * accessor suffix, in declaration order).
     */
    private static @Nullable String detectDataDrivenVariant(@NotNull MethodNode method) {
        List<String> suffixes = EntityNamingPolicies.DATA_VARIANT_SUFFIXES.strings();
        String ownerSuffix = suffixes.getFirst();
        String exactAccessor = suffixes.get(1);
        String accessorSuffix = suffixes.getLast();
        return AsmWalker.over(method).firstNotNull(in -> in instanceof MethodInsnNode mi
            && in.getOpcode() == Opcodes.INVOKEVIRTUAL
            && (mi.name.endsWith(accessorSuffix) || exactAccessor.equals(mi.name))
            && mi.owner.endsWith(ownerSuffix)
            ? mi.owner : null);
    }

    /**
     * Classifies an unresolved body by its bytecode pattern - {@code enum-map} (a static
     * {@code Map} + {@code Map.get}), {@code method-dispatch} (an Identifier-returning call
     * the walkers don't unfold), or {@code instance-field} ({@code aload_0; getfield
     * <field>:LIdentifier;}, donkey family). {@code null} when none match.
     */
    private static @Nullable String detectVariantPatternFlag(@NotNull MethodNode method) {
        AsmWalker body = AsmWalker.over(method);
        boolean sawStaticMap = body.any(in -> in.getOpcode() == Opcodes.GETSTATIC
            && in instanceof FieldInsnNode fi
            && "Ljava/util/Map;".equals(fi.desc));
        boolean sawMapGet = body.any(in -> in.getOpcode() == Opcodes.INVOKEINTERFACE
            && in instanceof MethodInsnNode mi
            && "java/util/Map".equals(mi.owner)
            && "get".equals(mi.name));
        boolean sawIdentifierReturningCall = body.any(in -> in instanceof MethodInsnNode mi
            && (in.getOpcode() == Opcodes.INVOKESTATIC || in.getOpcode() == Opcodes.INVOKEVIRTUAL)
            && AsmKit.descriptorReturns(mi.desc, VanillaSourceClasses.Types.IDENTIFIER)
            && !isWithDefaultNamespace(mi));
        if (sawStaticMap && sawMapGet) return "enum-map";
        if (sawIdentifierReturningCall) return "method-dispatch";
        return AsmWalker.over(method).any(in -> in.getOpcode() == Opcodes.GETFIELD
            && in instanceof FieldInsnNode fi
            && VanillaSourceClasses.Descs.IDENTIFIER_REF.equals(fi.desc)
            && AsmKit.previousReal(in) instanceof VarInsnNode load
            && load.getOpcode() == Opcodes.ALOAD
            && load.var == 0) ? "instance-field" : null;
    }

    /**
     * Reports whether {@code mi} is the {@code Identifier.withDefaultNamespace} factory itself.
     */
    private static boolean isWithDefaultNamespace(@NotNull MethodInsnNode mi) {
        return VanillaSourceClasses.Types.IDENTIFIER.equals(mi.owner)
            && VanillaSourceClasses.Methods.WITH_DEFAULT_NAMESPACE.equals(mi.name);
    }

    /**
     * Traces the body assuming every boolean state flag is false: each {@code IFEQ} is
     * taken, each {@code GOTO} followed, everything else falls through. Returns the field of
     * the first {@code GETSTATIC :LIdentifier;} on that path - the neutral-idle texture
     * (bee) - or {@code null} when none is reachable.
     */
    private static @Nullable String findPrimaryByDefaultPath(@NotNull MethodNode method) {
        // A revisited node ends the trace as a miss.
        return AsmWalker.over(method).traceFirst(
            in -> in.getOpcode() == Opcodes.GETSTATIC
                && in instanceof FieldInsnNode fi
                && VanillaSourceClasses.Descs.IDENTIFIER_REF.equals(fi.desc) ? fi.name : null,
            in -> in instanceof JumpInsnNode jump
                && (in.getOpcode() == Opcodes.IFEQ || in.getOpcode() == Opcodes.GOTO)
                ? jump.label : in.getNext());
    }

    /**
     * Every {@code GETSTATIC <field>:LIdentifier;} in the method, in source order.
     */
    private static @NotNull List<FieldInsnNode> collectIdentifierGetStatics(@NotNull MethodNode method) {
        return AsmWalker.over(method)
            .ofType(FieldInsnNode.class)
            .where(fi -> fi.getOpcode() == Opcodes.GETSTATIC
                && VanillaSourceClasses.Descs.IDENTIFIER_REF.equals(fi.desc))
            .toList();
    }

    // ------------------------------------------------------------------------------------
    // field / literal walkers
    // ------------------------------------------------------------------------------------

    /**
     * Walks the class's {@code <clinit>} for the canonical {@code LDC "textures/entity/X.png";
     * INVOKESTATIC Identifier.withDefaultNamespace; PUTSTATIC FIELD} triplet and returns the
     * field-to-path map.
     */
    private @NotNull Map<String, String> collectStaticTextureFields(@NotNull String classInternalName) {
        ClassNode cn = this.cache.load(classInternalName);
        if (cn == null) return Map.of();
        MethodNode clinit = AsmKit.findMethod(cn, AsmKit.CLINIT);
        if (clinit == null) return Map.of();

        Map<String, String> out = new LinkedHashMap<>();
        Cells.Latch<String> pendingPath = Cells.latch();
        Cells.Flag expectingPutStatic = Cells.flag();
        AsmWalker.over(clinit)
            .feed(pendingPath)
            .feed(expectingPutStatic)
            .on(Insn.of(AbstractInsnNode.class, in -> {
                String literal = AsmWalker.stringLiteral(in);
                return literal != null && literal.startsWith(VanillaSourceClasses.Paths.TEXTURES_ENTITY);
            }), in -> {
                String literal = AsmWalker.stringLiteral(in);
                if (literal == null) return;
                pendingPath.set(literal);
                expectingPutStatic.clear();
            })
            .on(Insn.of(MethodInsnNode.class, mi -> mi.getOpcode() == Opcodes.INVOKESTATIC
                && isWithDefaultNamespace(mi)
                && pendingPath.get() != null), mi -> expectingPutStatic.set())
            .commitAt(Insn.putStatic(classInternalName)
                    .and(fi -> VanillaSourceClasses.Descs.IDENTIFIER_REF.equals(fi.desc)
                        && expectingPutStatic.get()
                        && pendingPath.get() != null),
                fi -> out.put(fi.name, pendingPath.get()))
            .run();
        return out;
    }

    /**
     * The field whose path basename exactly matches {@code /<entityId>.png} (slash-anchored
     * so {@code piglin/piglin.png} never matches {@code piglin_brute}), skipping
     * {@code _baby.png} paths. {@code null} when no basename matches.
     */
    private @Nullable String pickFieldByEntityIdMatch(@NotNull Map<String, String> classFieldToPath) {
        String wantedSuffix = "/" + this.subject.localId() + ".png";
        for (Map.Entry<String, String> entry : classFieldToPath.entrySet()) {
            String path = entry.getValue();
            if (path.endsWith("_baby.png")) continue;
            if (path.endsWith(wantedSuffix)) return entry.getKey();
        }
        return null;
    }

    /**
     * Chases the single Identifier-returning {@code INVOKESTATIC} in the method into its
     * target, re-running the default-path walk there (parrot / shulker pure dispatch).
     * {@code null} when the dispatch can't be resolved.
     */
    private @Nullable Binding chaseStaticDispatch(@NotNull MethodNode method) {
        MethodInsnNode dispatch = AsmWalker.over(method)
            .ofType(MethodInsnNode.class)
            .where(mi -> mi.getOpcode() == Opcodes.INVOKESTATIC
                && AsmKit.descriptorReturns(mi.desc, VanillaSourceClasses.Types.IDENTIFIER)
                && !isWithDefaultNamespace(mi))
            .first();
        if (dispatch == null) return null;

        ClassNode targetCn = this.cache.load(dispatch.owner);
        if (targetCn == null) return null;
        MethodNode target = AsmKit.findMethod(targetCn, dispatch.name, dispatch.desc);
        if (target == null) return null;

        String primaryField = findPrimaryByDefaultPath(target);
        if (primaryField == null) return null;
        String primaryPath = collectStaticTextureFields(dispatch.owner).get(primaryField);
        if (primaryPath == null) return null;
        this.diagnostics.info("texture via static dispatch into '%s'", dispatch.owner);
        return new Binding(primaryPath, false);
    }

    /**
     * Resolves the instance-field-driven pattern (donkey / mule / skeleton_horse): the
     * lambda's first non-{@code _BABY} Type constant, looked up in the Type enum's
     * {@code <clinit>} texture bindings.
     */
    private @Nullable String resolveInstanceFieldDriven() {
        EntitySubject.TypeFieldRef adult = null;
        for (EntitySubject.TypeFieldRef ref : this.subject.lambdaTypeArgs())
            if (!ref.name().endsWith("_BABY")) {
                adult = ref;
                break;
            }
        if (adult == null) return null;
        return typeConstantTextureMap(adult.owner()).get(adult.name());
    }

    /**
     * Walks a {@code $Type} enum's {@code <clinit>} pairing the most recent
     * {@code textures/entity/} LDC (wrapped by {@code withDefaultNamespace}) with the
     * following {@code PUTSTATIC} of a Type field, returning the constant-to-path map.
     */
    private @NotNull Map<String, String> typeConstantTextureMap(@NotNull String typeOwner) {
        ClassNode cn = this.cache.load(typeOwner);
        if (cn == null) return Map.of();
        MethodNode clinit = AsmKit.findMethod(cn, AsmKit.CLINIT);
        if (clinit == null) return Map.of();

        Map<String, String> out = new LinkedHashMap<>();
        Cells.Latch<String> pendingPath = Cells.latch();
        Cells.Flag expectingPutStatic = Cells.flag();
        AsmWalker.over(clinit)
            .feed(pendingPath)
            .feed(expectingPutStatic)
            .on(Insn.of(AbstractInsnNode.class, in -> {
                String literal = AsmWalker.stringLiteral(in);
                return literal != null && literal.startsWith(VanillaSourceClasses.Paths.TEXTURES_ENTITY);
            }), in -> {
                String literal = AsmWalker.stringLiteral(in);
                if (literal == null) return;
                pendingPath.set(literal);
                expectingPutStatic.clear();
            })
            .on(Insn.of(MethodInsnNode.class, mi -> mi.getOpcode() == Opcodes.INVOKESTATIC
                && isWithDefaultNamespace(mi)
                && pendingPath.get() != null), mi -> expectingPutStatic.set())
            .commitAt(Insn.putStatic(typeOwner)
                    .and(fi -> expectingPutStatic.get() && pendingPath.get() != null),
                fi -> out.put(fi.name, pendingPath.get()))
            .run();
        return out;
    }

    // ------------------------------------------------------------------------------------
    // <clinit>-literal fallback chain
    // ------------------------------------------------------------------------------------

    /**
     * The first {@code <clinit>}-bound path whose field name does not contain {@code BABY},
     * falling back to the first entry, {@code null} only on an empty map.
     */
    private static @Nullable String pickFirstNonBabyTexturePath(@NotNull Map<String, String> classFieldToPath) {
        if (classFieldToPath.isEmpty()) return null;
        for (Map.Entry<String, String> entry : classFieldToPath.entrySet())
            if (!entry.getKey().contains("BABY")) return entry.getValue();
        return classFieldToPath.values().iterator().next();
    }

    /**
     * The texture path bound to the variant enum's canonical {@code DEFAULT} constant -
     * pairing each {@code GETSTATIC <Variant>.<NAME>} in the class's populating lambdas /
     * {@code <clinit>} with the next texture LDC, then reading the enum's {@code DEFAULT}
     * initialiser (keeps mooshroom on RED, parrot on RED_BLUE). {@code null} on any miss.
     */
    private @Nullable String findDefaultVariantLiteral(@NotNull String classInternalName) {
        ClassNode cn = this.cache.load(classInternalName);
        if (cn == null) return null;
        Map<String, String> variantToTexture = new LinkedHashMap<>();
        // The variant class is first-wins across every walked body, so it rides a hook-written
        // local rather than a per-walk cell.
        String[] variantClass = {null};
        for (MethodNode m : cn.methods) {
            if (!m.name.startsWith(AsmKit.LAMBDA_STATIC_PREFIX) && !AsmKit.CLINIT.equals(m.name)) continue;
            Cells.Latch<String> pendingVariantName = Cells.latch();
            AsmWalker.over(m)
                .feed(pendingVariantName)
                .on(Insn.of(FieldInsnNode.class, fi -> fi.getOpcode() == Opcodes.GETSTATIC
                    && fi.desc.startsWith("L")
                    && fi.desc.endsWith(";")
                    && fi.owner.endsWith("Variant")), fi -> {
                    pendingVariantName.set(fi.name);
                    if (variantClass[0] == null) variantClass[0] = fi.owner;
                })
                .on(Insn.of(AbstractInsnNode.class, in -> AsmWalker.stringLiteral(in) != null), in -> {
                    String literal = AsmWalker.stringLiteral(in);
                    if (literal == null || !isRealTexturePath(literal) || literal.endsWith("_baby.png")) return;
                    String pending = pendingVariantName.get();
                    if (pending != null) variantToTexture.putIfAbsent(pending, literal);
                })
                .run();
        }
        if (variantClass[0] == null || variantToTexture.isEmpty()) return null;
        String defaultName = AsmKit.findEnumDefaultName(this.cache, variantClass[0], DEFAULT_FIELD);
        if (defaultName == null) return null;
        return variantToTexture.get(defaultName);
    }

    /**
     * Every real {@code textures/entity/...png} literal in the class's {@code <clinit>} and
     * {@code lambda$static$*} bodies, in walk order.
     */
    private @NotNull List<String> collectAllTextureLiterals(@NotNull String classInternalName) {
        List<String> out = new ArrayList<>();
        ClassNode cn = this.cache.load(classInternalName);
        if (cn == null) return out;
        MethodNode clinit = AsmKit.findMethod(cn, AsmKit.CLINIT);
        if (clinit != null) collectTextureLiteralsFromMethod(clinit, out);
        for (MethodNode m : cn.methods)
            if (m.name.startsWith(AsmKit.LAMBDA_STATIC_PREFIX))
                collectTextureLiteralsFromMethod(m, out);
        return out;
    }

    /**
     * Appends the method's real texture-path literals to {@code out}. The reality test is a
     * jar existence probe - format-string templates
     * ({@code "textures/entity/axolotl/axolotl_%s.png"}) are not on-disk files and are
     * filtered out accordingly.
     */
    private void collectTextureLiteralsFromMethod(@NotNull MethodNode method, @NotNull List<String> out) {
        AsmWalker.over(method)
            .mapNotNull(AsmWalker::stringLiteral)
            .where(this::isRealTexturePath)
            .forEach(out::add);
    }

    /**
     * A texture-path literal that exists as a jar entry.
     */
    private boolean isRealTexturePath(@NotNull String literal) {
        return literal.startsWith(VanillaSourceClasses.Paths.TEXTURES_ENTITY)
            && literal.endsWith(".png")
            && entryExists(literal);
    }

    /**
     * Whether the asset-relative path exists in the jar.
     */
    private boolean entryExists(@NotNull String assetPath) {
        return this.cache.hasEntry(VanillaSourceClasses.Paths.ASSETS_ROOT + assetPath);
    }

    /**
     * The first literal whose stem does not end in {@code _baby}, falling back to the first
     * literal, {@code null} only on an empty list.
     */
    private static @Nullable String pickFirstNonBabyLiteral(@NotNull List<String> literals) {
        for (String literal : literals) {
            String base = literal.substring(0, literal.length() - ".png".length());
            if (!base.endsWith("_baby")) return literal;
        }
        return literals.isEmpty() ? null : literals.getFirst();
    }

    // ------------------------------------------------------------------------------------
    // enum-default detection (axolotl / rabbit)
    // ------------------------------------------------------------------------------------

    /**
     * The lowercase {@code DEFAULT} constant name when the renderer's own
     * {@code getTextureLocation} reads an enum-typed {@code state.variant} field AND that
     * enum has a {@code DEFAULT} initialiser ({@code Axolotl$Variant.DEFAULT = LUCY} yields
     * {@code lucy}). {@code null} for data-driven Holder variants and every other shape.
     */
    private @Nullable String resolveEnumDefaultName() {
        ClassNode rendererCn = this.cache.load(this.subject.rendererClass());
        if (rendererCn == null) return null;
        MethodNode getTex = findOwnGetTextureLocation(rendererCn);
        if (getTex == null) return null;

        FieldInsnNode variantRead = AsmWalker.over(getTex)
            .ofType(FieldInsnNode.class)
            .where(fi -> fi.getOpcode() == Opcodes.GETFIELD && VARIANT_FIELD.equals(fi.name))
            .first();
        if (variantRead == null) return null;
        String variantClass = AsmKit.internalNameOfRef(variantRead.desc);
        if (variantClass == null) return null;

        String defaultName = AsmKit.findEnumDefaultName(this.cache, variantClass, DEFAULT_FIELD);
        return defaultName == null ? null : defaultName.toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * The renderer's own (non-bridge) {@code getTextureLocation} - the
     * {@code LivingEntityRenderState} bridge overload is returned only when no typed
     * override exists.
     */
    private static @Nullable MethodNode findOwnGetTextureLocation(@NotNull ClassNode cn) {
        String bridgeDesc = VanillaSourceClasses.Descs.of(VanillaSourceClasses.Descs.IDENTIFIER_REF,
            VanillaSourceClasses.Descs.ref(VanillaSourceClasses.Types.LIVING_ENTITY_RENDER_STATE));
        MethodNode bridge = null;
        for (MethodNode m : cn.methods) {
            if (!VanillaSourceClasses.Methods.GET_TEXTURE_LOCATION.equals(m.name)) continue;
            if (m.desc == null) continue;
            if (bridgeDesc.equals(m.desc)) {
                bridge = m;
                continue;
            }
            if (AsmKit.descriptorReturns(m.desc, VanillaSourceClasses.Types.IDENTIFIER)) return m;
        }
        return bridge;
    }

    // ------------------------------------------------------------------------------------
    // class-bytes fallback + Sheets chain
    // ------------------------------------------------------------------------------------

    /**
     * Final-fallback binder for renderers whose cascade stays unresolved: collects every
     * texture literal from the renderer's own class plus one hop into any external class its
     * {@code getTextureLocation} dispatches into (copper golem's wrapper-type oxidation
     * walk), filters non-base stems against the derived suffix set, and returns the
     * first base stem. Falls through to the Sheets / SpriteMapper indirection (shulker).
     * Returns the stem WITHOUT prefix / suffix, or {@code null}.
     */
    private @Nullable String findBaseTextureFallback() {
        List<String> candidates = new ArrayList<>(collectAllTextureLiterals(this.subject.rendererClass()));
        ClassNode cn = this.cache.load(this.subject.rendererClass());
        if (cn != null) {
            Set<String> visited = new HashSet<>();
            visited.add(this.subject.rendererClass());
            for (MethodNode m : cn.methods) {
                if (!VanillaSourceClasses.Methods.GET_TEXTURE_LOCATION.equals(m.name)) continue;
                AsmWalker.over(m)
                    .ofType(MethodInsnNode.class)
                    .where(mi -> mi.getOpcode() == Opcodes.INVOKESTATIC
                        && !mi.owner.equals(this.subject.rendererClass())
                        && !VanillaSourceClasses.Types.IDENTIFIER.equals(mi.owner))
                    .forEach(mi -> {
                        if (visited.add(mi.owner)) candidates.addAll(collectAllTextureLiterals(mi.owner));
                    });
            }
        }

        Set<String> candidateStems = new HashSet<>();
        for (String path : candidates)
            candidateStems.add(path.substring(VanillaSourceClasses.Paths.TEXTURES_ENTITY.length(), path.length() - ".png".length()));

        for (String path : candidates) {
            String stem = path.substring(VanillaSourceClasses.Paths.TEXTURES_ENTITY.length(), path.length() - ".png".length());
            boolean nonBase = false;
            for (String suffix : this.nonBaseSuffixes)
                if (stem.endsWith(suffix)) {
                    nonBase = true;
                    break;
                }
            // A state-overlay sibling of a base texture present in the SAME candidate set is not
            // the base (ender dragon: skip dragon_exploding / dragon_eyes when dragon is a
            // candidate) - catches the non-recurring suffixes the derived set misses.
            if (!nonBase && hasBaseSibling(stem, candidateStems)) nonBase = true;
            if (!nonBase) return stem;
        }
        return findSheetsTextureFallback();
    }

    /**
     * Whether {@code stem} is a {@code <base>_<suffix>} overlay whose {@code <base>} sibling is
     * itself in the candidate set (same directory) - the local, single-directory analogue of the
     * cross-directory recurrence derivation, for state suffixes that appear only once
     * ({@code _exploding}).
     *
     * @param stem the candidate stem (directory + local name, no prefix / suffix)
     * @param candidateStems every candidate stem in the fallback set
     * @return whether a strict underscore-prefix of {@code stem} is also a candidate
     */
    private static boolean hasBaseSibling(@NotNull String stem, @NotNull Set<String> candidateStems) {
        int slash = stem.lastIndexOf('/');
        String directory = slash >= 0 ? stem.substring(0, slash + 1) : "";
        String local = slash >= 0 ? stem.substring(slash + 1) : stem;
        for (int underscore = local.indexOf('_'); underscore > 0; underscore = local.indexOf('_', underscore + 1))
            if (candidateStems.contains(directory + local.substring(0, underscore))) return true;
        return false;
    }

    /**
     * Resolves a base texture by following the {@code Sheets.<SPRITE_ID>.texture()} chain
     * when no inline LDC is reachable (sole 26.1 hit: shulker - prefix
     * {@code entity/shulker}, name {@code shulker}): the renderer's {@code <clinit>} names
     * the sheets class + sprite-id field; the sheets {@code <clinit>} pairs the field with a
     * {@code SpriteMapper.defaultNamespaceApply(LDC)} and the mapper with its
     * {@code new SpriteMapper(sheet, LDC prefix)}. Returns {@code null} on any mismatch.
     */
    private @Nullable String findSheetsTextureFallback() {
        MethodNode rendererClinit = AsmKit.findClinit(this.cache, this.subject.rendererClass());
        if (rendererClinit == null) return null;

        String spriteIdDesc = VanillaSourceClasses.Descs.ref(VanillaSourceClasses.Types.SPRITE_ID);
        String spriteMapperDesc = VanillaSourceClasses.Descs.ref(VanillaSourceClasses.Types.SPRITE_MAPPER);

        FieldInsnNode spriteIdGet = AsmWalker.over(rendererClinit)
            .ofType(FieldInsnNode.class)
            .where(fi -> fi.getOpcode() == Opcodes.GETSTATIC
                && spriteIdDesc.equals(fi.desc)
                && AsmKit.nextReal(fi) instanceof MethodInsnNode mi
                && mi.getOpcode() == Opcodes.INVOKEVIRTUAL
                && VanillaSourceClasses.Types.SPRITE_ID.equals(mi.owner)
                && VanillaSourceClasses.Methods.TEXTURE.equals(mi.name))
            .first();
        if (spriteIdGet == null) return null;
        String sheetsOwner = spriteIdGet.owner;
        String spriteIdField = spriteIdGet.name;

        MethodNode sheetsClinit = AsmKit.findClinit(this.cache, sheetsOwner);
        if (sheetsClinit == null) return null;

        FieldInsnNode spriteIdPut = AsmWalker.over(sheetsClinit).putStatic(sheetsOwner, spriteIdField).first();
        if (spriteIdPut == null) return null;
        AbstractInsnNode invoke = AsmKit.previousReal(spriteIdPut);
        if (!(invoke instanceof MethodInsnNode mi
            && mi.getOpcode() == Opcodes.INVOKEVIRTUAL
            && VanillaSourceClasses.Types.SPRITE_MAPPER.equals(mi.owner)
            && VanillaSourceClasses.Methods.DEFAULT_NAMESPACE_APPLY.equals(mi.name))) return null;
        AbstractInsnNode nameLdc = AsmKit.previousReal(invoke);
        String name = AsmKit.readStringLiteral(nameLdc);
        if (name == null) return null;
        if (!(AsmKit.previousReal(nameLdc) instanceof FieldInsnNode mapperGet
            && mapperGet.getOpcode() == Opcodes.GETSTATIC
            && sheetsOwner.equals(mapperGet.owner)
            && spriteMapperDesc.equals(mapperGet.desc))) return null;
        String mapperField = mapperGet.name;

        FieldInsnNode mapperPut = AsmWalker.over(sheetsClinit).putStatic(sheetsOwner, mapperField).first();
        if (mapperPut == null) return null;
        AbstractInsnNode init = AsmKit.previousReal(mapperPut);
        if (!(init instanceof MethodInsnNode ctor
            && ctor.getOpcode() == Opcodes.INVOKESPECIAL
            && VanillaSourceClasses.Types.SPRITE_MAPPER.equals(ctor.owner)
            && AsmKit.INIT.equals(ctor.name))) return null;
        String prefix = AsmKit.readStringLiteral(AsmKit.previousReal(init));
        if (prefix == null) return null;

        String stem = prefix + "/" + name;
        return stem.startsWith("entity/") ? stem.substring("entity/".length()) : stem;
    }

    // ------------------------------------------------------------------------------------
    // derived non-base suffix set
    // ------------------------------------------------------------------------------------

    /**
     * Derives the non-base-suffix set from the live entity-texture universe, once per
     * session: a suffix {@code _X} qualifies when a {@code <prefix>.png} and
     * {@code <prefix>_X.png} sibling pair co-exists in at least
     * {@link #SUFFIX_MIN_RECURRENCE} texture directories - state overlays ({@code _eyes},
     * {@code _exposed}) recur; data-variant names ({@code _lucy}) fall out. The INFO line
     * is the version-bump drift surface.
     *
     * @param session the live session
     * @return the derived suffix set
     */
    static @NotNull Set<String> deriveNonBaseSuffixes(@NotNull ToolingSession session) {
        String prefix = VanillaSourceClasses.Paths.ASSETS_ROOT + VanillaSourceClasses.Paths.TEXTURES_ENTITY;
        Set<String> stems = new LinkedHashSet<>();
        for (String entryPath : session.cache().list(prefix, ".png"))
            stems.add(entryPath.substring(prefix.length(), entryPath.length() - ".png".length()));

        Map<String, Integer> suffixCount = new HashMap<>();
        for (String stem : stems) {
            int slash = stem.lastIndexOf('/');
            String dir = slash >= 0 ? stem.substring(0, slash + 1) : "";
            String local = slash >= 0 ? stem.substring(slash + 1) : stem;
            int underscore = local.indexOf('_');
            while (underscore > 0) {
                String prefixLocal = local.substring(0, underscore);
                String suffix = local.substring(underscore);
                if (stems.contains(dir + prefixLocal)) suffixCount.merge(suffix, 1, Integer::sum);
                underscore = local.indexOf('_', underscore + 1);
            }
        }
        Set<String> out = new LinkedHashSet<>();
        for (Map.Entry<String, Integer> entry : suffixCount.entrySet())
            if (entry.getValue() >= SUFFIX_MIN_RECURRENCE) out.add(entry.getKey());
        session.diagnostics().child("textures").info("derived %d non-base texture suffixes: %s", out.size(), out);
        return out;
    }

}
