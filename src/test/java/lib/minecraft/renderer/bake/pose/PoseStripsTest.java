package lib.minecraft.renderer.bake.pose;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.mesh.EntityMesh;
import lib.minecraft.renderer.asset.mesh.TextureSize;
import lib.minecraft.renderer.asset.pose.EntityPose;
import lib.minecraft.renderer.asset.pose.PoseStyle;
import lib.minecraft.renderer.asset.pose.StyleCatalog;
import lib.minecraft.renderer.asset.pose.StyleClock;
import lib.minecraft.renderer.call.request.AppearanceOptions;
import lib.minecraft.renderer.content.index.EntityModelLoader;
import lib.minecraft.renderer.content.index.StripMemo;
import lib.minecraft.renderer.engine.draw.PassDeclaration;
import lib.minecraft.renderer.engine.frame.StripFit;
import lib.minecraft.renderer.engine.frame.StripTrack;
import lib.minecraft.renderer.engine.geometry.EulerRotation;
import lib.minecraft.renderer.engine.math.Vector2f;
import lib.minecraft.renderer.engine.math.Vector3f;
import lib.minecraft.renderer.engine.pose.PoseChannel;
import lib.minecraft.renderer.engine.pose.PoseExpr;
import lib.minecraft.renderer.engine.pose.PoseOperator;
import lib.minecraft.renderer.engine.pose.PoseWidth;
import lib.minecraft.renderer.engine.pose.StyleDriver;
import lib.minecraft.renderer.exception.RenderException;
import lib.minecraft.renderer.fixture.RegistrarFixtures;
import lib.minecraft.renderer.vanilla.appearance.Age;
import lib.minecraft.renderer.vanilla.appearance.Size;
import lib.minecraft.renderer.vanilla.appearance.TropicalFishPattern;
import lib.minecraft.renderer.vanilla.equipment.LayerType;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The strip a resolved row is drawn over, fitted once per key and held by the memo it was asked under.
 *
 * <p>The key is what is pinned: a row compared by value, since every resolve rebuilds a narrowed
 * one; every pose the sampler reads compared by instance; every mesh it reads compared by what a
 * sampling reads off it - the bones it declares, and each one's pivot, rotation, rest scale and
 * parent - since one pose drawn over two meshes samples two sets of tracks, where a mesh a resolve
 * builds afresh reads as the one before it; which passes are walked; each equipment layer's slot,
 * which names the channels the layer is sampled under; and the two things a pass moves by without a
 * pose of its own - a scroll in the texels of its sheet, and an opacity - each of which fits a
 * different strip on its own. Each mesh read, the walk, the slot, the scroll rate, the sheet's size
 * and the opacity is shown on its own, by two subjects sharing every other component and keying
 * apart over the one they differ in, and a mesh differing only where nothing reads it keys with the
 * mesh it copies. Over the shipped corpus, every appearance resolved twice, the key is sufficient:
 * two resolved subjects it keys equal sample equal tracks. A row nothing moves never reaches the key
 * at all, and a fit is shared by the asks of one memo and by no other.
 */
@DisplayName("the fitted strip of a resolved row, held by the memo it was asked under")
class PoseStripsTest {

    private static ConcurrentMap<String, Entity> entities;

    /** The memo every fit one test asks for is held in, fresh for each test. */
    private final @NotNull StripMemo strips = new StripMemo();

    @BeforeAll
    static void load() {
        entities = EntityModelLoader.load();
    }

    @Test
    @DisplayName("a row is fitted once, and every later ask answers that fit")
    void oneFitPerKey() {
        Entity zombie = AppearanceOptions.defaults().resolve(definition("minecraft:zombie"));
        PoseStyle stride = row(zombie, PoseStyle.STRIDE);
        int period = zombie.styles().periodTicks();

        StripFit first = PoseStrips.of(this.strips, zombie, stride, period);
        assertSame(first, PoseStrips.of(this.strips, zombie, stride, period), "the second ask answers the first fit itself");
        assertEquals(StripFit.fit(StripSampler.tracks(zombie, stride, period)), first,
            "which is the fit of the row's sampled tracks");
    }

    @Test
    @DisplayName("a fit is held by the memo it was asked under, and another memo fits the row afresh")
    void aFitIsHeldByItsMemo() {
        // A memo is held by the context owning the definitions, so a second context asking for the
        // same row takes a fit of its own rather than reaching one another context holds.
        Entity zombie = AppearanceOptions.defaults().resolve(definition("minecraft:zombie"));
        PoseStyle stride = row(zombie, PoseStyle.STRIDE);
        int period = zombie.styles().periodTicks();

        StripFit held = PoseStrips.of(this.strips, zombie, stride, period);
        StripFit afresh = PoseStrips.of(new StripMemo(), zombie, stride, period);
        assertNotSame(held, afresh, "another memo does not answer the first memo's fit");
        assertEquals(held, afresh, "and fits the same strip");
        assertSame(held, PoseStrips.of(this.strips, zombie, stride, period), "while the first memo still answers its own");
    }

