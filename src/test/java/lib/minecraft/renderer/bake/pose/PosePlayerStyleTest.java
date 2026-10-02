package lib.minecraft.renderer.bake.pose;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.mesh.EntityMesh;
import lib.minecraft.renderer.asset.pose.EntityPose;
import lib.minecraft.renderer.asset.pose.PoseStyle;
import lib.minecraft.renderer.asset.pose.StyleCatalog;
import lib.minecraft.renderer.content.index.EntityModelLoader;
import lib.minecraft.renderer.engine.math.Vector3f;
import lib.minecraft.renderer.engine.pose.PoseChannel;
import lib.minecraft.renderer.request.AppearanceOptions;
import lib.minecraft.renderer.request.EntityOptions;
import lib.minecraft.renderer.vanilla.appearance.Age;
import lib.minecraft.renderer.vanilla.appearance.Size;
import lib.minecraft.renderer.vanilla.appearance.TropicalFishPattern;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.ToDoubleFunction;

import static lib.minecraft.renderer.fixture.CompilerFixtures.drawnScale;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The resolved catalog row applied through the posing surface.
 *
 * <p>The first test is the one the whole opt-in rests on: under the {@code bind} row both the
 * subject form and the memo hand back the very instance they were given, so a caller that asks for
 * nothing allocates nothing and renders the bytes it always rendered. The rest pin what the memo
 * owes its two passes - one posed instance per tick, and one per member INSTANCE per tick, because
 * variant coats share the family id and an id-keyed memo would answer one coat's mesh for another.
 * The next five hold every form of every shipped row - each equipment slot filled among them - to
 * posing under every style it lists, every scale a shipped pose writes to the value its bone's own
 * field rests at but the harnessed happy ghast's body, every bone it poses to its rest scale and no
 * pose scale but its clips' and that same squeeze, every part's own field to resting at one on
 * every form but the small armour stand, and the salmon's flattened sizes to placing their
 * container where vanilla's pose stack places it. The last holds a harnessed happy ghast's body
 * inside the harness it wears.
 */
@DisplayName("the resolved style row applied to a subject")
class PosePlayerStyleTest {

    /** Ticks a subject is posed at - zero and one odd instant. */
    private static final int @NotNull [] TICKS = {0, 7};

    /** The adult happy ghast with its body slot filled, the one form whose body a filled slot reshapes. */
    private static final @NotNull String HARNESSED_GHAST = "minecraft:happy_ghast equip=body";

    /** The form whose mesh the baby transform bakes, resting its parts' own fields off one. */
    private static final @NotNull String SMALL_STAND = "minecraft:armor_stand size=small";

    private static ConcurrentMap<String, Entity> entities;

    @BeforeAll
    static void load() {
        entities = EntityModelLoader.load();
        assumeTrue(!entities.isEmpty(), "entity_models.json not present - run entityModels first");
    }

    @Test
    @DisplayName("the bind row hands back the very instance it was given, subject for subject")
    void theBindRowIsTheSubjectItself() {
        for (Entity entity : entities.values()) {
            PosePlayer.PosedFrames frames =
                PosePlayer.frames(entity, StyleCatalog.bind(), entity.styles().periodTicks());
            for (int tick : TICKS) {
                assertSame(entity,
                    PosePlayer.posed(entity, StyleCatalog.bind(), entity.styles().periodTicks(), tick),
                    entity.id() + " is its own subject at tick " + tick);
                assertSame(entity, frames.at(tick),
                    entity.id() + " is its own memo answer at tick " + tick);
            }
        }
    }

    @Test
    @DisplayName("a moving row poses the subject somewhere its bind pose is not")
    void aMovingRowPosesTheSubject() {
        Entity squid = subject("minecraft:squid");
        PoseStyle idle = squid.styles()
            .resolve(PoseStyle.IDLE, AppearanceOptions.defaults()::applies, "minecraft:squid");
        Entity posed = PosePlayer.posed(squid, idle, squid.styles().periodTicks(), 7);
        assertNotSame(squid, posed, "a driven row answers a new subject");
        assertNotSame(squid.model(), posed.model(), "carrying a posed mesh of its own");
    }

