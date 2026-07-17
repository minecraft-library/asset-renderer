package lib.minecraft.renderer.tooling.entity;

import lib.minecraft.renderer.tooling.kernel.AsmKit;
import lib.minecraft.renderer.tooling.kernel.ClassNodeCache;
import lib.minecraft.renderer.tooling.kernel.VanillaSourceClasses;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
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
 */
final class EntityPipelineTraits {

    /** Field descriptor of a {@code java.util.function.Function}-backed factory field (JDK name, kit-local). */
    private static final @NotNull String FUNCTION_DESC = "Ljava/util/function/Function;";

    /** Field descriptor of a {@code java.util.function.BiFunction}-backed factory field (JDK name, kit-local). */
    private static final @NotNull String BIFUNCTION_DESC = "Ljava/util/function/BiFunction;";

    /** One walked pipeline trait - the two shader defines plus the two blend constants. */
    enum Trait {
        /** {@code withShaderDefine("EMISSIVE")}. */
        EMISSIVE,
        /** {@code withShaderDefine("NO_CARDINAL_LIGHTING")} - the renderer-semantic full-bright bit. */
        NO_CARDINAL_LIGHTING,
        /** {@code BlendFunction.TRANSLUCENT} pushed in the build block. */
        TRANSLUCENT,
        /** {@code BlendFunction.ADDITIVE} pushed in the build block (energy swirl). */
        ADDITIVE
    }

    private final @NotNull ClassNodeCache cache;

    /** ClientAcquisition field name to build-block trait set - the ONE {@code RenderPipelines.<clinit>} walk, lazy. */
    private @Nullable Map<String, Set<Trait>> pipelineTraits;

