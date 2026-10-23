package lib.minecraft.renderer.tooling.animation;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.asset.pose.PoseStyle;
import lib.minecraft.renderer.asset.pose.StyleClock;
import lib.minecraft.renderer.diagnostic.Diagnostics;
import lib.minecraft.renderer.engine.pose.ClipDrive;
import lib.minecraft.renderer.engine.pose.PoseChannel;
import lib.minecraft.renderer.engine.pose.PoseExpr;
import lib.minecraft.renderer.engine.pose.PosePredicate;
import lib.minecraft.renderer.engine.pose.StyleDriver;
import lib.minecraft.renderer.tooling.exception.ToolingException;
import lib.minecraft.renderer.vanilla.appearance.Age;
import lib.minecraft.renderer.vanilla.appearance.Flag;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Emits each entity's style catalog - the {@code styles} member of the model table, one row per
 * uniquely identifiable output.
 *
 * <p>A row's drivers are the join of the curated roster with what the entity's poses and pass
 * opacities actually read: the standing row ramps elapsed age and adds the idle half of every clock
 * and every resting selection its forms consult, the walking row composes the stride pair and each
 * clock's stride half over it, and every non-default selectable member earns a row of its own. The
 * {@code bind} row is never emitted - every catalog synthesizes it. Row ids are unique per entity,
 * except that two rows may share one id where disjoint {@code age} members split them - the
 * axolotl's adult factor and baby clip both spell {@code play_dead}.
 *
 * <p><b>A row's {@code sources} member is measured, never asserted.</b> Each composed row is
 * evaluated across one period per contributing mesh - the body, the baby fork, every coat, size
 * and shape option, every overlay pass and its suppressed alternate, the body a selected carried
 * block swaps in, and the body a filled equipment slot swaps in - and a mechanism is written down
 * only where dropping its drivers changes the shape of some channel's travel, where a play site the
 * row plays runs a clip that moves, where a pass scrolls its sheet, or where a pass's opacity reads
 * a drive that travels. A source only a gated pass contributes carries that pass's gate token, and
 * one only a carried block's body contributes carries {@code carried} - so the iron golem's offered
 * flower, swaying on the age clock, earns it a standing row only a selected block admits. A source
 * only a filled slot's body contributes stops the flow instead, as one only a tinted pass
 * contributes does, because no gate token admits either. A row ships where a mechanism moves
 * it or where its held output differs from its
 * base's at some tick of the period - a held selection ships {@code sources: []}, a distinct pose
 * nothing travels - so a subject whose every row renders as its base ships no {@code styles}
 * member and stays bind-only.
 *
 * <p><b>A stride walks at its subject's vanilla pace.</b> The walking row is measured first under
 * the literal pair - hold 1, ramp 1 - and a side whose sources then hold {@code stride} reads the
 * walk clock: it is driven at its age's wander and re-measured to the same sources, and its age's
 * ceiling - the fastest walk its subject's own behaviours reach - is written once for the catalog, in
 * the model's {@code ceilings} member under the age's token. A side reading no walk clock keeps the
 * literal pair and records none, and a reading side with no pace stops the flow.
 *
 * <p>A state-gated play site plays where the binding holds its gate field non-zero; a walk-driven
 * one runs unconditionally, as vanilla applies it, and moves a row wherever its resolved clock or
 * capped amplitude travels under that row's binding - the nautilus feeds its clock elapsed age and
 * floors its amplitude, so its swim moves the standing row with nothing walking. The two universal
 * rows are measured per age side where the subject draws a baby mesh, each side keeping only the
 * selections and clocks its own forms read, and a subject whose sides measure apart - by their
 * sources or their drives - is emitted an age-split pair, a side with no distinct
 * output shipping no row, which is how a baby whose clips all rest stays bind-only while its adult
 * moves.
 *
 * <p>Drive spelling is deterministic: {@code field}, {@code wave} and {@code extent} are always
 * spelled, {@code rest} only where the wave reads it - a sweep, cycle, stroke or trapezoid - then a
 * stroke's {@code rate} and all four of a trapezoid's {@code rise}, {@code dwell}, {@code fall} and
 * {@code gap}, a zero span included, then {@code group} when the field has one; {@code base} is
 * omitted where the standing row itself is not emitted - a baseless row spells its whole composed
 * driver map.
 */
@UtilityClass
public final class StyleFlow {

    /** The three universally driven fields - elapsed age, and the stride pair. */
    private static final @NotNull String AGE_IN_TICKS = "ageInTicks";

    private static final @NotNull String WALK_SPEED = "walkAnimationSpeed";

    private static final @NotNull String WALK_POS = "walkAnimationPos";

    /**
     * The source kinds in emission order, which is this flow's own rather than the order
     * {@link StyleClock} declares them in. {@link StyleClock#NONE} is never a row source - its token
     * is the spelling of a {@link ClipDrive#NONE clip drive}.
     */
    private static final @NotNull List<StyleClock> SOURCE_ORDER =
        List.of(StyleClock.TICK, StyleClock.FIGURE, StyleClock.SELECT, StyleClock.STRIDE, StyleClock.SCROLL);

    // ------------------------------------------------------------------------------------
    // emit
    // ------------------------------------------------------------------------------------

    /**
     * Derives, measures and writes each entity's {@code styles} member into the model table.
     *
     * <p>Two census lines close the pass, telling the two still-nesses apart: a select site whose
     * field no roster member answers is a figure to add, where a subject no row moves is simply
     * still - flattening the first into the second is what the lines exist to prevent. The pace
     * scope beneath it logs each paced side's wander and fastest walk, and closes with a count of
     * the stride sides paced and the ones reading no walk clock.
     *
     * @param diagnostics the scope the pass is recorded against
     * @param models the model table, rewritten in place ahead of being written
     * @param poses every pose row the pose table carries
     * @param clips every parsed keyframe clip by coordinate
     * @param periodTicks the ticks a sweep or cycle figure wraps at, which is also the window every
     *     measurement samples
     * @param paces each subject's walks by age, keyed by entity id - a subject missing from the map
     *     walks at no pace
     */
    public static void emit(
        @NotNull Diagnostics diagnostics, @NotNull JsonTree models,
        @NotNull Map<String, PoseOutcome> poses, @NotNull Map<String, KeyframeClip> clips,
        int periodTicks, @NotNull Map<String, EntityPace> paces) {

        Diagnostics paceLog = diagnostics.child("pace");
        Map<String, Set<String>> reads = new LinkedHashMap<>();
        Map<String, PoseProgram> grounded = new LinkedHashMap<>();
        Map<String, Set<StyleClock>> measured = new LinkedHashMap<>();
        Set<String> unanswered = new TreeSet<>();
        Set<String> still = new TreeSet<>();
        int[] subjects = {0};
        int[] sides = {0, 0};

        models.members().forEach((entity, row) -> {
            subjects[0]++;
            List<Site> sites = meshSites(row);
            List<Scroll> scrolls = scrollSites(row);
            List<Opacity> opacities = opacitySites(row);
            Set<String> read = new LinkedHashSet<>();
            Set<String> babyRead = new LinkedHashSet<>();
            Set<String> adultRead = new LinkedHashSet<>();
            for (Site site : sites) {
                Set<String> held = readsOf(site.poseKey(), poses, reads);
                read.addAll(held);
                (site.baby() ? babyRead : adultRead).addAll(held);
            }
            for (Opacity opacity : opacities) {
                read.addAll(opacity.inputs());
                if (opacity.adultDrawn()) adultRead.addAll(opacity.inputs());
                if (opacity.babyDrawn()) babyRead.addAll(opacity.inputs());
            }
            recordUnanswered(entity, sites, poses, unanswered);

            boolean hasBaby = sites.stream().anyMatch(Site::baby);
            EntityPace pace = paces.getOrDefault(entity, EntityPace.NONE);
            Function<Row, List<JsonTree>> measure = side -> sourcesOf(entity, side, sites, scrolls, opacities,
                poses, clips, periodTicks, grounded, measured, diagnostics);
            List<Row> rows = deriveRows(entity, read, babyRead, adultRead, hasBaby, diagnostics);
            Row idle = rows.getFirst();
            List<JsonTree> written = new ArrayList<>();
            EnumMap<Age, WalkPace> ceilings = new EnumMap<>(Age.class);
            boolean idleEmitted = false;
            for (Row held : rows) {
                if (held.select() && held.composed().equals(idle.composed())) {
                    diagnostics.info("%s style '%s' composes to its base's drivers and is not emitted",
                        entity, held.id());
                    continue;
                }
                Map<String, Drive> base = PoseStyle.IDLE.equals(held.id()) ? Map.of() : idle.composed();
                boolean stride = PoseStyle.STRIDE.equals(held.id());
                Row emitted = held;
                List<JsonTree> sources;
                List<Sided> walked = List.of();
                if (!held.select() && hasBaby) {
                    Sided adult = sided(entity, Age.ADULT, aged(held, Age.ADULT, adultRead), stride, pace, measure, paceLog);
                    Sided baby = sided(entity, Age.BABY, aged(held, Age.BABY, babyRead), stride, pace, measure, paceLog);
                    boolean adultShips = !adult.sources().isEmpty()
                        || distinct(adult.row(), base, sites, poses, clips, periodTicks, grounded);
                    boolean babyShips = !baby.sources().isEmpty()
                        || distinct(baby.row(), base, sites, poses, clips, periodTicks, grounded);
                    if (adultShips != babyShips
                        || !spelled(adult.sources()).equals(spelled(baby.sources()))
                        || !bindingKey(adult.row().composed()).equals(bindingKey(baby.row().composed()))) {
                        diagnostics.info("%s style '%s' measures apart by age and is emitted age-split",
                            entity, held.id());
                        if (adultShips) {
                            written.add(rowNode(adult.row(), adult.sources(), idleEmitted));
                            idleEmitted |= PoseStyle.IDLE.equals(held.id());
                            if (stride) walk(adult, sides, ceilings);
                        }
                        if (babyShips) {
                            written.add(rowNode(baby.row(), baby.sources(), idleEmitted));
                            idleEmitted |= PoseStyle.IDLE.equals(held.id());
                            if (stride) walk(baby, sides, ceilings);
                        }
                        continue;
                    }
                    // The two sides measure alike, so their drives are the row's own; what one side
                    // was paced at is what the ageless row walks at, and each side records its own
                    // age's ceiling.
                    emitted = unaged(adult.row());
                    sources = measure.apply(emitted);
                    if (stride) walked = List.of(adult, baby);
                } else if (stride) {
                    Sided only = sided(entity, Age.ADULT, held, true, pace, measure, paceLog);
                    emitted = only.row();
                    sources = only.sources();
                    walked = List.of(only);
                } else
                    sources = measure.apply(held);
                if (sources.isEmpty()
                    && !distinct(emitted, base, sites, poses, clips, periodTicks, grounded)) continue;
                written.add(rowNode(emitted, sources, idleEmitted));
                if (PoseStyle.IDLE.equals(held.id())) idleEmitted = true;
                walked.forEach(side -> walk(side, sides, ceilings));
            }
            if (written.isEmpty()) still.add(entity);
            else {
                row.put("styles", JsonTree.array().addAll(written));
                if (!ceilings.isEmpty()) row.put("ceilings", ceilingsNode(ceilings));
            }
        });

        paceLog.info("%d stride side(s) walk at a vanilla pace; %d read no walk clock and keep hold 1 / ramp 1",
            sides[0], sides[1]);
        diagnostics.info("%d select site(s) read a figure nothing answers: %s",
            unanswered.size(), unanswered);
        diagnostics.info("%d of %d subject(s) ship no style row - nothing shipped moves them: %s",
            still.size(), subjects[0], still);
    }

