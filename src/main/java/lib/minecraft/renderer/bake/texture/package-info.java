/**
 * Composing the pixel buffer a subject's triangles sample - the textures vanilla builds at render
 * time out of the sprites a pack ships, rather than reading as one file.
 *
 * <p>Three of them fold a stack of sprites into one buffer.
 * {@link lib.minecraft.renderer.bake.texture.BannerKit BannerKit} paints a banner or shield's base dye
 * and tints each pattern layer over it, off the banner or the shield sheet as its
 * {@link lib.minecraft.renderer.bake.texture.BannerKit.Variant Variant} selects.
 * {@link lib.minecraft.renderer.bake.texture.ItemTint ItemTint} answers which tint index an item's
 * {@code layerN} sprite carries and what colour that resolves to against the caller's overrides, and
 * composes the tinted layers. {@link lib.minecraft.renderer.bake.texture.TrimKit TrimKit} permutes a
 * grayscale armour-trim pattern through a material's palette into a ready-to-composite overlay.
 *
 * <p>{@link lib.minecraft.renderer.bake.texture.GlintKit GlintKit} scrolls the enchantment glint over a
 * base image and adds it on, frame by frame; its
 * {@link lib.minecraft.renderer.bake.texture.GlintKit.Foil Foil} is the glint finish a baked schedule
 * ends on, for a whole item or a worn piece.
 * {@link lib.minecraft.renderer.bake.texture.PortalBake PortalBake} transcribes vanilla's end-portal
 * star-field shader onto the CPU and bakes it one face at a time.
 *
 * <p>{@link lib.minecraft.renderer.bake.texture.TextureRefusal TextureRefusal} hands a reader the pixels a
 * texture lookup answered, refusing an id no pack serves and a file that cannot be decoded, each in its
 * own words.
 *
 * <p>A type that yields no pixel buffer does not belong here.
 *
 * <p><b>Parity.</b> Every member declares its own claims; the package declares none.
 */
package lib.minecraft.renderer.bake.texture;
