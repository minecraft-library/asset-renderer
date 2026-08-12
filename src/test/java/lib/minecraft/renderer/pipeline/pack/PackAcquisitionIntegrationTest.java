package lib.minecraft.renderer.pipeline.pack;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.PackStack;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.asset.model.ModelData;
import lib.minecraft.renderer.asset.pack.PackCapability;
import lib.minecraft.renderer.asset.pack.PackContainer;
import lib.minecraft.renderer.asset.pack.PackId;
import lib.minecraft.renderer.asset.pack.ResolvedTexture;
import lib.minecraft.renderer.asset.pack.ResourcePack;
import lib.minecraft.renderer.asset.pack.item.ItemModelTree;
import lib.minecraft.renderer.client.ClientAssets;
import lib.minecraft.renderer.client.ClientOptions;
import lib.minecraft.renderer.pipeline.pack.item.ItemModelTreeLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.SortedSet;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * An acquisition of the real vanilla pack plus the on-disk user packs (defrosted, hypixel-skyblock,
 * eureka.cats.zip) into a {@link PackStack} - the id heuristic, namespace discovery, capability
 * detection and virtual {@code .cats} byte access end to end, plus what a third-party namespace
 * contributes to the texture index, the model index and the item-definition trees. Tagged slow because
 * it reads the gitignored pack cache; skips gracefully when absent.
 *
 * <p>Every count asserted here is a smoke floor rather than a corpus size: it says the walk reached
 * the pack at all, and sits far below what either pack ships so a content update cannot red it.
 *
 * <p>{@code VANILLA} and {@code PACKS} name the 26.1 cache directly, so an MC bump turns every test in
 * this class into a silent skip. The degradation is intended, and it means a green run of this class is
 * not by itself evidence that any assertion here ran.
 */
@Tag("slow")
@DisplayName("PackAcquisition over the real on-disk packs")
class PackAcquisitionIntegrationTest {

    private static final Path VANILLA = Path.of("cache/asset-renderer/vanilla/26.1");
    private static final Path PACKS = Path.of("cache/asset-renderer/packs");
    private static final String HYPIXEL_NS = "hypixel_skyblock";

    @Test
    @DisplayName("vanilla + defrosted + hypixel-skyblock + eureka acquire with the right ids, namespaces, and capabilities")
    void acquireRealPacks(@TempDir Path cache) {
        assumeTrue(Files.isDirectory(VANILLA), () -> "vanilla pack not extracted: " + VANILLA);
        Path defrosted = PACKS.resolve("defrosted");
        Path hypixel = PACKS.resolve("hypixel-skyblock");
        Path eureka = PACKS.resolve("eureka.cats.zip");
        assumeTrue(Files.isDirectory(defrosted) && Files.isDirectory(hypixel) && Files.isRegularFile(eureka),
            "sample packs not present under " + PACKS);

        List<Path> sources = new ArrayList<>(List.of(defrosted, hypixel, eureka));
        ClientOptions options = ClientOptions.builder()
            .cacheRoot(cache.toFile())
            .texturePacks(Concurrent.adoptList(sources.stream().map(Path::toFile).toList()))
            .build();
        PackStack stack = PackAcquisition.acquire(new ClientAssets(options, VANILLA));

        assertThat(stack.size(), is(4));

        // vanilla base pack
        ResourcePack vanilla = stack.vanilla();
        assertThat(vanilla.id(), is(PackId.VANILLA));
        assertThat(vanilla.namespaces(), hasItem("minecraft"));
        assertThat(vanilla.has(PackCapability.VANILLA_CORE), is(true));

        // defrosted: kebab dir id, three namespaces, optifine capability
        ResourcePack def = stack.byId(new PackId("defrosted")).orElseThrow();
        assertThat(def.namespaces(), hasItems("minecraft", "lunar", "skybox"));
        // namespaces() returns a sorted set (not a salted Set.copyOf), so primaryNamespace()'s findFirst
        // and the paletted-permutation iteration resolve the same winner on every JVM run.
        assertThat(def.namespaces(), is(instanceOf(SortedSet.class)));
        assertThat(new ArrayList<>(def.namespaces()), is(def.namespaces().stream().sorted().toList()));
        assertThat(def.has(PackCapability.OPTIFINE_RULES), is(true));
        assertThat(def.has(PackCapability.CATHARSIS_CONVENTIONS), is(false));

        // hypixel-skyblock: kebab dir / snake namespace duality, plain vanilla-core only
        ResourcePack hyp = stack.byId(new PackId("hypixel-skyblock")).orElseThrow();
        assertThat(hyp.namespaces(), hasItems("minecraft", HYPIXEL_NS));
        assertThat(hyp.primaryNamespace().orElseThrow(), is(HYPIXEL_NS));
        assertThat(hyp.has(PackCapability.OPTIFINE_RULES), is(false));
        assertThat(hyp.has(PackCapability.CATHARSIS_CONVENTIONS), is(false));

        // eureka: .cats filename id, catharsis capability, served virtually from its .cats container
        // (no extraction to disk - the render path reads bytes straight from the archive).
        ResourcePack eur = stack.byId(new PackId("eureka")).orElseThrow();
        assertThat(eur.has(PackCapability.CATHARSIS_CONVENTIONS), is(true));
        assertThat(eur.container(), is(instanceOf(PackContainer.Cats.class)));
        assertThat("the pack mcmeta is reachable through the container without extraction",
            eur.container().bytes("pack.mcmeta").isPresent(), is(true));
    }

