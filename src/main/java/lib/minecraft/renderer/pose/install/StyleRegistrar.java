package lib.minecraft.renderer.pose.install;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentLinkedMap;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.ImageFactory;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.EntityRenderer;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.asset.pose.EntityPose;
import lib.minecraft.renderer.asset.pose.PoseClip;
import lib.minecraft.renderer.asset.pose.PoseStyle;
import lib.minecraft.renderer.asset.pose.StyleCatalog;
import lib.minecraft.renderer.asset.pose.StyleDriver;
import lib.minecraft.renderer.engine.RendererContext;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;
import lib.minecraft.renderer.pipeline.loader.EntityModelLoader;
import lib.minecraft.renderer.pose.MotionSource;
import lib.minecraft.renderer.pose.PoseChannel;
import lib.minecraft.renderer.pose.PoseExpr;
import lib.minecraft.renderer.pose.PosePredicate;
import lib.minecraft.renderer.pose.author.BuiltStyle;
import lib.minecraft.renderer.pose.author.PoseScript;
import lib.minecraft.renderer.pose.compile.GraphInterner;
import lib.minecraft.renderer.pose.compile.LimbRoster;
import lib.minecraft.renderer.pose.compile.PoseCompiler;
import lib.minecraft.renderer.pose.compile.StyleDiagnostics;
import org.intellij.lang.annotations.PrintFormat;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The install surface of pose authoring - binds entity-free built styles onto entity rows and
 * hands the mutated definitions to the renderer's public constructor, so registration ships no
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
 * <p>{@link #add} is strict: a written bone absent from the target mesh - or from a woven
 * layer's - refuses naming every missing bone, so a typo fails on the default spelling instead of
 * dropping silently; {@link #addTolerant} weaves the present subset per row. Every install guard
 * runs where the author is, replacing the load validation a hand-built row skips, and each
 * refusal is {@link IllegalArgumentException} with its context recorded as an {@code ERROR}
 * entry immediately before the throw.
 *
 * <p>One interner pool serves all of an entity's compiles, so two styles' common subtrees unify
 * by instance and a second style's splices stack onto the first weave. Assembly is
 * single-threaded state ending at {@link #renderer(RendererContext)}; concurrent installs on one
 * registrar are unsupported.
 *
 * <p>A caller skin for the player rig registers through {@link #skin(byte[])} - the rig row's
 * state axis re-declares the reserved ref, and {@link #renderer(RendererContext)} wraps its
 * context so the reserved id answers the caller's sheet ahead of every pack.
 */
@Parity(subject = Subject.ENTITY)
public final class StyleRegistrar {

    /**
     * The coined coordinate prefix a woven layer's rebased fields are spelled under.
     */
    private static final String LAYER_PREFIX = "$layer";

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
    private final @NotNull StyleDiagnostics root;

    /**
     * One interner pool per entity id, created on the row's first install and reused after.
     */
    private final @NotNull Map<String, GraphInterner> pools = new HashMap<>();

    /**
     * The registered caller skin the rig's reserved id answers with, or {@code null} while none is registered.
     */
    private @Nullable PixelBuffer skin;

    private StyleRegistrar(@NotNull ConcurrentLinkedMap<String, Entity> working, @NotNull StyleDiagnostics root) {
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
        return of(definitions, StyleDiagnostics.Output.NONE, null);
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
                                             @NotNull StyleDiagnostics.Output mode, @Nullable Path fileTarget) {
        return new StyleRegistrar(Concurrent.newLinkedMap(definitions),
            StyleDiagnostics.root("styles", mode, fileTarget));
    }

    /**
     * Installs a built style on one entity row, strictly - a written bone absent from the
     * target mesh, or from a woven layer's, refuses naming every missing bone.
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
     * Installs a built style on one entity row tolerantly - written bones the target mesh or a
     * woven layer's mesh does not declare drop per row, each drop recorded, and the present
     * subset weaves.
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
     * {@link #renderer(RendererContext)} wraps its context so the reserved id answers the given
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
     * and {@code weave/<layer>}.
     *
     * @return the root diagnostics scope
     */
    public @NotNull StyleDiagnostics diagnostics() {
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
     * An entity renderer over the installed definitions - the existing public constructor, so
     * the untouched render chain resolves custom ids as carried peers of shipped ones. A
     * registered caller skin wraps the given context so the rig's reserved id answers it;
     * unregistered, the context passes through untouched.
     *
     * @param context the renderer context
     * @return the renderer
     */
    public @NotNull EntityRenderer renderer(@NotNull RendererContext context) {
        RendererContext resolved = this.skin == null ? context : new SkinContext(context, this.skin);
        return new EntityRenderer(resolved, this.definitions());
    }

    // ------------------------------------------------------------------------------------
    // the install sequence
    // ------------------------------------------------------------------------------------

    /**
     * Runs the whole install sequence for one row: the id guards, the shipped-clip scan, the
     * compile over the entity's pool, the strict-or-tolerant fork, the self-checks a hand-built
     * row skips at load, the overlay weave, and the rebuilt row with its appended catalog row.
     */
    private @NotNull StyleRegistrar install(@NotNull String entityId, @NotNull BuiltStyle style, boolean strict) {
        StyleDiagnostics scope = this.root.child(entityId).child(style.styleId());
        StyleDiagnostics install = scope.child("install");

        Entity row = this.working.get(entityId);
        if (row == null)
            throw this.refuse(install, "Entity '%s' is not a definition this registrar carries, so style '%s' has no row to install on",
                entityId, style.styleId());

        if (row.styles().ids().contains(style.styleId()))
            throw this.refuse(install, "Entity '%s' already carries style '%s' - shipped ids and previously installed ids are taken alike",
                entityId, style.styleId());

        Set<String> scaled = scaledBones(style.script(), row.model());
        List<String> foldedTokens = containerTokens(style.script());
        List<String> displacing = this.scanShippedClips(install, style, scaled, row.pose(), row.model());
        if (!displacing.isEmpty() && !foldedTokens.isEmpty())
            install.info("fold-seat: container channel(s) [%s] fold into the seat clip(s) [%s] displace",
                joined(foldedTokens), joined(displacing));

        GraphInterner pool = this.pools.computeIfAbsent(entityId, id -> new GraphInterner());
        Entity given = this.given.get(entityId);
        PoseCompiler.Compiled body = PoseCompiler.compile(style, row, given.pose(), scope, pool);

        if (strict && !body.droppedBones().isEmpty())
            throw this.refuse(install, "Style '%s' writes bone(s) [%s] that entity '%s' does not declare - its mesh declares [%s]",
                style.styleId(), joined(body.droppedBones()), entityId, joined(row.model().getBones().keySet()));

        this.checkSelectSites(install, entityId, body.pose());
        this.checkRawReads(install, style, row);

        EntityPose preWeave = row.pose();
        Optional<EntityPose.Clip> site = body.pose().clips().size() > preWeave.clips().size()
            ? Optional.of(body.pose().clips().getLast())
            : Optional.empty();

        LinkedHashMap<String, StyleDriver> drivers = new LinkedHashMap<>(body.style().drivers());
        Map<EntityPose, EntityPose> wovenRows = new IdentityHashMap<>();
        List<Entity.OverlayLayer> overlays = new ArrayList<>(row.overlays().size());
        for (int index = 0; index < row.overlays().size(); index++) {
            Entity.OverlayLayer layer = row.overlays().get(index);
            if (layer.pose() == preWeave) {
                overlays.add(repointed(layer, body.pose()));
                continue;
            }
            EntityPose woven;
            if (wovenRows.containsKey(layer.pose()))
                woven = wovenRows.get(layer.pose());
            else {
                EntityPose evidence = index < given.overlays().size()
                    ? given.overlays().get(index).pose()
                    : layer.pose();
                woven = this.wovenLayer(entityId, style, strict, layer, evidence, index, scope, install,
                    pool, site, row.styles().periodTicks(), scaled, foldedTokens, drivers);
                wovenRows.put(layer.pose(), woven);
            }
            overlays.add(woven == null ? layer : repointed(layer, woven));
        }

        List<PoseStyle> rows = new ArrayList<>(row.styles().styles());
        rows.add(new PoseStyle(style.styleId(), style.sources(),
            Concurrent.newUnmodifiableMap(drivers), style.toggles(), style.age(),
            body.style().periodTicks()));
        StyleCatalog catalog = new StyleCatalog(row.styles().periodTicks(),
            Concurrent.newUnmodifiableList(rows));

        this.working.put(entityId, row.mutate()
            .pose(body.pose())
            .styles(catalog)
            .overlays(Concurrent.newUnmodifiableList(overlays))
            .build());
        install.info("install summary: style '%s' joins entity '%s' - shipped styles untouched, the catalog lists %s",
            style.styleId(), entityId, catalog.ids());
        return this;
    }

    /**
     * Weaves one distinct-row overlay pass: the same script compiled against the layer's pose
     * and mesh over the entity's one pool at the row's catalog period, rebased splices reading
     * per-layer fields under the coined coordinate, delta splices and the play site riding the
     * body's fields and instances. The layer's drivers join the appended row first-wins, so
     * the body compile's copy of a shared field stands and a field only this layer's splices
     * read - a bone the body's mesh dropped - still lands its driver. Answers {@code null}
     * where nothing the script spells lands on the layer, leaving the pass untouched by
     * instance. The evidence is the pass's pose as it was given, for the reason the body's is.
     */
    private @Nullable EntityPose wovenLayer(@NotNull String entityId, @NotNull BuiltStyle style, boolean strict,
                                            @NotNull Entity.OverlayLayer layer, @NotNull EntityPose evidence, int index,
                                            @NotNull StyleDiagnostics scope, @NotNull StyleDiagnostics install,
                                            @NotNull GraphInterner pool, @NotNull Optional<EntityPose.Clip> site,
                                            int periodTicks,
                                            @NotNull Set<String> scaled, @NotNull List<String> foldedTokens,
                                            @NotNull LinkedHashMap<String, StyleDriver> drivers) {
        String coined = LAYER_PREFIX + index;
        StyleDiagnostics events = scope.child("weave").child(coined);
        EntityModelData mesh = layer.model();
        String texture = layer.textureRef().map(ref -> " (texture '" + ref + "')").orElse("");

        List<String> landing = writtenBones(style.script(), mesh).stream()
            .filter(mesh.getBones()::containsKey)
            .toList();
        if (landing.isEmpty() && foldedTokens.isEmpty()) {
            events.info("weave-skip: no written bone lands on layer '%s'%s", coined, texture);
            return null;
        }

        List<String> displacing = this.scanShippedClips(install, style, scaled, layer.pose(), mesh);
        if (!displacing.isEmpty() && !foldedTokens.isEmpty())
            events.info("fold-seat: container channel(s) [%s] fold into the seat clip(s) [%s] displace",
                joined(foldedTokens), joined(displacing));

        PoseCompiler.Compiled arm = PoseCompiler.compileLayer(style, layer.pose(), evidence, mesh, coined,
            scope, pool, site, periodTicks);
        if (!arm.droppedBones().isEmpty()) {
            // Recorded BEFORE the strict refusal, so strictness adds the error and never subtracts
            // the warning: both forks say the same thing about the same drop, and the strict one
            // says one more thing after it.
            events.warn("weave-subset: layer '%s' drops bone(s) [%s] and weaves the rest%s",
                coined, joined(arm.droppedBones()), texture);
            if (strict)
                throw this.refuse(install, "Style '%s' weaves layer '%s' of entity '%s', whose mesh does not declare bone(s) [%s] - it declares [%s]",
                    style.styleId(), coined, entityId, joined(arm.droppedBones()), joined(mesh.getBones().keySet()));
        } else
            events.info("weave-full: layer '%s' woven whole - %d written bone(s)%s",
                coined, landing.size(), texture);

        this.checkSelectSites(install, entityId, arm.pose());
        arm.style().drivers().forEach(drivers::putIfAbsent);
        return arm.pose();
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
    private @NotNull List<String> scanShippedClips(@NotNull StyleDiagnostics install, @NotNull BuiltStyle style,
                                                   @NotNull Set<String> scaled, @NotNull EntityPose pose,
                                                   @NotNull EntityModelData mesh) {
        List<String> displacing = new ArrayList<>();
        for (EntityPose.Clip site : pose.clips())
            for (PoseClip.Channel channel : site.clip().channels()) {
                if (mesh.getBones().containsKey(channel.bone())) {
                    if (channel.target() == PoseClip.Target.SCALE && scaled.contains(channel.bone()))
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
    private static boolean reachesContainer(@NotNull String named, @NotNull EntityModelData mesh) {
        if (ROOT_PART.equals(named)) return true;
        for (EntityModelData.Bone bone : mesh.getBones().values())
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
    private void checkSelectSites(@NotNull StyleDiagnostics install, @NotNull String entityId, @NotNull EntityPose pose) {
        for (EntityPose.Clip site : pose.clips()) {
            if (site.drive() != MotionSource.SELECT) continue;
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
    private void checkRawReads(@NotNull StyleDiagnostics install, @NotNull BuiltStyle style, @NotNull Entity row) {
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
    private void checkRawReads(@NotNull StyleDiagnostics install, @NotNull BuiltStyle style, @NotNull EntityModelData mesh) {
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
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
    private static @Nullable String missingRead(@NotNull PoseExpr node, @NotNull EntityModelData mesh,
                                                @NotNull Set<Object> visited) {
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
            default -> null;
        };
    }

    /**
     * The condition arm of the same walk.
     */
    private static @Nullable String missingRead(@NotNull PosePredicate node, @NotNull EntityModelData mesh,
                                                @NotNull Set<Object> visited) {
        if (!visited.add(node)) return null;
        String found = missingRead(node.left(), mesh, visited);
        return found != null ? found : missingRead(node.right(), mesh, visited);
    }

    // ------------------------------------------------------------------------------------
    // script readers and small helpers
    // ------------------------------------------------------------------------------------

    /**
     * Every bone the script addresses with content, in first-written order - raw captures included.
     *
     * <p>A selected limb is resolved against the mesh being asked about, because until a mesh
     * answers it there is no bone to name. Reading one as though it addressed nothing would leave
     * every distinct overlay layer of a legged style weave-skipped, with one info line and no
     * refusal.
     */
    private static @NotNull Set<String> writtenBones(@NotNull PoseScript script,
                                                     @NotNull EntityModelData mesh) {
        Set<String> bones = new LinkedHashSet<>();
        for (PoseScript.Stance stance : script.stances())
            stance.limb().ifPresent(limb -> {
                if (carries(stance)) bones.addAll(addressed(limb, mesh));
            });
        for (PoseScript.Raw raw : script.raws())
            bones.add(raw.bone());
        return bones;
    }

    /**
     * Every bone the script writes a scale channel on - uniform scales and scale-channel raws.
     */
    private static @NotNull Set<String> scaledBones(@NotNull PoseScript script,
                                                    @NotNull EntityModelData mesh) {
        Set<String> bones = new LinkedHashSet<>();
        for (PoseScript.Stance stance : script.stances())
            stance.limb().ifPresent(limb -> {
                if (!stance.of(PoseScript.Scale.class).isEmpty()) bones.addAll(addressed(limb, mesh));
            });
        for (PoseScript.Raw raw : script.raws())
            if (raw.channel().kind() == PoseChannel.Kind.SCALE) bones.add(raw.bone());
        return bones;
    }

    /**
     * The bones one captured limb names on the given mesh - the bone itself where it was written
     * by name, and whatever the mesh's own roster answers where it was written as a selector.
     */
    private static @NotNull List<String> addressed(PoseScript.@NotNull Limb limb,
                                                   @NotNull EntityModelData mesh) {
        return switch (limb) {
            case PoseScript.Limb.Named named -> List.of(named.bone());
            // The roster is built inside the supplier rather than before it, so a family address
            // resolves without one - which is what this method did before the resolver was shared.
            case PoseScript.Limb.Selected selected ->
                LimbRoster.members(selected.selector(), mesh, () -> LimbRoster.of(mesh));
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
        Entity.Axis<String, String> state = new Entity.Axis<>(
            Concurrent.newUnmodifiableMap(Map.of(Entity.BASE_STATE, PlayerRig.SKIN_REF)),
            Optional.of(Entity.BASE_STATE));
        return new Entity.Axes(axes.babyModel(), axes.babyPose(), axes.babyOverlays(),
            axes.shape(), state, axes.size(), axes.variant());
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
    private @NotNull IllegalArgumentException refuse(@NotNull StyleDiagnostics scope,
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

}
