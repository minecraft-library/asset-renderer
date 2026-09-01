package lib.minecraft.renderer.tooling.animation;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lib.minecraft.renderer.tooling.kernel.ToolingException;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Emits each entity's style catalog - the {@code styles} member of the model table, one row per
 * uniquely identifiable output.
 *
 * <p>A row's drivers are the join of the curated roster with what the entity's poses actually
 * read: the standing row ramps elapsed age and adds every figure and resting selection its forms
 * consult, the walking row composes the stride pair over it, and every non-default selectable
 * member earns a row of its own. The {@code bind} row is never emitted - every catalog synthesizes
 * it. Row ids are unique per entity, except that two rows may share one id where disjoint
 * {@code age} members split them - the axolotl's adult factor and baby clip both spell
 * {@code play_dead}.
 *
 * <p><b>A row's {@code sources} member is measured, never asserted.</b> Each composed row is
 * evaluated across one period per contributing mesh - the body, the baby fork, every coat, size
 * and shape option, every overlay pass and its suppressed alternate - and a mechanism is written
 * down only where dropping its drivers changes the shape of some channel's travel, where a play
 * site the row plays runs a clip that moves, or where a pass scrolls its sheet. A source only a
 * gated pass contributes carries that pass's gate token. A row ships where a mechanism moves it or
 * where its held output differs from its base's at some tick of the period - a held selection
 * ships {@code sources: []}, a distinct pose nothing travels - so a subject whose every row
 * renders as its base ships no {@code styles} member and stays bind-only.
 *
 * <p>A state-gated play site plays where the binding holds its gate field non-zero; a walk-driven
 * one runs unconditionally, as vanilla applies it, and moves a row wherever its resolved clock or
 * capped amplitude travels under that row's binding - the nautilus feeds its clock elapsed age and
 * floors its amplitude, so its swim moves the standing row with nothing walking. The two universal
 * rows are measured per age side where the subject draws a baby mesh, and a subject whose sides
 * measure apart is emitted an age-split pair - a side with no distinct output shipping no row,
 * which is how a baby whose clips all rest stays bind-only while its adult moves.
 *
 * <p>Drive spelling is deterministic: {@code field}, {@code wave} and {@code extent} are always
 * spelled, {@code rest} only on a sweep or cycle drive, {@code group} when the field has one, and
 * {@code base} is omitted where the standing row itself is not emitted - a baseless row spells its
 * whole composed driver map.
 */
@UtilityClass
public final class StyleFlow {

    /** The universal row ids, which every emitted row either is or composes over. */
    private static final @NotNull String IDLE = "idle";

    private static final @NotNull String STRIDE = "stride";

    /** The three universally driven fields - elapsed age, and the stride pair. */
    private static final @NotNull String AGE_IN_TICKS = "ageInTicks";

    private static final @NotNull String WALK_SPEED = "walkAnimationSpeed";

    private static final @NotNull String WALK_POS = "walkAnimationPos";

    /** The wave tokens a drive travels by, as the shipped table spells them. */
    private static final @NotNull String WAVE_HOLD = "hold";

    private static final @NotNull String WAVE_RAMP = "ramp";

    private static final @NotNull String WAVE_SWEEP = "sweep";

    private static final @NotNull String WAVE_CYCLE = "cycle";

    /** The source tokens in emission order; {@code none} is a clip drive and never a row source. */
    private static final @NotNull List<String> SOURCE_ORDER =
        List.of("tick", "figure", "select", "stride", "scroll");

    // ------------------------------------------------------------------------------------
    // emit
    // ------------------------------------------------------------------------------------

