package lib.minecraft.renderer.author.install;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentLinkedMap;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.ImageFactory;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.mesh.EntityMesh;
import lib.minecraft.renderer.asset.pose.EntityPose;
import lib.minecraft.renderer.asset.pose.PoseClip;
import lib.minecraft.renderer.asset.pose.PoseStyle;
import lib.minecraft.renderer.asset.pose.StyleCatalog;
import lib.minecraft.renderer.author.BuiltStyle;
import lib.minecraft.renderer.author.PoseScript;
import lib.minecraft.renderer.author.compile.GraphInterner;
import lib.minecraft.renderer.author.compile.PoseCompiler;
import lib.minecraft.renderer.author.mesh.LimbRoster;
import lib.minecraft.renderer.content.index.EntityModelLoader;
import lib.minecraft.renderer.diagnostic.Diagnostics;
import lib.minecraft.renderer.engine.pose.ClipDrive;
import lib.minecraft.renderer.engine.pose.PoseChannel;
import lib.minecraft.renderer.engine.pose.PoseExpr;
import lib.minecraft.renderer.engine.pose.PoseNode;
import lib.minecraft.renderer.engine.pose.PosePredicate;
import lib.minecraft.renderer.engine.pose.StyleDriver;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;
import lib.minecraft.renderer.port.RendererContext;
import lib.minecraft.renderer.vanilla.appearance.Age;
import lib.minecraft.renderer.vanilla.appearance.Size;
import org.intellij.lang.annotations.PrintFormat;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
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
 * The install surface of pose authoring - binds entity-free built styles onto entity rows and
 * hands the mutated definitions back through a derived renderer context, so registration ships no
 * runtime change at all.
 *
 * <p>An install compiles the style against the target row's own mesh and shipped pose, appends
 * one flat catalog row after the shipped rows, and weaves every pose row the runtime evaluates:
 * an overlay pass sharing the body's pose instance is re-pointed at the spliced instance and
 * follows for free, while a pass carrying a distinct pose row takes its own compile against its
 * own mesh, rebased splices reading per-layer fields under a coined {@code $layer<N>} coordinate -
 * the pass declares no name of its own at this seam, so the overlay index names it. Worn armor
 * and equipment evaluate no pose row at all, so an armored subject's shells hold the rest
 * silhouette under any custom style.
 *
 * <p>The weave reaches every form an appearance swaps in for the row. Each coat is woven as a row
 * of its own, and so is the baby form - the row's and each coat's - wherever the style's age
 * admits a baby. A form drawing the pose and mesh an earlier form was woven over takes that
 * weave; any other compiles against its own mesh, spelling what it solves against its own rests
 * under a coordinate coined for it - {@code $age:baby}, {@code $variant:<coat>} - with its passes
 * coined below it, and its drivers join the appended row after the row's, first-wins. The large
 * shape form draws the row's woven pose over its own mesh, so only its passes are woven. A size
 * form lends its mesh and render scale to that same pose, so its mesh is guarded rather than
 * compiled against: a scale a shipped clip already writes on it, or a raw read it does not
 * declare, refuses, and a written bone it does not declare is recorded. Every form carries the one
 * catalog the install rebuilds.
 *
 * <p>{@link #add} is strict: a written bone absent from the target mesh - or from a woven form's
 * or layer's - refuses naming every missing bone, so a typo fails on the default spelling instead
 * of dropping silently; {@link #addTolerant} weaves the present subset per row. Every install
 * guard runs where the author is, replacing the load validation a hand-built row skips, and each
 * refusal is {@link IllegalArgumentException} with its context recorded as an {@code ERROR}
 * entry immediately before the throw.
 *
 * <p>One interner pool serves all of an entity's compiles, so two styles' common subtrees unify
 * by instance and a second style's splices stack onto the first weave. Assembly is
 * single-threaded state ending at {@link #context(RendererContext)}; concurrent installs on one
 * registrar are unsupported.
 *
 * <p>A caller skin for the player rig registers through {@link #skin(byte[])} - the rig row's
 * state axis re-declares the reserved ref, and {@link #context(RendererContext)} wraps its
 * context so the reserved id answers the caller's sheet ahead of every pack.
 *
 * <p>Recording is unconditional and emission is not, so a registrar opened in
 * {@link Diagnostics.Output#FILE} mode holds every entry until {@link #close()} writes them.
 * A context is derived over a copy of the definitions and holds nothing the close releases, so an
 * assembly wrapped in a try-with-resources hands back one that outlives the block.
 */
@Parity(subject = Subject.ENTITY)
public final class StyleRegistrar implements AutoCloseable {

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
     * The working definitions, mutated row by row as installs land.
     */
    private final @NotNull ConcurrentLinkedMap<String, Entity> working;

    /**
     * The definitions as they were given, which no install touches - the evidence every compile
     * reads for which bones vanilla articulates. A woven row carries earlier installs' splices,
     * and a splice is the author's mark on a bone, never vanilla's, so a stance's landing is
     * read off the row as it shipped and does not depend on what was installed before it.
     */
    private final @NotNull ConcurrentMap<String, Entity> given;

    /**
     * The root scope every install records under.
     */
    private final @NotNull Diagnostics root;

    /**
     * One interner pool per entity id, created on the row's first install and reused after.
     */
    private final @NotNull Map<String, GraphInterner> pools = new HashMap<>();

    /**
     * The registered caller skin the rig's reserved id answers with, or {@code null} while none is registered.
     */
    private @Nullable PixelBuffer skin;

    private StyleRegistrar(@NotNull ConcurrentLinkedMap<String, Entity> working, @NotNull Diagnostics root) {
        this.working = working;
        this.given = Concurrent.newUnmodifiableLinkedMap(new LinkedHashMap<>(working));
        this.root = root;
    }

    // ------------------------------------------------------------------------------------
    // factories and the public surface
    // ------------------------------------------------------------------------------------

    /**
     * Opens a registrar over a copy of the shipped definitions.
     *
     * @return the registrar
     */
    public static @NotNull StyleRegistrar ofShipped() {
        return of(EntityModelLoader.load());
    }

    /**
     * Opens a quiet registrar over a copy of the given definitions.
     *
     * @param definitions the entity definitions keyed by namespaced id
     * @return the registrar
     */
    public static @NotNull StyleRegistrar of(@NotNull ConcurrentMap<String, Entity> definitions) {
        return of(definitions, Diagnostics.Output.NONE, null);
    }

    /**
     * Opens a registrar over a copy of the given definitions, with diagnostics emitted in the
     * given mode - recording itself is unconditional, and a library prints nothing uninvited.
     *
     * @param definitions the entity definitions keyed by namespaced id
     * @param mode the emission mode
     * @param fileTarget the file-mode log path, or {@code null} outside file mode
     * @return the registrar
     */
    public static @NotNull StyleRegistrar of(@NotNull ConcurrentMap<String, Entity> definitions,
                                             @NotNull Diagnostics.Output mode, @Nullable Path fileTarget) {
        return new StyleRegistrar(Concurrent.newLinkedMap(definitions),
            Diagnostics.root("styles", mode, fileTarget));
    }

    /**
     * Installs a built style on one entity row, strictly - a written bone absent from the
     * target mesh, or from a woven form's or layer's, refuses naming every missing bone.
     *
     * @param entityId the namespaced id of the target row
     * @param style the built style to install
     * @return this registrar
     * @throws IllegalArgumentException if any install guard refuses
     */
    public @NotNull StyleRegistrar add(@NotNull String entityId, @NotNull BuiltStyle style) {
        return this.install(entityId, style, true);
    }

    /**
     * Installs a built style on one entity row tolerantly - written bones the target mesh, a
     * woven form's or a woven layer's mesh does not declare drop per row, each drop recorded, and
     * the present subset weaves.
     *
     * @param entityId the namespaced id of the target row
     * @param style the built style to install
     * @return this registrar
     * @throws IllegalArgumentException if any install guard other than the missing-bone fork refuses
     */
    public @NotNull StyleRegistrar addTolerant(@NotNull String entityId, @NotNull BuiltStyle style) {
        return this.install(entityId, style, false);
    }

    /**
     * Registers a caller skin for the player rig from PNG bytes, decoded through the public
     * image codec and wired exactly as {@link #skin(PixelBuffer)} wires a decoded buffer.
     *
     * @param pngBytes the skin sheet as PNG bytes - the modern 64x64 layout the rig mesh samples
     * @return this registrar
     * @throws IllegalArgumentException if this registrar carries no player rig row
     */
    public @NotNull StyleRegistrar skin(byte @NotNull [] pngBytes) {
        return this.skin(new ImageFactory().fromByteArray(pngBytes).toPixelBuffer());
    }

    /**
     * Registers a caller skin for the player rig - the rig row's state axis re-declares
     * {@link PlayerRig#SKIN_REF} in place of the shipped Steve ref, and
     * {@link #context(RendererContext)} wraps its context so the reserved id answers the given
     * buffer ahead of every pack. One registration serves every render of the row; a later one
     * replaces the buffer.
     *
     * @param skin the decoded skin sheet - the modern 64x64 layout the rig mesh samples
     * @return this registrar
     * @throws IllegalArgumentException if this registrar carries no player rig row
     */
    public @NotNull StyleRegistrar skin(@NotNull PixelBuffer skin) {
        Entity rig = this.working.get(PlayerRig.ENTITY_ID);
        if (rig == null)
            throw this.refuse(this.root.child(PlayerRig.ENTITY_ID).child("skin"),
                "A caller skin rides the '%s' row, which this registrar does not carry - put the rig row in the definitions before registering a skin",
                PlayerRig.ENTITY_ID);
        this.skin = skin;
        this.working.put(PlayerRig.ENTITY_ID, rig.mutate()
            .axes(skinned(rig.axes()))
            .build());
        return this;
    }

    /**
     * The root scope every install records under - paths read
     * {@code styles/<entityId>/<styleId>/...} with children {@code compile}, {@code install}
     * and {@code weave/<layer>}, and each form the install weaves or guards records its own
     * below {@code form/<axis>:<option>}.
     *
     * @return the root diagnostics scope
     */
    public @NotNull Diagnostics diagnostics() {
        return this.root;
    }

    /**
     * The definitions as installed so far - an unmodifiable snapshot in the working order.
     *
     * @return the installed definitions keyed by namespaced id
     */
    public @NotNull ConcurrentMap<String, Entity> definitions() {
        return Concurrent.newUnmodifiableLinkedMap(this.working);
    }

    /**
     * A renderer context answering entities out of the installed definitions, so the untouched render
     * chain resolves custom ids as carried peers of shipped ones. A registered caller skin wraps the
     * given context so the rig's reserved id answers it; unregistered, the textures pass through
     * untouched.
     *
     * @param context the renderer context
     * @return the context an entity renderer draws the installed definitions through
     */
    public @NotNull RendererContext context(@NotNull RendererContext context) {
        RendererContext resolved = this.skin == null
            ? context
            : context.withTexture(PlayerRig.SKIN_TEXTURE_ID, this.skin);
        return resolved.withEntities(this.definitions());
    }

    /**
     * Writes every recorded entry to the file target, where one was opened.
     *
     * <p>Under {@link Diagnostics.Output#NONE} and {@link Diagnostics.Output#CONSOLE} an
     * entry is emitted at the instant it is recorded, so nothing is held and this does nothing.
     * Under {@link Diagnostics.Output#FILE} this is the write, and until it runs the target
     * does not exist at all.
     *
     * <p>Closing ends the diagnostics rather than the assembly, which ends at
     * {@link #context(RendererContext)}. A refusal records its context and throws out of the
     * install, so a close reached through a try-with-resources is what carries an aborted install
     * to the log.
     *
     * @throws UncheckedIOException if the file target cannot be written
     */
    @Override
    public void close() {
        this.root.flush();
    }

    // ------------------------------------------------------------------------------------
    // the install sequence
    // ------------------------------------------------------------------------------------

    /**
     * Runs the whole install sequence for one row: the id guards, the weave of the row and of
     * every form an appearance swaps in for it, and the rebuilt row, whose every form carries the
     * one catalog the appended row rebuilds.
     */
    private @NotNull StyleRegistrar install(@NotNull String entityId, @NotNull BuiltStyle style, boolean strict) {
        Diagnostics scope = this.root.child(entityId).child(style.styleId());
        Diagnostics install = scope.child("install");

        Entity row = this.working.get(entityId);
        if (row == null)
            throw this.refuse(install, "Entity '%s' is not a definition this registrar carries, so style '%s' has no row to install on",
                entityId, style.styleId());

        if (row.styles().carries(style.styleId(), style.age()))
            throw this.refuse(install, "Entity '%s' already carries style '%s' at that age - shipped ids and previously installed ids are taken alike",
                entityId, style.styleId());

        Weave weave = new Weave(entityId, style, strict,
            this.pools.computeIfAbsent(entityId, id -> new GraphInterner()), row.styles().periodTicks());
        Entity rebuilt = this.woven(weave, row, this.given.get(entityId), "", scope);

        List<PoseStyle> rows = new ArrayList<>(row.styles().styles());
        rows.add(new PoseStyle(style.styleId(), style.sources(),
            Concurrent.newUnmodifiableMap(weave.drivers), style.toggles(), style.age(),
            weave.declaredPeriod));
        StyleCatalog catalog = new StyleCatalog(row.styles().periodTicks(),
            Concurrent.newUnmodifiableList(rows));

        this.working.put(entityId, cataloged(rebuilt, catalog));
        install.info("install summary: style '%s' joins entity '%s' - shipped styles untouched, the catalog lists %s",
            style.styleId(), entityId, catalog.ids());
        return this;
    }

    /**
     * Weaves one form and every form it carries, answering it rebuilt with its catalog left for
     * the caller to set once every driver has joined: its own body and passes, its baby wherever
     * the style's age admits a baby, each shape form's passes over this body, the guard over each
     * size mesh this body plays over, and each coat as a form of its own. The row is the first
     * form woven, so an install on a row carrying no form appends exactly the drivers its body
     * and passes compile.
     *
     * @param weave the install's working state
     * @param form the form to weave, as the working definitions hold it
     * @param given the same form as it was given, the evidence every compile reads
     * @param coordinate the coordinate the form's own fields are spelled under, empty for the row
     * @param scope the form's diagnostics scope
     * @return the form carrying its woven pose, its passes and its rebuilt forms
     */
    private @NotNull Entity woven(@NotNull Weave weave, @NotNull Entity form, @NotNull Entity given,
                                  @NotNull String coordinate, @NotNull Diagnostics scope) {
        WovenBody body = this.body(weave, form, given, coordinate, scope);
        ConcurrentList<Entity.OverlayLayer> overlays = this.passes(weave, form, given, body, coordinate, scope);
        Entity.Axes axes = form.axes();
        Entity.Axes givenAxes = given.axes();

        Optional<Entity> baby = axes.baby().map(child -> admitsBaby(weave.style)
            ? this.woven(weave, child, givenAxes.baby().orElse(child), coined(coordinate, BABY_FORM),
                formScope(scope, BABY_FORM))
            : child);
        Entity.Variation<String, Entity> shape = mapped(axes.shape(), (key, option) -> {
            if (axes.shape().isDeclared(key)) return pointed(option, body.pose(), overlays, baby);
            String name = "shape:" + key;
            Entity givenOption = givenAxes.shape().select(key).orElse(option);
            return pointed(option, body.pose(),
                this.passes(weave, option, givenOption, body, coined(coordinate, name), formScope(scope, name)),
                baby);
        });
        Entity.Variation<Size, Entity> size = mapped(axes.size(), (key, option) -> {
            if (option.model() != form.model()) {
                String name = "size:" + key.name().toLowerCase(Locale.ROOT);
                this.guardSize(weave, form.pose(), option.model(), name, formScope(scope, name));
            }
            return pointed(option, body.pose(), overlays, baby);
        });
        Entity.Variation<String, Entity> variant = mapped(axes.variant(), (key, coat) -> {
            String name = "variant:" + key;
            return this.woven(weave, coat, givenAxes.variant().select(key).orElse(coat),
                coined(coordinate, name), formScope(scope, name));
        });

        return form.mutate()
            .pose(body.pose())
            .overlays(overlays)
            .axes(new Entity.Axes(baby, shape, axes.state(), size, variant))
            .build();
    }

    /**
     * The body one form evaluates, woven: the shipped-clip scan, the compile over the entity's
     * pool, the strict-or-tolerant fork and the self-checks a hand-built row skips at load, the
     * compile's drivers joining the appended row first-wins. The row compiles its own splices; any
     * other form compiles against its own mesh under its coined coordinate, so what it solves
     * against its own rests or pivots is spelled apart from every other mesh's. A form evaluating
     * the pose and mesh an earlier form of this install was woven over, against the same evidence,
     * takes that weave rather than compiling it again, which is what keeps a coat drawing the row's
     * own mesh from adding a field.
     */
    private @NotNull WovenBody body(@NotNull Weave weave, @NotNull Entity form, @NotNull Entity given,
                                    @NotNull String coordinate, @NotNull Diagnostics scope) {
        List<WovenBody> taken = weave.bodies.computeIfAbsent(form.pose(), pose -> new ArrayList<>(1));
        for (WovenBody earlier : taken)
            if (earlier.model() == form.model() && earlier.evidence() == given.pose()) return earlier;

        Diagnostics install = scope.child("install");
        BuiltStyle style = weave.style;
        Set<String> scaled = scaledBones(style.script(), form.model());
        List<String> displacing = this.scanShippedClips(install, style, scaled, form.pose(), form.model());
        if (!displacing.isEmpty() && !weave.foldedTokens.isEmpty())
            install.info("fold-seat: container channel(s) [%s] fold into the seat clip(s) [%s] displace",
                joined(weave.foldedTokens), joined(displacing));

        PoseCompiler.Compiled compiled = coordinate.isEmpty()
            ? PoseCompiler.compile(style, form, given.pose(), scope, weave.pool)
            : PoseCompiler.compileLayer(style, form.pose(), given.pose(), form.model(), coordinate, scope,
                weave.pool, Optional.empty(), weave.periodTicks);

        if (weave.strict && !compiled.drops().isEmpty())
            throw this.refuse(install, "Style '%s' addresses [%s] that %s answers with nothing - its mesh declares [%s]",
                style.styleId(), PoseCompiler.Unreached.describeAll(compiled.drops()),
                subject(weave.entityId, coordinate), joined(form.model().getBones().keySet()));

        this.checkSelectSites(install, weave.entityId, compiled.pose());
        this.checkRawReads(install, style, form);
        compiled.style().drivers().forEach(weave.drivers::putIfAbsent);
        weave.declaredPeriod = compiled.style().periodTicks();

        Optional<EntityPose.Clip> site = compiled.pose().clips().size() > form.pose().clips().size()
            ? Optional.of(compiled.pose().clips().getLast())
            : Optional.empty();
        WovenBody woven = new WovenBody(compiled.pose(), form.model(), given.pose(), site, scaled);
        taken.add(woven);
        return woven;
    }

    /**
     * One form's overlay passes over its woven body. A pass sharing the pose the form was loaded
     * with is re-pointed at the woven one and follows for free, and a pass carrying a distinct row
     * takes its own compile through {@link #wovenLayer} under a coordinate coined below the
     * form's, once per distinct row across the whole install, so a pass two forms share weaves
     * once.
     */
    private @NotNull ConcurrentList<Entity.OverlayLayer> passes(@NotNull Weave weave, @NotNull Entity form,
                                                               @NotNull Entity given, @NotNull WovenBody body,
                                                               @NotNull String coordinate,
                                                               @NotNull Diagnostics scope) {
        List<Entity.OverlayLayer> overlays = new ArrayList<>(form.overlays().size());
        for (int index = 0; index < form.overlays().size(); index++) {
            Entity.OverlayLayer layer = form.overlays().get(index);
            if (layer.pose() == form.pose()) {
                overlays.add(repointed(layer, body.pose()));
                continue;
            }
            EntityPose woven;
            if (weave.layers.containsKey(layer.pose()))
                woven = weave.layers.get(layer.pose());
            else {
                EntityPose evidence = index < given.overlays().size()
                    ? given.overlays().get(index).pose()
                    : layer.pose();
                woven = this.wovenLayer(weave, layer, evidence, coordinate + LAYER_PREFIX + index, body, scope);
                weave.layers.put(layer.pose(), woven);
            }
            overlays.add(woven == null ? layer : repointed(layer, woven));
        }
        return Concurrent.newUnmodifiableList(overlays);
    }

    /**
     * Weaves one distinct-row overlay pass: the same script compiled against the layer's pose and
     * mesh over the entity's one pool at the row's catalog period, rebased splices reading
     * per-layer fields under the coined coordinate, delta splices and the play site riding the
     * fields and instances of the body the pass is drawn over. The layer's drivers join the
     * appended row first-wins, so the body compile's copy of a shared field stands and a field only
     * this layer's splices read - a bone the body's mesh dropped - still lands its driver. Answers
     * {@code null} where nothing the script spells lands on the layer, leaving the pass untouched
     * by instance. The evidence is the pass's pose as it was given, for the reason the body's is.
     */
    private @Nullable EntityPose wovenLayer(@NotNull Weave weave, @NotNull Entity.OverlayLayer layer,
                                            @NotNull EntityPose evidence, @NotNull String coined,
                                            @NotNull WovenBody body, @NotNull Diagnostics scope) {
        BuiltStyle style = weave.style;
        Diagnostics install = scope.child("install");
        Diagnostics events = scope.child("weave").child(coined);
        EntityMesh mesh = layer.model();
        String texture = layer.textureRef().map(ref -> " (texture '" + ref + "')").orElse("");

        List<String> landing = writtenBones(style.script(), mesh).stream()
            .filter(mesh.getBones()::containsKey)
            .toList();
        if (landing.isEmpty() && weave.foldedTokens.isEmpty()) {
            // The style renders on this layer not at all, which is the criterion exactly. It joins
            // no aggregate and returns before any compile, so nothing else says it.
            events.warn("weave-skip: no written bone lands on layer '%s'%s", coined, texture);
            return null;
        }

        List<String> displacing = this.scanShippedClips(install, style, body.scaled(), layer.pose(), mesh);
        if (!displacing.isEmpty() && !weave.foldedTokens.isEmpty())
            events.info("fold-seat: container channel(s) [%s] fold into the seat clip(s) [%s] displace",
                joined(weave.foldedTokens), joined(displacing));

        PoseCompiler.Compiled arm = PoseCompiler.compileLayer(style, layer.pose(), evidence, mesh, coined,
            scope, weave.pool, body.site(), weave.periodTicks);
        if (!arm.drops().isEmpty()) {
            // Recorded BEFORE the strict refusal, so strictness adds the error and never subtracts
            // the warning: both forks say the same thing about the same drop, and the strict one
            // says one more thing after it.
            events.warn("weave-subset: layer '%s' drops [%s] and weaves the rest%s",
                coined, PoseCompiler.Unreached.describeAll(arm.drops()), texture);
            if (weave.strict)
                throw this.refuse(install, "Style '%s' weaves layer '%s' of entity '%s', which answers [%s] with nothing - its mesh declares [%s]",
                    style.styleId(), coined, weave.entityId, PoseCompiler.Unreached.describeAll(arm.drops()),
                    joined(mesh.getBones().keySet()));
        } else
            events.info("weave-full: layer '%s' woven whole - %d written bone(s)%s",
                coined, landing.size(), texture);

        this.checkSelectSites(install, weave.entityId, arm.pose());
        arm.style().drivers().forEach(weave.drivers::putIfAbsent);
        return arm.pose();
    }

    /**
     * Guards one size form lending its own mesh to the woven row. A size swaps its mesh in and
     * keeps the row's pose, so the render plays the woven row over a mesh no compile ran against:
     * a scale a shipped clip already writes on it refuses, as does a raw read it does not declare,
     * and a written bone it does not declare is recorded rather than refused, a write to a bone
     * the mesh lacks filtering at render.
     *
     * @param weave the install's working state
     * @param pose the row's pose as the install found it, whose shipped clips the mesh plays
     * @param mesh the size form's own mesh
     * @param name the form's name, as its diagnostics scope spells it
     * @param scope the form's diagnostics scope
     */
    private void guardSize(@NotNull Weave weave, @NotNull EntityPose pose, @NotNull EntityMesh mesh,
                           @NotNull String name, @NotNull Diagnostics scope) {
        Diagnostics install = scope.child("install");
        BuiltStyle style = weave.style;
        this.scanShippedClips(install, style, scaledBones(style.script(), mesh), pose, mesh);
        List<String> dropped = writtenBones(style.script(), mesh).stream()
            .filter(bone -> !mesh.getBones().containsKey(bone))
            .toList();
        if (!dropped.isEmpty())
            install.warn("weave-subset: form '%s' plays the woven row without [%s], which its mesh does not declare",
                name, joined(dropped));
        this.checkRawReads(install, style, mesh);
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
    private @NotNull List<String> scanShippedClips(@NotNull Diagnostics install, @NotNull BuiltStyle style,
                                                   @NotNull Set<String> scaled, @NotNull EntityPose pose,
                                                   @NotNull EntityMesh mesh) {
        List<String> displacing = new ArrayList<>();
        for (EntityPose.Clip site : pose.clips())
            for (PoseClip.Channel channel : site.clip().channels()) {
                if (mesh.getBones().containsKey(channel.bone())) {
                    if (channel.target() == PoseChannel.Kind.SCALE && scaled.contains(channel.bone()))
                        throw this.refuse(install, "Style '%s' scales bone '%s', which shipped clip '%s' already scales - one factor cannot hold both",
                            style.styleId(), channel.bone(), site.coordinate());
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
     * The selection-site shape check a hand-built row skips at load: every selection site of a
     * woven pose carries a present gate field and exactly one term. The installed style's own
     * site holds by construction and its gate field is driven by the row appended in the same
     * call; a shipped site arriving through the loader already passed, so what this catches is
     * a hand-built definitions map carrying a site the render would fail on.
     */
    private void checkSelectSites(@NotNull Diagnostics install, @NotNull String entityId, @NotNull EntityPose pose) {
        for (EntityPose.Clip site : pose.clips()) {
            if (site.drive() != ClipDrive.SELECT) continue;
            if (site.field().isEmpty())
                throw this.refuse(install, "Entity '%s' would carry selection site '%s' naming no gate field",
                    entityId, site.coordinate());
            if (site.arguments().size() != 1)
                throw this.refuse(install, "Entity '%s' would carry selection site '%s' on %d term(s), which takes 1",
                    entityId, site.coordinate(), site.arguments().size());
        }
    }

    /**
     * Widens the raw hatch's bone-read check to every same-instance overlay mesh that evaluates
     * the woven row - a write to a bone a mesh lacks filters silently, so a raw's reads matter
     * only on a mesh that declares its written bone, and there a read of a missing bone throws
     * at render. Distinct layer rows run the same check inside their own compiles.
     */
    private void checkRawReads(@NotNull Diagnostics install, @NotNull BuiltStyle style, @NotNull Entity row) {
        if (style.script().raws().isEmpty()) return;
        for (Entity.OverlayLayer layer : row.overlays()) {
            if (layer.pose() != row.pose()) continue;
            this.checkRawReads(install, style, layer.model());
            layer.noHatModel().ifPresent(alternate -> this.checkRawReads(install, style, alternate));
        }
    }

    /**
     * Checks each landing raw's reads against one mesh, visiting each node once by instance.
     */
    private void checkRawReads(@NotNull Diagnostics install, @NotNull BuiltStyle style, @NotNull EntityMesh mesh) {
        Set<PoseNode> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (PoseScript.Raw raw : style.script().raws()) {
            if (!mesh.getBones().containsKey(raw.bone())) continue;
            String missing = missingRead(raw.expr(), mesh, visited);
            if (missing != null)
                throw this.refuse(install, "Style '%s' reads bone '%s', which a mesh evaluating the woven row does not declare - a read of a missing bone throws at render",
                    style.styleId(), missing);
        }
    }

    /**
     * The first bone a graph reads that the mesh does not declare, or {@code null}.
     */
    private static @Nullable String missingRead(@NotNull PoseNode node, @NotNull EntityMesh mesh,
                                                @NotNull Set<PoseNode> visited) {
        if (!visited.add(node)) return null;
        return switch (node) {
            case PoseExpr.BoneRead read -> mesh.getBones().containsKey(read.bone()) ? null : read.bone();
            case PoseExpr.Op op -> {
                for (PoseExpr operand : op.operands()) {
                    String found = missingRead(operand, mesh, visited);
                    if (found != null) yield found;
                }
                yield null;
            }
            case PoseExpr.Select select -> {
                String found = missingRead(select.condition(), mesh, visited);
                if (found == null) found = missingRead(select.whenTrue(), mesh, visited);
                if (found == null) found = missingRead(select.whenFalse(), mesh, visited);
                yield found;
            }
            case PosePredicate predicate -> {
                String found = missingRead(predicate.left(), mesh, visited);
                yield found != null ? found : missingRead(predicate.right(), mesh, visited);
            }
            case PoseExpr.Constant ignored -> null;
            case PoseExpr.Input ignored -> null;
            // Reads no bone - it is answered off the subject rather than off the mesh.
            case PoseExpr.Answered ignored -> null;
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
     */
    private static @NotNull Set<String> writtenBones(@NotNull PoseScript script,
                                                     @NotNull EntityMesh mesh) {
        Supplier<LimbRoster> roster = rosterOf(mesh);
        Set<String> bones = new LinkedHashSet<>();
        for (PoseScript.Stance stance : script.stances())
            stance.limb().ifPresent(limb -> {
                if (carries(stance)) bones.addAll(addressed(limb, mesh, roster));
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

            private @Nullable LimbRoster derived;

            @Override
            public @NotNull LimbRoster get() {
                if (this.derived == null) this.derived = LimbRoster.of(mesh);
                return this.derived;
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
     * The same axes with the state axis re-declared at the reserved skin ref - one state, one ref.
     */
    private static @NotNull Entity.Axes skinned(@NotNull Entity.Axes axes) {
        Entity.Variation<String, String> state = new Entity.Variation<>(
            Concurrent.newUnmodifiableMap(Map.of(Entity.BASE_STATE, PlayerRig.SKIN_REF)),
            Optional.of(Entity.BASE_STATE));
        return new Entity.Axes(axes.baby(), axes.shape(), state, axes.size(), axes.variant());
    }

    /**
     * The same form with it and every form it carries holding the given catalog - a form holds its
     * row's own styles, so the catalog an install rebuilds for the row is every form's as well.
     */
    private static @NotNull Entity cataloged(@NotNull Entity form, @NotNull StyleCatalog catalog) {
        Entity.Axes axes = form.axes();
        return form.mutate()
            .styles(catalog)
            .axes(new Entity.Axes(axes.baby().map(baby -> cataloged(baby, catalog)),
                mapped(axes.shape(), (key, option) -> cataloged(option, catalog)), axes.state(),
                mapped(axes.size(), (key, option) -> cataloged(option, catalog)),
                mapped(axes.variant(), (key, option) -> cataloged(option, catalog))))
            .build();
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
     * mesh, render scale and axes - the whole of what a size or shape form differs from its row in.
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
     * How a refusal names what a compile ran against - the entity, or one form of it by the
     * coordinate the form's fields are spelled under.
     */
    private static @NotNull String subject(@NotNull String entityId, @NotNull String coordinate) {
        return coordinate.isEmpty()
            ? "entity '" + entityId + "'"
            : "form '" + coordinate + "' of entity '" + entityId + "'";
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
     * reader: a refusal spelled {@code throw this.refuse(...)} ends its branch in the branch, where
     * one that threw from in here ended it somewhere a reader had to already know about.
     *
     * @param scope the diagnostics scope the refusal records into
     * @param message the refusal, as a format string
     * @param args the format arguments
     * @return the refusal to throw
     */
    private @NotNull IllegalArgumentException refuse(@NotNull Diagnostics scope,
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

    // ------------------------------------------------------------------------------------
    // one install's working state
    // ------------------------------------------------------------------------------------

    /**
     * The working state one install carries across the row and every form it weaves - the style
     * and its strictness, the entity's pool, the catalog period, the drivers the appended row
     * collects, and the weaves already taken.
     */
    private static final class Weave {

        /**
         * The namespaced id of the row the style installs on.
         */
        private final @NotNull String entityId;

        /**
         * The built style being installed.
         */
        private final @NotNull BuiltStyle style;

        /**
         * Whether an address a woven mesh answers with nothing refuses rather than drops.
         */
        private final boolean strict;

        /**
         * The entity's interner pool, which every compile of the install shares.
         */
        private final @NotNull GraphInterner pool;

        /**
         * The row's catalog period, framing every compile's default strip window.
         */
        private final int periodTicks;

        /**
         * The container channel tokens the script writes.
         */
        private final @NotNull List<String> foldedTokens;

        /**
         * The appended row's drivers - the row's compile first, every later compile joining
         * first-wins.
         */
        private final @NotNull LinkedHashMap<String, StyleDriver> drivers = new LinkedHashMap<>();

        /**
         * Each body already woven, keyed by the pose instance it was woven over - one per mesh and
         * evidence, since a baby heading its adult's model class shares the adult's pose instance.
         */
        private final @NotNull Map<EntityPose, List<WovenBody>> bodies = new IdentityHashMap<>();

        /**
         * Each distinct pass row already woven, keyed by its pose instance - {@code null} where
         * nothing the script spells landed on it.
         */
        private final @NotNull Map<EntityPose, EntityPose> layers = new IdentityHashMap<>();

        /**
         * The period the script declares, in whole ticks, empty where it rides the catalog's -
         * every compile of one script answers the same.
         */
        private @NotNull Optional<Integer> declaredPeriod = Optional.empty();

        private Weave(@NotNull String entityId, @NotNull BuiltStyle style, boolean strict,
                      @NotNull GraphInterner pool, int periodTicks) {
            this.entityId = entityId;
            this.style = style;
            this.strict = strict;
            this.pool = pool;
            this.periodTicks = periodTicks;
            this.foldedTokens = containerTokens(style.script());
        }

    }

    /**
     * One form's body as an install wove it.
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
