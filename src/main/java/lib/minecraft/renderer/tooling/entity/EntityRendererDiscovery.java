package lib.minecraft.renderer.tooling.entity;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import lib.minecraft.renderer.tooling.util.AsmKit;
import lib.minecraft.renderer.tooling.util.Diagnostics;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.lang.invoke.LambdaMetafactory;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.zip.ZipFile;

/**
 * Bytecode-driven discovery of every {@code (EntityType field -&gt; renderer class)} registration
 * in {@code net.minecraft.client.renderer.entity.EntityRenderers.<clinit>}. Mirrors
 * {@link lib.minecraft.renderer.tooling.blockentity.SourceDiscovery#discover SourceDiscovery}'s
 * registry walk for the block-entity side - the bytecode shape is identical:
 *
 * <pre>
 *   GETSTATIC EntityType.&lt;NAME&gt;
 *   INVOKEDYNAMIC create:()EntityRendererProvider     // lambda metafactory
 *   INVOKESTATIC EntityRenderers.register(EntityType, EntityRendererProvider)V
 * </pre>
 *
 * <p>The {@link InvokeDynamicInsnNode} carries a {@link Handle} pointing at either the renderer
 * constructor directly ({@link Opcodes#H_NEWINVOKESPECIAL} - the lambda's body is just
 * {@code new XRenderer(context)}) or a synthetic {@code lambda$static$N} static method
 * ({@link Opcodes#H_INVOKESTATIC}) whose body wraps something more complex (composite
 * renderers, conditional dispatch). For the static-lambda case we walk the lambda body for
 * the first {@code NEW TypeInsnNode}, which is the concrete renderer being instantiated.
 *
 * <p>Output is the seed for the Java-derived entity-model pipeline: every mob in
 * {@link MobRegistryDiscovery} should pair with one {@link Registration} here, and the
 * renderer class is the entry point the next phase walks for the model factory and texture
 * binding.
 */
@UtilityClass
public final class EntityRendererDiscovery {

    /** JVM internal name of {@code net.minecraft.client.renderer.entity.EntityRenderers}. */
    private static final @NotNull String ENTITY_RENDERERS = "net/minecraft/client/renderer/entity/EntityRenderers";

    /** JVM internal name of {@code net.minecraft.world.entity.EntityType}. */
    private static final @NotNull String ENTITY_TYPE = "net/minecraft/world/entity/EntityType";

    /** JVM internal name of {@code net.minecraft.client.model.geom.ModelLayers}. */
    private static final @NotNull String MODEL_LAYERS = "net/minecraft/client/model/geom/ModelLayers";

    /**
     * One {@code (EntityType field, renderer class)} pair extracted from
     * {@code EntityRenderers.<clinit>}.
     *
     * @param entityFieldName the static field name on {@code EntityType}, e.g. {@code "ZOMBIE"},
     *     {@code "ARMADILLO"}; cross-references {@link MobRegistryDiscovery.MobEntry#fieldName()}
     * @param rendererInternalName the renderer class's JVM internal name, e.g.
     *     {@code "net/minecraft/client/renderer/entity/ZombieRenderer"}
     * @param lambdaLayerFields {@code GETSTATIC ModelLayers.X} field names observed in the
     *     {@code lambda$static$N} body that produces this renderer. Empty when the lambda is a
     *     direct constructor reference (the renderer's own constructor sources its layer fields).
     *     Populated when {@code EntityRenderers.<clinit>} wires layer fields through constructor
     *     params (e.g. {@code new SquidRenderer(context, ModelLayers.SQUID, ModelLayers.SQUID_BABY)}
     *     in {@code lambda$static$37}) - these are the only place the {@code ModelLayers.X} reference
     *     appears for those renderers since their constructors only see {@code aload_N} param
     *     references at bytecode level.
     * @param lambdaTypeArgs {@code GETSTATIC <X>$Type.<CONSTANT>} field references observed in
     *     the lambda body, i.e. enum constants whose owner is a {@code $Type}-suffixed inner
     *     class of the renderer (or any class). Populated for renderers in the donkey / horse
     *     family where the lambda passes per-entity Type-enum constants
     *     ({@code DonkeyRenderer$Type.DONKEY} vs {@code .MULE}; {@code UndeadHorseRenderer$Type.ZOMBIE}
     *     vs {@code .SKELETON}) into the renderer's constructor, which then stores
     *     {@code Type.texture} into instance fields read by {@code getTextureLocation}.
     *     {@link EntityTextureResolver} walks these to extract the per-entity texture path
     *     when the renderer's binding is otherwise flagged as {@code "(instance-field-driven)"}.
     */
    public record Registration(
        @NotNull String entityFieldName,
        @NotNull String rendererInternalName,
        @NotNull ConcurrentList<String> lambdaLayerFields,
        @NotNull ConcurrentList<TypeFieldRef> lambdaTypeArgs
    ) {}