    @Test
    @DisplayName("two resolves narrowing a row equally share one fit")
    void twoEqualNarrowedRowsShareOneFit() {
        // An uncharged creeper's stride loses the swirl's gated scroll entry, and the in-force view
        // rebuilds the row on every resolve - two instances of one value.
        Entity creeper = definition("minecraft:creeper");
        Entity first = AppearanceOptions.defaults().resolve(creeper);
        Entity second = AppearanceOptions.defaults().resolve(creeper);
        PoseStyle firstRow = row(first, PoseStyle.STRIDE);
        PoseStyle secondRow = row(second, PoseStyle.STRIDE);
        assertNotSame(firstRow, secondRow, "each resolve narrows the stride afresh");
        assertEquals(firstRow, secondRow, "to one value");
        assertTrue(firstRow.moves(), "which still walks");

        assertSame(PoseStrips.of(this.strips, first, firstRow, first.styles().periodTicks()),
            PoseStrips.of(this.strips, second, secondRow, second.styles().periodTicks()),
            "so both resolves meet one fit");
    }

    @Test
    @DisplayName("two resolves of one toggled appearance share one fit")
    void twoToggledResolvesShareOneFit() {
        // A selected toggle flips the bones it names onto a mesh every resolve builds afresh - the
        // bogged's body when it is sheared, the camel's saddle when it is ridden - and a bone is read
        // whether or not it draws, so the second resolve reads as the first.
        oneFitAcrossResolves("minecraft:bogged", AppearanceOptions.builder().sheared(true).build(), Entity::model);
        oneFitAcrossResolves("minecraft:camel", AppearanceOptions.builder().toggles(Set.of("ridden")).build(),
            subject -> subject.layers().equipment().getFirst().model());
    }

    @Test
    @DisplayName("a row with no source in force is still, and is never sampled")
    void aSourcelessRowIsStill() {
        // The pose reads a bone its mesh does not declare, so sampling it throws: a still answer is
        // proof that nothing was sampled.
        Map<PoseChannel, PoseExpr> broken = new EnumMap<>(PoseChannel.class);
        broken.put(PoseChannel.X_ROT, new PoseExpr.BoneRead("missing", PoseChannel.X_ROT));
        Entity subject = Entity.builder()
            .id(ResourceId.parse("minecraft:test"))
            .model(mesh(TextureSize.DEFAULT, "body"))
            .pose(new EntityPose(Concurrent.newUnmodifiableList(),
                Concurrent.newUnmodifiableMap(Map.of("body", broken)), Concurrent.newUnmodifiableList(),
                Optional.empty()))
            .build();
        PoseStyle idle = StyleCatalog.BIND_ONLY.resolve(PoseStyle.IDLE, any -> true, "minecraft:test");
        int period = subject.styles().periodTicks();

        assertTrue(idle.sources().isEmpty(), "the universal standing row names no source");
        assertThrows(RenderException.class, () -> StripSampler.tracks(subject, idle, period),
            "sampling the subject would throw");
        assertSame(StripFit.STILL, PoseStrips.of(this.strips, subject, idle, period), "so the still strip is answered unsampled");
    }

    @Test
    @DisplayName("two subjects sharing every pose but drawing it over two meshes fit apart")
    void twoMeshesUnderOnePoseFitApart() {
        // A pose writes only the bones the mesh it is drawn on declares, so an arm swinging slower
        // than the body lengthens the strip of the mesh that has the arm and leaves the armless one's
        // short. The two subjects differ in the bones the body's mesh declares and in nothing else.
        EntityPose pose = armSwing();
        Entity armless = posed(pose, mesh(TextureSize.DEFAULT, "body"));
        Entity armed = posed(pose, mesh(TextureSize.DEFAULT, "body", "arm"));
        PoseStyle row = style(StyleClock.TICK, new StyleDriver("ageInTicks", StyleDriver.Wave.Plain.RAMP, 0f, 1f, Optional.empty()));
        int period = armless.styles().periodTicks();

        StripFit armlessFit = PoseStrips.of(this.strips, armless, row, period);
        StripFit armedFit = PoseStrips.of(this.strips, armed, row, period);
        assertEquals(StripFit.fit(StripSampler.tracks(armed, row, period)), armedFit,
            "the armed mesh answers its own fit");
        assertNotEquals(armlessFit, armedFit, "which is not the armless mesh's");
        assertNotEquals(PoseStrips.Key.of(armless, row, period), PoseStrips.Key.of(armed, row, period),
            "because the two meshes key apart");
    }

