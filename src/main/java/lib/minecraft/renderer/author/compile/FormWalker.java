package lib.minecraft.renderer.author.compile;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.mesh.EntityMesh;
import lib.minecraft.renderer.asset.pose.EntityPose;
import lib.minecraft.renderer.asset.pose.PoseClip;
import lib.minecraft.renderer.author.BuiltStyle;
import lib.minecraft.renderer.author.PoseScript;
import lib.minecraft.renderer.author.mesh.LimbRoster;
import lib.minecraft.renderer.diagnostic.Diagnostics;
import lib.minecraft.renderer.engine.pose.ClipDrive;
import lib.minecraft.renderer.engine.pose.PoseChannel;
import lib.minecraft.renderer.engine.pose.PoseExpr;
import lib.minecraft.renderer.engine.pose.PoseNode;
import lib.minecraft.renderer.engine.pose.PosePredicate;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;
import lib.minecraft.renderer.vanilla.appearance.Age;
import lib.minecraft.renderer.vanilla.appearance.Size;
import org.intellij.lang.annotations.PrintFormat;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * The walk over every site one install of a built style on one row compiles or guards - the row's
 * body and passes, and every form an appearance swaps in for it - in the order the install weaves
 * them, answering the row rebuilt over what each compile answered.
 *
 * <p>The walk decides which sites there are and what each is guarded by; a {@link Visitor} decides
 * what a compile runs over and what an address it reached nothing with means. An installer compiles
 * over the entity's pool and refuses over such an address when strict; an audit compiles over a
 * pool of its own and keeps it. Both read one walk, so what an audit reports is what an install
 * would refuse over.
 *
 * <p>A form is woven body first, then its passes, its baby wherever the style's age admits a baby,
 * the passes of each shape form over its body, each size form, and each coat as a form of its own.
 * A pass sharing the pose its form was loaded with is re-pointed at the woven body and follows it
 * uncompiled; a pass carrying a distinct pose row compiles once per row across the whole walk, the
 * splices that depend on its mesh reading per-layer fields under a coined {@code $layer<N>}
 * coordinate, and is left untouched where no written bone lands on it. A form drawing the pose and
 * mesh an earlier form was woven over, against the same evidence, takes that weave. A size form
 * carrying a pose other than its row's, or a mesh resting apart from its row's - flattened at a
 * factor the row's is not, or resting a bone both declare at a scale the row's does not - is woven
 * as a form of its own, so a position it plays lands the authored pixels and a scale it plays
 * lands over its own rests, as a baby's do; one drawing the row's own pose over a mesh resting as
 * the row's does lends its mesh and render scale to that pose, so its mesh is guarded rather than
 * compiled against - a scale a shipped clip already writes on it refuses, as does a raw read it does
 * not declare, and a written bone it does not declare is recorded, a write to a bone the mesh lacks
 * filtering at render.
 *
 * <p>Each refusal is {@link IllegalArgumentException} with its context recorded as an {@code ERROR}
 * entry immediately before the throw.
 */
@Parity(subject = Subject.ENTITY)
public final class FormWalker {

    /**
     * The coined coordinate prefix a woven layer's rebased fields are spelled under.
     */
    private static final String LAYER_PREFIX = "$layer";

    /**
     * The scope segment a form an appearance swaps in records its diagnostics under.
     */
    private static final String FORM_SCOPE = "form";

    /**
     * The baby form's name - its axis and option, as its scope and its coordinate spell it.
     */
    private static final String BABY_FORM = "age:baby";

    /**
     * The name vanilla reserves for the part every bone hangs from, which no mesh here declares.
     */
    private static final String ROOT_PART = "root";

    /**
     * The namespaced id of the row the style installs on, as a refusal names it.
     */
    private final @NotNull String entityId;

    /**
     * The built style being installed.
     */
    private final @NotNull BuiltStyle style;

    /**
     * What the caller does at each compiled site.
     */
    private final @NotNull Visitor visitor;

    /**
     * The container channel tokens the script writes.
     */
    private final @NotNull List<String> foldedTokens;

    /**
     * Each body already woven, keyed by the pose instance it was woven over - one per mesh and
     * evidence, since a baby heading its adult's model class shares the adult's pose instance.
     */
    private final @NotNull Map<EntityPose, List<WovenBody>> bodies = new IdentityHashMap<>();

    /**
     * Each distinct pass row already woven, keyed by its pose instance - empty where nothing the
     * script spells landed on it.
     */
    private final @NotNull Map<EntityPose, Optional<EntityPose>> layers = new IdentityHashMap<>();

