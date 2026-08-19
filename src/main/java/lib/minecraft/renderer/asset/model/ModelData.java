package lib.minecraft.renderer.asset.model;

import dev.simplified.annotations.EqualsAndHashCode;
import dev.simplified.annotations.Getter;
import dev.simplified.annotations.NoArgsConstructor;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.collection.ConcurrentSet;
import dev.simplified.image.pixel.PixelBuffer;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * The fully-resolved block or item model parsed from a vanilla JSON file under
 * {@code assets/minecraft/models/block/*.json} or {@code .../item/*.json}.
 * <p>
 * By the time an instance lives inside a {@code Block} or {@code Item}, every reference to a parent
 * has been walked and deep-merged so the textures map and elements list already contain everything
 * needed to render. No lazy resolution happens at render time.
 * <p>
 * Block and item models share the same shape ({@code textures}, {@code elements},
 * {@code display}), plus the block-domain {@link #ambientocclusion} flag, which defaults to a no-op
 * for item models.
 */
@Getter
@NoArgsConstructor
@EqualsAndHashCode
public class ModelData {

    /**
     * Whether the model should receive ambient occlusion during rendering. Defaults to
     * {@code true}, which matches vanilla for most solid blocks. Block-domain field.
     */
    private boolean ambientocclusion = true;

    /**
     * Texture variable bindings: {@code "#top" -> "minecraft:block/grass_block_top"} for blocks,
     * {@code "layer0" -> "minecraft:item/diamond_sword"} for items. Both authored shapes parse to a
     * {@link ModelTexture}: a bare string to {@code (sprite, false)}, the 26.1 object form
     * ({@code {sprite, force_translucent}}) to a value carrying the flag.
     */
    private @NotNull ConcurrentMap<String, ModelTexture> textures = Concurrent.newMap();

    /**
     * The list of element boxes that make up the model (empty for layered flat items).
     */
    private @NotNull ConcurrentList<ModelElement> elements = Concurrent.newList();

    /**
     * Display transforms keyed by display slot: {@code gui}, {@code head}, {@code thirdperson_righthand}, etc.
     */
    private @NotNull ConcurrentMap<String, ModelTransform> display = Concurrent.newMap();

    /**
     * Whether this model would render nothing: no element face resolves to a concrete texture, and
     * no renderable texture binding (a sprite {@code layerN} for items, or any non-{@code particle}
     * binding for blocks) is concrete either.
     * <p>
     * This is the derived replacement for the hand-curated {@code TEMPLATE_*_NAMES} lists: a model is
     * a template iff it would render blank. Concrete variant models ({@code block/acacia_slab_top},
     * {@code item/clock_00}, the armor-trim items, the multipart submodels) all carry a resolvable
     * texture and are kept; pure inheritance parents ({@code item/generated}, {@code block/cross},
     * {@code block/slab}, {@code item/air}) carry only unresolved {@code #variable} references and are
     * dropped.
     * <p>
     * Face references are dereferenced against {@link #textures} via
     * {@link #resolveTextureReference}; a result still starting with {@code #} is an unresolved
     * {@code #variable} (a parent-template placeholder) and does not count as renderable. The
     * {@code particle} binding is ignored for blocks because it never draws on a face. The second
     * check keeps degenerate-but-textured models (a block whose geometry comes from a default cube
     * rather than an explicit {@code elements} array).
     *
     * @param item whether this is an item model ({@code layerN} sprites) or a block model
     * @return whether the model renders nothing
     */
    public boolean rendersNothing(boolean item) {
        for (ModelElement element : this.elements) {
            for (ModelFace face : element.getFaces().values()) {
                String ref = face.getTexture();
                if (ref.isBlank()) continue;
                if (!resolveTextureReference(ref).startsWith("#")) return false;
            }
        }

        for (Map.Entry<String, ModelTexture> binding : this.textures.entrySet()) {
            ModelTexture value = binding.getValue();
            if (value == null || value.sprite().startsWith("#")) continue;
            String key = binding.getKey();
            if (item ? key.startsWith("layer") : !key.equals("particle")) return false;
        }
        return true;
    }

    /**
     * Walks a {@code #variable} chain through this model's {@link #textures} bindings until it
     * terminates at a concrete namespaced id or fails to resolve. Handles bare variable names
     * (vanilla shorthand where {@code "texture": "all"} means {@code "texture": "#all"}).
     * Cycle-guarded so a malformed pack cannot hang the caller.
     *
     * @param reference the texture reference, possibly starting with {@code #}
     * @return the resolved namespaced texture id, or the last unresolvable {@code #variable}
     */
    public @NotNull String resolveTextureReference(@NotNull String reference) {
        String current = reference;

        if (!current.startsWith("#") && !current.contains(":") && this.textures.containsKey(current))
            current = "#" + current;

        ConcurrentSet<String> visited = Concurrent.newSet();
        while (current.startsWith("#")) {
            if (!visited.add(current)) return current;
            ModelTexture next = this.textures.get(current.substring(1));
            if (next == null) return current;
            current = next.sprite();
        }

        return current;
    }

    /**
     * Resolves and loads every unique face texture referenced by this model's elements into a map
     * keyed by the raw {@link ModelFace#getTexture()} reference (including any leading {@code #}).
     * <p>
     * Walks each element's faces, dereferences the {@code #variable} chain via
     * {@link #resolveTextureReference}, skips refs that stay unresolved ({@code #}-prefixed) or
     * blank, and loads each concrete id through the supplied {@code resolve} function exactly
     * once. The caller chooses how a concrete id becomes a {@link PixelBuffer} - block paths pass
     * a tick-aware {@code id -> Optional.of(resolveTextureAtTick(id, 0))}, the entity path passes
     * the context's {@code Optional}-returning lookup - so this never decides the resolution
     * strategy. Refs whose {@code resolve} yields an empty {@link Optional} are dropped, leaving
     * the kit to treat them as no-texture faces.
     *
     * @param resolve maps a concrete namespaced texture id to its pixel buffer, or empty to skip
     * @return a new map from raw face ref to its loaded pixel buffer
     */
    public @NotNull ConcurrentMap<String, PixelBuffer> loadElementFaceTextures(
        @NotNull Function<String, Optional<PixelBuffer>> resolve
    ) {
        ConcurrentMap<String, PixelBuffer> faceTextures = Concurrent.newMap();
        for (ModelElement element : this.elements) {
            for (ModelFace face : element.getFaces().values()) {
                String ref = face.getTexture();
                if (ref.isBlank() || faceTextures.containsKey(ref)) continue;
                String resolvedId = resolveTextureReference(ref);
                if (resolvedId.startsWith("#")) continue;
                resolve.apply(resolvedId).ifPresent(buffer -> faceTextures.put(ref, buffer));
            }
        }
        return faceTextures;
    }

    /**
     * Resolves which of this model's element face refs are force-translucent - the raw
     * {@link ModelFace#getTexture()} refs whose texture variable carried {@code force_translucent} in
     * the 26.1 object form. Keys match {@link #loadElementFaceTextures} exactly (the raw ref including
     * any leading {@code #}) and the {@code #variable} chain is walked as in
     * {@link #resolveTextureReference}, so a returned ref is the same key
     * {@code BlockGeometryKit.buildFromElements} looks up. A face is force-translucent when any
     * variable in its deref chain is flagged.
     *
     * @return the raw face refs to force into the translucent pass, empty when nothing is flagged
     */
    public @NotNull ConcurrentSet<String> resolveForceTranslucentRefs() {
        ConcurrentSet<String> refs = Concurrent.newSet();
        if (this.textures.values().stream().noneMatch(ModelTexture::forceTranslucent)) return refs;

        for (ModelElement element : this.elements) {
            for (ModelFace face : element.getFaces().values()) {
                String ref = face.getTexture();
                if (ref.isBlank() || refs.contains(ref)) continue;
                if (isForceTranslucent(ref)) refs.add(ref);
            }
        }
        return refs;
    }

    /**
     * Walks the {@code #variable} chain of a face ref, reporting whether any hop resolves to a
     * texture flagged {@code force_translucent}.
     *
     * @param reference the raw face-texture ref, possibly starting with {@code #}
     * @return {@code true} when any variable in the chain carried {@code force_translucent}
     */
    private boolean isForceTranslucent(@NotNull String reference) {
        String current = reference;
        if (!current.startsWith("#") && !current.contains(":") && this.textures.containsKey(current))
            current = "#" + current;

        ConcurrentSet<String> visited = Concurrent.newSet();
        while (current.startsWith("#")) {
            if (!visited.add(current)) return false;
            ModelTexture texture = this.textures.get(current.substring(1));
            if (texture == null) return false;
            if (texture.forceTranslucent()) return true;
            current = texture.sprite();
        }
        return false;
    }

}