    @Test
    @DisplayName("two meshes of three bones key apart on each value a sampling reads off one bone")
    void eachReadBoneValueKeysApart() {
        // A pose finds a bone by its name, and a channel read answers off the bone's pivot, its
        // rotation, its rest scale and the scale of the parent it hangs from, so a pose building on
        // what it reads samples other tracks wherever one of those differs. Each altered mesh parts
        // from the plain one in one value of one bone.
        Map<String, Map<PoseChannel, PoseExpr>> bones = new LinkedHashMap<>();
        Map<PoseChannel, PoseExpr> body = new EnumMap<>(PoseChannel.class);
        body.put(PoseChannel.Y, op(PoseOperator.ADD, wave(0.4f), new PoseExpr.BoneRead("body", PoseChannel.Y)));
        body.put(PoseChannel.X_ROT, op(PoseOperator.ADD, wave(0.4f), new PoseExpr.BoneRead("body", PoseChannel.X_ROT)));
        bones.put("body", body);
        Map<PoseChannel, PoseExpr> arm = new EnumMap<>(PoseChannel.class);
        arm.put(PoseChannel.X_ROT, op(PoseOperator.ADD, wave(0.05f), new PoseExpr.BoneRead("arm", PoseChannel.X_SCALE)));
        bones.put("arm", arm);
        EntityPose pose = new EntityPose(Concurrent.newUnmodifiableList(), Concurrent.newUnmodifiableLinkedMap(bones),
            Concurrent.newUnmodifiableList(), Optional.empty());
        PoseStyle row = style(StyleClock.TICK, new StyleDriver("ageInTicks", StyleDriver.Wave.Plain.RAMP, 0f, 1f, Optional.empty()));

        Entity plain = posed(pose, skeleton(Vector3f.ZERO, EulerRotation.NONE, "arm", 1f, "body"));
        Map<String, Entity> altered = new LinkedHashMap<>();
        altered.put("the body's pivot", posed(pose, skeleton(new Vector3f(0f, 2f, 0f), EulerRotation.NONE, "arm", 1f, "body")));
        altered.put("the body's rotation",
            posed(pose, skeleton(Vector3f.ZERO, new EulerRotation(10f, 0f, 0f), "arm", 1f, "body")));
        altered.put("the arm's name", posed(pose, skeleton(Vector3f.ZERO, EulerRotation.NONE, "leg", 1f, "body")));
        altered.put("the arm's rest scale", posed(pose, skeleton(Vector3f.ZERO, EulerRotation.NONE, "arm", 3f, "body")));
        altered.put("the arm's parent", posed(pose, skeleton(Vector3f.ZERO, EulerRotation.NONE, "arm", 1f, "head")));
        int period = plain.styles().periodTicks();
        List<StripTrack> plainTracks = StripSampler.tracks(plain, row, period);
        for (Map.Entry<String, Entity> other : altered.entrySet()) {
            assertFalse(sameTracks(plainTracks, StripSampler.tracks(other.getValue(), row, period)),
                "a mesh altering " + other.getKey() + " samples other tracks");
            assertNotEquals(PoseStrips.Key.of(plain, row, period), PoseStrips.Key.of(other.getValue(), row, period),
                "so a mesh altering " + other.getKey() + " keys apart");
        }
    }

    @Test
    @DisplayName("two meshes reading equal wherever a sampling reads them key together, however else they differ")
    void equalReadsKeyTogether() {
        // A bone is sampled whether or not it draws, and nothing sampled reads a cube, the sheet of a
        // pass that scrolls none, or whether a mesh culls - so a mesh built afresh, one hiding a bone,
        // one owning other cubes, one over another sheet and one culling all read as the plain one and
        // meet its fit.
        EntityPose pose = armSwing();
        PoseStyle row = style(StyleClock.TICK, new StyleDriver("ageInTicks", StyleDriver.Wave.Plain.RAMP, 0f, 1f, Optional.empty()));
        Entity plain = posed(pose, mesh(TextureSize.DEFAULT, "body", "arm"));
        int period = plain.styles().periodTicks();

        EntityMesh hidden = mesh(TextureSize.DEFAULT, "body", "arm");
        hidden.getBones().put("arm", hidden.getBones().get("arm").withVisible(false));
        EntityMesh recubed = mesh(TextureSize.DEFAULT, "body", "arm");
        recubed.getBones().put("arm", recubed.getBones().get("arm").withCubes(Concurrent.newList()));
        Map<String, EntityMesh> others = new LinkedHashMap<>();
        others.put("a copy", mesh(TextureSize.DEFAULT, "body", "arm"));
        others.put("a mesh hiding the arm", hidden);
        others.put("a mesh owning no arm cube", recubed);
        others.put("a mesh over another sheet", mesh(new TextureSize(128, 64), "body", "arm"));
        others.put("a culling mesh",
            new EntityMesh(TextureSize.DEFAULT, mesh(TextureSize.DEFAULT, "body", "arm").getBones(), true));

        StripFit fit = PoseStrips.of(this.strips, plain, row, period);
        List<StripTrack> plainTracks = StripSampler.tracks(plain, row, period);
        for (Map.Entry<String, EntityMesh> other : others.entrySet()) {
            Entity subject = posed(pose, other.getValue());
            assertNotSame(plain.model(), subject.model(), other.getKey() + " is a mesh of its own");
            assertTrue(sameTracks(plainTracks, StripSampler.tracks(subject, row, period)),
                other.getKey() + " samples the plain mesh's tracks");
            assertEquals(PoseStrips.Key.of(plain, row, period), PoseStrips.Key.of(subject, row, period),
                "so " + other.getKey() + " keys with the plain mesh");
            assertSame(fit, PoseStrips.of(this.strips, subject, row, period), "and meets its fit");
        }
    }

