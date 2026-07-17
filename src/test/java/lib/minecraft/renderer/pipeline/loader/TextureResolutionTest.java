package lib.minecraft.renderer.pipeline.loader;

import lib.minecraft.renderer.pipeline.pack.TextureIndexer;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.Concurrent;
import dev.simplified.image.ImageFactory;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.pipeline.pack.Capability;
import lib.minecraft.renderer.pipeline.pack.MCMeta;
import lib.minecraft.renderer.pipeline.pack.PackContainer;
import lib.minecraft.renderer.pipeline.pack.PackId;
import lib.minecraft.renderer.pipeline.pack.PackRoot;
import lib.minecraft.renderer.pipeline.pack.PackStack;
import lib.minecraft.renderer.pipeline.pack.ResolvedTexture;
import lib.minecraft.renderer.pipeline.pack.ResourcePack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import javax.imageio.ImageIO;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Exercises the multi-namespace texture index and the {@link PackStack} resolution rule: cross-pack
 * priority, within-pack overlay precedence, the same-pack sidecar binding, {@code filter.block}
 * erasure, and the namespace-first / pack-restricted dispatch.
 */
@DisplayName("Texture index + resolution rule")
class TextureResolutionTest {

    @TempDir
    static Path tmp;

    private static PackStack stack;

    @BeforeAll
    static void build() throws IOException {
        // vanilla: block/stone (+animation sidecar), block/dirt (+animation sidecar)
        Path van = tmp.resolve("vanilla");
        png(van.resolve("assets/minecraft/textures/block/stone.png"), 16, 16);
        sidecar(van.resolve("assets/minecraft/textures/block/stone.png.mcmeta"), 7);
        png(van.resolve("assets/minecraft/textures/block/dirt.png"), 16, 16);
        sidecar(van.resolve("assets/minecraft/textures/block/dirt.png.mcmeta"), 3);
        ResourcePack vanilla = pack(PackId.VANILLA, van, Set.of("minecraft"), Concurrent.newList(PackRoot.BASE));

        // hypixel-skyblock: overrides block/stone (NO sidecar - drops vanilla's animation),
        // adds hypixel_skyblock:item/sword, and ships block/grass in base + overlay (overlay wins).
        Path hyp = tmp.resolve("hyp");
        png(hyp.resolve("assets/minecraft/textures/block/stone.png"), 32, 32);
        png(hyp.resolve("assets/hypixel_skyblock/textures/item/sword.png"), 8, 8);
        png(hyp.resolve("assets/minecraft/textures/block/grass.png"), 16, 16);
        png(hyp.resolve("ov/assets/minecraft/textures/block/grass.png"), 16, 16);
        ConcurrentList<PackRoot> hypRoots = Concurrent.newList(PackRoot.BASE, PackRoot.overlay("ov"));
        ResourcePack hypixel = pack(new PackId("hypixel-skyblock"), hyp, Set.of("minecraft", "hypixel_skyblock"), hypRoots);

        PackStack bare = PackStack.of(Concurrent.newList(vanilla, hypixel));
        stack = bare.withTextureIndex(TextureIndexer.index(bare));
    }

    @Test
    @DisplayName("scans every namespace, not just minecraft")
    void multiNamespaceScan() {
        assertThat(stack.textureIndex().size(), is(4)); // stone, dirt, grass, sword
        assertThat(stack.indexed(new ResourceId("hypixel_skyblock", "item/sword")).isPresent(), is(true));
        assertThat(stack.indexed(new ResourceId("minecraft", "block/grass")).isPresent(), is(true));
    }

    @Test
    @DisplayName("higher-priority pack wins a cross-pack id collision")
    void crossPackPriority() {
        ResolvedTexture stone = stack.indexed(ResourceId.parse("minecraft:block/stone")).orElseThrow();
        assertThat(stone.pack(), is(new PackId("hypixel-skyblock")));
        assertThat(new ImageFactory().fromByteArray(stone.bytes()).toPixelBuffer().width(), is(32));

        ResolvedTexture resolved = stack.resolve(ResourceId.parse("minecraft:block/stone")).orElseThrow();
        assertThat(resolved.pack(), is(new PackId("hypixel-skyblock")));
        assertThat(resolved.path(), is("assets/minecraft/textures/block/stone.png"));
    }

    @Test
    @DisplayName("a PNG-only override drops the lower pack's sidecar; an un-overridden texture keeps it (D4)")
    void sidecarSamePackBinding() {
        assertThat("hypixel overrides stone without a sidecar",
            stack.indexed(ResourceId.parse("minecraft:block/stone")).orElseThrow().meta().isPresent(), is(false));

        Optional<MCMeta> dirtMeta = stack.indexed(ResourceId.parse("minecraft:block/dirt")).orElseThrow().meta();
        assertThat("vanilla dirt keeps its own sidecar", dirtMeta.isPresent(), is(true));
        assertThat(dirtMeta.orElseThrow().animation().orElseThrow().frametime(), is(3));
    }

