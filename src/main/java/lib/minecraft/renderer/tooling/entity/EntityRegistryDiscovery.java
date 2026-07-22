package lib.minecraft.renderer.tooling.entity;

import lib.minecraft.renderer.tooling.kernel.AsmKit;
import lib.minecraft.renderer.tooling.kernel.ClassNodeCache;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lib.minecraft.renderer.tooling.kernel.ToolingSession;
import lib.minecraft.renderer.tooling.kernel.VanillaSourceClasses;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Single-pass discovery of every living mob entity with a registered renderer via a
 * three-walk registry join. Registry order == on-disk family order - declared, not
 * incidental.
 *
 * <p>Three independent walks feed the join:
 * <ol>
 *   <li><b>{@code EntityType} field scan.</b> Each static field's generic signature
 *       ({@code EntityType<LFoo;>}) names the concrete entity class; non-{@code LivingEntity}
 *       classes drop out.</li>
 *   <li><b>{@code EntityType.<clinit>} scan.</b> {@code LDC "<id>"} +
 *       {@code GETSTATIC MobCategory.X} + {@code INVOKESTATIC Builder.of} + following
 *       {@code PUTSTATIC <FIELD>} pairs each field with its registry id and spawn category,
 *       in static-initializer order.</li>
 *   <li><b>{@code EntityRenderers.<clinit>} scan.</b> {@code GETSTATIC EntityType.X} paired
 *       with the next {@code INVOKEDYNAMIC}, resolving the lambda handle to the renderer
 *       class plus any {@code ModelLayers.X} / {@code <Renderer>$Type.X} references in the
 *       lambda body.</li>
 * </ol>
 *
 * <p>Mobs whose renderer registration is missing or unresolvable land as WARN diagnostics
 * rather than a JSON side table.
 */
public final class EntityRegistryDiscovery {

    private EntityRegistryDiscovery() {
    }

    /**
     * Walks the three registries, joins the {@code EntityType} registrations against the
     * renderer registrations, and returns the joined subjects in vanilla static-initializer
     * order.
     *
     * @param session the live session
     * @return the joined subjects in registry order
     */
    public static @NotNull List<EntitySubject> discover(@NotNull ToolingSession session) {
        ClassNodeCache cache = session.cache();
        Diagnostics diagnostics = session.diagnostics().child("discovery");

        ClassNode entityType = AsmKit.requireClass(cache, VanillaSourceClasses.Types.ENTITY_TYPE, "EntityType discovery");
        Map<String, String> fieldToClass = collectEntityTypeFieldClasses(entityType);
        Map<String, MobRegistration> mobRegistrations = collectMobRegistrations(entityType, diagnostics);
        Map<String, RendererRegistration> rendererRegistrations = collectRendererRegistrations(cache, diagnostics);

        List<EntitySubject> subjects = new ArrayList<>();
        int totalMobs = 0;
        for (Map.Entry<String, MobRegistration> mobEntry : mobRegistrations.entrySet()) {
            String fieldName = mobEntry.getKey();
            MobRegistration mob = mobEntry.getValue();

            String entityClass = fieldToClass.get(fieldName);
            if (entityClass == null) {
                diagnostics.info("EntityType.%s has no concrete generic parameter (wildcard or missing signature)", fieldName);
                continue;
            }

            if (!AsmKit.extendsClass(cache, entityClass, VanillaSourceClasses.Types.LIVING_ENTITY)) continue;

            totalMobs++;
            RendererRegistration renderer = rendererRegistrations.get(fieldName);
            if (renderer == null) {
                diagnostics.warn("mob '%s%s' has no resolvable renderer registration - no family emitted",
                    VanillaSourceClasses.Paths.MINECRAFT_NAMESPACE, mob.entityId());
                continue;
            }

            subjects.add(new EntitySubject(
                VanillaSourceClasses.Paths.MINECRAFT_NAMESPACE + mob.entityId(),
                fieldName,
                entityClass,
                mob.mobCategory(),
                renderer.rendererClass(),
                List.copyOf(renderer.lambdaLayerFields()),
                List.copyOf(renderer.lambdaTypeArgs()),
                List.copyOf(renderer.lambdaEquipmentLayerTypes())
            ));
        }

        diagnostics.info("discovered %d living mobs, %d with renderers", totalMobs, subjects.size());
        return subjects;
    }