    @Test
    @DisplayName("a pass redrawing the body's own mesh keys apart from one drawing a copy of it")
    void aRedrawnBodyKeysApartFromACopy() {
        // A pass drawing the body's own mesh under the body's own pose writes the body's channels a
        // second time and is not walked, where a pass drawing a copy of that mesh is walked and
        // samples channels of its own. The two subjects read equal everywhere else.
        EntityPose pose = armSwing();
        EntityMesh body = mesh(TextureSize.DEFAULT, "body", "arm");
        Entity redrawn = overlaid(pose, body, body);
        Entity copied = overlaid(pose, body, mesh(TextureSize.DEFAULT, "body", "arm"));
        PoseStyle row = style(StyleClock.TICK, new StyleDriver("ageInTicks", StyleDriver.Wave.Plain.RAMP, 0f, 1f, Optional.empty()));
        int period = redrawn.styles().periodTicks();

        assertFalse(sameTracks(StripSampler.tracks(redrawn, row, period), StripSampler.tracks(copied, row, period)),
            "the copy is walked as a pass of its own");
        assertNotEquals(PoseStrips.Key.of(redrawn, row, period), PoseStrips.Key.of(copied, row, period),
            "so the two key apart");
    }

    @Test
    @DisplayName("every two resolved subjects of the shipped corpus keyed equal sample equal tracks")
    void equalKeysSampleEqualTracks() {
        // The key is sufficient where nothing the sampler reads differs between two subjects it keys
        // equal. Each definition is resolved twice under every appearance it offers, at every row it
        // moves under, and a subject meeting a key already asked is sampled against the first to ask
        // it. A toggled appearance builds its meshes afresh on each resolve, so its second resolve
        // meets the first over meshes of its own.
        Map<PoseStrips.Key, Asked> first = new HashMap<>();
        List<String> parted = new ArrayList<>();
        int asked = 0;
        int met = 0;
        int metAfresh = 0;
        for (Entity definition : entities.values())
            for (Map.Entry<String, AppearanceOptions> appearance : appearancesOf(definition).entrySet())
                for (int resolve = 1; resolve <= 2; resolve++) {
                    Entity subject = appearance.getValue().resolve(definition);
                    int period = subject.styles().periodTicks();
                    for (String id : definition.styles().ids()) {
                        PoseStyle style;
                        try {
                            style = subject.styles().resolve(id, appearance.getValue()::applies, definition.id().toString());
                        } catch (RenderException refused) {
                            continue;
                        }
                        if (!style.moves()) continue;
                        asked++;
                        Asked ask = new Asked(definition.id() + " " + appearance.getKey() + " resolve " + resolve
                            + " '" + id + "'", subject, style, period);
                        Asked earlier = first.putIfAbsent(PoseStrips.Key.of(subject, style, period), ask);
                        if (earlier == null) continue;
                        met++;
                        if (!sameMeshes(earlier.subject(), subject)) metAfresh++;
                        if (!sameTracks(earlier.tracks(), ask.tracks())) parted.add(earlier.label() + " / " + ask.label());
                    }
                }

        assertTrue(asked > 400, "the walk asks for the corpus's moving rows twice, " + asked + " asks");
        assertTrue(met > 300, "and meets an earlier key often enough to compare, " + met + " times over "
            + first.size() + " keys");
        assertTrue(metAfresh > 20, "and meets one over mesh instances of its own " + metAfresh + " times of them");
        assertTrue(parted.isEmpty(), parted.size() + " pairs keyed equal sample different tracks: " + parted);
    }