    private FormWalker(@NotNull String entityId, @NotNull BuiltStyle style, @NotNull Visitor visitor) {
        this.entityId = entityId;
        this.style = style;
        this.visitor = visitor;
        this.foldedTokens = containerTokens(style.script());
    }

    // ------------------------------------------------------------------------------------
    // the public surface
    // ------------------------------------------------------------------------------------

    /**
     * One place the walk compiles the style, as a visitor is handed it.
     *
     * @param form the form the site belongs to, as the working definitions hold it
     * @param given the same form as it was given
     * @param mesh the mesh the compile runs against - the form's own for a body, the pass's for a pass
     * @param pose the pose the compile splices into - the form's for a body, the pass's for a pass
     * @param evidence the pose as it was given, read for which bones vanilla articulates
     * @param pass the distinct pass the site compiles, empty for a form's body
     * @param coordinate the coordinate the site's own fields are spelled under, empty for the row's body
     * @param scope the diagnostics scope of the form the site belongs to
     */
    public record Site(
        @NotNull Entity form,
        @NotNull Entity given,
        @NotNull EntityMesh mesh,
        @NotNull EntityPose pose,
        @NotNull EntityPose evidence,
        @NotNull Optional<Entity.OverlayLayer> pass,
        @NotNull String coordinate,
        @NotNull Diagnostics scope
    ) {}

    /**
     * What one caller does at each site the walk compiles.
     */
    public interface Visitor {

        /**
         * Compiles the style at one site - the form's body where the site names no pass, the pass
         * over the body it is drawn over otherwise.
         *
         * @param site the site to compile
         * @param playSite the play site of the body a pass is drawn over, carried by instance; empty
         *     for a body
         * @return the compile
         * @throws IllegalArgumentException if a lowering rule refuses the authored content
         */
        @NotNull PoseCompiler.Compiled compile(@NotNull Site site, @NotNull Optional<EntityPose.Clip> playSite);

        /**
         * Answers the addresses one site's compile reached nothing with, handed over only where
         * there are any, and ahead of the checks the walk runs on the compiled pose.
         *
         * @param site the compiled site
         * @param drops the addresses that reached nothing, in first-written order
         * @throws IllegalArgumentException if the caller refuses over them
         */
        void unreached(@NotNull Site site, @NotNull ConcurrentList<PoseCompiler.Unreached> drops);

    }

    /**
     * Walks every site an install of the style on the row compiles or guards, in the order the
     * install weaves them, and answers the row rebuilt over what the visitor's compiles answered.
     *
     * @param entityId the namespaced id the row is installed under, as a refusal names it
     * @param style the built style being installed
     * @param row the row as the working definitions hold it
     * @param given the same row as it was given, the evidence every compile reads
     * @param scope the install's diagnostics scope, under which every form records its own
     * @param visitor what the caller does at each compiled site
     * @return the row carrying its woven pose, its passes and its rebuilt forms, its catalog left for
     *     the caller to set
     * @throws IllegalArgumentException if a guard refuses, or the visitor does
     */
    public static @NotNull Entity walk(@NotNull String entityId, @NotNull BuiltStyle style,
                                       @NotNull Entity row, @NotNull Entity given,
                                       @NotNull Diagnostics scope, @NotNull Visitor visitor) {
        return new FormWalker(entityId, style, visitor).woven(row, given, "", scope);
    }

    // ------------------------------------------------------------------------------------
    // the walk
    // ------------------------------------------------------------------------------------

