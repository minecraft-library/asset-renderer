package lib.minecraft.renderer.vanilla;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lib.minecraft.renderer.support.ClientAssetsExtension;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;

/**
 * Every {@link Biome.Vanilla} row held to its biome definition in the extracted client data: the
 * temperature, the downfall, the {@code effects} grass-colour modifier and each {@code effects.*_color},
 * where a row's absent water override stands for {@link TintSource#WATER}'s default.
 * <p>
 * The table is transcribed, and no render the store holds reads a departing colour - the block sweep
 * and the carried block tint at the inventory point, and the fluid manifest reads only water - so this
 * is the one thing that holds the transcription to vanilla.
 */
@DisplayName("Biome.Vanilla holds every row to its 26.1 biome definition")
@ExtendWith(ClientAssetsExtension.class)
class BiomeVanillaTest {

    /** The biome definitions' directory under the extracted client data. */
    private static final @NotNull String BIOMES = "worldgen/biome";

    @Test
    @DisplayName("every biome definition has a row and every row a definition")
    void everyDefinitionHasARow() {
        Set<String> rows = Stream.of(Biome.Vanilla.values()).map(Biome.Vanilla::id)
            .collect(Collectors.toCollection(TreeSet::new));
        assertThat(rows, is(definitions()));
    }

    @Test
    @DisplayName("every row's climate, modifier and colours are its definition's")
    void everyRowIsItsDefinition() {
        List<String> departures = new ArrayList<>();
        for (Biome.Vanilla biome : Biome.Vanilla.values()) {
            JsonObject definition = definition(biome);
            JsonObject effects = definition.getAsJsonObject("effects");
            check(departures, biome, "temperature", biome.temperature(), definition.get("temperature").getAsFloat());
            check(departures, biome, "downfall", biome.downfall(), definition.get("downfall").getAsFloat());
            check(departures, biome, "grass_color_modifier", biome.grassColorModifier().name(),
                optional(effects, "grass_color_modifier").map(JsonElement::getAsString).orElse("none").toUpperCase());
            check(departures, biome, "grass_color", biome.grassColorOverride(), colour(effects, "grass_color"));
            check(departures, biome, "foliage_color", biome.foliageColorOverride(), colour(effects, "foliage_color"));
            check(departures, biome, "dry_foliage_color", biome.dryFoliageColorOverride(), colour(effects, "dry_foliage_color"));
            check(departures, biome, "water_color", biome.waterColorOverride().orElse(TintSource.WATER.defaultArgb()),
                colour(effects, "water_color").orElse(TintSource.WATER.defaultArgb()));
        }
        assertThat("rows departing from their definition", departures, is(empty()));
    }

    /**
     * Records one field that departs from the definition.
     *
     * @param departures the departures found so far
     * @param biome the row
     * @param field the field's definition name
     * @param actual what the row holds
     * @param expected what the definition holds
     */
    private static void check(
        @NotNull List<String> departures, @NotNull Biome.Vanilla biome, @NotNull String field,
        @NotNull Object actual, @NotNull Object expected
    ) {
        if (!actual.equals(expected))
            departures.add(biome.id() + " " + field + ": " + render(actual) + " where the definition has " + render(expected));
    }

    /**
     * Spells a value the way a failure reads best, colours as hex.
     *
     * @param value the value
     * @return its spelling
     */
    private static @NotNull String render(@NotNull Object value) {
        if (value instanceof Integer argb) return String.format("0x%08X", argb);
        if (value instanceof Optional<?> optional) return optional.map(BiomeVanillaTest::render).orElse("none");
        return String.valueOf(value);
    }

    /**
     * Reads one {@code #rrggbb} colour of a definition's effects, opaque, as the table spells it.
     *
     * @param effects the definition's effects
     * @param key the colour's member
     * @return the colour, or empty where the definition names none
     */
    private static @NotNull Optional<Integer> colour(@NotNull JsonObject effects, @NotNull String key) {
        return optional(effects, key).map(value -> 0xFF000000 | Integer.parseInt(value.getAsString().substring(1), 16));
    }

    /**
     * Reads one member of a JSON object.
     *
     * @param object the object
     * @param key the member
     * @return the member, or empty where the object has none
     */
    private static @NotNull Optional<JsonElement> optional(@NotNull JsonObject object, @NotNull String key) {
        return Optional.ofNullable(object.get(key));
    }

    /**
     * Reads a row's biome definition.
     *
     * @param biome the row
     * @return the parsed definition
     */
    private static @NotNull JsonObject definition(@NotNull Biome.Vanilla biome) {
        Path file = biomes().resolve(biome.id().substring(biome.id().indexOf(':') + 1) + ".json");
        try {
            return JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /**
     * Lists the ids every biome definition in the extracted client data declares.
     *
     * @return the namespaced ids, sorted
     */
    private static @NotNull Set<String> definitions() {
        try (Stream<Path> files = Files.list(biomes())) {
            return files.map(file -> file.getFileName().toString())
                .filter(name -> name.endsWith(".json"))
                .map(name -> "minecraft:" + name.substring(0, name.length() - ".json".length()))
                .collect(Collectors.toCollection(TreeSet::new));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /**
     * The biome definitions' directory in the extracted client data.
     *
     * @return the directory
     */
    private static @NotNull Path biomes() {
        return ClientAssetsExtension.vanillaRoot().resolve(VanillaPaths.VANILLA_DATA_ROOT).resolve(BIOMES);
    }

}
