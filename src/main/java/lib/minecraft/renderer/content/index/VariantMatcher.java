package lib.minecraft.renderer.content.index;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * Selects the blockstate variant a resolved state names, out of the variants a block's blockstate
 * declares.
 * <p>
 * The match is a subset test scored by specificity: a variant whose properties are all present with
 * the same values in the state is a candidate, and the candidate naming the most properties wins.
 */
@UtilityClass
@Parity(claim = "engine-renders", mode = Mode.DEMOTE)
public class VariantMatcher {

    /**
     * Looks up the blockstate variant a state selects. Answers {@code null} where nothing matches,
     * in which case the caller renders the raw model pose - which for oriented blocks matches what
     * vanilla inventory shows, since vanilla's inventory pipeline never consults the blockstate.
     *
     * @param block the block whose declared variants are searched
     * @param state the resolved blockstate the render draws at
     * @return the selected variant, or {@code null} when none matches
     */
    public static @Nullable Block.Variant resolve(
        @NotNull Block block, @NotNull ConcurrentMap<String, String> state) {
        // A property-less caller maps to the unconditional {@code ""} blockstate variant, whose
        // model is authoritative and need NOT equal {@link Block#model()} (the by-id
        // {@code block/<id>} guess). mud_bricks points {@code ""} at
        // {@code block/mud_bricks_north_west_mirrored} (north/west faces UV-flipped) where
        // {@code getModel()} is the plain {@code block/mud_bricks} cube_all - falling through to
        // {@code getModel()} dropped the mirror. The caller only swaps in the variant's geometry
        // when it carries real elements, so an empty particle-only template (TILE_ENTITY blocks
        // whose mesh comes from the block-entity model) still falls back to the BE model. Retained
        // as a direct string lookup on the string-keyed variants map.
        if (state.isEmpty()) return block.variants().get("");
        // Most-specific subset wins; first-encountered wins on ties. The caller may supply a
        // fully-qualified blockstate (e.g. `facing=north,half=lower,hinge=left,open=false,powered=false`
        // from the harness's defaultBlockState dump) while the JSON variant keys list only the
        // properties that actually affect the model (`facing/half/hinge/open` for doors, omitting
        // `powered`); the entry whose props are a SUBSET of the caller's and match the most
        // properties wins. This lets a geometry-bearing {@code attached=true} variant (injected for
        // the ceiling hanging sign) beat the unconditional {@code ""} catch-all. An exact match is
        // simply the maximal-specificity case of this same loop (vanilla keys are sorted, so no two
        // distinct keys parse to equal maps), so no separate exact fast path is needed. Each
        // variant's properties are PRE-PARSED at load - no per-render parse.
        Block.Variant best = null;
        int bestSpecificity = -1;
        for (Block.Variant variant : block.variants().values()) {
            ConcurrentMap<String, String> variantProps = variant.properties();
            if (isSubsetMatch(variantProps, state) && variantProps.size() > bestSpecificity) {
                best = variant;
                bestSpecificity = variantProps.size();
            }
        }
        return best;
    }

    /**
     * Returns true when every entry in {@code subset} appears with the same value in {@code superset}.
     *
     * @param subset the properties a variant declares
     * @param superset the resolved blockstate the render draws at
     * @return whether every declared property is matched
     */
    public static boolean isSubsetMatch(
        @NotNull ConcurrentMap<String, String> subset, @NotNull ConcurrentMap<String, String> superset) {
        for (Map.Entry<String, String> e : subset.entrySet()) {
            String supersetVal = superset.get(e.getKey());
            if (supersetVal == null || !supersetVal.equals(e.getValue())) return false;
        }
        return true;
    }

}
