package lib.minecraft.renderer.pipeline.pack;

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
import static org.hamcrest.Matchers.is;

/**
 * Verifies {@link PackAcquisition} over synthetic directory packs: renderer-target overlay activation
 * (decision 1 - match the game format, not the pack's own), namespace discovery across active roots,
 * capability detection, and the provenance sidecar. A directory source materializes in place, so no
 * extraction is exercised here (the real-pack integration test covers that).
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
        PackStack stack = PackAcquisition.acquire(List.of(user), cache, vanilla);

        assertThat(stack.size(), is(2));
        assertThat(stack.vanilla().id(), is(PackId.VANILLA));

        ResourcePack mypack = stack.byId(new PackId("mypack")).orElseThrow();
        assertThat("only the format-matching, on-disk overlay activates",
            mypack.roots(), contains(PackRoot.BASE, PackRoot.overlay("ov_hi")));
        assertThat(mypack.namespaces(), containsInAnyOrder("minecraft", "testns"));
        assertThat(mypack.capabilities(), containsInAnyOrder(Capability.VANILLA_CORE, Capability.OPTIFINE_RULES));

        // directory source materializes in place; provenance is written beside the cache packs dir
        assertThat(Files.isRegularFile(cache.resolve("packs/mypack.provenance.json")), is(true));
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

}