    @Test
    @DisplayName("within a pack the last existing root wins (overlay over base)")
    void overlayLastWins() {
        ResolvedTexture grass = stack.resolve(ResourceId.parse("minecraft:block/grass")).orElseThrow();
        assertThat(grass.path(), is("ov/assets/minecraft/textures/block/grass.png"));
    }

    @Test
    @DisplayName("namespace-branch resolve returns the owning pack; missing ids resolve to empty")
    void resolveDispatch() {
        ResolvedTexture dirt = stack.resolve(ResourceId.parse("minecraft:block/dirt")).orElseThrow();
        assertThat(dirt.pack(), is(PackId.VANILLA));
        assertThat(stack.resolve(ResourceId.parse("minecraft:block/absent")).isPresent(), is(false));
        assertThat(stack.resolve(ResourceId.parse("unknownns:block/x")).isPresent(), is(false));
    }

    @Test
    @DisplayName("resolveIn restricts to one pack and probes its namespaces (primary first)")
    void resolveInPackRestricted() {
        PackId hyp = new PackId("hypixel-skyblock");
        // The pack supplies minecraft:block/stone directly.
        assertThat(stack.resolveIn(hyp, ResourceId.parse("minecraft:block/stone")).isPresent(), is(true));
        // A bare path probes the primary namespace (hypixel_skyblock) and finds item/sword.
        ResolvedTexture sword = stack.resolveIn(hyp, ResourceId.parse("item/sword")).orElseThrow();
        assertThat(sword.id().namespace(), is("hypixel_skyblock"));
        // Vanilla does not supply the sword at all.
        assertThat(stack.resolveIn(PackId.VANILLA, ResourceId.parse("item/sword")).isPresent(), is(false));
    }

    @Test
    @DisplayName("filter.block erases matching rows from lower packs at index build (D11)")
    void filterBlockErasure() throws IOException {
        Path base = tmp.resolve("filter-base");
        png(base.resolve("assets/minecraft/textures/block/stone.png"), 16, 16);
        png(base.resolve("assets/minecraft/textures/block/dirt.png"), 16, 16);
        ResourcePack lower = pack(PackId.VANILLA, base, Set.of("minecraft"), Concurrent.newList(PackRoot.BASE));

        // A higher pack whose pack.mcmeta hides block/dirt from every lower pack.
        Path top = tmp.resolve("filter-top");
        Files.createDirectories(top.resolve("assets/minecraft"));
        MCMeta filtering = MCMeta.parse(
            "{\"pack\":{\"pack_format\":84},\"filter\":{\"block\":[{\"path\":\"block/dirt\"}]}}",
            new ResourceId("filterpack", "pack"));
        ResourcePack filterPack = new ResourcePack(new PackId("filterpack"),
            new PackContainer.Directory(top), filtering, Concurrent.newList(PackRoot.BASE),
            Set.of("minecraft"), Set.of(Capability.VANILLA_CORE));

        PackStack bare = PackStack.of(Concurrent.newList(lower, filterPack));
        PackStack filtered = bare.withTextureIndex(TextureIndexer.index(bare));

        assertThat(filtered.indexed(ResourceId.parse("minecraft:block/stone")).isPresent(), is(true));
        assertThat("dirt hidden by filter.block",
            filtered.indexed(ResourceId.parse("minecraft:block/dirt")).isPresent(), is(false));
    }

    @Test
    @DisplayName("index row path is the baked winning root-prefixed container path (overlay winner)")
    void indexedPathIsBaked() {
        assertThat(stack.indexed(ResourceId.parse("minecraft:block/grass")).orElseThrow().path(),
            equalTo("ov/assets/minecraft/textures/block/grass.png"));
    }

    private static @org.jetbrains.annotations.NotNull ResourcePack pack(PackId id, Path root, Set<String> namespaces,
                                                                        ConcurrentList<PackRoot> roots) {
        return new ResourcePack(id, new PackContainer.Directory(root), MCMeta.EMPTY,
            roots.toUnmodifiable(), namespaces, Set.of(Capability.VANILLA_CORE));
    }

    private static void png(Path path, int width, int height) throws IOException {
        Files.createDirectories(path.getParent());
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "PNG", path.toFile());
    }

    private static void sidecar(Path path, int frametime) throws IOException {
        Files.writeString(path, "{\"animation\":{\"frametime\":" + frametime + "}}");
    }

}