    @Test
    @DisplayName("the memo answers one posed instance per tick")
    void theMemoAnswersOneInstancePerTick() {
        Entity zombie = subject("minecraft:zombie");
        PoseStyle idle = zombie.styles()
            .resolve(PoseStyle.IDLE, AppearanceOptions.defaults()::applies, "minecraft:zombie");
        PosePlayer.PosedFrames frames = PosePlayer.frames(zombie, idle, zombie.styles().periodTicks());

        assertSame(frames.at(7), frames.at(7), "one tick asked twice is one posed instance");
        assertNotEquals(frames.at(0).model().getBones(), frames.at(7).model().getBones(),
            "and a subject that moves stands somewhere else seven ticks later");
    }

    @Test
    @DisplayName("the member memo keys by instance, so two subjects sharing an id pose apart")
    void theMemberMemoKeysByInstance() {
        Entity zombie = subject("minecraft:zombie");
        Entity twin = zombie.mutate().build();
        assertNotSame(zombie, twin, "the twin is a distinct instance of the same definition");

        PoseStyle idle = zombie.styles()
            .resolve(PoseStyle.IDLE, AppearanceOptions.defaults()::applies, "minecraft:zombie");
        PosePlayer.PosedFrames frames = PosePlayer.frames(zombie, idle, zombie.styles().periodTicks());
        Entity posed = frames.at(zombie, 7);
        Entity posedTwin = frames.at(twin, 7);

        assertNotSame(posed, posedTwin, "each instance is posed as its own subject");
        assertSame(posed, frames.at(zombie, 7), "and each is posed once per tick");
        assertEquals(frames.at(7).model().getBones(), posed.model().getBones(),
            "the primary asked through the member overload answers the same posed form");
    }

    @Test
    @DisplayName("a union member is measured under its own answer to the style id")
    void aUnionMemberIsMeasuredUnderItsOwnRow() {
        Entity axolotl = subject("minecraft:axolotl");
        EntityOptions baby = EntityOptions.builder()
            .entityId("minecraft:axolotl")
            .appearance(AppearanceOptions.builder().age(Age.BABY).build())
            .build();
        Entity resolved = baby.getAppearance().resolve(axolotl);
        PoseStyle row = resolved.styles().resolve(PoseStyle.IDLE, baby.getAppearance()::applies, baby.getEntityId());
        assertEquals(Set.of("ageInTicks"), Set.copyOf(row.drivers().keySet()),
            "the baby answers the universal row, its family's idle applying to the adult alone");

        PosePlayer.PosedFrames frames = PosePlayer.frames(resolved, row, resolved.styles().periodTicks());
        assertSame(resolved, frames.at(0),
            "nothing the universal row drives moves the baby's meshes");
        assertNotSame(axolotl.model(), frames.at(axolotl, 0).model(),
            "while the adult measured beside it stands in its own idle stance");
    }

    @Test
    @DisplayName("the lone-mesh overload answers the given mesh under bind and an unreadable pose")
    void theLoneMeshOverloadAnswersTheGivenMesh() {
        EntityMesh mesh = new EntityMesh();
        assertSame(mesh, PosePlayer.posed(EntityPose.NONE, mesh,
                StyleCatalog.bind(), StyleCatalog.BIND_ONLY.periodTicks(), 7),
            "the bind row is the mesh itself");

        EntityPose unreadable = new EntityPose(Concurrent.newUnmodifiableList(),
            Concurrent.newUnmodifiableMap(), Concurrent.newUnmodifiableList(),
            Optional.of("the walk could not read this model"));
        PoseStyle idle = StyleCatalog.BIND_ONLY
            .resolve(PoseStyle.IDLE, AppearanceOptions.defaults()::applies, "minecraft:test");
        assertSame(mesh, PosePlayer.posed(unreadable, mesh, idle,
                StyleCatalog.BIND_ONLY.periodTicks(), 7),
            "and so is a pose that could not be read, under a row that moves");
    }

