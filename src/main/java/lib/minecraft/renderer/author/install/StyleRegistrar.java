package lib.minecraft.renderer.author.install;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentLinkedMap;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.ImageFactory;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.pose.EntityPose;
import lib.minecraft.renderer.asset.pose.PoseStyle;
import lib.minecraft.renderer.asset.pose.StyleCatalog;
import lib.minecraft.renderer.author.BuiltStyle;
import lib.minecraft.renderer.author.compile.FormWalker;
import lib.minecraft.renderer.author.compile.GraphInterner;
import lib.minecraft.renderer.author.compile.PoseCompiler;
import lib.minecraft.renderer.content.index.EntityModelLoader;
import lib.minecraft.renderer.diagnostic.Diagnostics;
import lib.minecraft.renderer.engine.pose.StyleDriver;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;
import lib.minecraft.renderer.port.RendererContext;
import org.intellij.lang.annotations.PrintFormat;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;

/**
 * The install surface of pose authoring - binds entity-free built styles onto entity rows and
 * hands the mutated definitions back through a derived renderer context, so registration ships no
 * runtime change at all.
 *
 * <p>An install compiles the style against the target row's own mesh and shipped pose, appends
 * one flat catalog row after the shipped rows, and weaves every pose row the runtime evaluates:
 * an overlay pass sharing the body's pose instance is re-pointed at the spliced instance and
 * follows for free, while a pass carrying a distinct pose row takes its own compile against its
 * own mesh, the splices that depend on its mesh reading per-layer fields under a coined
 * {@code $layer<N>} coordinate - the pass declares no name of its own at this seam, so the overlay
 * index names it. Worn armor and equipment evaluate no pose row at all, so an armored subject's
 * shells hold the rest silhouette under any custom style.
 *
 * <p>The weave reaches every form an appearance swaps in for the row. Each coat is woven as a row
 * of its own, and so is the baby form - the row's and each coat's - wherever the style's age
 * admits a baby, and so is each size form carrying a pose other than the row's, the small and
 * medium pufferfish posed by their own model classes. A form drawing the pose and mesh an earlier
 * form was woven over takes that weave; any other compiles against its own mesh, spelling what
 * depends on that mesh - a turn rebased against its rests, a carry, a scale and a position delta -
 * under a coordinate coined for it - {@code $age:baby}, {@code $variant:<coat>},
 * {@code $size:<option>} - with its passes coined below it, and its drivers join the appended row
 * after the row's, first-wins. The large shape form draws the row's woven pose over its own mesh,
 * so only its passes are woven. A size form drawing the row's own pose lends its mesh and render
 * scale to that pose, so its mesh is guarded rather than compiled against: a scale a shipped clip
 * already writes on it, or a raw read it does not declare, refuses, and a written bone it does not
 * declare is recorded. Every form carries the one catalog the install rebuilds. Which sites there
 * are and what guards each is {@link FormWalker}'s walk, which an audit reads too; what a compile
 * runs over and the strict-or-tolerant fork are the install's.
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
            throw refuse(this.root.child(PlayerRig.ENTITY_ID).child("skin"),
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
     * Runs the whole install sequence for one row: the id guards, the walk over the row and every
     * form an appearance swaps in for it, and the rebuilt row, whose every form carries the one
     * catalog the appended row rebuilds.
     */
    private @NotNull StyleRegistrar install(@NotNull String entityId, @NotNull BuiltStyle style, boolean strict) {
        Diagnostics scope = this.root.child(entityId).child(style.styleId());
        Diagnostics install = scope.child("install");

        Entity row = this.working.get(entityId);
        if (row == null)
            throw refuse(install, "Entity '%s' is not a definition this registrar carries, so style '%s' has no row to install on",
                entityId, style.styleId());

        if (row.styles().carries(style.styleId(), style.age()))
            throw refuse(install, "Entity '%s' already carries style '%s' at that age - shipped ids and previously installed ids are taken alike",
                entityId, style.styleId());

        Weave weave = new Weave(entityId, style, strict,
            this.pools.computeIfAbsent(entityId, id -> new GraphInterner()), row.styles().periodTicks());
        Entity rebuilt = FormWalker.walk(entityId, style, row, this.given.get(entityId), scope, weave);

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

    // ------------------------------------------------------------------------------------
    // small helpers
    // ------------------------------------------------------------------------------------

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
     * How a refusal names what a compile ran against - the entity, or one form of it by the
     * coordinate the form's fields are spelled under.
     */
    private static @NotNull String subject(@NotNull String entityId, @NotNull String coordinate) {
        return coordinate.isEmpty()
            ? "entity '" + entityId + "'"
            : "form '" + coordinate + "' of entity '" + entityId + "'";
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

    // ------------------------------------------------------------------------------------
    // one install's working state
    // ------------------------------------------------------------------------------------

    /**
     * The working state one install carries across every site the walk compiles, and what the
     * install does at each - the style and its strictness, the entity's pool, the catalog period,
     * and the drivers the appended row collects: each compile runs over the pool, its drivers join
     * the appended row first-wins, and an address it reached nothing with refuses when strict.
     */
    private static final class Weave implements FormWalker.Visitor {

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
         * The appended row's drivers - the row's compile first, every later compile joining
         * first-wins.
         */
        private final @NotNull LinkedHashMap<String, StyleDriver> drivers = new LinkedHashMap<>();

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
        }

        /** {@inheritDoc} */
        @Override
        public @NotNull PoseCompiler.Compiled compile(@NotNull FormWalker.Site site,
                                                      @NotNull Optional<EntityPose.Clip> playSite) {
            PoseCompiler.Compiled compiled = site.coordinate().isEmpty()
                ? PoseCompiler.compile(this.style, site.form(), site.evidence(), site.scope(), this.pool)
                : PoseCompiler.compileLayer(this.style, site.pose(), site.evidence(), site.mesh(),
                    site.coordinate(), site.scope(), this.pool, playSite, this.periodTicks);
            // A later compile's drivers join first-wins, so the body compile's copy of a shared field
            // stands and a field only a pass's splices read - a bone the body's mesh dropped - still
            // lands its driver.
            compiled.style().drivers().forEach(this.drivers::putIfAbsent);
            if (site.pass().isEmpty())
                this.declaredPeriod = compiled.style().periodTicks();
            return compiled;
        }

        /** {@inheritDoc} */
        @Override
        public void unreached(@NotNull FormWalker.Site site, @NotNull ConcurrentList<PoseCompiler.Unreached> drops) {
            if (!this.strict) return;
            String declares = joined(site.mesh().getBones().keySet());
            if (site.pass().isEmpty())
                throw refuse(site.scope().child("install"),
                    "Style '%s' addresses [%s] that %s answers with nothing - its mesh declares [%s]",
                    this.style.styleId(), PoseCompiler.Unreached.describeAll(drops),
                    subject(this.entityId, site.coordinate()), declares);
            throw refuse(site.scope().child("install"),
                "Style '%s' weaves layer '%s' of entity '%s', which answers [%s] with nothing - its mesh declares [%s]",
                this.style.styleId(), site.coordinate(), this.entityId, PoseCompiler.Unreached.describeAll(drops),
                declares);
        }

    }

}
