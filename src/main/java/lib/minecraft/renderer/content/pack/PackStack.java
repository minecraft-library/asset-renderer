package lib.minecraft.renderer.content.pack;

import dev.simplified.annotations.AccessLevel;
import dev.simplified.annotations.Getter;
import dev.simplified.annotations.NamingStyle;
import dev.simplified.annotations.RequiredArgsConstructor;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.collection.ConcurrentSet;
import dev.simplified.image.ImageFactory;
import dev.simplified.image.exception.ImageException;
import dev.simplified.image.pixel.PixelBuffer;
import dev.simplified.util.Possible;
import lib.minecraft.renderer.asset.pack.Flipbook;
import lib.minecraft.renderer.asset.pack.MCMeta;
import lib.minecraft.renderer.asset.pack.PackFiles;
import lib.minecraft.renderer.asset.pack.PackRoot;
import lib.minecraft.renderer.asset.pack.ResourcePack;
import lib.minecraft.renderer.asset.rule.RuleSet;
import lib.minecraft.renderer.exception.ContentException;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.vanilla.id.PackId;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import org.intellij.lang.annotations.PrintFormat;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * The ordered resource-pack stack: vanilla at priority 0, then user packs in ascending priority,
 * higher winning. Owns the texture index and the resolution rule the renderer consults - a
 * namespace-first, pack-id-second dispatch over {@code namespace:path} ids, followed by a within-pack
 * root walk in which the last existing copy wins.
 *
 * <p>Acquisition assembles the stack without a texture index ({@link #of}); the pipeline scans the
 * stack into an index and re-derives the stack through {@link #withTextureIndex}. Both spellings share
 * the pack list, id map, and namespace union; only the index differs.
 *
 * <p>A winning file is chosen by its existence alone, as vanilla's resource manager chooses one, so a
 * file that cannot be read shadows a lower pack's copy and is served with no pixels. A served texture
 * cannot be read where its bytes are empty or do not decode, where its sidecar is there and does not
 * parse, or where its sidecar's animation has a frame size that does not divide the strip on both
 * axes - the three failures on which vanilla's sprite loader draws its missing sprite.
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
@Parity(claim = "asset-layer")
public final class PackStack {

    /**
     * Every pack in ascending-priority order (vanilla first, higher priority later).
     */
    @Getter(style = NamingStyle.FLUENT)
    private final @NotNull ConcurrentList<ResourcePack> ascending;

    private final @NotNull ConcurrentMap<PackId, ResourcePack> byId;

    /**
     * The union of every pack's namespaces, in the order the ascending stack first supplies each.
     */
    @Getter(style = NamingStyle.FLUENT)
    private final @NotNull ConcurrentSet<String> namespaces;

    /**
     * The scanned texture index, keyed by resolved namespaced id.
     */
    @Getter(style = NamingStyle.FLUENT)
    private final @NotNull ConcurrentMap<ResourceId, ResolvedTexture> textureIndex;

    /**
     * The merged pack rule payload the renderer consults - CIT rules, CTM rules, the colour overrides
     * of the highest pack shipping a {@code color.properties}, and the global glint policy, folded
     * across the stack at acquisition time.
     */
    @Getter(style = NamingStyle.FLUENT)
    private final @NotNull RuleSet rules;

    private final @NotNull Set<String> loggedAmbiguities = ConcurrentHashMap.newKeySet();

    private final @NotNull ImageFactory imageFactory = new ImageFactory();

    /**
     * Per-stack memoisation cache of decoded {@link PixelBuffer}s keyed by the resolved
     * {@code (PackId, ResourceId)}, populated lazily on the first {@link #pixels(ResourceId)} of each
     * texture, so a stack-wide and a pack-restricted lookup that land on the same file share one buffer.
     */
    private final @NotNull ConcurrentMap<PixelKey, PixelBuffer> pixelCache = Concurrent.newMap();

    /**
     * The resolved {@code (PackId, ResourceId)} keys of the textures a decode found and could not read -
     * an empty file or one that is not an image, a sidecar that does not parse, or an animation whose
     * frame size does not divide the strip - kept beside {@link #pixelCache} so a broken texture is read
     * and reported once, and answered empty from then on.
     */
    private final @NotNull ConcurrentSet<PixelKey> undecodable = Concurrent.newSet();

    /**
     * Per-stack memoisation cache of resolved {@link Flipbook}s on the same
     * {@code (PackId, ResourceId)} key the decoded pixels take, populated lazily on the first
     * {@link #flipbook(ResourceId)} of each indexed texture. A served texture that plays nothing caches
     * its empty answer, so a static id costs one map hit per lookup rather than a repeated miss.
     */
    private final @NotNull ConcurrentMap<PixelKey, Possible<Flipbook>> flipbookCache = Concurrent.newMap();

    /**
     * Builds a stack from packs in ascending-priority order; the first must be the vanilla pack. The
     * texture index starts empty - {@link #withTextureIndex} attaches it once the pipeline has scanned
     * the stack.
     *
     * @param ascending the packs, vanilla first, then user packs ascending
     * @return the assembled stack
     * @throws ContentException if the stack is empty or does not lead with the vanilla pack
     */
    public static @NotNull PackStack of(@NotNull ConcurrentList<ResourcePack> ascending) {
        if (ascending.isEmpty())
            throw new ContentException("Pack stack is empty; the vanilla pack is required at priority 0");
        if (!ascending.getFirst().id().equals(PackId.VANILLA))
            throw new ContentException("Pack stack must lead with the vanilla pack, got '%s'", ascending.getFirst().id());

        ConcurrentMap<PackId, ResourcePack> byId = ascending.stream()
            .collect(Concurrent.toUnmodifiableLinkedMap(ResourcePack::id, pack -> pack, (a, b) -> b));
        ConcurrentSet<String> namespaces = ascending.stream()
            .flatMap(pack -> pack.namespaces().stream())
            .collect(Concurrent.toUnmodifiableLinkedSet());
        return new PackStack(ascending, byId, namespaces, Concurrent.newMap(), RuleSet.empty(PackId.VANILLA));
    }

    /**
     * Returns a copy of this stack carrying the given texture index.
     *
     * @param index the scanned texture index, keyed by namespaced id
     * @return the indexed stack
     */
    public @NotNull PackStack withTextureIndex(@NotNull ConcurrentMap<ResourceId, ResolvedTexture> index) {
        return new PackStack(this.ascending, this.byId, this.namespaces, index, this.rules);
    }

    /**
     * Returns a copy of this stack carrying the given merged rule set.
     *
     * @param rules the merged pack rules (CIT / CTM / colour overrides / glint)
     * @return the stack carrying the rules
     */
    public @NotNull PackStack withRules(@NotNull RuleSet rules) {
        return new PackStack(this.ascending, this.byId, this.namespaces, this.textureIndex, rules);
    }

    /**
     * The vanilla base pack's on-disk root - the {@code <cacheRoot>/vanilla/<version>} directory the
     * client jar was extracted into.
     *
     * @return the vanilla pack root
     * @throws ContentException if the vanilla pack is not directory-backed
     */
    public @NotNull Path vanillaRoot() {
        if (vanilla().container() instanceof PackContainer.Directory dir) return dir.root();
        throw new ContentException("Vanilla pack '%s' is not directory-backed", vanilla().id());
    }

    /**
     * The vanilla base pack at priority 0.
     *
     * @return the vanilla pack
     */
    public @NotNull ResourcePack vanilla() {
        return this.ascending.getFirst();
    }

    /**
     * Looks up a pack by its id.
     *
     * @param id the pack id
     * @return the pack, or empty when no pack in the stack has that id
     */
    public @NotNull Optional<ResourcePack> byId(@NotNull PackId id) {
        return Optional.ofNullable(this.byId.get(id));
    }

    /**
     * The loaded pack ids.
     *
     * @return the set of pack ids in the stack
     */
    public @NotNull Set<PackId> packIds() {
        return this.byId.keySet();
    }

    /**
     * The number of packs in the stack, including vanilla.
     *
     * @return the pack count
     */
    public int size() {
        return this.ascending.size();
    }

    /**
     * Looks up the index row for an id directly (no dispatch, no root walk) - the metadata carrier the
     * animation-sidecar lookup reads.
     *
     * @param id the namespaced texture id
     * @return the index row, or empty when no pack indexed it
     */
    public @NotNull Optional<ResolvedTexture> indexed(@NotNull ResourceId id) {
        return Optional.ofNullable(this.textureIndex.get(id));
    }

    /**
     * Resolves a texture id to the winning pack's on-disk PNG, applying the namespace-first /
     * pack-id-second dispatch then the within-pack root walk.
     *
     * <p>When the id's prefix is a live namespace the content-addressed index decides the winner; when
     * it is instead a loaded pack id the lookup is restricted to that pack's namespaces in
     * primary-then-{@code minecraft}-then-sorted order. A prefix that is both logs an ambiguity once
     * and takes the namespace. An unknown prefix resolves to empty - no fallback.
     *
     * @param id the namespaced texture id
     * @return the resolved texture, or empty when nothing supplies it
     */
    public @NotNull Optional<ResolvedTexture> resolve(@NotNull ResourceId id) {
        return dispatch(id);
    }

    /**
     * Resolves a texture id restricted to one pack (the {@code resolveTexture(PackId, ResourceId)}
     * escape hatch), bypassing the namespace-first dispatch.
     *
     * @param pack the pack to restrict resolution to
     * @param id the namespaced texture id (its path is searched across the pack's namespaces)
     * @return the resolved texture, or empty when the pack does not supply it
     */
    public @NotNull Optional<ResolvedTexture> resolveIn(@NotNull PackId pack, @NotNull ResourceId id) {
        return byId(pack).flatMap(p -> probeInPack(p, id.name()));
    }

    /**
     * Resolves a texture id to its decoded {@link PixelBuffer}, memoising the decode on the resolved
     * {@code (PackId, ResourceId)} so a stack-wide and a pack-restricted lookup that land on the same
     * file share one buffer. Runs the namespace-first dispatch then decodes the winning PNG once; a
     * texture that cannot be read is remembered and answered empty from then on.
     *
     * @param id the namespaced texture id
     * @return the decoded texture; empty when the dispatch lands on a file whose bytes do not decode (an
     *     empty file included), whose sidecar does not parse, or whose animation's frame size does not
     *     divide it; absent when nothing supplies the id
     */
    public @NotNull Possible<PixelBuffer> pixels(@NotNull ResourceId id) {
        Optional<ResolvedTexture> indexed = indexed(id);
        if (indexed.isPresent()) {
            PixelBuffer cached = this.pixelCache.get(new PixelKey(indexed.get().pack(), id));
            if (cached != null) return Possible.of(cached);
        }
        return resolve(id).map(this::decode).orElseGet(Possible::absent);
    }

    /**
     * Resolves a texture id to the {@link Flipbook} its {@code .mcmeta} sidecar plays over its decoded
     * strip, memoising on the same {@code (PackId, ResourceId)} key the pixels take.
     * <p>
     * Gated on the dispatch, the way {@link #pixels} is. A texture the index holds plays the animation
     * section of the sidecar its row captured; a prefix that names a pack rather than a namespace
     * resolves with no index row and answers no sidecar, so it plays nothing.
     *
     * @param id the namespaced texture id
     * @return the resolved playback table; empty for a served texture that plays nothing - no sidecar,
     *     a sidecar that does not parse or holds no animation section, a strip that cannot be read, or a
     *     prefix naming a pack - and absent when the dispatch serves nothing
     */
    public @NotNull Possible<Flipbook> flipbook(@NotNull ResourceId id) {
        Optional<ResolvedTexture> indexed = indexed(id);
        if (indexed.isEmpty()) return resolve(id).isPresent() ? Possible.empty() : Possible.absent();

        PixelKey key = new PixelKey(indexed.get().pack(), id);
        Possible<Flipbook> cached = this.flipbookCache.get(key);
        if (cached != null) return cached;

        // No sidecar, one that does not parse, or one with no section plays nothing, and neither does a
        // strip that cannot be read - its empty answer passes through, so the table never outlives the
        // pixels it would play over.
        Possible<Flipbook> resolved = indexed.get().meta().orAbsent(Possible::empty)
            .flatMap(meta -> Possible.ofOptional(meta.animation()))
            .flatMap(animation -> pixels(id).flatMap(strip -> Possible.ofOptional(Flipbook.of(strip, animation))));
        this.flipbookCache.put(key, resolved);
        return resolved;
    }

    /**
     * Decodes a resolved texture, memoising on the resolved {@code (PackId, ResourceId)} key both the
     * pixels of a texture that can be read and the fact that one cannot.
     *
     * @param texture the winning file the dispatch resolved
     * @return the decoded pixels, or empty when the file's bytes are empty or do not decode, when its
     *     sidecar does not parse, or when its animation's frame size does not divide it
     */
    private @NotNull Possible<PixelBuffer> decode(@NotNull ResolvedTexture texture) {
        PixelKey key = new PixelKey(texture.pack(), texture.id());
        PixelBuffer cached = this.pixelCache.get(key);
        if (cached != null) return Possible.of(cached);
        if (this.undecodable.contains(key)) return Possible.empty();

        // A sidecar that does not parse loses its texture before the image is read, as vanilla's sprite
        // loader gives up on the metadata before it opens the PNG.
        if (texture.meta().getState() == Possible.State.EMPTY)
            return unreadable(key, "Unable to parse metadata from '%s' in pack '%s'%n", texture.id(), texture.pack());

        // Read before the try: an entry that vanished since the index scan is an I/O race rather than
        // a state of the file, so it raises.
        byte[] bytes = texture.bytes();
        PixelBuffer buffer;
        try {
            // PixelBuffer.wrap handles every BufferedImage layout the vanilla 1.21 pack ships -
            // INT_ARGB, INT_RGB, INT_BGR, 4BYTE_ABGR, 3BYTE_BGR, BYTE_INDEXED, BYTE_GRAY, BYTE_BINARY
            // (IndexColorModel), and TYPE_CUSTOM with ComponentColorModel of TYPE_GRAY (2-band
            // tRNS-keyed grayscale) - without applying the sRGB-gamma transform that would inflate
            // raw byte values on calibrated-gray sources.
            buffer = this.imageFactory.fromByteArray(bytes).toPixelBuffer();
        } catch (Exception ex) {
            // A zero-byte or non-image file raises ImageException; a truncated or corrupt PNG raises the
            // checked IIOException ImageIO throws, which the PNG reader rethrows undeclared - so the catch
            // names Exception, keeps those two, and passes anything else through unchanged.
            if (!(ex instanceof ImageException || ex instanceof IOException)) throw ex;
            return unreadable(key, "Unreadable texture '%s' in pack '%s' - %s%n", texture.id(), texture.pack(), ex);
        }

        // An animation is checked against the strip it plays over before the pixels are kept, so a strip
        // its frame size does not divide is decoded once and held nowhere.
        Optional<MCMeta.Animation> animation = texture.meta().toOptional().flatMap(MCMeta::animation);
        if (animation.isPresent()) {
            Flipbook.FrameSize frame = Flipbook.FrameSize.of(animation.get(), buffer);
            if (!frame.divides(buffer)) {
                return unreadable(key, "Image '%s' in pack '%s' size %d,%d is not multiple of frame size %d,%d%n",
                    texture.id(), texture.pack(), buffer.width(), buffer.height(), frame.width(), frame.height());
            }
        }

        this.pixelCache.put(key, buffer);
        return Possible.of(buffer);
    }

    /**
     * Records a texture that is there and cannot be read, reporting it the first time it is seen.
     *
     * @param key the resolved key the texture is remembered under
     * @param format the report's format string, naming why the texture cannot be read
     * @param args the report's arguments
     * @return the empty answer the texture is served as
     */
    private @NotNull Possible<PixelBuffer> unreadable(@NotNull PixelKey key, @PrintFormat @NotNull String format, @Nullable Object... args) {
        if (this.undecodable.add(key))
            System.err.printf(format, args);

        return Possible.empty();
    }

    /**
     * The namespace-first / pack-id-second dispatch: an index lookup or a live pack probe, both baked.
     */
    private @NotNull Optional<ResolvedTexture> dispatch(@NotNull ResourceId id) {
        String prefix = id.namespace();
        if (this.namespaces.contains(prefix)) {
            if (isLoadedPackId(prefix)) logAmbiguityOnce(prefix);
            return indexed(id);
        }
        if (isLoadedPackId(prefix))
            return byId(new PackId(prefix)).flatMap(pack -> probeInPack(pack, id.name()));
        return Optional.empty();
    }

    /**
     * Probes one pack for a texture path across its namespaces (primary, then minecraft, then sorted),
     * baking the within-pack root walk (base first, overlays after, last existing copy winning) and the
     * {@code .png.mcmeta} sidecar beside the winner - the same merged form the index carries.
     */
    private @NotNull Optional<ResolvedTexture> probeInPack(@NotNull ResourcePack pack, @NotNull String path) {
        PackFiles container = pack.container();
        for (String namespace : searchOrder(pack)) {
            String relativePath = pack.texturesDir(namespace) + "/" + path + ".png";
            String winning = null;
            for (PackRoot root : pack.roots()) {
                String candidate = root.prefix() + relativePath;
                if (container.exists(candidate)) winning = candidate;
            }
            if (winning != null)
                return Optional.of(ResolvedTexture.of(pack.id(), new ResourceId(namespace, path), container, winning));
        }
        return Optional.empty();
    }

    /**
     * The within-pack namespace search order: primary namespace, then {@code minecraft}, then the rest sorted.
     */
    private static @NotNull ConcurrentList<String> searchOrder(@NotNull ResourcePack pack) {
        return Stream.of(pack.primaryNamespace().stream(), Stream.of("minecraft"),
                pack.namespaces().stream().sorted())
            .flatMap(source -> source)
            .distinct()
            .collect(Concurrent.toUnmodifiableList());
    }

    private boolean isLoadedPackId(@NotNull String prefix) {
        return PackId.ALPHABET.matcher(prefix).matches() && this.byId.containsKey(new PackId(prefix));
    }

    private void logAmbiguityOnce(@NotNull String prefix) {
        if (this.loggedAmbiguities.add(prefix))
            System.err.printf("Resolution ambiguity: prefix '%s' is both a live namespace and a loaded pack id; "
                + "resolving as a namespace (use resolveIn for pack-restricted lookup)%n", prefix);
    }

    /**
     * The decoded-pixel cache key: the resolved pack plus the resolved texture id.
     */
    private record PixelKey(@NotNull PackId pack, @NotNull ResourceId id) {}

}