    /**
     * One side of a universal row as it is measured to ship.
     *
     * @param age the side's age, the adult for a row with no baby
     * @param row the side's row, paced where it walks
     * @param sources its measured sources
     * @param reads whether it is a stride side whose probe reads the walk clock
     */
    private record Sided(@NotNull Age age, @NotNull Row row, @NotNull List<JsonTree> sources, boolean reads) {}

    /**
     * One side measured to ship - a stride side driven at its age's wander where its probe, the
     * measurement under the literal pair, reads the walk clock, then re-measured under that pace and
     * logged with its wander and its fastest walk. A reading side with no pace keeps the literal pair
     * and records an error, as does a pace that moves the side's sources or a fastest walk under its
     * wander.
     *
     * @param entity the subject measured
     * @param age the side's age, the adult for a row that names none
     * @param side the side's row under the literal pair
     * @param stride whether the side is the stride row's
     * @param pace the subject's walks by age
     * @param measure what measures one row's sources
     * @param diagnostics the pace scope
     * @return the side as it ships
     */
    private static @NotNull Sided sided(
        @NotNull String entity, @NotNull Age age, @NotNull Row side, boolean stride,
        @NotNull EntityPace pace, @NotNull Function<Row, List<JsonTree>> measure,
        @NotNull Diagnostics diagnostics) {

        List<JsonTree> probe = measure.apply(side);
        if (!stride || !readsWalkClock(probe)) return new Sided(age, side, probe, false);
        String token = token(age);
        Optional<AgePace> walks = pace.at(age);
        if (walks.isEmpty()) {
            diagnostics.error("%s style 'stride' moves by the walk clock at %s and has no pace", entity, token);
            return new Sided(age, side, probe, true);
        }
        Row paced = paced(side, walks.get());
        List<JsonTree> sources = measure.apply(paced);
        if (!spelled(sources).equals(spelled(probe)))
            diagnostics.error("%s style 'stride' %s sources move under the pace: [%s] -> [%s]",
                entity, token, spelled(probe), spelled(sources));
        WalkPace wander = walks.get().wander();
        WalkPace ceiling = walks.get().ceiling();
        diagnostics.info("%s %s walks at speed %s, clock %s a tick - %s", entity, token,
            Float.toString(wander.speed()), Float.toString(wander.rate()), wander.basis());
        diagnostics.info("%s %s reaches speed %s, clock %s a tick at its fastest - %s", entity, token,
            Float.toString(ceiling.speed()), Float.toString(ceiling.rate()), ceiling.basis());
        if (ceiling.speed() < wander.speed() || ceiling.rate() < wander.rate())
            diagnostics.error("%s %s ceiling [%s, %s] is under its wander [%s, %s]", entity, token,
                Float.toString(ceiling.speed()), Float.toString(ceiling.rate()),
                Float.toString(wander.speed()), Float.toString(wander.rate()));
        return new Sided(age, paced, sources, true);
    }

    /**
     * Records one shipped stride side - its ceiling at its age where it walks at a pace, and a count of
     * it as paced or as reading no walk clock; a reading side with no pace is neither.
     */
    private static void walk(@NotNull Sided side, int @NotNull [] sides, @NotNull Map<Age, WalkPace> ceilings) {
        side.row().ceiling().ifPresent(ceiling -> ceilings.put(side.age(), ceiling));
        if (side.row().ceiling().isPresent()) sides[0]++;
        else if (!side.reads()) sides[1]++;
    }

    /** Whether a measured source list holds the walk clock, bare or under a gate. */
    private static boolean readsWalkClock(@NotNull List<JsonTree> sources) {
        String stride = StyleClock.STRIDE.token();
        return sources.stream().anyMatch(source -> source.isPrimitive()
            ? source.asString().filter(stride::equals).isPresent()
            : source.findString("source").filter(stride::equals).isPresent());
    }

    /** One stride row driven at an age's wander - the swing held, the clock ramped - holding the age's ceiling. */
    private static @NotNull Row paced(@NotNull Row row, @NotNull AgePace pace) {
        LinkedHashMap<String, Drive> own = new LinkedHashMap<>(row.own());
        own.put(WALK_SPEED, drive(WALK_SPEED, StyleRoster.Motion.hold(pace.wander().speed()), Optional.empty()));
        own.put(WALK_POS, drive(WALK_POS, StyleRoster.Motion.ramp(pace.wander().rate()), Optional.empty()));
        LinkedHashMap<String, Drive> composed = new LinkedHashMap<>(row.composed());
        composed.put(WALK_SPEED, own.get(WALK_SPEED));
        composed.put(WALK_POS, own.get(WALK_POS));
        return new Row(row.id(), row.select(), row.age(), row.toggles(), own, composed,
            Optional.of(pace.ceiling()));
    }

    /**
     * Names every select play site whose field no roster member drives - a clip no style row can
     * ever start, which is a figure to add rather than a subject that is still.
     */
    private static void recordUnanswered(
        @NotNull String entity, @NotNull List<Site> sites,
        @NotNull Map<String, PoseOutcome> poses, @NotNull Set<String> unanswered) {

        for (Site site : sites)
            if (poses.get(site.poseKey()) instanceof PoseOutcome.Extracted extracted)
                for (PoseClipSite play : extracted.program().clipSites())
                    if (play.drive() == ClipDrive.SELECT
                        && !StyleRoster.driven().contains(play.state()))
                        unanswered.add(entity + " plays " + play.clip() + " behind '" + play.state() + "'");
    }

    // ------------------------------------------------------------------------------------
    // row derivation
    // ------------------------------------------------------------------------------------

    /**
     * One render-state field a style drives, as the measurement reads it and the table spells it.
     *
     * @param driver the production driver a loaded row answers this field with
     */
    private record Drive(@NotNull StyleDriver driver) {

        /** The render-state field this drive answers. */
        @NotNull String field() {
            return this.driver.field();
        }

