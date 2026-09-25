package lib.minecraft.renderer.asset.pose;

import dev.simplified.collection.Concurrent;
import lib.minecraft.renderer.engine.pose.StyleDriver;
import lib.minecraft.renderer.exception.RendererException;
import lib.minecraft.renderer.vanilla.appearance.Age;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
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
class StyleCatalogTest {

    /** The subject id every refusal here names. */
    private static final @NotNull String SUBJECT = "minecraft:test";

    /** A predicate every row applies under, so a case isolates the catalog from any appearance. */
    private static final @NotNull Predicate<PoseStyle> ANY = style -> true;

    @Test
    @DisplayName("the styleless catalog is the bind row alone")
    void bindOnlyIsTheBindRowAlone() {
        PoseStyle bind = StyleCatalog.bind();
        assertEquals(PoseStyle.BIND, bind.id());
        assertTrue(bind.sources().isEmpty(), "nothing sourced");
        assertTrue(bind.drivers().isEmpty(), "nothing driven");
        assertTrue(bind.toggles().isEmpty(), "nothing toggled");
        assertTrue(bind.age().isEmpty(), "either age");
        assertFalse(bind.moves(), "and it holds still");
        assertEquals(List.of(PoseStyle.BIND), List.copyOf(StyleCatalog.BIND_ONLY.ids()),
            "bind is the whole of what it lists");
        assertEquals(3, StyleCatalog.BIND_ONLY.stripTicksPerFrame(),
            "the shipped period divides across the strip");
    }

    @Test
    @DisplayName("the four universal ids resolve on a catalog that ships nothing")
    void theUniversalIdsResolveEverywhere() {
        assertEquals(PoseStyle.BIND, StyleCatalog.BIND_ONLY.resolve(PoseStyle.BIND, ANY, SUBJECT).id());
        assertEquals(PoseStyle.IDLE, StyleCatalog.BIND_ONLY.resolve(PoseStyle.IDLE, ANY, SUBJECT).id());
        assertEquals(PoseStyle.STRIDE, StyleCatalog.BIND_ONLY.resolve(PoseStyle.STRIDE, ANY, SUBJECT).id());
        assertEquals(PoseStyle.BIND, StyleCatalog.BIND_ONLY.resolve(PoseStyle.ANIMATED, ANY, SUBJECT).id(),
            "a catalog nothing moves resolves animated to bind");
    }

    @Test
    @DisplayName("the synthesized idle ramps elapsed age and rests everything else")
    void theSynthesizedIdleRampsElapsedAge() {
        PoseStyle idle = StyleCatalog.BIND_ONLY.resolve(PoseStyle.IDLE, ANY, SUBJECT);
        ToDoubleFunction<String> frame = idle.frameAt(7, StyleCatalog.BIND_ONLY.periodTicks());
        assertEquals(7d, frame.applyAsDouble("ageInTicks"), "elapsed age is the tick itself");
        assertEquals(0d, frame.applyAsDouble("walkAnimationSpeed"), "a standing subject walks at nothing");
        assertEquals(0d, frame.applyAsDouble("walkAnimationPos"), "and its stride rests");
        assertEquals(0d, frame.applyAsDouble("tentacleAngle"), "an undriven field answers its resting zero");
    }

    @Test
    @DisplayName("the synthesized stride adds the walk pair at amplitude one")
    void theSynthesizedStrideAddsTheWalkPair() {
        PoseStyle stride = StyleCatalog.BIND_ONLY.resolve(PoseStyle.STRIDE, ANY, SUBJECT);
        ToDoubleFunction<String> frame = stride.frameAt(7, StyleCatalog.BIND_ONLY.periodTicks());
        assertEquals(7d, frame.applyAsDouble("ageInTicks"), "elapsed age still climbs");
        assertEquals(1d, frame.applyAsDouble("walkAnimationSpeed"), "the amplitude is the full one");
        assertEquals(7d, frame.applyAsDouble("walkAnimationPos"), "and the phase is the tick times it");
    }

    @Test
    @DisplayName("an unknown id is refused listing the supported set")
    void anUnknownIdIsRefusedListingTheSupportedSet() {
        RendererException refused = assertThrows(RendererException.class,
            () -> StyleCatalog.BIND_ONLY.resolve("croak", ANY, SUBJECT));
        assertTrue(refused.getMessage().contains("croak"),
            "the refusal names what was asked: " + refused.getMessage());
        assertTrue(refused.getMessage().contains(PoseStyle.BIND),
            "and what is supported: " + refused.getMessage());
        assertTrue(refused.getMessage().contains(SUBJECT),
            "and the subject it was asked of: " + refused.getMessage());
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

        StyleCatalog narrowed = catalog.inForce(false, gate -> false);
        assertEquals(1, narrowed.styles().size(), "the baby-only row drops for an adult subject");
        PoseStyle kept = narrowed.styles().getFirst();
        assertEquals(PoseStyle.IDLE, kept.id());
        assertEquals(1, kept.sources().size(), "the gated entry drops under a refusing predicate");
        assertEquals(StyleClock.FIGURE, kept.sources().getFirst().source(),
            "and the unconditional one survives");

        assertSame(catalog, catalog.inForce(true, gate -> true),
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

        assertEquals(PoseStyle.IDLE, catalog.animated().id(),
            "the shipped union carries the charged movement");
        assertEquals(PoseStyle.BIND, catalog.inForce(false, gate -> false).animated().id(),
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
        assertEquals(List.of(PoseStyle.BIND, "rest"), List.copyOf(catalog.ids()),
            "and is still a selectable output");
    }

    @Test
    @DisplayName("an age-split pair is listed once, after bind")
    void anAgeSplitPairIsListedOnce() {
        StyleCatalog catalog = new StyleCatalog(24,
            Concurrent.newUnmodifiableList(playDead(Age.ADULT), playDead(Age.BABY)));
        assertEquals(List.of(PoseStyle.BIND, "play_dead"), List.copyOf(catalog.ids()),
            "one id names both ages");
    }

    @Test
    @DisplayName("resolve answers the row of a shared id that applies to the subject")
    void resolvePicksTheApplyingRowOfASharedId() {
        StyleCatalog catalog = new StyleCatalog(24,
            Concurrent.newUnmodifiableList(playDead(Age.ADULT), playDead(Age.BABY)));
        Predicate<PoseStyle> adult = style -> style.age().equals(Optional.of(Age.ADULT));
        Predicate<PoseStyle> baby = style -> style.age().equals(Optional.of(Age.BABY));

        assertEquals(Optional.of(Age.ADULT), catalog.resolve("play_dead", adult, SUBJECT).age(),
            "a subject the adult row applies to resolves the adult row");
        assertEquals(Optional.of(Age.BABY), catalog.resolve("play_dead", baby, SUBJECT).age(),
            "and one the baby row applies to the baby one");
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