    /**
     * Weaves one form and every form it carries, answering it rebuilt with its catalog left for
     * the caller to set: its own body and passes, its baby wherever the style's age admits a baby,
     * each shape form's passes over this body, each size form - woven as a form of its own where it
     * carries a pose other than this body's or a mesh resting apart from this body's, guarded where
     * it lends a mesh resting as this body's does to this body's pose - and each coat as a form of
     * its own. The row is the first form woven, so a row carrying no form is walked as
     * exactly its body and passes.
     *
     * @param form the form to weave, as the working definitions hold it
     * @param given the same form as it was given, the evidence every compile reads
     * @param coordinate the coordinate the form's own fields are spelled under, empty for the row
     * @param scope the form's diagnostics scope
     * @return the form carrying its woven pose, its passes and its rebuilt forms
     */
    private @NotNull Entity woven(@NotNull Entity form, @NotNull Entity given, @NotNull String coordinate,
                                  @NotNull Diagnostics scope) {
        WovenBody body = this.body(form, given, coordinate, scope);
        ConcurrentList<Entity.OverlayLayer> overlays = this.passes(form, given, body, coordinate, scope);
        Entity.Axes axes = form.axes();
        Entity.Axes givenAxes = given.axes();

        Optional<Entity> baby = axes.baby().map(child -> admitsBaby(this.style)
            ? this.woven(child, givenAxes.baby().orElse(child), coined(coordinate, BABY_FORM),
                formScope(scope, BABY_FORM))
            : child);
        Entity.Variation<String, Entity> shape = mapped(axes.shape(), (key, option) -> {
            if (axes.shape().isDeclared(key)) return pointed(option, body.pose(), overlays, baby);
            String name = "shape:" + key;
            Entity givenOption = givenAxes.shape().select(key).orElse(option);
            return pointed(option, body.pose(),
                this.passes(option, givenOption, body, coined(coordinate, name), formScope(scope, name)),
                baby);
        });
        // A size weaves apart where it carries a pose of its own or a mesh resting apart from the
        // row's, since a position field spelled over the row's factor lands scaled on a mesh
        // flattened at another, and a scale field spelled over the row's rest lands off on a bone
        // resting at another. The pose test is by instance, not by content: the index hands a size
        // naming the row's own class the row's own pose, an install hands a guarded size the woven
        // one, and a size woven apart keeps a woven pose of its own, so each size takes one arm on
        // the first install and on every later one.
        Entity.Variation<Size, Entity> size = mapped(axes.size(), (key, option) -> {
            String name = "size:" + key.name().toLowerCase(Locale.ROOT);
            if (option.pose() != form.pose() || restsApart(option.model(), form.model())) {
                Entity givenOption = givenAxes.size().select(key).orElse(option);
                return this.woven(option, givenOption, coined(coordinate, name), formScope(scope, name));
            }
            if (option.model() != form.model())
                this.guardSize(form.pose(), option.model(), name, formScope(scope, name));
            return pointed(option, body.pose(), overlays, baby);
        });
        Entity.Variation<String, Entity> variant = mapped(axes.variant(), (key, coat) -> {
            String name = "variant:" + key;
            return this.woven(coat, givenAxes.variant().select(key).orElse(coat),
                coined(coordinate, name), formScope(scope, name));
        });

        return form.mutate()
            .pose(body.pose())
            .overlays(overlays)
            .axes(new Entity.Axes(baby, shape, axes.state(), size, variant))
            .build();
    }

    /**
     * The body one form evaluates, woven: the shipped-clip scan, the visitor's compile, the
     * visitor's answer to whatever that compile reached nothing with, and the self-checks a
     * hand-built row skips at load. The row compiles its own splices; any other form compiles
     * against its own mesh under its coined coordinate, so what depends on its own mesh - its
     * rests, its pivots and the factor it is flattened at - is spelled apart from every other
     * mesh's. A form evaluating the pose and mesh an earlier form of this walk was woven over,
     * against the same evidence, takes that weave rather than compiling it again, which is what
     * keeps a coat drawing the row's own mesh from adding a field.
     */
    private @NotNull WovenBody body(@NotNull Entity form, @NotNull Entity given, @NotNull String coordinate,
                                    @NotNull Diagnostics scope) {
        List<WovenBody> taken = this.bodies.computeIfAbsent(form.pose(), pose -> new ArrayList<>(1));
        for (WovenBody earlier : taken)
            if (earlier.model() == form.model() && earlier.evidence() == given.pose()) return earlier;

        Diagnostics install = scope.child("install");
        Set<String> scaled = scaledBones(this.style.script(), form.model());
        List<String> displacing = this.scanShippedClips(install, scaled, form.pose(), form.model());
        if (!displacing.isEmpty() && !this.foldedTokens.isEmpty())
            install.info("fold-seat: container channel(s) [%s] fold into the seat clip(s) [%s] displace",
                joined(this.foldedTokens), joined(displacing));

        Site site = new Site(form, given, form.model(), form.pose(), given.pose(), Optional.empty(),
            coordinate, scope);
        PoseCompiler.Compiled compiled = this.visitor.compile(site, Optional.empty());
        if (!compiled.drops().isEmpty())
            this.visitor.unreached(site, compiled.drops());

        this.checkSelectSites(install, compiled.pose());
        this.checkRawReads(install, form);

        Optional<EntityPose.Clip> playSite = compiled.pose().clips().size() > form.pose().clips().size()
            ? Optional.of(compiled.pose().clips().getLast())
            : Optional.empty();
        WovenBody woven = new WovenBody(compiled.pose(), form.model(), given.pose(), playSite, scaled);
        taken.add(woven);
        return woven;
    }

