package lib.minecraft.renderer.bake.texture;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.asset.Item.LayerTint;
import lib.minecraft.renderer.asset.Item;
import lib.minecraft.renderer.asset.model.ModelElement;
import lib.minecraft.renderer.asset.model.ModelFace;
import lib.minecraft.renderer.asset.model.ModelTexture;
import lib.minecraft.renderer.asset.pack.Flipbook;
import lib.minecraft.renderer.call.request.DecorationOptions;
import lib.minecraft.renderer.call.request.ItemContext;
import lib.minecraft.renderer.call.request.ItemOptions;
import lib.minecraft.renderer.content.index.CitResult;
import lib.minecraft.renderer.content.index.GlintPolicy;
import lib.minecraft.renderer.content.index.RendererContext;
import lib.minecraft.renderer.exception.RenderException;
import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.vanilla.DecodedComponent;
import lib.minecraft.renderer.vanilla.TintSource;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.OptionalInt;

/**
 * The colour an item's {@code layerN} sprites carry: which tintindex a layer answers to, what colour
 * that resolves to against the caller's overrides, the composite the tinted layers fold into, and the
 * glint finish the stack ends on.
 * <p>
 * Every entry is a function of a resolved {@link Item} and the caller's options; nothing here draws
 * to a canvas the caller owns.
 */
@UtilityClass
@Parity(claim = "engine-renders", mode = Mode.DEMOTE)
public class ItemTint {

    /**
     * Prefix for multi-layer item texture keys ({@code layer0}, {@code layer1}, ...).
     */
    public static final @NotNull String LAYER_TEXTURE_PREFIX = "layer";

    /**
     * Resolves the effective ARGB tint for {@code layerN} of an item. When the item carries a
     * {@link LayerTint} for that layer (from its definition's {@code model.tints[]}), the colour is
     * that tint {@link #resolve resolved}. When it has none, falls back to the vanilla
     * {@code item/generated} convention: the caller's {@link DecorationOptions#getTintColor()} applies
     * to the tintindex-0 slot ({@link #tintIndexForLayer(Item, int)}), every other layer renders
     * untinted. Returns {@link ColorMath#WHITE} for an untinted layer.
     * <p>
     * This is the rule {@link #layerTints(RendererContext, ConcurrentList, ItemOptions)} states for
     * a model's faces, read at one layer: a generated model's layer index is its tintindex.
     *
     * @param context the renderer context the definition tints resolve against
     * @param item the item being rendered
     * @param layerIndex the layer index being rendered
     * @param options the caller's options, supplying the overrides
     * @return the ARGB colour the layer multiplies by
     */
    public static int resolveLayerTint(
        @NotNull RendererContext context,
        @NotNull Item item,
        int layerIndex,
        @NotNull ItemOptions options
    ) {
        ConcurrentList<LayerTint> tints = item.tints();
        if (layerIndex < tints.size())
            return resolve(context, tints.get(layerIndex), options);
        int tint = options.getDecoration().getTintColor().orElse(ColorMath.WHITE);
        return tint != ColorMath.WHITE && tintIndexForLayer(item, layerIndex) == 0 ? tint : ColorMath.WHITE;
    }

    /**
     * Resolves the colours a model's tinted faces pick by tintindex: each definition tint
     * {@link #resolve resolved} at its own index. Where the definition lists none, the caller's
     * {@link DecorationOptions#getTintColor()} stands at index 0 - the slot the flat
     * {@code item/generated} convention gives {@code layer0} - and every other index stays white. A
     * face at no tintindex never takes a colour from here.
     *
     * @param context the renderer context the definition tints resolve against
     * @param tints the item definition's tints, in tintindex order
     * @param options the caller's options, supplying the overrides
     * @return the ARGB colour per tintindex, empty where neither the definition nor the caller names one
     */
    public static int @NotNull [] layerTints(
        @NotNull RendererContext context,
        @NotNull ConcurrentList<LayerTint> tints,
        @NotNull ItemOptions options
    ) {
        if (tints.isEmpty())
            return options.getDecoration().getTintColor().map(argb -> new int[]{ argb }).orElseGet(() -> new int[0]);
        return tints.stream().mapToInt(tint -> resolve(context, tint, options)).toArray();
    }