        /** The exclusion group token this drive's field belongs to, empty for an ungrouped field. */
        @NotNull Optional<String> group() {
            return this.driver.group();
        }

        /**
         * What this drive answers at one tick - production's own {@link StyleDriver#at}, so a
         * measurement binds what the loaded row renders.
         *
         * @param tick the tick being posed
         * @param periodTicks the window a sweep or cycle wraps at
         * @return the field's value at that tick
         */
        float at(int tick, int periodTicks) {
            return this.driver.at(tick, periodTicks);
        }

        /** Whether this drive answers non-zero at any tick of one period. */
        boolean answers(int periodTicks) {
            for (int tick = 0; tick < periodTicks; tick++)
                if (at(tick, periodTicks) != 0f) return true;
            return false;
        }

        /** Whether this drive's value moves at all across one period. */
        boolean travels(int periodTicks) {
            float first = at(0, periodTicks);
            for (int tick = 1; tick < periodTicks; tick++)
                if (Float.compare(at(tick, periodTicks), first) != 0) return true;
            return false;
        }

        /**
         * The source kind this drive belongs to, which is what a caused variation is filed under -
         * elapsed age the tick, the stride pair the stride, a grouped drive a selection, and every
         * other drive a render-state scalar.
         */
        @NotNull StyleClock kind() {
            if (AGE_IN_TICKS.equals(this.field())) return StyleClock.TICK;
            if (WALK_SPEED.equals(this.field()) || WALK_POS.equals(this.field())) return StyleClock.STRIDE;
            if (this.group().isPresent()) return StyleClock.SELECT;
            return StyleClock.FIGURE;
        }

        /**
         * This drive spelled as the table carries it - the wave by its own token, the rest only where
         * the wave travels from it, and whatever the wave reads beside its extent after the extent.
         */
        @NotNull JsonTree node() {
            StyleDriver.Wave wave = this.driver.wave();
            JsonTree node = JsonTree.object().put("field", this.field()).put("wave", wave.token());
            boolean rested = switch (wave) {
                case StyleDriver.Wave.Plain plain -> switch (plain) {
                    case HOLD, RAMP -> false;
                    case SWEEP, CYCLE -> true;
                };
                case StyleDriver.Wave.Stroke ignored -> true;
                case StyleDriver.Wave.Trapezoid ignored -> true;
            };
            if (rested) node.put("rest", this.driver.rest());
            node.put("extent", this.driver.extent());
            switch (wave) {
                case StyleDriver.Wave.Plain ignored -> { }
                case StyleDriver.Wave.Stroke stroke -> node.put("rate", stroke.rate());
                case StyleDriver.Wave.Trapezoid trapezoid -> node.putInt("rise", trapezoid.rise())
                    .putInt("dwell", trapezoid.dwell()).putInt("fall", trapezoid.fall()).putInt("gap", trapezoid.gap());
            }
            this.group().ifPresent(held -> node.put("group", held));
            return node;
        }

    }

    /**
     * One derived style row ahead of its measurement.
     *
     * @param id the style id
     * @param select whether this is a selection row rather than a universal one
     * @param age the age the row applies to, empty for both
     * @param toggles the bone toggles the selection entails
     * @param own the drives the row declares itself
     * @param composed the whole driver map after base composition and group replacement
     * @param ceiling the fastest walk a paced stride side reaches, empty on every other row
     */
    private record Row(
        @NotNull String id,
        boolean select,
        @NotNull Optional<Age> age,
        @NotNull List<String> toggles,
        @NotNull LinkedHashMap<String, Drive> own,
        @NotNull LinkedHashMap<String, Drive> composed,
        @NotNull Optional<WalkPace> ceiling
    ) {}

    /**
     * The entity's derived rows in emission order - standing, walking, then one row per
     * non-default driven member whose field its poses read, in roster order.
     *
     * <p>Each clock a form or a pass opacity reads puts its idle half on the standing row, after
     * elapsed age and before the resting selections, and its stride half - where it has one - on the
     * walking row, after the stride pair and before the forked selections. A stride half is spelled
     * wherever it differs, a held zero included, since the walking row would otherwise inherit the
     * standing row's half. The walking row's stride pair is the literal hold 1, ramp 1 a side is
     * probed under before it takes a pace.
     *
     * @param entity the subject being derived, for the refusal
     * @param reads every field the entity's poses read
     * @param babyReads the fields its baby forms read
     * @param adultReads the fields everything but its baby forms reads
     * @param hasBaby whether the entity draws a baby mesh at all, without which no row is aged
     * @param diagnostics the scope a roster disagreement is recorded against
     * @return the derived rows, the standing row first
     */
    private static @NotNull List<Row> deriveRows(
        @NotNull String entity, @NotNull Set<String> reads, @NotNull Set<String> babyReads,
        @NotNull Set<String> adultReads, boolean hasBaby, @NotNull Diagnostics diagnostics) {

        LinkedHashMap<String, Drive> idle = new LinkedHashMap<>();
        idle.put(AGE_IN_TICKS, drive(AGE_IN_TICKS, StyleRoster.Motion.ramp(1f), Optional.empty()));
        for (StyleRoster.Clock clock : StyleRoster.CLOCKS)
            if (reads.contains(clock.field())) idle.put(clock.field(), drive(clock.field(), clock.idle(), Optional.empty()));
        for (StyleRoster.Group group : StyleRoster.GROUPS) {
            if (!group.readBy(reads)) continue;
            StyleRoster.Member selected = group.selected(false);
            if (selected.drives())
                idle.put(selected.field(), drive(selected.field(), StyleRoster.Motion.hold(1f), Optional.of(group.token())));
        }

        LinkedHashMap<String, Drive> strideOwn = new LinkedHashMap<>();
        strideOwn.put(WALK_SPEED, drive(WALK_SPEED, StyleRoster.Motion.hold(1f), Optional.empty()));
        strideOwn.put(WALK_POS, drive(WALK_POS, StyleRoster.Motion.ramp(1f), Optional.empty()));
        for (StyleRoster.Clock clock : StyleRoster.CLOCKS)
            if (reads.contains(clock.field()))
                clock.stride().ifPresent(half -> strideOwn.put(clock.field(), drive(clock.field(), half, Optional.empty())));
        for (StyleRoster.Group group : StyleRoster.GROUPS) {
            if (!group.forked() || !group.readBy(reads)) continue;
            StyleRoster.Member selected = group.selected(true);
            if (selected.drives())
                strideOwn.put(selected.field(), drive(selected.field(), StyleRoster.Motion.hold(1f), Optional.of(group.token())));
        }

        List<Row> out = new ArrayList<>();
        out.add(new Row(PoseStyle.IDLE, false, Optional.empty(), List.of(), idle, idle, Optional.empty()));
        out.add(new Row(PoseStyle.STRIDE, false, Optional.empty(), List.of(), strideOwn, compose(idle, strideOwn),
            Optional.empty()));
        for (StyleRoster.Group group : StyleRoster.GROUPS)
            for (StyleRoster.Member member : group.members()) {
                if (!member.drives() || !reads.contains(member.field()) || group.isDefault(member)) continue;
                Optional<Age> age = !hasBaby ? Optional.empty()
                    : babyReads.contains(member.field()) && !adultReads.contains(member.field()) ? Optional.of(Age.BABY)
                    : adultReads.contains(member.field()) && !babyReads.contains(member.field()) ? Optional.of(Age.ADULT)
                    : Optional.empty();
                if (!age.equals(group.age()))
                    diagnostics.error("%s reads '%s' on %s where the roster declares %s",
                        entity, member.field(), age.map(at -> token(at) + " forms alone").orElse("either age's forms"),
                        group.age().map(at -> token(at) + " alone").orElse("either age"));
                LinkedHashMap<String, Drive> own = new LinkedHashMap<>();
                own.put(member.field(), drive(member.field(), StyleRoster.Motion.hold(1f), Optional.of(group.token())));
                out.add(new Row(StyleRoster.styleId(member.field()), true, age,
                    StyleRoster.togglesOf(member.field()), own, compose(idle, own), Optional.empty()));
            }
        return out;
    }

    /** One wave as the drive it spells for one field - travelling from a rest of zero to its extent. */
    private static @NotNull Drive drive(
        @NotNull String field, @NotNull StyleRoster.Motion motion, @NotNull Optional<String> group) {

        return new Drive(new StyleDriver(field, motion.wave(), 0f, motion.extent(), group));
    }