    /**
     * One form's overlay passes over its woven body. A pass sharing the pose the form was loaded
     * with is re-pointed at the woven one and follows for free, and a pass carrying a distinct row
     * takes its own compile through {@link #wovenLayer} under a coordinate coined below the
     * form's, once per distinct row across the whole walk, so a pass two forms share weaves once.
     */
    private @NotNull ConcurrentList<Entity.OverlayLayer> passes(@NotNull Entity form, @NotNull Entity given,
                                                               @NotNull WovenBody body,
                                                               @NotNull String coordinate,
                                                               @NotNull Diagnostics scope) {
        List<Entity.OverlayLayer> overlays = new ArrayList<>(form.overlays().size());
        for (int index = 0; index < form.overlays().size(); index++) {
            Entity.OverlayLayer layer = form.overlays().get(index);
            if (layer.pose() == form.pose()) {
                overlays.add(repointed(layer, body.pose()));
                continue;
            }
            Optional<EntityPose> woven;
            if (this.layers.containsKey(layer.pose()))
                woven = this.layers.get(layer.pose());
            else {
                EntityPose evidence = index < given.overlays().size()
                    ? given.overlays().get(index).pose()
                    : layer.pose();
                woven = this.wovenLayer(form, given, layer, evidence, coordinate + LAYER_PREFIX + index,
                    body, scope);
                this.layers.put(layer.pose(), woven);
            }
            overlays.add(woven.map(pose -> repointed(layer, pose)).orElse(layer));
        }
        return Concurrent.newUnmodifiableList(overlays);
    }

    /**
     * Weaves one distinct-row overlay pass: the same script compiled against the layer's pose and
     * mesh, the splices that depend on the layer's mesh reading per-layer fields under the coined
     * coordinate, and a turn's delta splice and the play site riding the fields and instances of
     * the body the pass is drawn over. Answers empty where nothing the script spells lands on the
     * layer, which is then left untouched by instance. The evidence is the pass's pose as it was
     * given, for the reason the body's is.
     */
    private @NotNull Optional<EntityPose> wovenLayer(@NotNull Entity form, @NotNull Entity given,
                                                     @NotNull Entity.OverlayLayer layer,
                                                     @NotNull EntityPose evidence, @NotNull String coined,
                                                     @NotNull WovenBody body, @NotNull Diagnostics scope) {
        Diagnostics install = scope.child("install");
        Diagnostics events = scope.child("weave").child(coined);
        EntityMesh mesh = layer.model();
        String texture = layer.textureRef().map(ref -> " (texture '" + ref + "')").orElse("");

        List<String> landing = writtenBones(this.style.script(), mesh).stream()
            .filter(mesh.getBones()::containsKey)
            .toList();
        if (landing.isEmpty() && this.foldedTokens.isEmpty()) {
            // The style renders on this layer not at all, which is the criterion exactly. It joins
            // no aggregate and returns before any compile, so nothing else says it.
            events.warn("weave-skip: no written bone lands on layer '%s'%s", coined, texture);
            return Optional.empty();
        }

        List<String> displacing = this.scanShippedClips(install, body.scaled(), layer.pose(), mesh);
        if (!displacing.isEmpty() && !this.foldedTokens.isEmpty())
            events.info("fold-seat: container channel(s) [%s] fold into the seat clip(s) [%s] displace",
                joined(this.foldedTokens), joined(displacing));

        Site site = new Site(form, given, mesh, layer.pose(), evidence, Optional.of(layer), coined, scope);
        PoseCompiler.Compiled arm = this.visitor.compile(site, body.site());
        if (!arm.drops().isEmpty()) {
            // Recorded BEFORE the visitor answers, so a strict refusal adds the error and never
            // subtracts the warning: both forks say the same thing about the same drop, and the
            // strict one says one more thing after it.
            events.warn("weave-subset: layer '%s' drops [%s] and weaves the rest%s",
                coined, PoseCompiler.Unreached.describeAll(arm.drops()), texture);
            this.visitor.unreached(site, arm.drops());
        } else
            events.info("weave-full: layer '%s' woven whole - %d written bone(s)%s",
                coined, landing.size(), texture);

        this.checkSelectSites(install, arm.pose());
        return Optional.of(arm.pose());
    }

