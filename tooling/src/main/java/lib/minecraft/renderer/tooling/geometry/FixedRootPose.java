package lib.minecraft.renderer.tooling.geometry;

import lib.minecraft.renderer.engine.pose.PoseChannel;
import lib.minecraft.renderer.tooling.asm.ClassKit;
import lib.minecraft.renderer.tooling.exception.ToolingException;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * The root pose a model's {@code setupAnim} writes when it takes no input - one declared over
 * {@code net.minecraft.util.Unit}, so every draw runs the same body and what it writes is part of the
 * mesh rather than an animation of it. The copper golem statue's sets the root's {@code y} to zero and
 * its {@code zRot} to pi on every draw.
 * <p>
 * Each write replaces the member it names on the root the factory and its registration posed, as an
 * assignment at draw time replaces a baked {@code PartPose}; a member it does not write keeps the baked
 * value. Only the offset and the rotation are carried: a write to the root's scale is refused, since a
 * bone's scale here does not propagate down the chain as vanilla's does.
 *
 * @param modelClass the internal name of the model whose {@code setupAnim} writes the pose
 * @param writes the constant each written channel is set to - model pixels for an offset, radians for a rotation
 */
public record FixedRootPose(@NotNull String modelClass, @NotNull Map<PoseChannel, Float> writes) {

    /**
     * Constructs a new {@code FixedRootPose}, keeping the writes unmodifiable in channel order.
     *
     * @throws ToolingException when a write sets one of the root's scales
     */
    public FixedRootPose {
        EnumMap<PoseChannel, Float> held = new EnumMap<>(PoseChannel.class);
        held.putAll(writes);
        held.keySet().stream()
            .filter(channel -> channel.kind() == PoseChannel.Kind.SCALE)
            .findFirst()
            .ifPresent(channel -> {
                throw new ToolingException("'%s.setupAnim' writes the root's '%s', which a fixed pose does not carry",
                    ClassKit.simpleName(modelClass), channel.token());
            });
        writes = Collections.unmodifiableMap(held);
    }

    /**
     * Answers the root's offset once the pose is written over the baked one: each written axis takes
     * the written value, the others keep the baked.
     *
     * @param baked the offset the factory and its registration posed the root at, in model pixels
     * @return the offset the root is drawn at
     */
    float @NotNull [] offsetOver(float @NotNull [] baked) {
        return new float[] {
            this.writes.getOrDefault(PoseChannel.X, baked[0]),
            this.writes.getOrDefault(PoseChannel.Y, baked[1]),
            this.writes.getOrDefault(PoseChannel.Z, baked[2])
        };
    }

    /**
     * Answers the root's rotation once the pose is written: each written axis takes the written value,
     * and an unwritten one stays at the zero every factory root is built at.
     *
     * @return the rotation about x, y and z, in radians
     */
    float @NotNull [] rotation() {
        return new float[] {
            this.writes.getOrDefault(PoseChannel.X_ROT, 0f),
            this.writes.getOrDefault(PoseChannel.Y_ROT, 0f),
            this.writes.getOrDefault(PoseChannel.Z_ROT, 0f)
        };
    }

    /**
     * Answers how a geometry key and its {@code source} twin name the pose: the simple name of the model
     * writing it, as {@code @baby} names its transformer rather than spelling its values.
     *
     * @return the discriminator
     */
    @NotNull String discriminator() {
        return ClassKit.simpleName(this.modelClass);
    }

}
