package lib.minecraft.renderer.content.pack;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import lib.minecraft.renderer.asset.pack.MCMeta;
import lib.minecraft.renderer.asset.pack.PackCapability;
import lib.minecraft.renderer.asset.pack.PackRoot;
import lib.minecraft.renderer.asset.pack.ResourcePack;
import lib.minecraft.renderer.content.container.PackContainer;
import lib.minecraft.renderer.exception.ContentException;
import lib.minecraft.renderer.vanilla.id.PackId;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Coverage of {@link PackStack} assembly, and of the one {@link ResourcePack} accessor that is the sole
 * subject of a test here rather than a stack fixture: vanilla-first validation, id and namespace lookups,
 * primary-namespace resolution of the kebab/snake duality, and the base-first / last-wins overlay-root
 * stacking on an overlapping texture path.
 */
@DisplayName("PackStack assembly + ResourcePack accessors")
class PackStackTest {

    private static ResourcePack pack(PackId id, Set<String> namespaces, Set<PackCapability> capabilities) {
        ConcurrentList<PackRoot> roots = Concurrent.newList();
        roots.add(PackRoot.BASE);
        return new ResourcePack(id, new PackContainer.Directory(Path.of(id.value())), MCMeta.EMPTY,
                                roots.toUnmodifiable(), Concurrent.newUnmodifiableTreeSet(namespaces),
                                Concurrent.newUnmodifiableLinkedSet(capabilities));
    }

    @Test
    @DisplayName("of() requires a non-empty stack led by vanilla")
    void validation() {
        assertThrows(ContentException.class, () -> PackStack.of(Concurrent.newList()));

        ConcurrentList<ResourcePack> userFirst = Concurrent.newList();
        userFirst.add(pack(new PackId("eureka"), Set.of("minecraft"), Set.of(PackCapability.VANILLA_CORE)));
        assertThrows(ContentException.class, () -> PackStack.of(userFirst.toUnmodifiable()));
    }

    @Test
    @DisplayName("byId, namespaces union, and packIds reflect the whole stack")
    void lookups() {
        ConcurrentList<ResourcePack> ascending = Concurrent.newList();
        ascending.add(pack(PackId.VANILLA, Set.of("minecraft"), Set.of(PackCapability.VANILLA_CORE)));
        ascending.add(pack(new PackId("hypixel-skyblock"), Set.of("minecraft", "hypixel_skyblock"), Set.of(PackCapability.VANILLA_CORE)));
        PackStack stack = PackStack.of(ascending.toUnmodifiable());

        assertThat(stack.size(), is(2));
        assertThat(stack.vanilla().id(), is(PackId.VANILLA));
        assertThat(stack.byId(new PackId("hypixel-skyblock")).isPresent(), is(true));
        assertThat(stack.byId(new PackId("nope")), is(Optional.empty()));
        assertThat(stack.namespaces(), containsInAnyOrder("minecraft", "hypixel_skyblock"));
        assertThat(stack.packIds(), containsInAnyOrder(PackId.VANILLA, new PackId("hypixel-skyblock")));
    }

    @Test
    @DisplayName("primaryNamespace resolves the kebab-dir / snake-namespace duality")
    void primaryNamespace() {
        ResourcePack hypixel = pack(new PackId("hypixel-skyblock"), Set.of("minecraft", "hypixel_skyblock"), Set.of(PackCapability.VANILLA_CORE));
        assertThat(hypixel.primaryNamespace(), is(Optional.of("hypixel_skyblock")));

        ResourcePack defrosted = pack(new PackId("defrosted"), Set.of("minecraft", "lunar", "skybox"), Set.of());
        assertThat(defrosted.primaryNamespace(), is(Optional.empty()));
    }

