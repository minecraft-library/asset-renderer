package lib.minecraft.renderer.pipeline.pack;

import dev.simplified.collection.Concurrent;
import lib.minecraft.renderer.engine.texture.PalettedPermutationSource;
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.is;

/**
 * Exercises {@link PalettedPermutationLoader}: parsing a {@code paletted_permutations} atlas source,
 * and the concatenate-across-packs-ascending merge (the one concatenation-merge file
 * kind), where a higher pack's sources are appended after the vanilla ones rather than replacing them.
 */
@DisplayName("PalettedPermutationLoader atlas sources")
class PalettedPermutationLoaderTest {

    @TempDir
    Path tmp;

    @Test
    @DisplayName("parses a paletted_permutations source's palette key, permutations, and textures")
    void parsesSource() throws IOException {
        Path van = tmp.resolve("vanilla");
        write(van.resolve("assets/minecraft/atlases/items.json"), """
            {"sources":[
              {"type":"minecraft:directory","source":"item","prefix":"item/"},
              {"type":"minecraft:paletted_permutations","palette_key":"minecraft:trims/color_palettes/trim_palette",
               "permutations":{"amethyst":"minecraft:trims/color_palettes/amethyst"},
               "textures":["minecraft:trims/items/chestplate_trim"]}
            ]}""");

        List<PalettedPermutationSource> sources = PalettedPermutationLoader.load(stack(van, Set.of("minecraft")));
        assertThat(sources.size(), is(1));
        assertThat(sources.getFirst().paletteKey(), is("minecraft:trims/color_palettes/trim_palette"));
        assertThat(sources.getFirst().permutations(), hasEntry("amethyst", "minecraft:trims/color_palettes/amethyst"));
        assertThat(sources.getFirst().textures(), contains("minecraft:trims/items/chestplate_trim"));
    }

    @Test
    @DisplayName("sources concatenate across packs ascending (vanilla first, then user)")
    void concatenatesAscending() throws IOException {
        Path van = tmp.resolve("vanilla");
        write(van.resolve("assets/minecraft/atlases/items.json"), """
            {"sources":[{"type":"minecraft:paletted_permutations","palette_key":"minecraft:vanilla_key",
              "permutations":{"amethyst":"minecraft:trims/color_palettes/amethyst"},"textures":["minecraft:vanilla_base"]}]}""");
        Path user = tmp.resolve("user");
        write(user.resolve("assets/minecraft/atlases/items.json"), """
            {"sources":[{"type":"minecraft:paletted_permutations","palette_key":"minecraft:user_key",
              "permutations":{"ruby":"minecraft:trims/color_palettes/ruby"},"textures":["minecraft:user_base"]}]}""");

        PackStack stack = PackStack.of(Concurrent.newList(
            pack(PackId.VANILLA, van, Set.of("minecraft")),
            pack(new PackId("userpack"), user, Set.of("minecraft"))));

        List<PalettedPermutationSource> sources = PalettedPermutationLoader.load(stack);
        assertThat("both sources present, vanilla first",
            sources.stream().map(PalettedPermutationSource::paletteKey).toList(),
            contains("minecraft:vanilla_key", "minecraft:user_key"));
    }

    private static PackStack stack(Path vanillaRoot, Set<String> namespaces) {
        return PackStack.of(Concurrent.newList(pack(PackId.VANILLA, vanillaRoot, namespaces)));
    }

    private static ResourcePack pack(PackId id, Path root, Set<String> namespaces) {
        return new ResourcePack(id, new PackContainer.Directory(root), MCMeta.EMPTY,
            Concurrent.newList(PackRoot.BASE).toUnmodifiable(), namespaces, Set.of(Capability.VANILLA_CORE));
    }

    private static void write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

}
