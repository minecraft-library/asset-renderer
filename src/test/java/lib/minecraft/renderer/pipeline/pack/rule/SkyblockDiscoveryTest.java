package lib.minecraft.renderer.pipeline.pack.rule;

import dev.simplified.collection.Concurrent;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.pipeline.pack.Capability;
import lib.minecraft.renderer.pipeline.pack.MCMeta;
import lib.minecraft.renderer.pipeline.pack.PackContainer;
import lib.minecraft.renderer.pipeline.pack.PackId;
import lib.minecraft.renderer.pipeline.pack.PackRoot;
import lib.minecraft.renderer.pipeline.pack.ResourcePack;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;

/**
 * Verifies {@link SkyblockDiscovery} (03-rules §6.2): the capability gate (a non-Catharsis pack
 * discovers nothing) and the {@code assets/skyblock/items/**.json} walk including the shared-base
 * sub-identifier directories, addressed under the synthetic {@code skyblock} namespace. Discovery only -
 * nothing renders these until phase 6.
 */
class SkyblockDiscoveryTest {

    @TempDir
    Path tmp;

    @Test
    @DisplayName("a pack without CATHARSIS_CONVENTIONS discovers nothing, even with skyblock item files present")
    void capabilityGated() throws IOException {
        write("assets/skyblock/items/adaptive_boots.json", "{}");
        assertThat(SkyblockDiscovery.discover(pack(Set.of(Capability.VANILLA_CORE))), is(empty()));
    }

    @Test
    @DisplayName("discovers top-level and sub-identifier item defs, skipping non-json and non-items files")
    void discoversItemDefinitions() throws IOException {
        write("assets/skyblock/items/adaptive_boots.json", "{}");
        write("assets/skyblock/items/double_plant.4.json", "{}");
        write("assets/skyblock/items/enchantments/sharpness.json", "{}");
        write("assets/skyblock/items/pets/wolf.json", "{}");
        write("assets/skyblock/items/notes.txt", "ignored");           // not .json
        write("assets/minecraft/textures/block/stone.png", "png");     // outside the items root

        Set<ResourceId> ids = SkyblockDiscovery.discover(pack(Set.of(Capability.VANILLA_CORE, Capability.CATHARSIS_CONVENTIONS)));
        assertThat(ids, containsInAnyOrder(
            new ResourceId("skyblock", "adaptive_boots"),
            new ResourceId("skyblock", "double_plant.4"),
            new ResourceId("skyblock", "enchantments/sharpness"),
            new ResourceId("skyblock", "pets/wolf")));
    }

    private @NotNull ResourcePack pack(@NotNull Set<Capability> capabilities) {
        return new ResourcePack(new PackId("catpack"), new PackContainer.Directory(this.tmp), MCMeta.EMPTY,
            Concurrent.newList(PackRoot.BASE), Set.of("minecraft", "skyblock"), capabilities);
    }

    private void write(@NotNull String relative, @NotNull String content) throws IOException {
        Path file = this.tmp.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

}