    /**
     * One universal row narrowed to a single age side - the age is what {@link #skips} filters the
     * meshes by, so the side measures over its own forms alone, and the drives are narrowed with it:
     * the side keeps the stride pair and elapsed age, every selection whose roster group declares no
     * age or this one, and every other drive whose field its own forms read.
     *
     * @param row the universal row
     * @param age the side's age
     * @param reads every field that side's forms and pass opacities read
     * @return the side's row
     */
    private static @NotNull Row aged(@NotNull Row row, @NotNull Age age, @NotNull Set<String> reads) {
        return new Row(row.id(), row.select(), Optional.of(age), row.toggles(), narrowed(row.own(), age, reads),
            narrowed(row.composed(), age, reads), row.ceiling());
    }

    /** One driver map with every drive an age side does not keep taken out, in its own order; see {@link #aged}. */
    private static @NotNull LinkedHashMap<String, Drive> narrowed(
        @NotNull Map<String, Drive> drives, @NotNull Age age, @NotNull Set<String> reads) {

        LinkedHashMap<String, Drive> out = new LinkedHashMap<>();
        drives.forEach((field, drive) -> {
            boolean kept = AGE_IN_TICKS.equals(field) || WALK_SPEED.equals(field) || WALK_POS.equals(field)
                || drive.group().map(group -> groupAge(group).map(age::equals).orElse(true))
                    .orElseGet(() -> reads.contains(field));
            if (kept) out.put(field, drive);
        });
        return out;
    }

    /** The age the roster group spelled by a drive's group token declares, empty where either age's forms read it. */
    private static @NotNull Optional<Age> groupAge(@NotNull String token) {
        return StyleRoster.GROUPS.stream()
            .filter(group -> group.token().equals(token))
            .map(StyleRoster.Group::age)
            .findFirst()
            .flatMap(Function.identity());
    }

    /** The token a table spells an age by, as a style row's {@code age} and a model's {@code ceilings} key it. */
    private static @NotNull String token(@NotNull Age age) {
        return age.name().toLowerCase(Locale.ROOT);
    }

    /** One age side's row taken back to both ages, its drives and its ceiling kept. */
    private static @NotNull Row unaged(@NotNull Row row) {
        return new Row(row.id(), row.select(), Optional.empty(), row.toggles(), row.own(), row.composed(),
            row.ceiling());
    }

    /**
     * One row's drivers composed flat over its base's - the base map copied, then each own drive
     * put by its field, a grouped drive first evicting any still-inherited driver carrying the
     * same group. The arithmetic the loader applies at read, applied here so the measurement binds
     * what the loaded row will answer.
     */
    private static @NotNull LinkedHashMap<String, Drive> compose(
        @NotNull Map<String, Drive> base, @NotNull Map<String, Drive> own) {

        LinkedHashMap<String, Drive> out = new LinkedHashMap<>(base);
        for (Drive drive : own.values()) {
            drive.group().ifPresent(group -> out.entrySet().removeIf(entry ->
                entry.getValue() == base.get(entry.getKey())
                    && entry.getValue().group().filter(group::equals).isPresent()));
            out.put(drive.field(), drive);
        }
        return out;
    }

    // ------------------------------------------------------------------------------------
    // the entity's meshes
    // ------------------------------------------------------------------------------------

    /**
     * The gate admitting a mesh. A pass's is resolved from what its {@code when} member spells - the
     * same precedence the render-time assembler takes, flag first, then charged, then tinted - and a
     * wearer form's is the selection that swaps it in.
     */
    private sealed interface Gate {

        /** The gate of a pass nothing conditions. */
        @NotNull Gate OPEN = new Open();

        /**
         * The gate of a body a selected carried block swaps in - drawn exactly while a caller selects a
         * block, which the render-time appearance admits under {@link Flag#CARRIED}.
         */
        @NotNull Gate CARRIED = new OnFlag(Flag.CARRIED, true);

        /** A pass nothing conditions. */
        record Open() implements Gate {}

        /**
         * A pass drawn where an appearance flag answers as it expects.
         *
         * @param flag the flag the pass's {@code when} member names
         * @param expected the answer the pass is drawn at - {@code false} for a pass drawn where its
         *     flag rests unset, which the source spelling cannot carry
         */
        record OnFlag(@NotNull Flag flag, boolean expected) implements Gate {}

        /**
         * A pass drawn while its tint axis selects a dye apart from its baked tint, which no flag
         * spells and so no source can carry.
         */
        record Tinted() implements Gate {}

        /**
         * A body a filled equipment slot swaps in - drawn while a caller fills the slot, which no
         * flag spells and so no source can carry.
         *
         * @param slot the equipment axis option naming the slot
         */
        record Filled(@NotNull String slot) implements Gate {}

        /** Whether this gate admits unconditionally. */
        default boolean open() {
            return this instanceof Open;
        }

        /** Whether this gate admits only where its flag rests unset. */
        default boolean negative() {
            return this instanceof OnFlag(Flag ignored, boolean expected) && !expected;
        }

        /** What this gate is told apart by in a site's key. */
        default @NotNull String key() {
            return switch (this) {
                case Open ignored -> "open";
                case OnFlag(Flag flag, boolean expected) -> (expected ? "" : "!") + token(flag);
                case Tinted ignored -> "tinted";
                case Filled(String slot) -> "filled=" + slot;
            };
        }

    }

    /**
     * One mesh a subject draws, joined to the pose row that moves it.
     *
     * @param poseKey the pose table key the mesh resolves
     * @param gate the gate admitting the draw, {@link Gate#OPEN} for a form a plain render draws
     * @param baby whether only a baby render draws it
     */
    private record Site(@NotNull String poseKey, @NotNull Gate gate, boolean baby) {}

    /**
     * One overlay pass that scrolls its sheet.
     *
     * @param gate the overlay gate admitting the pass
     * @param babyDrawn whether a baby render draws the pass at all
     */
    private record Scroll(@NotNull Gate gate, boolean babyDrawn) {}

    /**
     * One overlay pass whose opacity follows the clock.
     *
     * @param gate the overlay gate admitting the pass
     * @param adultDrawn whether a render of any age but a baby draws the pass
     * @param babyDrawn whether a baby render draws the pass
     * @param inputs the render-state fields the opacity reads, in the order it names them
     */
    private record Opacity(@NotNull Gate gate, boolean adultDrawn, boolean babyDrawn, @NotNull Set<String> inputs) {}

    /**
     * Every mesh one entity draws, each with the gate that admits it - the age forms, every coat,
     * size and shape option through their explicit {@code pose} members, every overlay pass,
     * suppressed alternate and baby delta through the class heading its geometry coordinate, the
     * carried axis's form, the body a selected block swaps in, under the gate {@link Gate#CARRIED},
     * and each equipment axis's form, the body a filled slot swaps in, under a {@link Gate.Filled}
     * naming the slot - the two wearer axes read after every other site, so an ungated one of the
     * same pose subsumes it. A filled slot's form is measured so that a source only it contributes
     * stops the flow: no gate token admits a filled slot, so the source spelling has nothing to
     * carry one under. Worn armor and the equipment layers are not drawn by a plain render and are
     * not consulted.
     */
    private static @NotNull List<Site> meshSites(@NotNull JsonTree row) {
        LinkedHashMap<String, Site> out = new LinkedHashMap<>();
        row.find("axes").ifPresent(axes -> axes.members().forEach((axis, held) -> {
            if (PoseFlow.CARRIED_AXIS.equals(axis) || PoseFlow.EQUIPMENT_AXIS.equals(axis)) return;
            held.find("options").ifPresent(options -> options.members().forEach((option, chosen) -> {
                boolean baby = "age".equals(axis) && "baby".equals(option);
                chosen.findString("pose").ifPresent(key -> site(out, key, Gate.OPEN, baby));
                chosen.find("overlays").ifPresent(list -> list.elements().toList().forEach(overlay ->
                    overlaySites(out, overlay, baby)));
            }));
        }));
        row.find("overlays").ifPresent(list -> list.elements().toList().forEach(overlay ->
            overlaySites(out, overlay, false)));
        row.findPath("axes", PoseFlow.CARRIED_AXIS, "options").ifPresent(options -> options.members()
            .forEach((option, chosen) -> chosen.findString("pose")
                .ifPresent(key -> site(out, key, Gate.CARRIED, false))));
        row.findPath("axes", PoseFlow.EQUIPMENT_AXIS, "options").ifPresent(options -> options.members()
            .forEach((slot, chosen) -> chosen.findString("pose")
                .ifPresent(key -> site(out, key, new Gate.Filled(slot), false))));
        return List.copyOf(out.values());
    }

    /** One overlay pass's meshes - its own, its suppressed alternate, and its baby delta's pair. */
    private static void overlaySites(
        @NotNull Map<String, Site> out, @NotNull JsonTree overlay, boolean baby) {

        Gate gate = gateOf(overlay.find("when"));
        overlay.findString("geometry").ifPresent(coordinate -> site(out, head(coordinate), gate, baby));
        overlay.findString("no_hat_geometry").ifPresent(coordinate -> site(out, head(coordinate), gate, baby));
        overlay.find("baby").ifPresent(delta -> {
            delta.findString("geometry").ifPresent(coordinate -> site(out, head(coordinate), gate, true));
            delta.findString("no_hat_geometry").ifPresent(coordinate -> site(out, head(coordinate), gate, true));
        });
    }