    /**
     * Calculates one item-definition tint against the caller's overrides and the pack stack, as
     * vanilla calculates each of an item model's tints on every render:
     * <ul>
     * <li>{@link LayerTint.Dye} - {@link DecorationOptions#getLeatherColor()} → the stack's
     * {@code minecraft:dyed_color}, read from the one {@linkplain ItemOptions#components() component
     * patch} the dispatch walk reads and made opaque as vanilla's dye source makes it →
     * {@link DecorationOptions#getTintColor()} → default.</li>
     * <li>{@link LayerTint.Potion} - {@link DecorationOptions#getPotionColor()} → the first
     * {@link ItemContext#potionEffects() potion effect}'s colour via
     * {@link RendererContext#findPotionEffectColor(String)} → {@link DecorationOptions#getTintColor()} → default.</li>
     * <li>{@link LayerTint.Firework} - {@link DecorationOptions#getFireworkColor()} → {@link DecorationOptions#getTintColor()} → default.</li>
     * <li>{@link LayerTint.Grass} - the {@link TintSource#GRASS} colormap sampled at the tint's climate
     * point, else the source's {@link TintSource#defaultArgb() default} on a context built without it.</li>
     * <li>{@link LayerTint.MapColor} - {@link DecorationOptions#getTintColor()} → default, forced
     * opaque.</li>
     * <li>{@link LayerTint.Constant} - the fixed value.</li>
     * </ul>
     *
     * @param context the renderer context the colormap and potion colours resolve against
     * @param tint the tint to calculate
     * @param options the caller's options, supplying the overrides
     * @return the ARGB colour the tinted layer multiplies by
     */
    public static int resolve(@NotNull RendererContext context, @NotNull LayerTint tint, @NotNull ItemOptions options) {
        return switch (tint) {
            case LayerTint.Dye dye -> options.getDecoration().getLeatherColor()
                .or(() -> stackDye(options))
                .or(options.getDecoration()::getTintColor)
                .orElse(dye.defaultColor());
            case LayerTint.Potion potion ->
                options.getDecoration().getPotionColor()
                    .or(() -> options.getContext().potionEffects().stream().findFirst().flatMap(id -> context.findPotionEffectColor(id).toOptional()))
                    .or(options.getDecoration()::getTintColor).orElse(potion.defaultColor());
            case LayerTint.Firework firework ->
                options.getDecoration().getFireworkColor().or(options.getDecoration()::getTintColor).orElse(firework.defaultColor());
            case LayerTint.Grass grass -> context.findColorMap(TintSource.GRASS)
                .map(map -> map.sample(grass.temperature(), grass.downfall()))
                .orElse(TintSource.GRASS.defaultArgb());
            case LayerTint.MapColor mapColor ->
                0xFF000000 | options.getDecoration().getTintColor().orElse(mapColor.defaultColor());
            case LayerTint.Constant constant -> constant.argb();
        };
    }

    /**
     * Reads the colour the stack's {@code minecraft:dyed_color} paints, opaque as vanilla's
     * {@code DyedItemColor.getOrDefault} answers it.
     *
     * @param options the caller's options, supplying the stack's component patch
     * @return the ARGB colour, or empty where the patch sets no dyed colour
     */
    private static @NotNull Optional<Integer> stackDye(@NotNull ItemOptions options) {
        OptionalInt rgb = DecodedComponent.dyedColor(options.components());
        return rgb.isPresent() ? Optional.of(0xFF000000 | rgb.getAsInt()) : Optional.empty();
    }

    /**
     * Composites an item's {@code layerN} sprites into a native-resolution {@link PixelBuffer},
     * multiplying each layer's {@link #resolveLayerTint resolved tint} in. Used by the HELD_3D
     * flat-slab path so tinted items (leather armour, potions, firework stars) carry their colour
     * onto the 3D slab the same way the GUI path tints them. Every layer is read through the context's
     * {@link RendererContext#withMissingTexture() missing-texture wrapper}, so a layer no pack supplies,
     * or that cannot be read, draws the checkerboard.
     */
    public static @NotNull PixelBuffer composeTintedLayers(
        @NotNull RendererContext context,
        @NotNull Item item,
        @NotNull ItemOptions options,
        @NotNull CitResult cit,
        int tick
    ) {
        String layer0Ref = cit.textureFor("layer0").map(ResourceId::id).orElse(item.textures().get("layer0"));
        if (layer0Ref == null || layer0Ref.isBlank())
            throw new RenderException("Item '%s' has no elements and no layer0 - nothing to render in Held3D path", item.id().id());
        RendererContext textures = context.withMissingTexture();
        PixelBuffer base = Flipbook.atTick(textures.resolveTexture(layer0Ref), textures.findFlipbook(layer0Ref), tick).get();
        PixelBuffer composite = PixelBuffer.create(base.width(), base.height());

        int layerIndex = 0;
        while (true) {
            String layerKey = LAYER_TEXTURE_PREFIX + layerIndex;
            String textureRef = cit.textureFor(layerKey).map(ResourceId::id).orElse(item.textures().get(layerKey));
            if (textureRef == null || textureRef.isBlank()) break;
            PixelBuffer layer = Flipbook.atTick(
                textures.resolveTexture(textureRef), textures.findFlipbook(textureRef), tick).get();
            int color = resolveLayerTint(context, item, layerIndex, options);
            // ColorMath.tint returns a multiplied copy (alpha preserved); blit composites it
            // source-over so layer0 lands cleanly even when the composite is still empty.
            PixelBuffer drawable = color != ColorMath.WHITE ? ColorMath.tint(layer, color) : layer;
            composite.blit(drawable, 0, 0);
            layerIndex++;
        }
        return composite;
    }

