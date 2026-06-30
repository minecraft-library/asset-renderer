package lib.minecraft.renderer.engine.compose;

import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.ImageData;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.asset.Item;
import lib.minecraft.renderer.engine.GlintMask;
import lib.minecraft.renderer.engine.kit.GlintKit;
import lib.minecraft.renderer.options.ItemOptions;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * Shared terminal stage that composites the enchantment glint (foil) onto a finished render buffer.
 * <p>
 * Glint is the renderer's frame-multiplying tail: a single finished buffer becomes a static frame
 * when nothing glints, or a scrolling animated foil when it does. That is why it is a stage - a
 * sibling to {@link FinalizeStage} and {@link AnimationStage} - rather than an {@link ImageLayer}
 * (which writes one buffer) or a {@link GeometryLayer} (which contributes triangles). It runs after
 * the layer stack has produced the finished buffer.
 * <p>
 * Every renderer composites the same way - pack-resolve the glint texture, fall back to a static
 * frame when absent, run {@link GlintKit#applyGlint}, and wrap the frames - and differs only in the
 * glint predicate and {@link GlintKit.GlintOptions} preset. {@link #forItem} and {@link #forArmor}
 * capture those two decisions; {@link #apply} is the shared core. The glint texture is supplied
 * through a {@link TextureResolver} (typically {@code engine::tryResolveTexture}) so this stage stays
 * free of the engine class hierarchy.
 *
 * @see GlintKit
 * @see GlintMask
 */
public final class GlintStage {

    /**
     * Output frame rate for worn-armor glint, matching every vanilla armor render site.
     */
    private static final int ARMOR_GLINT_FPS = 30;

    /**
     * Resolves a glint scroll texture by its namespaced id.
     */
    @FunctionalInterface
    public interface TextureResolver {

        /**
         * Resolves the glint texture for the given id.
         *
         * @param textureId the namespaced glint texture id
         * @return the resolved texture, or empty when the active pack stack provides none
         */
        @NotNull Optional<PixelBuffer> resolve(@NotNull String textureId);
    }

    private GlintStage() {
    }

    /**
     * Composites the item enchantment glint onto a finished buffer, deriving the glint flag from the
     * item and its options ({@code glintOverride}, else the item's always-glinted flag or
     * {@code enchanted}). Foils every opaque pixel - the whole-subject item behaviour - at the item
     * preset and the caller's animate flag.
     *
     * @param resolver the glint-texture resolver, typically {@code engine::tryResolveTexture}
     * @param buffer the finished item buffer
     * @param item the resolved item, consulted for its always-glinted flag
     * @param options the item render options
     * @return a static image when no glint applies, an animated foil otherwise
     */
    public static @NotNull ImageData forItem(
        @NotNull TextureResolver resolver,
        @NotNull PixelBuffer buffer,
        @NotNull Item item,
        @NotNull ItemOptions options
    ) {
        boolean glinted = options.getGlintOverride().orElse(item.isAlwaysGlinted() || options.isEnchanted());
        return apply(resolver, buffer, glinted, options.isAnimateGlint(),
            GlintKit.GlintOptions.itemDefault(options.getFramesPerSecond()), null);
    }

    /**
     * Composites the worn-armor enchantment glint onto a finished buffer, confined to the marked
     * pixels of {@code glintMask} so the foil lands on the armor rather than the whole silhouette.
     * Always animated, at the armor preset.
     *
     * @param resolver the glint-texture resolver, typically {@code engine::tryResolveTexture}
     * @param buffer the finished render buffer
     * @param enchanted whether the worn armor is enchanted and should show a glint
     * @param glintMask the armor coverage mask, or {@code null} to foil every opaque pixel
     * @return a static image when no glint applies, an animated foil otherwise
     */
    public static @NotNull ImageData forArmor(
        @NotNull TextureResolver resolver,
        @NotNull PixelBuffer buffer,
        boolean enchanted,
        @Nullable GlintMask glintMask
    ) {
        return apply(resolver, buffer, enchanted, true,
            GlintKit.GlintOptions.armorDefault(ARMOR_GLINT_FPS), glintMask);
    }

    /**
     * Wraps a finished buffer into an {@link ImageData}, optionally applying a scrolling glint overlay
     * animation. If {@code enchanted} is {@code false} or the resolver yields no glint texture, the
     * buffer is returned as a single-frame static image; otherwise the configured glint is composed
     * via {@link GlintKit#applyGlint}. When {@code animate} is {@code true} the frames are emitted at
     * {@code glintOptions.framesPerSecond()}; when {@code false} only the frame-0 (un-scrolled) glint
     * is kept and returned as a static image - the atlas path uses this so a glinted tile never
     * promotes the whole grid to animated output.
     *
     * @param resolver the glint-texture resolver
     * @param buffer the finished render surface
     * @param enchanted whether the subject is enchanted and should show a glint
     * @param animate whether to emit the animated scroll; {@code false} keeps a single static frame
     * @param glintOptions the glint preset, carrying the texture id and frame rate
     * @param glintMask the glint coverage mask, or {@code null} to foil every opaque pixel
     * @return a static image when no glint is applied or {@code animate} is false, an animated image otherwise
     */
    public static @NotNull ImageData apply(
        @NotNull TextureResolver resolver,
        @NotNull PixelBuffer buffer,
        boolean enchanted,
        boolean animate,
        @NotNull GlintKit.GlintOptions glintOptions,
        @Nullable GlintMask glintMask
    ) {
        if (!enchanted)
            return Frames.staticFrame(buffer);

        Optional<PixelBuffer> glintTexture = resolver.resolve(glintOptions.glintTextureId());
        if (glintTexture.isEmpty())
            return Frames.staticFrame(buffer);

        ConcurrentList<PixelBuffer> frames = GlintKit.applyGlint(buffer, glintTexture.get(), glintOptions, glintMask);
        if (!animate)
            return Frames.staticFrame(frames.getFirst());

        int frameDelayMs = Math.max(1, Math.round(1000f / glintOptions.framesPerSecond()));
        return Frames.wrapFrames(frames, frameDelayMs);
    }
}
