package lib.minecraft.renderer.tooling.entity;

import lib.minecraft.renderer.tooling.geometry.GeometryManifest;
import lib.minecraft.renderer.tooling.geometry.GeometryRequest;
import lib.minecraft.renderer.tooling.kernel.AsmKit;
import lib.minecraft.renderer.tooling.kernel.ClassNodeCache;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.tooling.kernel.VanillaSourceClasses;
import lib.minecraft.renderer.tooling.vanilla.LayerDefinitionIndex;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Node {@code axes.shape} - the option-encoded body-shape axis (tropical_fish small / large,
 * 26.1's only multi-shape entity). Detection is the generic multi-body-model renderer probe:
 * the ctor references a second body mesh ({@code TROPICAL_FISH_LARGE}) beyond the primary;
 * membership + option naming are declared via {@link EntityAxisPolicies#SHAPE_SIZE_MEMBERSHIP};
 * the domain is the fixed {@code [small, large]} pair. The large texture is read from the
 * renderer's {@code <clinit>} {@code LARGE_TEXTURE} static - no string surgery.
 *
 * <p>The family's pattern overlays are re-registered against the large mesh: each row's
 * geometry swaps to the large body key and its texture stem swaps from the small body's stem
 * to the large's - both stems derived from the {@code <clinit>} texture and the family
 * texture, with the swap existence-probed against the jar, so no blind string surgery
 * survives.
 */
final class EntityShapeAxisResolver {

    /** The fixed shape domain. */
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
     * The shape node, or {@code null} when the entity is not a shape-axis member (or the
     * declared membership derives no second body mesh).
     *
     * @param adultTexture the family's resolved texture (the small-stem source), or {@code null}
     * @param overlays the family's resolved {@code overlays} rows to clone, or {@code null}
     * @return the node, or {@code null} to omit
     */
    @Nullable JsonTree resolve(@Nullable String adultTexture, @Nullable JsonTree overlays) {
        if (!"shape".equals(EntityAxisPolicies.shapeSizeAxisFor(this.subject.entityId()))) return null;
        String primaryField = this.geometryRef.primaryFieldName();
        if (primaryField == null) return null;

        JsonTree options = null;
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
            String optionTexture = optionClinitTexture(option);
            JsonTree body = JsonTree.object().put("geometry", key)
                .putIf("texture", optionTexture);
            body.putIf("overlays", cloneOverlays(overlays, key, adultTexture, optionTexture));
            if (options == null) options = JsonTree.object();
            options.put(option, body);
            this.diagnostics.info("shape axis: option '%s' mesh ModelLayers.%s -> %s [D2]", option, field, key);
        }
        if (options == null) {
            this.diagnostics.warn("P37 declares a shape axis but no second body mesh resolved");
            return null;
        }

        JsonTree node = JsonTree.object().put("default", DOMAIN.getFirst());
        node.put("options", options);
        return node;
    }

    /**
     * Clones the family's overlay rows onto the shape option: the family-geometry
     * reference swaps to the option's mesh key, a texture sharing the
     * family texture's stem swaps to the option stem when the swapped path exists in the
     * jar; every other member copies verbatim. {@code null} when there is nothing to clone.
     */
    private @Nullable JsonTree cloneOverlays(
        @Nullable JsonTree overlays,
        @NotNull String optionKey,
        @Nullable String familyTexture,
        @Nullable String optionTexture
    ) {
        if (overlays == null) return null;
        String familyKey = this.geometryRef.primaryKey();
        String familyStem = textureStem(familyTexture);
        String optionStem = textureStem(optionTexture);
        JsonTree out = null;
        for (JsonTree row : overlays.elements().toList()) {
            JsonTree clone = JsonTree.object();
            for (var member : row.members().toList()) {
                if ("geometry".equals(member.getKey()) && Objects.equals(row.findString("geometry").orElse(null), familyKey)) {
                    clone.put("geometry", optionKey);
                    continue;
                }
                String texture = "texture".equals(member.getKey()) ? row.findString("texture").orElse(null) : null;
                if (texture != null && familyStem != null && optionStem != null && texture.startsWith(familyStem)) {
                    String swapped = optionStem + texture.substring(familyStem.length());
                    String rawPath = swapped.substring(VanillaSourceClasses.Paths.MINECRAFT_NAMESPACE.length());
                    if (this.cache.hasEntry(VanillaSourceClasses.Paths.ASSETS_ROOT + rawPath)) {
                        clone.put("texture", swapped);
                        continue;
                    }
                    this.diagnostics.warn("shape clone texture '%s' missing from the jar - original kept", swapped);
                }
                clone.put(member.getKey(), member.getValue());
            }
            if (out == null) out = JsonTree.array();
            out.add(clone);
        }
        return out;
    }

    /** The {@code .png}-stripped stem of a namespaced texture path, or {@code null}. */
    private static @Nullable String textureStem(@Nullable String texture) {
        return texture == null || !texture.endsWith(".png")
            ? null
            : texture.substring(0, texture.length() - ".png".length());
    }

    /**
     * The renderer's {@code <clinit>}-bound texture whose static field is named after the
     * option ({@code LARGE_TEXTURE} for {@code large}), as a full namespaced path;
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
