package lib.minecraft.renderer.tooling.entity;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import lib.minecraft.renderer.tooling.util.AsmKit;
import lib.minecraft.renderer.tooling.util.Diagnostics;
import lib.minecraft.renderer.tooling.util.VanillaSourceClasses;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.invoke.LambdaMetafactory;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Single-pass discovery of every living mob entity that has a registered renderer. Joins
 * the two registry walks - {@code EntityType} for living-mob registrations and
 * {@code EntityRenderers.<clinit>} for {@code EntityType -> renderer} lambdas - by
 * EntityType field name and emits a single per-entity {@link Result.Entry} carrying
 * everything downstream resolvers need.
 *
 * <p>Three independent walks feed the join:
 * <ol>
 *   <li><b>EntityType field scan.</b> Each static {@code EntityType} field carries a generic
 *       signature of shape {@code Lnet/minecraft/world/entity/EntityType<Lcom/acme/Foo;>;};
 *       parsing the inner parameter type gives the concrete entity class. Fields whose
 *       concrete class does not extend {@code LivingEntity} drop out.</li>
 *   <li><b>EntityType {@code <clinit>} scan.</b> The registration sequence
 *       {@code LDC "<id>"} + {@code INVOKEDYNAMIC <factory>} +
 *       {@code GETSTATIC MobCategory.<CATEGORY>} +
 *       {@code INVOKESTATIC Builder.of(...)} + ... + {@code PUTSTATIC <FIELD>} pairs each
 *       static field with its registry id and spawn category. Order in this list matches
 *       static-initializer (vanilla alphabetical) order.</li>
 *   <li><b>EntityRenderers {@code <clinit>} scan.</b> {@code GETSTATIC EntityType.X} +
 *       {@code INVOKEDYNAMIC create:()EntityRendererProvider} pairs each EntityType field
 *       with a renderer class extracted from the lambda target Handle. For
 *       {@link Opcodes#H_INVOKESTATIC} synthetic lambdas the body is walked for the first
 *       {@code NEW TypeInsnNode} (the renderer class) plus any {@code ModelLayers.X} field
 *       references and any {@code <Renderer>$Type.X} enum-constant references.</li>
 * </ol>
 *
 * <p>The output list is keyed in EntityType {@code <clinit>} order so downstream consumers
 * see entities in the same order they appear in vanilla static-initialization - which is the
 * row order {@code entity_models.json} carries. The {@code Result} wrapper also exposes the
 * unjoined-mob list (mobs without a renderer registration) and the total mob count, both
 * consumed by {@link EntityDiagnosticsWriter}.
 */
@UtilityClass
public final class EntityRegistryDiscovery {

    /**
     * Name of the builder factory method: {@code EntityType$Builder.of(EntityFactory, MobCategory)}.
     */
    private static final @NotNull String BUILDER_OF = "of";

    /**
     * Field descriptor every {@code EntityType} static field shares.
     */
    private static final @NotNull String ENTITY_TYPE_DESC = "Lnet/minecraft/world/entity/EntityType;";

    /**
     * Extracts the inner generic parameter from a signature like
     * {@code Lnet/minecraft/world/entity/EntityType<LFoo;>;}. Group 1 captures the inner
     * parameter's internal name. Non-concrete parameters (wildcards, generic variables) don't
     * match and are silently skipped.
     */
    private static final @NotNull Pattern ENTITY_TYPE_SIGNATURE = Pattern.compile(
        "^Lnet/minecraft/world/entity/EntityType<L([^;<>]+);>;$"
    );

    /**
     * Bundle returned by {@link #discover(EntityToolingContext)} carrying the joined per-entity
     * entries plus auxiliary stats consumed by the diagnostics writer.
     *
     * @param entries one {@link Entry} per mob that has a matching renderer registration, in
     *     vanilla static-initializer order
     * @param mobsWithoutRenderer namespaced ids of mobs whose {@code EntityType} registration
     *     exists but whose {@code EntityRenderers.<clinit>} entry could not be resolved (or is
     *     missing entirely) - retained for the diagnostic JSON
     * @param totalMobsDiscovered count of living-mob {@code EntityType} fields walked; the
     *     diagnostic JSON reports this as {@code mobs_discovered}
     */
    public record Result(
        @NotNull ConcurrentList<Entry> entries,
        @NotNull ConcurrentList<String> mobsWithoutRenderer,
        int totalMobsDiscovered
    ) {

        /**
         * One joined entity, paired with the renderer registration that points at it.
         *
         * @param entityId the namespace-less registry id, e.g. {@code "villager"}, {@code "zombie"}
         * @param entityFieldName the {@code EntityType} static field name, e.g. {@code "VILLAGER"}
         * @param entityClassInternalName the concrete entity class, e.g.
         *     {@code "net/minecraft/world/entity/monster/zombie/Zombie"}
         * @param mobCategory the {@code MobCategory} enum constant name from the registration
         * @param rendererInternalName the renderer class, e.g.
         *     {@code "net/minecraft/client/renderer/entity/ZombieRenderer"}
         * @param lambdaLayerFields {@code GETSTATIC ModelLayers.X} field names observed in the
         *     renderer-factory lambda body (populated for {@link Opcodes#H_INVOKESTATIC}
         *     lambdas only; empty for direct constructor references). Lets the layer picker
         *     promote {@code ModelLayers.DONKEY} over the lambda's {@code DONKEY_SADDLE} for
         *     {@code minecraft:donkey}.
         * @param lambdaTypeArgs {@code GETSTATIC <X>$Type.<CONSTANT>} references observed in
         *     the lambda body; lets {@code EntityTextureResolver} resolve
         *     instance-field-driven renderers (donkey / horse) by matching the constant
         *     against the constant's {@code .texture} initialiser in the Type enum
         */
        public record Entry(
            @NotNull String entityId,
            @NotNull String entityFieldName,
            @NotNull String entityClassInternalName,
            @NotNull String mobCategory,
            @NotNull String rendererInternalName,
            @NotNull ConcurrentList<String> lambdaLayerFields,
            @NotNull ConcurrentList<TypeFieldRef> lambdaTypeArgs
        ) {}

    }

    /**
     * Owner + field-name pair for a {@code GETSTATIC <Owner>$Type.<NAME>} reference seen in a
     * renderer-factory lambda body. Consumed by {@link EntityTextureResolver} to resolve
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
     * Walks the three registries, joins the EntityType registrations against the renderer
     * registrations, and returns the joined entries in vanilla static-initializer order.
     *
     * @param context the tooling context carrying the open jar, ClassNode cache, and diagnostics sink
     * @return the joined entries plus auxiliary stats for the diagnostic JSON
     */
    public static @NotNull Result discover(@NotNull EntityToolingContext context) {
        Diagnostics diagnostics = context.diagnostics();

        ClassNode entityType = AsmKit.requireClass(context.classNodes(), VanillaSourceClasses.ENTITY_TYPE, "EntityType");
        Map<String, String> fieldToClass = collectEntityTypeFieldClasses(entityType);
        Map<String, MobRegistration> mobRegistrations = collectMobRegistrations(entityType, diagnostics);
        Map<String, RendererRegistration> rendererRegistrations = collectRendererRegistrations(context);

        ConcurrentList<Result.Entry> entries = Concurrent.newList();
        ConcurrentList<String> mobsWithoutRenderer = Concurrent.newList();
        int totalMobs = 0;
        for (Map.Entry<String, MobRegistration> mobEntry : mobRegistrations.entrySet()) {
            String fieldName = mobEntry.getKey();
            MobRegistration mob = mobEntry.getValue();

            String entityClass = fieldToClass.get(fieldName);
            if (entityClass == null) {
                diagnostics.info("EntityType.%s has no concrete generic parameter (wildcard or missing signature)", fieldName);
                continue;
            }

            if (!AsmKit.extendsClass(context.classNodes(), entityClass, VanillaSourceClasses.LIVING_ENTITY))
                continue;

            totalMobs++;
            RendererRegistration renderer = rendererRegistrations.get(fieldName);
            if (renderer == null) {
                mobsWithoutRenderer.add("minecraft:" + mob.entityId);
                continue;
            }

            entries.add(new Result.Entry(
                mob.entityId,
                fieldName,
                entityClass,
                mob.mobCategory,
                renderer.rendererClass,
                renderer.lambdaLayerFields,
                renderer.lambdaTypeArgs
            ));
        }

        return new Result(entries, mobsWithoutRenderer, totalMobs);
    }

    /**
     * Reads every {@code EntityType} static field's generic signature and returns a map from
     * field name to the concrete entity class's internal name. Fields whose signature uses a
     * wildcard ({@code EntityType<?>}) or a generic variable drop out - those don't correspond
     * to any one spawn-registered entity anyway.
     */
    private static @NotNull Map<String, String> collectEntityTypeFieldClasses(@NotNull ClassNode entityType) {
        Map<String, String> out = new LinkedHashMap<>();
        for (FieldNode field : entityType.fields) {
            if (!ENTITY_TYPE_DESC.equals(field.desc)) continue;
            if (field.signature == null) continue;

            Matcher matcher = ENTITY_TYPE_SIGNATURE.matcher(field.signature);
            if (!matcher.matches()) continue;

            out.put(field.name, matcher.group(1));
        }
        return out;
    }

    /**
     * Walks {@code EntityType.<clinit>} and returns a map from EntityType field name to its
     * registration metadata (entity-id + MobCategory). Preserves static-initializer (vanilla
     * alphabetical) order.
     */
    private static @NotNull Map<String, MobRegistration> collectMobRegistrations(
        @NotNull ClassNode entityType,
        @NotNull Diagnostics diagnostics
    ) {
        MethodNode clinit = AsmKit.findMethod(entityType, AsmKit.CLINIT);
        if (clinit == null) {
            diagnostics.error("%s has no <clinit> method", VanillaSourceClasses.ENTITY_TYPE);
            return Map.of();
        }

        Map<String, MobRegistration> out = new LinkedHashMap<>();
        MobWindow window = new MobWindow();

        for (AbstractInsnNode insn = clinit.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            observeMobWindow(insn, window);

            if (!isBuilderOfCall(insn)) continue;
            if (window.entityId == null || window.mobCategory == null) {
                diagnostics.warn("EntityType$Builder.of call without preceding entity id and MobCategory literals");
                window.reset();
                continue;
            }

            String fieldName = AsmKit.findFollowingPutStatic(insn, VanillaSourceClasses.ENTITY_TYPE, EntityRegistryDiscovery::isBuilderOfCall);
            if (fieldName == null) {
                diagnostics.warn("EntityType registration for id '%s' has no PUTSTATIC field", window.entityId);
                window.reset();
                continue;
            }

            out.put(fieldName, new MobRegistration(window.entityId, window.mobCategory));
            window.reset();
        }

        return out;
    }

    /**
     * Updates the rolling window of the most recent entity-id LDC and MobCategory GETSTATIC
     * seen on the EntityType {@code <clinit>} stream. Both are zeroed by the caller after the
     * matching {@code Builder.of} call fires.
     */
    private static void observeMobWindow(@NotNull AbstractInsnNode insn, @NotNull MobWindow window) {
        String s = AsmKit.readStringLiteral(insn);
        if (s != null) window.entityId = s;

        if (AsmKit.isGetStatic(insn, VanillaSourceClasses.MOB_CATEGORY))
            window.mobCategory = ((FieldInsnNode) insn).name;
    }

    /**
     * {@code true} when {@code insn} is an {@code INVOKESTATIC EntityType$Builder.of(...)}.
     */
    private static boolean isBuilderOfCall(@NotNull AbstractInsnNode insn) {
        return AsmKit.isInvokeStatic(insn, VanillaSourceClasses.ENTITY_TYPE_BUILDER, BUILDER_OF);
    }

    /**
     * Walks {@code EntityRenderers.<clinit>} and returns a map from EntityType field name to
     * the resolved renderer class plus any {@code ModelLayers.X} / {@code <Renderer>$Type.X}
     * references seen in the lambda body.
     *
     * <p>Each registration compiles to {@code GETSTATIC EntityType.X} immediately followed by
     * an {@code INVOKEDYNAMIC create():EntityRendererProvider}. The walk carries that
     * {@code GETSTATIC}'s field name forward in {@code pendingEntityField} and pairs it with the
     * next {@code INVOKEDYNAMIC} it sees, resolving the renderer via
     * {@link #resolveLambdaRenderer}; unresolvable lambdas log an INFO trace and leave the
     * entity unmapped (it later surfaces in {@code mobsWithoutRenderer}).
     */
    private static @NotNull Map<String, RendererRegistration> collectRendererRegistrations(
        @NotNull EntityToolingContext context
    ) {
        Diagnostics diagnostics = context.diagnostics();
        ClassNode registryClass = context.classNodes().load(VanillaSourceClasses.ENTITY_RENDERERS);
        if (registryClass == null) {
            diagnostics.error("'%s' class missing from jar - cannot discover entity renderers", VanillaSourceClasses.ENTITY_RENDERERS);
            return Map.of();
        }
        MethodNode registryInit = AsmKit.findMethod(registryClass, AsmKit.CLINIT);
        if (registryInit == null) {
            diagnostics.error("'%s.<clinit>' missing - cannot discover entity renderers", VanillaSourceClasses.ENTITY_RENDERERS);
            return Map.of();
        }

        Map<String, RendererRegistration> out = new LinkedHashMap<>();
        String pendingEntityField = null;
        for (AbstractInsnNode in = registryInit.instructions.getFirst(); in != null; in = in.getNext()) {
            if (AsmKit.isGetStatic(in, VanillaSourceClasses.ENTITY_TYPE)) {
                pendingEntityField = ((FieldInsnNode) in).name;
                continue;
            }
            if (in instanceof InvokeDynamicInsnNode indy && pendingEntityField != null) {
                LambdaResolution resolution = resolveLambdaRenderer(indy, registryClass);
                if (resolution != null) {
                    ConcurrentList<String> layers = Concurrent.newList();
                    layers.addAll(resolution.lambdaLayerFields);
                    ConcurrentList<TypeFieldRef> typeArgs = Concurrent.newList();
                    typeArgs.addAll(resolution.lambdaTypeArgs);
                    out.put(pendingEntityField, new RendererRegistration(resolution.rendererClass, layers, typeArgs));
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
     * field references and {@code <Renderer>$Type.X} enum-constant references seen in the
     * lambda body. Two implementation-method shapes are handled:
     *
     * <ul>
     *   <li><b>{@link Opcodes#H_NEWINVOKESPECIAL} constructor reference.</b> A bare
     *       {@code XRenderer::new}; the handle's owner IS the renderer class, so it is returned
     *       directly with empty layer / type-arg sets (no synthetic body to walk).</li>
     *   <li><b>{@link Opcodes#H_INVOKESTATIC} synthetic lambda.</b> A {@code ctx -> new XRenderer(ctx, ...)}
     *       compiled into a private static method on {@code ownerClass}. The body is walked for
     *       its first {@code NEW} (the renderer class) plus every {@code GETSTATIC ModelLayers.X}
     *       and {@code GETSTATIC <...$Type>.X} it references. Only lambdas owned by
     *       {@code ownerClass} are followed.</li>
     * </ul>
     *
     * <p>Returns {@code null} when the handle can't be extracted or its tag is neither shape.
     */
    private static @Nullable LambdaResolution resolveLambdaRenderer(
        @NotNull InvokeDynamicInsnNode indy,
        @NotNull ClassNode ownerClass
    ) {
        Handle handle = AsmKit.extractLambdaHandle(indy);
        if (handle == null) return null;

        if (handle.getTag() == Opcodes.H_NEWINVOKESPECIAL && AsmKit.INIT.equals(handle.getName()))
            return new LambdaResolution(handle.getOwner(), Set.of(), Set.of());

        if (handle.getTag() == Opcodes.H_INVOKESTATIC && handle.getOwner().equals(ownerClass.name)) {
            Set<String> layerFields = new LinkedHashSet<>();
            Set<TypeFieldRef> typeArgs = new LinkedHashSet<>();
            String rendererClass = AsmKit.walkLambdaBody(indy, ownerClass, node -> {
                if (AsmKit.isGetStatic(node, VanillaSourceClasses.MODEL_LAYERS)) {
                    layerFields.add(((FieldInsnNode) node).name);
                } else if (node.getOpcode() == Opcodes.GETSTATIC && node instanceof FieldInsnNode fi && fi.owner.contains("$Type")) {
                    typeArgs.add(new TypeFieldRef(fi.owner, fi.name));
                }
            });
            return rendererClass == null ? null : new LambdaResolution(rendererClass, layerFields, typeArgs);
        }
        return null;
    }

    /**
     * Rolling accumulator of per-registration literals consumed across {@link #collectMobRegistrations}.
     */
    private static final class MobWindow {
        @Nullable String entityId;
        @Nullable String mobCategory;

        void reset() {
            this.entityId = null;
            this.mobCategory = null;
        }
    }

    /**
     * {@code <clinit>}-derived registration metadata paired with each EntityType field.
     */
    private record MobRegistration(@NotNull String entityId, @NotNull String mobCategory) {}

    /**
     * Resolved renderer registration paired with each EntityType field.
     */
    private record RendererRegistration(
        @NotNull String rendererClass,
        @NotNull ConcurrentList<String> lambdaLayerFields,
        @NotNull ConcurrentList<TypeFieldRef> lambdaTypeArgs
    ) {}

    /**
     * Internal triple: the renderer class plus layer fields and Type-enum fields observed in the lambda body.
     */
    private record LambdaResolution(
        @NotNull String rendererClass,
        @NotNull Set<String> lambdaLayerFields,
        @NotNull Set<TypeFieldRef> lambdaTypeArgs
    ) {}

}
