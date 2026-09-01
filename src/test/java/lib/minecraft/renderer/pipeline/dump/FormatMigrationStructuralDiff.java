package lib.minecraft.renderer.pipeline.dump;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.appearance.Age;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.asset.pose.EntityPose;
import lib.minecraft.renderer.asset.pose.PoseChannel;
import lib.minecraft.renderer.asset.pose.PoseClip;
import lib.minecraft.renderer.asset.pose.PoseExpr;
import lib.minecraft.renderer.asset.pose.PosePredicate;
import lib.minecraft.renderer.asset.pose.PoseStyle;
import lib.minecraft.renderer.asset.pose.StyleCatalog;
import lib.minecraft.renderer.pipeline.index.EntityIndexBuilder;
import lib.minecraft.renderer.pipeline.index.RawEntityModelsFile;
import lib.minecraft.renderer.pipeline.index.RawEntityPosesFile;
import lib.minecraft.renderer.pipeline.util.ResourceDocument;
import lib.minecraft.renderer.tensor.Vector2f;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bit-compares the entity index assembled from two emitted table sets, one under each grammar the
 * dual-read loader accepts, proving the two arms hand the renderer identical in-memory values.
 *
 * <p>The old grammar carries the renderer steps in a {@code renderers} table the assembler composes
 * with its own ground-frame step ahead of each pose's container; the new grammar writes each
 * container whole into the pose row. The equality that matters is therefore of the ASSEMBLED index,
 * float for float: every container step, bone channel expression, clip play site (coordinate,
 * drive, field, arguments and the authored table it plays), and refusal, over the body pose, the
 * baby pose, and every overlay pass's pose - plus the meshes, block overlays, equipment rows and
 * worn-armor shells, which neither grammar is allowed to move.
 *
 * <p>The {@code styles} member is the one deliberate asymmetry: the old grammar names none, so
 * every old-side entity must carry {@link StyleCatalog#BIND_ONLY}, while the new side must load
 * exactly the rows its file spells - counted and compared row-for-row against a direct parse.
 *
 * <p>Both table directories arrive as system properties (forwarded into the test fork by the
 * build's {@code asset.*} forwarder): {@code asset.migration.old} and {@code asset.migration.new},
 * each holding {@code entity_geometry.json} / {@code entity_models.json} / {@code entity_poses.json}.
 * The comparison is skipped when either is unset or absent, so the ordinary suite never depends on
 * files only a migration workspace holds.
 */
@DisplayName("one entity index assembled from either table grammar")
class FormatMigrationStructuralDiff {

    /** Names the directory holding the old-grammar tables. */
    private static final @NotNull String OLD_TABLES_PROPERTY = "asset.migration.old";

    /** Names the directory holding the new-grammar tables. */
    private static final @NotNull String NEW_TABLES_PROPERTY = "asset.migration.new";

    /** How many entities the new tables style. */
    private static final int STYLED_ENTITIES = 86;

    /** How many style rows the new tables carry in all. */
    private static final int STYLE_ROWS = 180;

    /** How many of those rows nothing moves - an empty source inventory. */
    private static final int HELD_ROWS = 4;

    /** The excursion period every styled family shares. */
    private static final int PERIOD_TICKS = 24;

    /** The most difference lines the report keeps before suppressing the rest. */
    private static final int REPORT_CAP = 400;

    @Test
    @DisplayName("both grammars assemble to a bit-equal index; styles arrive only from the new one")
    void bothGrammarsAssembleBitEqual() throws IOException {
        Path oldTables = tables(OLD_TABLES_PROPERTY);
        Path newTables = tables(NEW_TABLES_PROPERTY);
        Assumptions.assumeTrue(oldTables != null && newTables != null,
            "set -D" + OLD_TABLES_PROPERTY + " and -D" + NEW_TABLES_PROPERTY
                + " to the two emitted table directories");

        Map<String, Entity> oldIndex = assembleFrom(oldTables);
        Map<String, Entity> newIndex = assembleFrom(newTables);

        Differ differ = new Differ();
        if (!oldIndex.keySet().equals(newIndex.keySet()))
            differ.report("index", "entity ids " + oldIndex.keySet(), "entity ids " + newIndex.keySet());
        for (Map.Entry<String, Entity> entry : oldIndex.entrySet()) {
            Entity other = newIndex.get(entry.getKey());
            if (other != null) differ.compareEntity(entry.getKey(), entry.getValue(), other);
        }

        for (Map.Entry<String, Entity> entry : oldIndex.entrySet())
            if (!entry.getValue().styles().styles().isEmpty())
                differ.report(entry.getKey() + "/styles",
                    "carries " + entry.getValue().styles().styles().size()
                        + " rows where the old grammar ships none");
        compareStylesAgainstEmit(newTables, newIndex, differ);

        System.out.println("structural dump diff: " + oldIndex.size() + " entities on each side, "
            + differ.diffs.size() + " difference(s)");
        assertTrue(differ.diffs.isEmpty(),
            () -> "the two grammars assemble differently:\n" + String.join("\n", differ.diffs));
    }

    /** The directory a property names, or {@code null} where it is unset or holds no tables. */
    private static @Nullable Path tables(@NotNull String property) {
        String named = System.getProperty(property);
        if (named == null || named.isBlank()) return null;
        Path directory = Path.of(named);
        return Files.isRegularFile(directory.resolve("entity_models.json")) ? directory : null;
    }

    /** One whole assembly from a directory's three tables, through the same reads the loader runs. */
    private static @NotNull Map<String, Entity> assembleFrom(@NotNull Path directory) throws IOException {
        ResourceDocument geometry = ResourceDocument.open(
            Files.readAllBytes(directory.resolve("entity_geometry.json")));
        ResourceDocument models = ResourceDocument.open(
            Files.readAllBytes(directory.resolve("entity_models.json")), 2, 3);
        ResourceDocument poses = ResourceDocument.open(
            Files.readAllBytes(directory.resolve("entity_poses.json")), 2, 3);

        Map<String, EntityModelData> geometries = geometry.as(GeometryFile.class).geometries();
        RawEntityModelsFile raw = models.as(RawEntityModelsFile.class);
        RawEntityPosesFile posed = poses.as(RawEntityPosesFile.class);
        return EntityIndexBuilder.assemble(geometries, raw, posed.poses(), posed.renderTransforms());
    }

    /** The {@code entity_geometry.json} payload: geometry coordinate to its bone tree. */
    private record GeometryFile(@NotNull Map<String, EntityModelData> geometries) {}

    /**
     * Holds the new side's loaded catalogs against a direct parse of its models file: every styled
     * family loads exactly the rows it spells - id, age, gated source inventory and toggles in
     * shipped order at the shared period - and the corpus counts land where the emit says they do.
     */
    private static void compareStylesAgainstEmit(
        @NotNull Path newTables, @NotNull Map<String, Entity> newIndex, @NotNull Differ differ)
        throws IOException {

        JsonObject models = JsonParser.parseString(
                Files.readString(newTables.resolve("entity_models.json"), StandardCharsets.UTF_8))
            .getAsJsonObject()
            .getAsJsonObject("models");

        int styled = 0;
        int rows = 0;
        int held = 0;
        for (String id : models.keySet()) {
            Entity loaded = newIndex.get(id);
            if (loaded == null) continue;
            JsonElement stylesNode = models.getAsJsonObject(id).get("styles");
            StyleCatalog catalog = loaded.styles();
            if (stylesNode == null) {
                if (!catalog.styles().isEmpty())
                    differ.report(id + "/styles", "loads " + catalog.styles().size()
                        + " rows from a family spelling none");
                continue;
            }

            styled++;
            JsonArray shipped = stylesNode.getAsJsonArray();
            rows += shipped.size();
            if (catalog.periodTicks() != PERIOD_TICKS)
                differ.report(id + "/styles/periodTicks",
                    String.valueOf(PERIOD_TICKS), String.valueOf(catalog.periodTicks()));
            if (catalog.styles().size() != shipped.size()) {
                differ.report(id + "/styles", shipped.size() + " rows spelled",
                    catalog.styles().size() + " rows loaded");
                continue;
            }
            for (int i = 0; i < shipped.size(); i++) {
                JsonObject row = shipped.get(i).getAsJsonObject();
                if (spelledSources(row).isEmpty()) held++;
                compareStyleRow(id + "/styles[" + i + "]", row, catalog.styles().get(i), differ);
            }
        }

        if (styled != STYLED_ENTITIES)
            differ.report("styles/entities", String.valueOf(STYLED_ENTITIES), String.valueOf(styled));
        if (rows != STYLE_ROWS)
            differ.report("styles/rows", String.valueOf(STYLE_ROWS), String.valueOf(rows));
        if (held != HELD_ROWS)
            differ.report("styles/heldRows", String.valueOf(HELD_ROWS), String.valueOf(held));
    }

    /** One spelled style row against the row the catalog loaded at the same position. */
    private static void compareStyleRow(
        @NotNull String path, @NotNull JsonObject row, @NotNull PoseStyle style, @NotNull Differ differ) {

        String id = row.get("id").getAsString();
        if (!id.equals(style.id())) differ.report(path + "/id", id, style.id());

        Optional<Age> age = row.has("age")
            ? Optional.of("baby".equals(row.get("age").getAsString()) ? Age.BABY : Age.ADULT)
            : Optional.<Age>empty();
        if (!age.equals(style.age()))
            differ.report(path + "/age", String.valueOf(age), String.valueOf(style.age()));

        List<String> spelled = spelledSources(row);
        List<String> loaded = style.sources().stream()
            .map(source -> source.source().token() + source.gate().map(gate -> "@" + gate).orElse(""))
            .toList();
        if (!spelled.equals(loaded))
            differ.report(path + "/sources", String.valueOf(spelled), String.valueOf(loaded));

        List<String> toggles = new ArrayList<>();
        if (row.has("toggles"))
            for (JsonElement toggle : row.getAsJsonArray("toggles")) toggles.add(toggle.getAsString());
        if (!toggles.equals(List.copyOf(style.toggles())))
            differ.report(path + "/toggles", String.valueOf(toggles), String.valueOf(style.toggles()));
    }

    /** A row's spelled source inventory as {@code token} or {@code token@gate} strings, in order. */
    private static @NotNull List<String> spelledSources(@NotNull JsonObject row) {
        List<String> out = new ArrayList<>();
        if (!row.has("sources")) return out;
        for (JsonElement source : row.getAsJsonArray("sources")) {
            if (source.isJsonPrimitive()) {
                out.add(source.getAsString());
                continue;
            }
            JsonObject gated = source.getAsJsonObject();
            JsonElement gate = gated.get("gate");
            out.add(gated.get("source").getAsString() + (gate == null ? "" : "@" + gate.getAsString()));
        }
        return out;
    }

    // ------------------------------------------------------------------------------------

    /**
     * Walks two assembled entities member by member, collecting every point they disagree at.
     *
     * <p>Expressions are shared graphs - a humanoid's shared table stands for millions of expanded
     * nodes - so the walk memoizes verdicts by node-pair identity and never expands a shared
     * sub-expression twice. Floats compare by their raw bits: {@code Double.doubleToLongBits} for
     * the literal carrier, {@code Float.floatToIntBits} everywhere a float is stored.
     */
    private static final class Differ {

        private final @NotNull List<String> diffs = new ArrayList<>();
        private final @NotNull Map<IdentityPair, Boolean> comparedNodes = new HashMap<>();
        private final @NotNull Set<IdentityPair> comparedEntities = new HashSet<>();

        private void report(@NotNull String path, @NotNull String oldSide, @NotNull String newSide) {
            report(path, "old " + oldSide + " vs new " + newSide);
        }

        private void report(@NotNull String path, @NotNull String detail) {
            if (this.diffs.size() == REPORT_CAP) this.diffs.add("... further differences suppressed");
            if (this.diffs.size() < REPORT_CAP) this.diffs.add(path + ": " + detail);
        }

        private void compareEntity(@NotNull String path, @NotNull Entity a, @NotNull Entity b) {
            // The variant axis carries the base row among its own options, so the walk revisits
            // entities; a pair already walked (or on the stack) has nothing new to say.
            if (!this.comparedEntities.add(new IdentityPair(a, b))) return;

            if (!a.model().equals(b.model())) report(path + "/model", "meshes differ");
            comparePose(path + "/pose", a.pose(), b.pose());

            if (!a.axes().babyModel().equals(b.axes().babyModel()))
                report(path + "/babyModel", "baby meshes differ");
            compareOptionalPose(path + "/babyPose", a.axes().babyPose(), b.axes().babyPose());
            compareOverlays(path + "/overlays", a.overlays(), b.overlays());
            compareOverlays(path + "/babyOverlays", a.axes().babyOverlays(), b.axes().babyOverlays());

            if (!List.copyOf(a.blockOverlays()).equals(List.copyOf(b.blockOverlays())))
                report(path + "/blockOverlays", "rows differ");
            if (a.baseTintArgb() != b.baseTintArgb())
                report(path + "/baseTint", Integer.toHexString(a.baseTintArgb()),
                    Integer.toHexString(b.baseTintArgb()));
            if (Float.floatToIntBits(a.rendererScale()) != Float.floatToIntBits(b.rendererScale()))
                report(path + "/rendererScale", bits(a.rendererScale()), bits(b.rendererScale()));
            if (!List.copyOf(a.members()).equals(List.copyOf(b.members())))
                report(path + "/members", String.valueOf(a.members()), String.valueOf(b.members()));

            if (!a.layers().humanoidArmor().equals(b.layers().humanoidArmor()))
                report(path + "/armor", "worn shells differ");
            if (!List.copyOf(a.layers().equipment()).equals(List.copyOf(b.layers().equipment())))
                report(path + "/equipment", "rows differ");

            if (!new HashMap<>(a.axes().state().options()).equals(new HashMap<>(b.axes().state().options()))
                || !a.axes().state().declared().equals(b.axes().state().declared()))
                report(path + "/state", "state axes differ");
            compareEntityAxis(path + "/shape", a.axes().shape(), b.axes().shape());
            compareEntityAxis(path + "/size", a.axes().size(), b.axes().size());
            compareEntityAxis(path + "/variant", a.axes().variant(), b.axes().variant());
        }

        private <K> void compareEntityAxis(
            @NotNull String path, Entity.@NotNull Axis<K, Entity> a, Entity.@NotNull Axis<K, Entity> b) {

            if (!a.declared().equals(b.declared()))
                report(path + "/declared", String.valueOf(a.declared()), String.valueOf(b.declared()));
            if (!a.options().keySet().equals(b.options().keySet())) {
                report(path, "options " + a.options().keySet(), "options " + b.options().keySet());
                return;
            }
            for (Map.Entry<K, Entity> option : a.options().entrySet())
                compareEntity(path + "[" + option.getKey() + "]",
                    option.getValue(), b.options().get(option.getKey()));
        }

        private void compareOverlays(
            @NotNull String path,
            @NotNull List<Entity.OverlayLayer> a, @NotNull List<Entity.OverlayLayer> b) {

            if (a.size() != b.size()) {
                report(path, a.size() + " passes", b.size() + " passes");
                return;
            }
            for (int i = 0; i < a.size(); i++) {
                Entity.OverlayLayer left = a.get(i);
                Entity.OverlayLayer right = b.get(i);
                String at = path + "[" + i + "]";
                if (!left.model().equals(right.model())) report(at + "/model", "meshes differ");
                if (!left.noHatModel().equals(right.noHatModel()))
                    report(at + "/noHatModel", "alternates differ");
                if (!left.textureRef().equals(right.textureRef()))
                    report(at + "/texture", String.valueOf(left.textureRef()),
                        String.valueOf(right.textureRef()));
                if (left.tintArgb() != right.tintArgb())
                    report(at + "/tint", Integer.toHexString(left.tintArgb()),
                        Integer.toHexString(right.tintArgb()));
                if (left.skipBounds() != right.skipBounds())
                    report(at + "/skipBounds", String.valueOf(left.skipBounds()),
                        String.valueOf(right.skipBounds()));
                if (!left.tintBy().equals(right.tintBy()))
                    report(at + "/tintBy", String.valueOf(left.tintBy()), String.valueOf(right.tintBy()));
                if (!left.textureBy().equals(right.textureBy()))
                    report(at + "/textureBy", String.valueOf(left.textureBy()),
                        String.valueOf(right.textureBy()));
                if (left.gate().isPresent() != right.gate().isPresent())
                    report(at + "/gate", left.gate().isPresent() ? "gated" : "unconditional",
                        right.gate().isPresent() ? "gated" : "unconditional");
                compareScroll(at + "/textureScroll", left.textureScroll(), right.textureScroll());
                comparePose(at + "/pose", left.pose(), right.pose());
            }
        }

        private void compareScroll(
            @NotNull String path, @NotNull Optional<Vector2f> a, @NotNull Optional<Vector2f> b) {

            if (a.isPresent() != b.isPresent()) {
                report(path, a.isPresent() ? "scrolls" : "holds", b.isPresent() ? "scrolls" : "holds");
                return;
            }
            if (a.isEmpty()) return;
            Vector2f left = a.get();
            Vector2f right = b.get();
            if (Float.floatToIntBits(left.x()) != Float.floatToIntBits(right.x())
                || Float.floatToIntBits(left.y()) != Float.floatToIntBits(right.y()))
                report(path, bits(left.x()) + "," + bits(left.y()),
                    bits(right.x()) + "," + bits(right.y()));
        }

        private void compareOptionalPose(
            @NotNull String path, @NotNull Optional<EntityPose> a, @NotNull Optional<EntityPose> b) {

            if (a.isPresent() != b.isPresent()) {
                report(path, a.isPresent() ? "present" : "absent", b.isPresent() ? "present" : "absent");
                return;
            }
            if (a.isPresent()) comparePose(path, a.get(), b.orElseThrow());
        }

        private void comparePose(@NotNull String path, @NotNull EntityPose a, @NotNull EntityPose b) {
            if (!a.refusal().equals(b.refusal())) {
                report(path + "/refusal", String.valueOf(a.refusal()), String.valueOf(b.refusal()));
                return;
            }

            if (a.container().size() != b.container().size())
                report(path + "/container", a.container().size() + " steps", b.container().size() + " steps");
            else for (int i = 0; i < a.container().size(); i++)
                compareStep(path + "/container[" + i + "]", a.container().get(i), b.container().get(i));

            List<String> oldBones = List.copyOf(a.bones().keySet());
            List<String> newBones = List.copyOf(b.bones().keySet());
            if (!oldBones.equals(newBones))
                report(path + "/bones", String.valueOf(oldBones), String.valueOf(newBones));
            else for (String bone : oldBones)
                compareStep(path + "/bones/" + bone, a.bones().get(bone), b.bones().get(bone));

            if (a.clips().size() != b.clips().size())
                report(path + "/clips", a.clips().size() + " sites", b.clips().size() + " sites");
            else for (int i = 0; i < a.clips().size(); i++)
                compareClipSite(path + "/clips[" + i + "]", a.clips().get(i), b.clips().get(i));
        }

        private void compareStep(
            @NotNull String path,
            @NotNull Map<PoseChannel, PoseExpr> a, @NotNull Map<PoseChannel, PoseExpr> b) {

            if (!a.keySet().equals(b.keySet())) {
                report(path, "channels " + a.keySet(), "channels " + b.keySet());
                return;
            }
            for (Map.Entry<PoseChannel, PoseExpr> channel : a.entrySet())
                compareExpr(path + "/" + channel.getKey().name().toLowerCase(Locale.ROOT),
                    channel.getValue(), b.get(channel.getKey()));
        }

        private void compareClipSite(
            @NotNull String path, EntityPose.@NotNull Clip a, EntityPose.@NotNull Clip b) {

            if (!a.coordinate().equals(b.coordinate()))
                report(path + "/coordinate", a.coordinate(), b.coordinate());
            if (a.drive() != b.drive())
                report(path + "/drive", a.drive().name(), b.drive().name());
            if (!a.field().equals(b.field()))
                report(path + "/field", String.valueOf(a.field()), String.valueOf(b.field()));
            if (a.arguments().size() != b.arguments().size())
                report(path + "/args", a.arguments().size() + " arguments", b.arguments().size() + " arguments");
            else for (int i = 0; i < a.arguments().size(); i++)
                compareExpr(path + "/args[" + i + "]", a.arguments().get(i), b.arguments().get(i));
            compareClipTable(path + "/table", a.clip(), b.clip());
        }

        private void compareClipTable(@NotNull String path, @NotNull PoseClip a, @NotNull PoseClip b) {
            IdentityPair key = new IdentityPair(a, b);
            Boolean held = this.comparedNodes.get(key);
            if (held != null) {
                if (!held) report(path, "repeats a clip-table difference already reported");
                return;
            }

            int before = this.diffs.size();
            if (Float.floatToIntBits(a.lengthSeconds()) != Float.floatToIntBits(b.lengthSeconds()))
                report(path + "/length", bits(a.lengthSeconds()), bits(b.lengthSeconds()));
            if (a.looping() != b.looping())
                report(path + "/looping", String.valueOf(a.looping()), String.valueOf(b.looping()));
            if (a.channels().size() != b.channels().size())
                report(path + "/channels", a.channels().size() + " channels", b.channels().size() + " channels");
            else for (int i = 0; i < a.channels().size(); i++)
                compareClipChannel(path + "/channels[" + i + "]", a.channels().get(i), b.channels().get(i));
            this.comparedNodes.put(key, before == this.diffs.size());
        }

        private void compareClipChannel(
            @NotNull String path, PoseClip.@NotNull Channel a, PoseClip.@NotNull Channel b) {

            if (!a.bone().equals(b.bone())) report(path + "/bone", a.bone(), b.bone());
            if (a.target() != b.target()) report(path + "/target", a.target().name(), b.target().name());
            if (a.keyframes().size() != b.keyframes().size()) {
                report(path + "/keyframes", a.keyframes().size() + " frames", b.keyframes().size() + " frames");
                return;
            }
            for (int i = 0; i < a.keyframes().size(); i++) {
                PoseClip.Keyframe left = a.keyframes().get(i);
                PoseClip.Keyframe right = b.keyframes().get(i);
                if (Float.floatToIntBits(left.timeSeconds()) != Float.floatToIntBits(right.timeSeconds())
                    || Float.floatToIntBits(left.x()) != Float.floatToIntBits(right.x())
                    || Float.floatToIntBits(left.y()) != Float.floatToIntBits(right.y())
                    || Float.floatToIntBits(left.z()) != Float.floatToIntBits(right.z())
                    || left.interpolation() != right.interpolation())
                    report(path + "/keyframes[" + i + "]",
                        keyframe(left), keyframe(right));
            }
        }

        private boolean compareExpr(@NotNull String path, @NotNull PoseExpr a, @NotNull PoseExpr b) {
            if (a == b) return true;
            IdentityPair key = new IdentityPair(a, b);
            Boolean held = this.comparedNodes.get(key);
            if (held != null) {
                if (!held) report(path, "repeats an expression difference already reported");
                return held;
            }
            boolean equal = expressionsEqual(path, a, b);
            this.comparedNodes.put(key, equal);
            return equal;
        }

        private boolean expressionsEqual(@NotNull String path, @NotNull PoseExpr a, @NotNull PoseExpr b) {
            if (a instanceof PoseExpr.Const left && b instanceof PoseExpr.Const right) {
                if (left.width() == right.width()
                    && Double.doubleToLongBits(left.value()) == Double.doubleToLongBits(right.value()))
                    return true;
                report(path, describe(a), describe(b));
                return false;
            }
            if (a instanceof PoseExpr.Input left && b instanceof PoseExpr.Input right) {
                if (left.field().equals(right.field())) return true;
                report(path, describe(a), describe(b));
                return false;
            }
            if (a instanceof PoseExpr.BoneRead left && b instanceof PoseExpr.BoneRead right) {
                if (left.bone().equals(right.bone()) && left.channel() == right.channel()) return true;
                report(path, describe(a), describe(b));
                return false;
            }
            if (a instanceof PoseExpr.Op left && b instanceof PoseExpr.Op right) {
                if (left.operator() != right.operator()
                    || left.operands().size() != right.operands().size()) {
                    report(path, describe(a), describe(b));
                    return false;
                }
                boolean equal = true;
                for (int i = 0; i < left.operands().size(); i++)
                    equal &= compareExpr(
                        path + "/" + left.operator().name().toLowerCase(Locale.ROOT) + "[" + i + "]",
                        left.operands().get(i), right.operands().get(i));
                return equal;
            }
            if (a instanceof PoseExpr.Select left && b instanceof PoseExpr.Select right) {
                boolean equal = comparePredicate(path + "/if", left.condition(), right.condition());
                equal &= compareExpr(path + "/then", left.whenTrue(), right.whenTrue());
                equal &= compareExpr(path + "/else", left.whenFalse(), right.whenFalse());
                return equal;
            }
            report(path, describe(a), describe(b));
            return false;
        }

        private boolean comparePredicate(
            @NotNull String path, @NotNull PosePredicate a, @NotNull PosePredicate b) {

            if (a == b) return true;
            IdentityPair key = new IdentityPair(a, b);
            Boolean held = this.comparedNodes.get(key);
            if (held != null) {
                if (!held) report(path, "repeats a condition difference already reported");
                return held;
            }
            boolean equal = true;
            if (a.comparison() != b.comparison()) {
                report(path, a.comparison().name(), b.comparison().name());
                equal = false;
            } else {
                equal = compareExpr(path + "/left", a.left(), b.left());
                equal &= compareExpr(path + "/right", a.right(), b.right());
            }
            this.comparedNodes.put(key, equal);
            return equal;
        }

    }

    /** One expression node named compactly for a difference line. */
    private static @NotNull String describe(@NotNull PoseExpr expression) {
        return switch (expression) {
            case PoseExpr.Const constant -> "const:" + constant.width() + ":" + constant.value()
                + " (bits " + Long.toHexString(Double.doubleToLongBits(constant.value())) + ")";
            case PoseExpr.Input input -> "input:" + input.field();
            case PoseExpr.BoneRead read -> "bone:" + read.bone() + "." + read.channel();
            case PoseExpr.Op op -> "op:" + op.operator() + "/" + op.operands().size();
            case PoseExpr.Select ignored -> "select";
        };
    }

    /** One keyframe as its raw bits, for a difference line. */
    private static @NotNull String keyframe(PoseClip.@NotNull Keyframe frame) {
        return "t=" + bits(frame.timeSeconds()) + " [" + bits(frame.x()) + "," + bits(frame.y())
            + "," + bits(frame.z()) + "] " + frame.interpolation().name();
    }

    /** A float as its value and raw bits. */
    private static @NotNull String bits(float value) {
        return value + " (bits " + Integer.toHexString(Float.floatToIntBits(value)) + ")";
    }

    /** An old/new node pair compared once, keyed by reference identity rather than by value. */
    private static final class IdentityPair {

        private final @NotNull Object left;
        private final @NotNull Object right;

        private IdentityPair(@NotNull Object left, @NotNull Object right) {
            this.left = left;
            this.right = right;
        }

        @Override
        public boolean equals(@Nullable Object other) {
            return other instanceof IdentityPair pair && this.left == pair.left && this.right == pair.right;
        }

        @Override
        public int hashCode() {
            return 31 * System.identityHashCode(this.left) + System.identityHashCode(this.right);
        }

    }

}