    /**
     * Guards one size form lending its own mesh to the woven row. A size form carrying the row's
     * own pose over a mesh resting as the row's does - flattened at the row's own factor, and
     * resting every bone both declare at the row's own scale - swaps only its mesh in, so the render
     * plays the woven row, whose position fields cross that one factor and whose scale fields
     * replace those same rests, over a mesh no compile ran against: a scale a shipped clip already
     * writes on it refuses, as does a raw read it does not declare, and a written bone it does not
     * declare is recorded rather than refused, a write to a bone the mesh lacks filtering at render.
     *
     * @param pose the row's pose as the install found it, whose shipped clips the mesh plays
     * @param mesh the size form's own mesh
     * @param name the form's name, as its diagnostics scope spells it
     * @param scope the form's diagnostics scope
     */
    private void guardSize(@NotNull EntityPose pose, @NotNull EntityMesh mesh, @NotNull String name,
                           @NotNull Diagnostics scope) {
        Diagnostics install = scope.child("install");
        this.scanShippedClips(install, scaledBones(this.style.script(), mesh), pose, mesh);
        List<String> dropped = writtenBones(this.style.script(), mesh).stream()
            .filter(bone -> !mesh.getBones().containsKey(bone))
            .toList();
        if (!dropped.isEmpty())
            install.warn("weave-subset: form '%s' plays the woven row without [%s], which its mesh does not declare",
                name, joined(dropped));
        this.checkRawReads(install, mesh);
    }

    // ------------------------------------------------------------------------------------
    // install guards
    // ------------------------------------------------------------------------------------

    /**
     * The one walk over a row's shipped play sites: refuses a scale collision - a clip channel
     * scaling a bone the script also scales, the very pair the write-back would throw on at
     * render - and classifies the row for the container fold-in by collecting each clip
     * coordinate whose channels reach the part every bone hangs from.
     *
     * @return the displacing clip coordinates, in site order; empty off the fold seat
     */
    private @NotNull List<String> scanShippedClips(@NotNull Diagnostics install, @NotNull Set<String> scaled,
                                                   @NotNull EntityPose pose, @NotNull EntityMesh mesh) {
        List<String> displacing = new ArrayList<>();
        for (EntityPose.Clip site : pose.clips())
            for (PoseClip.Channel channel : site.clip().channels()) {
                if (mesh.getBones().containsKey(channel.bone())) {
                    if (channel.target() == PoseChannel.Kind.SCALE && scaled.contains(channel.bone()))
                        throw refuse(install, "Style '%s' scales bone '%s', which shipped clip '%s' already scales - one factor cannot hold both",
                            this.style.styleId(), channel.bone(), site.coordinate());
                } else if (reachesContainer(channel.bone(), mesh) && !displacing.contains(site.coordinate()))
                    displacing.add(site.coordinate());
            }
        return displacing;
    }

    /**
     * Whether a name no bone answers to reaches the container - the root part, or a dangling parent.
     */
    private static boolean reachesContainer(@NotNull String named, @NotNull EntityMesh mesh) {
        if (ROOT_PART.equals(named)) return true;
        for (EntityMesh.Bone bone : mesh.getBones().values())
            if (named.equals(bone.getParent())) return true;
        return false;
    }

    /**
     * Whether a size form's mesh rests apart from its row's where a compile over the row's mesh
     * lands wrong on it - flattened at a factor the row's is not, which scales a position field by
     * the ratio of the two, or resting a bone the row also declares at a scale the row's does not,
     * whose scale field replaces a rest the row's compile never read.
     *
     * <p>The factor is compared beside the bones, because two meshes can rest every shared bone at
     * one scale and still answer different factors where a bone only one declares breaks the
     * other's agreement. That bone sets neither apart on its own, holding no rest on the other mesh
     * for a field to land off from: a write to one the size mesh lacks filters at render, which the
     * guard records. Both scales are table values rather than computed ones, so they compare
     * exactly.
     */
    private static boolean restsApart(@NotNull EntityMesh mesh, @NotNull EntityMesh row) {
        if (mesh.getFlattenedScale() != row.getFlattenedScale()) return true;
        for (Map.Entry<String, EntityMesh.Bone> bone : mesh.getBones().entrySet()) {
            EntityMesh.Bone shared = row.getBones().get(bone.getKey());
            if (shared != null && shared.getScale() != bone.getValue().getScale()) return true;
        }
        return false;
    }