    /**
     * Records one mesh site, deduplicated: an ungated site already held for the same pose and age
     * subsumes a gated one, its contributions being admitted unconditionally either way.
     */
    private static void site(
        @NotNull Map<String, Site> out, @NotNull String poseKey, @NotNull Gate gate, boolean baby) {

        String open = poseKey + '|' + Gate.OPEN.key() + '|' + baby;
        if (out.containsKey(open)) return;
        out.putIfAbsent(poseKey + '|' + gate.key() + '|' + baby, new Site(poseKey, gate, baby));
    }

    /** Every overlay pass of the row that scrolls its sheet by a non-zero rate. */
    private static @NotNull List<Scroll> scrollSites(@NotNull JsonTree row) {
        List<Scroll> out = new ArrayList<>();
        row.find("overlays").ifPresent(list -> list.elements().toList().forEach(overlay ->
            overlay.find("texture_scroll").ifPresent(scroll -> {
                if (scroll.getFloat("u", 0f) == 0f && scroll.getFloat("v", 0f) == 0f) return;
                out.add(new Scroll(gateOf(overlay.find("when")), overlay.has("baby")));
            })));
        return out;
    }

    /**
     * Every overlay pass of the row whose {@code opacity} reads the render state - the row's own and
     * each option's, a pass of the baby age option drawn on a baby alone and one carrying a baby
     * delta drawn at both ages, as {@link #meshSites} reads their meshes.
     */
    private static @NotNull List<Opacity> opacitySites(@NotNull JsonTree row) {
        List<Opacity> out = new ArrayList<>();
        row.find("axes").ifPresent(axes -> axes.members().forEach((axis, held) ->
            held.find("options").ifPresent(options -> options.members().forEach((option, chosen) -> {
                boolean baby = "age".equals(axis) && "baby".equals(option);
                chosen.find("overlays").ifPresent(list -> list.elements().toList().forEach(overlay ->
                    opacitySite(out, overlay, baby)));
            }))));
        row.find("overlays").ifPresent(list -> list.elements().toList().forEach(overlay ->
            opacitySite(out, overlay, false)));
        return out;
    }

    /** One overlay pass's opacity, where it carries one. */
    private static void opacitySite(@NotNull List<Opacity> out, @NotNull JsonTree overlay, boolean baby) {
        overlay.find("opacity").ifPresent(expression -> {
            Set<String> inputs = new LinkedHashSet<>();
            inputsOf(expression, inputs);
            out.add(new Opacity(gateOf(overlay.find("when")), !baby, baby || overlay.has("baby"),
                Collections.unmodifiableSet(inputs)));
        });
    }

    /** Collects every {@code input} an expression node names, in the order it names them. */
    private static void inputsOf(@NotNull JsonTree node, @NotNull Set<String> out) {
        if (node.isArray()) node.elements().forEach(element -> inputsOf(element, out));
        if (!node.isObject()) return;
        node.members().forEach((member, value) -> {
            if ("input".equals(member)) value.asString().ifPresent(out::add);
            else inputsOf(value, out);
        });
    }

    /**
     * The gate one {@code when} member spells; see {@link Gate}.
     *
     * @throws ToolingException if the member names a flag no {@link Flag} is
     */
    private static @NotNull Gate gateOf(@NotNull Optional<JsonTree> when) {
        if (when.isEmpty()) return Gate.OPEN;
        JsonTree held = when.get();
        Optional<String> flag = held.findString("flag");
        if (flag.isPresent()) {
            Flag named = Arrays.stream(Flag.values())
                .filter(candidate -> token(candidate).equals(flag.get()))
                .findFirst()
                .orElseThrow(() -> new ToolingException("A pass is gated on flag '%s', which is not a flag", flag.get()));
            return new Gate.OnFlag(named, held.findBoolean("value").orElse(false));
        }
        if (held.findBoolean("charged").orElse(false)) return new Gate.OnFlag(Flag.CHARGED, true);
        if (held.findBoolean("tinted").orElse(false)) return new Gate.Tinted();
        return Gate.OPEN;
    }

    /** The token a table spells a flag by, as a pass's {@code when} and a source's {@code gate} name it. */
    private static @NotNull String token(@NotNull Flag flag) {
        return flag.name().toLowerCase(Locale.ROOT);
    }

    /** The class a geometry coordinate is headed with, which is what keys the pose it takes. */
    private static @NotNull String head(@NotNull String coordinate) {
        int member = coordinate.indexOf('#');
        return member < 0 ? coordinate : coordinate.substring(0, member);
    }

    /**
     * Every render-state field one pose row reads - its expressions' inputs, its play sites' gate
     * fields and argument inputs - memoized per row, a refused row reading nothing.
     */
    private static @NotNull Set<String> readsOf(
        @NotNull String poseKey, @NotNull Map<String, PoseOutcome> poses,
        @NotNull Map<String, Set<String>> memo) {

        Set<String> held = memo.get(poseKey);
        if (held != null) return held;
        Set<String> out = new LinkedHashSet<>();
        if (poses.get(poseKey) instanceof PoseOutcome.Extracted extracted) {
            PoseProgram program = extracted.program();
            Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
            for (Map<PoseChannel, PoseExpr> step : program.container())
                step.values().forEach(expr -> readInto(out, expr, visited));
            program.bones().values().forEach(channels ->
                channels.values().forEach(expr -> readInto(out, expr, visited)));
            for (PoseClipSite play : program.clipSites()) {
                if (!play.state().isEmpty()) out.add(play.state());
                play.arguments().forEach(expr -> readInto(out, expr, visited));
                readInto(out, play.condition(), visited);
            }
        }
        Set<String> sealed = Collections.unmodifiableSet(out);
        memo.put(poseKey, sealed);
        return sealed;
    }

    /** Collects one expression's input fields, visited once per node so the graph stays linear. */
    private static void readInto(
        @NotNull Set<String> out, @NotNull PoseExpr expr, @NotNull Set<Object> visited) {

        if (!visited.add(expr)) return;
        switch (expr) {
            case PoseExpr.Input input -> out.add(input.field());
            case PoseExpr.Op op -> op.operands().forEach(operand -> readInto(out, operand, visited));
            case PoseExpr.Select select -> {
                readInto(out, select.condition(), visited);
                readInto(out, select.whenTrue(), visited);
                readInto(out, select.whenFalse(), visited);
            }
            default -> { }
        }
    }

    /** Collects one condition's input fields, by the rule {@link #readInto} states. */
    private static void readInto(
        @NotNull Set<String> out, @NotNull PosePredicate predicate, @NotNull Set<Object> visited) {

        if (!visited.add(predicate)) return;
        readInto(out, predicate.left(), visited);
        readInto(out, predicate.right(), visited);
    }

    // ------------------------------------------------------------------------------------
    // source measurement
    // ------------------------------------------------------------------------------------

    /**
     * One measured contribution - a mechanism, and the gate of the mesh or pass that contributed it.
     *
     * @param source the source kind
     * @param gate the gate admitting what contributed it
     */
    private record Entry(@NotNull StyleClock source, @NotNull Gate gate) {}