    /**
     * Derives, measures and writes each entity's {@code styles} member into the model table.
     *
     * <p>Two census lines close the pass, telling the two still-nesses apart: a select site whose
     * field no roster member answers is a figure to add, where a subject no row moves is simply
     * still - flattening the first into the second is what the lines exist to prevent.
     *
     * @param diagnostics the scope the pass is recorded against
     * @param models the model table, rewritten in place ahead of being written
     * @param poses every pose row the pose table carries
     * @param clips every parsed keyframe clip by coordinate
     * @param periodTicks the ticks one whole excursion spans
     */
    public static void emit(
        @NotNull Diagnostics diagnostics, @NotNull JsonTree models,
        @NotNull Map<String, PoseOutcome> poses, @NotNull Map<String, KeyframeClip> clips,
        int periodTicks) {

        Map<String, Set<String>> reads = new LinkedHashMap<>();
        Map<String, PoseProgram> grounded = new LinkedHashMap<>();
        Map<String, Set<String>> measured = new LinkedHashMap<>();
        Set<String> unanswered = new TreeSet<>();
        Set<String> still = new TreeSet<>();
        int[] subjects = {0};

        models.members().forEach((entity, row) -> {
            subjects[0]++;
            List<Site> sites = meshSites(row);
            List<Scroll> scrolls = scrollSites(row);
            Set<String> read = new LinkedHashSet<>();
            Set<String> babyRead = new LinkedHashSet<>();
            Set<String> adultRead = new LinkedHashSet<>();
            for (Site site : sites) {
                Set<String> held = readsOf(site.poseKey(), poses, reads);
                read.addAll(held);
                (site.baby() ? babyRead : adultRead).addAll(held);
            }
            recordUnanswered(entity, sites, poses, unanswered);

            boolean hasBaby = sites.stream().anyMatch(Site::baby);
            List<Row> rows = deriveRows(entity, read, babyRead, adultRead, hasBaby, diagnostics);
            Row idle = rows.getFirst();
            List<JsonTree> written = new ArrayList<>();
            boolean idleEmitted = false;
            for (Row held : rows) {
                if (held.select() && held.composed().equals(idle.composed())) {
                    diagnostics.info("%s style '%s' composes to its base's drivers and is not emitted",
                        entity, held.id());
                    continue;
                }
                Map<String, Drive> base = IDLE.equals(held.id()) ? Map.of() : idle.composed();
                if (!held.select() && hasBaby) {
                    Row adult = aged(held, "adult");
                    Row baby = aged(held, "baby");
                    List<JsonTree> adultSources = sourcesOf(entity, adult, sites, scrolls, poses,
                        clips, periodTicks, grounded, measured, diagnostics);
                    List<JsonTree> babySources = sourcesOf(entity, baby, sites, scrolls, poses,
                        clips, periodTicks, grounded, measured, diagnostics);
                    boolean adultShips = !adultSources.isEmpty()
                        || distinct(adult, base, sites, poses, clips, periodTicks, grounded);
                    boolean babyShips = !babySources.isEmpty()
                        || distinct(baby, base, sites, poses, clips, periodTicks, grounded);
                    if (adultShips != babyShips
                        || !spelled(adultSources).equals(spelled(babySources))) {
                        diagnostics.info("%s style '%s' measures apart by age and is emitted age-split",
                            entity, held.id());
                        if (adultShips) {
                            written.add(rowNode(adult, adultSources, idleEmitted));
                            idleEmitted |= IDLE.equals(held.id());
                        }
                        if (babyShips) {
                            written.add(rowNode(baby, babySources, idleEmitted));
                            idleEmitted |= IDLE.equals(held.id());
                        }
                        continue;
                    }
                }
                List<JsonTree> sources = sourcesOf(entity, held, sites, scrolls, poses, clips,
                    periodTicks, grounded, measured, diagnostics);
                if (sources.isEmpty()
                    && !distinct(held, base, sites, poses, clips, periodTicks, grounded)) continue;
                written.add(rowNode(held, sources, idleEmitted));
                if (IDLE.equals(held.id())) idleEmitted = true;
            }
            if (written.isEmpty()) still.add(entity);
            else row.put("styles", JsonTree.array().addAll(written));
        });

        diagnostics.info("%d select site(s) read a figure nothing answers: %s",
            unanswered.size(), unanswered);
        diagnostics.info("%d of %d subject(s) ship no style row - nothing shipped moves them: %s",
            still.size(), subjects[0], still);
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
                    if (play.drive() == PoseClipSite.Gate.SELECT
                        && !StyleRoster.driven().contains(play.state()))
                        unanswered.add(entity + " plays " + play.clip() + " behind '" + play.state() + "'");
    }

    // ------------------------------------------------------------------------------------
    // row derivation
    // ------------------------------------------------------------------------------------

