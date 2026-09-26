package lib.minecraft.renderer.bake.pose;

import dev.simplified.collection.Concurrent;
import lib.minecraft.renderer.asset.pose.PoseStyle;
import lib.minecraft.renderer.asset.pose.StyleCatalog;
import lib.minecraft.renderer.asset.pose.StyleClock;
import lib.minecraft.renderer.engine.pose.StyleDriver;
import lib.minecraft.renderer.exception.RendererException;
import lib.minecraft.renderer.request.AppearanceOptions;
import lib.minecraft.renderer.request.EntityOptions;
import lib.minecraft.renderer.vanilla.appearance.Age;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.ToDoubleFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The style catalog's resolution, discovery and narrowing behaviour.
 *
 * <p>The universal ids must resolve on every catalog - the sweep contract renders every subject at
 * every gait - and the synthesized rows must answer the same numbers the frame oracle answers
 * universally: elapsed age as the tick itself, the stride pair at amplitude one. An unknown id
 * fails loud listing the supported set, and the in-force view narrows a resolved subject's
 * inventory without touching what the entity is said to support.
 */
@DisplayName("the style catalog resolves, lists and narrows")
class StyleSelectionTest {

    @Test
    @DisplayName("the styleless catalog is the bind row alone")
    void bindOnlyIsTheBindRowAlone() {
        PoseStyle bind = StyleSelection.bind();
        assertEquals(PoseStyle.BIND, bind.id());
        assertTrue(bind.sources().isEmpty(), "nothing sourced");
        assertTrue(bind.drivers().isEmpty(), "nothing driven");
        assertTrue(bind.toggles().isEmpty(), "nothing toggled");
        assertTrue(bind.age().isEmpty(), "either age");
        assertFalse(bind.moves(), "and it holds still");
        assertEquals(List.of(PoseStyle.BIND), List.copyOf(StyleSelection.ids(StyleCatalog.BIND_ONLY)),
            "bind is the whole of what it lists");
        assertEquals(3, StyleSelection.stripTicksPerFrame(StyleCatalog.BIND_ONLY),
            "the shipped period divides across the strip");
    }

    @Test
    @DisplayName("the four universal ids resolve on a catalog that ships nothing")
    void theUniversalIdsResolveEverywhere() {
        EntityOptions options = EntityOptions.of("minecraft:test");
        assertEquals(PoseStyle.BIND,
            StyleSelection.resolve(StyleCatalog.BIND_ONLY, PoseStyle.BIND, options).id());
        assertEquals(PoseStyle.IDLE,
            StyleSelection.resolve(StyleCatalog.BIND_ONLY, PoseStyle.IDLE, options).id());
        assertEquals(PoseStyle.STRIDE,
            StyleSelection.resolve(StyleCatalog.BIND_ONLY, PoseStyle.STRIDE, options).id());
        assertEquals(PoseStyle.BIND,
            StyleSelection.resolve(StyleCatalog.BIND_ONLY, PoseStyle.ANIMATED, options).id(),
            "a catalog nothing moves resolves animated to bind");
    }

    @Test
    @DisplayName("the synthesized idle ramps elapsed age and rests everything else")
    void theSynthesizedIdleRampsElapsedAge() {
        PoseStyle idle = StyleSelection.resolve(StyleCatalog.BIND_ONLY, PoseStyle.IDLE, EntityOptions.of("minecraft:test"));
        ToDoubleFunction<String> frame = idle.frameAt(7, StyleCatalog.BIND_ONLY.periodTicks());
        assertEquals(7d, frame.applyAsDouble("ageInTicks"), "elapsed age is the tick itself");
        assertEquals(0d, frame.applyAsDouble("walkAnimationSpeed"), "a standing subject walks at nothing");
        assertEquals(0d, frame.applyAsDouble("walkAnimationPos"), "and its stride rests");
        assertEquals(0d, frame.applyAsDouble("tentacleAngle"), "an undriven field answers its resting zero");
    }

