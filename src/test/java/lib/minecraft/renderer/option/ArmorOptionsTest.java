package lib.minecraft.renderer.option;

import lib.minecraft.renderer.asset.equipment.ArmorMaterial;
import lib.minecraft.renderer.asset.equipment.ArmorPiece;
import lib.minecraft.renderer.asset.equipment.ArmorSlot;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * The two obligations {@link ArmorOptions#equipped()} carries for every consumer: that it can answer
 * for <b>every</b> {@link ArmorSlot} constant, and that it hands them back in the back-to-front
 * composite order the slot's declaration order defines.
 * <p>
 * <b>The coverage test is the fifth-slot guard.</b> A new {@code ArmorSlot} constant with no
 * matching field on {@link ArmorOptions} would be silently unequippable in every consumer - the entity
 * renderer, the player renderer and the glint pass alike - and no renderer is the right place to
 * notice that on this type's behalf. {@code equipped()}'s own exhaustive switch makes it a compile
 * error, and this pins the same rule from outside so it survives a rewrite of that body.
 */
@DisplayName("ArmorOptions slot coverage and composite order")
class ArmorOptionsTest {

    private static final @NotNull ArmorPiece PIECE = ArmorPiece.of(ArmorMaterial.IRON);

    @Test
    @DisplayName("equipped() answers for every ArmorSlot constant when all are worn")
    void equippedCoversEverySlot() {
        assertThat(EnumSet.copyOf(fullyArmored().equipped().keySet()), equalTo(EnumSet.allOf(ArmorSlot.class)));
    }

    @Test
    @DisplayName("equipped() iterates in declaration order - the back-to-front composite contract")
    void equippedIteratesInCompositeOrder() {
        List<ArmorSlot> declared = new ArrayList<>(EnumSet.allOf(ArmorSlot.class));
        assertThat(new ArrayList<>(fullyArmored().equipped().keySet()), equalTo(declared));
        assertThat(declared, contains(ArmorSlot.LEGGINGS, ArmorSlot.HELMET, ArmorSlot.CHESTPLATE, ArmorSlot.BOOTS));
    }

    @Test
    @DisplayName("piece() answers every slot constant, and answers empty where nothing is worn")
    void pieceAnswersEverySlot() {
        ArmorOptions bare = ArmorOptions.defaults();
        ArmorSlot.forEach(slot ->
            assertThat("slot " + slot + " must be answered", bare.piece(slot), is(Optional.empty())));
    }

    @Test
    @DisplayName("equipped() omits an unworn slot rather than mapping it to empty")
    void unwornSlotsAreAbsent() {
        ArmorOptions helmetOnly = ArmorOptions.builder().helmet(Optional.of(PIECE)).build();
        assertThat(helmetOnly.equipped().keySet(), contains(ArmorSlot.HELMET));
    }

    /** An options bag with every declared slot worn - the only input that can prove full coverage. */
    private static @NotNull ArmorOptions fullyArmored() {
        return ArmorOptions.builder()
            .helmet(Optional.of(PIECE))
            .chestplate(Optional.of(PIECE))
            .leggings(Optional.of(PIECE))
            .boots(Optional.of(PIECE))
            .build();
    }

}