    /** Factory name to resolved trait set - the per-factory memo. */
    private final @NotNull Map<String, Set<Trait>> factoryTraits = new LinkedHashMap<>();

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
     * Whether any {@code RenderTypes} factory invoked from the layer hierarchy's INSTANCE
     * methods carries the trait - the layer-body probe the overlay engine's emissive / blend
     * classification runs. The superclass walk picks up {@code RenderTypes.energySwirl} where
     * it lives, in {@code EnergySwirlLayer.submit}; static methods are excluded because the
     * {@code RenderLayer} base's static cutout helpers invoke {@code RenderTypes.entityCutout}
     * themselves and would accept every layer.
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
     * The composite {@code blend} classification of a layer hierarchy: {@code "additive"}
     * when any invoked factory's pipeline blends additively, else {@code "translucent"}
     * when one blends translucent WITHOUT {@code NO_CARDINAL_LIGHTING} (the eyes pipelines
     * are translucent full-bright and stay unannotated), else {@code null}
     * (source-over normal). Instance methods only, per {@link #layerInvokes}.
     *
     * @param layerClass the layer class's JVM internal name
     * @return the blend token, or {@code null} for the default
     */
    @Nullable String classifyBlend(@NotNull String layerClass) {
        String[] blend = {null};
        AsmKit.walkSuperChain(this.cache, layerClass, cn -> {
            for (String factory : instanceFactoryCalls(cn)) {
                String token = blendToken(traitsOf(factory));
                if ("additive".equals(token)) {
                    blend[0] = token;
                    return;
                }
                if (token != null && blend[0] == null) blend[0] = token;
            }
        });
        return blend[0];
    }

    /** The {@code RenderTypes} factory names invoked from the class's instance non-init methods. */
    private static @NotNull List<String> instanceFactoryCalls(@NotNull ClassNode cn) {
        List<String> out = new ArrayList<>();
        for (MethodNode method : cn.methods) {
            if ((method.access & Opcodes.ACC_STATIC) != 0) continue;
            if (AsmKit.INIT.equals(method.name)) continue;
            for (AbstractInsnNode in = method.instructions.getFirst(); in != null; in = in.getNext()) {
                if (in.getOpcode() != Opcodes.INVOKESTATIC || !(in instanceof MethodInsnNode mi)) continue;
                if (VanillaSourceClasses.Types.RENDER_TYPES.equals(mi.owner)) out.add(mi.name);
            }
        }
        return out;
    }

    /**
     * The {@code blend} token of a trait set - {@code "additive"}, {@code "translucent"}
     * (only when not full-bright), or {@code null} for source-over.
     *
     * @param traits the walked trait set
     * @return the token, or {@code null}
     */
    static @Nullable String blendToken(@NotNull Set<Trait> traits) {
        if (traits.contains(Trait.ADDITIVE)) return "additive";
        if (traits.contains(Trait.TRANSLUCENT) && !traits.contains(Trait.NO_CARDINAL_LIGHTING)) return "translucent";
        return null;
    }

    // ------------------------------------------------------------------------------------
    // factory -> pipeline resolution
    // ------------------------------------------------------------------------------------

    private @Nullable String resolveFactoryPipeline(@NotNull String factoryName) {
        ClassNode renderTypes = this.cache.load(VanillaSourceClasses.Types.RENDER_TYPES);
        if (renderTypes == null) return null;
        for (MethodNode method : renderTypes.methods) {
            if (!factoryName.equals(method.name)) continue;
            for (AbstractInsnNode in = method.instructions.getFirst(); in != null; in = in.getNext()) {
                if (in.getOpcode() != Opcodes.GETSTATIC || !(in instanceof FieldInsnNode fi)) continue;
                if (VanillaSourceClasses.Types.RENDER_PIPELINES.equals(fi.owner)) return fi.name;
                // Function/BiFunction-backed factory (entityTranslucent, outline): the field is
                // bound in <clinit> by an indy lambda whose body references the pipeline.
                if (renderTypes.name.equals(fi.owner)
                    && (FUNCTION_DESC.equals(fi.desc) || BIFUNCTION_DESC.equals(fi.desc))) {
                    String pipeline = chaseFunctionFieldPipeline(renderTypes, fi.name);
                    if (pipeline != null) return pipeline;
                }
            }
        }
        return null;
    }

    /**
     * The {@code RenderPipelines.X} field a lambda-backed factory field builds against:
     * pairs the {@code <clinit>} {@code invokedynamic}; {@code PUTSTATIC <fieldName>} chain
     * and reads the bound lambda's first pipeline reference.
     */
    private static @Nullable String chaseFunctionFieldPipeline(@NotNull ClassNode renderTypes, @NotNull String fieldName) {
        MethodNode clinit = AsmKit.findMethod(renderTypes, AsmKit.CLINIT);
        if (clinit == null) return null;
        Handle pendingLambda = null;
        for (AbstractInsnNode in = clinit.instructions.getFirst(); in != null; in = in.getNext()) {
            if (AsmKit.isLambdaInvokeDynamic(in) && in instanceof InvokeDynamicInsnNode indy) {
                Handle handle = AsmKit.extractLambdaHandle(indy);
                if (handle != null) pendingLambda = handle;
                continue;
            }
            if (AsmKit.isPutStatic(in, renderTypes.name, fieldName) && pendingLambda != null) {
                MethodNode lambda = AsmKit.findMethod(renderTypes, pendingLambda.getName(), pendingLambda.getDesc());
                if (lambda == null) return null;
                for (AbstractInsnNode li = lambda.instructions.getFirst(); li != null; li = li.getNext())
                    if (AsmKit.isGetStatic(li, VanillaSourceClasses.Types.RENDER_PIPELINES))
                        return ((FieldInsnNode) li).name;
                return null;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------------------------
    // the ONE RenderPipelines.<clinit> walk
    // ------------------------------------------------------------------------------------

    private @NotNull Map<String, Set<Trait>> pipelines() {
        if (this.pipelineTraits != null) return this.pipelineTraits;
        Map<String, Set<Trait>> out = new LinkedHashMap<>();
        ClassNode cn = this.cache.load(VanillaSourceClasses.Types.RENDER_PIPELINES);
        MethodNode clinit = cn == null ? null : AsmKit.findMethod(cn, AsmKit.CLINIT);
        if (clinit == null) {
            this.pipelineTraits = out;
            return out;
        }

        EnumSet<Trait> block = EnumSet.noneOf(Trait.class);
        String pendingDefine = null;
        for (AbstractInsnNode in = clinit.instructions.getFirst(); in != null; in = in.getNext()) {
            String literal = AsmKit.readStringLiteral(in);
            if (literal != null) {
                pendingDefine = literal;
                continue;
            }
            if (in.getOpcode() == Opcodes.INVOKEVIRTUAL
                && in instanceof MethodInsnNode mi
                && VanillaSourceClasses.Defines.WITH_SHADER_DEFINE.equals(mi.name)) {
                if (VanillaSourceClasses.Defines.NO_CARDINAL_LIGHTING.equals(pendingDefine)) block.add(Trait.NO_CARDINAL_LIGHTING);
                else if (VanillaSourceClasses.Defines.EMISSIVE.equals(pendingDefine)) block.add(Trait.EMISSIVE);
                pendingDefine = null;
                continue;
            }
            if (in.getOpcode() == Opcodes.GETSTATIC
                && in instanceof FieldInsnNode fi
                && VanillaSourceClasses.Types.BLEND_FUNCTION.equals(fi.owner)) {
                if (VanillaSourceClasses.Defines.TRANSLUCENT.equals(fi.name)) block.add(Trait.TRANSLUCENT);
                else if (VanillaSourceClasses.Defines.ADDITIVE.equals(fi.name)) block.add(Trait.ADDITIVE);
                continue;
            }
            if (in.getOpcode() == Opcodes.PUTSTATIC && in instanceof FieldInsnNode fi) {
                out.put(fi.name, block.isEmpty() ? Set.of() : Collections.unmodifiableSet(EnumSet.copyOf(block)));
                block = EnumSet.noneOf(Trait.class);
                pendingDefine = null;
            }
        }
        this.pipelineTraits = out;
        return out;
    }

}