    /**
     * One row's measured {@code sources} member - each contributing mesh's kinds gathered under
     * its gate, then each kind spelled once: a bare token where any ungated mesh contributes it,
     * the gated object spelling otherwise, and a refusal where only a rests-false flag gate does.
     * A kind no ungated mesh contributes stops the flow where a tinted pass or a filled slot's body
     * contributes it, no gate token admitting either.
     * A pass whose opacity follows the clock contributes, under its gate, the kind of each drive its
     * opacity reads that travels within the period - the pulse's analogue of a scroll.
     *
     * @param entity the subject being measured, for the refusal
     * @param row the derived row
     * @param sites the entity's meshes
     * @param scrolls the entity's scrolling passes
     * @param opacities the entity's passes whose opacity follows the clock
     * @param poses every pose row the pose table carries
     * @param clips every parsed keyframe clip by coordinate
     * @param periodTicks the ticks one whole excursion spans
     * @param grounded the grounded programs already built, by pose key
     * @param measured the measurements already taken, by pose key and binding
     * @param diagnostics the scope a refusal is recorded against
     * @return the source entries in kind order, empty for a row nothing moves
     * @throws ToolingException if a kind no ungated mesh contributes is contributed by a tinted pass
     *     or by a filled slot's body
     */
    private static @NotNull List<JsonTree> sourcesOf(
        @NotNull String entity, @NotNull Row row, @NotNull List<Site> sites,
        @NotNull List<Scroll> scrolls, @NotNull List<Opacity> opacities, @NotNull Map<String, PoseOutcome> poses,
        @NotNull Map<String, KeyframeClip> clips, int periodTicks,
        @NotNull Map<String, PoseProgram> grounded, @NotNull Map<String, Set<StyleClock>> measured,
        @NotNull Diagnostics diagnostics) {

        List<Entry> entries = new ArrayList<>();
        for (Site site : sites) {
            if (skips(row, site.baby())) continue;
            for (StyleClock kind : kindsOf(site.poseKey(), row.composed(), poses, clips, periodTicks,
                grounded, measured))
                entries.add(new Entry(kind, site.gate()));
        }
        for (Scroll scroll : scrolls) {
            if (row.age().filter(Age.BABY::equals).isPresent() && !scroll.babyDrawn()) continue;
            entries.add(new Entry(StyleClock.SCROLL, scroll.gate()));
        }
        for (Opacity opacity : opacities) {
            if (row.age().filter(Age.BABY::equals).isPresent() && !opacity.babyDrawn()) continue;
            if (row.age().filter(Age.ADULT::equals).isPresent() && !opacity.adultDrawn()) continue;
            for (String input : opacity.inputs()) {
                Drive drive = row.composed().get(input);
                if (drive != null && drive.travels(periodTicks)) entries.add(new Entry(drive.kind(), opacity.gate()));
            }
        }

        List<JsonTree> out = new ArrayList<>();
        for (StyleClock kind : SOURCE_ORDER) {
            List<Entry> held = entries.stream()
                .filter(entry -> entry.source() == kind)
                .toList();
            if (held.isEmpty()) continue;
            if (held.stream().anyMatch(entry -> entry.gate().open())) {
                out.add(JsonTree.of(kind.token()));
                continue;
            }
            if (held.stream().anyMatch(entry -> entry.gate() instanceof Gate.Tinted))
                throw new ToolingException("%s style '%s' moves by '%s' on a pass drawn only while a tint is selected, "
                    + "which no flag spells and no source gate can carry", entity, row.id(), kind.token());
            for (Entry entry : held)
                if (entry.gate() instanceof Gate.Filled(String slot))
                    throw new ToolingException("%s style '%s' moves by '%s' on the body its '%s' slot swaps in while filled, "
                        + "which no flag spells and no source gate can carry", entity, row.id(), kind.token(), slot);
            Set<String> gates = held.stream()
                .map(Entry::gate)
                .filter(gate -> !gate.negative())
                .map(gate -> token(((Gate.OnFlag) gate).flag()))
                .collect(Collectors.toCollection(TreeSet::new));
            if (gates.isEmpty()) {
                diagnostics.error(
                    "%s style '%s' moves by '%s' only where flag [%s] rests false, which the source spelling cannot carry",
                    entity, row.id(), kind.token(), held.stream()
                        .map(entry -> token(((Gate.OnFlag) entry.gate()).flag()))
                        .distinct()
                        .collect(Collectors.joining(", ")));
                continue;
            }
            for (String gate : gates) out.add(JsonTree.object().put("source", kind.token()).put("gate", gate));
        }
        return out;
    }

    /**
     * Whether an aged row passes over one mesh - a baby row reads the baby meshes alone, an adult
     * row everything but them, an ageless row every mesh.
     */
    private static boolean skips(@NotNull Row row, boolean babyMesh) {
        return row.age().map(age -> (age == Age.BABY) != babyMesh).orElse(false);
    }

    /**
     * One measured source list spelled to comparable text, which is how two age sides are told
     * apart - by what each entry carries, never by node identity.
     */
    private static @NotNull String spelled(@NotNull List<JsonTree> sources) {
        return sources.stream()
            .map(source -> source.isPrimitive()
                ? source.asString().orElseThrow()
                : source.findString("source").orElseThrow()
                    + "@" + source.findString("gate").orElseThrow())
            .collect(Collectors.joining(","));
    }

    /**
     * Whether one row's evaluated output differs anywhere in a period from its base's - the base
     * of every composed row is the standing row, and the standing row's own base is bind, the
     * un-driven evaluation. Channel values are compared tick by tick by the measurement's own
     * value equality - {@link #same}, both zero signs held together - and the play sites as what
     * each binding plays: which sites it satisfies, which clips they run, and how their arguments
     * travel. A held selection - a constant clip posing bones at its first instant - is distinct
     * while nothing about it travels, which is what a sources-empty row's emission rides on. A
     * mesh only a rests-false flag gate admits is passed over, the same terms {@link #sourcesOf}
     * refuses it on.
     *
     * @param row the derived row
     * @param base the whole composed driver map of the row's base
     * @param sites the entity's meshes
     * @param poses every pose row the pose table carries
     * @param clips every parsed keyframe clip by coordinate
     * @param periodTicks the ticks one whole excursion spans
     * @param grounded the grounded programs already built, by pose key
     * @return whether any admitted mesh renders the row apart from its base
     */
    private static boolean distinct(
        @NotNull Row row, @NotNull Map<String, Drive> base, @NotNull List<Site> sites,
        @NotNull Map<String, PoseOutcome> poses, @NotNull Map<String, KeyframeClip> clips,
        int periodTicks, @NotNull Map<String, PoseProgram> grounded) {

        for (Site site : sites) {
            if (skips(row, site.baby()) || site.gate().negative()) continue;
            if (!(poses.get(site.poseKey()) instanceof PoseOutcome.Extracted extracted)) continue;
            PoseProgram program = grounded.computeIfAbsent(site.poseKey(), key -> ground(extracted.program()));
            Evaluated own = evaluate(program, row.composed(), Set.of(), periodTicks);
            Evaluated against = evaluate(program, base, Set.of(), periodTicks);
            if (!sameSeries(own.channels(), against.channels())) return true;
            if (!samePlays(played(own, row.composed(), clips, periodTicks),
                played(against, base, clips, periodTicks))) return true;
        }
        return false;
    }

    /** Whether two evaluations write the same channels with equal values at every tick. */
    private static boolean sameSeries(
        @NotNull Map<String, double[]> own, @NotNull Map<String, double[]> against) {

        if (!own.keySet().equals(against.keySet())) return false;
        for (Map.Entry<String, double[]> channel : own.entrySet()) {
            double[] held = against.get(channel.getKey());
            for (int tick = 0; tick < channel.getValue().length; tick++)
                if (!same(channel.getValue()[tick], held[tick])) return false;
        }
        return true;
    }

    /**
     * The sites one binding plays - each site it plays whose clip writes any channel, in program
     * order. A state-gated site plays where the binding holds its gate field non-zero; a static
     * and a walk-driven site both play unconditionally, vanilla applying each with no gate of its
     * own, so what tells two bindings apart on a walk-driven site is its argument travel.
     */
    private static @NotNull List<SiteSeries> played(
        @NotNull Evaluated evaluated, @NotNull Map<String, Drive> binding,
        @NotNull Map<String, KeyframeClip> clips, int periodTicks) {

        List<SiteSeries> out = new ArrayList<>();
        for (SiteSeries site : evaluated.sites()) {
            boolean plays = switch (site.drive()) {
                case NONE, STRIDE -> true;
                case SELECT -> satisfied(binding.get(site.state()), periodTicks);
            };
            if (!plays) continue;
            KeyframeClip clip = clips.get(site.clip());
            if (clip == null
                || clip.channels().stream().allMatch(channel -> channel.keyframes().isEmpty())) continue;
            out.add(site);
        }
        return out;
    }

    /** Whether two bindings play the same clips with equal argument travel. */
    private static boolean samePlays(
        @NotNull List<SiteSeries> own, @NotNull List<SiteSeries> against) {

        if (own.size() != against.size()) return false;
        for (int index = 0; index < own.size(); index++) {
            SiteSeries left = own.get(index);
            SiteSeries right = against.get(index);
            if (!left.clip().equals(right.clip()) || left.args().size() != right.args().size())
                return false;
            for (int arg = 0; arg < left.args().size(); arg++) {
                double[] here = left.args().get(arg);
                double[] there = right.args().get(arg);
                for (int tick = 0; tick < here.length; tick++)
                    if (!same(here[tick], there[tick])) return false;
            }
        }
        return true;
    }