    @Test
    @DisplayName("the synthesized stride adds the walk pair at amplitude one")
    void theSynthesizedStrideAddsTheWalkPair() {
        PoseStyle stride = StyleSelection.resolve(StyleCatalog.BIND_ONLY, PoseStyle.STRIDE, EntityOptions.of("minecraft:test"));
        ToDoubleFunction<String> frame = stride.frameAt(7, StyleCatalog.BIND_ONLY.periodTicks());
        assertEquals(7d, frame.applyAsDouble("ageInTicks"), "elapsed age still climbs");
        assertEquals(1d, frame.applyAsDouble("walkAnimationSpeed"), "the amplitude is the full one");
        assertEquals(7d, frame.applyAsDouble("walkAnimationPos"), "and the phase is the tick times it");
    }

    @Test
    @DisplayName("an unknown id is refused listing the supported set")
    void anUnknownIdIsRefusedListingTheSupportedSet() {
        RendererException refused = assertThrows(RendererException.class,
            () -> StyleSelection.resolve(StyleCatalog.BIND_ONLY, "croak", EntityOptions.of("minecraft:test")));
        assertTrue(refused.getMessage().contains("croak"),
            "the refusal names what was asked: " + refused.getMessage());
        assertTrue(refused.getMessage().contains(PoseStyle.BIND),
            "and what is supported: " + refused.getMessage());
    }

    @Test
    @DisplayName("a baby-only row applies to a baby and refuses an adult")
    void aBabyOnlyRowFiltersOnAge() {
        PoseStyle rollUp = new PoseStyle("roll_up",
            Concurrent.newUnmodifiableList(
                new PoseStyle.StyleSource(StyleClock.SELECT, Optional.empty())),
            Concurrent.newUnmodifiableMap(Map.of("rollUpAnimationState",
                new StyleDriver("rollUpAnimationState", StyleDriver.Wave.HOLD, 0f, 1f,
                    Optional.of("action")))),
            Concurrent.newUnmodifiableList(), Optional.of(Age.BABY), Optional.empty());
        StyleCatalog catalog = new StyleCatalog(24, Concurrent.newUnmodifiableList(rollUp));
        EntityOptions adult = EntityOptions.of("minecraft:test");
        EntityOptions baby = EntityOptions.builder()
            .entityId("minecraft:test")
            .appearance(AppearanceOptions.builder().age(Age.BABY).build())
            .build();

        assertFalse(rollUp.appliesTo(adult.getAppearance()), "the row refuses an adult appearance");
        assertTrue(rollUp.appliesTo(baby.getAppearance()), "and applies to a baby one");
        assertEquals("roll_up", StyleSelection.resolve(catalog, "roll_up", baby).id());
        assertThrows(RendererException.class, () -> StyleSelection.resolve(catalog, "roll_up", adult),
            "a row that does not apply resolves as an unknown id does");
    }

    @Test
    @DisplayName("the in-force view drops what the subject's gates refuse and keeps the rest")
    void inForceDropsRefusedGatesAndRefusedAges() {
        PoseStyle idle = new PoseStyle(PoseStyle.IDLE,
            Concurrent.newUnmodifiableList(
                new PoseStyle.StyleSource(StyleClock.FIGURE, Optional.empty()),
                new PoseStyle.StyleSource(StyleClock.SCROLL, Optional.of("charged"))),
            Concurrent.newUnmodifiableMap(), Concurrent.newUnmodifiableList(), Optional.empty(),
            Optional.empty());
        PoseStyle babyRow = new PoseStyle("roll_up",
            Concurrent.newUnmodifiableList(
                new PoseStyle.StyleSource(StyleClock.SELECT, Optional.empty())),
            Concurrent.newUnmodifiableMap(), Concurrent.newUnmodifiableList(),
            Optional.of(Age.BABY), Optional.empty());
        StyleCatalog catalog = new StyleCatalog(24, Concurrent.newUnmodifiableList(idle, babyRow));

        StyleCatalog narrowed = StyleSelection.inForce(catalog, false, gate -> false);
        assertEquals(1, narrowed.styles().size(), "the baby-only row drops for an adult subject");
        PoseStyle kept = narrowed.styles().getFirst();
        assertEquals(PoseStyle.IDLE, kept.id());
        assertEquals(1, kept.sources().size(), "the gated entry drops under a refusing predicate");
        assertEquals(StyleClock.FIGURE, kept.sources().getFirst().source(),
            "and the unconditional one survives");

        assertSame(catalog, StyleSelection.inForce(catalog, true, gate -> true),
            "a subject nothing narrows holds the catalog itself");
    }