    /**
     * The selection-site shape check a hand-built row skips at load: every selection site of a
     * woven pose carries a present gate field and exactly one term. The installed style's own
     * site holds by construction and its gate field is driven by the row appended in the same
     * call; a shipped site arriving through the loader already passed, so what this catches is
     * a hand-built definitions map carrying a site the render would fail on.
     */
    private void checkSelectSites(@NotNull Diagnostics install, @NotNull EntityPose pose) {
        for (EntityPose.Clip site : pose.clips()) {
            if (site.drive() != ClipDrive.SELECT) continue;
            if (site.field().isEmpty())
                throw refuse(install, "Entity '%s' would carry selection site '%s' naming no gate field",
                    this.entityId, site.coordinate());
            if (site.arguments().size() != 1)
                throw refuse(install, "Entity '%s' would carry selection site '%s' on %d term(s), which takes 1",
                    this.entityId, site.coordinate(), site.arguments().size());
        }
    }

    /**
     * Widens the raw hatch's bone-read check to every same-instance overlay mesh that evaluates
     * the woven row - a write to a bone a mesh lacks filters silently, so a raw's reads matter
     * only on a mesh that declares its written bone, and there a read of a missing bone throws
     * at render. Distinct layer rows run the same check inside their own compiles.
     */
    private void checkRawReads(@NotNull Diagnostics install, @NotNull Entity row) {
        if (this.style.script().raws().isEmpty()) return;
        for (Entity.OverlayLayer layer : row.overlays()) {
            if (layer.pose() != row.pose()) continue;
            this.checkRawReads(install, layer.model());
            layer.noHatModel().ifPresent(alternate -> this.checkRawReads(install, alternate));
        }
    }

    /**
     * Checks each landing raw's reads against one mesh, visiting each node once by instance.
     */
    private void checkRawReads(@NotNull Diagnostics install, @NotNull EntityMesh mesh) {
        Set<PoseNode> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (PoseScript.Raw raw : this.style.script().raws()) {
            if (!mesh.getBones().containsKey(raw.bone())) continue;
            Optional<String> missing = missingRead(raw.expr(), mesh, visited);
            if (missing.isPresent())
                throw refuse(install, "Style '%s' reads bone '%s', which a mesh evaluating the woven row does not declare - a read of a missing bone throws at render",
                    this.style.styleId(), missing.get());
        }
    }

    /**
     * The first bone a graph reads that the mesh does not declare, empty where it declares every one.
     */
    private static @NotNull Optional<String> missingRead(@NotNull PoseNode node, @NotNull EntityMesh mesh,
                                                         @NotNull Set<PoseNode> visited) {
        if (!visited.add(node)) return Optional.empty();
        return switch (node) {
            case PoseExpr.BoneRead read -> mesh.getBones().containsKey(read.bone())
                ? Optional.empty()
                : Optional.of(read.bone());
            case PoseExpr.Op op -> {
                for (PoseExpr operand : op.operands()) {
                    Optional<String> found = missingRead(operand, mesh, visited);
                    if (found.isPresent()) yield found;
                }
                yield Optional.empty();
            }
            case PoseExpr.Select select -> missingRead(select.condition(), mesh, visited)
                .or(() -> missingRead(select.whenTrue(), mesh, visited))
                .or(() -> missingRead(select.whenFalse(), mesh, visited));
            case PosePredicate predicate -> missingRead(predicate.left(), mesh, visited)
                .or(() -> missingRead(predicate.right(), mesh, visited));
            case PoseExpr.Constant ignored -> Optional.empty();
            case PoseExpr.Input ignored -> Optional.empty();
            // Reads no bone - it is answered off the subject rather than off the mesh.
            case PoseExpr.Answered ignored -> Optional.empty();
        };
    }

    // ------------------------------------------------------------------------------------
    // script readers and small helpers
    // ------------------------------------------------------------------------------------

