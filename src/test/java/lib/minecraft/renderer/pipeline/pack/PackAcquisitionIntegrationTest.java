package lib.minecraft.renderer.pipeline.pack;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.PackStack;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.asset.pack.PackCapability;
import lib.minecraft.renderer.asset.pack.PackContainer;
import lib.minecraft.renderer.asset.pack.PackId;
import lib.minecraft.renderer.asset.pack.ResolvedTexture;
import lib.minecraft.renderer.asset.pack.ResourcePack;
import lib.minecraft.renderer.pipeline.ClientAssets;
import lib.minecraft.renderer.pipeline.ClientOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration test acquiring the real vanilla pack plus the on-disk user packs (defrosted,
 * hypixel-skyblock, eureka.cats.zip) into a {@link PackStack}. Pins the id heuristic, namespace
 * discovery, capability detection, and virtual {@code .cats} byte access end to end. Tagged slow
 * because it reads the gitignored pack cache; skips gracefully when absent.
 */
@Tag("slow")
@DisplayName("PackAcquisition over the real on-disk packs")
class PackAcquisitionIntegrationTest {

    private static final Path VANILLA = Path.of("cache/asset-renderer/vanilla/26.1");
    private static final Path PACKS = Path.of("cache/asset-renderer/packs");

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
        assertThat(def.has(PackCapability.OPTIFINE_RULES), is(true));
        assertThat(def.has(PackCapability.CATHARSIS_CONVENTIONS), is(false));

        // hypixel-skyblock: kebab dir / snake namespace duality, plain vanilla-core only
        ResourcePack hyp = stack.byId(new PackId("hypixel-skyblock")).orElseThrow();
        assertThat(hyp.namespaces(), hasItems("minecraft", "hypixel_skyblock"));
        assertThat(hyp.primaryNamespace().orElseThrow(), is("hypixel_skyblock"));
        assertThat(hyp.has(PackCapability.OPTIFINE_RULES), is(false));
        assertThat(hyp.has(PackCapability.CATHARSIS_CONVENTIONS), is(false));

        // eureka: .cats filename id, catharsis capability, served virtually from its .cats container
        // (no extraction to disk - the render path reads bytes straight from the archive).
        ResourcePack eur = stack.byId(new PackId("eureka")).orElseThrow();
        assertThat(eur.has(PackCapability.CATHARSIS_CONVENTIONS), is(true));
        assertThat(eur.container(), is(org.hamcrest.Matchers.instanceOf(PackContainer.Cats.class)));
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
        assertThat("hypixel_skyblock namespace present",
            index.keySet().stream().anyMatch(id -> id.namespace().equals("hypixel_skyblock")), is(true));
    }

}
