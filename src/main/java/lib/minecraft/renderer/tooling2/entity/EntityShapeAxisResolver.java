package lib.minecraft.renderer.tooling2.entity;

import lib.minecraft.renderer.tooling2.geometry.GeometryManifest;
import lib.minecraft.renderer.tooling2.geometry.GeometryRequest;
import lib.minecraft.renderer.tooling2.kernel.AsmKit;
import lib.minecraft.renderer.tooling2.kernel.ClassNodeCache;
import lib.minecraft.renderer.tooling2.kernel.Diagnostics;
import lib.minecraft.renderer.tooling2.kernel.JsonNode;
import lib.minecraft.renderer.tooling2.kernel.VanillaSourceClasses;
import lib.minecraft.renderer.tooling2.vanilla.LayerDefinitionIndex;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Node {@code axes.shape} - the option-encoded body-shape axis (SPINE 3.1 row 12;
 * tropical_fish small / large, 26.1's only multi-shape entity). Detection is the generic
 * multi-body-model renderer probe [D2]: the ctor references a second body mesh
 * ({@code TROPICAL_FISH_LARGE}) beyond the primary; membership + option naming are declared
 * (P37, {@link EntityAxisPolicies#SHAPE_SIZE_MEMBERSHIP}); the domain is the fixed
 * {@code [small, large]} pair (doc 06 SS3.12). The large texture is READ from the
 * renderer's {@code <clinit>} {@code LARGE_TEXTURE} static [D41] - no string surgery.
 *
 * <p>The family's pattern overlays are re-registered against the large mesh in the legacy
 * form (clone semantics) - that clone rides the overlay engine (roster row 13) and lands
 * with it; until then the option carries geometry + texture only.
 */
final class EntityShapeAxisResolver {

    /** The fixed shape domain (doc 06 SS3.12; option naming = P37). */
    private static final @NotNull List<String> DOMAIN = List.of("small", "large");

    private final @NotNull ClassNodeCache cache;
    private final @NotNull EntitySubject subject;
    private final @NotNull LayerDefinitionIndex layerDefinitions;
    private final @NotNull EntityGeometryRefResolver geometryRef;
    private final @NotNull GeometryManifest manifest;
    private final @NotNull Diagnostics diagnostics;

    EntityShapeAxisResolver(
        @NotNull ClassNodeCache cache,
        @NotNull EntitySubject subject,
        @NotNull LayerDefinitionIndex layerDefinitions,
        @NotNull EntityGeometryRefResolver geometryRef,
        @NotNull GeometryManifest manifest,
        @NotNull Diagnostics diagnostics
    ) {
        this.cache = cache;
        this.subject = subject;
        this.layerDefinitions = layerDefinitions;
        this.geometryRef = geometryRef;
        this.manifest = manifest;
        this.diagnostics = diagnostics;
    }

    /**
     * The shape node, or {@code null} when the entity is not a P37 shape member (or the
     * declared membership derives no second body mesh).
     *
     * @return the node, or {@code null} to omit
     */
    @Nullable JsonNode resolve() {
        if (!"shape".equals(EntityAxisPolicies.shapeSizeAxisFor(this.subject.entityId()))) return null;
        String primaryField = this.geometryRef.primaryFieldName();
        if (primaryField == null) return null;

        JsonNode options = null;
        for (String field : new LinkedHashSet<>(this.geometryRef.tripleSites())) {
            if (field.equals(primaryField)) continue;
            String option = field.substring(field.lastIndexOf('_') + 1).toLowerCase(Locale.ROOT);
            if (!DOMAIN.contains(option) || DOMAIN.getFirst().equals(option)) continue;
            LayerDefinitionIndex.Entry entry = this.layerDefinitions.get(field);
            if (entry == null) continue;
            String key = this.manifest.register(GeometryRequest.shape(
                entry.factoryClass(), entry.factoryMethod(), this.subject.entityId(),
                entry.texWidthOverride(), entry.texHeightOverride(),
                entry.floatParam(), entry.grow(), entry.appliedMeshTransformerScale()));
            JsonNode body = JsonNode.object().put("geometry", key)
                .putIf("texture", optionClinitTexture(option));
            if (options == null) options = JsonNode.object();
            options.put(option, body);
            this.diagnostics.info("shape axis: option '%s' mesh ModelLayers.%s -> %s [D2]", option, field, key);
        }
        if (options == null) {
            this.diagnostics.warn("P37 declares a shape axis but no second body mesh resolved");
            return null;
        }

        JsonNode node = JsonNode.object().put("default", DOMAIN.getFirst());
        JsonNode values = node.childArray("values");
        for (String member : DOMAIN) values.add(member);
        node.put("options", options);
        return node;
    }

    /**
     * The renderer's {@code <clinit>}-bound texture whose static field is named after the
     * option ({@code LARGE_TEXTURE} for {@code large}) [D41], as a full namespaced path;
     * {@code null} when no such binding exists.
     */
    private @Nullable String optionClinitTexture(@NotNull String option) {
        ClassNode cn = this.cache.load(this.subject.rendererClass());
        MethodNode clinit = cn == null ? null : AsmKit.findMethod(cn, AsmKit.CLINIT);
        if (clinit == null) return null;
        String wantedPrefix = option.toUpperCase(Locale.ROOT);
        String pendingPath = null;
        for (AbstractInsnNode in = clinit.instructions.getFirst(); in != null; in = in.getNext()) {
            String literal = AsmKit.readStringLiteral(in);
            if (literal != null && literal.startsWith(VanillaSourceClasses.Paths.TEXTURES_ENTITY)) {
                pendingPath = literal;
                continue;
            }
            if (in instanceof FieldInsnNode fi
                && AsmKit.isPutStatic(in, cn.name)
                && VanillaSourceClasses.Descs.IDENTIFIER_REF.equals(fi.desc)
                && fi.name.startsWith(wantedPrefix)
                && pendingPath != null)
                return VanillaSourceClasses.Paths.MINECRAFT_NAMESPACE + pendingPath;
        }
        return null;
    }

}
