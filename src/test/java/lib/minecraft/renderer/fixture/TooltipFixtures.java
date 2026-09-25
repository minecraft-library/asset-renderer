package lib.minecraft.renderer.fixture;

import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.nbt.tag.CompoundTag;
import lib.minecraft.nbt.tag.StringTag;
import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.asset.ColorMap;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.Item;
import lib.minecraft.renderer.asset.pack.Flipbook;
import lib.minecraft.renderer.asset.pack.MCMeta;
import lib.minecraft.renderer.port.RendererContext;
import lib.minecraft.renderer.request.ItemContext;
import lib.minecraft.renderer.vanilla.TintSource;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NonNull;

import java.util.Map;
import java.util.Optional;

/**
 * Tooltip chrome inputs for the tests that resolve or draw a sprite chrome - the nine-slice sidecars
 * vanilla's tooltip sprites ship, an item carrying a {@code minecraft:tooltip_style} component, and a
 * context that answers only the sprites and sidecars it is seeded with.
 */
public final class TooltipFixtures {

    private TooltipFixtures() {}

    /** The background sprite's sidecar, carrying the nine-slice scaling its shipped one declares */
    public static final @NotNull MCMeta BG_META = guiMeta(new MCMeta.GuiScaling(
        MCMeta.GuiScaling.Type.NINE_SLICE, -1, -1, new MCMeta.GuiScaling.Border(9, 9, 9, 9), false));

    /** The frame sprite's sidecar, whose nine-slice scaling stretches its inner slices */
    public static final @NotNull MCMeta FRAME_META = guiMeta(new MCMeta.GuiScaling(
        MCMeta.GuiScaling.Type.NINE_SLICE, -1, -1, new MCMeta.GuiScaling.Border(10, 10, 10, 10), true));

    /**
     * Wraps a GUI scaling in the sidecar document the context answers with, every other section absent -
     * a sprite sidecar declaring {@code gui.scaling} alone, which is what vanilla ships.
     *
     * @param scaling the scaling section the sidecar declares
     * @return the sidecar carrying it
     */
    public static @NotNull MCMeta guiMeta(@NotNull MCMeta.GuiScaling scaling) {
        return new MCMeta(new ResourceId("minecraft", "tooltip"), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.of(scaling), Optional.empty());
    }

    /**
     * Builds an item context carrying a {@code minecraft:tooltip_style} component.
     *
     * @param style the style key the component names
     * @return the item context the resolution surface reads
     */
    public static @NotNull ItemContext itemWithStyle(@NotNull String style) {
        CompoundTag components = new CompoundTag();
        components.put("minecraft:tooltip_style", new StringTag(style));
        CompoundTag root = new CompoundTag();
        root.put("components", components);
        return ItemContext.builder().itemId("minecraft:diamond_sword").nbt(root).build();
    }

    /**
     * Seeds a context that resolves only the given textures and sidecars, and no animation.
     *
     * @param textures the texture id to pixels bindings the context can resolve
     * @param metas the texture id to sidecar bindings the context can resolve
     * @return the seeded context
     */
    public static @NotNull RendererContext stubContext(@NotNull Map<String, PixelBuffer> textures,
                                                       @NotNull Map<String, MCMeta> metas) {
        return new StubContext(textures, metas, Map.of());
    }

    /**
     * Seeds a context that resolves only the given textures, sidecars and animations.
     *
     * @param textures the texture id to pixels bindings the context can resolve
     * @param metas the texture id to sidecar bindings the context can resolve
     * @param animations the texture id to animation sidecar bindings the context can resolve
     * @return the seeded context
     */
    public static @NotNull RendererContext stubContext(@NotNull Map<String, PixelBuffer> textures,
                                                       @NotNull Map<String, MCMeta> metas,
                                                       @NotNull Map<String, MCMeta.Animation> animations) {
        return new StubContext(textures, metas, animations);
    }

    /**
     * A minimal renderer context that resolves only the textures + sidecars + animations it was seeded
     * with.
     *
     * @param textures the texture id to pixels bindings this context can resolve
     * @param metas the texture id to sidecar bindings this context can resolve
     * @param animations the texture id to animation sidecar bindings this context can resolve
     */
    private record StubContext(Map<String, PixelBuffer> textures, Map<String, MCMeta> metas,
                               Map<String, MCMeta.Animation> animations) implements RendererContext {
        @Override public @NotNull Optional<Block> findBlock(@NotNull String id) { return Optional.empty(); }
        @Override public @NotNull Optional<ColorMap> findColorMap(@NotNull TintSource target) { return Optional.empty(); }
        @Override public @NotNull Optional<Entity> findEntity(@NotNull String id) { return Optional.empty(); }
        @Override public @NotNull Optional<Item> findItem(@NotNull String id) { return Optional.empty(); }
        @Override public @NotNull Optional<PixelBuffer> resolveTexture(@NonNull String textureId) { return Optional.ofNullable(this.textures.get(textureId)); }
        @Override public @NotNull Optional<MCMeta> findMeta(@NotNull String textureId) { return Optional.ofNullable(this.metas.get(textureId)); }
        @Override public @NotNull Optional<MCMeta.Animation> findAnimation(@NotNull String textureId) { return Optional.ofNullable(this.animations.get(textureId)); }
        @Override public @NotNull Optional<Flipbook> findFlipbook(@NotNull String textureId) { return Flipbook.of(findAnimation(textureId), () -> resolveTexture(textureId)); }
    }

}