    @Test
    @DisplayName("every form of every shipped row poses under every style its in-force catalog lists")
    void everyFormPosesUnderEveryListedStyle() {
        List<String> failures = new ArrayList<>();
        Set<String> walked = new LinkedHashSet<>();
        for (Form form : forms()) {
            walked.add(form.label());
            for (String id : form.styleIds())
                for (int tick : TICKS) {
                    try {
                        PosePlayer.posed(form.resolved(), form.style(id), form.periodTicks(), tick);
                    } catch (RuntimeException failure) {
                        failures.add(form.label() + " '" + id + "' @" + tick + ": " + failure.getMessage());
                    }
                }
        }
        assertTrue(walked.size() > 50, "the walk reaches the corpus, " + walked.size() + " forms");
        assertTrue(walked.containsAll(List.of("minecraft:salmon size=small", "minecraft:salmon size=large")),
            "the walk reaches both flattened salmon forms");
        assertEquals(List.of(), failures, "every form poses under every style it lists");
    }

    @Test
    @DisplayName("every scale a shipped pose writes is its bone's field rest, bit for bit, on every form under every listed style")
    void everyShippedWrittenScaleIsItsRest() {
        // A written scale is vanilla's field and rides the chain as its ratio to the value that field
        // rests at, and one equal to that rest adds nothing to the chain - which is what leaves every
        // stored render untouched by the ratio. A shipped write off its rest draws somewhere new, and
        // the rows named here owe a capture.
        List<String> offRest = new ArrayList<>();
        Set<String> reached = new LinkedHashSet<>();
        for (Form form : forms())
            for (String id : form.styleIds())
                for (int tick : TICKS) {
                    PoseStyle style = form.style(id);
                    if (PoseStyle.BIND.equals(style.id())) continue;
                    ToDoubleFunction<String> frame = style.frameAt(tick, form.periodTicks());
                    String where = form.label() + " '" + id + "' @" + tick;
                    Entity resolved = form.resolved();
                    if (writesScale(where, resolved.pose(), resolved.model(), frame, offRest))
                        reached.add(form.label());
                    // Each pass poses its own mesh with its own model class, and a suppressed pass's
                    // no-hat alternate takes the pass's pose, as the subject form poses them.
                    for (int index = 0; index < resolved.overlays().size(); index++) {
                        Entity.OverlayLayer overlay = resolved.overlays().get(index);
                        String pass = where + " pass " + index;
                        if (writesScale(pass, overlay.pose(), overlay.model(), frame, offRest))
                            reached.add(form.label());
                        Optional<EntityMesh> noHat = overlay.noHatModel();
                        if (noHat.isPresent()
                            && writesScale(pass + " no-hat", overlay.pose(), noHat.get(), frame, offRest))
                            reached.add(form.label());
                    }
                    // Each equipment mesh is posed by the class its layer is handed.
                    for (Entity.EquipmentOverlay equipment : resolved.layers().equipment())
                        writesScale(where + " equip " + equipment.slot(), equipment.pose(), equipment.model(),
                            frame, offRest);
                }
        assertTrue(reached.containsAll(List.of("minecraft:happy_ghast", "minecraft:happy_ghast age=baby",
                HARNESSED_GHAST)),
            "the pin reaches the happy ghast's written body scale at both ages and harnessed: " + reached);
        // Stated before the run: vanilla squeezes a harnessed adult's body to 0.9375 on all three axes,
        // so that row is the one write off its field's rest, and every other write holds.
        List<String> admitted = offRest.stream().filter(PosePlayerStyleTest::isHarnessSqueeze).toList();
        for (String axis : List.of("x_scale", "y_scale", "z_scale"))
            assertTrue(admitted.stream().anyMatch(write -> write.contains("'body' " + axis + " = 0.9375")),
                "the harnessed body writes " + axis + " at 0.9375: " + admitted);
        offRest.removeAll(admitted);
        assertEquals(List.of(), offRest, "every other shipped written scale is its bone's field rest, bit for bit");
    }