    /**
     * Reads every {@code EntityType} static field's generic signature and returns a map from
     * field name to the concrete entity class's internal name. Wildcard / generic-variable
     * signatures drop out - those don't correspond to any one spawn-registered entity.
     */
    private static @NotNull Map<String, String> collectEntityTypeFieldClasses(@NotNull ClassNode entityType) {
        Map<String, String> out = new LinkedHashMap<>();
        for (FieldNode field : entityType.fields) {
            if (!VanillaSourceClasses.Descs.ENTITY_TYPE_REF.equals(field.desc)) continue;
            if (field.signature == null) continue;
            String concrete = AsmKit.extractGenericTypeParameter(field.signature, VanillaSourceClasses.Types.ENTITY_TYPE);
            if (concrete != null) out.put(field.name, concrete);
        }
        return out;
    }

    /**
     * Walks {@code EntityType.<clinit>} and returns a map from field name to its registration
     * metadata (entity id + {@code MobCategory}), preserving static-initializer (vanilla
     * alphabetical) order.
     */
    private static @NotNull Map<String, MobRegistration> collectMobRegistrations(
        @NotNull ClassNode entityType,
        @NotNull Diagnostics diagnostics
    ) {
        MethodNode clinit = AsmKit.findMethod(entityType, AsmKit.CLINIT);
        if (clinit == null) {
            diagnostics.error("%s has no <clinit> method", VanillaSourceClasses.Types.ENTITY_TYPE);
            return Map.of();
        }

        Map<String, MobRegistration> out = new LinkedHashMap<>();
        String pendingId = null;
        String pendingCategory = null;

        for (AbstractInsnNode in = clinit.instructions.getFirst(); in != null; in = in.getNext()) {
            String literal = AsmKit.readStringLiteral(in);
            if (literal != null) pendingId = literal;
            if (AsmKit.isGetStatic(in, VanillaSourceClasses.Types.MOB_CATEGORY))
                pendingCategory = ((FieldInsnNode) in).name;

            if (!isBuilderOfCall(in)) continue;
            if (pendingId == null || pendingCategory == null) {
                diagnostics.warn("EntityType$Builder.of call without preceding entity id and MobCategory literals");
                pendingId = null;
                pendingCategory = null;
                continue;
            }

            String fieldName = AsmKit.findFollowingPutStatic(in, VanillaSourceClasses.Types.ENTITY_TYPE, EntityRegistryDiscovery::isBuilderOfCall);
            if (fieldName == null) {
                diagnostics.warn("EntityType registration for id '%s' has no PUTSTATIC field", pendingId);
            } else {
                out.put(fieldName, new MobRegistration(pendingId, pendingCategory));
            }
            pendingId = null;
            pendingCategory = null;
        }

        return out;
    }

    /** Reports whether {@code in} is an {@code INVOKESTATIC EntityType$Builder.of(...)}. */
    private static boolean isBuilderOfCall(@NotNull AbstractInsnNode in) {
        return AsmKit.isInvokeStatic(in, VanillaSourceClasses.Types.ENTITY_TYPE_BUILDER, VanillaSourceClasses.Methods.BUILDER_OF);
    }

    /**
     * Walks {@code EntityRenderers.<clinit>} and returns a map from {@code EntityType} field
     * name to the resolved renderer class plus the {@code ModelLayers.X} /
     * {@code <Renderer>$Type.X} / {@code EquipmentClientInfo$LayerType.X} references seen in
     * the lambda body.
     *
     * <p>Each registration compiles to {@code GETSTATIC EntityType.X} followed by an
     * {@code INVOKEDYNAMIC create():EntityRendererProvider}; the walk carries the field name
     * forward and pairs it with the next {@code INVOKEDYNAMIC}. Unresolvable lambdas log an
     * INFO trace and leave the entity unmapped (it surfaces as the WARN in {@link #discover}).
     */
    private static @NotNull Map<String, RendererRegistration> collectRendererRegistrations(
        @NotNull ClassNodeCache cache,
        @NotNull Diagnostics diagnostics
    ) {
        ClassNode registryClass = cache.load(VanillaSourceClasses.Types.ENTITY_RENDERERS);
        if (registryClass == null) {
            diagnostics.error("'%s' class missing from jar - cannot discover entity renderers", VanillaSourceClasses.Types.ENTITY_RENDERERS);
            return Map.of();
        }
        MethodNode registryInit = AsmKit.findMethod(registryClass, AsmKit.CLINIT);
        if (registryInit == null) {
            diagnostics.error("'%s.<clinit>' missing - cannot discover entity renderers", VanillaSourceClasses.Types.ENTITY_RENDERERS);
            return Map.of();
        }

        Map<String, RendererRegistration> out = new LinkedHashMap<>();
        String pendingEntityField = null;
        for (AbstractInsnNode in = registryInit.instructions.getFirst(); in != null; in = in.getNext()) {
            if (AsmKit.isGetStatic(in, VanillaSourceClasses.Types.ENTITY_TYPE)) {
                pendingEntityField = ((FieldInsnNode) in).name;
                continue;
            }
            if (in instanceof InvokeDynamicInsnNode indy && pendingEntityField != null) {
                RendererRegistration resolution = resolveLambdaRenderer(indy, registryClass);
                if (resolution != null)
                    out.put(pendingEntityField, resolution);
                else
                    diagnostics.info("EntityRenderers.<clinit>: could not resolve renderer class for EntityType.%s - skipped", pendingEntityField);
                pendingEntityField = null;
            }
        }
        return out;
    }

