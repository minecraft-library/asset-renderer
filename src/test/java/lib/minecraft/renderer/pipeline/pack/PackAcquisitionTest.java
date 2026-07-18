package lib.minecraft.renderer.pipeline.pack;

import dev.simplified.collection.Concurrent;
import lib.minecraft.renderer.pipeline.ClientAssets;
import lib.minecraft.renderer.pipeline.ClientOptions;
import lib.minecraft.renderer.asset.pack.Capability;
import lib.minecraft.renderer.asset.pack.PackId;
import lib.minecraft.renderer.asset.pack.PackRoot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;

/**
 * Verifies {@link PackAcquisition} over synthetic directory packs: renderer-target overlay activation
 * (match the game format, not the pack's own), namespace discovery across active roots, and capability
 * detection. Acquisition is virtual - a directory source is served in place through its
 * {@link PackContainer.Directory}, with no extraction or cache copy.
 */
@DisplayName("PackAcquisition over synthetic directory packs")
class PackAcquisitionTest {

    @Test
    @DisplayName("overlays activate against the renderer target; namespaces and capabilities are detected")
    void acquireDirectoryPack(@TempDir Path dir) throws IOException {
        Path vanilla = dir.resolve("vanilla");
        write(vanilla.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":84}}");
        write(vanilla.resolve("assets/minecraft/textures/block/stone.png"), "png");

        Path user = dir.resolve("mypack");
        write(user.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":84},\"overlays\":{\"entries\":["
            + "{\"directory\":\"ov_hi\",\"formats\":[84,84]},"
            + "{\"directory\":\"ov_lo\",\"formats\":[1,1]},"
            + "{\"directory\":\"ov_gone\",\"formats\":[84,84]}]}}");
        write(user.resolve("assets/minecraft/optifine/color.properties"), "grass.plains=0x00ff00");
        write(user.resolve("assets/testns/textures/item/x.png"), "png");
        write(user.resolve("ov_hi/assets/minecraft/textures/block/stone.png"), "png"); // active overlay
        write(user.resolve("ov_lo/assets/minecraft/textures/block/dirt.png"), "png");   // format 1 -> inactive
        // ov_gone is declared but never created on disk -> inactive

        Path cache = dir.resolve("cache");
        ClientOptions options = ClientOptions.builder()
            .cacheRoot(cache.toFile())
            .texturePacks(Concurrent.adoptList(List.of(user.toFile())))
            .build();
        PackStack stack = PackAcquisition.acquire(new ClientAssets(options, vanilla));

        assertThat(stack.size(), is(2));
        assertThat(stack.vanilla().id(), is(PackId.VANILLA));

        ResourcePack mypack = stack.byId(new PackId("mypack")).orElseThrow();
        assertThat("only the format-matching, on-disk overlay activates",
            mypack.roots(), contains(PackRoot.BASE, PackRoot.overlay("ov_hi")));
        assertThat(mypack.namespaces(), containsInAnyOrder("minecraft", "testns"));
        assertThat(mypack.capabilities(), containsInAnyOrder(Capability.VANILLA_CORE, Capability.OPTIFINE_RULES));

        // directory source is served in place (virtual - no extraction, no cache copy)
        assertThat(mypack.container(), is(org.hamcrest.Matchers.instanceOf(PackContainer.Directory.class)));
        assertThat(((PackContainer.Directory) mypack.container()).root(), is(user));
    }