    /**
     * The source kinds one pose row moves through under one binding, measured across one period.
     *
     * <p>Channel variation is attributed by driver subsets: a kind is written down only where
     * dropping its drivers changes the SHAPE of some varying channel's travel - its per-tick
     * deviation from its own first frame - so a drive that merely offsets a still channel names no
     * mechanism, and two kinds jointly carrying one channel are both named. A state-gated play
     * site contributes {@code select} where the binding satisfies it, its clip moves at all, and
     * its time axis travels. A walk-driven site plays unconditionally, the way vanilla applies it,
     * and contributes wherever its resolved drive - the clip clock and the capped amplitude -
     * travels across the period, attributed by the same subsets: every kind whose drop changes
     * that drive is named, which files the nautilus's rest-swim under the tick that advances it.
     *
     * @param poseKey the pose row being measured
     * @param binding the row's whole composed driver map
     * @param poses every pose row the pose table carries
     * @param clips every parsed keyframe clip by coordinate
     * @param periodTicks the ticks one whole excursion spans
     * @param grounded the grounded programs already built, by pose key
     * @param measured the measurements already taken, by pose key and binding
     * @return the kinds, empty for a row this binding never moves
     * @throws ToolingException if a channel varies and no driver kind accounts for it
     */
    private static @NotNull Set<StyleClock> kindsOf(
        @NotNull String poseKey, @NotNull LinkedHashMap<String, Drive> binding,
        @NotNull Map<String, PoseOutcome> poses, @NotNull Map<String, KeyframeClip> clips,
        int periodTicks, @NotNull Map<String, PoseProgram> grounded,
        @NotNull Map<String, Set<StyleClock>> measured) {

        if (!(poses.get(poseKey) instanceof PoseOutcome.Extracted extracted)) return Set.of();
        String memoKey = poseKey + '|' + bindingKey(binding);
        Set<StyleClock> held = measured.get(memoKey);
        if (held != null) return held;

        PoseProgram program = grounded.computeIfAbsent(poseKey, key -> ground(extracted.program()));
        Evaluated full = evaluate(program, binding, Set.of(), periodTicks);
        Set<String> varying = full.varying();

        Map<StyleClock, List<String>> byKind = new LinkedHashMap<>();
        for (Drive drive : binding.values())
            byKind.computeIfAbsent(drive.kind(), kind -> new ArrayList<>()).add(drive.field());
        Map<StyleClock, Evaluated> droppedByKind = new LinkedHashMap<>();

        Set<StyleClock> kinds = new LinkedHashSet<>();
        if (!varying.isEmpty()) {
            for (StyleClock kind : byKind.keySet()) {
                Evaluated dropped = droppedByKind.computeIfAbsent(kind,
                    dropping -> evaluate(program, binding, Set.copyOf(byKind.get(dropping)), periodTicks));
                for (String channel : varying)
                    if (!sameShape(full.channels().get(channel), dropped.channels().get(channel))) {
                        kinds.add(kind);
                        break;
                    }
            }
            if (kinds.isEmpty())
                throw new ToolingException("'%s' varies under [%s] and no driver kind accounts for it",
                    poseKey, String.join(", ", binding.keySet()));
        }

        for (int index = 0; index < full.sites().size(); index++) {
            SiteSeries site = full.sites().get(index);
            if (site.drive() == ClipDrive.NONE) continue;
            if (site.drive() == ClipDrive.SELECT) {
                if (!satisfied(binding.get(site.state()), periodTicks)) continue;
                if (moves(clipOf(clips, poseKey, site)) && site.argsVary()) kinds.add(StyleClock.SELECT);
                continue;
            }
            if (!moves(clipOf(clips, poseKey, site)) || !strideTravels(site, poseKey)) continue;
            boolean attributed = false;
            for (StyleClock kind : byKind.keySet()) {
                Evaluated dropped = droppedByKind.computeIfAbsent(kind,
                    dropping -> evaluate(program, binding, Set.copyOf(byKind.get(dropping)), periodTicks));
                if (sameStrideDrive(site, dropped.sites().get(index))) continue;
                kinds.add(kind);
                attributed = true;
            }
            if (!attributed)
                throw new ToolingException("'%s' plays '%s' with travel no driver kind accounts for",
                    poseKey, site.clip());
        }

        Set<StyleClock> sealed = Collections.unmodifiableSet(kinds);
        measured.put(memoKey, sealed);
        return sealed;
    }

    /**
     * The clip a play site names, which the table must carry.
     *
     * @throws ToolingException if the clip table has no entry for it
     */
    private static @NotNull KeyframeClip clipOf(
        @NotNull Map<String, KeyframeClip> clips, @NotNull String poseKey,
        @NotNull SiteSeries site) {

        KeyframeClip clip = clips.get(site.clip());
        if (clip == null)
            throw new ToolingException("'%s' plays '%s', which the clip table does not carry",
                poseKey, site.clip());
        return clip;
    }

    /**
     * The clip clock a walk-driven site resolves at one tick, through the runtime's own
     * arithmetic - the position term scaled to milliseconds by the rate term and truncated.
     */
    private static long strideMillis(@NotNull SiteSeries site, int tick) {
        return (long) ((float) site.args().get(0)[tick] * 50f * (float) site.args().get(2)[tick]);
    }

    /** The amplitude a walk-driven site resolves at one tick, capped at one as the runtime caps it. */
    private static float strideAmplitude(@NotNull SiteSeries site, int tick) {
        return Math.min((float) site.args().get(1)[tick] * (float) site.args().get(3)[tick], 1f);
    }

    /**
     * Whether a walk-driven site's resolved drive travels across the period - its clock or its
     * capped amplitude moving at all, with the amplitude reaching past zero somewhere. A drive
     * whose amplitude rests at zero throughout displaces nothing however its clock runs, and one
     * whose pair never moves holds the clip at one instant.
     *
     * @throws ToolingException if the site does not carry the four walk terms
     */
    private static boolean strideTravels(@NotNull SiteSeries site, @NotNull String poseKey) {
        if (site.args().size() != 4)
            throw new ToolingException("'%s' plays '%s' walk-driven on %d term(s), which takes 4",
                poseKey, site.clip(), site.args().size());
        long millis = strideMillis(site, 0);
        float amplitude = strideAmplitude(site, 0);
        boolean travels = false;
        boolean audible = amplitude != 0f;
        for (int tick = 1; tick < site.args().getFirst().length; tick++) {
            if (strideMillis(site, tick) != millis || strideAmplitude(site, tick) != amplitude)
                travels = true;
            if (strideAmplitude(site, tick) != 0f) audible = true;
        }
        return travels && audible;
    }

    /** Whether two evaluations resolve one walk-driven site to the same drive at every tick. */
    private static boolean sameStrideDrive(@NotNull SiteSeries own, @NotNull SiteSeries against) {
        for (int tick = 0; tick < own.args().getFirst().length; tick++)
            if (strideMillis(own, tick) != strideMillis(against, tick)
                || strideAmplitude(own, tick) != strideAmplitude(against, tick)) return false;
        return true;
    }

    /** Whether a drive is present and answers non-zero somewhere in one period. */
    private static boolean satisfied(Drive drive, int periodTicks) {
        return drive != null && drive.answers(periodTicks);
    }

    /** Whether any channel of a clip holds more than one distinct keyframe value. */
    private static boolean moves(@NotNull KeyframeClip clip) {
        return clip.channels().stream().anyMatch(channel -> channel.keyframes().stream()
            .map(AnimationValue.Frame::vector)
            .distinct()
            .count() > 1);
    }

    /**
     * One measurement's memo key half - the binding as spelled values, order-independent: each
     * driver's field, its wave with everything that wave reads, its rest and extent bits and its
     * group.
     */
    private static @NotNull String bindingKey(@NotNull Map<String, Drive> binding) {
        return binding.values().stream()
            .map(Drive::driver)
            .map(driver -> driver.field() + ':' + driver.wave() + ':'
                + Float.floatToIntBits(driver.rest()) + ':' + Float.floatToIntBits(driver.extent()) + ':'
                + driver.group().orElse(""))
            .sorted()
            .collect(Collectors.joining(","));
    }

    /**
     * One pose row evaluated across one period under one binding.
     *
     * @param channels each written channel's per-tick values, keyed by its owner and token
     * @param sites each surviving play site with its arguments' per-tick values
     */
    private record Evaluated(
        @NotNull LinkedHashMap<String, double[]> channels,
        @NotNull List<SiteSeries> sites
    ) {

        /** The channels whose value moves at all across the period. */
        @NotNull Set<String> varying() {
            Set<String> out = new LinkedHashSet<>();
            this.channels.forEach((key, series) -> {
                for (double value : series)
                    if (!same(value, series[0])) {
                        out.add(key);
                        return;
                    }
            });
            return out;
        }

    }

    /**
     * One play site's evaluation across a period.
     *
     * @param drive what decides whether the clip contributes
     * @param state the render-state field the gate reads, empty where the drive is not a state
     * @param clip the clip coordinate
     * @param args each argument's per-tick values
     */
    private record SiteSeries(
        @NotNull ClipDrive drive,
        @NotNull String state,
        @NotNull String clip,
        @NotNull List<double[]> args
    ) {

        /** Whether any argument's value moves across the period - the clip's own time axis. */
        boolean argsVary() {
            for (double[] series : this.args)
                for (double value : series)
                    if (!same(value, series[0])) return true;
            return false;
        }

    }

