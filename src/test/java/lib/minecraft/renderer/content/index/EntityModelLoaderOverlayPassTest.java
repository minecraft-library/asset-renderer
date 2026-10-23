package lib.minecraft.renderer.content.index;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.pixel.BlendMode;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.mesh.EntityMesh;
import lib.minecraft.renderer.content.table.EntityModelsTable.RawAxes;
import lib.minecraft.renderer.content.table.EntityModelsTable.RawAxis;
import lib.minecraft.renderer.content.table.EntityModelsTable.RawModel;
import lib.minecraft.renderer.content.table.EntityModelsTable.RawOption;
import lib.minecraft.renderer.content.table.EntityModelsTable.RawOverlay;
import lib.minecraft.renderer.content.table.EntityModelsTable;
import lib.minecraft.renderer.engine.draw.PassDeclaration;
import lib.minecraft.renderer.engine.pose.PoseExpr;
import lib.minecraft.renderer.engine.pose.PoseOperator;
import lib.minecraft.renderer.engine.pose.PoseWidth;
import lib.minecraft.renderer.exception.ContentException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pin on the pass declarations vanilla makes independently of one another - the {@code EMISSIVE}
 * shader define, {@code DepthStencilState.writeDepth}, {@code RenderSetup.sortOnUpload} and the
 * {@link BlendMode} a pipeline's blend function resolves to - taken over every
 * {@link Entity.OverlayLayer} {@link EntityModelLoader} ships, so a tooling walk that stops
 * resolving one of them cannot silently collapse it back onto another.
 * <p>
 * The collapse this guards against is a real one that shipped: keying the depth-write skip on
 * {@code emissive} suppressed it for the energy swirl, which vanilla registers with
 * {@code DepthStencilState.DEFAULT} and therefore writes. The corpus contains every disagreement
 * needed to catch that - an emissive pass that writes, an emissive pass that does not, a
 * non-emissive pass that sorts, and an additive pass that is emissive too - so any rule inferring
 * one declaration from another fails at least one row.
 * <p>
 * A pass's opacity is declared one way - an {@code opacity} expression the frame evaluates, a
 * constant one spelled as the grammar's own {@code const}. The fixture cases hold that an expression
 * lands on the pass as written, that a constant draws at its own value, and that a constant drawing
 * nothing is refused rather than shipped.
 */
@DisplayName("overlay pipeline declarations")
class EntityModelLoaderOverlayPassTest {

    /**
     * Entities carrying an overlay whose pipeline declares no depth write - vanilla's eyes-style
     * passes. The breeze is on this list and on the emissive-and-writes list both, because it carries
     * two overlays that disagree: an eyes pass that does not write and a wind pass that does.
     */
    private static final Set<String> NO_DEPTH_WRITE = Set.of(
        "minecraft:breeze", "minecraft:cave_spider", "minecraft:copper_golem",
        "minecraft:ender_dragon", "minecraft:enderman", "minecraft:phantom", "minecraft:spider",
        "minecraft:warden");

    /** Overlays whose pipeline blends additively - vanilla's two energy swirls. */
    private static final Set<String> ADDITIVE = Set.of("minecraft:creeper", "minecraft:wither");

    /** The fixture entity the opacity cases assemble. */
    private static final @NotNull String ENTITY = "minecraft:test";

    /** The fixture entity's one geometry coordinate. */
    private static final @NotNull String COORD = "TestModel#createBodyLayer";

    private static ConcurrentMap<String, Entity> index() {
        return EntityModelLoader.load();
    }

    @Test
    @DisplayName("an emissive overlay may write depth or not, and the corpus carries both")
    void depthWriteIsIndependentOfEmissive() {
        Set<String> emissiveWithoutWrite = new TreeSet<>();
        Set<String> emissiveWithWrite = new TreeSet<>();
        index().forEach((id, entity) -> entity.overlays().forEach(overlay -> {
            if (!overlay.pass().emissive()) return;
            (overlay.pass().writesDepth() ? emissiveWithWrite : emissiveWithoutWrite).add(id);
        }));
        assertThat(emissiveWithoutWrite, is(new TreeSet<>(Set.of(
            "minecraft:cave_spider", "minecraft:copper_golem", "minecraft:ender_dragon",
            "minecraft:enderman", "minecraft:phantom", "minecraft:spider"))));
        assertThat(emissiveWithWrite, is(new TreeSet<>(Set.of(
            "minecraft:breeze", "minecraft:creeper", "minecraft:wither"))));
    }

    @Test
    @DisplayName("every overlay declaring no depth write is one of vanilla's eyes-style passes")
    void noDepthWriteRosterMatches() {
        Set<String> flagged = new TreeSet<>();
        index().forEach((id, entity) -> entity.overlays().forEach(overlay -> {
            if (!overlay.pass().writesDepth()) flagged.add(id);
        }));
        assertThat(flagged, is(new TreeSet<>(NO_DEPTH_WRITE)));
    }

