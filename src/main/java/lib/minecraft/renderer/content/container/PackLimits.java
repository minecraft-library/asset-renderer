package lib.minecraft.renderer.content.container;

import lib.minecraft.renderer.exception.ContentException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The ceilings a pack is read under - how much one pack may ask the reader to hold.
 *
 * <p>A pack file is a few bytes that name a far larger thing: a compressed entry names its inflated
 * size, an image header its pixel count, a range, an animation or a permutation list the entries it
 * expands to. Each ceiling here bounds one of those, and each is tested before the thing it bounds is
 * allocated, so a file that names more is refused for the cost of reading its header.
 *
 * <p>{@link #DEFAULTS} is what every read uses unless it is handed another. Each default is set from the
 * 26.1 vanilla pack with room above it for a large community pack; the figure it was measured against is
 * given with the component. A caller that reads packs it trusts to be larger raises a ceiling through a
 * {@code with} method and hands the result to the read - a stack is built under the limits its client
 * options carry.
 *
 * <p>What a ceiling bounds is one file's share. A pack still holds as many files as its entry and byte
 * ceilings admit, each of which may reach its own ceiling.
 *
 * @param entryBytes the inflated bytes one archive entry may hold. The largest file the vanilla pack
 *     keeps is 502,008 bytes and the largest in the client jar 4,522,247; a 4096 x 4096 RGBA image that
 *     does not compress at all is 64 MiB.
 * @param packBytes the inflated bytes the entries of one archive may hold together. The vanilla pack
 *     holds 14,467,356.
 * @param entries the entries one zip may list, its directory records among them. The vanilla pack holds
 *     18,744 files.
 * @param imagePixels the pixels one image may declare, its width by its height. The largest vanilla
 *     texture is 1024 x 256, 262,144 pixels, and a 512 x 512 texture is the same count.
 * @param heldPixels the decoded pixels one pack stack keeps for later reads. A texture decoded past it is
 *     still served, and decoded again on its next read. Every vanilla texture together is 9,231,487
 *     pixels.
 * @param tiles the tiles one connected-texture rule's ranges may expand to. The largest fixed method
 *     takes 47.
 * @param permutations the sprites the paletted permutations of one stack, or of one atlas, may add.
 *     Vanilla's two trim atlases add 640.
 * @param frames the frames one animated strip may be cut into. The longest vanilla strip holds 32.
 */
public record PackLimits(
    long entryBytes,
    long packBytes,
    int entries,
    long imagePixels,
    long heldPixels,
    int tiles,
    int permutations,
    int frames
) {

    /**
     * The limits a read takes when it is handed none: 128 MiB an entry, 1 GiB and 1,048,576 entries an
     * archive, 4096 x 4096 pixels an image, a quarter of the heap's maximum in kept pixels, 1,024 tiles a
     * rule, 65,536 permutations and 4,096 frames a strip.
     */
    public static final @NotNull PackLimits DEFAULTS = new PackLimits(
        1L << 27,
        1L << 30,
        1 << 20,
        1L << 24,
        Runtime.getRuntime().maxMemory() / (4 * Integer.BYTES),
        1 << 10,
        1 << 16,
        1 << 12
    );

    /** The largest array an entry is read into. */
    private static final long MAX_ARRAY = Integer.MAX_VALUE - 8;

    /** The bytes a stream of undeclared length is read in at a time. */
    private static final int BLOCK = 1 << 20;

    /**
     * Constructs a new {@code PackLimits}.
     *
     * @throws IllegalArgumentException if a ceiling is negative
     */
    public PackLimits {
        if (entryBytes < 0 || packBytes < 0 || entries < 0 || imagePixels < 0 || heldPixels < 0
            || tiles < 0 || permutations < 0 || frames < 0)
            throw new IllegalArgumentException("A pack limit cannot be negative");
    }

    /**
     * These limits with another ceiling on one archive entry's inflated bytes.
     *
     * @param entryBytes the inflated bytes one archive entry may hold
     * @return the changed limits
     */
    public @NotNull PackLimits withEntryBytes(long entryBytes) {
        return new PackLimits(entryBytes, this.packBytes, this.entries, this.imagePixels, this.heldPixels, this.tiles, this.permutations, this.frames);
    }

    /**
     * These limits with another ceiling on one archive's inflated bytes.
     *
     * @param packBytes the inflated bytes the entries of one archive may hold together
     * @return the changed limits
     */
    public @NotNull PackLimits withPackBytes(long packBytes) {
        return new PackLimits(this.entryBytes, packBytes, this.entries, this.imagePixels, this.heldPixels, this.tiles, this.permutations, this.frames);
    }

    /**
     * These limits with another ceiling on one archive's entry count.
     *
     * @param entries the entries one zip may list
     * @return the changed limits
     */
    public @NotNull PackLimits withEntries(int entries) {
        return new PackLimits(this.entryBytes, this.packBytes, entries, this.imagePixels, this.heldPixels, this.tiles, this.permutations, this.frames);
    }

    /**
     * These limits with another ceiling on one image's pixels.
     *
     * @param imagePixels the pixels one image may declare
     * @return the changed limits
     */
    public @NotNull PackLimits withImagePixels(long imagePixels) {
        return new PackLimits(this.entryBytes, this.packBytes, this.entries, imagePixels, this.heldPixels, this.tiles, this.permutations, this.frames);
    }

    /**
     * These limits with another ceiling on the decoded pixels a stack keeps.
     *
     * @param heldPixels the decoded pixels one pack stack keeps for later reads
     * @return the changed limits
     */
    public @NotNull PackLimits withHeldPixels(long heldPixels) {
        return new PackLimits(this.entryBytes, this.packBytes, this.entries, this.imagePixels, heldPixels, this.tiles, this.permutations, this.frames);
    }

    /**
     * These limits with another ceiling on one connected-texture rule's expanded tiles.
     *
     * @param tiles the tiles one rule's ranges may expand to
     * @return the changed limits
     */
    public @NotNull PackLimits withTiles(int tiles) {
        return new PackLimits(this.entryBytes, this.packBytes, this.entries, this.imagePixels, this.heldPixels, tiles, this.permutations, this.frames);
    }

    /**
     * These limits with another ceiling on paletted permutations.
     *
     * @param permutations the sprites the paletted permutations of one stack, or of one atlas, may add
     * @return the changed limits
     */
    public @NotNull PackLimits withPermutations(int permutations) {
        return new PackLimits(this.entryBytes, this.packBytes, this.entries, this.imagePixels, this.heldPixels, this.tiles, permutations, this.frames);
    }

    /**
     * These limits with another ceiling on one strip's frames.
     *
     * @param frames the frames one animated strip may be cut into
     * @return the changed limits
     */
    public @NotNull PackLimits withFrames(int frames) {
        return new PackLimits(this.entryBytes, this.packBytes, this.entries, this.imagePixels, this.heldPixels, this.tiles, this.permutations, frames);
    }

    /**
     * Reads a stream to its end, refusing it once it yields more than a ceiling.
     *
     * <p>The stream is read a block at a time and the ceiling tested after each, and nothing is joined
     * until the stream has ended within it, so a stream that inflates past the ceiling costs the ceiling
     * and one block to refuse rather than its whole length. A stream no longer than a block is handed
     * back as the one array it was read into.
     *
     * @param in the stream to read
     * @param ceiling the most bytes the stream may yield
     * @return the stream's bytes, or {@code null} where it yields more than the ceiling
     * @throws IOException if the stream cannot be read, or holds more than an array can
     */
    static byte @Nullable [] readWithin(@NotNull InputStream in, long ceiling) throws IOException {
        List<byte[]> blocks = new ArrayList<>();
        long total = 0;
        while (true) {
            byte[] block = in.readNBytes(BLOCK);
            total += block.length;
            if (total > ceiling) return null;
            if (total > MAX_ARRAY) throw new IOException("An entry larger than the largest array cannot be read");

            // A short block is the stream's last, and the only one is the whole stream.
            if (block.length < BLOCK && blocks.isEmpty()) return block;
            blocks.add(block);
            if (block.length < BLOCK) break;
        }

        byte[] bytes = new byte[(int) total];
        int at = 0;
        for (byte[] block : blocks) {
            System.arraycopy(block, 0, bytes, at, block.length);
            at += block.length;
        }
        return bytes;
    }

    /**
     * Reads a stream whose length is declared, refusing it once it yields more than a ceiling.
     *
     * <p>A stream that turns out longer than declared is read on to its end, or to the ceiling, so a
     * declaration that understates a stream costs a copy rather than the stream's tail.
     *
     * @param in the stream to read
     * @param declared the length the stream is declared to have, at most the ceiling
     * @param ceiling the most bytes the stream may yield
     * @return the stream's bytes, or {@code null} where it yields more than the ceiling
     * @throws IOException if the stream cannot be read, or holds more than an array can
     */
    static byte @Nullable [] readWithin(@NotNull InputStream in, int declared, long ceiling) throws IOException {
        byte[] bytes = in.readNBytes(declared);
        int next = in.read();
        if (next < 0) return bytes;
        if (bytes.length >= ceiling) return null;

        byte[] rest = readWithin(in, ceiling - bytes.length - 1);
        if (rest == null) return null;

        if ((long) bytes.length + 1 + rest.length > MAX_ARRAY)
            throw new IOException("An entry larger than the largest array cannot be read");

        byte[] whole = Arrays.copyOf(bytes, bytes.length + 1 + rest.length);
        whole[bytes.length] = (byte) next;
        System.arraycopy(rest, 0, whole, bytes.length + 1, rest.length);
        return whole;
    }

    /**
     * The refusal of a read that ran past a ceiling.
     *
     * @param what the thing that ran over, as a sentence's subject
     * @param ceiling the ceiling it ran past
     * @param unit what the ceiling counts
     * @return the exception to throw
     */
    static @NotNull ContentException over(@NotNull String what, long ceiling, @NotNull String unit) {
        return new ContentException("%s holds more than the limit of %d %s", what, ceiling, unit);
    }

}
