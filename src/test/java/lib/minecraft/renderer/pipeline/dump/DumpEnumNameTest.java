package lib.minecraft.renderer.pipeline.dump;

import dev.simplified.image.pixel.BlendMode;
import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.asset.appearance.Age;
import lib.minecraft.renderer.asset.appearance.Size;
import lib.minecraft.renderer.asset.pack.MCMeta;
import lib.minecraft.renderer.asset.pack.PackCapability;
import lib.minecraft.renderer.asset.pack.rule.CitType;
import lib.minecraft.renderer.asset.pack.rule.CtmMethod;
import lib.minecraft.renderer.asset.pack.rule.Hand;
import lib.minecraft.renderer.face.Face;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * The enum constant names that reach the hashed dump, pinned as the stored strings they are.
 *
 * <p>{@code PipelineParityDump} writes several values through {@link Enum#name}, and a dump section
 * is hashed byte for byte by {@code manifest.dump.vanilla} and {@code manifest.dump.packs}. So a
 * constant renamed - a refactor with no behaviour in it at all - moves a promoted digest and arrives
 * at whoever reads the compare as a mover to investigate. The recorded case is real: a blend mode
 * spelled {@code ADDITIVE} became {@code ADD}, and the only thing that changed was the word.
 *
 * <p>What earns a row is being written as a <b>value</b>, because a key site's rename breaks a
 * loader loudly rather than quietly: the entity index builder calls {@code Size.valueOf} on keys out
 * of shipped JSON. {@code Size} is here all the same - it keys two sections and is written as a value
 * in a third, and the third is what earns it the row. {@code Block.TintTarget} earns two rows for the
 * opposite reason: it is written as a value at the block tint and spells the keys of the colour-map
 * section, and nothing outside the digest guards the second - the colour-map loader composes its
 * texture path from the target's own {@code colorMapName} rather than from the constant, so a rename
 * still resolves the same PNG and moves only the hash.
 *
 * <p>Failing here is not a veto. A constant genuinely worth renaming is renamed, this roster moves
 * with it, and the same commit registers the movers through {@code parityExpect} - which is what
 * turns an unexplained digest move into a declared one.
 */
@DisplayName("The enum names the parity dump stores are the ones it stored last time")
final class DumpEnumNameTest {

    /**
     * One line of the dump writer that stores a value by its own name, with the enum it names.
     *
     * @param line the writer's line, trimmed, as the site scan below reads it
     * @param type the enum whose constant that line writes
     * @param constants that enum's constant names, in declaration order
     */
    private record ValueSite(String line, Class<? extends Enum<?>> type, List<String> constants) {}

    /**
     * Every value site of the dump, each against the enum whose constants it stores.
     *
     * <p>Ordered as the dump writes them so a reader can find the site, and the constants spelled
     * out rather than read off the enum: derived, the comparison is the enum against itself and
     * holds for any rename at all.
     *
     * <p>Site and enum are one row because that is what pins which enums are here. An enum dropped
     * from this list takes its line with it, and the scan below then finds a line in the writer that
     * nothing here accounts for - so narrowing the roster back to the state that let a rename
     * through fails the same way the rename does. What text still cannot decide is which enum a line
     * names, so a row whose type and constants move together is a rewrite for a person to read
     * rather than a drift.
     */
    private static final List<ValueSite> VALUE_SITES = List.of(
        new ValueSite("root.add(\"capabilities\", CanonicalJson.strings(pack.capabilities().stream()"
            + ".map(Enum::name).toList()));", PackCapability.class,
            List.of("VANILLA_CORE", "OPTIFINE_RULES", "CATHARSIS_CONVENTIONS")),
        new ValueSite("section.addProperty(\"type\", gui.type().name());",
            MCMeta.GuiScaling.Type.class, List.of("STRETCH", "TILE", "NINE_SLICE")),
        new ValueSite("root.addProperty(\"source\", block.source().name());", Block.Source.class,
            List.of("PRIMARY", "BLOCKSTATE_ONLY", "TILE_ENTITY")),
        new ValueSite("root.addProperty(\"target\", tint.target().name());", Block.TintTarget.class,
            List.of("NONE", "GRASS", "FOLIAGE", "DRY_FOLIAGE", "WATER", "CONSTANT")),
        new ValueSite("root.add(\"color_maps\", CanonicalJson.map(ColorMapLoader.load(stack), "
            + "Enum::name, colorMap -> {", Block.TintTarget.class,
            List.of("NONE", "GRASS", "FOLIAGE", "DRY_FOLIAGE", "WATER", "CONSTANT")),
        new ValueSite("root.addProperty(\"type\", rule.type().name());", CitType.class,
            List.of("ITEM", "ENCHANTMENT", "ARMOR", "ELYTRA")),
        new ValueSite("root.addProperty(\"hand\", rule.hand().name());", Hand.class,
            List.of("ANY", "MAIN", "OFF")),
        new ValueSite("root.addProperty(\"method\", rule.method().name());", CtmMethod.class,
            List.of("CTM", "CTM_COMPACT", "HORIZONTAL", "VERTICAL", "HORIZONTAL_VERTICAL",
                "VERTICAL_HORIZONTAL", "TOP", "RANDOM", "REPEAT", "FIXED", "OVERLAY", "OVERLAY_CTM",
                "OVERLAY_RANDOM", "OVERLAY_REPEAT", "OVERLAY_FIXED")),
        new ValueSite("root.add(\"faces\", CanonicalJson.ordered(rule.faces(), "
            + "face -> new JsonPrimitive(face.name())));", Face.class,
            List.of("DOWN", "UP", "NORTH", "SOUTH", "WEST", "EAST")),
        new ValueSite("root.addProperty(\"blend\", overlay.pass().blend().name());", BlendMode.class,
            List.of("NORMAL", "REPLACE", "ADD", "MULTIPLY", "OVERLAY", "QUADRATIC_ADD")),
        new ValueSite("root.addProperty(\"value\", age.value().name());", Age.class,
            List.of("ADULT", "BABY")),
        new ValueSite("root.addProperty(\"value\", size.value().name());", Size.class,
            List.of("SMALL", "MEDIUM", "LARGE")));

    /** The dump writer, which is the file every site below is counted in. */
    private static final Path DUMP =
        Path.of("src/test/java/lib/minecraft/renderer/pipeline/dump/PipelineParityDump.java");

    /**
     * The two spellings that turn something into its own name.
     *
     * <p>A call and a method reference handed to one of the canonical-JSON factories, and the second
     * is the form three of the sites below use - a pattern that saw only the call could not see a
     * whole map's keys or a whole array's elements being spelled by an enum.
     */
    private static final Pattern NAME_CALL = Pattern.compile("\\.name\\(\\)|Enum::name");

    /**
     * The lines that spell a map's keys off an enum a rename would stop a loader on.
     *
     * <p>Here rather than in the roster above because the digest is not what protects them: the entity
     * index builder reads the constant back through {@code valueOf} on shipped JSON, so the rename
     * fails before anything is hashed.
     */
    private static final List<String> KEY_SITES = List.of(
        "root.add(\"size_models\", CanonicalJson.map(axes.sizeModels(), Enum::name, PipelineParityDump::entityModel));",
        "root.add(\"size_scales\", CanonicalJson.map(axes.sizeScales(), Enum::name, scale -> CanonicalJson.number(scale)));");

    /** The lines that match the scan and are not enums at all - a path segment and an NBT key. */
    private static final List<String> PLAIN_SITES = List.of(
        "root.addProperty(\"path\", \"assets/\" + texture.id().namespace() + \"/textures/\" + texture.id().name() + \".png\");",
        "root.addProperty(\"name\", key.name());");

    /**
     * Every line of the dump writer that spells something by its own name, in one sorted list.
     *
     * <p>The lines themselves rather than a count of them, because a count is answered by one site
     * added and another removed, and answered again by a site edited to name a different thing at
     * the same key. Sorted rather than held in file order: where a site sits between two others is
     * not a property of the dump, and the three groupings above are what a reader needs instead.
     */
    private static final List<String> NAME_SITES =
        Stream.of(VALUE_SITES.stream().map(ValueSite::line), KEY_SITES.stream(), PLAIN_SITES.stream())
            .flatMap(lines -> lines)
            .sorted()
            .toList();

    @Test
    @DisplayName("every pinned enum still spells its constants the way the dump stored them")
    void theStoredSpellingsHaveNotMoved() {
        List<String> moved = VALUE_SITES.stream()
            .filter(site -> !constantsOf(site.type()).equals(site.constants()))
            .map(site -> site.type().getName() + ": " + constantsOf(site.type())
                + " against the stored " + site.constants())
            .toList();

        assertThat("the roster is empty, which would make this case hold for any rename at all",
            VALUE_SITES, is(not(empty())));
        assertThat("enums whose constants no longer spell what the promoted dump digests were taken "
            + "over. Order is part of it: several of these are written through an ordered collection "
            + "and a reordered enum moves the same bytes a rename does. Rename them if they are "
            + "worth renaming, move this roster in the same commit, and register the movers with "
            + "parityExpect so the digest move arrives declared", moved, is(empty()));
    }

    @Test
    @DisplayName("the dump spells a name nowhere this roster has not accounted for")
    void everyNameSiteIsAccountedFor() throws IOException {
        List<String> found = Files.readString(DUMP).lines()
            .map(String::trim)
            .filter(line -> NAME_CALL.matcher(line).find())
            .sorted()
            .toList();

        assertThat("the site roster is empty, which would make this case hold for a dump writer "
            + "that spells every one of its keys and values off an enum", NAME_SITES, is(not(empty())));
        assertThat("the lines of the dump writer that spell something by its own name, against the "
            + "ones this roster was written against. A line here is one of three things and only a "
            + "person can say which: a plain accessor that happens to be called name, a key site a "
            + "loader already breaks loudly on, or a value site, which is carried above beside the "
            + "enum it stores and cannot be dropped without dropping its line from here too. A line "
            + "that left is the same decision backwards", found, equalTo(NAME_SITES));
    }

    /**
     * Returns one enum's constant names in declaration order.
     *
     * @param type the enum class
     * @return its constants, in order
     */
    private static List<String> constantsOf(Class<? extends Enum<?>> type) {
        return Arrays.stream(type.getEnumConstants()).map(Enum::name).toList();
    }

}
