package lib.minecraft.renderer.pipeline;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import lib.minecraft.renderer.asset.PackStack;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.asset.equipment.ArmorMaterial;
import lib.minecraft.renderer.asset.equipment.LayerType;
import lib.minecraft.renderer.asset.pack.MCMeta;
import lib.minecraft.renderer.asset.pack.PackCapability;
import lib.minecraft.renderer.asset.pack.PackContainer;
import lib.minecraft.renderer.asset.pack.PackId;
import lib.minecraft.renderer.asset.pack.PackRoot;
import lib.minecraft.renderer.asset.pack.ResourcePack;
import lib.minecraft.renderer.asset.pack.rule.CitResult;
import lib.minecraft.renderer.asset.pack.rule.CitRule;
import lib.minecraft.renderer.asset.pack.rule.GlintPolicy;
import lib.minecraft.renderer.asset.pack.rule.ItemContext;
import lib.minecraft.renderer.asset.pack.rule.RuleSet;
import lib.minecraft.renderer.engine.texture.TextureSynthesizer;
import lib.minecraft.renderer.pipeline.pack.PalettedPermutationLoader;
import lib.minecraft.renderer.pipeline.pack.rule.CitParser;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Properties;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Coverage of {@link PipelineRendererContext#resolveArmorTextureOverride}: a synthetic
 * {@code type=armor} / {@code type=elytra} CIT rule retextures a matching equipped piece, a
 * non-matching context and a vanilla-only stack resolve to {@link CitResult#NONE}, and the layer type
 * selects the rule subject ({@link LayerType#WINGS} wants {@code type=elytra}, any other
 * {@code type=armor}).
 */
@DisplayName("PipelineRendererContext.resolveArmorTextureOverride walks CIT armor / elytra rules")
class PipelineRendererContextArmorOverrideTest {

    @TempDir
    Path tmp;

    @Test
    @DisplayName("a matching type=armor rule returns the override texture with DEFAULT glint")
    void armorMatchReturnsOverride() {
        CitRule rule = cit("type", "armor", "items", "iron_chestplate", "texture", "custom/iron_armor");
        CitResult result = contextWith(rule)
            .resolveArmorTextureOverride(ArmorMaterial.IRON, LayerType.HUMANOID, ItemContext.ofItem("minecraft:iron_chestplate"));

        assertThat(result.texture().isPresent(), is(true));
        assertThat(result.texture().get().id(), equalTo("minecraft:custom/iron_armor"));
        // Armor enchant glint rides a separate PixelMask channel, so a CIT-armor override stays DEFAULT.
        assertThat(result.glint(), is(GlintPolicy.DEFAULT));
    }

    @Test
    @DisplayName("a non-matching item context returns NONE")
    void nonMatchReturnsNone() {
        CitRule rule = cit("type", "armor", "items", "iron_chestplate", "texture", "custom/iron_armor");
        CitResult result = contextWith(rule)
            .resolveArmorTextureOverride(ArmorMaterial.IRON, LayerType.HUMANOID, ItemContext.ofItem("minecraft:diamond_chestplate"));

        assertThat(result, is(CitResult.NONE));
    }

    @Test
    @DisplayName("a vanilla-only stack with no rules returns NONE")
    void vanillaStackReturnsNone() {
        CitResult result = contextWith()
            .resolveArmorTextureOverride(ArmorMaterial.IRON, LayerType.HUMANOID, ItemContext.ofItem("minecraft:iron_chestplate"));

        assertThat(result, is(CitResult.NONE));
    }

    @Test
    @DisplayName("WINGS selects the type=elytra subject and retextures the wings")
    void wingsSelectsElytraSubject() {
        CitRule rule = cit("type", "elytra", "items", "elytra", "texture", "custom/wings");
        CitResult result = contextWith(rule)
            .resolveArmorTextureOverride(ArmorMaterial.IRON, LayerType.WINGS, ItemContext.ofItem("minecraft:elytra"));

        assertThat(result.texture().isPresent(), is(true));
        assertThat(result.texture().get().id(), equalTo("minecraft:custom/wings"));
    }

    @Test
    @DisplayName("a WINGS query never matches a type=armor rule (subject discrimination)")
    void wingsIgnoresArmorRule() {
        CitRule armorRule = cit("type", "armor", "items", "elytra", "texture", "custom/iron_armor");
        CitResult result = contextWith(armorRule)
            .resolveArmorTextureOverride(ArmorMaterial.IRON, LayerType.WINGS, ItemContext.ofItem("minecraft:elytra"));

        assertThat(result, is(CitResult.NONE));
    }

    private static @NotNull CitRule cit(@NotNull String... keyValues) {
        Properties props = new Properties();
        for (int i = 0; i < keyValues.length; i += 2) props.setProperty(keyValues[i], keyValues[i + 1]);
        return CitParser.parse(props, new ResourceId("minecraft", "optifine/cit/x.properties"),
            PackId.VANILLA, "optifine/cit", "optifine/cit").orElseThrow();
    }

    private @NotNull PipelineRendererContext contextWith(@NotNull CitRule... rules) {
        ResourcePack vanilla = new ResourcePack(PackId.VANILLA, new PackContainer.Directory(tmp), MCMeta.EMPTY,
            Concurrent.newList(PackRoot.BASE).toUnmodifiable(), Set.of("minecraft"), Set.of(PackCapability.VANILLA_CORE));
        RuleSet base = RuleSet.empty(PackId.VANILLA);
        ConcurrentList<CitRule> citRules = Concurrent.newList(rules).toUnmodifiable();
        RuleSet ruleSet = new RuleSet(PackId.VANILLA, citRules, base.ctmRules(), base.colors(), base.useGlint());
        PackStack stack = PackStack.of(Concurrent.newList(vanilla)).withRules(ruleSet);

        return new PipelineRendererContext(
            stack, Concurrent.newMap(), Concurrent.newMap(), Concurrent.newMap(), Concurrent.newMap(),
            Concurrent.newMap(), Concurrent.newMap(), Concurrent.newMap(), Concurrent.newMap(),
            Concurrent.newMap(), Concurrent.newMap(),
            new TextureSynthesizer(PalettedPermutationLoader.load(stack)), Concurrent.newMap(),
            Concurrent.newUnmodifiableList(), Concurrent.newUnmodifiableList());
    }

}
