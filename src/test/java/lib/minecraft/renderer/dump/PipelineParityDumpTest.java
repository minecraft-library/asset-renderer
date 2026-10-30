package lib.minecraft.renderer.dump;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.util.Possible;
import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.Item;
import lib.minecraft.renderer.asset.equipment.Shell;
import lib.minecraft.renderer.asset.mesh.EntityMesh;
import lib.minecraft.renderer.asset.pack.MCMeta;
import lib.minecraft.renderer.asset.pose.PaceCeiling;
import lib.minecraft.renderer.asset.pose.PoseStyle;
import lib.minecraft.renderer.asset.pose.StyleCatalog;
import lib.minecraft.renderer.asset.pose.StyleClock;
import lib.minecraft.renderer.content.index.RendererContext;
import lib.minecraft.renderer.engine.math.Vector3f;
import lib.minecraft.renderer.engine.pose.StyleDriver;
import lib.minecraft.renderer.store.ParityJson;
import lib.minecraft.renderer.vanilla.appearance.Age;
import lib.minecraft.renderer.vanilla.appearance.AppearanceGate;
import lib.minecraft.renderer.vanilla.appearance.Flag;
import lib.minecraft.renderer.vanilla.equipment.ArmorForm;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Coverage of how the pipeline dump writes an id its key source lists but whose lookup holds no row,
 * of how it writes the forms that re-pose a wearer, of how it writes a style catalog and a worn shell,
 * and of how it writes a sidecar's {@code gui} and {@code animation} sections in each of their three
 * states, an animation's undeclared members omitted.
 * <p>
 * The context lists every block and item it knows, the ones that draw nothing included, so the dump's
 * blocks and items sections and its {@code icon_gui} probe each walk an id the lookup answers empty.
 * Such an id is written as its state alone and has no transform to probe; an id the lookup does not
 * know at all still fails the dump, since it means the index and its key source disagree.
 * <p>
 * An entity's carried form and its equipment forms are render input like any other form, and the
 * equipment forms are ordered: the first filled slot in the axis's order is the form drawn, so the
 * written bytes keep that order where the writer sorts an object's keys.
 * <p>
 * A style catalog's rows are ordered too, the first applying row of an id being the one that resolves,
 * and each driver carries the members its own wave reads. A worn shell carries its second shell whole,
 * under the selection that reaches it.
 * <p>
 * The context is a stub listing one id the lookups answer empty and one they answer absent, and the
 * forms, the catalog and the shells are built in memory, so no client assets are read.
 */
@DisplayName("The pipeline dump writes an id that draws nothing as its state, fails one nothing knows, keeps a wearer's slot order, and writes a catalog and a shell whole, a gui section as what its scaling holds and an animation as what it declares")
class PipelineParityDumpTest {

    /** An id the stub's lookups answer empty, as the client's index answers air. */
    private static final String DRAWS_NOTHING = "minecraft:air";

    /** An id the stub's lookups answer absent. */
    private static final String UNKNOWN = "minecraft:definitely_not_a_real_id";

    @Test
    @DisplayName("the blocks and items sections write an id that draws nothing as its state alone")
    void anIdDrawingNothingIsWrittenAsItsState() {
        RendererContext context = listing(DRAWS_NOTHING);

        assertThat(PipelineParityDump.blocks(context).toString(), is("{\"minecraft:air\":{\"state\":\"empty\"}}"));
        assertThat(PipelineParityDump.items(context).toString(), is("{\"minecraft:air\":{\"state\":\"empty\"}}"));
    }

    @Test
    @DisplayName("the icon_gui probe skips a block that draws nothing, which has no transform")
    void theIconGuiProbeSkipsABlockDrawingNothing() {
        assertThat(PipelineParityDump.iconGui(listing(DRAWS_NOTHING)).toString(), is("{}"));
    }

    @Test
    @DisplayName("a listed id the lookup does not know still fails both sections")
    void anUnknownListedIdStillFails() {
        RendererContext context = listing(UNKNOWN);

        IllegalStateException blocks = assertThrows(IllegalStateException.class, () -> PipelineParityDump.blocks(context));
        assertThat(blocks.getMessage(), containsString("'" + UNKNOWN + "'"));
        IllegalStateException items = assertThrows(IllegalStateException.class, () -> PipelineParityDump.items(context));
        assertThat(items.getMessage(), containsString("'" + UNKNOWN + "'"));
    }