    @Test
    @DisplayName("two subjects sharing a pass's pose and scroll rate over sheets of two sizes fit apart")
    void twoSheetSizesFitApart() {
        // A scroll is sampled in its own sheet's texels and comes round at the sheet's extent, so one
        // rate over a wider sheet jumps further at the restart. A sheet's size is read for a pass that
        // scrolls and nowhere else, so the two meshes read equal everywhere a pose reads them and it is
        // the sheet sizes that part the two keys.
        EntityPose pose = EntityPose.NONE;
        Vector2f rate = new Vector2f(0.0185f, 0f);
        Entity narrow = scrolling(pose, rate, mesh(new TextureSize(16, 16), "body"));
        Entity wide = scrolling(pose, rate, mesh(new TextureSize(64, 32), "body"));
        PoseStyle row = style(StyleClock.SCROLL);
        int period = narrow.styles().periodTicks();

        StripFit narrowFit = PoseStrips.of(this.strips, narrow, row, period);
        StripFit wideFit = PoseStrips.of(this.strips, wide, row, period);
        assertEquals(StripFit.fit(StripSampler.tracks(wide, row, period)), wideFit,
            "the wide sheet answers its own fit");
        assertNotEquals(narrowFit, wideFit, "which is not the narrow sheet's");
        assertNotEquals(PoseStrips.Key.of(narrow, row, period), PoseStrips.Key.of(wide, row, period),
            "because the two sheets key apart");
    }

    @Test
    @DisplayName("two subjects sharing a pass's mesh and pose but scrolling it at two rates fit apart")
    void twoScrollRatesFitApart() {
        // A scroll repeats on the inverse of its rate, so a slower one over the same sheet asks for a
        // longer strip. The two subjects share every pose and mesh instance and differ in the rate.
        EntityPose pose = EntityPose.NONE;
        EntityMesh mesh = mesh(new TextureSize(64, 32), "body");
        Entity slow = scrolling(pose, new Vector2f(0.0185f, 0f), mesh);
        Entity fast = scrolling(pose, new Vector2f(0.025f, 0f), mesh);
        PoseStyle row = style(StyleClock.SCROLL);
        int period = slow.styles().periodTicks();

        StripFit slowFit = PoseStrips.of(this.strips, slow, row, period);
        StripFit fastFit = PoseStrips.of(this.strips, fast, row, period);
        assertEquals(StripFit.fit(StripSampler.tracks(fast, row, period)), fastFit,
            "the fast scroll answers its own fit");
        assertNotEquals(slowFit, fastFit, "which is not the slow scroll's");
        assertNotEquals(PoseStrips.Key.of(slow, row, period), PoseStrips.Key.of(fast, row, period),
            "because the two rates key apart");
    }

    @Test
    @DisplayName("two subjects sharing every pose but one pass's opacity program fit apart")
    void twoOpacityProgramsFitApart() {
        // An opacity sets how long a strip runs, so a glow pulsing on a period of its own lengthens
        // the strip to show the pulse whole. The two subjects share every pose and mesh instance and
        // differ in the opacity.
        EntityPose pose = EntityPose.NONE;
        EntityMesh mesh = mesh(TextureSize.DEFAULT, "body");
        Entity slow = glowing(pose, mesh, pulse(0.045f));
        Entity fast = glowing(pose, mesh, pulse(0.1f));
        PoseStyle row = style(StyleClock.TICK, new StyleDriver("ageInTicks", StyleDriver.Wave.Plain.RAMP, 0f, 1f, Optional.empty()));
        int period = slow.styles().periodTicks();

        StripFit slowFit = PoseStrips.of(this.strips, slow, row, period);
        StripFit fastFit = PoseStrips.of(this.strips, fast, row, period);
        assertEquals(StripFit.fit(StripSampler.tracks(fast, row, period)), fastFit,
            "the fast pulse answers its own fit");
        assertNotEquals(slowFit, fastFit, "which is not the slow pulse's");
        assertNotEquals(PoseStrips.Key.of(slow, row, period), PoseStrips.Key.of(fast, row, period),
            "because the two opacities key apart");
    }

    @Test
    @DisplayName("two subjects wearing one equipment mesh twice under one pose, in two slots and in one, fit apart")
    void twoSlotsFitApart() {
        // A layer's slot names the channels it is sampled under, and two layers sampled under one
        // channel sum there. The two subjects share every pose and mesh instance and differ in the
        // second layer's slot, so one swings two legs apart and the other one leg twice as far.
        EntityMesh body = mesh(TextureSize.DEFAULT, "body");
        EntityMesh worn = mesh(TextureSize.DEFAULT, "leg");
        Map<String, Map<PoseChannel, PoseExpr>> bones = new LinkedHashMap<>();
        bones.put("leg", swing(0.05f));
        EntityPose pose = new EntityPose(Concurrent.newUnmodifiableList(), Concurrent.newUnmodifiableLinkedMap(bones),
            Concurrent.newUnmodifiableList(), Optional.empty());
        Entity apart = equipped(body, worn, pose, "saddle", "body");
        Entity together = equipped(body, worn, pose, "saddle", "saddle");
        PoseStyle row = style(StyleClock.TICK, new StyleDriver("ageInTicks", StyleDriver.Wave.Plain.RAMP, 0f, 1f, Optional.empty()));
        int period = apart.styles().periodTicks();

        StripFit apartFit = PoseStrips.of(this.strips, apart, row, period);
        StripFit togetherFit = PoseStrips.of(this.strips, together, row, period);
        assertEquals(StripFit.fit(StripSampler.tracks(together, row, period)), togetherFit,
            "the shared slot answers its own fit");
        assertNotEquals(apartFit, togetherFit, "which is not the two slots' fit");
        assertNotEquals(PoseStrips.Key.of(apart, row, period), PoseStrips.Key.of(together, row, period),
            "because the two slot lists key apart");
    }

