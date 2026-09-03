package lib.minecraft.renderer.author.pose;

import dev.simplified.collection.ConcurrentList;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.Optional;

/**
 * The outcome of validating a built style against a target row's known-good motion - the
 * bind-adjacent bone pairs whose posed clearance left the envelope the shipped styles define,
 * each with the numbers that place the fault and a reading of which side the author moved.
 *
 * @param styleId the audited style's id
 * @param rowId the target row's entity id
 * @param pairsChecked how many bind-adjacent pairs the audit measured
 * @param droppedBones the written bones the target mesh does not declare
 * @param findings the pairs that left the shipped envelope, in mesh order
 */
public record PoseAudit(
    @NotNull String styleId,
    @NotNull String rowId,
    int pairsChecked,
    @NotNull ConcurrentList<String> droppedBones,
    @NotNull ConcurrentList<Finding> findings
) {

    /**
     * How a pair left the envelope.
     */
    public enum Kind {

        /**
         * The pair separates further than any shipped style carries it.
         */
        SPLIT,

        /**
         * The pair interpenetrates deeper than any shipped style carries it.
         */
        OVERLAP

    }

    /**
     * One pair that left the shipped envelope.
     *
     * @param kind how the pair left the envelope
     * @param boneA the first bone, in mesh order
     * @param boneB the second bone, in mesh order
     * @param bindClearance the pair's clearance at bind, in model pixels
     * @param knownMin the tightest clearance any shipped style reaches
     * @param knownMax the widest clearance any shipped style reaches
     * @param posedMin the tightest clearance the audited style reaches
     * @param posedMax the widest clearance the audited style reaches
     * @param worstTick the sampled tick where the excursion peaks
     * @param aStanced whether the audited style moves the first bone
     * @param bStanced whether the audited style moves the second bone
     * @param sharedParent the parent both bones hang under, when they hang under one
     */
    public record Finding(
        @NotNull Kind kind,
        @NotNull String boneA,
        @NotNull String boneB,
        float bindClearance,
        float knownMin,
        float knownMax,
        float posedMin,
        float posedMax,
        int worstTick,
        boolean aStanced,
        boolean bStanced,
        @NotNull Optional<String> sharedParent
    ) {

        /**
         * Renders the finding as its two report lines - the measured excursion, then the
         * reading of which side the author moved and what usually closes the fault.
         *
         * @return the two-line description
         */
        public @NotNull String describe() {
            String measure = String.format(Locale.ROOT,
                "[%s] %s <-> %s: bind %.1fpx, shipped [%.1f .. %.1f]px, posed [%.1f .. %.1f]px, worst at tick %d",
                this.kind, this.boneA, this.boneB, this.bindClearance,
                this.knownMin, this.knownMax, this.posedMin, this.posedMax, this.worstTick);

            return measure + "\n        " + this.reading();
        }

        /**
         * The reading of which side the author moved and what usually closes the fault.
         */
        private @NotNull String reading() {
            if (this.aStanced != this.bStanced) {
                String moved = this.aStanced ? this.boneA : this.boneB;
                String still = this.aStanced ? this.boneB : this.boneA;

                if (this.sharedParent.isPresent())
                    return String.format(Locale.ROOT,
                        "'%s' moved and its sibling '%s' did not - both hang under '%s'; stance the parent so the assembly moves whole",
                        moved, still, this.sharedParent.get());

                return String.format(Locale.ROOT,
                    "'%s' moved and '%s' did not - vanilla couples the pair outside the pose table; stance '%s' too (an offset for a seat, a rotation for a joint), or accept the change",
                    moved, still, still);
            }

            if (this.aStanced)
                return "both bones are stanced and the pair still leaves the shipped envelope - check the two stances agree on the seam";

            return "neither bone is stanced - a container step or clip moved the pair";
        }

    }

    /**
     * Whether the audit measured every pair inside the shipped envelope.
     *
     * @return true when no pair left the envelope
     */
    public boolean clean() {
        return this.findings.isEmpty();
    }

    /**
     * Renders the whole audit as a report - one header line, the dropped-bone inventory when
     * one exists, then each finding's two lines.
     *
     * @return the rendered report
     */
    public @NotNull String report() {
        StringBuilder out = new StringBuilder(String.format(Locale.ROOT,
            "pose audit: '%s' on '%s' - %s",
            this.styleId, this.rowId,
            this.clean()
                ? String.format(Locale.ROOT, "clean (%d adjacent pairs within the shipped envelope)", this.pairsChecked)
                : String.format(Locale.ROOT, "%d finding%s over %d adjacent pairs",
                    this.findings.size(), this.findings.size() == 1 ? "" : "s", this.pairsChecked)));

        if (!this.droppedBones.isEmpty())
            out.append("\n    written bones the mesh does not declare: ").append(String.join(", ", this.droppedBones));
        for (Finding finding : this.findings)
            out.append("\n    ").append(finding.describe());

        return out.toString();
    }

}
