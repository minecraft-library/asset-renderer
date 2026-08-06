package lib.minecraft.renderer.tooling.entity;

import lib.minecraft.renderer.tooling.kernel.AsmKit;
import lib.minecraft.renderer.tooling.kernel.ClassNodeCache;
import lib.minecraft.renderer.tooling.kernel.VanillaSourceClasses;
import lib.minecraft.renderer.tooling.walk.AsmWalker;
import lib.minecraft.renderer.tooling.walk.Cells;
import lib.minecraft.renderer.tooling.walk.CommitWalk;
import lib.minecraft.renderer.tooling.walk.Insn;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The session-memoized render-pipeline trait classifier - factory name to
 * {@code RenderTypes} body to {@code RenderPipelines.<clinit>} build block to the
 * {@link Trait} set. Caching is scoped to the session, so a stale cache can't outlive it.
 *
 * <p>Two factory shapes resolve the pipeline reference: a direct
 * {@code GETSTATIC RenderPipelines.X} in the factory body, and a {@code Function} /
 * {@code BiFunction}-field factory whose field is bound in {@code <clinit>} by an
 * {@code invokedynamic} lambda that references the pipeline. An unresolvable factory
 * classifies as the empty trait set (cardinal-lit, source-over).
 *
 * <p>Build-block boundaries in {@code RenderPipelines.<clinit>} are marked by any
 * {@code PUTSTATIC}: traits accumulate until the boundary and reset after it, so one
 * pipeline's defines never leak into the next.
 *
 * <p>A layer may reach its render type only through a static helper it calls rather than through a
 * factory in its own body, so the layer probe follows the statics a layer <em>actually invokes</em>
 * up to {@link #HELPER_HOPS} deep. Following what a layer calls is what separates that from
 * accepting every static in its hierarchy - a base class's helpers are inherited by every subclass,
 * and crediting a layer with one it never calls would classify the whole corpus alike.
 */
final class EntityPipelineTraits {

    /**
     * How many static calls deep the layer probe follows a helper before giving up. Vanilla's cutout
     * helper delegates once before reaching a factory, so one hop finds nothing and two is the floor
     * rather than a margin. The walk resolves each call the way the JVM would and stops at any
     * target the extracted jar does not hold, which is what keeps it inside vanilla's own code.
     */
    private static final int HELPER_HOPS = 2;

    /**
     * The {@code blend} token of a pipeline whose fragments are added to the destination.
     */
    static final @NotNull String BLEND_ADDITIVE = "additive";

    /**
     * The {@code blend} token of a pipeline that blends its fragments against the destination.
     */
    static final @NotNull String BLEND_TRANSLUCENT = "translucent";

    /**
     * The {@code blend} token of a pipeline that declares no blend function at all - it writes each
     * surviving fragment over the destination verbatim, alpha included, rather than compositing it.
     */
    static final @NotNull String BLEND_CUTOUT = "cutout";

    /**
     * Field descriptor of a {@code java.util.function.Function}-backed factory field (JDK name, kit-local).
     */
    private static final @NotNull String FUNCTION_DESC = "Ljava/util/function/Function;";

    /**
     * Field descriptor of a {@code java.util.function.BiFunction}-backed factory field (JDK name, kit-local).
     */
    private static final @NotNull String BIFUNCTION_DESC = "Ljava/util/function/BiFunction;";

    /**
     * One walked pipeline trait - the two shader defines plus the two blend constants.
     */
    enum Trait {
        /**
         * {@code withShaderDefine("EMISSIVE")}.
         */
        EMISSIVE,
        /**
         * {@code withShaderDefine("NO_CARDINAL_LIGHTING")} - the renderer-semantic full-bright bit.
         */
        NO_CARDINAL_LIGHTING,
        /**
         * {@code BlendFunction.TRANSLUCENT} pushed in the build block.
         */
        TRANSLUCENT,
        /**
         * {@code BlendFunction.ADDITIVE} pushed in the build block (energy swirl).
         */
        ADDITIVE
    }

    private final @NotNull ClassNodeCache cache;

    /**
     * ClientAcquisition field name to build-block trait set - the ONE {@code RenderPipelines.<clinit>} walk, lazy.
     */
    private @Nullable Map<String, Set<Trait>> pipelineTraits;

    /**
     * Pipeline (and snippet) field name to the {@code writeDepth} its {@code DepthStencilState}
     * declares, filled by the same {@code <clinit>} walk that fills {@link #pipelineTraits}. Absent
     * where no state was declared and none could be inherited from a snippet.
     */
    private final @NotNull Map<String, Boolean> pipelineDepthWrite = new LinkedHashMap<>();

    /**
     * Factory name to resolved trait set - the per-factory memo.
     */
    private final @NotNull Map<String, Set<Trait>> factoryTraits = new LinkedHashMap<>();

    /**
     * Factory name to whether its {@code RenderSetup} declares {@code sortOnUpload} - the per-factory memo.
     */
    private final @NotNull Map<String, Boolean> factorySorts = new LinkedHashMap<>();

    EntityPipelineTraits(@NotNull ClassNodeCache cache) {
        this.cache = cache;
    }

    /**
     * The trait set of a {@code RenderTypes} factory, empty when the factory or its pipeline
     * reference cannot be resolved (treated as cardinal-lit source-over).
     *
     * @param factoryName the {@code RenderTypes} factory method name
     * @return the walked trait set (unmodifiable)
     */
    @NotNull Set<Trait> traitsOf(@NotNull String factoryName) {
        return this.factoryTraits.computeIfAbsent(factoryName, name -> {
            String pipelineField = resolveFactoryPipeline(name);
            if (pipelineField == null) return Set.of();
            Set<Trait> traits = pipelines().get(pipelineField);
            return traits == null ? Set.of() : traits;
        });
    }

    /**
     * Whether any {@code RenderTypes} factory reached from the layer hierarchy's INSTANCE
     * methods carries the trait - the layer-body probe the overlay engine's emissive / blend
     * classification runs. The superclass walk picks up {@code RenderTypes.energySwirl} where
     * it lives, in {@code EnergySwirlLayer.submit}.
     *
     * @param layerClass the layer class's JVM internal name
     * @param trait the probed trait
     * @return whether any invoked factory's pipeline carries it
     */
    boolean layerInvokes(@NotNull String layerClass, @NotNull Trait trait) {
        boolean[] found = {false};
        AsmKit.walkSuperChain(this.cache, layerClass, cn -> {
            if (found[0]) return;
            for (String factory : instanceFactoryCalls(cn))
                if (traitsOf(factory).contains(trait)) {
                    found[0] = true;
                    return;
                }
        });
        return found[0];
    }

    /**
     * The composite {@code blend} classification of a layer hierarchy - the strongest token any
     * reached factory yields, by {@link #blendRank}. Instance methods only, per
     * {@link #layerInvokes}.
     *
     * @param layerClass the layer class's JVM internal name
     * @return the blend token, or {@code null} for the source-over default
     */
    @Nullable String classifyBlend(@NotNull String layerClass) {
        String[] blend = {null};
        AsmKit.walkSuperChain(this.cache, layerClass, cn -> {
            for (String factory : instanceFactoryCalls(cn)) {
                String token = blendTokenOf(factory);
                if (blendRank(token) > blendRank(blend[0])) blend[0] = token;
            }
        });
        return blend[0];
    }

    /**
     * The {@code blend} token of one {@code RenderTypes} factory, resolved through its pipeline:
     * {@link #BLEND_ADDITIVE} when the build block pushed {@code BlendFunction.ADDITIVE},
     * {@link #BLEND_TRANSLUCENT} when it pushed {@code BlendFunction.TRANSLUCENT} and the pipeline
     * is not full-bright (the eyes pipelines are translucent full-bright and stay unannotated),
     * {@link #BLEND_CUTOUT} when it pushed neither, and {@code null} when the factory or its
     * pipeline could not be resolved at all.
     *
     * <p>That last distinction is the whole point of resolving here rather than from a trait set: an
     * unresolved factory and a resolved one that declares no blend function both walk to an empty
     * set, yet the first is an unknown that must default to source-over and the second is a positive
     * finding that the destination is not read.
     *
     * @param factoryName the {@code RenderTypes} factory method name
     * @return the blend token, or {@code null} when unresolved
     */
    @Nullable String blendTokenOf(@NotNull String factoryName) {
        String pipelineField = resolveFactoryPipeline(factoryName);
        if (pipelineField == null) return null;
        Set<Trait> traits = pipelines().get(pipelineField);
        if (traits == null) return null;
        if (traits.contains(Trait.ADDITIVE)) return BLEND_ADDITIVE;
        if (traits.contains(Trait.TRANSLUCENT))
            return traits.contains(Trait.NO_CARDINAL_LIGHTING) ? null : BLEND_TRANSLUCENT;
        return BLEND_CUTOUT;
    }

    /**
     * Whether one {@code RenderTypes} factory's pipeline writes depth - vanilla's
     * {@code DepthStencilState.writeDepth}, which is a pipeline declaration in its own right rather
     * than a consequence of the {@code EMISSIVE} define. {@code DepthStencilState.DEFAULT} is
     * {@code (LESS_THAN_OR_EQUAL, true)}, and a pipeline declaring no state of its own inherits the
     * one on the snippet it was built from, so an unresolved factory answers {@code true} - the
     * value all but a handful of pipelines carry.
     *
     * @param factoryName the {@code RenderTypes} factory method name
     * @return whether fragments of this pass write the depth buffer
     */
    boolean factoryWritesDepth(@NotNull String factoryName) {
        String pipelineField = resolveFactoryPipeline(factoryName);
        if (pipelineField == null) return true;
        pipelines();
        return this.pipelineDepthWrite.getOrDefault(pipelineField, Boolean.TRUE);
    }

    /**
     * Whether one {@code RenderTypes} factory's {@code RenderSetup} declares {@code sortOnUpload} -
     * the flag that has vanilla sort the pass's quads back-to-front by centroid before it
     * rasterizes them. Read off the factory body, or off the lambda a memoized
     * {@code Function}-backed factory is bound to, since that is where the builder chain lives.
     *
     * @param factoryName the {@code RenderTypes} factory method name
     * @return whether this pass's quads are drawn back-to-front
     */
    boolean factorySortsQuads(@NotNull String factoryName) {
        return this.factorySorts.computeIfAbsent(factoryName, name -> {
            ClassNode renderTypes = this.cache.load(VanillaSourceClasses.Types.RENDER_TYPES);
            if (renderTypes == null) return false;
            for (MethodNode method : renderTypes.methods) {
                if (!name.equals(method.name)) continue;
                if (declaresSortOnUpload(method)) return true;
                boolean viaLambda = AsmWalker.over(method)
                    .getStatic(renderTypes.name)
                    .where(fi -> FUNCTION_DESC.equals(fi.desc) || BIFUNCTION_DESC.equals(fi.desc))
                    .any(fi -> {
                        MethodNode lambda = chaseFunctionFieldLambda(renderTypes, fi.name);
                        return lambda != null && declaresSortOnUpload(lambda);
                    });
                if (viaLambda) return true;
            }
            return false;
        });
    }

    /**
     * Whether every {@code RenderTypes} factory the layer hierarchy reaches writes depth. Instance
     * methods only, per {@link #layerInvokes} - a layer reaching none answers {@code true}, the
     * default every ordinary pass carries.
     *
     * @param layerClass the layer class's JVM internal name
     * @return whether the layer's pass writes depth
     */
    boolean layerWritesDepth(@NotNull String layerClass) {
        boolean[] writes = {true};
        AsmKit.walkSuperChain(this.cache, layerClass, cn -> {
            for (String factory : instanceFactoryCalls(cn))
                if (!factoryWritesDepth(factory)) writes[0] = false;
        });
        return writes[0];
    }

    /**
     * Whether any {@code RenderTypes} factory the layer hierarchy reaches sorts its quads. Instance
     * methods only, per {@link #layerInvokes}.
     *
     * @param layerClass the layer class's JVM internal name
     * @return whether the layer's pass is drawn back-to-front
     */
    boolean layerSortsQuads(@NotNull String layerClass) {
        boolean[] sorts = {false};
        AsmKit.walkSuperChain(this.cache, layerClass, cn -> {
            if (sorts[0]) return;
            for (String factory : instanceFactoryCalls(cn))
                if (factorySortsQuads(factory)) {
                    sorts[0] = true;
                    return;
                }
        });
        return sorts[0];
    }

    /**
     * Whether a method body invokes {@code RenderSetup$RenderSetupBuilder.sortOnUpload}.
     */
    private static boolean declaresSortOnUpload(@NotNull MethodNode method) {
        return AsmWalker.over(method)
            .invokeVirtual(VanillaSourceClasses.Types.RENDER_SETUP_BUILDER, VanillaSourceClasses.Defines.SORT_ON_UPLOAD)
            .any();
    }

    /**
     * How strongly a token claims a layer, so a hierarchy reaching several factories resolves to one
     * without depending on walk order - additive over translucent over cutout over the default.
     *
     * @param token the blend token, or {@code null}
     * @return the rank, {@code 0} for the default
     */
    private static int blendRank(@Nullable String token) {
        if (BLEND_ADDITIVE.equals(token)) return 3;
        if (BLEND_TRANSLUCENT.equals(token)) return 2;
        if (BLEND_CUTOUT.equals(token)) return 1;
        return 0;
    }

    /**
     * The {@code RenderTypes} factory names the class's instance non-init methods reach, directly or
     * through a static helper they invoke.
     */
    private @NotNull List<String> instanceFactoryCalls(@NotNull ClassNode cn) {
        List<String> out = new ArrayList<>();
        for (MethodNode method : cn.methods) {
            if ((method.access & Opcodes.ACC_STATIC) != 0) continue;
            if (AsmKit.INIT.equals(method.name)) continue;
            collectFactoryCalls(method, out, new HashSet<>(), HELPER_HOPS);
        }
        return out;
    }

    /**
     * Collects the {@code RenderTypes} factories one method body reaches, descending into each
     * static call it makes while hops remain. Resolution walks the super chain the way
     * {@code invokestatic} does, since javac names the calling class rather than the declaring one
     * when a subclass invokes an inherited static; a target the jar does not hold ends that branch,
     * which is what confines the descent to vanilla's own code.
     */
    private void collectFactoryCalls(@NotNull MethodNode method, @NotNull List<String> out,
                                     @NotNull Set<String> visited, int hops) {
        AsmWalker.over(method)
            .opcode(Opcodes.INVOKESTATIC)
            .ofType(MethodInsnNode.class)
            .forEach(mi -> {
                if (VanillaSourceClasses.Types.RENDER_TYPES.equals(mi.owner)) {
                    out.add(mi.name);
                    return;
                }
                if (hops <= 0 || !visited.add(mi.owner + '.' + mi.name + mi.desc)) return;
                MethodNode helper = AsmKit.findMethodInHierarchy(this.cache, mi.owner, mi.name, mi.desc);
                if (helper != null) collectFactoryCalls(helper, out, visited, hops - 1);
            });
    }

    // ------------------------------------------------------------------------------------
    // factory -> pipeline resolution
    // ------------------------------------------------------------------------------------

    private @Nullable String resolveFactoryPipeline(@NotNull String factoryName) {
        ClassNode renderTypes = this.cache.load(VanillaSourceClasses.Types.RENDER_TYPES);
        if (renderTypes == null) return null;
        for (MethodNode method : renderTypes.methods) {
            if (!factoryName.equals(method.name)) continue;
            String pipeline = AsmWalker.over(method)
                .opcode(Opcodes.GETSTATIC)
                .ofType(FieldInsnNode.class)
                .firstNotNull(fi -> {
                    if (VanillaSourceClasses.Types.RENDER_PIPELINES.equals(fi.owner)) return fi.name;
                    // Function/BiFunction-backed factory (entityTranslucent, outline): the field is
                    // bound in <clinit> by an indy lambda whose body references the pipeline.
                    if (renderTypes.name.equals(fi.owner)
                        && (FUNCTION_DESC.equals(fi.desc) || BIFUNCTION_DESC.equals(fi.desc)))
                        return chaseFunctionFieldPipeline(renderTypes, fi.name);
                    return null;
                });
            if (pipeline != null) return pipeline;
        }
        return null;
    }

    /**
     * The {@code RenderPipelines.X} field a lambda-backed factory field builds against - the first
     * pipeline reference in the lambda the field is bound to.
     */
    private static @Nullable String chaseFunctionFieldPipeline(@NotNull ClassNode renderTypes, @NotNull String fieldName) {
        MethodNode lambda = chaseFunctionFieldLambda(renderTypes, fieldName);
        if (lambda == null) return null;
        return AsmWalker.over(lambda)
            .getStatic(VanillaSourceClasses.Types.RENDER_PIPELINES)
            .names()
            .first();
    }

    /**
     * The lambda body a memoized {@code Function} / {@code BiFunction} factory field is bound to:
     * pairs the {@code <clinit>} {@code invokedynamic}; {@code PUTSTATIC <fieldName>} chain and
     * resolves the captured handle back to its synthetic method.
     */
    private static @Nullable MethodNode chaseFunctionFieldLambda(@NotNull ClassNode renderTypes, @NotNull String fieldName) {
        MethodNode clinit = AsmKit.findMethod(renderTypes, AsmKit.CLINIT);
        if (clinit == null) return null;
        Handle bound = AsmWalker.over(clinit)
            .latch(in -> AsmWalker.isLambdaInvokeDynamic(in) && in instanceof InvokeDynamicInsnNode indy
                ? AsmWalker.extractLambdaHandle(indy) : null)
            .commitAt(Insn.putStatic(renderTypes.name, fieldName))
            .firstNotNull(CommitWalk.Commit::value);
        return bound == null ? null : AsmKit.findMethod(renderTypes, bound.getName(), bound.getDesc());
    }

    // ------------------------------------------------------------------------------------
    // the ONE RenderPipelines.<clinit> walk
    // ------------------------------------------------------------------------------------

    private @NotNull Map<String, Set<Trait>> pipelines() {
        if (this.pipelineTraits != null) return this.pipelineTraits;
        Map<String, Set<Trait>> out = new LinkedHashMap<>();
        MethodNode clinit = AsmKit.findClinit(this.cache, VanillaSourceClasses.Types.RENDER_PIPELINES);
        if (clinit == null) {
            this.pipelineTraits = out;
            return out;
        }

        Cells.ListCell<Trait> block = Cells.list();
        Cells.Window<String> pendingDefine = Cells.window(AsmWalker::stringLiteral, 1);
        Cells.Latch<Boolean> blockDepthWrite = Cells.latch();
        Cells.Window<Boolean> pendingBoolean = Cells.window(AsmWalker::booleanLiteral, 1);
        Cells.ListCell<String> blockSnippets = Cells.list();
        // The depth-stencil state, in the two shapes vanilla writes it: the shared DEFAULT
        // constant (LESS_THAN_OR_EQUAL, writeDepth true) and an explicit
        // `new DepthStencilState(CompareOp, writeDepth)` whose flag is the boolean standing on
        // the stack when the constructor is reached. A block builds on zero or more snippets
        // and inherits whatever state it does not declare itself, so a pipeline naming no
        // DepthStencilState (breeze wind) still has one.
        AsmWalker.over(clinit)
            .feed(block)
            .feed(blockDepthWrite)
            .feed(blockSnippets)
            .feed(pendingDefine)
            .feed(pendingBoolean)
            .on(Insn.getStatic(VanillaSourceClasses.Types.DEPTH_STENCIL_STATE, VanillaSourceClasses.Defines.DEPTH_STENCIL_DEFAULT),
                dsf -> blockDepthWrite.set(Boolean.TRUE))
            .on(Insn.invokeSpecial(VanillaSourceClasses.Types.DEPTH_STENCIL_STATE, AsmKit.INIT), mi -> {
                if (pendingBoolean.size() == 0) return;
                blockDepthWrite.set(pendingBoolean.values().getFirst());
                pendingBoolean.clear();
            })
            .on(Insn.getStatic(VanillaSourceClasses.Types.RENDER_PIPELINES)
                    .and(sf -> VanillaSourceClasses.Descs.ref(VanillaSourceClasses.Types.RENDER_PIPELINE_SNIPPET).equals(sf.desc)),
                sf -> blockSnippets.add(sf.name))
            .on(Insn.of(MethodInsnNode.class, mi -> mi.getOpcode() == Opcodes.INVOKEVIRTUAL
                && VanillaSourceClasses.Defines.WITH_SHADER_DEFINE.equals(mi.name)), mi -> {
                String define = pendingDefine.size() == 0 ? null : pendingDefine.values().getFirst();
                if (VanillaSourceClasses.Defines.NO_CARDINAL_LIGHTING.equals(define)) block.add(Trait.NO_CARDINAL_LIGHTING);
                else if (VanillaSourceClasses.Defines.EMISSIVE.equals(define)) block.add(Trait.EMISSIVE);
                pendingDefine.clear();
            })
            .on(Insn.getStatic(VanillaSourceClasses.Types.BLEND_FUNCTION), fi -> {
                if (VanillaSourceClasses.Defines.TRANSLUCENT.equals(fi.name)) block.add(Trait.TRANSLUCENT);
                else if (VanillaSourceClasses.Defines.ADDITIVE.equals(fi.name)) block.add(Trait.ADDITIVE);
            })
            .commitAt(Insn.of(FieldInsnNode.class, fi -> fi.getOpcode() == Opcodes.PUTSTATIC), fi -> {
                List<Trait> traits = block.values();
                out.put(fi.name, traits.isEmpty() ? Set.of() : Collections.unmodifiableSet(EnumSet.copyOf(traits)));
                Boolean depthWrite = blockDepthWrite.get();
                if (depthWrite == null)
                    for (String snippet : blockSnippets.values()) {
                        Boolean inherited = this.pipelineDepthWrite.get(snippet);
                        if (inherited != null) {
                            depthWrite = inherited;
                            break;
                        }
                    }
                if (depthWrite != null) this.pipelineDepthWrite.put(fi.name, depthWrite);
            })
            .run();
        this.pipelineTraits = out;
        return out;
    }

}
