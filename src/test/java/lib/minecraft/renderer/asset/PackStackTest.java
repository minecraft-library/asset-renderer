package lib.minecraft.renderer.asset;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import lib.minecraft.renderer.asset.pack.PackCapability;
import lib.minecraft.renderer.asset.pack.MCMeta;
import lib.minecraft.renderer.asset.pack.PackContainer;
import lib.minecraft.renderer.asset.pack.PackId;
import lib.minecraft.renderer.asset.pack.PackRoot;
import lib.minecraft.renderer.asset.pack.ResolvedTexture;
import lib.minecraft.renderer.asset.pack.ResourcePack;
import lib.minecraft.renderer.exception.PipelineException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies {@link PackStack} assembly and the {@link ResourcePack} accessors: vanilla-first
 * validation, id/namespace lookups, primary-namespace resolution of the kebab/snake duality, the
 * capability query, and the base-first / last-wins overlay-root stacking on an overlapping texture path.
 */
@DisplayName("PackStack assembly + ResourcePack accessors")
class PackStackTest {

    private static ResourcePack pack(PackId id, Set<String> namespaces, Set<PackCapability> capabilities) {
        ConcurrentList<PackRoot> roots = Concurrent.newList();
        roots.add(PackRoot.BASE);
        return new ResourcePack(id, new PackContainer.Directory(Path.of(id.value())), MCMeta.EMPTY,
                                roots.toUnmodifiable(), namespaces, capabilities);
    }

    @Test
    @DisplayName("of() requires a non-empty stack led by vanilla")
    void validation() {
        assertThrows(PipelineException.class, () -> PackStack.of(Concurrent.newList()));

        ConcurrentList<ResourcePack> userFirst = Concurrent.newList();
        userFirst.add(pack(new PackId("eureka"), Set.of("minecraft"), Set.of(PackCapability.VANILLA_CORE)));
        assertThrows(PipelineException.class, () -> PackStack.of(userFirst.toUnmodifiable()));
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
    @DisplayName("has() queries the detected capability set")
    void has() {
        ResourcePack p = pack(new PackId("defrosted"), Set.of("minecraft"), Set.of(PackCapability.VANILLA_CORE, PackCapability.OPTIFINE_RULES));
        assertThat(p.has(PackCapability.OPTIFINE_RULES), is(true));
        assertThat(p.has(PackCapability.CATHARSIS_CONVENTIONS), is(false));
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
                                             roots.toUnmodifiable(), Set.of("minecraft"), Set.of(PackCapability.VANILLA_CORE));
        ConcurrentList<ResourcePack> ascending = Concurrent.newList();
        ascending.add(pack);
        PackStack stack = PackStack.of(ascending.toUnmodifiable());

        ResolvedTexture resolved = stack.resolveIn(PackId.VANILLA, new ResourceId("minecraft", "x")).orElseThrow();
        assertThat(resolved.path(), is("ov_late/" + rel));
        assertThat(new String(resolved.bytes(), StandardCharsets.UTF_8), is("late"));
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

}
