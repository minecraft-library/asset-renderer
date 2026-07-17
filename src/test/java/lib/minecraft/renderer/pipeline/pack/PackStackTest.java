package lib.minecraft.renderer.pipeline.pack;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import lib.minecraft.renderer.exception.PipelineException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies {@link PackStack} assembly and the {@link ResourcePack} accessors: vanilla-first
 * validation, id/namespace lookups, primary-namespace resolution of the kebab/snake duality, and the
 * capability query.
 */
@DisplayName("PackStack assembly + ResourcePack accessors")
class PackStackTest {

    private static ResourcePack pack(PackId id, Set<String> namespaces, Set<Capability> capabilities) {
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
        userFirst.add(pack(new PackId("eureka"), Set.of("minecraft"), Set.of(Capability.VANILLA_CORE)));
        assertThrows(PipelineException.class, () -> PackStack.of(userFirst.toUnmodifiable()));
    }

    @Test
    @DisplayName("byId, namespaces union, and packIds reflect the whole stack")
    void lookups() {
        ConcurrentList<ResourcePack> ascending = Concurrent.newList();
        ascending.add(pack(PackId.VANILLA, Set.of("minecraft"), Set.of(Capability.VANILLA_CORE)));
        ascending.add(pack(new PackId("hypixel-skyblock"), Set.of("minecraft", "hypixel_skyblock"), Set.of(Capability.VANILLA_CORE)));
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
        ResourcePack hypixel = pack(new PackId("hypixel-skyblock"), Set.of("minecraft", "hypixel_skyblock"), Set.of(Capability.VANILLA_CORE));
        assertThat(hypixel.primaryNamespace(), is(Optional.of("hypixel_skyblock")));

        ResourcePack defrosted = pack(new PackId("defrosted"), Set.of("minecraft", "lunar", "skybox"), Set.of());
        assertThat(defrosted.primaryNamespace(), is(Optional.empty()));
    }

    @Test
    @DisplayName("has() queries the detected capability set")
    void has() {
        ResourcePack p = pack(new PackId("defrosted"), Set.of("minecraft"), Set.of(Capability.VANILLA_CORE, Capability.OPTIFINE_RULES));
        assertThat(p.has(Capability.OPTIFINE_RULES), is(true));
        assertThat(p.has(Capability.CATHARSIS_CONVENTIONS), is(false));
    }

}