    /**
     * Every bone the script addresses with content, in first-written order - raw captures included.
     *
     * <p>A selected limb is resolved against the mesh being asked about, because until a mesh
     * answers it there is no bone to name. Reading one as though it addressed nothing would leave
     * every distinct overlay layer of a legged style weave-skipped, with one warning and no
     * refusal.
     *
     * <p>The head's implicit hat copy counts only on a mesh the compile weaves it onto - one whose
     * hat sits outside the head's chain. Nobody wrote it, so a mesh lacking the shell drops it
     * silently and a hat the head carries takes nothing from it, and neither is a written bone.
     */
    private static @NotNull Set<String> writtenBones(@NotNull PoseScript script,
                                                     @NotNull EntityMesh mesh) {
        Supplier<LimbRoster> roster = rosterOf(mesh);
        boolean mirrored = mesh.getBones().containsKey("hat") && !PoseCompiler.hatRidesHead(mesh);
        Set<String> bones = new LinkedHashSet<>();
        for (PoseScript.Stance stance : script.stances())
            stance.limb().ifPresent(limb -> {
                if (!carries(stance)) return;
                if (!mirrored && PoseCompiler.implicitHatMirror(script, stance)) return;
                bones.addAll(addressed(limb, mesh, roster));
            });
        for (PoseScript.Raw raw : script.raws())
            bones.add(raw.bone());
        return bones;
    }

    /**
     * Every bone the script writes a scale channel on - uniform scales and scale-channel raws.
     */
    private static @NotNull Set<String> scaledBones(@NotNull PoseScript script,
                                                    @NotNull EntityMesh mesh) {
        Supplier<LimbRoster> roster = rosterOf(mesh);
        Set<String> bones = new LinkedHashSet<>();
        for (PoseScript.Stance stance : script.stances())
            stance.limb().ifPresent(limb -> {
                if (!stance.of(PoseScript.Scale.class).isEmpty()) bones.addAll(addressed(limb, mesh, roster));
            });
        for (PoseScript.Raw raw : script.raws())
            if (raw.channel().kind() == PoseChannel.Kind.SCALE) bones.add(raw.bone());
        return bones;
    }

    /**
     * The bones one captured limb names on the given mesh - the bone itself where it was written
     * by name, and whatever the mesh's own roster answers where it was written as a selector.
     *
     * @param roster the shared derivation every selected limb of one script reads through
     */
    private static @NotNull List<String> addressed(PoseScript.@NotNull Limb limb,
                                                   @NotNull EntityMesh mesh,
                                                   @NotNull Supplier<LimbRoster> roster) {
        return switch (limb) {
            case PoseScript.Limb.Named named -> List.of(named.bone());
            // Handed on as the supplier rather than as a roster, so a family address still resolves
            // without one ever being derived.
            case PoseScript.Limb.Selected selected ->
                LimbRoster.members(selected.selector(), mesh, roster);
        };
    }

    /**
     * One roster derivation shared across every selected limb of one script.
     *
     * <p>Deriving one is a full chain-transform walk over the mesh plus work quadratic in the leg
     * count, and a script addressing legs several times asked for that walk once per address. It
     * stays a supplier rather than becoming a roster so that a script addressing none of them, or
     * addressing only a family, still derives nothing at all - which is the reason the resolver
     * takes a supplier in the first place.
     *
     * @param mesh the mesh the roster is derived from
     * @return a supplier deriving on its first call and answering the same roster after
     */
    private static @NotNull Supplier<LimbRoster> rosterOf(@NotNull EntityMesh mesh) {
        return new Supplier<>() {

            private @NotNull Optional<LimbRoster> derived = Optional.empty();

            @Override
            public @NotNull LimbRoster get() {
                if (this.derived.isEmpty()) this.derived = Optional.of(LimbRoster.of(mesh));
                return this.derived.get();
            }
        };
    }

    /**
     * The container channel tokens the script writes - step verbs plus the hover vertical.
     */
    private static @NotNull List<String> containerTokens(@NotNull PoseScript script) {
        Set<String> tokens = new LinkedHashSet<>();
        for (PoseScript.Stance stance : script.stances()) {
            if (stance.limb().isPresent()) continue;
            for (PoseScript.Write write : stance.of(PoseScript.Write.class))
                tokens.add(write.channel().token());
            for (PoseScript.Sway sway : stance.of(PoseScript.Sway.class))
                tokens.add(sway.axis().channel().token());
            for (PoseScript.Spin spin : stance.of(PoseScript.Spin.class))
                tokens.add(spin.axis().channel().token());
        }
        script.hover().ifPresent(hover -> {
            if (hover.liftPixels() != 0d || hover.bobPixels() != 0d)
                tokens.add(PoseChannel.Y.token());
        });
        return List.copyOf(tokens);
    }

    /**
     * Whether a stance captured any verb at all - an empty lambda addresses nothing.
     */
    private static boolean carries(@NotNull PoseScript.Stance stance) {
        return !stance.fragments().isEmpty();
    }