    /**
     * Resolves a lambda-metafactory {@code INVOKEDYNAMIC} to the renderer class the lambda
     * produces, plus the {@code ModelLayers.X} field references, {@code <Renderer>$Type.X}
     * enum-constant references, and {@code EquipmentClientInfo$LayerType.X} constants seen in
     * the lambda body. Two implementation-method shapes:
     * a bare {@code XRenderer::new} constructor reference (the handle's owner IS the renderer
     * class, empty reference sets), and an {@code H_INVOKESTATIC} synthetic lambda whose body
     * is walked for its first {@code NEW} plus the field references. Returns {@code null}
     * when the handle can't be extracted or its tag is neither shape.
     */
    private static @Nullable RendererRegistration resolveLambdaRenderer(
        @NotNull InvokeDynamicInsnNode indy,
        @NotNull ClassNode ownerClass
    ) {
        Handle handle = AsmKit.extractLambdaHandle(indy);
        if (handle == null) return null;

        if (handle.getTag() == Opcodes.H_NEWINVOKESPECIAL && AsmKit.INIT.equals(handle.getName()))
            return new RendererRegistration(handle.getOwner(), Set.of(), Set.of(), Set.of());

        if (handle.getTag() == Opcodes.H_INVOKESTATIC && handle.getOwner().equals(ownerClass.name)) {
            Set<String> layerFields = new LinkedHashSet<>();
            Set<EntitySubject.TypeFieldRef> typeArgs = new LinkedHashSet<>();
            Set<String> equipmentLayerTypes = new LinkedHashSet<>();
            String rendererClass = AsmKit.walkLambdaBody(indy, ownerClass, node -> {
                if (AsmKit.isGetStatic(node, VanillaSourceClasses.Types.MODEL_LAYERS))
                    layerFields.add(((FieldInsnNode) node).name);
                else if (AsmKit.isGetStatic(node, VanillaSourceClasses.Types.EQUIPMENT_LAYER_TYPE))
                    equipmentLayerTypes.add(((FieldInsnNode) node).name);
                else if (node.getOpcode() == Opcodes.GETSTATIC && node instanceof FieldInsnNode fi && fi.owner.contains("$Type"))
                    typeArgs.add(new EntitySubject.TypeFieldRef(fi.owner, fi.name));
            });
            return rendererClass == null ? null : new RendererRegistration(rendererClass, layerFields, typeArgs, equipmentLayerTypes);
        }
        return null;
    }

    /**
     * {@code <clinit>}-derived registration metadata paired with each {@code EntityType} field.
     *
     * @param entityId the namespace-less registry id
     * @param mobCategory the {@code MobCategory} enum constant name
     */
    private record MobRegistration(@NotNull String entityId, @NotNull String mobCategory) {}

    /**
     * Resolved renderer registration paired with each {@code EntityType} field.
     *
     * @param rendererClass the renderer class's JVM internal name
     * @param lambdaLayerFields {@code ModelLayers.X} field names from the lambda body
     * @param lambdaTypeArgs {@code <Renderer>$Type.X} references from the lambda body
     * @param lambdaEquipmentLayerTypes {@code EquipmentClientInfo$LayerType.X} constant names
     *     from the lambda body
     */
    private record RendererRegistration(
        @NotNull String rendererClass,
        @NotNull Set<String> lambdaLayerFields,
        @NotNull Set<EntitySubject.TypeFieldRef> lambdaTypeArgs,
        @NotNull Set<String> lambdaEquipmentLayerTypes
    ) {}

}
