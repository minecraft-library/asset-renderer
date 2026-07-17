package lib.minecraft.renderer.pipeline.loader;

import dev.simplified.collection.Concurrent;
import lib.minecraft.renderer.pipeline.pack.Capability;
import lib.minecraft.renderer.pipeline.pack.MCMeta;
import lib.minecraft.renderer.pipeline.pack.PackContainer;
import lib.minecraft.renderer.pipeline.pack.PackId;
import lib.minecraft.renderer.pipeline.pack.PackRoot;
import lib.minecraft.renderer.pipeline.pack.PackStack;
import lib.minecraft.renderer.pipeline.pack.ResourcePack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

/**
 * Exercises the shadowed-model diagnostic: a non-vanilla pack shipping a
 * vanilla-form {@code models/block/<id>.json} or {@code blockstates/<id>.json} for a block-entity-backed
 * id is named and pointed at the override channel, while the vanilla pack and non-block-entity ids stay
 * silent (byte-neutral - the pass emits only stderr).
 */
@DisplayName("BlockEntityShadowDiagnostics shadowed-model warnings")
class BlockEntityShadowDiagnosticsTest {

    @TempDir
    Path tmp;

    @Test
    @DisplayName("a non-vanilla pack shipping a model for a block-entity id is named + pointed at the channel")
    void warnsOnShadowedModel() throws IOException {
        Path pack = tmp.resolve("shadowy");
        write(pack.resolve("assets/minecraft/models/block/chest.json"), "{}");

        String err = captureReport(stack(pack), Set.of("minecraft:chest"));
        assertThat(err, containsString("shadowy"));
        assertThat(err, containsString("minecraft:chest"));
        assertThat(err, containsString("renderer/block_models.json"));
    }

    @Test
    @DisplayName("a non-vanilla pack shipping a blockstate for a block-entity id also warns")
    void warnsOnShadowedBlockstate() throws IOException {
        Path pack = tmp.resolve("shadowy");
        write(pack.resolve("assets/minecraft/blockstates/bell.json"), "{}");

        String err = captureReport(stack(pack), Set.of("minecraft:bell"));
        assertThat(err, containsString("blockstate"));
        assertThat(err, containsString("minecraft:bell"));
    }

    @Test
    @DisplayName("the vanilla pack is never diagnosed for shipping a block-entity model")
    void vanillaNeverWarns() throws IOException {
        Path vanilla = tmp.resolve("vanilla");
        write(vanilla.resolve("assets/minecraft/models/block/chest.json"), "{}");

        String err = captureReport(PackStack.of(Concurrent.newList(pack(PackId.VANILLA, vanilla))), Set.of("minecraft:chest"));
        assertThat(err.isEmpty(), is(true));
    }

    @Test
    @DisplayName("a pack shipping a non-block-entity model is not diagnosed")
    void nonBlockEntityModelSilent() throws IOException {
        Path pack = tmp.resolve("normalpack");
        write(pack.resolve("assets/minecraft/models/block/stone.json"), "{}");

        String err = captureReport(stack(pack), Set.of("minecraft:chest"));
        assertThat(err.isEmpty(), is(true));
    }

    private static String captureReport(PackStack stack, Set<String> beIds) {
        PrintStream original = System.err;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(buffer, true, StandardCharsets.UTF_8));
            BlockEntityShadowDiagnostics.report(stack, beIds);
        } finally {
            System.setErr(original);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private PackStack stack(Path userPackRoot) {
        List<ResourcePack> packs = new ArrayList<>();
        packs.add(pack(PackId.VANILLA, tmp.resolve("vanilla")));
        packs.add(pack(new PackId(userPackRoot.getFileName().toString()), userPackRoot));
        return PackStack.of(Concurrent.adoptList(packs).toUnmodifiable());
    }

    private static ResourcePack pack(PackId id, Path root) {
        return new ResourcePack(id, new PackContainer.Directory(root), MCMeta.EMPTY,
            Concurrent.newList(PackRoot.BASE).toUnmodifiable(), Set.of("minecraft"), Set.of(Capability.VANILLA_CORE));
    }

    private static void write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

}
