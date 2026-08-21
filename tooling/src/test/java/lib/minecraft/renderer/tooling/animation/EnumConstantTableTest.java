package lib.minecraft.renderer.tooling.animation;

import lib.minecraft.renderer.client.ClientAcquisition;
import lib.minecraft.renderer.client.ClientOptions;
import lib.minecraft.renderer.tooling.kernel.ClassNodeCache;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The enum-constant reader against the real client jar.
 *
 * <p>Pinned on the two enums a pose actually turns on, and on one type that merely looks like one.
 * The arm pose is the interesting case in both directions: it declares eleven constants, the last of
 * which carries a body and is therefore built through an anonymous subclass, and it stores two
 * booleans that the model's own code reads back through accessors rather than as fields.
 *
 * <p>Tagged {@code slow}: it reads the downloaded client jar.
 */
@Tag("slow")
@DisplayName("the enum constant reader")
class EnumConstantTableTest {

    private static final @NotNull String ARM_POSE = "net/minecraft/client/model/HumanoidModel$ArmPose";
    private static final @NotNull String HUMANOID_ARM = "net/minecraft/world/entity/HumanoidArm";
    private static final @NotNull String ROTATIONS = "net/minecraft/core/Rotations";

    private static ClassNodeCache cache;

    @BeforeAll
    static void open() {
        cache = ClassNodeCache.open(ClientAcquisition.downloadJarToCache(ClientOptions.defaults()));
    }

    @AfterAll
    static void close() {
        if (cache != null) cache.close();
    }

    @Test
    @DisplayName("an arm pose reads as eleven constants in declaration order")
    void everyConstantIsFoundIncludingTheOneWithABody() {
        // SPEAR is the trap this reader exists to survive. It carries a body of its own, so it is
        // constructed as an anonymous subclass rather than as the enum, and anything keyed on the
        // allocation drops it - leaving a ten-constant enum that looks entirely reasonable and is
        // missing exactly the constant at the ordinal a switch sends its hardest arm to.
        EnumConstantTable table = read(ARM_POSE);
        assertEquals(
            List.of("EMPTY", "ITEM", "BLOCK", "BOW_AND_ARROW", "THROW_TRIDENT", "CROSSBOW_CHARGE",
                "CROSSBOW_HOLD", "SPYGLASS", "TOOT_HORN", "BRUSH", "SPEAR"),
            table.constants().stream().map(EnumConstantTable.Constant::name).toList(),
            "the arm poses, in the order they are declared");
        assertEquals(10, table.byName("SPEAR").orElseThrow().ordinal(),
            "the constant with a body is still numbered where it was declared");
    }

    @Test
    @DisplayName("a constant answers what its constructor was handed, through a subclass if there is one")
    void constructorArgumentsBindToTheFieldsTheyAreStoredIn() {
        // Both of these are read in a pose body through accessors, so the walk answers the accessor
        // by answering the field. THROW_TRIDENT and SPEAR disagree on one of the two, which is what
        // makes this more than a check that some boolean came back.
        EnumConstantTable table = read(ARM_POSE);
        assertEquals(Map.of("twoHanded", 0d, "affectsOffhandPose", 1d),
            table.byName("SPEAR").orElseThrow().fields(),
            "a spear is held in one hand and still changes what the other does");
        assertEquals(Map.of("twoHanded", 1d, "affectsOffhandPose", 1d),
            table.byName("CROSSBOW_HOLD").orElseThrow().fields(),
            "a held crossbow takes both hands");
        assertEquals(Map.of("twoHanded", 0d, "affectsOffhandPose", 0d),
            table.byName("EMPTY").orElseThrow().fields(),
            "an empty hand does neither");
    }

    @Test
    @DisplayName("a constant's non-numeric arguments are left out rather than guessed at")
    void onlyWhatReadsAsANumberIsBound() {
        // Which arm is declared with an id and two strings. The id comes back and the strings do
        // not, which is the whole rule: a field is bound when the constructor was handed a number
        // for it, and absent otherwise, so a question about one refuses where it is asked instead of
        // being answered with a zero that looks like an answer.
        EnumConstantTable table = read(HUMANOID_ARM);
        assertEquals(List.of("LEFT", "RIGHT"),
            table.constants().stream().map(EnumConstantTable.Constant::name).toList(),
            "which arm, in vanilla's order");
        assertEquals(Map.of("id", 0d), table.byName("LEFT").orElseThrow().fields(),
            "the left arm's number, and neither of its names");
        assertEquals(Map.of("id", 1d), table.byName("RIGHT").orElseThrow().fields(),
            "the right arm's number, and neither of its names");
    }

    @Test
    @DisplayName("something that is not an enum is answered as such rather than enumerated")
    void aRecordIsNotAnEnum() {
        // An armour stand's headPose is a Rotations, and the only thing that says so is the class it
        // names: a render-state field is otherwise told apart by not being primitive, which a record
        // also is not. Enumerating one would answer a pose with constants nothing declares.
        assertTrue(EnumConstantTable.of(cache, ROTATIONS).isEmpty(),
            "a record is not an enum, whatever its descriptor looks like");
    }

    private static @NotNull EnumConstantTable read(@NotNull String type) {
        Optional<EnumConstantTable> table = EnumConstantTable.of(cache, type);
        assertTrue(table.isPresent(), type + " is expected to read as an enum");
        return table.orElseThrow();
    }

}