    /**
     * One axis with every option replaced as the given function answers it, in the axis's own
     * order and declaring what it declared - the axis itself where it carries no option.
     */
    private static <K> Entity.@NotNull Variation<K, Entity> mapped(@NotNull Entity.Variation<K, Entity> axis,
                                                                  @NotNull BiFunction<K, Entity, Entity> option) {
        if (axis.options().isEmpty()) return axis;
        LinkedHashMap<K, Entity> options = new LinkedHashMap<>();
        axis.options().forEach((key, value) -> options.put(key, option.apply(key, value)));
        return new Entity.Variation<>(Concurrent.newUnmodifiableLinkedMap(options), axis.declared());
    }

    /**
     * A form taking the woven row's pose, the given passes and the row's baby while keeping its own
     * mesh, render scale and axes - the whole of what a shape form, or a size form drawing the row's
     * own pose over a mesh resting as the row's does, differs from its row in.
     */
    private static @NotNull Entity pointed(@NotNull Entity form, @NotNull EntityPose pose,
                                           @NotNull ConcurrentList<Entity.OverlayLayer> overlays,
                                           @NotNull Optional<Entity> baby) {
        Entity.Axes axes = form.axes();
        return form.mutate()
            .pose(pose)
            .overlays(overlays)
            .axes(new Entity.Axes(baby, axes.shape(), axes.state(), axes.size(), axes.variant()))
            .build();
    }

    /**
     * The coordinate one form's own fields are spelled under - its parent's, extended by its name.
     */
    private static @NotNull String coined(@NotNull String coordinate, @NotNull String name) {
        return coordinate + "$" + name;
    }

    /**
     * The scope one form records under - its parent's, below {@code form/<axis>:<option>}.
     */
    private static @NotNull Diagnostics formScope(@NotNull Diagnostics scope, @NotNull String name) {
        return scope.child(FORM_SCOPE).child(name);
    }

    /**
     * Whether a style's age admits a baby - an ageless style does, and so does a baby one.
     */
    private static boolean admitsBaby(@NotNull BuiltStyle style) {
        return style.age().map(age -> age == Age.BABY).orElse(true);
    }

    /**
     * The same pass carrying a different pose row - every other component untouched.
     */
    private static @NotNull Entity.OverlayLayer repointed(@NotNull Entity.OverlayLayer layer, @NotNull EntityPose pose) {
        return new Entity.OverlayLayer(layer.model(), layer.textureRef(), layer.pass(), layer.tintArgb(),
            layer.skipBounds(), layer.tintBy(), layer.textureBy(), layer.gate(), layer.noHatModel(),
            pose, layer.textureScroll());
    }

    /**
     * Records the refusal context and builds it - the entry is the post-mortem, and the
     * {@code throw} at the call site is the gate.
     *
     * <p>Returned rather than thrown so that not returning is visible to the compiler and to a
     * reader: a refusal spelled {@code throw refuse(...)} ends its branch in the branch, where one
     * that threw from in here ended it somewhere a reader had to already know about.
     *
     * @param scope the diagnostics scope the refusal records into
     * @param message the refusal, as a format string
     * @param args the format arguments
     * @return the refusal to throw
     */
    private static @NotNull IllegalArgumentException refuse(@NotNull Diagnostics scope,
                                                            @NotNull @PrintFormat String message,
                                                            @Nullable Object... args) {
        String formatted = String.format(message, args);
        scope.error("%s", formatted);
        return new IllegalArgumentException(formatted);
    }

    /**
     * One comma-joined name list for a refusal or a recorded line.
     */
    private static @NotNull String joined(@NotNull Collection<String> names) {
        return String.join(", ", names);
    }

    /**
     * One form's body as the walk wove it.
     *
     * @param pose the form's pose with the style's splices woven in
     * @param model the mesh the compile ran against
     * @param evidence the form's pose as it was given, read for which bones vanilla articulates
     * @param site the style's play site on the woven pose, which every distinct pass drawn over
     *     this body carries by instance; empty where the style keys no timeline
     * @param scaled every bone the script writes a scale channel on, resolved on the mesh
     */
    private record WovenBody(
        @NotNull EntityPose pose,
        @NotNull EntityMesh model,
        @NotNull EntityPose evidence,
        @NotNull Optional<EntityPose.Clip> site,
        @NotNull Set<String> scaled
    ) {}

}
