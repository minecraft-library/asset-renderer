package lib.minecraft.renderer.pipeline.pack.item;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import dev.simplified.collection.Concurrent;
import dev.simplified.gson.GsonSettings;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.pipeline.pack.Capability;
import lib.minecraft.renderer.pipeline.pack.MCMeta;
import lib.minecraft.renderer.pipeline.pack.PackContainer;
import lib.minecraft.renderer.pipeline.pack.PackId;
import lib.minecraft.renderer.pipeline.pack.PackRoot;
import lib.minecraft.renderer.pipeline.pack.ResourcePack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;

/**
 * Exercises the legacy {@code overrides} tolerate-and-map (05-models.md §7): the mechanical 1.21.4
 * translation of pre-format-46 {@code models/item} {@code overrides} onto the items-tree node
 * vocabulary, the {@link LegacyOverrideMapper#isLegacyPack} format gate, threshold-order preservation,
 * and the unmapped-predicate skip-with-diagnostic contract.
 */
@DisplayName("LegacyOverrideMapper 1.21.4 predicate translation")
class LegacyOverrideMapperTest {

    private static final Gson GSON = GsonSettings.defaults().create();
    private static final PackId PACK = new PackId("legacypack");
    private static final String ITEM_ID = "minecraft:diamond_sword";
    private static final String BASE_REF = "minecraft:item/diamond_sword";

    @Test
    @DisplayName("custom_model_data maps to a flat range_dispatch, dispatched by the caller value")
    void customModelDataToRangeDispatch() {
        JsonArray overrides = overrides(
            "{\"predicate\":{\"custom_model_data\":1},\"model\":\"item/sword_cmd1\"}",
            "{\"predicate\":{\"custom_model_data\":2},\"model\":\"item/sword_cmd2\"}");

        ItemModelNode root = map(overrides).orElseThrow();
        assertThat(root, instanceOf(ItemModelNode.RangeDispatch.class));
        ItemModelNode.RangeDispatch range = (ItemModelNode.RangeDispatch) root;
        assertThat(range.property(), is("custom_model_data"));

        // The bare id renders the base; the caller value selects the override frame.
        assertThat(resolveAt(root, null), is(BASE_REF));
        assertThat(resolveAt(root, 1f), is("minecraft:item/sword_cmd1"));
        assertThat(resolveAt(root, 2f), is("minecraft:item/sword_cmd2"));
        // Highest threshold <= value wins (last-satisfied), like vanilla overrides.
        assertThat(resolveAt(root, 5f), is("minecraft:item/sword_cmd2"));
    }

    @Test
    @DisplayName("pulling/pull maps to condition(using_item) wrapping range_dispatch(use_duration), matching bow.json")
    void pullingPullToConditionRangeDispatch() {
        JsonArray overrides = overrides(
            "{\"predicate\":{\"pulling\":1},\"model\":\"item/bow_pulling_0\"}",
            "{\"predicate\":{\"pulling\":1,\"pull\":0.65},\"model\":\"item/bow_pulling_1\"}",
            "{\"predicate\":{\"pulling\":1,\"pull\":0.9},\"model\":\"item/bow_pulling_2\"}");

        ItemModelNode root = map(overrides).orElseThrow();
        assertThat(root, instanceOf(ItemModelNode.Condition.class));
        ItemModelNode.Condition condition = (ItemModelNode.Condition) root;
        assertThat(condition.property(), is("using_item"));

        assertThat(condition.onTrue(), instanceOf(ItemModelNode.RangeDispatch.class));
        ItemModelNode.RangeDispatch range = (ItemModelNode.RangeDispatch) condition.onTrue();
        assertThat(range.property(), is("use_duration"));
        assertThat(range.scale(), is(0.05f));
        assertThat(range.entries().size(), is(2));
        // gate-only override ({pulling:1}) becomes the range fallback (bow_pulling_0)
        assertThat(range.fallback(), instanceOf(ItemModelNode.Model.class));
        assertThat(((ItemModelNode.Model) range.fallback()).model(), is("minecraft:item/bow_pulling_0"));
        // on_false (not using item) is the base model
        assertThat(((ItemModelNode.Model) condition.onFalse()).model(), is(BASE_REF));

        // Neutral -> base; using item -> the gate-true branch fallback (use_duration=0)
        assertThat(resolveUsingItem(root, false), is(BASE_REF));
        assertThat(resolveUsingItem(root, true), is("minecraft:item/bow_pulling_0"));
    }

    @Test
    @DisplayName("blocking maps to condition(using_item) selecting the blocking model")
    void blockingToCondition() {
        JsonArray overrides = overrides("{\"predicate\":{\"blocking\":1},\"model\":\"item/shield_blocking\"}");
        ItemModelNode root = map(overrides).orElseThrow();
        assertThat(root, instanceOf(ItemModelNode.Condition.class));
        assertThat(resolveUsingItem(root, true), is("minecraft:item/shield_blocking"));
        assertThat(resolveUsingItem(root, false), is(BASE_REF));
    }