    @Test
    @DisplayName("every bone a shipped form poses keeps its rest scale and carries no pose scale but its clips', on every form under every listed style")
    void everyPosedBoneCarriesOnlyItsClipsScale() {
        // What the pin above leaves to the player: a bone written its field's rest, or written no
        // scale at all, is handed back with the very pose scale it was loaded with, so its chain skips
        // the scale step and composes the matrix an unposed bone composes. Only a clip's scale reaches
        // a chain on the shipped corpus, as its field's rest plus the displacement over that rest, and
        // no pose moves a rest factor.
        List<String> moved = new ArrayList<>();
        for (Form form : forms())
            for (String id : form.styleIds())
                for (int tick : TICKS) {
                    PoseStyle style = form.style(id);
                    if (PoseStyle.BIND.equals(style.id())) continue;
                    ToDoubleFunction<String> frame = style.frameAt(tick, form.periodTicks());
                    String where = form.label() + " '" + id + "' @" + tick;
                    Entity resolved = form.resolved();
                    Entity posed = PosePlayer.posed(resolved, style, form.periodTicks(), tick);
                    keepsItsScales(where, resolved.pose(), resolved.model(), posed.model(), frame, moved);
                    for (int index = 0; index < resolved.overlays().size(); index++) {
                        Entity.OverlayLayer rest = resolved.overlays().get(index);
                        Entity.OverlayLayer layer = posed.overlays().get(index);
                        String pass = where + " pass " + index;
                        keepsItsScales(pass, rest.pose(), rest.model(), layer.model(), frame, moved);
                        if (rest.noHatModel().isPresent())
                            keepsItsScales(pass + " no-hat", rest.pose(), rest.noHatModel().get(),
                                layer.noHatModel().orElseThrow(), frame, moved);
                    }
                    for (int index = 0; index < resolved.layers().equipment().size(); index++) {
                        Entity.EquipmentOverlay rest = resolved.layers().equipment().get(index);
                        keepsItsScales(where + " equip " + rest.slot(), rest.pose(), rest.model(),
                            posed.layers().equipment().get(index).model(), frame, moved);
                    }
                }
        // Stated before the run: the harnessed adult's body rides vanilla's 0.9375 as its pose scale,
        // which no clip gives it, and nothing else does.
        List<String> admitted = moved.stream().filter(PosePlayerStyleTest::isHarnessSqueeze).toList();
        assertFalse(admitted.isEmpty(), "the harnessed body carries the squeeze");
        assertTrue(admitted.stream().allMatch(entry -> entry.contains("'body' carries a pose scale of")),
            "the squeeze is the body's pose scale alone: " + admitted);
        moved.removeAll(admitted);
        assertEquals(List.of(), moved, "every posed bone keeps its rest and takes no pose scale but its clips'");
    }

    @Test
    @DisplayName("the one form resting any part's own scale field off one is the small armour stand, its head at three quarters and every other part at half")
    void theOnlyFieldOffOneIsTheSmallArmorStand() {
        // A whole-mesh factor rides vanilla's root and leaves every part's own field at one, and the
        // small stand alone is baked through the baby transform, which writes each top part's own
        // field instead. A mesh putting a factor on the root beside one on a part would read that
        // root as one and land its parts here, which is the shape the field read cannot tell.
        Map<String, List<String>> offOne = new LinkedHashMap<>();
        for (Form form : forms()) {
            Entity resolved = form.resolved();
            fieldsOffOne(form.label(), form.label(), resolved.model(), offOne);
            for (int index = 0; index < resolved.overlays().size(); index++) {
                Entity.OverlayLayer overlay = resolved.overlays().get(index);
                String pass = form.label() + " pass " + index;
                fieldsOffOne(form.label(), pass, overlay.model(), offOne);
                if (overlay.noHatModel().isPresent())
                    fieldsOffOne(form.label(), pass + " no-hat", overlay.noHatModel().get(), offOne);
            }
        }
        assertEquals(Set.of(SMALL_STAND), offOne.keySet(),
            "the small armour stand is the one form resting a field off one: " + offOne);

        EntityMesh small = AppearanceOptions.builder().size(Optional.of(Size.SMALL)).build()
            .resolve(subject("minecraft:armor_stand")).model();
        assertEquals(10, small.getBones().size(), "the small stand's ten parts");
        small.getBones().forEach((name, bone) ->
            assertEquals("head".equals(name) ? 0.75f : 0.5f, PosePlayer.authored(small, name, PoseChannel.X_SCALE),
                0f, "'" + name + "' rests its own field where the baby transform wrote it"));
    }