    // ------------------------------------------------------------------------------------

    /**
     * One resolved row asked for, as the memo would be asked for it.
     *
     * @param label how a failure names the ask
     * @param subject the resolved subject
     * @param style the resolved row
     * @param period the figure period the row frames at where it declares none of its own
     */
    private record Asked(@NotNull String label, @NotNull Entity subject, @NotNull PoseStyle style, int period) {

        /** Samples the row's tracks. */
        @NotNull List<StripTrack> tracks() {
            return StripSampler.tracks(this.subject, this.style, this.period);
        }

    }

    private static @NotNull Entity definition(@NotNull String id) {
        Entity definition = entities.get(id);
        assertNotNull(definition, id + " is expected to load");
        return definition;
    }

    /**
     * Resolves one appearance of a definition twice and asks for its stride under each, holding that
     * the mesh the appearance flips is built afresh on each resolve and that both asks meet one fit.
     */
    private static void oneFitAcrossResolves(
        @NotNull String id, @NotNull AppearanceOptions appearance, @NotNull Function<Entity, EntityMesh> flipped) {

        Entity definition = definition(id);
        Entity first = appearance.resolve(definition);
        Entity second = appearance.resolve(definition);
        assertNotSame(flipped.apply(first), flipped.apply(second), id + " flips its bones onto a mesh of each resolve's own");

        PoseStyle firstRow = first.styles().resolve(PoseStyle.STRIDE, appearance::applies, id);
        PoseStyle secondRow = second.styles().resolve(PoseStyle.STRIDE, appearance::applies, id);
        assertTrue(firstRow.moves(), id + " walks");
        int period = first.styles().periodTicks();
        assertEquals(PoseStrips.Key.of(first, firstRow, period), PoseStrips.Key.of(second, secondRow, period),
            id + "'s two resolves key equal");
        StripMemo strips = new StripMemo();
        assertSame(PoseStrips.of(strips, first, firstRow, period), PoseStrips.of(strips, second, secondRow, period),
            "so the second resolve of " + id + " meets the first one's fit");
    }

    /** Tests whether two subjects draw the very same mesh instances as their body, passes and equipment. */
    private static boolean sameMeshes(@NotNull Entity first, @NotNull Entity second) {
        if (first.model() != second.model()) return false;
        if (first.overlays().size() != second.overlays().size()) return false;
        for (int index = 0; index < first.overlays().size(); index++)
            if (first.overlays().get(index).model() != second.overlays().get(index).model()) return false;

        if (first.layers().equipment().size() != second.layers().equipment().size()) return false;
        for (int index = 0; index < first.layers().equipment().size(); index++)
            if (first.layers().equipment().get(index).model() != second.layers().equipment().get(index).model()) return false;

        return true;
    }

    /** A resolved subject's in-force row of one id, as an adult default render resolves it. */
    private static @NotNull PoseStyle row(@NotNull Entity subject, @NotNull String id) {
        return subject.styles().resolve(id, AppearanceOptions.defaults()::applies, subject.id().toString());
    }

    /**
     * Every appearance one definition is resolved under, by how a failure names it - each age it
     * carries, and at the adult each coat, size and shape it offers, each equipment slot filled on its
     * own, every bone toggle its meshes name, sheared and charged.
     */
    private static @NotNull Map<String, AppearanceOptions> appearancesOf(@NotNull Entity definition) {
        Map<String, AppearanceOptions> appearances = new LinkedHashMap<>();
        appearances.put("adult", AppearanceOptions.builder().age(Age.ADULT).build());
        if (definition.axes().baby().isPresent())
            appearances.put("baby", AppearanceOptions.builder().age(Age.BABY).build());
        for (String coat : definition.axes().variant().options().keySet())
            appearances.put("variant=" + coat, AppearanceOptions.builder().variant(coat).build());
        for (Size size : definition.axes().size().options().keySet())
            appearances.put("size=" + size, AppearanceOptions.builder().size(size).build());
        if (definition.axes().shape().options().containsKey(Entity.SHAPE_LARGE))
            appearances.put("pattern=flopper", AppearanceOptions.builder().pattern(TropicalFishPattern.FLOPPER).build());
        for (Entity.EquipmentOverlay equipment : definition.layers().equipment())
            appearances.put("equipment=" + equipment.slot(), AppearanceOptions.builder().equipment(Map.of(equipment.slot(), "")).build());

        Set<String> toggles = new LinkedHashSet<>();
        togglesOf(definition.model(), toggles);
        for (Entity.EquipmentOverlay equipment : definition.layers().equipment())
            togglesOf(equipment.model(), toggles);
        if (!toggles.isEmpty())
            appearances.put("toggles=" + toggles, AppearanceOptions.builder().toggles(toggles).build());
        appearances.put("sheared", AppearanceOptions.builder().sheared(true).build());
        appearances.put("charged", AppearanceOptions.builder().charged(true).build());
        return appearances;
    }

