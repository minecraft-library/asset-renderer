package lib.minecraft.renderer.pipeline.pack.rule;

import dev.simplified.collection.Concurrent;
import lib.minecraft.renderer.asset.pack.MCMeta;
import lib.minecraft.renderer.asset.PackStack;
import lib.minecraft.renderer.asset.pack.Capability;
import lib.minecraft.renderer.asset.pack.PackContainer;
import lib.minecraft.renderer.asset.pack.PackId;
import lib.minecraft.renderer.asset.pack.PackRoot;
import lib.minecraft.renderer.asset.pack.ResourcePack;
import lib.minecraft.renderer.asset.pack.rule.CitRule;
import lib.minecraft.renderer.asset.pack.rule.RuleSet;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Verifies {@link RuleScanner#mergeAll(PackStack)} over on-disk Directory packs - the deterministic CIT
 * order (weight DESC, then FILENAME, then higher-priority pack), the CTM tile-before-block partition,
 * and the per-key highest-pack-wins colour merge.
 */
class RuleScannerMergeTest {

    @TempDir
    Path tmp;

    private static final @NotNull PackId USER = new PackId("userpack");

    @Test
    @DisplayName("CIT rules order by weight DESC then filename ASC")
    void weightThenFilename() throws IOException {
        writeCit(PackId.VANILLA, "z_rule.properties", "items=diamond_sword\nweight=5\ntexture=z");
        writeCit(PackId.VANILLA, "a_rule.properties", "items=diamond_sword\nweight=5\ntexture=a");
        writeCit(PackId.VANILLA, "top.properties", "items=diamond_sword\nweight=10\ntexture=t");

        RuleSet merged = RuleScanner.mergeAll(PackStack.of(Concurrent.newList(pack(PackId.VANILLA))));
        List<String> order = merged.citRules().stream().map(CitRule::filename).toList();
        assertThat(order, equalTo(List.of("top.properties", "a_rule.properties", "z_rule.properties")));
    }

    @Test
    @DisplayName("a weight+filename tie is broken by the higher-priority pack")
    void packPriorityTieBreak() throws IOException {
        writeCit(PackId.VANILLA, "dup.properties", "items=diamond_sword\nweight=5\ntexture=v");
        writeCit(USER, "dup.properties", "items=diamond_sword\nweight=5\ntexture=u");

        RuleSet merged = RuleScanner.mergeAll(PackStack.of(Concurrent.newList(pack(PackId.VANILLA), pack(USER))));
        assertThat(merged.citRules().getFirst().pack(), equalTo(USER));
    }

    @Test
    @DisplayName("color.properties merges per-key with the higher-priority pack winning each key")
    void colorPerKeyHighestWins() throws IOException {
        writeFile(PackId.VANILLA, "assets/minecraft/optifine/color.properties", "redstone.0=0x111111\ngrass.plains=0x00FF00");
        writeFile(USER, "assets/minecraft/optifine/color.properties", "redstone.0=0x222222");

        RuleSet merged = RuleScanner.mergeAll(PackStack.of(Concurrent.newList(pack(PackId.VANILLA), pack(USER))));
        assertThat(merged.colors().get("redstone.0").orElseThrow(), equalTo(0xFF222222));
        assertThat(merged.colors().get("grass.plains").orElseThrow(), equalTo(0xFF00FF00));
    }

    @Test
    @DisplayName("CTM rules partition tile-target before block-target")
    void ctmTileBeforeBlock() throws IOException {
        writeFile(PackId.VANILLA, "assets/minecraft/optifine/ctm/block_stone.properties", "method=fixed\nmatchBlocks=minecraft:stone\ntiles=custom");
        writeFile(PackId.VANILLA, "assets/minecraft/optifine/ctm/glass.properties", "method=fixed\nmatchTiles=glass\ntiles=custom");

        RuleSet merged = RuleScanner.mergeAll(PackStack.of(Concurrent.newList(pack(PackId.VANILLA))));
        assertThat(merged.ctmRules().size(), equalTo(2));
        assertThat(merged.ctmRules().getFirst().isTileTarget(), is(true));
        assertThat(merged.ctmRules().get(1).isTileTarget(), is(false));
    }

    @Test
    @DisplayName("same-basename rules in different directories order deterministically by full id, not walk order")
    void sameBasenameDeterministicByFullId() throws IOException {
        writeCit(PackId.VANILLA, "swords/legendary.properties", "items=diamond_sword\ntexture=s");
        writeCit(PackId.VANILLA, "tools/legendary.properties", "items=diamond_sword\ntexture=t");

        RuleSet merged = RuleScanner.mergeAll(PackStack.of(Concurrent.newList(pack(PackId.VANILLA))));
        List<String> ids = merged.citRules().stream().map(rule -> rule.id().name()).toList();
        assertThat(ids, equalTo(List.of("optifine/cit/swords/legendary.properties", "optifine/cit/tools/legendary.properties")));
    }

    @Test
    @DisplayName("a pack without OPTIFINE_RULES scans to empty, ignoring on-disk cit files")
    void capabilityGateShortCircuits() throws IOException {
        writeCit(PackId.VANILLA, "z.properties", "items=diamond_sword\ntexture=z");
        ResourcePack vanillaOnly = new ResourcePack(PackId.VANILLA,
            new PackContainer.Directory(this.tmp.resolve(PackId.VANILLA.value())), MCMeta.EMPTY,
            Concurrent.newList(PackRoot.BASE), Set.of("minecraft"), Set.of(Capability.VANILLA_CORE));

        RuleSet scanned = RuleScanner.scan(vanillaOnly);
        assertThat(scanned.citRules().isEmpty(), is(true));
        assertThat(scanned.colors().isEmpty(), is(true));
    }

    @Test
    @DisplayName("useGlint is taken from the highest-priority pack that ships it")
    void useGlintHighestPackWins() throws IOException {
        writeFile(PackId.VANILLA, "assets/minecraft/optifine/cit.properties", "useGlint=true");
        writeFile(USER, "assets/minecraft/optifine/cit.properties", "useGlint=false");

        RuleSet merged = RuleScanner.mergeAll(PackStack.of(Concurrent.newList(pack(PackId.VANILLA), pack(USER))));
        assertThat(merged.useGlint().orElseThrow(), is(false));
    }

    private @NotNull ResourcePack pack(@NotNull PackId id) throws IOException {
        Path root = this.tmp.resolve(id.value());
        Files.createDirectories(root);
        return new ResourcePack(id, new PackContainer.Directory(root), MCMeta.EMPTY,
            Concurrent.newList(PackRoot.BASE), Set.of("minecraft"), Set.of(Capability.VANILLA_CORE, Capability.OPTIFINE_RULES));
    }

    private void writeCit(@NotNull PackId id, @NotNull String name, @NotNull String content) throws IOException {
        writeFile(id, "assets/minecraft/optifine/cit/" + name, content);
    }

    private void writeFile(@NotNull PackId id, @NotNull String relative, @NotNull String content) throws IOException {
        Path file = this.tmp.resolve(id.value()).resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

}
