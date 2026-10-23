package lib.minecraft.renderer.content.index;

import dev.simplified.image.PixelBuffer;
import dev.simplified.util.Possible;
import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.asset.ColorMap;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.Item;
import lib.minecraft.renderer.engine.frame.StripFit;
import lib.minecraft.renderer.vanilla.TintSource;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The memo a context holds its fitted strips in, and which context holds it.
 *
 * <p>A memo fits a key once and answers that fit to every later ask, and another memo fits the key
 * afresh. A context holding definitions answers one memo for its whole life and every wrapper over it
 * answers that memo, save a context answering entities of its own, which holds one of its own; a context
 * declaring none answers a fresh memo on every ask.
 */
@DisplayName("the memo a context holds its fitted strips in")
class StripMemoTest {

    @Test
    @DisplayName("a key is fitted once under one memo, and another memo fits it afresh")
    void aKeyIsFittedOncePerMemo() {
        StripMemo strips = new StripMemo();
        AtomicInteger fitted = new AtomicInteger();
        Supplier<StripFit> fitting = () -> {
            fitted.incrementAndGet();
            return new StripFit(3, 22, 0.5);
        };

        StripFit first = strips.fit("stride", fitting);
        assertSame(first, strips.fit("stride", fitting), "a second ask answers the first fit itself");
        assertEquals(1, fitted.get(), "without fitting again");
        assertNotSame(first, new StripMemo().fit("stride", fitting), "another memo fits the key afresh");
        strips.fit("idle", fitting);
        assertEquals(3, fitted.get(), "and another key is a fit of its own");
    }

    @Test
    @DisplayName("a context answers one memo, every wrapper over it that memo, and a context over entities of its own another")
    void aContextHoldsOneMemoItsWrappersShare() {
        RendererContext context = RendererContext.builder().build();
        StripMemo held = context.strips();
        assertSame(held, context.strips(), "a context answers one memo for its whole life");

        Map<String, RendererContext> wrappers = new LinkedHashMap<>();
        wrappers.put("withTextures", context.withTextures(id -> Possible.absent()));
        wrappers.put("withTexture", context.withTexture("minecraft:test", PixelBuffer.create(16, 16)));
        wrappers.put("withMissingTexture", context.withMissingTexture());
        wrappers.put("hiding", context.hiding("minecraft:test"));
        wrappers.put("collecting", context.collecting(new SubstitutionCollector()));
        wrappers.put("measuring", context.measuring());
        wrappers.forEach((name, wrapper) -> assertSame(held, wrapper.strips(), name + " answers its delegate's memo"));

        RendererContext entities = context.withEntities(Map.of());
        assertNotSame(held, entities.strips(), "a context answering entities of its own holds a memo of its own");
        assertSame(entities.strips(), entities.strips(), "for its whole life");
        assertSame(entities.strips(), entities.measuring().strips(), "which a wrapper over it answers in turn");
    }

    @Test
    @DisplayName("a context declaring no memo of its own answers a fresh one on every ask")
    void aContextDeclaringNoMemoSharesNothing() {
        RendererContext bare = new RendererContext() {

            @Override public @NotNull Possible<Block> findBlock(@NotNull String id) {
                return Possible.absent();
            }

            @Override public @NotNull Possible<ColorMap> findColorMap(@NotNull TintSource target) {
                return Possible.absent();
            }

            @Override public @NotNull Possible<Entity> findEntity(@NotNull String id) {
                return Possible.absent();
            }

            @Override public @NotNull Possible<Item> findItem(@NotNull String id) {
                return Possible.absent();
            }

            @Override public @NotNull Possible<PixelBuffer> resolveTexture(@NotNull String textureId) {
                return Possible.absent();
            }
        };

        assertNotSame(bare.strips(), bare.strips(), "so no fit is held from one ask to the next");
    }

}
