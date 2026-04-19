package lib.minecraft.renderer.tooling.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

/**
 * Unit tests for {@link VariantReconciler} covering the three classification outcomes (base,
 * variant, drop) against the canonical Bedrock geometry identifiers observed in 26.1.
 */
@DisplayName("VariantReconciler")
class VariantReconcilerTest {

    private static final Set<String> MOBS = Set.of(
        "zombie", "cow", "chicken", "creeper", "sheep", "copper_golem", "happy_ghast",
        "parched", "zombified_piglin", "evoker"
    );

    @Test
    @DisplayName("base mob matches directly")
    void baseMobKeptAsBase() {
        VariantReconciler.Classification result = VariantReconciler.classify("zombie", MOBS);
        assertThat(result.isMob(), is(true));
        assertThat(result.canonicalId(), equalTo("zombie"));
        assertThat(result.baseMobId(), is(nullValue()));
    }

    @Test
    @DisplayName("biome variant is kept and tied to its base")
    void biomeVariantClassified() {
        VariantReconciler.Classification cold = VariantReconciler.classify("cow_cold", MOBS);
        assertThat("cow_cold kept", cold.isMob(), is(true));
        assertThat("cow_cold canonical", cold.canonicalId(), equalTo("cow_cold"));
        assertThat("cow_cold base", cold.baseMobId(), equalTo("cow"));

        VariantReconciler.Classification warm = VariantReconciler.classify("chicken_cold", MOBS);
        assertThat("chicken_cold kept", warm.isMob(), is(true));
        assertThat("chicken_cold canonical", warm.canonicalId(), equalTo("chicken_cold"));
        assertThat("chicken_cold base", warm.baseMobId(), equalTo("chicken"));
    }

    @Test
    @DisplayName("state variant is kept and tied to its base")
    void stateVariantClassified() {
        VariantReconciler.Classification charged = VariantReconciler.classify("creeper_charged", MOBS);
        assertThat(charged.isMob(), is(true));
        assertThat(charged.canonicalId(), equalTo("creeper_charged"));
        assertThat(charged.baseMobId(), equalTo("creeper"));

        VariantReconciler.Classification sheared = VariantReconciler.classify("sheep_sheared", MOBS);
        assertThat(sheared.isMob(), is(true));
        assertThat(sheared.canonicalId(), equalTo("sheep_sheared"));
        assertThat(sheared.baseMobId(), equalTo("sheep"));
    }

    @Test
    @DisplayName("pose variant is kept and tied to its base")
    void poseVariantClassified() {
        for (String suffix : new String[] { "_running", "_sitting", "_flower", "_star" }) {
            String id = "copper_golem" + suffix;
            VariantReconciler.Classification result = VariantReconciler.classify(id, MOBS);
            assertThat(id + " kept", result.isMob(), is(true));
            assertThat(id + " canonical", result.canonicalId(), equalTo(id));
            assertThat(id + " base", result.baseMobId(), equalTo("copper_golem"));
        }
    }

    @Test
    @DisplayName("attachment overlay is kept and tied to its base")
    void attachmentOverlayClassified() {
        VariantReconciler.Classification result = VariantReconciler.classify("happy_ghast_ropes", MOBS);
        assertThat(result.isMob(), is(true));
        assertThat(result.canonicalId(), equalTo("happy_ghast_ropes"));
        assertThat(result.baseMobId(), equalTo("happy_ghast"));
    }

    @Test
    @DisplayName("residual hand-aliases resolve to their Java ids")
    void residualAliases() {
        assertAliased("zombie_pigman", "zombified_piglin");
        assertAliased("evocation_illager", "evoker");
    }

    @Test
    @DisplayName("parched is a real Java mob, not a hard-drop")
    void parchedKept() {
        VariantReconciler.Classification result = VariantReconciler.classify("parched", MOBS);
        assertThat("parched kept", result.isMob(), is(true));
        assertThat("parched canonical", result.canonicalId(), equalTo("parched"));
    }

    private static void assertAliased(String bedrockName, String expectedJavaId) {
        VariantReconciler.Classification result = VariantReconciler.classify(bedrockName, MOBS);
        assertThat(bedrockName + " kept", result.isMob(), is(true));
        assertThat(bedrockName + " canonicalId", result.canonicalId(), equalTo(expectedJavaId));
        assertThat(bedrockName + " base (aliased base, not variant)", result.baseMobId(), is(nullValue()));
    }

    @Test
    @DisplayName("held-item and projectile animations are always dropped")
    void projectilesDropped() {
        for (String id : new String[] {
            "bow_pulling_0", "bow_pulling_1", "bow_pulling_2", "bow_standby",
            "crossbow_pulling_0", "crossbow_pulling_1", "crossbow_pulling_2",
            "crossbow_standby", "crossbow_arrow", "crossbow_rocket",
            "breeze_wind_0", "breeze_wind_1", "breeze_wind_5", "breeze_eyes",
            "evocation_fang", "spear", "wind_charge", "llamaspit"
        }) {
            VariantReconciler.Classification result = VariantReconciler.classify(id, MOBS);
            assertThat(id + " dropped", result.isMob(), is(false));
        }
    }

    @Test
    @DisplayName("Bedrock-only dev models and attachments are dropped")
    void bedrockOnlyDropped() {
        for (String id : new String[] { "humanoid_custom", "npc", "harness", "minecart" }) {
            VariantReconciler.Classification result = VariantReconciler.classify(id, MOBS);
            assertThat(id + " dropped", result.isMob(), is(false));
        }
    }

    @Test
    @DisplayName("suffix match without a matching base mob is dropped")
    void orphanSuffixDropped() {
        VariantReconciler.Classification result = VariantReconciler.classify("unicorn_cold", MOBS);
        assertThat("no base 'unicorn' exists, drop", result.isMob(), is(false));
    }

    @Test
    @DisplayName("unknown names that don't match any mob or suffix are dropped")
    void unknownNameDropped() {
        VariantReconciler.Classification result = VariantReconciler.classify("bogus_thing", MOBS);
        assertThat(result.isMob(), is(false));
    }

}
