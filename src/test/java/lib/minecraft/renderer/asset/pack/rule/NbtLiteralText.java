package lib.minecraft.renderer.asset.pack.rule;

import dev.simplified.annotations.UtilityClass;
import lib.minecraft.nbt.tag.Tag;
import lib.minecraft.renderer.pipeline.dump.PipelineParityDump;
import org.jetbrains.annotations.NotNull;

/**
 * Package-private reach-through to the SNBT form of an NBT rule literal, for the parity dump.
 * <p>
 * {@code NbtValues} is a package-private utility, so its {@code snbt} is unreachable from the dump's
 * package. The dump needs it because {@code NbtPredicate.Exact} holds a {@code Tag<?>} whose
 * {@code toString} is an identity-flavoured form with no stability contract - the exact thing a
 * byte-comparison oracle must not rest on. Re-implementing the SNBT rendering in the dump would be
 * worse: it would be a second copy of the production rule, free to drift from it silently.
 *
 * @see PipelineParityDump
 */
@UtilityClass
public final class NbtLiteralText {

    /**
     * Returns a literal tag's SNBT form.
     *
     * @param tag the literal to render
     * @return its SNBT text
     */
    public static @NotNull String snbt(@NotNull Tag<?> tag) {
        return NbtValues.snbt(tag);
    }

}
