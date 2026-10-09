package lib.minecraft.renderer.content.rule;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import lib.minecraft.renderer.asset.pack.MCMeta;
import lib.minecraft.renderer.asset.pack.PackCapability;
import lib.minecraft.renderer.asset.pack.PackRoot;
import lib.minecraft.renderer.asset.pack.ResourcePack;
import lib.minecraft.renderer.asset.rule.CitRule;
import lib.minecraft.renderer.asset.rule.ColorProperties;
import lib.minecraft.renderer.asset.rule.CtmRule;
import lib.minecraft.renderer.asset.rule.RuleSet;
import lib.minecraft.renderer.asset.rule.TileRef;
import lib.minecraft.renderer.engine.geometry.Face;
import lib.minecraft.renderer.fixture.PackFixtures;
import lib.minecraft.renderer.vanilla.id.PackId;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Coverage of {@link RuleScanner#mergeAll(List)} over on-disk Directory packs - the deterministic
 * CIT order (weight DESC, then FILENAME, then higher-priority pack), the CTM tile-before-block
 * partition, and the one {@code optifine/color.properties} a stack reads: the copy the highest pack
 * shipping a loadable one holds, from the last of that pack's roots to ship it, read whole.
 */
class RuleScannerMergeTest {

    @TempDir
    Path tmp;

    private static final @NotNull PackId USER = new PackId("userpack");

    /** The pack-relative path of the colour file the scanner reads. */
    private static final @NotNull String COLOR_FILE = "assets/minecraft/optifine/color.properties";

    @Test
    @DisplayName("CIT rules order by weight DESC then filename ASC")
    void weightThenFilename() throws IOException {
        writeCit(PackId.VANILLA, "z_rule.properties", "items=diamond_sword\nweight=5\ntexture=z");
        writeCit(PackId.VANILLA, "a_rule.properties", "items=diamond_sword\nweight=5\ntexture=a");
        writeCit(PackId.VANILLA, "top.properties", "items=diamond_sword\nweight=10\ntexture=t");

        RuleSet merged = RuleScanner.mergeAll(Concurrent.newList(pack(PackId.VANILLA)));
        List<String> order = merged.citRules().stream().map(CitRule::filename).toList();
        assertThat(order, equalTo(List.of("top.properties", "a_rule.properties", "z_rule.properties")));
    }

    @Test
    @DisplayName("a weight+filename tie is broken by the higher-priority pack")
    void packPriorityTieBreak() throws IOException {
        writeCit(PackId.VANILLA, "dup.properties", "items=diamond_sword\nweight=5\ntexture=v");
        writeCit(USER, "dup.properties", "items=diamond_sword\nweight=5\ntexture=u");

        RuleSet merged = RuleScanner.mergeAll(Concurrent.newList(pack(PackId.VANILLA), pack(USER)));
        assertThat(merged.citRules().getFirst().pack(), equalTo(USER));
    }

    @Test
    @DisplayName("the highest-priority pack's color.properties is read whole, and no key of a lower pack's file shows through")
    void colorHighestFileReadWhole() throws IOException {
        writeFile(PackId.VANILLA, COLOR_FILE, "redstone.0=0x111111\nredstone.1=0x111111");
        writeFile(USER, COLOR_FILE, "redstone.0=0x222222");

        RuleSet merged = RuleScanner.mergeAll(Concurrent.newList(pack(PackId.VANILLA), pack(USER)));
        assertThat(color(merged, "redstone.0"), is(Optional.of(0xFF222222)));
        assertThat(color(merged, "redstone.1"), is(Optional.empty()));
    }

    @Test
    @DisplayName("a top color.properties holding no usable key still hides every lower pack's file")
    void colorTopFileWithNoUsableKeyHidesLower() throws IOException {
        writeFile(PackId.VANILLA, COLOR_FILE, "redstone.0=0x111111");

        for (String body : List.of("# only a comment", "redstone.0=")) {
            writeFile(USER, COLOR_FILE, body);

            RuleSet top = RuleScanner.scan(pack(USER));
            assertThat(body, top.colors().map(ColorProperties::isEmpty), is(Optional.of(true)));

            RuleSet merged = RuleScanner.mergeAll(Concurrent.newList(pack(PackId.VANILLA), pack(USER)));
            assertThat(body, merged.colors().isPresent(), is(true));
            assertThat(body, color(merged, "redstone.0"), is(Optional.empty()));
        }
    }

    @Test
    @DisplayName("a top pack shipping no color.properties lets the lower pack's file through whole")
    void colorTopPackWithoutFileLetsLowerThrough() throws IOException {
        writeFile(PackId.VANILLA, COLOR_FILE, "redstone.0=0x111111\nredstone.1=0x333333");
        writeCit(USER, "sword.properties", "items=diamond_sword\ntexture=s");

        assertThat(RuleScanner.scan(pack(USER)).colors(), is(Optional.empty()));

        RuleSet merged = RuleScanner.mergeAll(Concurrent.newList(pack(PackId.VANILLA), pack(USER)));
        assertThat(color(merged, "redstone.0"), is(Optional.of(0xFF111111)));
        assertThat(color(merged, "redstone.1"), is(Optional.of(0xFF333333)));
    }

    @Test
    @DisplayName("a blank or unparseable value is unset beside a good key in the same file, and the lower pack's value does not show through")
    void colorBlankAndUnparseableValuesAreUnset() throws IOException {
        writeFile(PackId.VANILLA, COLOR_FILE, "redstone.0=0x111111\nredstone.1=0x111111\nredstone.2=0x111111");
        writeFile(USER, COLOR_FILE, "redstone.0=\nredstone.1=zzz\nredstone.2=0x222222");

        RuleSet merged = RuleScanner.mergeAll(Concurrent.newList(pack(PackId.VANILLA), pack(USER)));
        assertThat("blank", color(merged, "redstone.0"), is(Optional.empty()));
        assertThat("unparseable", color(merged, "redstone.1"), is(Optional.empty()));
        assertThat("good", color(merged, "redstone.2"), is(Optional.of(0xFF222222)));
    }

    @Test
    @DisplayName("a color.properties with a malformed unicode escape counts as not shipped, and the next pack's file is read whole")
    void colorMalformedEscapeSkipsFile() throws IOException {
        writeFile(PackId.VANILLA, COLOR_FILE, "redstone.0=0x111111\nredstone.1=0x333333");
        writeFile(USER, COLOR_FILE, "redstone.0=0x222222\nbroken=\\uZZZZ");

        assertThat(RuleScanner.scan(pack(USER)).colors(), is(Optional.empty()));

        RuleSet merged = RuleScanner.mergeAll(Concurrent.newList(pack(PackId.VANILLA), pack(USER)));
        assertThat(color(merged, "redstone.0"), is(Optional.of(0xFF111111)));
        assertThat(color(merged, "redstone.1"), is(Optional.of(0xFF333333)));
    }

    @Test
    @DisplayName("an overlay's color.properties wins over the base's, read whole")
    void colorOverlayFileWinsOverBase() throws IOException {
        writeFile(USER, COLOR_FILE, "redstone.0=0x111111\nredstone.1=0x111111");
        writeFile(USER, "ov/" + COLOR_FILE, "redstone.0=0x222222");

        RuleSet scanned = RuleScanner.scan(overlaidPack(USER, PackRoot.overlay("ov")));
        assertThat(scanned.colors().flatMap(colors -> colors.get("redstone.0")), is(Optional.of(0xFF222222)));
        assertThat(scanned.colors().flatMap(colors -> colors.get("redstone.1")), is(Optional.empty()));
    }

    @Test
    @DisplayName("of two overlays shipping color.properties, the later-declared one wins")
    void colorLaterOverlayWinsOverEarlier() throws IOException {
        writeFile(USER, COLOR_FILE, "redstone.0=0x111111");
        writeFile(USER, "ov_z/" + COLOR_FILE, "redstone.0=0x222222\nredstone.1=0x222222");
        writeFile(USER, "ov_a/" + COLOR_FILE, "redstone.0=0x333333");

        // Declared z before a, so declaration order and name order disagree on the winner.
        RuleSet scanned = RuleScanner.scan(overlaidPack(USER, PackRoot.overlay("ov_z"), PackRoot.overlay("ov_a")));
        assertThat(scanned.colors().flatMap(colors -> colors.get("redstone.0")), is(Optional.of(0xFF333333)));
        assertThat(scanned.colors().flatMap(colors -> colors.get("redstone.1")), is(Optional.empty()));
    }

    @Test
    @DisplayName("mcpatcher/color.properties is not read, and does not hide a lower pack's optifine file")
    void colorMcpatcherPathIsNotRead() throws IOException {
        writeFile(USER, "assets/minecraft/mcpatcher/color.properties", "redstone.0=0x222222");

        RuleSet alone = RuleScanner.mergeAll(Concurrent.newList(pack(USER)));
        assertThat(alone.colors(), is(Optional.empty()));

        writeFile(PackId.VANILLA, COLOR_FILE, "redstone.0=0x111111");
        RuleSet merged = RuleScanner.mergeAll(Concurrent.newList(pack(PackId.VANILLA), pack(USER)));
        assertThat(color(merged, "redstone.0"), is(Optional.of(0xFF111111)));
    }

    @Test
    @DisplayName("a CIT rule file with a malformed unicode escape is skipped, and its siblings still load")
    void citMalformedEscapeSkipsFile() throws IOException {
        writeCit(PackId.VANILLA, "good.properties", "items=diamond_sword\ntexture=g");
        writeCit(PackId.VANILLA, "bad.properties", "items=diamond_sword\ntexture=\\uZZZZ");

        RuleSet merged = RuleScanner.mergeAll(Concurrent.newList(pack(PackId.VANILLA)));
        List<String> filenames = merged.citRules().stream().map(CitRule::filename).toList();
        assertThat(filenames, equalTo(List.of("good.properties")));
    }

    @Test
    @DisplayName("a potion shortcut PNG under cit/potion synthesises a rule, and a stray PNG does not")
    void potionShortcutTextureSynthesisesRule() throws IOException {
        // The scanner reads two kinds of file from one CIT root - the .properties rules and these
        // .png shortcuts - so this pins the discovery arm rather than the parse, which CitParserTest
        // already covers. The stray sits in the same tree and must be ignored.
        writeCit(PackId.VANILLA, "potion/splash/fire_resistance.png", "");
        writeCit(PackId.VANILLA, "not_a_potion.png", "");

        RuleSet merged = RuleScanner.mergeAll(Concurrent.newList(pack(PackId.VANILLA)));
        List<String> filenames = merged.citRules().stream().map(CitRule::filename).toList();
        assertThat(filenames.size(), equalTo(1));
        assertThat(filenames.getFirst(), containsString("fire_resistance"));
    }

    @Test
    @DisplayName("CTM rules partition tile-target before block-target")
    void ctmTileBeforeBlock() throws IOException {
        writeFile(PackId.VANILLA, "assets/minecraft/optifine/ctm/block_stone.properties", "method=fixed\nmatchBlocks=minecraft:stone\ntiles=custom");
        writeFile(PackId.VANILLA, "assets/minecraft/optifine/ctm/glass.properties", "method=fixed\nmatchTiles=glass\ntiles=custom");

        RuleSet merged = RuleScanner.mergeAll(Concurrent.newList(pack(PackId.VANILLA)));
        assertThat(merged.ctmRules().size(), equalTo(2));
        assertThat(merged.ctmRules().getFirst().isTileTarget(), is(true));
        assertThat(merged.ctmRules().getLast().isTileTarget(), is(false));
    }

    @Test
    @DisplayName("a scanned + merged ctm rule keeps its targeted face alone and its tile under the ctm root")
    void ctmRuleKeepsFaceAndTileThroughMerge() throws IOException {
        writeFile(PackId.VANILLA, "assets/minecraft/optifine/ctm/glass.properties",
            "method=fixed\nmatchTiles=glass\ntiles=custom\nfaces=top");

        RuleSet merged = RuleScanner.mergeAll(Concurrent.newList(pack(PackId.VANILLA)));
        assertThat(merged.ctmRules().size(), equalTo(1));
        CtmRule rule = merged.ctmRules().getFirst();
        assertThat(rule.faces(), equalTo(EnumSet.of(Face.UP)));
        ResourceId tile = ((TileRef.Texture) rule.tiles().getFirst()).id();
        assertThat(tile.name(), equalTo("optifine/ctm/custom"));
    }

    @Test
    @DisplayName("same-basename rules in different directories order deterministically by full id, not walk order")
    void sameBasenameDeterministicByFullId() throws IOException {
        writeCit(PackId.VANILLA, "swords/legendary.properties", "items=diamond_sword\ntexture=s");
        writeCit(PackId.VANILLA, "tools/legendary.properties", "items=diamond_sword\ntexture=t");

        RuleSet merged = RuleScanner.mergeAll(Concurrent.newList(pack(PackId.VANILLA)));
        List<String> ids = merged.citRules().stream().map(rule -> rule.id().name()).toList();
        assertThat(ids, equalTo(List.of("optifine/cit/swords/legendary.properties", "optifine/cit/tools/legendary.properties")));
    }

    @Test
    @DisplayName("a pack without OPTIFINE_RULES scans to empty, ignoring on-disk cit files")
    void capabilityGateShortCircuits() throws IOException {
        writeCit(PackId.VANILLA, "z.properties", "items=diamond_sword\ntexture=z");
        ResourcePack vanillaOnly = new ResourcePack(PackId.VANILLA,
            PackFixtures.directory(this.tmp.resolve(PackId.VANILLA.value())), MCMeta.EMPTY,
            Concurrent.newList(PackRoot.BASE), Concurrent.newUnmodifiableTreeSet("minecraft"),
            Concurrent.newUnmodifiableLinkedSet(PackCapability.VANILLA_CORE));

        RuleSet scanned = RuleScanner.scan(vanillaOnly);
        assertThat(scanned.citRules().isEmpty(), is(true));
        assertThat(scanned.colors().isEmpty(), is(true));
    }

    @Test
    @DisplayName("useGlint is taken from the highest-priority pack that ships it")
    void useGlintHighestPackWins() throws IOException {
        writeFile(PackId.VANILLA, "assets/minecraft/optifine/cit.properties", "useGlint=true");
        writeFile(USER, "assets/minecraft/optifine/cit.properties", "useGlint=false");

        RuleSet merged = RuleScanner.mergeAll(Concurrent.newList(pack(PackId.VANILLA), pack(USER)));
        assertThat(merged.useGlint().orElseThrow(), is(false));
    }

    private @NotNull ResourcePack pack(@NotNull PackId id) throws IOException {
        return PackFixtures.rulePack(id, this.tmp.resolve(id.value()));
    }

    /**
     * Opens a rule-bearing pack whose active roots are the base and then the given overlays, in the
     * order given.
     *
     * @param id the pack's id
     * @param overlays the overlay roots, in declaration order
     * @return the pack over its directory
     */
    private @NotNull ResourcePack overlaidPack(@NotNull PackId id, @NotNull PackRoot... overlays) {
        ConcurrentList<PackRoot> roots = Concurrent.newList(PackRoot.BASE);
        roots.addAll(List.of(overlays));
        return new ResourcePack(id, PackFixtures.directory(this.tmp.resolve(id.value())), MCMeta.EMPTY,
            roots, Concurrent.newUnmodifiableTreeSet("minecraft"),
            Concurrent.newUnmodifiableLinkedSet(PackCapability.VANILLA_CORE, PackCapability.OPTIFINE_RULES));
    }

    /**
     * The colour a rule set's file holds under a key.
     *
     * @param rules the rule set
     * @param key the property key
     * @return the opaque ARGB, or empty when no file is read or the file read holds no colour for the key
     */
    private static @NotNull Optional<Integer> color(@NotNull RuleSet rules, @NotNull String key) {
        return rules.colors().flatMap(colors -> colors.get(key));
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
