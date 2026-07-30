package lib.minecraft.renderer.tooling.entity;

import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.tooling.geometry.GeometryManifest;
import lib.minecraft.renderer.tooling.geometry.GeometryRequest;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lib.minecraft.renderer.tooling.vanilla.LayerDefinitionIndex;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Node {@code axes.size} - the option-encoded size axis, in two forms:
 *
 * <ul>
 *   <li><b>mesh</b> - each option registers its own {@code GeometryRequest}, whether the mesh is a
 *       distinct factory (pufferfish small / medium from the renderer ctor's multiple
 *       {@code ModelLayers.PUFFERFISH_*} references, big = primary) or the primary factory under a
 *       whole-mesh transformer - a {@code MeshTransformer.scaling} factor (salmon 0.5 / 1.5 off
 *       {@code SALMON_SMALL} / {@code SALMON_LARGE}) or the aged-down rewrite the armor stand's
 *       {@code ARMOR_STAND_SMALL} is registered through. All three bake to a mesh: vanilla's
 *       {@code SalmonRenderer} likewise holds three baked {@code SalmonModel} instances and picks
 *       one, and {@code ArmorStandRenderer} holds two, so the transformed mesh is emitted as
 *       geometry the parser bakes the transformer into, not a render-time scale rider.</li>
 *   <li><b>scale</b> - the option multiplies {@code rendererScale}: slime / magma_cube 2.0 / 4.0
 *       proportional to their natural-size set (size-proportional per {@code SlimeRenderer.scale} at
 *       squish 0). These have no per-size mesh - vanilla scales the one model at render.</li>
 * </ul>
 *
 * <p>Membership is declared per entity (pufferfish / salmon carry a body-mesh axis; slime / magma_cube
 * a natural-size set); the concrete meshes / factors are derived. Option names come from the candidate
 * field's suffix matched against the size domain ({@code PUFFERFISH_MEDIUM} to {@code medium}); default =
 * the option-less domain member; option members emit in domain order.
 */
final class EntitySizeAxisResolver {

    private final @NotNull EntitySubject subject;
    private final @NotNull LayerDefinitionIndex layerDefinitions;
    private final @NotNull EntityGeometryRefResolver geometryRef;
    private final @NotNull GeometryManifest manifest;
    private final @NotNull Diagnostics diagnostics;

    EntitySizeAxisResolver(
        @NotNull EntitySubject subject,
        @NotNull LayerDefinitionIndex layerDefinitions,
        @NotNull EntityGeometryRefResolver geometryRef,
        @NotNull GeometryManifest manifest,
        @NotNull Diagnostics diagnostics
    ) {
        this.subject = subject;
        this.layerDefinitions = layerDefinitions;
        this.geometryRef = geometryRef;
        this.manifest = manifest;
        this.diagnostics = diagnostics;
    }

    /**
     * The size node, or {@code null} when the entity has neither a natural-size set nor a
     * declared size-shape axis (or the declared membership derives no options).
     *
     * @return the node, or {@code null} to omit
     */
    @Nullable JsonTree resolve() {
        List<Integer> naturalSizes = EntityAxisPolicies.naturalSizesFor(this.subject.entityId());
        if (naturalSizes != null) return naturalSizeForm(naturalSizes);
        if (!"size".equals(EntityAxisPolicies.shapeSizeAxisFor(this.subject.entityId()))) return null;
        return meshForm();
    }

    /**
     * Builds scale options proportional to the natural sizes (base = the first,
     * option-less), named into the size domain positionally.
     */
    private @Nullable JsonTree naturalSizeForm(@NotNull List<Integer> naturalSizes) {
        List<String> domain = EntityAxisPolicies.SIZE_DOMAIN.strings();
        if (naturalSizes.size() > domain.size()) {
            this.diagnostics.warn("P1 natural-size set %s exceeds the P28 domain %s - size axis omitted", naturalSizes, domain);
            return null;
        }
        Map<String, JsonTree> options = new LinkedHashMap<>();
        int base = naturalSizes.getFirst();
        for (int index = 1; index < naturalSizes.size(); index++)
            options.put(domain.get(index), JsonTree.object().put("scale", (float) naturalSizes.get(index) / base));
        this.diagnostics.info("size axis via P1 natural sizes %s (scale-per-size proportional)", naturalSizes);
        return sizeNode(domain, options);
    }