    /**
     * One render-state field a style drives, and how its value travels across a period.
     *
     * @param field the render-state field name
     * @param wave the wave token
     * @param rest what the field holds at the ends of a swept travel
     * @param extent the far end of the travel - the whole of a held one-hot, the per-tick slope of
     *     a ramp
     * @param group the exclusion group token, empty for an ungrouped field
     */
    private record Drive(
        @NotNull String field,
        @NotNull String wave,
        float rest,
        float extent,
        @NotNull Optional<String> group
    ) {

        /**
         * What this drive answers at one tick - the excursion arithmetic the reference set is
         * rendered against, every narrowing a {@code float}.
         *
         * @param tick the tick being posed
         * @param periodTicks the ticks one whole excursion spans
         * @return the field's value at that tick
         */
        float at(int tick, int periodTicks) {
            return switch (this.wave) {
                case WAVE_HOLD -> this.extent;
                case WAVE_RAMP -> tick * this.extent;
                case WAVE_SWEEP, WAVE_CYCLE -> {
                    float phase = Math.floorMod(tick, periodTicks) / (float) periodTicks;
                    float travel = WAVE_CYCLE.equals(this.wave) ? phase : 1f - Math.abs(2f * phase - 1f);
                    yield this.rest + (this.extent - this.rest) * travel;
                }
                default -> throw new ToolingException(
                    "'%s' travels by '%s', which is not a wave", this.field, this.wave);
            };
        }

        /** Whether this drive answers non-zero at any tick of one period. */
        boolean answers(int periodTicks) {
            for (int tick = 0; tick < periodTicks; tick++)
                if (at(tick, periodTicks) != 0f) return true;
            return false;
        }

        /** The source kind this drive belongs to, which is what a caused variation is filed under. */
        @NotNull String kind() {
            if (AGE_IN_TICKS.equals(this.field)) return "tick";
            if (WALK_SPEED.equals(this.field) || WALK_POS.equals(this.field)) return "stride";
            if (WAVE_SWEEP.equals(this.wave) || WAVE_CYCLE.equals(this.wave)) return "figure";
            return "select";
        }

        /** This drive spelled as the table carries it. */
        @NotNull JsonTree node() {
            JsonTree node = JsonTree.object().put("field", this.field).put("wave", this.wave);
            if (WAVE_SWEEP.equals(this.wave) || WAVE_CYCLE.equals(this.wave)) node.put("rest", this.rest);
            node.put("extent", this.extent);
            this.group.ifPresent(held -> node.put("group", held));
            return node;
        }

    }

    /**
     * One derived style row ahead of its measurement.
     *
     * @param id the style id
     * @param select whether this is a selection row rather than a universal one
     * @param age the age token the row applies to, empty for both
     * @param toggles the bone toggles the selection entails
     * @param own the drives the row declares itself
     * @param composed the whole driver map after base composition and group replacement
     */
    private record Row(
        @NotNull String id,
        boolean select,
        @NotNull Optional<String> age,
        @NotNull List<String> toggles,
        @NotNull LinkedHashMap<String, Drive> own,
        @NotNull LinkedHashMap<String, Drive> composed
    ) {}

