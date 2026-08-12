package lib.minecraft.renderer.asset.pack;

import lib.minecraft.renderer.exception.PipelineException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Coverage of {@link PackId}, the normalized pack identity: its {@link PackId#normalize} transform
 * (formatting-code strip, lowercase, hyphen-collapse, letter-led digit widening), the reserved
 * constants, and the alphabet-validating constructor.
 */
@DisplayName("PackId normalization + validation")
class PackIdTest {

    @Test
    @DisplayName("normalize lowercases, hyphenates, and unifies the namespace/dirname duality")
    void normalizeBasics() {
        assertThat(PackId.normalize("eureka"), is(Optional.of(new PackId("eureka"))));
        assertThat(PackId.normalize("FurSky Reborn"), is(Optional.of(new PackId("fursky-reborn"))));
        assertThat(PackId.normalize("hypixel_skyblock"), is(Optional.of(new PackId("hypixel-skyblock"))));
        assertThat(PackId.normalize("Hypixel SkyBlock"), is(Optional.of(new PackId("hypixel-skyblock"))));
        assertThat(PackId.normalize("A_B"), is(Optional.of(new PackId("a-b"))));
    }

    @Test
    @DisplayName("normalize strips § formatting codes before hyphenating")
    void normalizeStripsSectionCodes() {
        assertThat(PackId.normalize("§3§ktest§r"), is(Optional.of(new PackId("test"))));
    }

    @Test
    @DisplayName("digits survive in non-leading positions; a leading digit run is trimmed away")
    void normalizeDigitWidening() {
        assertThat(PackId.normalize("emerald-16x"), is(Optional.of(new PackId("emerald-16x"))));
        assertThat(PackId.normalize("16-emerald"), is(Optional.of(new PackId("emerald"))));
        assertThat(PackId.normalize("26.1"), is(Optional.empty()));
        assertThat(PackId.normalize("123"), is(Optional.empty()));
    }

    @Test
    @DisplayName("a purely symbolic or empty input normalizes to nothing")
    void normalizeEmpties() {
        assertThat(PackId.normalize("..."), is(Optional.empty()));
        assertThat(PackId.normalize(""), is(Optional.empty()));
        assertThat(PackId.normalize("   "), is(Optional.empty()));
    }

    @Test
    @DisplayName("the reserved ids are well-formed and flagged")
    void reserved() {
        assertThat(PackId.normalize("Vanilla"), is(Optional.of(PackId.VANILLA)));
        assertThat(PackId.VANILLA.isReserved(), is(true));
        assertThat(PackId.MINECRAFT.isReserved(), is(true));
        assertThat(new PackId("eureka").isReserved(), is(false));
    }

    @Test
    @DisplayName("the constructor rejects ids outside the alphabet")
    void validation() {
        assertThrows(PipelineException.class, () -> new PackId("26-1"));   // leading digit
        assertThrows(PipelineException.class, () -> new PackId("-x"));     // leading hyphen
        assertThrows(PipelineException.class, () -> new PackId("x-"));     // trailing hyphen
        assertThrows(PipelineException.class, () -> new PackId("x--y"));   // double hyphen
        assertThrows(PipelineException.class, () -> new PackId("Ab"));     // uppercase
        assertThrows(PipelineException.class, () -> new PackId(""));       // empty
        // a well-formed id constructs cleanly
        assertThat(new PackId("emerald-16x").value(), equalTo("emerald-16x"));
    }

}