    @Test
    @DisplayName("Catharsis fabric:overlays activate against config.catharsis.json defaults for a CATHARSIS pack")
    void acquireCatharsisPack(@TempDir Path dir) throws IOException {
        Path vanilla = dir.resolve("vanilla");
        write(vanilla.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":84}}");
        write(vanilla.resolve("assets/minecraft/textures/block/stone.png"), "png");

        Path user = dir.resolve("catpack");
        write(user.resolve("pack.mcmeta"), "{\"pack\":{\"min_format\":69,\"max_format\":255},\"fabric:overlays\":{\"entries\":["
            + "{\"directory\":\"block_ore\",\"condition\":{\"condition\":\"catharsis:config\",\"pack\":\"catpack\",\"id\":\"block.ore\",\"value\":\"on\"}},"
            + "{\"directory\":\"theme_dark\",\"condition\":{\"condition\":\"catharsis:config\",\"pack\":\"catpack\",\"id\":\"theme.dark\",\"value\":\"on\"}},"
            + "{\"directory\":\"never_built\",\"condition\":{\"condition\":\"catharsis:config\",\"pack\":\"catpack\",\"id\":\"block.ore\",\"value\":\"on\"}}]}}");
        // config.catharsis.json: block.ore on by default, theme.dark off -> only block_ore activates.
        write(user.resolve("config.catharsis.json"), "[{\"type\":\"tab\",\"elements\":["
            + "{\"type\":\"boolean\",\"id\":\"block.ore\",\"default\":true},"
            + "{\"type\":\"boolean\",\"id\":\"theme.dark\",\"default\":false}]}]");
        write(user.resolve("assets/skyblock/items/adaptive_boots.json"), "{}"); // a CATHARSIS signal + a discoverable item def
        write(user.resolve("block_ore/assets/minecraft/textures/block/stone.png"), "png");  // condition holds + on disk -> active
        write(user.resolve("theme_dark/assets/minecraft/textures/block/dirt.png"), "png");  // condition fails -> inactive
        // never_built: condition holds but the directory is not on disk -> inactive

        ClientOptions options = ClientOptions.builder()
            .cacheRoot(dir.resolve("cache").toFile())
            .texturePacks(Concurrent.adoptList(List.of(user.toFile())))
            .build();
        PackStack stack = PackAcquisition.acquire(new ClientAssets(options, vanilla));
        ResourcePack catpack = stack.byId(new PackId("catpack")).orElseThrow();

        assertThat(catpack.capabilities(), hasItem(Capability.CATHARSIS_CONVENTIONS));
        assertThat("only the config-active, on-disk Catharsis overlay stacks over the base root",
            catpack.roots(), contains(PackRoot.BASE, PackRoot.overlay("block_ore")));
    }

    @Test
    @DisplayName("a Catharsis pack detected only via the mcmeta catharsis:pack section activates overlays from its mcmeta config")
    void catharsisDetectedViaMcmetaOnly(@TempDir Path dir) throws IOException {
        Path vanilla = dir.resolve("vanilla");
        write(vanilla.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":84}}");
        write(vanilla.resolve("assets/minecraft/textures/block/stone.png"), "png");

        Path user = dir.resolve("catpack");
        // No config.catharsis.json and no assets/skyblock/items: both detection AND the config defaults
        // must come from the mcmeta (catharsis:pack/v1 section + fabric:overlays catharsis condition).
        write(user.resolve("pack.mcmeta"), "{\"pack\":{\"min_format\":69,\"max_format\":255},"
            + "\"catharsis:pack/v1\":{\"config\":[{\"type\":\"boolean\",\"id\":\"block.ore\",\"default\":true}]},"
            + "\"fabric:overlays\":{\"entries\":[{\"directory\":\"block_ore\",\"condition\":{\"condition\":\"catharsis:config\",\"id\":\"block.ore\",\"value\":\"on\"}}]}}");
        write(user.resolve("block_ore/assets/minecraft/textures/block/stone.png"), "png");

        ClientOptions options = ClientOptions.builder()
            .cacheRoot(dir.resolve("cache").toFile())
            .texturePacks(Concurrent.adoptList(List.of(user.toFile())))
            .build();
        PackStack stack = PackAcquisition.acquire(new ClientAssets(options, vanilla));
        ResourcePack catpack = stack.byId(new PackId("catpack")).orElseThrow();
        assertThat("detected via the mcmeta signal (no path signal present)", catpack.capabilities(), hasItem(Capability.CATHARSIS_CONVENTIONS));
        assertThat("overlay activated from the mcmeta catharsis:pack/v1.config fallback",
            catpack.roots(), contains(PackRoot.BASE, PackRoot.overlay("block_ore")));
    }

