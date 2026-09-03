package lib.minecraft.renderer.author.pose;

import dev.simplified.collection.Concurrent;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.appearance.Age;
import lib.minecraft.renderer.asset.pose.EntityPose;
import lib.minecraft.renderer.asset.pose.MotionSource;
import lib.minecraft.renderer.asset.pose.PoseStyle;
import lib.minecraft.renderer.asset.pose.StyleCatalog;
import lib.minecraft.renderer.asset.pose.StyleDriver;
import lib.minecraft.renderer.engine.kit.PoseKit;
import lib.minecraft.renderer.exception.RendererException;
import lib.minecraft.renderer.option.AppearanceOptions;
import lib.minecraft.renderer.option.EntityOptions;
import lib.minecraft.renderer.support.StubRendererContext;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static lib.minecraft.renderer.author.pose.CompilerFixtures.humanoid;
import static lib.minecraft.renderer.author.pose.RegistrarFixtures.definitions;
import static lib.minecraft.renderer.author.pose.RegistrarFixtures.entity;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Resolution and discovery around an installed style - the options knob selecting the carried
 * row, the universal ids answering exactly what they answered before the install, the in-force
 * narrowing that keeps an adult subject's custom row and drops a baby's, and the identity the
 * {@code bind} row keeps on the mutated subject.
 */
@DisplayName("an installed style resolves as a carried peer and moves nothing universal")
class StyleResolutionTest {

    /**
     * The catalog period every fixture row frames its excursions against.
     */
    private static final int PERIOD = 24;

    @Test
    @DisplayName("the options style knob resolves the carried row by its free string")
    void optionsStyleKnobResolvesTheCarriedRow() {
        StyleRegistrar registrar = registrar();
        registrar.add("minecraft:test", sit());

        StyleCatalog installed = registrar.definitions().get("minecraft:test").styles();
        EntityOptions options = EntityOptions.builder()
            .entityId("minecraft:test")
            .style("sit")
            .build();
        PoseStyle resolved = installed.resolve(options.getStyle(), options);
        assertEquals("sit", resolved.id());
        assertSame(installed.byId("sit").orElseThrow(), resolved,
            "the knob selects the carried row itself, not a copy");
    }

    @Test
    @DisplayName("the universal ids answer the same row instances before and after an install")
    void universalIdsAnswerAsBeforeTheInstall() {
        StyleRegistrar registrar = registrar();
        EntityOptions options = EntityOptions.of("minecraft:test");
        StyleCatalog before = registrar.definitions().get("minecraft:test").styles();
        PoseStyle bind = before.resolve(PoseStyle.BIND, options);
        PoseStyle idle = before.resolve(PoseStyle.IDLE, options);
        PoseStyle stride = before.resolve(PoseStyle.STRIDE, options);
        PoseStyle animated = before.resolve(PoseStyle.ANIMATED, options);
        assertEquals("dance", animated.id(), "the shipped moving row answers the animated request");

        registrar.add("minecraft:test", sit());
        registrar.add("minecraft:test", Poses.humanoid("wave")
            .head(head -> head.sway(Turn.YAW, -5, 5))
            .build());

        StyleCatalog after = registrar.definitions().get("minecraft:test").styles();
        assertSame(bind, after.resolve(PoseStyle.BIND, options));
        assertSame(idle, after.resolve(PoseStyle.IDLE, options));
        assertSame(stride, after.resolve(PoseStyle.STRIDE, options));
        assertSame(animated, after.resolve(PoseStyle.ANIMATED, options),
            "custom rows trail the shipped ones, so a moving install never hijacks the animated request");
    }

    @Test
    @DisplayName("the in-force view keeps the custom row for an adult and drops it for a baby")
    void inForceKeepsTheAdultRowAndDropsItForABaby() {
        StyleRegistrar registrar = registrar();
        registrar.add("minecraft:test", sit());
        StyleCatalog installed = registrar.definitions().get("minecraft:test").styles();

        assertSame(installed, installed.inForce(false, gate -> true),
            "nothing narrows for an adult, so the catalog answers itself");

        StyleCatalog narrowed = installed.inForce(true, gate -> true);
        assertTrue(narrowed.byId("sit").isEmpty(), "the adult-default row drops out of a baby's view");
        assertTrue(narrowed.byId("dance").isPresent(), "an ageless shipped row survives the narrowing");

        EntityOptions baby = EntityOptions.builder()
            .entityId("minecraft:test")
            .appearance(AppearanceOptions.builder().age(Age.BABY).build())
            .build();
        RendererException refused = assertThrows(RendererException.class,
            () -> narrowed.resolve("sit", baby));
        assertTrue(refused.getMessage().contains("has no style 'sit'"), refused.getMessage());
        assertTrue(refused.getMessage().contains("it supports"),
            "the refusal lists what the narrowed subject still answers: " + refused.getMessage());
    }

    @Test
    @DisplayName("the bind row hands back the mutated subject's own instances untouched")
    void bindIdentityHoldsOnTheMutatedRow() {
        StyleRegistrar registrar = registrar();
        registrar.add("minecraft:test", sit());
        Entity woven = registrar.definitions().get("minecraft:test");
        PoseStyle bind = woven.styles().resolve(PoseStyle.BIND, EntityOptions.of("minecraft:test"));

        assertSame(woven.model(),
            PoseKit.posed(woven.pose(), woven.model(), bind, PERIOD, 7),
            "posing the woven graph under bind is identity on the mesh, never a copy");
        assertSame(woven, PoseKit.posed(woven, bind, PERIOD, 7),
            "and identity on the whole subject");
    }

    @Test
    @DisplayName("a registrar-built renderer discovers styles off the mutated catalog itself")
    void rendererStylesAnswersTheMutatedCatalog() {
        StyleRegistrar registrar = registrar();
        registrar.add("minecraft:test", sit());

        StyleCatalog discovered = registrar.renderer(StubRendererContext.builder().build())
            .styles("minecraft:test");
        assertSame(registrar.definitions().get("minecraft:test").styles(), discovered,
            "discovery and resolution read one catalog instance");
        assertTrue(discovered.ids().contains("sit"), "so the installed id is discoverable");
    }

    // ------------------------------------------------------------------------------------

    /**
     * A registrar over one row whose catalog carries a moving shipped style.
     */
    private static @NotNull StyleRegistrar registrar() {
        PoseStyle dance = new PoseStyle("dance",
            Concurrent.newUnmodifiableList(new PoseStyle.StyleSource(MotionSource.TICK, Optional.empty())),
            Concurrent.newUnmodifiableMap(Map.of("ageInTicks",
                new StyleDriver("ageInTicks", StyleDriver.Wave.RAMP, 0f, 1f, Optional.empty()))),
            Concurrent.newUnmodifiableList(), Optional.empty(), Optional.empty());
        return StyleRegistrar.of(definitions(entity("minecraft:test", humanoid(), EntityPose.NONE,
            new StyleCatalog(PERIOD, Concurrent.newUnmodifiableList(dance)))));
    }

    /**
     * The seated statue - one container step, adult by default, nothing animated.
     */
    private static @NotNull BuiltStyle sit() {
        return Poses.humanoid("sit").container(step -> step.offset(0, 7, 0)).build();
    }

}
