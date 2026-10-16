package lib.minecraft.renderer.engine.frame;

import dev.simplified.image.pixel.PixelBuffer;
import dev.simplified.image.pixel.PixelBufferPool;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A scope around one render in which the first frame it rasterizes is drawn into a buffer leased from
 * the thread's {@link PixelBufferPool}, returned to the pool once that frame has been drawn elsewhere -
 * so the next render on the thread draws into the same buffer rather than a new one.
 * <p>
 * A loan is opened around one render on one thread and closed after it. While it is open, the first
 * frame that thread rasterizes through {@link RasterPass#renderFrame} draws into a buffer the loan
 * leases - one the pool holds when the thread has returned one of the frame's size, transparent as a new
 * one is. {@link #giveBack} returns the lease, but only when the picture it is handed holds that
 * buffer's pixels: a render that drew its picture into another buffer, or copied the one it was lent,
 * returns nothing, and the lent buffer goes as any other would.
 * <p>
 * Giving back is a promise that nothing reads the picture again. The grid makes it for a streamed
 * sheet whose caller asks it to with {@code GridOptions.recycleTiles}, and nothing else does.
 * <p>
 * Loans nest per thread, the innermost answering: a worker that takes up another render while it waits
 * inside one opens that render's loan over its own, so each render borrows at most one buffer, and only
 * for its own first frame.
 */
public final class FrameLoan implements AutoCloseable {

    /** The innermost loan open on each thread, absent where none is. */
    private static final ThreadLocal<FrameLoan> INNERMOST = new ThreadLocal<>();

    /** The loan this one was opened inside, on the same thread, or {@code null} at the outermost. */
    private final @Nullable FrameLoan outer;

    /** Whether this loan's first frame has been drawn, after which it lends nothing. */
    private boolean spent;

    /** The lease holding the buffer this loan lent, until it is given back or let go. */
    private PixelBufferPool.@Nullable Lease lease;

    private FrameLoan(@Nullable FrameLoan outer) {
        this.outer = outer;
    }

    /**
     * Opens a loan on the calling thread, inside whichever loan is already open there.
     *
     * @return the loan, to be closed on the same thread
     */
    public static @NotNull FrameLoan open() {
        FrameLoan loan = new FrameLoan(INNERMOST.get());
        INNERMOST.set(loan);
        return loan;
    }

    /**
     * Returns a transparent buffer for a frame the calling thread is about to rasterize - one the
     * innermost open loan leases, when it has lent none yet, and a new buffer otherwise.
     *
     * @param width the frame width
     * @param height the frame height
     * @return a buffer of the frame's size, every pixel transparent
     */
    static @NotNull PixelBuffer frame(int width, int height) {
        FrameLoan loan = INNERMOST.get();
        if (loan == null || loan.spent || width <= 0 || height <= 0)
            return PixelBuffer.create(width, height);

        loan.spent = true;
        loan.lease = PixelBufferPool.acquire(width, height);
        return loan.lease.buffer();
    }

    /**
     * Returns the buffer this loan lent to the pool, when {@code drawn} holds its pixels, and lets it
     * go otherwise.
     *
     * @param drawn the picture the render answered, which nothing reads after this
     */
    public void giveBack(@NotNull PixelBuffer drawn) {
        PixelBufferPool.Lease lent = this.lease;
        this.lease = null;

        if (lent != null && drawn.data() == lent.buffer().data())
            lent.close();
    }

    /**
     * Closes this loan, leaving the loan it was opened inside as the thread's innermost. A buffer it
     * lent and was not given back stays with whatever holds it.
     */
    @Override
    public void close() {
        this.lease = null;

        if (this.outer == null)
            INNERMOST.remove();
        else
            INNERMOST.set(this.outer);
    }

}
