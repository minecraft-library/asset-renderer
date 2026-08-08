package lib.minecraft.renderer.pipeline.pack.rule;

import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.asset.pack.PackId;
import lib.minecraft.renderer.asset.pack.rule.CitRule;
import lib.minecraft.renderer.asset.pack.rule.CitType;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Properties;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Verifies that {@link CitParser}'s fail-closed items / output guards now cover {@code type=armor} and
 * {@code type=elytra} as they already do {@code type=item} - a concrete-retexture rule that names no
 * items is rejected rather than left silently over-matching. {@code type=enchantment} stays exempt: a
 * glint-only rule legitimately carries no item filter.
 */
class CitParserArmorGuardTest {

    private static final @NotNull ResourceId RULE_ID = new ResourceId("minecraft", "optifine/cit/armor/custom.properties");
    private static final @NotNull String PROPS_DIR = "optifine/cit/armor";
    private static final @NotNull String CIT_ROOT = "optifine/cit";

    @Test
    @DisplayName("type=armor naming no items is rejected (concrete-retexture guard now covers armor)")
    void armorWithoutItemsRejected() {
        assertThat(parse(props("type", "armor", "texture", "custom_iron")).isPresent(), is(false));
    }

    @Test
    @DisplayName("a valid type=armor rule parses and keeps its ARMOR subject")
    void armorWithItemsParses() {
        CitRule rule = parse(props("type", "armor", "items", "iron_chestplate", "texture", "custom_iron")).orElseThrow();
        assertThat(rule.type(), equalTo(CitType.ARMOR));
        assertThat(rule.items().getFirst().id(), equalTo("minecraft:iron_chestplate"));
        assertThat(rule.output().texture().isPresent(), is(true));
    }

    @Test
    @DisplayName("type=elytra naming no items is rejected too")
    void elytraWithoutItemsRejected() {
        assertThat(parse(props("type", "elytra", "texture", "custom_wings")).isPresent(), is(false));
    }

    @Test
    @DisplayName("a valid type=elytra rule parses and keeps its ELYTRA subject")
    void elytraWithItemsParses() {
        CitRule rule = parse(props("type", "elytra", "items", "elytra", "texture", "custom_wings")).orElseThrow();
        assertThat(rule.type(), equalTo(CitType.ELYTRA));
        assertThat(rule.items().getFirst().id(), equalTo("minecraft:elytra"));
    }

    @Test
    @DisplayName("type=enchantment stays exempt: a glint-only rule with no items still parses")
    void enchantmentWithoutItemsStillParses() {
        CitRule rule = parse(props("type", "enchantment", "enchantments", "sharpness", "texture", "custom_glint")).orElseThrow();
        assertThat(rule.type(), equalTo(CitType.ENCHANTMENT));
        assertThat(rule.items().isEmpty(), is(true));
    }

    private static @NotNull Optional<CitRule> parse(@NotNull Properties props) {
        return CitParser.parse(props, RULE_ID, PackId.VANILLA, PROPS_DIR, CIT_ROOT);
    }

    private static @NotNull Properties props(@NotNull String... keyValues) {
        Properties props = new Properties();
        for (int i = 0; i < keyValues.length; i += 2) props.setProperty(keyValues[i], keyValues[i + 1]);
        return props;
    }

}
