package lib.minecraft.renderer.tooling.entity;

import dev.simplified.annotations.AccessLevel;
import dev.simplified.annotations.RequiredArgsConstructor;
import lib.minecraft.renderer.tooling.kernel.VanillaSourceClasses;
import lib.minecraft.renderer.tooling.policy.AsmContext;
import lib.minecraft.renderer.tooling.policy.Navigation;
import lib.minecraft.renderer.tooling.policy.NavigationPolicy;
import org.jetbrains.annotations.NotNull;

/**
 * What a subject's render state rests holding where the walk that reads {@code extractRenderState}
 * cannot decide it. Never fetches ({@code PolicyPurityTest}).
 *
 * <p>The walk answers a field filled from a plain accessor on the entity, whose own last return
 * says what it falls through to. Two questions sit outside that: one whose answer is a property of
 * the reference render rather than of vanilla, and one whose answer is real but sits behind a
 * receiver reflection installs.
 *
 * <p>A row whose fact sits at a walkable member declares that {@link Navigation} coordinate in
 * place of a value; the consuming resolver re-enters the engine there and reads it, so the declared
 * half is only which member answers and the answer itself still comes from the jar.
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
enum EntityRestPolicies implements NavigationPolicy {

    /**
     * The entity base an offline render puts in water. A fish renderer reads {@code isInWater}
     * twice - once to scale the amplitude its body wags at, and once to decide whether to lay the
     * subject on its side. The second is vanilla's flopping-on-land pose, right in a world and the
     * wrong shape for a reference render, so the harness pins the field on and this side has to
     * answer the same or the two draw different fish.
     *
     * <p>Scoped to this base exactly, because the harness's pin is: a dolphin, an axolotl, a squid
     * and a drowned each read the same field for something else, so widening it to everything
     * aquatic would answer for four subjects that never asked the same question.
     */
    IN_WATER_FAMILY(
        VanillaSourceClasses.Types.ABSTRACT_FISH,
        "the harness pins state.isInWater on this base so a reference render draws a fish swimming rather"
            + " than lying on its side; the scope is the pin's, not a judgment about what is aquatic"),

    /**
     * The coordinate answering what a dragon that has never ticked is doing. Its renderer fills
     * {@code isSitting} from the phase instance its manager is holding, and that receiver is
     * unreachable by a walk: the manager's constructor installs {@code EnderDragonPhase.HOVERING},
     * whose instance class is a {@code Class} token the phase carries and builds through
     * {@code Constructor.newInstance}. So which class answers is declared and what it answers is
     * read, off the same last-unguarded-return the enum arm reads a constant off.
     *
     * <p>A dragon that sits bends every neck segment further than the one above it and pitches its
     * head a quarter turn down, so the flag is the whole of that subject's silhouette.
     */
    RESTING_PHASE_ANSWER(
        new Navigation.At(
            "net/minecraft/world/entity/boss/enderdragon/phases/DragonHoverPhase", "isSitting", "()Z"),
        "EnderDragonPhaseManager's constructor sets HOVERING, and a spawned dragon is never ticked out of"
            + " it; the instance class travels as a Class token through reflection, which no walk follows");

    private final @NotNull Object value;
    private final @NotNull String provenance;

    @Override
    public @NotNull Navigation navigate(@NotNull AsmContext context) {
        if (this.value instanceof Navigation coordinate) return coordinate;
        return new Navigation.Value<>(this.value, this.provenance);
    }

    /**
     * The declared string fact of a string-valued row ({@link #IN_WATER_FAMILY}).
     *
     * @return the declared value
     */
    @NotNull String stringValue() {
        return (String) this.value;
    }

}