    /** Adds every toggle one mesh's bones name. */
    private static void togglesOf(@NotNull EntityMesh mesh, @NotNull Set<String> toggles) {
        for (EntityMesh.Bone bone : mesh.getBones().values())
            if (bone.getToggle() != null) toggles.add(bone.getToggle());
    }

    /** Tests whether two samplings hold the same tracks in the same order, every sample bit for bit. */
    private static boolean sameTracks(@NotNull List<StripTrack> first, @NotNull List<StripTrack> second) {
        if (first.size() != second.size()) return false;
        for (int index = 0; index < first.size(); index++) {
            StripTrack one = first.get(index);
            StripTrack other = second.get(index);
            boolean same = one.channel().equals(other.channel()) && one.kind() == other.kind()
                && one.role().equals(other.role()) && one.wrap().equals(other.wrap())
                && Arrays.equals(one.values(), other.values());
            if (!same) return false;
        }
        return true;
    }

    /** A subject drawing its body mesh under a pose, with no pass and no equipment. */
    private static @NotNull Entity posed(@NotNull EntityPose pose, @NotNull EntityMesh mesh) {
        return Entity.builder()
            .id(ResourceId.parse("minecraft:test"))
            .model(mesh)
            .pose(pose)
            .build();
    }

    /** A pose swinging a body quickly and an arm slowly about x. */
    private static @NotNull EntityPose armSwing() {
        Map<String, Map<PoseChannel, PoseExpr>> bones = new LinkedHashMap<>();
        bones.put("body", swing(0.4f));
        bones.put("arm", swing(0.05f));
        return new EntityPose(Concurrent.newUnmodifiableList(), Concurrent.newUnmodifiableLinkedMap(bones),
            Concurrent.newUnmodifiableList(), Optional.empty());
    }

    /** A bone swinging about x on elapsed age at a rate, half a radian either way. */
    private static @NotNull Map<PoseChannel, PoseExpr> swing(float rate) {
        Map<PoseChannel, PoseExpr> channels = new EnumMap<>(PoseChannel.class);
        channels.put(PoseChannel.X_ROT, wave(rate));
        return channels;
    }

    /** A value waving on elapsed age at a rate, half a unit either way. */
    private static @NotNull PoseExpr wave(float rate) {
        return op(PoseOperator.MUL, op(PoseOperator.MTH_COS,
            op(PoseOperator.MUL, new PoseExpr.Input("ageInTicks"), new PoseExpr.Constant(rate, PoseWidth.FLOAT))),
            new PoseExpr.Constant(0.5f, PoseWidth.FLOAT));
    }

    /** A subject drawing a body mesh under a pose, and one pass drawing a mesh under the same pose. */
    private static @NotNull Entity overlaid(@NotNull EntityPose pose, @NotNull EntityMesh body, @NotNull EntityMesh pass) {
        return Entity.builder()
            .id(ResourceId.parse("minecraft:test"))
            .model(body)
            .pose(pose)
            .overlays(Concurrent.newUnmodifiableList(RegistrarFixtures.overlay(pass, pose)))
            .build();
    }

