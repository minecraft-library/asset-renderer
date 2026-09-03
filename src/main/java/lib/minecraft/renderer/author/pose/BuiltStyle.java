package lib.minecraft.renderer.author.pose;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.appearance.Age;
import lib.minecraft.renderer.asset.pose.MotionSource;
import lib.minecraft.renderer.asset.pose.PoseStyle;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * One authored style as a portable value - entity-free and immutable, so a single built style
 * installs on any number of rows and each install lowers it against that row's own mesh and
 * rest values.
 *
 * <p>The script is still in author units - degrees, model pixels, seconds - and the source
 * inventory is inferred from its content rather than declared: a script with any timeline, sway,
 * spin, stride ride or nonzero hover bob moves on the clock, and anything else holds still as a
 * one-frame statue.
 *
 * @param styleId the id a caller selects the style by
 * @param script the captured intermediate form, in author units
 * @param sources the mechanism inventory the compiled row carries - empty for a still style,
 *     one clock-driven entry for a moving one
 * @param toggles the appearance bone toggles the style entails
 * @param age the age the style applies to; empty applies to both
 */
public record BuiltStyle(
    @NotNull String styleId,
    @NotNull PoseScript script,
    @NotNull ConcurrentList<PoseStyle.StyleSource> sources,
    @NotNull ConcurrentList<String> toggles,
    @NotNull Optional<Age> age
) {

    /**
     * The ids the universal rows answer - a custom style under one would shadow a row every
     * entity already resolves, and a {@code bind} selection returns the untouched subject by
     * identity before any row is read.
     */
    private static final @NotNull Set<String> RESERVED =
        Set.of(PoseStyle.BIND, PoseStyle.IDLE, PoseStyle.STRIDE, PoseStyle.ANIMATED);

    /**
     * Builds the portable value from a builder's captured state - refuses a reserved id, infers
     * the source inventory from the script's content and snapshots the toggle list.
     *
     * @param styleId the id a caller selects the style by
     * @param script the captured intermediate form
     * @param toggles the appearance bone toggles the style entails
     * @param age the age the style applies to; empty applies to both
     * @return the built style
     * @throws IllegalArgumentException if the id is one the universal rows answer
     */
    static @NotNull BuiltStyle built(@NotNull String styleId, @NotNull PoseScript script,
                                     @NotNull List<String> toggles, @NotNull Optional<Age> age) {
        if (RESERVED.contains(styleId))
            throw new IllegalArgumentException(String.format(
                "Style id '%s' is reserved - 'bind', 'idle', 'stride' and 'animated' name the universal rows every entity answers",
                styleId
            ));

        return new BuiltStyle(styleId, script, sourcesOf(script), Concurrent.newUnmodifiableList(toggles), age);
    }

    /**
     * Audits this style against one target row's known-good motion - compiles it, evaluates
     * the woven pose across its period, and reports every bind-adjacent bone pair whose
     * clearance leaves the envelope the shipped styles define.
     *
     * @param row the shipped row the style would install on
     * @return the audit
     * @throws IllegalArgumentException if the style refuses to compile against the row
     */
    public @NotNull PoseAudit validate(@NotNull Entity row) {
        return PoseValidator.audit(this, row);
    }

    /**
     * Infers the source inventory from a script's content - any timeline, sway, spin, stride
     * ride or nonzero hover bob moves the style on the clock, and anything else holds still.
     *
     * @param script the captured script to read
     * @return the inferred inventory
     */
    private static @NotNull ConcurrentList<PoseStyle.StyleSource> sourcesOf(@NotNull PoseScript script) {
        boolean moves = script.keepStride()
            || script.hover().map(hover -> hover.bobPixels() != 0).orElse(false)
            || script.stances().stream().anyMatch(stance ->
                !stance.tracks().isEmpty() || !stance.sways().isEmpty() || !stance.spins().isEmpty());

        return moves
            ? Concurrent.newUnmodifiableList(new PoseStyle.StyleSource(MotionSource.TICK, Optional.empty()))
            : Concurrent.newUnmodifiableList();
    }

}