    @Test
    @DisplayName("overlay roots stack base-first; the last-declared overlay wins an overlapping path")
    void overlayRootsLastWins(@TempDir Path root) throws IOException {
        // Base and two overlays all ship the same texture. The within-pack root walk keeps the last
        // existing copy, mirroring vanilla CompositePackResources (overlays reversed, first-present wins)
        // and Fabric fabric:overlays (entries appended in declaration order): every overlay outranks the
        // base, and the last-declared overlay outranks the earlier ones.
        String rel = "assets/minecraft/textures/x.png";
        write(root.resolve(rel), "base");
        write(root.resolve("ov_early/" + rel), "early");
        write(root.resolve("ov_late/" + rel), "late");

        ConcurrentList<PackRoot> roots = Concurrent.newList();
        roots.add(PackRoot.BASE);
        roots.add(PackRoot.overlay("ov_early"));
        roots.add(PackRoot.overlay("ov_late"));
        ResourcePack pack = new ResourcePack(PackId.VANILLA, new PackContainer.Directory(root), MCMeta.EMPTY,
                                             roots.toUnmodifiable(), Concurrent.newUnmodifiableTreeSet("minecraft"),
                                             Concurrent.newUnmodifiableLinkedSet(PackCapability.VANILLA_CORE));
        ConcurrentList<ResourcePack> ascending = Concurrent.newList();
        ascending.add(pack);
        PackStack stack = PackStack.of(ascending.toUnmodifiable());

        ResolvedTexture resolved = stack.resolveIn(PackId.VANILLA, new ResourceId("minecraft", "x")).orElseThrow();
        assertThat(resolved.path(), is("ov_late/" + rel));
        assertThat(new String(resolved.bytes(), StandardCharsets.UTF_8), is("late"));
    }

    @Test
    @DisplayName("a pack-prefixed texture id that climbs out of the textures directory resolves to nothing, whatever the pack is stored as")
    void packPrefixedIdStaysUnderTextures(@TempDir Path dir) throws IOException {
        Path root = dir.resolve("pack");
        write(root.resolve("assets/minecraft/textures/block/x.png"), "texture");
        write(root.resolve("assets/minecraft/models/y.png"), "not a texture");
        write(dir.resolve("beside.png"), "not the pack's");

        // "vanilla" is a loaded pack id and no namespace, so the dispatch probes the pack for the id's path.
        PackStack onDisk = stackOf(new PackContainer.Directory(root));
        assertThat(onDisk.resolve(new ResourceId("vanilla", "block/x")).orElseThrow().path(), is("assets/minecraft/textures/block/x.png"));
        for (String path : new String[] { "../../../../beside", "../models/y", "block/../../models/y", "block/x\0" }) {
            assertThat(path, onDisk.resolve(new ResourceId("vanilla", path)), is(Optional.empty()));
            assertThat(path, onDisk.resolveIn(PackId.VANILLA, new ResourceId("minecraft", path)), is(Optional.empty()));
        }

        // An archive looks a path up as a key, and a key spelled with a parent segment is refused the same way.
        PackStack archived = stackOf(PackContainer.Live.of(dir.resolve("pack.zip"), Map.of(
            "assets/minecraft/textures/block/x.png", new byte[0],
            "assets/minecraft/textures/../models/y.png", new byte[0])));
        assertThat(archived.resolve(new ResourceId("vanilla", "block/x")).isPresent(), is(true));
        assertThat(archived.resolve(new ResourceId("vanilla", "../models/y")), is(Optional.empty()));
    }

    private static PackStack stackOf(PackContainer container) {
        ConcurrentList<PackRoot> roots = Concurrent.newList();
        roots.add(PackRoot.BASE);
        ConcurrentList<ResourcePack> ascending = Concurrent.newList();
        ascending.add(new ResourcePack(PackId.VANILLA, container, MCMeta.EMPTY, roots.toUnmodifiable(),
                                       Concurrent.newUnmodifiableTreeSet("minecraft"),
                                       Concurrent.newUnmodifiableLinkedSet(PackCapability.VANILLA_CORE)));
        return PackStack.of(ascending.toUnmodifiable());
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

}