    @Test
    @DisplayName("the axes write the carried form and every equipment form, the slots in the axis's order")
    void theWearerFormsAreWrittenWithTheirSlotOrderKept() {
        ConcurrentMap<String, Entity> slots = Concurrent.newUnmodifiableLinkedMap(
            Map.entry("saddle", leaf("minecraft:saddled")),
            Map.entry("body", leaf("minecraft:armoured")));
        Entity.Axes axes = new Entity.Axes(Optional.empty(), Entity.Variation.none(), Entity.Variation.none(),
            Entity.Variation.none(), Entity.Variation.none(), Optional.of(leaf("minecraft:held")),
            new Entity.Variation<>(slots, Optional.empty()));

        JsonObject written = parsed(PipelineParityDump.axes(axes));

        assertThat(written.getAsJsonObject("carried").get("id").getAsString(), is("minecraft:held"));
        List<String> order = written.getAsJsonArray("equipment")
            .asList()
            .stream()
            .map(JsonElement::getAsJsonObject)
            .map(slot -> slot.get("slot").getAsString() + "=" + slot.get("id").getAsString())
            .toList();
        assertThat(order, is(List.of("saddle=minecraft:saddled", "body=minecraft:armoured")));
    }

    @Test
    @DisplayName("the style catalog keeps its rows in order and writes each wave with the members it reads")
    void theStyleCatalogIsWrittenWithEachWavesOwnMembers() {
        StyleDriver stroke = new StyleDriver("flap", new StyleDriver.Wave.Stroke(0.5f), 0f, 1f, Optional.empty());
        StyleDriver trapezoid = new StyleDriver("peek", new StyleDriver.Wave.Trapezoid(2, 3, 4, 5), 0.25f, 1f,
            Optional.of("shell"));
        StyleDriver ramp = new StyleDriver("ageInTicks", StyleDriver.Wave.Plain.RAMP, 0f, 1f, Optional.empty());
        PoseStyle adult = new PoseStyle("croak",
            Concurrent.newUnmodifiableList(new PoseStyle.StyleSource(StyleClock.FIGURE, Optional.of(Flag.CARRIED))),
            Concurrent.newUnmodifiableLinkedMap(Map.entry("flap", stroke), Map.entry("peek", trapezoid)),
            Concurrent.newUnmodifiableList("sac"), Optional.of(Age.ADULT), Optional.of(12));
        PoseStyle baby = new PoseStyle("croak",
            Concurrent.newUnmodifiableList(new PoseStyle.StyleSource(StyleClock.TICK, Optional.empty())),
            Concurrent.newUnmodifiableLinkedMap(Map.entry("ageInTicks", ramp)),
            Concurrent.newUnmodifiableList(), Optional.of(Age.BABY), Optional.empty());
        // Declared baby first: a writer sorting the rows by id or by content would put the adult first.
        StyleCatalog catalog = new StyleCatalog(24,
            Concurrent.newUnmodifiableMap(Map.of(Age.ADULT, new PaceCeiling(0.5f, 0.75f))),
            Concurrent.newUnmodifiableList(baby, adult));

        JsonObject written = parsed(PipelineParityDump.styles(catalog));

        assertThat(written.get("period_ticks").getAsInt(), is(24));
        assertThat(written.getAsJsonObject("ceilings").toString(), is("{\"ADULT\":{\"rate\":0.75,\"speed\":0.5}}"));
        List<JsonObject> rows = written.getAsJsonArray("rows").asList().stream().map(JsonElement::getAsJsonObject).toList();
        assertThat(rows.stream().map(row -> row.get("id").getAsString() + "@" + row.get("age").getAsString()).toList(),
            is(List.of("croak@BABY", "croak@ADULT")));

        JsonObject adultRow = rows.getLast();
        assertThat(adultRow.getAsJsonArray("sources").toString(), is("[{\"gate\":\"CARRIED\",\"source\":\"figure\"}]"));
        assertThat(adultRow.getAsJsonArray("toggles").toString(), is("[\"sac\"]"));
        assertThat(adultRow.get("period_ticks").getAsInt(), is(12));
        JsonObject drivers = adultRow.getAsJsonObject("drivers");
        assertThat(drivers.getAsJsonObject("flap").toString(),
            is("{\"extent\":1.0,\"field\":\"flap\",\"rate\":0.5,\"rest\":0.0,\"wave\":\"stroke\"}"));
        assertThat(drivers.getAsJsonObject("peek").toString(),
            is("{\"dwell\":3,\"extent\":1.0,\"fall\":4,\"field\":\"peek\",\"gap\":5,\"group\":\"shell\","
                + "\"rest\":0.25,\"rise\":2,\"wave\":\"trapezoid\"}"));

        JsonObject babyRow = rows.getFirst();
        assertThat(babyRow.getAsJsonArray("sources").toString(), is("[{\"source\":\"tick\"}]"));
        assertThat(babyRow.has("period_ticks"), is(false));
        assertThat(babyRow.getAsJsonObject("drivers").getAsJsonObject("ageInTicks").toString(),
            is("{\"extent\":1.0,\"field\":\"ageInTicks\",\"rest\":0.0,\"wave\":\"ramp\"}"));
    }

