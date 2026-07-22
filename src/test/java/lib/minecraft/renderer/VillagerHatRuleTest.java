package lib.minecraft.renderer;

import lib.minecraft.renderer.asset.pack.MCMeta.Villager.Hat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Truth table for the villager robe pass' mesh select - the predicate deciding whether the type / robe
 * overlay draws its full mesh or the head-stripped alternate, so a profession hat is never stacked on
 * top of a type hat.
 */
@DisplayName("Villager hat mesh-select rule")
class VillagerHatRuleTest {

    @Test
    @DisplayName("a hatless profession always draws the full mesh")
    void hatlessProfessionNeverSuppresses() {
        assertThat(EntityRenderer.useFullModel(Hat.NONE, Hat.NONE), is(true));
        assertThat(EntityRenderer.useFullModel(Hat.NONE, Hat.PARTIAL), is(true));
        assertThat(EntityRenderer.useFullModel(Hat.NONE, Hat.FULL), is(true));
    }

    @Test
    @DisplayName("a partial-hat profession suppresses only over a full-hat type")
    void partialProfessionSuppressesOnlyOverAFullType() {
        assertThat(EntityRenderer.useFullModel(Hat.PARTIAL, Hat.NONE), is(true));
        assertThat(EntityRenderer.useFullModel(Hat.PARTIAL, Hat.PARTIAL), is(true));
        assertThat(EntityRenderer.useFullModel(Hat.PARTIAL, Hat.FULL), is(false));
    }

    @Test
    @DisplayName("a full-hat profession always suppresses, whatever the type wears")
    void fullProfessionAlwaysSuppresses() {
        assertThat(EntityRenderer.useFullModel(Hat.FULL, Hat.NONE), is(false));
        assertThat(EntityRenderer.useFullModel(Hat.FULL, Hat.PARTIAL), is(false));
        assertThat(EntityRenderer.useFullModel(Hat.FULL, Hat.FULL), is(false));
    }

    @Test
    @DisplayName("the vanilla-reachable rows: plains/none and plains/butcher keep the robe head, desert/butcher and plains/farmer strip it")
    void vanillaReachableRows() {
        // plains ships no sidecar (type NONE); desert and snow ship hat=full. The professions shipping a
        // sidecar are farmer / fisherman / fletcher / librarian / shepherd (full) and butcher (partial).
        assertThat("plains villager, no profession", EntityRenderer.useFullModel(Hat.NONE, Hat.NONE), is(true));
        assertThat("plains butcher keeps the headband over a hatless type", EntityRenderer.useFullModel(Hat.PARTIAL, Hat.NONE), is(true));
        assertThat("desert butcher drops the turban", EntityRenderer.useFullModel(Hat.PARTIAL, Hat.FULL), is(false));
        assertThat("plains farmer's straw hat stands alone", EntityRenderer.useFullModel(Hat.FULL, Hat.NONE), is(false));
    }

}
