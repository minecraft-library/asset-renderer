package lib.minecraft.renderer.tooling.blockentity;

import lib.minecraft.renderer.tooling.kernel.JsonNode;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * The {@code parts} node: the ordered sub-model composition rows
 * {@code {model, offset?, texture?}} a base split renders alongside its own geometry (the bed
 * foot, the banner flag, the decorated-pot sides). Driven entirely by the declared
 * {@link BlockTransformPolicies#partsOf} roster - the offsets and the pot-side texture are
 * declared facts, the part model ids are our split-id vocabulary.
 *
 * <p>{@code offset} stays the deliberate int channel; it is omitted when the roster carries none
 * (the banner flag composes with no offset), present as {@code [0, 0, 0]} for the pot sides.
 */
final class BlockPartsResolver {

    private BlockPartsResolver() {
    }

    /**
     * The split's {@code parts} array, or {@code null} when it composes no sub-models.
     *
     * @param splitId the models key
     * @return the {@code parts} array node, or {@code null}
     */
    static @Nullable JsonNode resolve(@NotNull String splitId) {
        List<BlockTransformPolicies.PartSpec> specs = BlockTransformPolicies.partsOf(splitId);
        if (specs == null) return null;
        JsonNode parts = JsonNode.array();
        for (BlockTransformPolicies.PartSpec spec : specs) {
            JsonNode part = JsonNode.object().put("model", spec.model());
            if (spec.offset() != null) part.putInts("offset", spec.offset());
            part.putIf("texture", spec.texture());
            parts.add(part);
        }
        return parts;
    }

}
