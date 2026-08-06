package lib.minecraft.renderer.tooling.kernel;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Bounded FIFO/LIFO hybrid for accumulating bytecode literals between control points -
 * each parser feeds its {@code readXLiteral} results in through {@link #push(Object)},
 * then pops them in LIFO order when a builder-dispatch instruction (e.g.
 * {@code constant(int, int)} or {@code addBox(FFFFFF)}) is encountered. When the
 * per-parser capacity is exceeded (4 for tints, 8 for potion colours, 16 for block
 * entities) the oldest entry is evicted, matching the removeFirst eviction the legacy
 * parsers used (backed by {@link ArrayDeque}). Typed pop helpers
 * ({@link #popInt()}, {@link #popFloat()}, {@link #popString()}) return {@code null}
 * when the stack is empty <i>or</i> when the top entry is not of the requested type,
 * so a caller hunting ints after a float-consuming descriptor mismatch doesn't
 * silently pick up a wrong-type value.
 *
 * <p>For parsers that need to flag "I observed a value here but it came from a non-literal
 * source (a local variable populated by computation, a method-call result, ...)" - distinct
 * from "the stack was empty" or "the value was the wrong type" - {@link #pushNonLiteral()}
 * pushes a sentinel marker. The {@code *OrZero} family
 * ({@link #popIntOrZero(Diagnostics, String, String) popIntOrZero},
 * {@link #popFloatOrZero(Diagnostics, String, String) popFloatOrZero}) consume that
 * sentinel by returning a primitive zero <i>and</i> emitting a contextualised WARN. Empty
 * stack on these methods is silent zero (matches the original
 * {@code GeometryParser} accounting).
 */
public final class LiteralStack {

    /**
     * Sentinel singleton pushed by {@link #pushNonLiteral()}. Extends {@link Number} with
     * zero values so an arithmetic site that pops the sentinel and calls
     * {@code .intValue()} / {@code .floatValue()} silently produces zero (matching the
     * upstream {@code GeometryParser} semantics: non-literal values that
     * survive into arithmetic resolve to zero without WARNing - the WARN fires only
     * when the marker is consumed by a builder-dispatch pop site that gates on it via
     * the {@code *OrZero} variants).
     */
    private static final @NotNull Number NON_LITERAL = new Number() {
        @Override public int intValue() { return 0; }
        @Override public long longValue() { return 0L; }
        @Override public float floatValue() { return 0f; }
        @Override public double doubleValue() { return 0d; }
        @Override public @NotNull String toString() { return "<non-literal>"; }
    };

    private final int capacity;
    private final @NotNull Deque<Object> entries = new ArrayDeque<>();

    /**
     * Constructs a new {@code LiteralStack} with the given retention capacity.
     *
     * @param capacity the maximum number of retained entries; overflow evicts the oldest
     */
    public LiteralStack(int capacity) {
        this.capacity = capacity;
    }

    /**
     * Pushes a value onto the top of the stack, evicting the oldest entry when capacity
     * is exceeded.
     *
     * @param value the value to push
     */
    public void push(@NotNull Object value) {
        this.entries.addLast(value);
        if (this.entries.size() > this.capacity)
            this.entries.removeFirst();
    }

    /**
     * Removes and returns the top of the stack, or {@code null} when the stack is empty.
     *
     * @return the popped value, or {@code null} when the stack is empty
     */
    public @Nullable Object pop() {
        if (this.entries.isEmpty()) return null;
        return this.entries.removeLast();
    }

    /**
     * Returns the top of the stack without removing it, or {@code null} when the stack
     * is empty.
     *
     * @return the top value, or {@code null} when the stack is empty
     */
    public @Nullable Object peek() {
        if (this.entries.isEmpty()) return null;
        return this.entries.getLast();
    }

    /**
     * Removes and returns the top of the stack as an {@link Integer}, returning
     * {@code null} when the stack is empty or when the top entry is not an int.
     *
     * @return the popped int, or {@code null} on empty stack or wrong-type top
     */
    public @Nullable Integer popInt() {
        if (this.entries.isEmpty()) return null;
        Object top = this.entries.getLast();
        if (!(top instanceof Integer value)) return null;
        this.entries.removeLast();
        return value;
    }

    /**
     * Removes and returns the top of the stack as a {@link Float}, returning {@code null}
     * when the stack is empty or when the top entry is not a float.
     *
     * @return the popped float, or {@code null} on empty stack or wrong-type top
     */
    public @Nullable Float popFloat() {
        if (this.entries.isEmpty()) return null;
        Object top = this.entries.getLast();
        if (!(top instanceof Float value)) return null;
        this.entries.removeLast();
        return value;
    }

    /**
     * Removes and returns the top of the stack as a {@link String}, returning
     * {@code null} when the stack is empty or when the top entry is not a string.
     *
     * @return the popped string, or {@code null} on empty stack or wrong-type top
     */
    public @Nullable String popString() {
        if (this.entries.isEmpty()) return null;
        Object top = this.entries.getLast();
        if (!(top instanceof String value)) return null;
        this.entries.removeLast();
        return value;
    }

    /**
     * Pushes a sentinel marker indicating "the JVM operand stack contained a value here,
     * but its source was a non-literal (a local variable populated by computation, a
     * method-call result, a {@code GETFIELD} of an opaque field, ...) and the parser
     * cannot resolve it to a constant". Consumed by the {@code *OrZero} pop variants
     * to produce a contextualised WARN instead of silently zero-filling, which would
     * mask the parser's accounting gap as a coordinate of {@code 0}.
     *
     * <p>The marker is type-agnostic - one {@code pushNonLiteral} matches both
     * {@link #popIntOrZero(Diagnostics, String, String) popIntOrZero} and
     * {@link #popFloatOrZero(Diagnostics, String, String) popFloatOrZero}.
     */
    public void pushNonLiteral() {
        this.entries.addLast(NON_LITERAL);
        if (this.entries.size() > this.capacity)
            this.entries.removeFirst();
    }

    /**
     * Silent counterpart of {@link #popIntOrZero(Diagnostics, String, String)}: pops the top
     * as an {@code int} via {@link #popInt()} and returns {@code 0} when the stack is empty or
     * the top is not an {@link Integer} (the wrong-type top is left in place, matching
     * {@code popInt}). No diagnostic is emitted - for callers that treat an absent or wrong-type
     * operand as a benign zero (the {@code BlockTintSources.constant} literal grab).
     *
     * @return the popped int, or {@code 0} on an empty stack or non-int top
     */
    public int popIntOrZero() {
        Integer value = popInt();
        return value != null ? value : 0;
    }

    /**
     * Pops an int from the stack, returning {@code 0} on three cases with different
     * diagnostic behaviour:
     * <ul>
     *   <li><b>empty stack</b> - silent zero. Mirrors the upstream parser convention that
     *       an underflow at the pop site is typically a benign accounting boundary
     *       (descriptor with fewer args than the stack carried) rather than a bug.</li>
     *   <li><b>{@code pushNonLiteral()} sentinel on top</b> - returns zero AND emits a
     *       WARN formatted as
     *       <pre>{@code "<contextPrefix> at <popSite>: non-literal argument consumed - a local variable populated from a computation, resolved to 0"}</pre>.
     *       The {@code contextPrefix} is the caller's tagging string (typically an entity
     *       id) prepended so a multi-source parse run can attribute the warning to a
     *       specific source.</li>
     *   <li><b>wrong-type on top</b> - same WARN shape as the marker case but with
     *       {@code "type mismatch (found <SimpleName>)"} appended. Still returns zero and
     *       still consumes the top so the parse can continue.</li>
     *   <li><b>matching int on top</b> - pops and returns the value, no WARN.</li>
     * </ul>
     *
     * @param diagnostics the diagnostic sink (must be non-null; use {@link #popInt()} for the no-diag variant)
     * @param contextPrefix a short label prepended to WARN messages (typically the source's entity id)
     * @param popSite a short label identifying the caller's pop site (e.g. {@code "addBox(name,FFFIIIII) v"})
     * @return the popped int, or {@code 0} on empty / marker / wrong-type
     */
    public int popIntOrZero(@NotNull Diagnostics diagnostics, @NotNull String contextPrefix, @NotNull String popSite) {
        if (this.entries.isEmpty()) return 0;
        Object top = this.entries.removeLast();
        if (top == NON_LITERAL) {
            diagnostics.warn(
                "%s at %s: non-literal argument consumed - a local variable populated from a computation, resolved to 0",
                contextPrefix, popSite
            );
            return 0;
        }
        if (top instanceof Integer value) return value;
        if (top instanceof Number n) return n.intValue();
        diagnostics.warn(
            "%s at %s: type mismatch popping int (found %s), resolved to 0",
            contextPrefix, popSite, top.getClass().getSimpleName()
        );
        return 0;
    }

    /**
     * Float-typed counterpart of {@link #popIntOrZero(Diagnostics, String, String)}.
     *
     * @param diagnostics the diagnostic sink
     * @param contextPrefix a short label prepended to WARN messages
     * @param popSite a short label identifying the caller's pop site
     * @return the popped float, or {@code 0f} on empty / marker / wrong-type
     */
    public float popFloatOrZero(@NotNull Diagnostics diagnostics, @NotNull String contextPrefix, @NotNull String popSite) {
        if (this.entries.isEmpty()) return 0f;
        Object top = this.entries.removeLast();
        if (top == NON_LITERAL) {
            diagnostics.warn(
                "%s at %s: non-literal argument consumed - a local variable populated from a computation, resolved to 0",
                contextPrefix, popSite
            );
            return 0f;
        }
        if (top instanceof Float value) return value;
        if (top instanceof Number n) return n.floatValue();
        diagnostics.warn(
            "%s at %s: type mismatch popping float (found %s), resolved to 0",
            contextPrefix, popSite, top.getClass().getSimpleName()
        );
        return 0f;
    }

    /**
     * Removes and returns the top of the stack as a {@link Number}, or {@code null} when
     * the stack is empty or the top is not a {@code Number}. Used by parsers that walk
     * arithmetic expressions ({@code FADD}, {@code I2F}, {@code Math.cos}, ...) over a
     * mixed Integer / Float / Double / Long stack where the result type is wider than any
     * single typed pop method. The {@link #pushNonLiteral non-literal marker} returns as a
     * {@code Number} whose {@code intValue() / floatValue() / doubleValue()} all yield
     * zero - matching the silent-zero arithmetic policy upstream parsers rely on.
     *
     * @return the popped number, or {@code null} when the stack is empty or top is non-numeric
     */
    public @Nullable Number popNumber() {
        if (this.entries.isEmpty()) return null;
        Object top = this.entries.getLast();
        if (!(top instanceof Number n)) return null;
        this.entries.removeLast();
        return n;
    }

    /**
     * Like {@link #popNumber} but returns {@code null} when the top entry is the
     * {@link #pushNonLiteral non-literal marker}, distinguishing "real compile-time literal
     * on top" from "computed-or-unknown placeholder on top". Used by parsers whose
     * branch-following logic must NOT take a branch when the comparison value isn't a real
     * literal (the marker's {@code .intValue() == 0} would otherwise spuriously satisfy
     * {@code IFLE} / {@code IFEQ} / {@code IF_ICMPLE} predicates).
     *
     * <p>Always consumes the top entry (matching JVM stack semantics - the operand IS
     * popped even when the parser can't evaluate the predicate). Returns the literal value
     * when it's a real number, or {@code null} when the stack is empty / the top is non-
     * numeric / the top is the non-literal marker. A {@code null} return means "the caller
     * should fall through linearly rather than follow the branch" while still keeping the
     * JVM stack aligned for the post-comparison code.
     *
     * @return the popped number when it's a real literal, or {@code null} when the stack
     *     is empty, the top is non-numeric, or the top is the non-literal marker
     */
    public @Nullable Number popLiteralNumber() {
        if (this.entries.isEmpty()) return null;
        Object top = this.entries.removeLast();
        if (top == NON_LITERAL) return null;
        if (!(top instanceof Number n)) return null;
        return n;
    }

    /**
     * Removes and returns the bottom-most (oldest) entry, or {@code null} when the stack
     * is empty. Mirrors the removeFirst semantics the legacy parsers used; needed
     * alongside {@link #pop} for callers that maintain a FIFO discard policy beyond the
     * built-in capacity-overflow eviction.
     *
     * @return the oldest value, or {@code null} when the stack is empty
     */
    public @Nullable Object removeFirst() {
        if (this.entries.isEmpty()) return null;
        return this.entries.removeFirst();
    }

    /**
     * Clears every entry from the stack.
     */
    public void reset() {
        this.entries.clear();
    }

    /**
     * The current number of entries on the stack.
     *
     * @return the entry count
     */
    public int size() {
        return this.entries.size();
    }

    /**
     * {@code true} when {@link #size()} is zero.
     *
     * @return whether the stack is empty
     */
    public boolean isEmpty() {
        return this.entries.isEmpty();
    }

}
