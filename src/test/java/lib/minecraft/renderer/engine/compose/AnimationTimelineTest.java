package lib.minecraft.renderer.engine.compose;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import lib.minecraft.renderer.asset.AnimationData;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Coverage for {@link AnimationTimeline}'s AUTO derivation (04-animation §3 / §6, decision D2) against
 * the real vanilla 26.1 {@code .mcmeta} shapes: the reordered {@code fire_0} frames list, the
 * {@code water_still} bare frametime, the interpolating {@code magma} / {@code prismarine} loops (the
 * latter over the 200-tick cap), and multi-texture LCM/GCD reconciliation. Verifies both the uniform
 * timeline (frameCount + ticksPerFrame) and the change-point schedule.
 */
class AnimationTimelineTest {

    /** fire_0.png.mcmeta: 32 bare frame indices [16..31, 0..15], default frametime (1 tick each). */
    private static @NotNull AnimationData fire() {
        ConcurrentList<AnimationData.FrameEntry> frames = Concurrent.newList();
        for (int i = 16; i <= 31; i++) frames.add(new AnimationData.FrameEntry(i, -1));
        for (int i = 0; i <= 15; i++) frames.add(new AnimationData.FrameEntry(i, -1));
        return new AnimationData(1, false, frames, -1, -1);
    }

    /** water_still.png.mcmeta: bare {@code frametime: 2}, no frames list (implicit strip order). */
    private static @NotNull AnimationData waterStill() {
        return new AnimationData(2, false, Concurrent.newList(), -1, -1);
    }

    /** magma.png.mcmeta: {@code frametime: 8}, interpolate, frames [0, 1, 2]. */
    private static @NotNull AnimationData magma() {
        ConcurrentList<AnimationData.FrameEntry> frames = Concurrent.newList();
        frames.add(new AnimationData.FrameEntry(0, -1));
        frames.add(new AnimationData.FrameEntry(1, -1));
        frames.add(new AnimationData.FrameEntry(2, -1));
        return new AnimationData(8, true, frames, -1, -1);
    }

    /** prismarine.png.mcmeta: {@code frametime: 300}, interpolate, 22 frames -> loop far over the cap. */
    private static @NotNull AnimationData prismarine() {
        ConcurrentList<AnimationData.FrameEntry> frames = Concurrent.newList();
        int[] seq = {0, 1, 0, 2, 0, 3, 0, 1, 2, 1, 3, 1, 0, 2, 1, 2, 3, 2, 0, 3, 1, 3};
        for (int index : seq) frames.add(new AnimationData.FrameEntry(index, -1));
        return new AnimationData(300, true, frames, -1, -1);
    }

    /** A frametime-only source with the given tick length and implicit frame count. */
    private static @NotNull AnimationData implicit(int frametime) {
        return new AnimationData(frametime, false, Concurrent.newList(), -1, -1);
    }

    @Test
    @DisplayName("no sources -> STATIC single frame")
    void emptyIsStatic() {
        assertThat(AnimationTimeline.deriveUniform(List.of()), is(AnimationTimeline.Uniform.STATIC));
    }

    @Test
    @DisplayName("fire: 32 bare frames -> 32 frames at cadence 1 (loop 32)")
    void fireUniform() {
        AnimationTimeline.Uniform u = AnimationTimeline.deriveUniform(
            List.of(new AnimationTimeline.Source(32, fire())));
        assertThat(u.frameCount(), is(32));
        assertThat(u.ticksPerFrame(), is(1));
    }

    @Test
    @DisplayName("water_still: frametime 2, 32 implicit frames -> 32 frames at cadence 2 (loop 64)")
    void waterUniform() {
        AnimationTimeline.Uniform u = AnimationTimeline.deriveUniform(
            List.of(new AnimationTimeline.Source(32, waterStill())));
        assertThat(u.frameCount(), is(32));
        assertThat(u.ticksPerFrame(), is(2));
    }

    @Test
    @DisplayName("magma: interpolate clamps cadence to 1 (loop 24 -> 24 frames)")
    void magmaInterpolateClampsCadence() {
        AnimationTimeline.Uniform u = AnimationTimeline.deriveUniform(
            List.of(new AnimationTimeline.Source(3, magma())));
        assertThat(u.ticksPerFrame(), is(1));
        assertThat(u.frameCount(), is(24));
    }

    @Test
    @DisplayName("prismarine: 6600-tick loop capped at 200 ticks (cadence 1 -> 200 frames)")
    void prismarineCapsLoop() {
        AnimationTimeline.Uniform u = AnimationTimeline.deriveUniform(
            List.of(new AnimationTimeline.Source(4, prismarine())));
        assertThat(u.ticksPerFrame(), is(1));
        assertThat(u.frameCount(), is(AnimationTimeline.MAX_LOOP_TICKS));
    }

    @Test
    @DisplayName("multi-texture: LCM loop over GCD cadence (loops 8 & 12 -> lcm 24, gcd 2 -> 12 frames)")
    void multiTextureLcmGcd() {
        AnimationTimeline.Uniform u = AnimationTimeline.deriveUniform(List.of(
            new AnimationTimeline.Source(4, implicit(2)),   // 4 frames x 2 ticks = 8-tick loop
            new AnimationTimeline.Source(3, implicit(4))    // 3 frames x 4 ticks = 12-tick loop
        ));
        assertThat(u.ticksPerFrame(), is(2));               // gcd(2, 4)
        assertThat(u.frameCount(), is(12));                 // lcm(8, 12) / 2 = 24 / 2
    }

    @Test
    @DisplayName("fire schedule: change points 0..31, each frame held 50 ms (mcmeta round-trip)")
    void fireSchedule() {
        AnimationTimeline.Schedule schedule = AnimationTimeline.deriveSchedule(
            List.of(new AnimationTimeline.Source(32, fire())));
        assertThat(schedule.sampleTicks().length, is(32));
        for (int f = 0; f < 32; f++) {
            assertThat(schedule.sampleTicks()[f], is((long) f));
            assertThat(schedule.frameDelaysMs()[f], is(50));
        }
    }

    @Test
    @DisplayName("water schedule: change points every 2 ticks (0,2,..,62), each held 100 ms")
    void waterSchedule() {
        AnimationTimeline.Schedule schedule = AnimationTimeline.deriveSchedule(
            List.of(new AnimationTimeline.Source(32, waterStill())));
        assertThat(schedule.sampleTicks().length, is(32));
        assertThat(schedule.sampleTicks()[0], is(0L));
        assertThat(schedule.sampleTicks()[1], is(2L));
        assertThat(schedule.frameDelaysMs()[0], is(100));   // 2 ticks * 50 ms
    }
}