    @Test
    @DisplayName("animated answers the in-force inventory, not the shipped union")
    void animatedFollowsTheInForceInventory() {
        PoseStyle scrollsWhenCharged = new PoseStyle(PoseStyle.IDLE,
            Concurrent.newUnmodifiableList(
                new PoseStyle.StyleSource(StyleClock.SCROLL, Optional.of("charged"))),
            Concurrent.newUnmodifiableMap(), Concurrent.newUnmodifiableList(), Optional.empty(),
            Optional.empty());
        StyleCatalog catalog =
            new StyleCatalog(24, Concurrent.newUnmodifiableList(scrollsWhenCharged));

        assertEquals(PoseStyle.IDLE, StyleSelection.animated(catalog).id(),
            "the shipped union carries the charged movement");
        assertEquals(PoseStyle.BIND, StyleSelection.animated(StyleSelection.inForce(catalog, false, gate -> false)).id(),
            "an appearance that dropped the pass falls through to bind");
    }

    @Test
    @DisplayName("a held row is listed - a held stance renders a picture bind does not")
    void aHeldRowIsListed() {
        PoseStyle rest = new PoseStyle("rest",
            Concurrent.newUnmodifiableList(),
            Concurrent.newUnmodifiableMap(Map.of("restAnimationState",
                new StyleDriver("restAnimationState", StyleDriver.Wave.HOLD, 0f, 1f,
                    Optional.of("action")))),
            Concurrent.newUnmodifiableList(), Optional.empty(), Optional.empty());
        StyleCatalog catalog = new StyleCatalog(24, Concurrent.newUnmodifiableList(rest));

        assertTrue(rest.sources().isEmpty(), "the row holds still");
        assertEquals(List.of(PoseStyle.BIND, "rest"), List.copyOf(StyleSelection.ids(catalog)),
            "and is still a selectable output");
    }

    @Test
    @DisplayName("an age-split pair is listed once, after bind")
    void anAgeSplitPairIsListedOnce() {
        StyleCatalog catalog = new StyleCatalog(24,
            Concurrent.newUnmodifiableList(playDead(Age.ADULT), playDead(Age.BABY)));
        assertEquals(List.of(PoseStyle.BIND, "play_dead"), List.copyOf(StyleSelection.ids(catalog)),
            "one id names both ages");
    }

    @Test
    @DisplayName("resolve answers the row of a shared id that applies to the request")
    void resolvePicksTheApplyingRowOfASharedId() {
        StyleCatalog catalog = new StyleCatalog(24,
            Concurrent.newUnmodifiableList(playDead(Age.ADULT), playDead(Age.BABY)));
        EntityOptions adult = EntityOptions.of("minecraft:test");
        EntityOptions baby = EntityOptions.builder()
            .entityId("minecraft:test")
            .appearance(AppearanceOptions.builder().age(Age.BABY).build())
            .build();

        assertEquals(Optional.of(Age.ADULT), StyleSelection.resolve(catalog, "play_dead", adult).age(),
            "an adult request resolves the adult row");
        assertEquals(Optional.of(Age.BABY), StyleSelection.resolve(catalog, "play_dead", baby).age(),
            "and a baby request the baby one");
    }

    // ------------------------------------------------------------------------------------

    /** One age's copy of a shared held row, so an age-split pair is two rows under one id. */
    private static @NotNull PoseStyle playDead(@NotNull Age age) {
        return new PoseStyle("play_dead", Concurrent.newUnmodifiableList(),
            Concurrent.newUnmodifiableMap(Map.of("playingDeadAnimationState",
                new StyleDriver("playingDeadAnimationState", StyleDriver.Wave.HOLD, 0f, 1f,
                    Optional.of("action")))),
            Concurrent.newUnmodifiableList(), Optional.of(age), Optional.empty());
    }

}