    /**
     * Looks up the {@code tintindex} that applies to {@code layerN} for a flat item. Prefers the
     * model-declared tintindex on any element face whose texture reference resolves to the layer,
     * falling back to the vanilla {@code item/generated} convention ({@code layerN} has tintindex
     * {@code N}) when the resolved model has no elements - which is the common case for flat
     * items.
     *
     * @param item the item being rendered
     * @param layerIndex the layer index being rendered
     * @return the tintindex for the layer, or {@code -1} when the layer should render untinted
     */
    public static int tintIndexForLayer(@NotNull Item item, int layerIndex) {
        ConcurrentList<ModelElement> elements = item.model().getElements();
        if (elements.isEmpty()) {
            // Vanilla item/generated convention: layer N has tintindex N.
            return layerIndex;
        }

        ConcurrentMap<String, ModelTexture> variables = item.model().getTextures();
        String layerKey = LAYER_TEXTURE_PREFIX + layerIndex;
        ModelTexture layerTexture = variables.get(layerKey);
        String layerRef = layerTexture == null ? null : layerTexture.sprite();
        for (ModelElement element : elements) {
            for (ModelFace face : element.getFaces().values()) {
                String faceRef = face.getTexture();
                if (faceRef.equals("#" + layerKey) || faceRef.equals(layerRef))
                    return face.getTintIndex();

                if (item.model().resolveTextureReference(faceRef).filter(id -> id.equals(layerRef)).isPresent())
                    return face.getTintIndex();
            }
        }
        return -1;
    }

    /**
     * Builds the item enchantment-glint finish, deriving the glint flag
     * ({@code glintOverride}, else the item's always-glinted flag or {@code enchanted}) the GUI and
     * held-item paths share, then applying the CIT-derived {@link GlintPolicy}: a
     * {@link GlintPolicy.Suppressed} decision forces no glint ({@code useGlint=false}); a
     * {@link GlintPolicy.Replaced} decision swaps the glint texture id (a matched
     * {@code type=enchantment} rule) while leaving whether the item glints to its own flag; a
     * {@link GlintPolicy.Default} decision is vanilla behaviour.
     */
    public static @NotNull GlintKit.Foil itemGlint(
        @NotNull RendererContext context, @NotNull Item item, @NotNull ItemOptions options, @NotNull GlintPolicy glint
    ) {
        return itemGlint(context, item.alwaysGlinted(), options, glint);
    }

    /**
     * Builds the item enchantment-glint finish for a subject by its intrinsic-foil flag, for a held
     * block that no item index entry carries - the same derivation and {@link GlintPolicy} arms as
     * {@link #itemGlint(RendererContext, Item, ItemOptions, GlintPolicy)}.
     *
     * @param context the renderer context the glint texture resolves against
     * @param alwaysGlinted whether the subject glints whatever the caller asks
     * @param options the caller's options, supplying the glint override, enchantment and timing
     * @param glint the CIT-derived glint decision
     * @return the glint finish
     */
    public static @NotNull GlintKit.Foil itemGlint(
        @NotNull RendererContext context, boolean alwaysGlinted, @NotNull ItemOptions options, @NotNull GlintPolicy glint
    ) {
        boolean glinted = options.getGlintOverride().orElse(alwaysGlinted || options.isEnchanted());
        return switch (glint) {
            case GlintPolicy.Suppressed ignored ->
                GlintKit.Foil.item(context, false, options.isAnimateGlint(), options.getFramesPerSecond());
            case GlintPolicy.Replaced replaced ->
                GlintKit.Foil.itemReplaced(context, glinted, options.isAnimateGlint(), options.getFramesPerSecond(), replaced.texture().id());
            case GlintPolicy.Default ignored ->
                GlintKit.Foil.item(context, glinted, options.isAnimateGlint(), options.getFramesPerSecond());
        };
    }

}
