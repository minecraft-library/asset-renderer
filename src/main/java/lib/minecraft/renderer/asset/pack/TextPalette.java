package lib.minecraft.renderer.asset.pack;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.vanilla.id.PackId;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import org.jetbrains.annotations.NotNull;

/**
 * The colours a pack's text shader draws text in, in place of the ones it is given - the switch the
 * shader runs over every text vertex colour, read from the pack: each swapped colour's red, green and
 * blue, and the red, green and blue drawn in its place.
 * <p>
 * A colour is swapped by its red, green and blue alone, as the shader keys a vertex by them, and keeps
 * its top byte. A colour the table holds no key for is drawn as given, and so is every colour under
 * {@link #VANILLA}, the table of the program vanilla draws text with.
 *
 * @param id the shader file the table was read from
 * @param pack the pack shipping it
 * @param swaps each swapped colour's 24-bit rgb, and the 24-bit rgb drawn in its place
 */
public record TextPalette(@NotNull ResourceId id, @NotNull PackId pack, @NotNull ConcurrentMap<Integer, Integer> swaps) {

    /**
     * The table of the program vanilla draws text with, which swaps no colour - what a stack whose
     * packs ship no text shader draws its text through.
     */
    public static final @NotNull TextPalette VANILLA = new TextPalette(
        new ResourceId("minecraft", "shaders/core/rendertype_text.vsh"), PackId.VANILLA, Concurrent.newUnmodifiableMap());

    /**
     * Answers the colour drawn for a text colour: its swap where the table holds its red, green and blue,
     * its top byte kept, and the colour itself otherwise.
     *
     * @param argb the text colour - its top byte the alpha, or zero for a bare rgb
     * @return the colour drawn in its place
     */
    public int swap(int argb) {
        if (this.swaps.isEmpty()) return argb;

        Integer swapped = this.swaps.get(argb & 0xFFFFFF);
        return swapped == null ? argb : (argb & 0xFF000000) | swapped;
    }

}
