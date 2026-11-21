package lib.minecraft.renderer.asset.pack;

import lib.minecraft.renderer.exception.ContentException;
import org.jetbrains.annotations.NotNull;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The work a pack's own regular expressions may spend matching, counted in steps and shared by every
 * match one caller makes through it.
 * <p>
 * A pack supplies patterns in three places - its {@code filter.block} section, an atlas's
 * {@code filter} source and a CIT rule's {@code regex:} and {@code pattern:} values - and the client
 * takes any {@link java.util.regex} pattern in each, so none can be held to a subset that is cheap to
 * match. What is held instead is the matching: every such pattern is matched through a budget, a step
 * being one character the matcher reads from the subject, and a match is charged {@value #CALL_STEPS}
 * steps before it reads any, so a pattern that reads nothing still costs. A match the budget cannot
 * pay for, one whose subject runs past {@value #MAX_SUBJECT_LENGTH} characters, and one the matcher
 * runs out of stack on are each refused with a {@link ContentException}, and every later match
 * through a spent budget is refused the same way.
 * <p>
 * Steps are counted rather than timed so that the same pattern over the same subjects is refused or
 * answered the same way on every run, whatever else the machine is doing.
 * <p>
 * The scope of a budget is the scope of the bound, so a caller walking many subjects makes one
 * {@linkplain #across(Stream) across} them all rather than one per match: a pattern that stays inside
 * a single match's allowance on every subject is then still held to the pass's.
 * <p>
 * A budget is spent by one thread.
 */
public final class PatternBudget {

    /**
     * The steps a budget made for {@linkplain #single() one match} holds, and the fewest any budget
     * does. A pattern that reads every character three times over spends under a fifth of it on the
     * longest subject taken, and spending all of it takes some tens of milliseconds.
     */
    public static final long SINGLE_STEPS = 1L << 20;

    /**
     * The steps a budget made {@linkplain #across(Stream) across} many subjects holds for each,
     * whatever its length - what pays for a pattern anchored on a directory, which spends about 50
     * steps on a file of the 26.1 client, and for the charge a long list of such patterns runs up.
     */
    public static final long SUBJECT_STEPS = 1L << 10;

    /**
     * The further steps such a budget holds for each subject, for every character of it squared.
     * A pattern written without care - one that opens on {@code .*} and matches nothing, so is
     * searched for from every position - spends about one and a half steps for every character of
     * the subject squared: measured, about 2,000 on the average file of the 26.1 client and 15,000
     * on a path of a hundred characters. The allowance is ten times that at any length. A load of
     * the 26.1 client under one filtering pack tests about 10,500 files, for some 400 million steps
     * of budget in all, and spending every step of it takes about as long again as the load itself.
     */
    public static final long LENGTH_STEPS = 16;

    /**
     * The longest subject whose length adds to such a budget, a little past the client's longest
     * path. A longer subject is paid for as one of this length, so what a pass may spend grows with
     * how many subjects it holds and not with how long a pack spells them.
     */
    public static final int PAID_LENGTH = 128;

    /** The steps one match is charged before the matcher reads a character. */
    public static final int CALL_STEPS = 16;

    /**
     * The longest subject a pattern is matched against, the longest text one NBT string holds - its
     * length is written in two bytes - and far past any resource path.
     */
    public static final int MAX_SUBJECT_LENGTH = 65_535;

    /** The longest a pattern is quoted in a refusal before it is cut short. */
    private static final int QUOTED_LENGTH = 80;

    /** The steps this budget started with. */
    private final long allowance;

    /** The steps left, below zero once the budget is spent. */
    private long remaining;

    private PatternBudget(long allowance) {
        this.allowance = allowance;
        this.remaining = allowance;
    }

    /**
     * A budget for one match and nothing wider - a pattern tested against a single subject, with no
     * pass around it to share one.
     *
     * @return a budget of {@link #SINGLE_STEPS} steps
     */
    public static @NotNull PatternBudget single() {
        return new PatternBudget(SINGLE_STEPS);
    }

    /**
     * A budget for one pass of a pack's patterns over many subjects, sized by how many there are and
     * how long each is.
     *
     * @param subjects the subjects the pass tests
     * @return a budget of {@link #SUBJECT_STEPS} steps for each subject and {@link #LENGTH_STEPS} more
     *     for every character of it squared, up to {@link #PAID_LENGTH} characters, and no fewer than
     *     {@link #SINGLE_STEPS}
     */
    public static @NotNull PatternBudget across(@NotNull Stream<String> subjects) {
        return new PatternBudget(Math.max(SINGLE_STEPS, subjects.mapToLong(PatternBudget::stepsFor).sum()));
    }

    private static long stepsFor(@NotNull String subject) {
        long length = Math.min(subject.length(), PAID_LENGTH);
        return SUBJECT_STEPS + LENGTH_STEPS * length * length;
    }

    /**
     * Whether a pattern is found anywhere in a subject, as {@link Matcher#find()} answers.
     *
     * @param pattern the pack-supplied pattern
     * @param subject the text to search
     * @return {@code true} when the pattern matches some part of the subject
     * @throws ContentException if the subject is too long, the budget cannot pay for the match, or the
     *     matcher runs out of stack
     */
    public boolean find(@NotNull Pattern pattern, @NotNull String subject) {
        return this.run(pattern, subject, false);
    }

    /**
     * Whether a pattern matches a whole subject, as {@link Matcher#matches()} answers.
     *
     * @param pattern the pack-supplied pattern
     * @param subject the text to match
     * @return {@code true} when the pattern matches the subject from end to end
     * @throws ContentException if the subject is too long, the budget cannot pay for the match, or the
     *     matcher runs out of stack
     */
    public boolean matches(@NotNull Pattern pattern, @NotNull String subject) {
        return this.run(pattern, subject, true);
    }

    private boolean run(@NotNull Pattern pattern, @NotNull String subject, boolean whole) {
        if (subject.length() > MAX_SUBJECT_LENGTH)
            throw new ContentException("Pattern '%s' is not matched against a subject longer than %d characters", quoted(pattern), MAX_SUBJECT_LENGTH);

        try {
            this.spend(CALL_STEPS);
            Matcher matcher = pattern.matcher(new Metered(subject));
            return whole ? matcher.matches() : matcher.find();
        } catch (Spent ex) {
            throw new ContentException("Pattern '%s' did not finish matching within a budget of %d steps", quoted(pattern), this.allowance);
        } catch (StackOverflowError ex) {
            // The matcher recurses once per repeat of some groups, so the depth is the subject's to set.
            throw new ContentException("Pattern '%s' ran out of stack matching its subject", quoted(pattern));
        }
    }

    private void spend(int steps) {
        if (this.remaining < 0 || (this.remaining -= steps) < 0) {
            this.remaining = -1;
            throw Spent.SIGNAL;
        }
    }

    private static @NotNull String quoted(@NotNull Pattern pattern) {
        String text = pattern.pattern();
        return text.length() <= QUOTED_LENGTH ? text : text.substring(0, QUOTED_LENGTH) + "...";
    }

    /** A subject that charges the budget one step for every character read from it. */
    private final class Metered implements CharSequence {

        private final @NotNull String subject;

        private Metered(@NotNull String subject) {
            this.subject = subject;
        }

        @Override
        public int length() {
            return this.subject.length();
        }

        @Override
        public char charAt(int index) {
            PatternBudget.this.spend(1);
            return this.subject.charAt(index);
        }

        @Override
        public @NotNull CharSequence subSequence(int start, int end) {
            return this.subject.subSequence(start, end);
        }

        @Override
        public @NotNull String toString() {
            return this.subject;
        }

    }

    /** The signal a spent budget unwinds the matcher with, raised and caught inside one match. */
    private static final class Spent extends RuntimeException {

        private static final @NotNull Spent SIGNAL = new Spent();

        private Spent() {
            super(null, null, false, false);
        }

    }

}
