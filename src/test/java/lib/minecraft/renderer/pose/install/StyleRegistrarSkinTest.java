package lib.minecraft.renderer.pose.install;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentLinkedMap;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.ImageData;
import dev.simplified.image.ImageFactory;
import dev.simplified.image.ImageFormat;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.pack.MCMeta;
import lib.minecraft.renderer.engine.RendererContext;
import lib.minecraft.renderer.option.EntityOptions;
import lib.minecraft.renderer.pipeline.loader.EntityModelLoader;
import lib.minecraft.renderer.support.StubRendererContext;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The caller-skin wiring end to end - the reserved coordinate on the rig's state axis, the
 * context wrapper answering it ahead of every pack, and the precedence the options override
 * keeps.
 */
@DisplayName("a caller skin reaches the rig through the reserved id")
class StyleRegistrarSkinTest {

    private static ConcurrentMap<String, Entity> shipped;

    @BeforeAll
    static void load() {
        shipped = EntityModelLoader.load();
        assumeTrue(!shipped.isEmpty(), "entity_models.json not present - run entityModels first");
    }

    @Test
    @DisplayName("the wrapper answers the reserved id with the caller's buffer, tick-stable, everything else forwarded")
    void wrapperAnswersTheReservedIdAlone() {
        StubRendererContext spy = StubRendererContext.builder().build();
        PixelBuffer sheet = sheet(0xFFAA5511);
        SkinContext wrapped = new SkinContext(spy, sheet);

        assertSame(sheet, wrapped.resolveTexture(PlayerRig.SKIN_TEXTURE_ID).orElseThrow(),
            "the reserved id answers the caller's very buffer");
        assertSame(sheet, wrapped.resolveTextureAtTick(PlayerRig.SKIN_TEXTURE_ID, 0).orElseThrow());
        assertSame(sheet, wrapped.resolveTextureAtTick(PlayerRig.SKIN_TEXTURE_ID, 21).orElseThrow(),
            "no flipbook resolves for it, so every tick answers the buffer unchanged");
        assertFalse(spy.getResolved().contains(PlayerRig.SKIN_TEXTURE_ID),
            "the reserved id never reaches the delegate");

        assertTrue(wrapped.resolveTexture("minecraft:entity/zombie/zombie").isEmpty(),
            "an id the delegate cannot answer stays unanswered");
        assertTrue(spy.getResolved().contains("minecraft:entity/zombie/zombie"),
            "because every other id forwards to the delegate untouched");
    }

    @Test
    @DisplayName("the reserved id's sidecar stops at the wrapper, even where the delegate would answer one")
    void theReservedIdCarriesNoSidecar() {
        // The delegate answers a sidecar for EVERY id, including the reserved one - which is the state
        // the wrapper has to be correct in. Forwarding the sidecar doors would pair a pack's metadata
        // with the caller's pixels, describing a texture nothing serves. Nothing reads a skin's sidecar
        // today, so this is the only thing that would notice.
        SkinContext wrapped = new SkinContext(new AlwaysMeta(StubRendererContext.builder().build()), sheet(0xFFAA5511));

        assertTrue(wrapped.findMeta(PlayerRig.SKIN_TEXTURE_ID).isEmpty(),
            "the reserved id's pixels and metadata come from the same place, and the sheet has none");
        assertTrue(wrapped.findAnimation(PlayerRig.SKIN_TEXTURE_ID).isEmpty(),
            "and the answer derived from that sidecar agrees with it");
        assertTrue(wrapped.resolveTextureAtTick(PlayerRig.SKIN_TEXTURE_ID, 21).isPresent(),
            "so no flipbook resolves and every tick still answers the sheet");

        assertTrue(wrapped.findMeta("minecraft:entity/zombie/zombie").isPresent(),
            "every other id still reaches the delegate's sidecar");
    }

    @Test
    @DisplayName("registered bytes re-point the rig's state axis and draw with no pack answering anything")
    void registeredBytesReachTheRender() {
        StyleRegistrar registrar = registrarWithRig().skin(pngBytes(sheet(0xFF44AA77)));

        assertEquals(Optional.of(PlayerRig.SKIN_REF),
            registrar.definitions().get(PlayerRig.ENTITY_ID).textureRef(),
            "one registration re-points the row for every later render");

        StubRendererContext empty = StubRendererContext.builder().build();
        ImageData drawn = registrar.renderer(empty).render(EntityOptions.of(PlayerRig.ENTITY_ID));
        assertTrue(opaqueCount(drawn.toPixelBuffer()) > 0,
            "the caller's bytes are the only texture anywhere, and they draw");
    }