    /**
     * The entity's derived rows in emission order - standing, walking, then one row per
     * non-default driven member whose field its poses read, in roster order.
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
        idle.put(AGE_IN_TICKS, new Drive(AGE_IN_TICKS, WAVE_RAMP, 0f, 1f, Optional.empty()));
        for (StyleRoster.Figure figure : StyleRoster.FIGURES)
            if (reads.contains(figure.field()))
                idle.put(figure.field(),
                    new Drive(figure.field(), figure.wave(), figure.rest(), figure.extent(), Optional.empty()));
        for (StyleRoster.Group group : StyleRoster.GROUPS) {
            if (!group.readBy(reads)) continue;
            StyleRoster.Member selected = group.selected(false);
            if (selected.drives())
                idle.put(selected.field(),
                    new Drive(selected.field(), WAVE_HOLD, 0f, 1f, Optional.of(group.token())));
        }

        LinkedHashMap<String, Drive> strideOwn = new LinkedHashMap<>();
        strideOwn.put(WALK_SPEED, new Drive(WALK_SPEED, WAVE_HOLD, 0f, 1f, Optional.empty()));
        strideOwn.put(WALK_POS, new Drive(WALK_POS, WAVE_RAMP, 0f, 1f, Optional.empty()));
        for (StyleRoster.Group group : StyleRoster.GROUPS) {
            if (!group.forked() || !group.readBy(reads)) continue;
            StyleRoster.Member selected = group.selected(true);
            if (selected.drives())
                strideOwn.put(selected.field(),
                    new Drive(selected.field(), WAVE_HOLD, 0f, 1f, Optional.of(group.token())));
        }

        List<Row> out = new ArrayList<>();
        out.add(new Row(IDLE, false, Optional.empty(), List.of(), idle, idle));
        out.add(new Row(STRIDE, false, Optional.empty(), List.of(), strideOwn, compose(idle, strideOwn)));
        for (StyleRoster.Group group : StyleRoster.GROUPS)
            for (StyleRoster.Member member : group.members()) {
                if (!member.drives() || !reads.contains(member.field()) || group.isDefault(member)) continue;
                String age = !hasBaby ? ""
                    : babyReads.contains(member.field()) && !adultReads.contains(member.field()) ? "baby"
                    : adultReads.contains(member.field()) && !babyReads.contains(member.field()) ? "adult"
                    : "";
                if (!age.equals(group.age()))
                    diagnostics.error("%s reads '%s' on %s where the roster declares %s",
                        entity, member.field(), age.isEmpty() ? "either age's forms" : age + " forms alone",
                        group.age().isEmpty() ? "either age" : group.age() + " alone");
                LinkedHashMap<String, Drive> own = new LinkedHashMap<>();
                own.put(member.field(),
                    new Drive(member.field(), WAVE_HOLD, 0f, 1f, Optional.of(group.token())));
                out.add(new Row(StyleRoster.styleId(member.field()), true,
                    age.isEmpty() ? Optional.empty() : Optional.of(age),
                    StyleRoster.togglesOf(member.field()), own, compose(idle, own)));
            }
        return out;
    }

    /**
     * One universal row narrowed to a single age side, everything else its own - the age is what
     * {@link #skips} filters the meshes by, so the side measures over its own forms alone.
     */
    private static @NotNull Row aged(@NotNull Row row, @NotNull String age) {
        return new Row(row.id(), row.select(), Optional.of(age), row.toggles(), row.own(),
            row.composed());
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
     * The overlay gate admitting a pass, resolved to the token the pass's {@code when} member
     * spells - the same precedence the render-time assembler takes, flag first, then charged, then
     * tinted.
     *
     * @param token the gate token, empty for an unconditional pass
     * @param negative whether the gate admits the pass where its flag rests {@code false}, which
     *     the source spelling cannot carry
     */
    private record Gate(@NotNull Optional<String> token, boolean negative) {

        /** The gate of a pass nothing conditions. */
        static final @NotNull Gate OPEN = new Gate(Optional.empty(), false);

        /** Whether this gate admits unconditionally. */
        boolean open() {
            return !this.negative && this.token.isEmpty();
        }

    }

    /**
     * One mesh a subject draws, joined to the pose row that moves it.
     *
     * @param poseKey the pose table key the mesh resolves
     * @param gate the overlay gate admitting the draw, {@link Gate#OPEN} for a form
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
     * Every mesh one entity draws, each with the gate that admits it - the age forms, every coat,
     * size and shape option through their explicit {@code pose} members, and every overlay pass,
     * suppressed alternate and baby delta through the class heading its geometry coordinate. Worn
     * armor and equipment layers are not drawn by a plain render and are not consulted.
     */
    private static @NotNull List<Site> meshSites(@NotNull JsonTree row) {
        LinkedHashMap<String, Site> out = new LinkedHashMap<>();
        row.find("axes").ifPresent(axes -> axes.members().forEach((axis, held) ->
            held.find("options").ifPresent(options -> options.members().forEach((option, chosen) -> {
                boolean baby = "age".equals(axis) && "baby".equals(option);
                chosen.findString("pose").ifPresent(key -> site(out, key, Gate.OPEN, baby));
                chosen.find("overlays").ifPresent(list -> list.elements().toList().forEach(overlay ->
                    overlaySites(out, overlay, baby)));
            }))));
        row.find("overlays").ifPresent(list -> list.elements().toList().forEach(overlay ->
            overlaySites(out, overlay, false)));
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

        String open = poseKey + "|open|" + baby;
        if (out.containsKey(open)) return;
        String keyed = gate.open()
            ? open
            : poseKey + '|' + (gate.negative() ? "!" : "") + gate.token().orElse("") + '|' + baby;
        out.putIfAbsent(keyed, new Site(poseKey, gate, baby));
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

    /** The gate one {@code when} member spells; see {@link Gate}. */
    private static @NotNull Gate gateOf(@NotNull Optional<JsonTree> when) {
        if (when.isEmpty()) return Gate.OPEN;
        JsonTree held = when.get();
        Optional<String> flag = held.findString("flag");
        if (flag.isPresent()) return new Gate(flag, !held.findBoolean("value").orElse(false));
        if (held.findBoolean("charged").orElse(false)) return new Gate(Optional.of("charged"), false);
        if (held.findBoolean("tinted").orElse(false)) return new Gate(Optional.of("tinted"), false);
        return Gate.OPEN;
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
        switch (predicate) {
            case PosePredicate.Compare compare -> {
                readInto(out, compare.left(), visited);
                readInto(out, compare.right(), visited);
            }
            case PosePredicate.Not not -> readInto(out, not.operand(), visited);
            default -> { }
        }
    }

    // ------------------------------------------------------------------------------------
    // source measurement
    // ------------------------------------------------------------------------------------

    /**
     * One measured contribution - a mechanism, and the gate of the pass that contributed it.
     *
     * @param source the source kind token
     * @param gate the contributing pass's gate
     */
    private record Entry(@NotNull String source, @NotNull Gate gate) {}

    /**
     * One row's measured {@code sources} member - each contributing mesh's kinds gathered under
     * its gate, then each kind spelled once: a bare token where any ungated mesh contributes it,
     * the gated object spelling otherwise, and a refusal where only a rests-false flag gate does.
     *
     * @param entity the subject being measured, for the refusal
     * @param row the derived row
     * @param sites the entity's meshes
     * @param scrolls the entity's scrolling passes
     * @param poses every pose row the pose table carries
     * @param clips every parsed keyframe clip by coordinate
     * @param periodTicks the ticks one whole excursion spans
     * @param grounded the grounded programs already built, by pose key
     * @param measured the measurements already taken, by pose key and binding
     * @param diagnostics the scope a refusal is recorded against
     * @return the source entries in kind order, empty for a row nothing moves
     */
    private static @NotNull List<JsonTree> sourcesOf(
        @NotNull String entity, @NotNull Row row, @NotNull List<Site> sites,
        @NotNull List<Scroll> scrolls, @NotNull Map<String, PoseOutcome> poses,
        @NotNull Map<String, KeyframeClip> clips, int periodTicks,
        @NotNull Map<String, PoseProgram> grounded, @NotNull Map<String, Set<String>> measured,
        @NotNull Diagnostics diagnostics) {

        List<Entry> entries = new ArrayList<>();
        for (Site site : sites) {
            if (skips(row, site.baby())) continue;
            for (String kind : kindsOf(site.poseKey(), row.composed(), poses, clips, periodTicks,
                grounded, measured))
                entries.add(new Entry(kind, site.gate()));
        }
        for (Scroll scroll : scrolls) {
            if (row.age().filter("baby"::equals).isPresent() && !scroll.babyDrawn()) continue;
            entries.add(new Entry("scroll", scroll.gate()));
        }

        List<JsonTree> out = new ArrayList<>();
        for (String kind : SOURCE_ORDER) {
            List<Entry> held = entries.stream()
                .filter(entry -> entry.source().equals(kind))
                .toList();
            if (held.isEmpty()) continue;
            if (held.stream().anyMatch(entry -> entry.gate().open())) {
                out.add(JsonTree.of(kind));
                continue;
            }
            Set<String> gates = held.stream()
                .filter(entry -> !entry.gate().negative())
                .map(entry -> entry.gate().token().orElseThrow())
                .collect(Collectors.toCollection(TreeSet::new));
            if (gates.isEmpty()) {
                diagnostics.error(
                    "%s style '%s' moves by '%s' only where flag [%s] rests false, which the source spelling cannot carry",
                    entity, row.id(), kind, held.stream()
                        .map(entry -> entry.gate().token().orElse(""))
                        .distinct()
                        .collect(Collectors.joining(", ")));
                continue;
            }
            for (String gate : gates) out.add(JsonTree.object().put("source", kind).put("gate", gate));
        }
        return out;
    }

    /**
     * Whether an aged row passes over one mesh - a baby row reads the baby meshes alone, an adult
     * row everything but them, an ageless row every mesh.
     */
    private static boolean skips(@NotNull Row row, boolean babyMesh) {
        return row.age().map(age -> "baby".equals(age) != babyMesh).orElse(false);
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
     * @return the kind tokens, empty for a row this binding never moves
     * @throws ToolingException if a channel varies and no driver kind accounts for it
     */
    private static @NotNull Set<String> kindsOf(
        @NotNull String poseKey, @NotNull LinkedHashMap<String, Drive> binding,
        @NotNull Map<String, PoseOutcome> poses, @NotNull Map<String, KeyframeClip> clips,
        int periodTicks, @NotNull Map<String, PoseProgram> grounded,
        @NotNull Map<String, Set<String>> measured) {

        if (!(poses.get(poseKey) instanceof PoseOutcome.Extracted extracted)) return Set.of();
        String memoKey = poseKey + '|' + bindingKey(binding);
        Set<String> held = measured.get(memoKey);
        if (held != null) return held;

        PoseProgram program = grounded.computeIfAbsent(poseKey, key -> ground(extracted.program()));
        Evaluated full = evaluate(program, binding, Set.of(), periodTicks);
        Set<String> varying = full.varying();

        Map<String, List<String>> byKind = new LinkedHashMap<>();
        for (Drive drive : binding.values())
            byKind.computeIfAbsent(drive.kind(), kind -> new ArrayList<>()).add(drive.field());
        Map<String, Evaluated> droppedByKind = new LinkedHashMap<>();

        Set<String> kinds = new LinkedHashSet<>();
        if (!varying.isEmpty()) {
            for (String kind : byKind.keySet()) {
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
            if (site.drive() == PoseClipSite.Gate.NONE) continue;
            if (site.drive() == PoseClipSite.Gate.SELECT) {
                if (!satisfied(binding.get(site.state()), periodTicks)) continue;
                if (moves(clipOf(clips, poseKey, site)) && site.argsVary()) kinds.add("select");
                continue;
            }
            if (!moves(clipOf(clips, poseKey, site)) || !strideTravels(site, poseKey)) continue;
            boolean attributed = false;
            for (String kind : byKind.keySet()) {
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

        Set<String> sealed = Collections.unmodifiableSet(kinds);
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

    /** One measurement's memo key half - the binding as spelled values, order-independent. */
    private static @NotNull String bindingKey(@NotNull Map<String, Drive> binding) {
        return binding.values().stream()
            .map(drive -> drive.field() + ':' + drive.wave() + ':'
                + Float.floatToIntBits(drive.rest()) + ':' + Float.floatToIntBits(drive.extent()))
            .sorted()
            .collect(Collectors.joining(","));
    }

    /**
     * One pose row evaluated across one period under one binding.
     *
     * @param channels each written non-flag channel's per-tick values, keyed by its owner and token
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
        @NotNull PoseClipSite.Gate drive,
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
            if (channel.getKey().isFlag()) continue;
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
            Collections.unmodifiableMap(bones), List.copyOf(sites));
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
            case PoseExpr.BoneRead ignored -> PoseExpr.Const.of(0f);
            case PoseExpr.Op op -> PoseExpr.Op.of(op.operator(), op.operands().stream()
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
        PosePredicate out = switch (predicate) {
            case PosePredicate.Compare compare -> new PosePredicate.Compare(compare.comparison(),
                ground(compare.left(), memo), ground(compare.right(), memo));
            case PosePredicate.Not not -> new PosePredicate.Not(ground(not.operand(), memo));
            default -> predicate;
        };
        memo.put(predicate, out);
        return out;
    }

    // ------------------------------------------------------------------------------------
    // spelling
    // ------------------------------------------------------------------------------------

    /**
     * One emitted row - id first, then its base where the standing row is itself emitted, its age,
     * its measured sources, its entailed toggles, and its drives: the row's own where a base
     * carries the rest, the whole composed map where none does.
     */
    private static @NotNull JsonTree rowNode(
        @NotNull Row row, @NotNull List<JsonTree> sources, boolean baseEmitted) {

        JsonTree node = JsonTree.object().put("id", row.id());
        boolean based = baseEmitted && !IDLE.equals(row.id());
        if (based) node.put("base", IDLE);
        row.age().ifPresent(age -> node.put("age", age));
        node.put("sources", JsonTree.array().addAll(sources));
        if (!row.toggles().isEmpty())
            node.putStrings("toggles", row.toggles().toArray(String[]::new));
        JsonTree drives = node.childArray("drives");
        for (Drive drive : (based ? row.own() : row.composed()).values()) drives.add(drive.node());
        return node;
    }

}
