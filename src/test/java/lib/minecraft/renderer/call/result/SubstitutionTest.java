package lib.minecraft.renderer.call.result;

import dev.simplified.gson.JsonTree;
import dev.simplified.util.Possible;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Coverage of {@link Substitution}: it refuses a present state and a naming item that does not match
 * the kind, each factory names its kind, the state the kind is drawn under and the naming item, the
 * natural order runs kind, id, state, then the naming item, and a row round-trips through its JSON -
 * lowercase tokens, the naming item written only where there is one, and an unknown token refused.
 */
@DisplayName("A substitution names what a render drew a stand-in for")
class SubstitutionTest {

    @Test
    @DisplayName("a present state is refused, naming the id")
    void refusesAPresentState() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
            () -> Substitution.texture("minecraft:block/stone", Possible.State.PRESENT));

        assertThat(refused.getMessage(), containsString("'minecraft:block/stone'"));
    }

    @Test
    @DisplayName("each factory names its kind, its state and the item that named the id")
    void eachFactoryNamesItsKind() {
        assertThat(Substitution.texture("minecraft:block/stone", Possible.State.EMPTY),
            is(new Substitution(Substitution.Kind.TEXTURE, Possible.State.EMPTY, "minecraft:block/stone", Optional.empty())));
        assertThat(Substitution.texture("#side", Possible.State.ABSENT),
            is(new Substitution(Substitution.Kind.TEXTURE, Possible.State.ABSENT, "#side", Optional.empty())));
        assertThat(Substitution.subject("minecraft:nothing"),
            is(new Substitution(Substitution.Kind.SUBJECT, Possible.State.ABSENT, "minecraft:nothing", Optional.empty())));
        assertThat(Substitution.leafModel("minecraft:item/nothing", "minecraft:stick"),
            is(new Substitution(Substitution.Kind.LEAF_MODEL, Possible.State.ABSENT, "minecraft:item/nothing", Optional.of("minecraft:stick"))));
        assertThat(Substitution.citModel("minecraft:optifine/cit/nothing", "minecraft:stick"),
            is(new Substitution(Substitution.Kind.CIT_MODEL, Possible.State.ABSENT, "minecraft:optifine/cit/nothing", Optional.of("minecraft:stick"))));
        assertThat(Substitution.special("mod:statue", "minecraft:stick"),
            is(new Substitution(Substitution.Kind.SPECIAL, Possible.State.ABSENT, "mod:statue", Optional.of("minecraft:stick"))));
        assertThat(Substitution.itemModel("minecraft:stick", Possible.State.EMPTY),
            is(new Substitution(Substitution.Kind.ITEM_MODEL, Possible.State.EMPTY, "minecraft:stick", Optional.empty())));
        assertThat(Substitution.itemModel("minecraft:stick", Possible.State.ABSENT),
            is(new Substitution(Substitution.Kind.ITEM_MODEL, Possible.State.ABSENT, "minecraft:stick", Optional.empty())));
    }

    @Test
    @DisplayName("a leaf model, CIT model or special kind names the item that named the id, and no other kind does")
    void theNamingItemMatchesTheKind() {
        IllegalArgumentException unnamed = assertThrows(IllegalArgumentException.class,
            () -> new Substitution(Substitution.Kind.LEAF_MODEL, Possible.State.ABSENT, "minecraft:item/a", Optional.empty()));
        assertThat(unnamed.getMessage(), containsString("'minecraft:item/a'"));

        for (Substitution.Kind kind : List.of(Substitution.Kind.CIT_MODEL, Substitution.Kind.SPECIAL)) {
            assertThrows(IllegalArgumentException.class,
                () -> new Substitution(kind, Possible.State.ABSENT, "minecraft:item/a", Optional.empty()), kind.name());
        }

        for (Substitution.Kind kind : List.of(Substitution.Kind.TEXTURE, Substitution.Kind.SUBJECT, Substitution.Kind.ITEM_MODEL)) {
            assertThrows(IllegalArgumentException.class,
                () -> new Substitution(kind, Possible.State.ABSENT, "minecraft:a", Optional.of("minecraft:stick")), kind.name());
        }
    }

    @Test
    @DisplayName("the natural order is kind, then id, then state, then the naming item")
    void ordersByKindIdStateThenNamingItem() {
        List<Substitution> ordered = List.of(
            Substitution.texture("minecraft:block/a", Possible.State.EMPTY),
            Substitution.texture("minecraft:block/a", Possible.State.ABSENT),
            Substitution.texture("minecraft:block/b", Possible.State.EMPTY),
            Substitution.subject("minecraft:a"),
            Substitution.subject("minecraft:b"),
            Substitution.leafModel("minecraft:item/a", "minecraft:apple"),
            Substitution.leafModel("minecraft:item/a", "minecraft:stick"),
            Substitution.leafModel("minecraft:item/b", "minecraft:apple"),
            Substitution.citModel("minecraft:item/a", "minecraft:apple"),
            Substitution.special("mod:a", "minecraft:apple"),
            Substitution.itemModel("minecraft:apple", Possible.State.EMPTY),
            Substitution.itemModel("minecraft:apple", Possible.State.ABSENT));

        for (long seed = 0; seed < 8; seed++) {
            List<Substitution> shuffled = new ArrayList<>(ordered);
            Collections.shuffle(shuffled, new Random(seed));
            Collections.sort(shuffled);
            assertThat("sorted from shuffle " + seed, shuffled, is(ordered));
        }
    }

    @Test
    @DisplayName("the order agrees with equality")
    void theOrderAgreesWithEquality() {
        Substitution leaf = Substitution.leafModel("minecraft:item/a", "minecraft:apple");

        assertThat(leaf.compareTo(Substitution.leafModel("minecraft:item/a", "minecraft:apple")), is(0));
        assertThat("a different naming item",
            leaf.compareTo(Substitution.leafModel("minecraft:item/a", "minecraft:stick")) == 0, is(false));
        assertThat("an id named by an item apart from the same id with no item",
            Substitution.texture("minecraft:item/a", Possible.State.ABSENT).compareTo(leaf) == 0, is(false));
    }

    @Test
    @DisplayName("every kind round-trips through its JSON row")
    void roundTripsThroughJson() {
        List<Substitution> rows = List.of(
            Substitution.texture("minecraft:block/stone", Possible.State.EMPTY),
            Substitution.texture("", Possible.State.ABSENT),
            Substitution.subject("minecraft:nothing"),
            Substitution.leafModel("minecraft:item/nothing", "minecraft:stick"),
            Substitution.citModel("minecraft:optifine/cit/nothing", "minecraft:stick"),
            Substitution.special("mod:statue", "minecraft:stick"),
            Substitution.itemModel("minecraft:stick", Possible.State.EMPTY),
            Substitution.itemModel("minecraft:stick", Possible.State.ABSENT));

        for (Substitution row : rows)
            assertThat(row.toString(), Substitution.parse(row.toJson()), is(row));
    }

    @Test
    @DisplayName("the row writes lowercase tokens and the naming item only where there is one")
    void writesTheRowShape() {
        assertThat(Substitution.leafModel("minecraft:item/nothing", "minecraft:stick").toJson().toGson().toString(),
            is("{\"kind\":\"leaf_model\",\"state\":\"absent\",\"id\":\"minecraft:item/nothing\",\"namedBy\":\"minecraft:stick\"}"));
        assertThat(Substitution.texture("minecraft:block/stone", Possible.State.EMPTY).toJson().toGson().toString(),
            is("{\"kind\":\"texture\",\"state\":\"empty\",\"id\":\"minecraft:block/stone\"}"));
    }

    @Test
    @DisplayName("a row naming no kind or state constant, or a present state, is refused")
    void refusesAnUnknownToken() {
        IllegalArgumentException kind = assertThrows(IllegalArgumentException.class,
            () -> parse("{\"kind\":\"sprite\",\"state\":\"absent\",\"id\":\"minecraft:block/stone\"}"));
        assertThat(kind.getMessage(), containsString("'sprite'"));

        IllegalArgumentException state = assertThrows(IllegalArgumentException.class,
            () -> parse("{\"kind\":\"texture\",\"state\":\"missing\",\"id\":\"minecraft:block/stone\"}"));
        assertThat(state.getMessage(), containsString("'missing'"));

        assertThrows(IllegalArgumentException.class,
            () -> parse("{\"kind\":\"texture\",\"id\":\"minecraft:block/stone\"}"));
        assertThrows(IllegalArgumentException.class,
            () -> parse("{\"kind\":\"texture\",\"state\":\"present\",\"id\":\"minecraft:block/stone\"}"));
    }

    /**
     * Parses one substitution row from its JSON text.
     *
     * @param row the row's JSON text
     * @return the parsed substitution
     */
    private static @NotNull Substitution parse(@NotNull String row) {
        return Substitution.parse(JsonTree.parse(row.getBytes(StandardCharsets.UTF_8)));
    }

}
