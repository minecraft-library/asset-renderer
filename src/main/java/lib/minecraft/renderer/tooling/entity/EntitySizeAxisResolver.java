package lib.minecraft.renderer.tooling.entity;

import lib.minecraft.renderer.tooling.geometry.GeometryManifest;
import lib.minecraft.renderer.tooling.geometry.GeometryRequest;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lib.minecraft.renderer.json.JsonNode;
import lib.minecraft.renderer.tooling.vanilla.LayerDefinitionIndex;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Node {@code axes.size} - the option-encoded size axis, with two mutually-exclusive
 * option forms (the mesh-select XOR scale contract):
 *
 * <ul>
 *   <li><b>mesh-select</b> - pufferfish small / medium meshes derived from the renderer
 *       ctor's multiple {@code ModelLayers.PUFFERFISH_*} references (big = primary);
 *       each option registers its own {@code GeometryRequest}.</li>
 *   <li><b>scale</b> - salmon 0.5 / 1.5 read off the {@code SALMON_SMALL} /
 *       {@code SALMON_LARGE} index entries' {@code MeshTransformer.scaling} factors
 *       (same factory coordinate as the primary = a scale rider, never a second mesh);
 *       slime / magma_cube 2.0 / 4.0 proportional to their natural-size set (scale is
 *       size-proportional per {@code SlimeRenderer.scale} at squish 0).</li>
 * </ul>
 *
 * <p>Membership is declared per entity (pufferfish / salmon vs. slime / magma_cube); the
 * concrete meshes / factors are derived. Option names come from the candidate field's
 * suffix matched against the size domain ({@code PUFFERFISH_MEDIUM} to {@code medium});
 * default = the option-less domain member; option members emit in domain order. The
 * scale axis is a visual no-op under the auto-fit renderer - it is wired for a future
 * absolute-scale scene renderer.
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
    @Nullable JsonNode resolve() {
        List<Integer> naturalSizes = EntityAxisPolicies.naturalSizesFor(this.subject.entityId());
        if (naturalSizes != null) return naturalSizeForm(naturalSizes);
        if (!"size".equals(EntityAxisPolicies.shapeSizeAxisFor(this.subject.entityId()))) return null;
        return meshOrScaleForm();
    }

    /**
     * Builds scale options proportional to the natural sizes (base = the first,
     * option-less), named into the size domain positionally.
     */
    private @Nullable JsonNode naturalSizeForm(@NotNull List<Integer> naturalSizes) {
        List<String> domain = EntityAxisPolicies.SIZE_DOMAIN.strings();
        if (naturalSizes.size() > domain.size()) {
            this.diagnostics.warn("P1 natural-size set %s exceeds the P28 domain %s - size axis omitted", naturalSizes, domain);
            return null;
        }
        Map<String, JsonNode> options = new LinkedHashMap<>();
        int base = naturalSizes.getFirst();
        for (int index = 1; index < naturalSizes.size(); index++)
            options.put(domain.get(index), JsonNode.object().put("scale", (float) naturalSizes.get(index) / base));
        this.diagnostics.info("size axis via P1 natural sizes %s (scale-per-size proportional [D5])", naturalSizes);
        return sizeNode(domain, options);
    }

    /**
     * Extra body-mesh candidates from the ctor-chain triples, classified by the
     * mesh-select XOR scale contract against the primary's factory coordinate.
     */
    private @Nullable JsonNode meshOrScaleForm() {
        LayerDefinitionIndex.Entry primary = this.geometryRef.resolvedEntry();
        String primaryField = this.geometryRef.primaryFieldName();
        if (primary == null || primaryField == null) return null;
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

        Map<String, JsonNode> options = new LinkedHashMap<>();
        for (Map.Entry<String, LayerDefinitionIndex.Entry> candidate : candidates.entrySet()) {
            LayerDefinitionIndex.Entry entry = candidate.getValue();
            boolean sameFactory = entry.factoryClass().equals(primary.factoryClass())
                && entry.factoryMethod().equals(primary.factoryMethod());
            if (sameFactory) {
                // Scale form: the same mesh under an external MeshTransformer factor.
                float scale = entry.appliedMeshTransformerScale() / primary.appliedMeshTransformerScale();
                options.put(candidate.getKey(), JsonNode.object().put("scale", scale));
                continue;
            }
            String key = this.manifest.register(GeometryRequest.shape(
                entry.factoryClass(), entry.factoryMethod(), this.subject.entityId(),
                entry.texWidthOverride(), entry.texHeightOverride(),
                entry.floatParam(), entry.grow(), entry.appliedMeshTransformerScale()));
            options.put(candidate.getKey(), JsonNode.object().put("geometry", key));
        }
        this.diagnostics.info("size axis via P37 membership: options %s", options.keySet());
        return sizeNode(domain, options);
    }

    /**
     * Assembles the node: the non-default delta options in size-domain order, option-less default (the
     * base mesh the family {@code geometry} already renders). The domain lives in the size-axis policy,
     * not a per-family {@code values} list.
     */
    private static @NotNull JsonNode sizeNode(@NotNull List<String> domain, @NotNull Map<String, JsonNode> options) {
        String dflt = domain.getFirst();
        for (String member : domain)
            if (!options.containsKey(member)) {
                dflt = member;
                break;
            }
        JsonNode node = JsonNode.object().put("default", dflt);
        JsonNode optionsNode = node.child("options");
        for (String member : domain) {
            JsonNode option = options.get(member);
            if (option != null) optionsNode.put(member, option);
        }
        return node;
    }

}
