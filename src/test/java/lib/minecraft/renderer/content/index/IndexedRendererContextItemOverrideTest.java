package lib.minecraft.renderer.content.index;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import lib.minecraft.renderer.asset.pack.MCMeta;
import lib.minecraft.renderer.asset.pack.PackCapability;
import lib.minecraft.renderer.asset.pack.PackRoot;
import lib.minecraft.renderer.asset.pack.ResourcePack;
import lib.minecraft.renderer.asset.rule.CitRule;
import lib.minecraft.renderer.asset.rule.RuleSet;
import lib.minecraft.renderer.content.pack.PackContainer;
import lib.minecraft.renderer.content.pack.PackStack;
import lib.minecraft.renderer.content.pack.PalettedPermutationLoader;
import lib.minecraft.renderer.content.pack.ResolvedModels;
import lib.minecraft.renderer.content.pack.TextureSynthesizer;
import lib.minecraft.renderer.content.rule.CitParser;
import lib.minecraft.renderer.request.ItemContext;
import lib.minecraft.renderer.vanilla.id.PackId;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Coverage of {@link IndexedRendererContext#resolveItemTextureOverride}: the walk takes the first
 * matching {@code type=item} rule in the stack's order, passes over a matching rule of any other
 * type, and grafts the render's glint decision onto the winning output - or onto
 * {@link CitResult#NONE} when no item rule matches.
 */
@DisplayName("IndexedRendererContext.resolveItemTextureOverride walks CIT item rules")
class IndexedRendererContextItemOverrideTest {

    private static final @NotNull ItemContext SWORD = ItemContext.ofItem("minecraft:diamond_sword");

    private static final @NotNull CitRule SWORD_A = cit("type", "item", "items", "diamond_sword", "texture", "custom/sword_a");

    private static final @NotNull CitRule SWORD_ARMOR = cit("type", "armor", "items", "diamond_sword", "texture", "custom/sword_armor");

    @TempDir
    Path tmp;

    @Test
    @DisplayName("a matching type=item rule retextures the item with DEFAULT glint")
    void itemRuleRetexturesTheItem() {
        CitResult result = contextWith(Optional.empty(), SWORD_A).resolveItemTextureOverride(SWORD);

        assertThat(result.texture().map(ResourceId::id), is(Optional.of("minecraft:custom/sword_a")));
        assertThat(result.glint(), is(GlintPolicy.DEFAULT));
    }

    @Test
    @DisplayName("a rule with no type= key is an item rule")
    void untypedRuleIsAnItemRule() {
        CitRule untyped = cit("items", "diamond_sword", "texture", "custom/sword_a");
        CitResult result = contextWith(Optional.empty(), untyped).resolveItemTextureOverride(SWORD);

        assertThat(result.texture().map(ResourceId::id), is(Optional.of("minecraft:custom/sword_a")));
        assertThat(result.glint(), is(GlintPolicy.DEFAULT));
    }

    @Test
    @DisplayName("a matching rule of another type ahead of the item rule is passed over")
    void aMatchingRuleOfAnotherTypeIsPassedOver() {
        CitResult result = contextWith(Optional.empty(), SWORD_ARMOR, SWORD_A).resolveItemTextureOverride(SWORD);

        assertThat(result.texture().map(ResourceId::id), is(Optional.of("minecraft:custom/sword_a")));
    }

    @Test
    @DisplayName("a stack whose only matching rule is not an item rule resolves NONE")
    void aStackWithNoItemRuleResolvesNone() {
        CitResult result = contextWith(Optional.empty(), SWORD_ARMOR).resolveItemTextureOverride(SWORD);

        assertThat(result, is(CitResult.NONE));
    }

    @Test
    @DisplayName("the first matching item rule in the stack's order wins")
    void theFirstMatchingItemRuleWins() {
        CitRule swordB = cit("type", "item", "items", "diamond_sword", "texture", "custom/sword_b");
        CitResult result = contextWith(Optional.empty(), SWORD_A, swordB).resolveItemTextureOverride(SWORD);

        assertThat(result.texture().map(ResourceId::id), is(Optional.of("minecraft:custom/sword_a")));
    }

    @Test
    @DisplayName("a matching type=enchantment rule's glint rides on the winning item output")
    void theGlintIsGraftedOntoTheWinningOutput() {
        // Filterless, so it matches any context - and it sits first, so the walk passes over a
        // matching enchantment rule as well as taking its glint.
        CitRule glint = cit("type", "enchantment", "texture", "global_glint");
        CitResult result = contextWith(Optional.empty(), glint, SWORD_A).resolveItemTextureOverride(SWORD);

        assertThat(result.texture().map(ResourceId::id), is(Optional.of("minecraft:custom/sword_a")));
        assertThat(result.glint(), is(new GlintPolicy.Replaced(new ResourceId("minecraft", "optifine/cit/global_glint"))));
    }

    @Test
    @DisplayName("a render no item rule matches still carries a glint decision other than DEFAULT")
    void anUnmatchedItemStillCarriesTheGlint() {
        CitResult result = contextWith(Optional.of(false)).resolveItemTextureOverride(SWORD);

        assertThat(result.texture(), is(Optional.empty()));
        assertThat(result.glint(), is(GlintPolicy.SUPPRESSED));
    }

    private static @NotNull CitRule cit(@NotNull String... keyValues) {
        Properties props = new Properties();
        for (int i = 0; i < keyValues.length; i += 2) props.setProperty(keyValues[i], keyValues[i + 1]);
        return CitParser.parse(props, new ResourceId("minecraft", "optifine/cit/x.properties"),
            PackId.VANILLA, "optifine/cit", "optifine/cit").orElseThrow();
    }

    private @NotNull IndexedRendererContext contextWith(@NotNull Optional<Boolean> useGlint, @NotNull CitRule... rules) {
        ResourcePack vanilla = new ResourcePack(PackId.VANILLA, new PackContainer.Directory(tmp), MCMeta.EMPTY,
            Concurrent.newList(PackRoot.BASE).toUnmodifiable(), Concurrent.newUnmodifiableTreeSet("minecraft"),
            Concurrent.newUnmodifiableLinkedSet(PackCapability.VANILLA_CORE));
        RuleSet base = RuleSet.empty(PackId.VANILLA);
        ConcurrentList<CitRule> citRules = Concurrent.newList(rules).toUnmodifiable();
        RuleSet ruleSet = new RuleSet(PackId.VANILLA, citRules, base.ctmRules(), base.colors(), useGlint);
        PackStack stack = PackStack.of(Concurrent.newList(vanilla)).withRules(ruleSet);

        return new IndexedRendererContext(
            stack, Concurrent.newMap(), Concurrent.newMap(), Concurrent.newMap(),
            new ResolvedModels(Concurrent.newMap(), Concurrent.newMap(), Concurrent.newMap()),
            Concurrent.newMap(), Concurrent.newMap(), Concurrent.newMap(), Concurrent.newMap(),
            Concurrent.newMap(), Concurrent.newMap(),
            new TextureSynthesizer(PalettedPermutationLoader.load(stack)), Concurrent.newMap(),
            Concurrent.newUnmodifiableList(), Concurrent.newUnmodifiableList());
    }

}