    /**
     * Owner + field-name pair for a {@code GETSTATIC <Owner>$Type.<NAME>} reference seen in
     * a renderer-factory lambda body. Used by {@link EntityTextureResolver} to resolve
     * instance-field-driven renderers (donkey / horse) by matching the per-entity Type enum
     * constant the lambda passes into the constructor against the constant's
     * {@code .texture} field initialiser in the {@code Type} enum's {@code <clinit>}.
     *
     * @param owner the JVM internal name of the enum-class owner, e.g.
     *     {@code "net/minecraft/client/renderer/entity/DonkeyRenderer$Type"}
     * @param name the static-field constant name, e.g. {@code "DONKEY"} or {@code "DONKEY_BABY"}
     */
    public record TypeFieldRef(@NotNull String owner, @NotNull String name) {}

    /**
     * Walks {@code EntityRenderers.<clinit>} and returns one {@link Registration} per
     * successfully-resolved registration. Diagnostic info is recorded for unresolvable lambdas
     * (typically synthetic factories that don't directly {@code NEW} a renderer); the missing
     * registration drops out silently from the returned list.
     *
     * @param zip the deobfuscated client jar
     * @param diagnostics the diagnostic sink shared with sibling discovery walks
     * @return registrations in vanilla {@code <clinit>} order (alphabetical by EntityType field)
     */
    public static @NotNull ConcurrentList<Registration> discover(
        @NotNull ZipFile zip,
        @NotNull Diagnostics diagnostics
    ) {
        ClassNode registryClass = AsmKit.loadClass(zip, ENTITY_RENDERERS);
        if (registryClass == null) {
            diagnostics.error("'%s' class missing from jar - cannot discover entity renderers", ENTITY_RENDERERS);
            return Concurrent.newList();
        }
        MethodNode registryInit = AsmKit.findMethod(registryClass, "<clinit>");
        if (registryInit == null) {
            diagnostics.error("'%s.<clinit>' missing - cannot discover entity renderers", ENTITY_RENDERERS);
            return Concurrent.newList();
        }

        ConcurrentList<Registration> out = Concurrent.newList();
        String pendingEntityField = null;
        for (AbstractInsnNode in = registryInit.instructions.getFirst(); in != null; in = in.getNext()) {
            // Capture the most recent EntityType.X GETSTATIC. When the next INVOKEDYNAMIC
            // (lambda factory producing an EntityRendererProvider) appears, its target Handle
            // is the renderer constructor (or a synthetic wrapper) we want to bind to this field.
            if (in instanceof FieldInsnNode fieldInsn
                && fieldInsn.getOpcode() == Opcodes.GETSTATIC
                && ENTITY_TYPE.equals(fieldInsn.owner)) {
                pendingEntityField = fieldInsn.name;
                continue;
            }
            if (in instanceof InvokeDynamicInsnNode indy && pendingEntityField != null) {
                LambdaResolution resolution = resolveLambdaRenderer(indy, registryClass);
                if (resolution != null) {
                    ConcurrentList<String> lambdaLayers = Concurrent.newList();
                    resolution.lambdaLayerFields.forEach(lambdaLayers::add);
                    ConcurrentList<TypeFieldRef> typeArgs = Concurrent.newList();
                    resolution.lambdaTypeArgs.forEach(typeArgs::add);
                    out.add(new Registration(pendingEntityField, resolution.rendererClass, lambdaLayers, typeArgs));
                } else {
                    diagnostics.info("EntityRenderers.<clinit>: could not resolve renderer class for EntityType.%s - skipped", pendingEntityField);
                }
                pendingEntityField = null;
            }
        }
        return out;
    }