    @Test
    @DisplayName("the texture index spans the vanilla and user-pack namespaces")
    void indexesUserPackTextures(@TempDir Path cache) {
        assumeTrue(Files.isDirectory(VANILLA), () -> "vanilla pack not extracted: " + VANILLA);
        Path defrosted = PACKS.resolve("defrosted");
        Path hypixel = PACKS.resolve("hypixel-skyblock");
        assumeTrue(Files.isDirectory(defrosted) && Files.isDirectory(hypixel),
            "sample packs not present under " + PACKS);

        List<Path> sources = new ArrayList<>(List.of(defrosted, hypixel));
        ClientOptions options = ClientOptions.builder()
            .cacheRoot(cache.toFile())
            .texturePacks(Concurrent.adoptList(sources.stream().map(Path::toFile).toList()))
            .build();
        PackStack stack = PackAcquisition.acquire(new ClientAssets(options, VANILLA));
        ConcurrentMap<ResourceId, ResolvedTexture> index = TextureIndexer.index(stack);

        // Vanilla alone catalogues > 500 textures; the user packs override some and add their own.
        assertThat(index.size(), is(greaterThan(500)));
        assertThat("vanilla namespace present",
            index.keySet().stream().anyMatch(id -> id.namespace().equals("minecraft")), is(true));
        assertThat(HYPIXEL_NS + " namespace present",
            index.keySet().stream().anyMatch(id -> id.namespace().equals(HYPIXEL_NS)), is(true));
    }

    @Test
    @DisplayName("a third-party namespace's item models, item-definition trees and block-item projection all resolve")
    void thirdPartyNamespaceContent(@TempDir Path cache) {
        assumeTrue(Files.isDirectory(VANILLA), () -> "vanilla pack not extracted: " + VANILLA);
        Path hypixel = PACKS.resolve("hypixel-skyblock");
        assumeTrue(Files.isDirectory(hypixel), () -> "hypixel-skyblock pack not present under " + PACKS);

        ClientOptions options = ClientOptions.builder()
            .cacheRoot(cache.toFile())
            .texturePacks(Concurrent.adoptList(List.of(hypixel.toFile())))
            .build();
        PackStack stack = PackAcquisition.acquire(new ClientAssets(options, VANILLA));

        ConcurrentMap<String, ModelData> itemModels = ResolvedModels.load(stack).items();
        assertThat(HYPIXEL_NS + " item models indexed by qualified id",
            inNamespace(itemModels.keySet()), is(greaterThan(1000L)));

        // Deeply nested ids in a non-minecraft namespace all reach the item-tree walker.
        ConcurrentMap<String, ItemModelTree> trees = ItemModelTreeLoader.load(stack);
        assertThat(HYPIXEL_NS + " item-definition trees parsed", inNamespace(trees.keySet()), is(greaterThan(900L)));
        assertThat(trees.containsKey(HYPIXEL_NS + ":item/abiphones/abiphone_basic"), is(true));

        // Every hypixel model ref is an item model, so the block-item projection takes none of them.
        ConcurrentMap<String, String> blockItems = ItemModelTreeLoader.deriveBlockItemModels(trees);
        assertThat("the pack ships no block items, so none project",
            inNamespace(blockItems.keySet()), is(0L));
    }

    private static long inNamespace(Collection<String> ids) {
        return ids.stream().filter(id -> id.startsWith(HYPIXEL_NS + ":")).count();
    }

}