    @Test
    @DisplayName("a worn shell is written whole, its second shell under the selection that reaches it")
    void aWornShellIsWrittenWithItsSecondShell() {
        Shell baby = new Shell(new EntityMesh(), new Vector3f(0.25f, 0.25f, 0.25f), new Vector3f(0.5f, 0.5f, 0.5f),
            ArmorForm.BABY, Optional.empty());
        Shell adult = new Shell(new EntityMesh(), new Vector3f(0.5f, 0.5f, 0.5f), new Vector3f(1f, 1f, 1f),
            ArmorForm.ADULT, Optional.of(new Shell.Alternate(new AppearanceGate.Selected(Age.BABY, true), baby)));

        JsonObject written = parsed(PipelineParityDump.shell(adult));

        assertThat(written.get("form").getAsString(), is("ADULT"));
        assertThat(written.getAsJsonArray("inner_grow").toString(), is("[0.5,0.5,0.5]"));
        assertThat(written.getAsJsonArray("outer_grow").toString(), is("[1.0,1.0,1.0]"));
        assertThat(written.has("mesh"), is(true));
        JsonObject alternate = written.getAsJsonObject("alternate");
        assertThat(alternate.getAsJsonObject("when").toString(), is("{\"gate\":\"age\",\"value\":\"BABY\"}"));
        JsonObject second = alternate.getAsJsonObject("shell");
        assertThat(second.get("form").getAsString(), is("BABY"));
        assertThat(second.getAsJsonArray("inner_grow").toString(), is("[0.25,0.25,0.25]"));
        assertThat(second.has("alternate"), is(false));
    }

    @Test
    @DisplayName("a gui section is written as what its scaling holds, a refused one as its state, and an absent one not at all")
    void aGuiSectionIsWrittenAsItsRecordAndARefusedOneAsItsState() {
        MCMeta.GuiScaling nineSlice = new MCMeta.GuiScaling.NineSlice(100, 100, new MCMeta.GuiScaling.NineSlice.Border(9, 9, 9, 9), false);

        assertThat("a nine-slice, in the stored rows' form", gui(Possible.of(nineSlice)),
            is(JsonParser.parseString("{\"type\":\"NINE_SLICE\",\"width\":100,\"height\":100,\"stretch_inner\":false}")));
        assertThat("a tile", gui(Possible.of(new MCMeta.GuiScaling.Tile(16, 8))),
            is(JsonParser.parseString("{\"type\":\"TILE\",\"width\":16,\"height\":8}")));
        assertThat("a stretch", gui(Possible.of(new MCMeta.GuiScaling.Stretch())),
            is(JsonParser.parseString("{\"type\":\"STRETCH\"}")));
        assertThat("a section the client's codec refuses", gui(Possible.empty()),
            is(JsonParser.parseString("{\"state\":\"empty\"}")));
        assertThat("a sidecar carrying no section",
            PipelineParityDump.meta(sidecar(Possible.absent(), Possible.absent())).has("gui"), is(false));
    }