    @Test
    @DisplayName("a salmon of either non-default size poses, its ground frame placed where vanilla places it")
    void aFlattenedSalmonPosesAtVanillasGroundFrame() {
        Entity salmon = subject("minecraft:salmon");
        for (Size size : List.of(Size.SMALL, Size.LARGE)) {
            AppearanceOptions appearance = AppearanceOptions.builder().size(Optional.of(size)).build();
            Entity resolved = appearance.resolve(salmon);
            assertNotEquals(1f, resolved.model().getFlattenedScale(), size + " is a flattened mesh");
            int declared = resolved.model().getBones().size();
            for (String id : List.of(PoseStyle.IDLE, PoseStyle.STRIDE))
                for (int tick : TICKS) {
                    String where = size + " '" + id + "' @" + tick;
                    PoseStyle style = resolved.styles().resolve(id, appearance::applies, "minecraft:salmon");
                    EntityMesh posed = PosePlayer.posed(resolved, style, resolved.styles().periodTicks(), tick).model();
                    List<String> names = List.copyOf(posed.getBones().keySet());
                    assertEquals(declared + 4, names.size(), where + " seats the four container steps");
                    EntityMesh.Bone ground = posed.getBones().get(names.getLast());
                    assertEquals(-EntityMesh.FEET_ANCHOR, ground.getPivot().y(), where + " ground frame");
                    EntityMesh.Bone flop = posed.getBones().get(names.get(declared + 1));
                    assertEquals(0f, flop.getPivot().x(), where + " out-of-water translate x");
                    assertEquals(0f, flop.getPivot().y(), where + " out-of-water translate y");
                    resolved.model().getBones().forEach((name, bone) -> {
                        if (bone.getParent() == null)
                            assertEquals(names.getLast(), posed.getBones().get(name).getParent(),
                                where + " '" + name + "' hangs off the ground frame");
                    });
                }
        }
    }

    @Test
    @DisplayName("a harnessed happy ghast draws its body inside a harness drawn at full size")
    void aHarnessedGhastDrawsItsBodyInsideTheHarness() {
        // HappyGhastModel#setupAnim squeezes the body to 0.9375 while the body slot is filled, under a
        // root scaled by 4, so the body, its core and its tentacles draw at 3.75; the harness is a model
        // of its own, scaled by 4 and squeezed by nothing.
        Entity ghast = subject("minecraft:happy_ghast");
        AppearanceOptions harnessed = AppearanceOptions.builder().equipment(Map.of("body", "white_harness")).build();
        Entity resolved = harnessed.resolve(ghast);
        PoseStyle idle = resolved.styles().resolve(PoseStyle.IDLE, harnessed::applies, "minecraft:happy_ghast");
        Entity posed = PosePlayer.posed(resolved, idle, resolved.styles().periodTicks(), 0);

        assertEquals(new Vector3f(0.9375f, 0.9375f, 0.9375f), posed.model().getBones().get("body").getPoseScale(),
            "the body rides vanilla's squeeze");
        for (String bone : List.of("body", "inner_body", "tentacle0"))
            assertEquals(3.75f, drawnScale(posed.model(), bone), 1e-4f, "'" + bone + "' draws inside the harness");
        assertEquals(4f, drawnScale(posed.layers().equipment().getFirst().model(), "harness"), 1e-4f,
            "the harness draws at full size");

        Entity bare = AppearanceOptions.defaults().resolve(ghast);
        assertEquals(4f, drawnScale(PosePlayer.posed(bare, idle, bare.styles().periodTicks(), 0).model(), "body"),
            1e-4f, "an unharnessed ghast draws its body at full size");
    }

    // ------------------------------------------------------------------------------------