    /**
     * Extra body-mesh candidates from the ctor-chain triples, each emitted as its own geometry. A
     * candidate on a distinct factory (pufferfish) registers that factory; one on the primary factory
     * under a {@code MeshTransformer.scaling} factor (salmon) registers the same factory with the
     * captured scale, which the parser bakes into the mesh exactly as vanilla bakes its
     * {@code smallSalmonModel} / {@code largeSalmonModel} - so both are a mesh swap, never a
     * render-time scale.
     */
    private @Nullable JsonTree meshForm() {
        String primaryField = this.geometryRef.primaryFieldName();
        if (this.geometryRef.resolvedEntry() == null || primaryField == null) return null;
        List<String> domain = EntityAxisPolicies.SIZE_DOMAIN.strings();

        Map<String, LayerDefinitionIndex.Entry> candidates = new LinkedHashMap<>();
        for (String field : new LinkedHashSet<>(this.geometryRef.tripleSites())) {
            if (field.equals(primaryField)) continue;
            String option = field.substring(field.lastIndexOf('_') + 1).toLowerCase(Locale.ROOT);
            if (!domain.contains(option)) {
                this.diagnostics.info("extra body mesh ModelLayers.%s outside the P28 domain - not a size option", field);
                continue;
            }
            LayerDefinitionIndex.Entry entry = this.layerDefinitions.get(field);
            if (entry != null) candidates.put(option, entry);
        }
        if (candidates.isEmpty()) {
            this.diagnostics.warn("P37 declares a size axis but no domain-suffixed body meshes resolved");
            return null;
        }

        Map<String, JsonTree> options = new LinkedHashMap<>();
        for (Map.Entry<String, LayerDefinitionIndex.Entry> candidate : candidates.entrySet()) {
            LayerDefinitionIndex.Entry entry = candidate.getValue();
            String key = this.manifest.register(GeometryRequest.shape(
                    entry.factoryClass(), entry.factoryMethod(), this.subject.entityId(),
                    entry.texWidthOverride(), entry.texHeightOverride(),
                    entry.floatParam(), entry.grow(), entry.appliedMeshTransformerScale())
                .withBabyTransform(entry.appliedBabyTransform()));
            options.put(candidate.getKey(), JsonTree.object().put("geometry", key));
        }
        this.diagnostics.info("size axis via P37 membership: options %s", options.keySet());
        return sizeNode(domain, options);
    }

    /**
     * Assembles the node: the non-default delta options in size-domain order, option-less default (the
     * base mesh the family {@code geometry} already renders). The domain lives in the size-axis policy,
     * not a per-family {@code values} list.
     *
     * <p>The default is the <b>last</b> option-less member rather than the first, which matters only
     * for a family that fills fewer than two of the three: the armor stand's one option is
     * {@code small}, and the mesh its {@code geometry} already renders is the full-size one rather
     * than a middling one. Every family filling two options has exactly one member left, so the two
     * readings agree on all of them.
     */
    private static @NotNull JsonTree sizeNode(@NotNull List<String> domain, @NotNull Map<String, JsonTree> options) {
        String dflt = domain.getLast();
        for (String member : domain.reversed())
            if (!options.containsKey(member)) {
                dflt = member;
                break;
            }
        JsonTree node = JsonTree.object().put("default", dflt);
        JsonTree optionsNode = node.child("options");
        for (String member : domain) {
            JsonTree option = options.get(member);
            if (option != null) optionsNode.put(member, option);
        }
        return node;
    }

}