    @Test
    @DisplayName("an animation section is written as what it declares, a refused one as its state, and an absent one not at all")
    void anAnimationIsWrittenAsWhatItDeclaresAndARefusedOneAsItsState() {
        assertThat("a section declaring nothing",
            animation(Possible.of(new MCMeta.Animation(1, false, Optional.empty(), Optional.empty(), Optional.empty()))),
            is(JsonParser.parseString("{\"frametime\":1,\"interpolate\":false}")));
        ConcurrentList<MCMeta.Frame> frames = Concurrent.newList(
            new MCMeta.Frame(0, Optional.empty()), new MCMeta.Frame(1, Optional.of(5)));
        assertThat("a section declaring a height, a frame list and one frame's time",
            animation(Possible.of(new MCMeta.Animation(2, true, Optional.empty(), Optional.of(16), Optional.of(frames)))),
            is(JsonParser.parseString("{\"frametime\":2,\"interpolate\":true,\"height\":16,"
                + "\"frames\":[{\"index\":0},{\"index\":1,\"time\":5}]}")));
        assertThat("a section the client's codec refuses", animation(Possible.empty()),
            is(JsonParser.parseString("{\"state\":\"empty\"}")));
        assertThat("a sidecar carrying no section",
            PipelineParityDump.meta(sidecar(Possible.absent(), Possible.absent())).has("animation"), is(false));
    }

    /**
     * Writes a sidecar carrying the given {@code gui} section and nothing else, and answers what the dump
     * writes under its {@code gui} key.
     *
     * @param gui the section the sidecar carries
     * @return the written section
     */
    private static @NotNull JsonElement gui(@NotNull Possible<MCMeta.GuiScaling> gui) {
        return PipelineParityDump.meta(sidecar(Possible.absent(), gui)).get("gui");
    }

    /**
     * Writes a sidecar carrying the given {@code animation} section and nothing else, and answers what the
     * dump writes under its {@code animation} key.
     *
     * @param animation the section the sidecar carries
     * @return the written section
     */
    private static @NotNull JsonElement animation(@NotNull Possible<MCMeta.Animation> animation) {
        return PipelineParityDump.meta(sidecar(animation, Possible.absent())).get("animation");
    }

    /**
     * Builds a sidecar carrying the given {@code animation} and {@code gui} sections and no other.
     *
     * @param animation the animation section the sidecar carries
     * @param gui the gui section the sidecar carries
     * @return the sidecar
     */
    private static @NotNull MCMeta sidecar(@NotNull Possible<MCMeta.Animation> animation, @NotNull Possible<MCMeta.GuiScaling> gui) {
        return new MCMeta(ResourceId.parse("minecraft:gui/sprites/tooltip/background"), Optional.empty(), animation,
            Possible.absent(), gui, Optional.empty());
    }

    /**
     * Reads a written object back through the canonical writer, so its keys are in the order the dump
     * stores them.
     *
     * @param written the object a writer answered
     * @return the object as the dump stores it
     */
    private static @NotNull JsonObject parsed(@NotNull JsonObject written) {
        return JsonParser.parseString(ParityJson.text(written)).getAsJsonObject();
    }

    /**
     * Builds a form that draws an empty mesh and carries no form of its own.
     *
     * @param id the id the form is written under
     * @return the form
     */
    private static @NotNull Entity leaf(@NotNull String id) {
        return Entity.builder()
            .id(ResourceId.parse(id))
            .model(new EntityMesh())
            .axes(new Entity.Axes(Optional.empty(), Entity.Variation.none(), Entity.Variation.none(),
                Entity.Variation.none(), Entity.Variation.none(), Optional.empty(), Entity.Variation.none()))
            .build();
    }

    /**
     * Builds a context listing one id as its only block and its only item, whose lookups answer the
     * id {@link #DRAWS_NOTHING} empty and every other id absent.
     *
     * @param id the id both lists hold
     * @return the stub context
     */
    private static @NotNull RendererContext listing(@NotNull String id) {
        RendererContext inMemory = RendererContext.builder().build();
        return new RendererContext.Forwarding() {

            @Override
            public @NotNull RendererContext delegate() {
                return inMemory;
            }

            @Override
            public @NotNull Possible<Block> findBlock(@NotNull String blockId) {
                return blockId.equals(DRAWS_NOTHING) ? Possible.empty() : Possible.absent();
            }

            @Override
            public @NotNull Possible<Item> findItem(@NotNull String itemId) {
                return itemId.equals(DRAWS_NOTHING) ? Possible.empty() : Possible.absent();
            }

            @Override
            public @NotNull ConcurrentList<String> knownBlockIds() {
                return Concurrent.newUnmodifiableList(id);
            }

            @Override
            public @NotNull ConcurrentList<String> knownItemIds() {
                return Concurrent.newUnmodifiableList(id);
            }

        };
    }

}
