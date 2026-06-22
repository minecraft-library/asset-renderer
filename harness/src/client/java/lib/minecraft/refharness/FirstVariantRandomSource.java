package lib.minecraft.refharness;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;

/**
 * A {@link RandomSource} whose integer rolls are pinned to {@code 0}, forcing vanilla's weighted
 * block-model variant lists ({@code WeightedVariants}) to emit their FIRST entry deterministically.
 *
 * <p>Vanilla's {@code WeightedList.getRandomOrThrow} selects via {@code selector.get(nextInt(
 * totalWeight))}; returning {@code 0} from {@link #nextInt(int)} therefore always picks index 0 -
 * the first variant in blockstate declaration order. That matches asset-renderer's
 * {@code BlockStateLoader.parseVariants}, which always takes {@code variants[0]}.
 *
 * <p>Without this, {@link BlockFrameRenderer} drove {@code collectParts} with a live
 * {@link RandomSource#create()} (random seed each render), so noisy blocks whose blockstate lists
 * several weighted rotation / mirror variants (bedrock 4, stone 4, netherrack 16, and the many
 * rotated-cube tiles) baked a random rotation into their reference PNG. The asset always renders
 * {@code variants[0]}, so the reference's texture noise was rotated / mirrored relative to it -
 * a large per-pixel delta on a perfectly-matching silhouette. Rotation-symmetric tiles (dirt,
 * cobblestone) were unaffected by the randomness; only asymmetric textures diverged.
 *
 * <p>The chosen {@code SingleVariant} consumes no further randomness from this source, so pinning
 * {@code nextInt} is sufficient; the remaining methods delegate to a real {@link RandomSource} so
 * any future caller still gets well-formed values. This is the block-side analogue of the harness's
 * other determinism fixes (noon lightmap pin, shaking-mob suppression).
 */
final class FirstVariantRandomSource implements RandomSource {

    private final RandomSource delegate = RandomSource.create();

    @Override
    public RandomSource fork() {
        return delegate.fork();
    }

    @Override
    public PositionalRandomFactory forkPositional() {
        return delegate.forkPositional();
    }

    @Override
    public void setSeed(long seed) {
        delegate.setSeed(seed);
    }

    @Override
    public int nextInt() {
        return 0;
    }

    @Override
    public int nextInt(int bound) {
        return 0;
    }

    @Override
    public long nextLong() {
        return delegate.nextLong();
    }

    @Override
    public boolean nextBoolean() {
        return delegate.nextBoolean();
    }

    @Override
    public float nextFloat() {
        return delegate.nextFloat();
    }

    @Override
    public double nextDouble() {
        return delegate.nextDouble();
    }

    @Override
    public double nextGaussian() {
        return delegate.nextGaussian();
    }
}