    /**
     * Evaluates one grounded program at every tick of a period, the binding's drives answering
     * their fields and every other input answering zero, through the same fold the table was
     * generated with - so a value measured here is the value the runtime computes.
     *
     * @param program the grounded program
     * @param binding the row's whole composed driver map
     * @param dropped the fields left unanswered, for subset attribution
     * @param periodTicks the ticks one whole excursion spans
     * @return the per-tick values
     * @throws ToolingException if a channel or argument does not settle under a full binding, or a
     *     site's reach depends on the tick
     */
    private static @NotNull Evaluated evaluate(
        @NotNull PoseProgram program, @NotNull Map<String, Drive> binding,
        @NotNull Set<String> dropped, int periodTicks) {

        LinkedHashMap<String, double[]> channels = new LinkedHashMap<>();
        List<SiteSeries> sites = null;
        for (int tick = 0; tick < periodTicks; tick++) {
            Map<String, Float> values = new LinkedHashMap<>();
            for (Drive drive : binding.values())
                if (!dropped.contains(drive.field()))
                    values.put(drive.field(), drive.at(tick, periodTicks));
            PoseProgram folded = PoseFold.fold(program, Map.of(), Map.of(), Map.of(), values,
                Set.of(), Set.of(), Map.of());

            for (int step = 0; step < folded.container().size(); step++)
                record(channels, "container[" + step + "]", folded.container().get(step),
                    tick, periodTicks, program.model());
            for (Map.Entry<String, Map<PoseChannel, PoseExpr>> bone : folded.bones().entrySet())
                record(channels, bone.getKey(), bone.getValue(), tick, periodTicks, program.model());

            List<PoseClipSite> played = folded.clipSites();
            if (sites == null) {
                sites = new ArrayList<>(played.size());
                for (PoseClipSite play : played) {
                    List<double[]> args = new ArrayList<>(play.arguments().size());
                    for (int arg = 0; arg < play.arguments().size(); arg++)
                        args.add(new double[periodTicks]);
                    sites.add(new SiteSeries(play.drive(), play.state(), play.clip(), args));
                }
            } else if (played.size() != sites.size())
                throw new ToolingException(
                    "'%s' plays a tick-dependent site count, which this measurement cannot attribute",
                    program.model());
            for (int index = 0; index < played.size(); index++) {
                PoseClipSite play = played.get(index);
                for (int arg = 0; arg < play.arguments().size(); arg++)
                    sites.get(index).args().get(arg)[tick] =
                        settled(play.arguments().get(arg), program.model(), "a clip argument", tick);
            }
        }
        return new Evaluated(channels, sites == null ? List.of() : sites);
    }

    /** Records one channel map's settled values at one tick, flags passed over. */
    private static void record(
        @NotNull Map<String, double[]> channels, @NotNull String owner,
        @NotNull Map<PoseChannel, PoseExpr> written, int tick, int periodTicks,
        @NotNull String model) {

        for (Map.Entry<PoseChannel, PoseExpr> channel : written.entrySet()) {
            String key = owner + '.' + channel.getKey().token();
            channels.computeIfAbsent(key, name -> new double[periodTicks])[tick] =
                settled(channel.getValue(), model, key, tick);
        }
    }

    /** One folded expression's literal value, or the refusal a full binding makes unreachable. */
    private static double settled(
        @NotNull PoseExpr expr, @NotNull String model, @NotNull String what, int tick) {

        return expr.constantValue().orElseThrow(() -> new ToolingException(
            "'%s' leaves %s unsettled at tick %d under a full binding", model, what, tick));
    }

    /** Whether two series travel the same way - each tick's deviation from its own first frame. */
    private static boolean sameShape(double @NotNull [] full, double @NotNull [] dropped) {
        for (int tick = 0; tick < full.length; tick++)
            if (!same(full[tick] - full[0], dropped[tick] - dropped[0])) return false;
        return true;
    }

    /** Value equality that holds two NaNs together and both zero signs together. */
    private static boolean same(double left, double right) {
        return left == right || (Double.isNaN(left) && Double.isNaN(right));
    }

    /**
     * One program with every bone read bound to zero - a bone read is tick-constant either way, so
     * variation detection is unaffected, and binding it is what lets every channel settle to a
     * literal per tick. Memoized by identity, so the graph stays a graph.
     */
    private static @NotNull PoseProgram ground(@NotNull PoseProgram program) {
        Map<Object, Object> memo = new IdentityHashMap<>();
        List<Map<PoseChannel, PoseExpr>> container = new ArrayList<>(program.container().size());
        for (Map<PoseChannel, PoseExpr> step : program.container())
            container.add(groundChannels(step, memo));
        Map<String, Map<PoseChannel, PoseExpr>> bones = new LinkedHashMap<>();
        program.bones().forEach((bone, channels) -> bones.put(bone, groundChannels(channels, memo)));
        List<PoseClipSite> sites = new ArrayList<>(program.clipSites().size());
        for (PoseClipSite site : program.clipSites())
            sites.add(new PoseClipSite(site.clip(), site.drive(), site.state(),
                site.arguments().stream()
                    .map(argument -> ground(argument, memo))
                    .collect(Collectors.toUnmodifiableList()),
                ground(site.condition(), memo)));
        return new PoseProgram(program.model(), List.copyOf(container),
            Collections.unmodifiableMap(bones), program.flags(), List.copyOf(sites));
    }

    /** One channel map grounded, in its own order. */
    private static @NotNull Map<PoseChannel, PoseExpr> groundChannels(
        @NotNull Map<PoseChannel, PoseExpr> written, @NotNull Map<Object, Object> memo) {

        LinkedHashMap<PoseChannel, PoseExpr> out = new LinkedHashMap<>();
        written.forEach((channel, expr) -> out.put(channel, ground(expr, memo)));
        return out;
    }

    /** One expression grounded - a bone read becomes zero, everything else is rebuilt around it. */
    private static @NotNull PoseExpr ground(@NotNull PoseExpr expr, @NotNull Map<Object, Object> memo) {
        PoseExpr known = (PoseExpr) memo.get(expr);
        if (known != null) return known;
        PoseExpr out = switch (expr) {
            case PoseExpr.BoneRead ignored -> new PoseExpr.Constant(0f);
            case PoseExpr.Op op -> PoseExpr.operation(op.operator(), op.operands().stream()
                .map(operand -> ground(operand, memo))
                .collect(Collectors.toUnmodifiableList()));
            case PoseExpr.Select select -> new PoseExpr.Select(ground(select.condition(), memo),
                ground(select.whenTrue(), memo), ground(select.whenFalse(), memo));
            default -> expr;
        };
        memo.put(expr, out);
        return out;
    }

    /** One condition grounded, by the rule {@link #ground(PoseExpr, Map)} states. */
    private static @NotNull PosePredicate ground(
        @NotNull PosePredicate predicate, @NotNull Map<Object, Object> memo) {

        PosePredicate known = (PosePredicate) memo.get(predicate);
        if (known != null) return known;
        PosePredicate out = new PosePredicate(predicate.comparison(),
            ground(predicate.left(), memo), ground(predicate.right(), memo));
        memo.put(predicate, out);
        return out;
    }

    // ------------------------------------------------------------------------------------
    // spelling
    // ------------------------------------------------------------------------------------

    /**
     * One emitted row - id first, then its base where the standing row is itself emitted, its age,
     * its measured sources, its entailed toggles, and its drives - the row's own where a base carries
     * the rest, the whole composed map where none does.
     */
    private static @NotNull JsonTree rowNode(
        @NotNull Row row, @NotNull List<JsonTree> sources, boolean baseEmitted) {

        JsonTree node = JsonTree.object().put("id", row.id());
        boolean based = baseEmitted && !PoseStyle.IDLE.equals(row.id());
        if (based) node.put("base", PoseStyle.IDLE);
        row.age().ifPresent(age -> node.put("age", token(age)));
        node.put("sources", JsonTree.array().addAll(sources));
        if (!row.toggles().isEmpty())
            node.putStrings("toggles", row.toggles().toArray(String[]::new));
        JsonTree drives = node.childArray("drives");
        for (Drive drive : (based ? row.own() : row.composed()).values()) drives.add(drive.node());
        return node;
    }

    /**
     * A model's {@code ceilings} member - the fastest walk each age's paced stride reaches, keyed by
     * the age's token in age order.
     */
    private static @NotNull JsonTree ceilingsNode(@NotNull Map<Age, WalkPace> ceilings) {
        JsonTree node = JsonTree.object();
        ceilings.forEach((age, ceiling) -> node.put(token(age),
            JsonTree.object().put("speed", ceiling.speed()).put("rate", ceiling.rate())));
        return node;
    }

}
