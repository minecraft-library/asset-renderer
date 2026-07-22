package lib.minecraft.renderer.tooling.entity;

import lib.minecraft.renderer.tooling.kernel.VanillaSourceClasses;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * One discovered living-mob entity joined with its renderer registration - everything the
 * per-entity resolver chain needs, produced by {@link EntityRegistryDiscovery} in registry
 * order.
 *
 * @param entityId the namespaced registry id, e.g. {@code minecraft:wolf} - the on-disk
 *     family key
 * @param typeField the {@code EntityType} static field name, e.g. {@code WOLF}
 * @param entityClass the concrete entity class's JVM internal name
 * @param mobCategory the {@code MobCategory} enum constant name from the registration
 * @param rendererClass the renderer class's JVM internal name
 * @param lambdaLayerFields {@code GETSTATIC ModelLayers.X} field names observed in the
 *     renderer-factory lambda body (empty for direct constructor references) - lets the
 *     primary-layer pick promote {@code ModelLayers.DONKEY} over the lambda's
 *     {@code DONKEY_SADDLE}
 * @param lambdaTypeArgs {@code GETSTATIC <Renderer>$Type.X} enum-constant references
 *     observed in the lambda body - resolves instance-field-driven renderers (donkey /
 *     horse) by matching the constant against its {@code .texture} initialiser
 * @param lambdaEquipmentLayerTypes {@code GETSTATIC EquipmentClientInfo$LayerType.X} constant
 *     names observed in the lambda body - the layer type of a renderer that takes it as a
 *     constructor parameter (the donkey / mule / undead-horse saddles) rather than naming it
 *     in its own bytecode
 */
public record EntitySubject(
    @NotNull String entityId,
    @NotNull String typeField,
    @NotNull String entityClass,
    @NotNull String mobCategory,
    @NotNull String rendererClass,
    @NotNull List<String> lambdaLayerFields,
    @NotNull List<TypeFieldRef> lambdaTypeArgs,
    @NotNull List<String> lambdaEquipmentLayerTypes
) {

    /**
     * Owner + field-name pair for a {@code GETSTATIC <Renderer>$Type.<NAME>} reference seen
     * in a renderer-factory lambda body.
     *
     * @param owner the enum-class owner's JVM internal name
     * @param name the static-field constant name, e.g. {@code DONKEY} or {@code DONKEY_BABY}
     */
    public record TypeFieldRef(@NotNull String owner, @NotNull String name) {}

    /**
     * The namespace-less id ({@code wolf} for {@code minecraft:wolf}) - the basename the
     * texture / layer-pick heuristics match against.
     */
    public @NotNull String localId() {
        return this.entityId.startsWith(VanillaSourceClasses.Paths.MINECRAFT_NAMESPACE)
            ? this.entityId.substring(VanillaSourceClasses.Paths.MINECRAFT_NAMESPACE.length())
            : this.entityId;
    }

}