    /** Whether one recorded scale entry is the harnessed adult happy ghast's body squeeze. */
    private static boolean isHarnessSqueeze(@NotNull String entry) {
        return entry.startsWith(HARNESSED_GHAST + " '") && entry.contains("'body' ") && !entry.contains(" equip ")
            && entry.contains("0.9375");
    }

    private static @NotNull Entity subject(@NotNull String id) {
        Entity entity = entities.get(id);
        assertNotNull(entity, id + " is expected to load");
        return entity;
    }

    /**
     * Every form of every shipped row as a render resolves it - the row itself and each coat, and
     * for each the baby, every size and the large shape its axes carry.
     *
     * @return each form under the appearance selecting it, in load order
     */
    private static @NotNull List<Form> forms() {
        List<Form> forms = new ArrayList<>();
        for (Entity entity : entities.values()) {
            List<Optional<String>> coats = new ArrayList<>();
            coats.add(Optional.empty());
            entity.axes().variant().options().keySet().forEach(coat -> coats.add(Optional.of(coat)));
            for (Optional<String> coat : coats) {
                Entity form = coat.flatMap(entity.axes().variant()::select).orElse(entity);
                String label = entity.id().id() + coat.map(key -> " variant=" + key).orElse("");
                Map<String, AppearanceOptions> appearances = new LinkedHashMap<>();
                appearances.put(label, AppearanceOptions.builder().variant(coat).build());
                if (form.axes().baby().isPresent())
                    appearances.put(label + " age=baby", AppearanceOptions.builder().variant(coat).age(Age.BABY).build());
                for (Size size : form.axes().size().options().keySet())
                    appearances.put(label + " size=" + size.name().toLowerCase(Locale.ROOT),
                        AppearanceOptions.builder().variant(coat).size(Optional.of(size)).build());
                if (form.axes().shape().select(Entity.SHAPE_LARGE).isPresent())
                    appearances.put(label + " pattern=flopper", AppearanceOptions.builder()
                        .variant(coat).pattern(Optional.of(TropicalFishPattern.FLOPPER)).build());
                // Each slot filled with its default material, which is what poses its layer and what
                // swaps a wearer pose in.
                for (Entity.EquipmentOverlay equipment : form.layers().equipment())
                    appearances.put(label + " equip=" + equipment.slot(), AppearanceOptions.builder()
                        .variant(coat).equipment(Map.of(equipment.slot(), "")).build());
                appearances.forEach((formLabel, appearance) ->
                    forms.add(new Form(formLabel, entity.id().id(), appearance, appearance.resolve(entity))));
            }
        }
        return forms;
    }

    /**
     * Whether one mesh's pose writes any scale channel, recording each one that is not bit for bit
     * the value its bone's own field rests at.
     *
     * @param where how a failure names the mesh, style and tick
     * @param pose the pose belonging to the mesh
     * @param mesh the mesh posed
     * @param frame what each render-state figure reads as at that tick
     * @param offRest where each write off its rest is recorded
     * @return whether the pose writes any scale channel at all
     */
    private static boolean writesScale(
        @NotNull String where, @NotNull EntityPose pose, @NotNull EntityMesh mesh,
        @NotNull ToDoubleFunction<String> frame, @NotNull List<String> offRest) {

        boolean scaled = false;
        Map<String, Map<PoseChannel, Float>> writes = PosePlayer.evaluate(pose, mesh, frame).bones();
        for (Map.Entry<String, Map<PoseChannel, Float>> bone : writes.entrySet())
            for (Map.Entry<PoseChannel, Float> written : bone.getValue().entrySet()) {
                if (written.getKey().kind() != PoseChannel.Kind.SCALE) continue;
                scaled = true;
                float rest = PosePlayer.authored(mesh, bone.getKey(), written.getKey());
                if (Float.floatToRawIntBits(written.getValue()) != Float.floatToRawIntBits(rest))
                    offRest.add(where + " '" + bone.getKey() + "' " + written.getKey().token() + " = "
                        + written.getValue() + " over a field rest of " + rest);
            }
        return scaled;
    }