    @Test
    @DisplayName("both energy swirls write depth and sort, which is what occludes their own far faces")
    void energySwirlsWriteAndSort() {
        Set<String> flagged = new TreeSet<>();
        index().forEach((id, entity) -> entity.overlays().forEach(overlay -> {
            if (overlay.pass().blend() != BlendMode.ADD) return;
            flagged.add(id);
            assertThat(id + " writes depth", overlay.pass().writesDepth(), is(true));
            assertThat(id + " sorts", overlay.pass().sorted(), is(true));
        }));
        assertThat(flagged, is(new TreeSet<>(ADDITIVE)));
    }

    @Test
    @DisplayName("a sorted overlay need not be emissive, so the sort cannot be read off that flag")
    void sortedIsIndependentOfEmissive() {
        Set<String> sortedNonEmissive = new TreeSet<>();
        index().forEach((id, entity) -> entity.overlays().forEach(overlay -> {
            if (overlay.pass().sorted() && !overlay.pass().emissive()) sortedNonEmissive.add(id);
        }));
        assertThat(sortedNonEmissive, is(new TreeSet<>(Set.of(
            "minecraft:breeze", "minecraft:slime", "minecraft:warden"))));
    }

    @Test
    @DisplayName("an overlay's opacity loads onto its pass as the expression the row spells")
    void anOpacityLoadsOntoItsPass() {
        ConcurrentMap<String, Entity> assembled = assemble(
            JsonParser.parseString("{\"mul\": [{\"input\": \"ageInTicks\"}, {\"const\": 0.01}]}"));
        Entity.OverlayLayer pass = assembled.get(ENTITY).overlays().getFirst();

        assertThat(pass.opacity(), is(Optional.of(new PoseExpr.Op(PoseOperator.MUL, Concurrent.newUnmodifiableList(
            new PoseExpr.Input("ageInTicks"), new PoseExpr.Constant(0.01f, PoseWidth.FLOAT))))));
    }

    @Test
    @DisplayName("a constant opacity loads as the grammar's constant and draws its pass at that value")
    void aConstantOpacityDrawsAtItsValue() {
        Entity.OverlayLayer pass = assemble(JsonParser.parseString("{\"const\": 0.25}")).get(ENTITY).overlays().getFirst();

        assertThat(pass.opacity(), is(Optional.of(new PoseExpr.Constant(0.25f, PoseWidth.FLOAT))));
        assertThat(pass.pass().alpha(), is(PassDeclaration.drawnAlpha(0.25f)));
    }

    @Test
    @DisplayName("a constant opacity at or under zero is refused, a pass that draws nothing being one the generator drops")
    void aConstantOpacityDrawingNothingIsRefused() {
        ContentException raised = assertThrows(ContentException.class, () ->
            assemble(JsonParser.parseString("{\"const\": 0.0}")));
        assertThat(raised.getMessage(),
            is("Entity 'minecraft:test' overlay '0' declares opacity '0.0', and a shipped pass draws"));
    }

    // ------------------------------------------------------------------------------------

    /**
     * Assembles the fixture entity with one pass drawing its body's mesh.
     *
     * @param opacity the pass's {@code opacity}, or {@code null} where the row declares none
     * @return the assembled index
     */
    private static @NotNull ConcurrentMap<String, Entity> assemble(@Nullable JsonElement opacity) {
        Map<String, RawModel> models = new LinkedHashMap<>();
        models.put(ENTITY, new RawModel(
            null,                                                         // renderer
            null,                                                         // render
            List.of(new RawOverlay(
                null,                                                     // geometry
                null,                                                     // no_hat_geometry
                "test",                                                   // texture
                null,                                                     // tint
                null,                                                     // tint_by
                null,                                                     // texture_by
                null,                                                     // pipeline
                null,                                                     // texture_scroll
                false,                                                    // skip_bounds
                null,                                                     // when
                null,                                                     // baby
                opacity)),                                                // opacity
            null,                                                         // block_overlays
            null,                                                         // armor
            null,                                                         // wings
            null,                                                         // equipment
            ageAxis(),                                                    // axes
            null,                                                         // members
            null,                                                         // styles
            null));                                                       // ceilings
        EntityMesh mesh = new EntityMesh();
        mesh.getBones().put("body", new EntityMesh.Bone());
        return EntityIndexBuilder.assemble(Map.of(COORD, mesh), new EntityModelsTable(models, null), Map.of());
    }

    private static @NotNull RawAxes ageAxis() {
        Map<String, RawOption> options = new LinkedHashMap<>();
        options.put("adult", new RawOption(COORD, "test", null, null, null, null, null, null));
        return new RawAxes(null, new RawAxis(null, options), null, null, null, null, null);
    }

}