    /**
     * A subject drawing one mesh as its body and as one pass under a pose, the pass scrolling its
     * sheet at a rate.
     */
    private static @NotNull Entity scrolling(@NotNull EntityPose pose, @NotNull Vector2f rate, @NotNull EntityMesh mesh) {
        return Entity.builder()
            .id(ResourceId.parse("minecraft:test"))
            .model(mesh)
            .pose(pose)
            .overlays(Concurrent.newUnmodifiableList(RegistrarFixtures.pass(mesh, Optional.empty(),
                PassDeclaration.DEFAULT, 0xFFFFFFFF, false, Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), pose, Optional.of(rate))))
            .build();
    }

    /**
     * A subject drawing one mesh as its body and as one pass under a pose, the pass at an opacity that
     * follows the clock.
     */
    private static @NotNull Entity glowing(
        @NotNull EntityPose pose, @NotNull EntityMesh mesh, @NotNull PoseExpr opacity) {

        return Entity.builder()
            .id(ResourceId.parse("minecraft:test"))
            .model(mesh)
            .pose(pose)
            .overlays(Concurrent.newUnmodifiableList(new Entity.OverlayLayer(mesh, Optional.empty(),
                PassDeclaration.DEFAULT, 0xFFFFFFFF, false, Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), pose, Optional.empty(), Optional.of(opacity))))
            .build();
    }

    /** A subject whose body stands unposed, wearing one equipment mesh under a pose in each slot named, in order. */
    private static @NotNull Entity equipped(
        @NotNull EntityMesh body, @NotNull EntityMesh worn, @NotNull EntityPose pose, @NotNull String @NotNull ... slots) {

        List<Entity.EquipmentOverlay> layers = new ArrayList<>();
        for (String slot : slots) {
            layers.add(new Entity.EquipmentOverlay(slot, worn, pose, LayerType.HORSE_BODY, Concurrent.newUnmodifiableMap(),
                Optional.empty()));
        }
        return Entity.builder()
            .id(ResourceId.parse("minecraft:test"))
            .model(body)
            .pose(EntityPose.NONE)
            .layers(new Entity.Layers(Concurrent.newUnmodifiableList(layers), Optional.empty(), false))
            .build();
    }

    /** A glow pulsing on elapsed age at a rate - lit a quarter at its peak and dark half the cycle. */
    private static @NotNull PoseExpr pulse(float rate) {
        return op(PoseOperator.MAX, new PoseExpr.Constant(0f, PoseWidth.FLOAT),
            op(PoseOperator.MUL, op(PoseOperator.MTH_COS,
                op(PoseOperator.MUL, new PoseExpr.Input("ageInTicks"), new PoseExpr.Constant(rate, PoseWidth.FLOAT))),
                new PoseExpr.Constant(0.25f, PoseWidth.FLOAT)));
    }

    private static @NotNull PoseExpr op(@NotNull PoseOperator operator, @NotNull PoseExpr... operands) {
        return new PoseExpr.Op(operator, Concurrent.newUnmodifiableList(operands));
    }

    /** A row moved through one clock by the given drivers. */
    private static @NotNull PoseStyle style(@NotNull StyleClock clock, @NotNull StyleDriver... drivers) {
        Map<String, StyleDriver> driven = new LinkedHashMap<>();
        for (StyleDriver driver : drivers) driven.put(driver.field(), driver);
        return new PoseStyle("test",
            Concurrent.newUnmodifiableList(new PoseStyle.StyleSource(clock, Optional.empty())),
            Concurrent.newUnmodifiableMap(driven), Concurrent.newUnmodifiableList(), Optional.empty(),
            Optional.empty());
    }

    /** A mesh over a sheet of a size declaring the named bones, each carrying one cube at rest. */
    private static @NotNull EntityMesh mesh(@NotNull TextureSize sheet, @NotNull String... bones) {
        EntityMesh mesh = new EntityMesh(sheet, Concurrent.newLinkedMap(), false);
        for (String bone : bones) {
            mesh.getBones().put(bone, new EntityMesh.Bone(Vector3f.ZERO, EulerRotation.NONE, EulerRotation.NONE, 1f, cube(),
                null));
        }
        return mesh;
    }

    /**
     * A mesh of three bones, each carrying one cube - a body standing at a pivot and rotation, a head
     * hanging from it at a rest scale of two, and a third bone under a name at a rest scale hanging from
     * a parent.
     */
    private static @NotNull EntityMesh skeleton(
        @NotNull Vector3f bodyPivot, @NotNull EulerRotation bodyRotation,
        @NotNull String armName, float armScale, @NotNull String armParent) {

        EntityMesh mesh = new EntityMesh(TextureSize.DEFAULT, Concurrent.newLinkedMap(), false);
        mesh.getBones().put("body", new EntityMesh.Bone(bodyPivot, bodyRotation, EulerRotation.NONE, 1f, cube(), null));
        mesh.getBones().put("head", new EntityMesh.Bone(Vector3f.ZERO, EulerRotation.NONE, EulerRotation.NONE, 2f, cube(),
            "body"));
        mesh.getBones().put(armName, new EntityMesh.Bone(Vector3f.ZERO, EulerRotation.NONE, EulerRotation.NONE, armScale, cube(),
            armParent));
        return mesh;
    }

    /** One cube at rest, the whole of what a test bone owns. */
    private static @NotNull ConcurrentList<EntityMesh.Cube> cube() {
        ConcurrentList<EntityMesh.Cube> cubes = Concurrent.newList();
        cubes.add(new EntityMesh.Cube());
        return cubes;
    }

}