    @Test
    @DisplayName("without a registered skin the reserved id resolves nowhere and no shipped row names it")
    void reservedIdIsIsolated() {
        for (Entity row : shipped.values()) {
            for (String ref : row.axes().state().options().values())
                assertFalse(ref.contains("$"),
                    "no shipped state ref carries the reserved marker: " + ref);
            for (Entity.OverlayLayer overlay : row.overlays())
                overlay.textureRef().ifPresent(ref -> assertFalse(ref.contains("$"),
                    "no shipped overlay ref carries the reserved marker: " + ref));
        }

        StubRendererContext spy = StubRendererContext.builder()
            .everyTexture(() -> sheet(0xFF888888))
            .build();
        registrarWithRig().renderer(spy).render(EntityOptions.of(PlayerRig.ENTITY_ID));
        assertTrue(spy.getResolved().contains("minecraft:entity/player/wide/steve"),
            "an unregistered rig resolves the Steve default");
        assertFalse(spy.getResolved().contains(PlayerRig.SKIN_TEXTURE_ID),
            "and never asks for the reserved id");
    }

    @Test
    @DisplayName("the options texture override still wins the precedence chain on the rig")
    void optionsOverrideStillWins() {
        StubRendererContext spy = StubRendererContext.builder()
            .texturesById(Map.of("test:pack/skin", sheet(0xFF2244CC)))
            .build();
        ImageData drawn = registrarWithRig()
            .skin(sheet(0xFF44AA77))
            .renderer(spy)
            .render(EntityOptions.builder()
                .entityId(PlayerRig.ENTITY_ID)
                .textureId("test:pack/skin")
                .build());

        assertTrue(spy.getResolved().contains("test:pack/skin"),
            "the override id is the one resolved, ahead of the registered skin");
        assertTrue(opaqueCount(drawn.toPixelBuffer()) > 0, "and its sheet draws");
    }

    @Test
    @DisplayName("a skin registered where no rig row is carried refuses naming the row")
    void skinWithoutRigRowRefuses() {
        StyleRegistrar registrar = StyleRegistrar.of(Concurrent.newMap());

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
            () -> registrar.skin(sheet(0xFF000000)));
        assertTrue(refused.getMessage().contains(PlayerRig.ENTITY_ID),
            "the refusal names the row the skin rides: " + refused.getMessage());
    }

    // ------------------------------------------------------------------------------------

    /**
     * A delegate answering a sidecar for every id, so a wrapper that forwards the sidecar doors is
     * caught pairing pack metadata with a caller's pixels. It derives its animation answer from that
     * sidecar exactly as the concrete context does, which is what couples the two doors.
     *
     * @param delegate the stub every other lookup forwards to
     */
    private record AlwaysMeta(@NotNull RendererContext delegate) implements RendererContext.Forwarding {

        /** {@inheritDoc} */
        @Override
        public @NotNull Optional<MCMeta> findMeta(@NotNull String textureId) {
            return Optional.of(MCMeta.EMPTY);
        }

        /** {@inheritDoc} */
        @Override
        public @NotNull Optional<MCMeta.Animation> findAnimation(@NotNull String textureId) {
            return findMeta(textureId).flatMap(MCMeta::animation);
        }

    }

    /**
     * A registrar over a copy of the shipped rows plus a fresh rig row.
     */
    private static @NotNull StyleRegistrar registrarWithRig() {
        ConcurrentLinkedMap<String, Entity> defs = Concurrent.newLinkedMap(shipped);
        defs.put(PlayerRig.ENTITY_ID, PlayerRig.entityRow());
        return StyleRegistrar.of(defs);
    }

    /**
     * One opaque 64x64 sheet in a single colour, so drawn geometry lands visible pixels.
     */
    private static @NotNull PixelBuffer sheet(int argb) {
        PixelBuffer sheet = PixelBuffer.create(64, 64);
        sheet.fill(argb);
        return sheet;
    }

    /**
     * The sheet as PNG bytes, the shape the byte-decoding knob takes.
     */
    private static byte @NotNull [] pngBytes(@NotNull PixelBuffer sheet) {
        ImageFactory factory = new ImageFactory();
        return factory.toByteArray(factory.fromImage(sheet.toBufferedImage()), ImageFormat.PNG);
    }

    /**
     * How many pixels of a frame carry any alpha at all.
     */
    private static int opaqueCount(@NotNull PixelBuffer buffer) {
        int count = 0;
        for (int y = 0; y < buffer.height(); y++)
            for (int x = 0; x < buffer.width(); x++)
                if ((buffer.getPixel(x, y) >>> 24) != 0) count++;
        return count;
    }

}