    /**
     * Records every bone of one mesh whose own scale field rests anywhere but bit for bit at one,
     * under the form it belongs to.
     *
     * @param label the form the mesh belongs to, which the record keys on
     * @param where how the record names the mesh
     * @param mesh the mesh as it was loaded
     * @param offOne where each field off one is recorded
     */
    private static void fieldsOffOne(
        @NotNull String label, @NotNull String where, @NotNull EntityMesh mesh,
        @NotNull Map<String, List<String>> offOne) {

        mesh.getBones().keySet().forEach(name -> {
            float field = PosePlayer.authored(mesh, name, PoseChannel.X_SCALE);
            if (Float.floatToRawIntBits(field) != Float.floatToRawIntBits(1f))
                offOne.computeIfAbsent(label, form -> new ArrayList<>())
                    .add(where + " '" + name + "' = " + field);
        });
    }

    /**
     * Records each bone of one posed mesh whose rest scale moved, or whose pose scale is anything
     * but what its clips alone give it - the very instance it was loaded with where no clip scales
     * it at that tick, and its field's rest plus the displacement over that rest on each axis where
     * one does.
     *
     * @param where how a failure names the mesh, style and tick
     * @param pose the pose belonging to the mesh
     * @param rest the mesh as it was loaded
     * @param posed the mesh as the pose leaves it
     * @param frame what each render-state figure reads as at that tick
     * @param moved where each bone off its expected scales is recorded
     */
    private static void keepsItsScales(
        @NotNull String where, @NotNull EntityPose pose, @NotNull EntityMesh rest,
        @NotNull EntityMesh posed, @NotNull ToDoubleFunction<String> frame, @NotNull List<String> moved) {

        ClipPlayer.Displacement displaced = ClipPlayer.deltas(pose, rest, frame);
        rest.getBones().forEach((name, loaded) -> {
            EntityMesh.Bone bone = posed.getBones().get(name);
            if (Float.floatToRawIntBits(bone.getScale()) != Float.floatToRawIntBits(loaded.getScale()))
                moved.add(where + " '" + name + "' rests at " + bone.getScale() + " where it loaded at "
                    + loaded.getScale());
            Map<PoseChannel, Float> delta = displaced.of(name);
            float x = delta.getOrDefault(PoseChannel.X_SCALE, 0f);
            float y = delta.getOrDefault(PoseChannel.Y_SCALE, 0f);
            float z = delta.getOrDefault(PoseChannel.Z_SCALE, 0f);
            if (x == 0f && y == 0f && z == 0f) {
                if (bone.getPoseScale() != loaded.getPoseScale())
                    moved.add(where + " '" + name + "' carries a pose scale of " + bone.getPoseScale()
                        + " that no clip gives it");
                return;
            }
            float field = PosePlayer.authored(rest, name, PoseChannel.X_SCALE);
            Vector3f clipped = new Vector3f((field + x) / field, (field + y) / field, (field + z) / field);
            if (!bone.getPoseScale().equals(clipped))
                moved.add(where + " '" + name + "' carries a pose scale of " + bone.getPoseScale()
                    + " where its clips give it " + clipped + " over a field rest of " + field);
        });
    }

    /**
     * One form of a shipped row, resolved as a render resolves it.
     *
     * @param label how a failure names the form
     * @param entityId the shipped row's id, which a catalog resolve reports under
     * @param appearance the appearance selecting the form
     * @param resolved the subject that appearance resolves to
     */
    private record Form(@NotNull String label, @NotNull String entityId,
                        @NotNull AppearanceOptions appearance, @NotNull Entity resolved) {

        /** Every style id the form's in-force catalog lists, then idle and stride. */
        @NotNull Set<String> styleIds() {
            Set<String> ids = new LinkedHashSet<>(this.resolved.styles().ids());
            ids.add(PoseStyle.IDLE);
            ids.add(PoseStyle.STRIDE);
            return ids;
        }

        /** The row the form's in-force catalog answers for one style id. */
        @NotNull PoseStyle style(@NotNull String id) {
            return this.resolved.styles().resolve(id, this.appearance::applies, this.entityId);
        }

        /** The ticks one whole excursion spans in the form's catalog. */
        int periodTicks() {
            return this.resolved.styles().periodTicks();
        }

    }

}