    @Test
    @DisplayName("an unmapped predicate skips that entry with a diagnostic; the rest of the file still maps")
    void unmappedPredicateSkippedWithDiagnostic() {
        JsonArray overrides = overrides(
            "{\"predicate\":{\"custom_model_data\":1},\"model\":\"item/sword_cmd1\"}",
            "{\"predicate\":{\"lefthanded\":1},\"model\":\"item/should_not_map\"}");

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream original = System.err;
        Optional<ItemModelNode> mapped;
        try {
            System.setErr(new PrintStream(buffer, true, StandardCharsets.UTF_8));
            mapped = map(overrides);
        } finally {
            System.setErr(original);
        }
        String err = buffer.toString(StandardCharsets.UTF_8);
        assertThat(err, containsString("lefthanded"));
        assertThat(err, containsString("legacypack"));

        ItemModelNode root = mapped.orElseThrow();
        assertThat(resolveAt(root, 1f), is("minecraft:item/sword_cmd1"));
        // the unmapped model never appears
        assertThat(((ItemModelNode.RangeDispatch) root).entries().size(), is(1));
    }

    @Test
    @DisplayName("two overrides with the same threshold honour last-declared (last-satisfied)")
    void equalThresholdLastWins() {
        JsonArray overrides = overrides(
            "{\"predicate\":{\"custom_model_data\":5},\"model\":\"item/first\"}",
            "{\"predicate\":{\"custom_model_data\":5},\"model\":\"item/second\"}");
        ItemModelNode root = map(overrides).orElseThrow();
        assertThat("last-declared override at an equal threshold wins", resolveAt(root, 5f), is("minecraft:item/second"));
    }

    @Test
    @DisplayName("the caller-supplied fallback is the tree's default branch (preserves the native tree)")
    void fallbackIsTheDefaultBranch() {
        JsonArray overrides = overrides("{\"predicate\":{\"custom_model_data\":1},\"model\":\"item/cmd1\"}");
        // A stand-in native tree carrying a distinct model - the mapper must fall back to it, not to BASE_REF.
        ItemModelNode nativeTree = new ItemModelNode.Model("minecraft:item/native_default", List.of());
        ItemModelNode root = LegacyOverrideMapper.map(ITEM_ID, overrides, PACK, nativeTree).orElseThrow();
        assertThat("neutral context falls back to the native tree", resolveAt(root, null), is("minecraft:item/native_default"));
        assertThat("cmd=1 still selects the override frame", resolveAt(root, 1f), is("minecraft:item/cmd1"));
    }

    @Test
    @DisplayName("a file with no mappable overrides synthesises no tree")
    void noMappableOverridesYieldsEmpty() {
        JsonArray overrides = overrides("{\"predicate\":{\"lefthanded\":1},\"model\":\"item/x\"}");
        assertThat(map(overrides).isPresent(), is(false));
    }

    @Test
    @DisplayName("the format gate admits pre-46 packs and rejects 46+ / undeclared packs")
    void formatGate() {
        assertThat(LegacyOverrideMapper.isLegacyPack(packWithFormat(15)), is(true));
        assertThat(LegacyOverrideMapper.isLegacyPack(packWithFormat(45)), is(true));
        assertThat(LegacyOverrideMapper.isLegacyPack(packWithFormat(46)), is(false));
        assertThat(LegacyOverrideMapper.isLegacyPack(packWithFormat(60)), is(false));
        // no pack section (MCMeta.EMPTY) is not treated as legacy
        assertThat(LegacyOverrideMapper.isLegacyPack(packWithMeta(MCMeta.EMPTY)), is(false));
    }

    private static Optional<ItemModelNode> map(JsonArray overrides) {
        return LegacyOverrideMapper.map(ITEM_ID, overrides, PACK, new ItemModelNode.Model(BASE_REF, List.of()));
    }

    private static String resolveAt(ItemModelNode root, Float customModelData) {
        ItemModelContext context = new ItemModelContext("gui", false, false, null, null, 0f, 0f, customModelData, null);
        return ItemModelWalker.resolve(root, context).modelId().orElse("<none>");
    }

    private static String resolveUsingItem(ItemModelNode root, boolean usingItem) {
        ItemModelContext context = new ItemModelContext("gui", usingItem, false, null, null, 0f, 0f, null, null);
        return ItemModelWalker.resolve(root, context).modelId().orElse("<none>");
    }

    private static JsonArray overrides(String... entries) {
        JsonArray array = new JsonArray();
        for (String entry : entries) array.add(GSON.fromJson(entry, com.google.gson.JsonObject.class));
        return array;
    }

    private static ResourcePack packWithFormat(int format) {
        return packWithMeta(MCMeta.parse("{\"pack\":{\"pack_format\":" + format + ",\"description\":\"x\"}}",
            new ResourceId("legacypack", "pack")));
    }

    private static ResourcePack packWithMeta(MCMeta meta) {
        return new ResourcePack(new PackId("legacypack"), new PackContainer.Directory(Path.of("nonexistent")), meta,
            Concurrent.newList(PackRoot.BASE).toUnmodifiable(), Set.of("minecraft"), Set.of(Capability.VANILLA_CORE));
    }

}