    @Test
    @DisplayName("a syntactically-malformed config.catharsis.json degrades to no overlays without breaking acquisition")
    void catharsisMalformedConfigDegrades(@TempDir Path dir) throws IOException {
        Path vanilla = dir.resolve("vanilla");
        write(vanilla.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":84}}");
        write(vanilla.resolve("assets/minecraft/textures/block/stone.png"), "png");

        Path user = dir.resolve("catpack");
        // Valid pack.mcmeta + fabric:overlays (a catharsis:* condition is itself a detection signal),
        // but the config.catharsis.json is garbage - the overlay evaluation must degrade, never throw.
        write(user.resolve("pack.mcmeta"), "{\"pack\":{\"min_format\":69,\"max_format\":255},\"fabric:overlays\":{\"entries\":["
            + "{\"directory\":\"block_ore\",\"condition\":{\"condition\":\"catharsis:config\",\"id\":\"block.ore\",\"value\":\"on\"}}]}}");
        write(user.resolve("config.catharsis.json"), "{ this is : not, valid json ]");
        write(user.resolve("block_ore/assets/minecraft/textures/block/stone.png"), "png");

        ClientOptions options = ClientOptions.builder()
            .cacheRoot(dir.resolve("cache").toFile())
            .texturePacks(Concurrent.adoptList(List.of(user.toFile())))
            .build();
        PackStack stack = PackAcquisition.acquire(new ClientAssets(options, vanilla)); // must not throw
        ResourcePack catpack = stack.byId(new PackId("catpack")).orElseThrow();
        assertThat("mcmeta fabric:overlays catharsis condition is a detection signal", catpack.capabilities(), hasItem(Capability.CATHARSIS_CONVENTIONS));
        assertThat("no config defaults -> the condition fails -> no overlays activate", catpack.roots(), contains(PackRoot.BASE));
    }

    @Test
    @DisplayName("an illegal overlay directory whose condition holds degrades without breaking acquisition")
    void catharsisIllegalOverlayDirectoryDegrades(@TempDir Path dir) throws IOException {
        Path vanilla = dir.resolve("vanilla");
        write(vanilla.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":84}}");
        write(vanilla.resolve("assets/minecraft/textures/block/stone.png"), "png");

        Path user = dir.resolve("catpack");
        // The condition holds (block.ore default on), but the directory carries a NUL char, so
        // packRoot.resolve(directory) throws InvalidPathException - acquisition must degrade, not abort.
        write(user.resolve("pack.mcmeta"), "{\"pack\":{\"min_format\":69,\"max_format\":255},\"fabric:overlays\":{\"entries\":["
            + "{\"directory\":\"\\u0000bad\",\"condition\":{\"condition\":\"catharsis:config\",\"id\":\"block.ore\",\"value\":\"on\"}}]}}");
        write(user.resolve("config.catharsis.json"), "[{\"type\":\"boolean\",\"id\":\"block.ore\",\"default\":true}]");
        write(user.resolve("assets/skyblock/items/foo.json"), "{}");

        ClientOptions options = ClientOptions.builder()
            .cacheRoot(dir.resolve("cache").toFile())
            .texturePacks(Concurrent.adoptList(List.of(user.toFile())))
            .build();
        PackStack stack = PackAcquisition.acquire(new ClientAssets(options, vanilla)); // must not throw
        ResourcePack catpack = stack.byId(new PackId("catpack")).orElseThrow();
        assertThat("illegal overlay directory is skipped, base root survives", catpack.roots(), contains(PackRoot.BASE));
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

}