    /**
     * Resolves an {@code INVOKEDYNAMIC} built via {@link LambdaMetafactory#metafactory} to the
     * internal name of the renderer class the lambda produces, plus any {@code ModelLayers.X}
     * field references seen in the lambda body. Two recognised tags:
     * <ul>
     *   <li>{@link Opcodes#H_NEWINVOKESPECIAL} ({@code tag=8}) - direct constructor reference,
     *       lambda body is just {@code new RendererClass(context)} - no ModelLayers fields here,
     *       the renderer's own constructor is the source of truth.</li>
     *   <li>{@link Opcodes#H_INVOKESTATIC} ({@code tag=6}) - synthetic {@code lambda$static$N}
     *       wrapper. The lambda body is walked for both the first {@link TypeInsnNode}
     *       {@link Opcodes#NEW} (the renderer class) AND every {@code GETSTATIC ModelLayers.X}
     *       reference (the layer fields the lambda passes through to the renderer constructor).
     *       This catches squid / endermite / silverfish / glow_squid / piglin /
     *       zombified_piglin / donkey / mule / llama / trader_llama whose renderers take
     *       {@code ModelLayerLocation} as a constructor parameter and therefore have no GETSTATIC
     *       in their own bytecode.</li>
     * </ul>
     * Returns {@code null} when the lambda doesn't match either pattern.
     */
    private static @Nullable LambdaResolution resolveLambdaRenderer(
        @NotNull InvokeDynamicInsnNode indy,
        @NotNull ClassNode ownerClass
    ) {
        if (indy.bsmArgs.length < 2) return null;
        if (!(indy.bsmArgs[1] instanceof Handle handle)) return null;

        if (handle.getTag() == Opcodes.H_NEWINVOKESPECIAL && "<init>".equals(handle.getName()))
            return new LambdaResolution(handle.getOwner(), Set.of(), Set.of());

        if (handle.getTag() == Opcodes.H_INVOKESTATIC && handle.getOwner().equals(ownerClass.name)) {
            MethodNode lambda = AsmKit.findMethod(ownerClass, handle.getName(), handle.getDesc());
            if (lambda == null) return null;
            String rendererClass = null;
            Set<String> layerFields = new LinkedHashSet<>();
            Set<TypeFieldRef> typeArgs = new LinkedHashSet<>();
            for (AbstractInsnNode node = lambda.instructions.getFirst(); node != null; node = node.getNext()) {
                if (rendererClass == null && node instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW)
                    rendererClass = type.desc;
                if (node.getOpcode() == Opcodes.GETSTATIC
                    && node instanceof FieldInsnNode fi) {
                    if (MODEL_LAYERS.equals(fi.owner))
                        layerFields.add(fi.name);
                    else if (fi.owner.contains("$Type"))
                        typeArgs.add(new TypeFieldRef(fi.owner, fi.name));
                }
            }
            return rendererClass == null ? null : new LambdaResolution(rendererClass, layerFields, typeArgs);
        }
        return null;
    }

    /** Internal triple: the renderer class plus layer fields and Type-enum fields observed in the lambda body. */
    private record LambdaResolution(
        @NotNull String rendererClass,
        @NotNull Set<String> lambdaLayerFields,
        @NotNull Set<TypeFieldRef> lambdaTypeArgs
    ) {}

}
