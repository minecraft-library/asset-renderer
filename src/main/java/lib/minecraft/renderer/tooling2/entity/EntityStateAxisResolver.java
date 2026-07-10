package lib.minecraft.renderer.tooling2.entity;

import lib.minecraft.renderer.tooling2.kernel.Diagnostics;
import lib.minecraft.renderer.tooling2.kernel.JsonNode;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * Node {@code axes.state} - the option-encoded state axis (SPINE 3.1 row 10; wolf
 * wild / tame / angry, the sole 26.1 family). Values come from the multi-asset variant-JSON
 * {@code assets} subkeys already held by {@link VariantIndex}; the node is a pure selector -
 * per-state textures stay INSIDE the variant options' state-keyed {@code textures} maps, so
 * no {@code options} member is ever emitted (empty-vs-absent: a state option never carries a
 * delta body).
 *
 * <p>Default and values ordering = P22 precedence, {@code wild} first, then the remaining
 * subkeys in table walk order ({@link EntityAxisPolicies#STATE_PRECEDENCE}).
 */
final class EntityStateAxisResolver {

    private final @NotNull EntitySubject subject;
    private final @NotNull VariantIndex variants;
    private final @NotNull Diagnostics diagnostics;

    EntityStateAxisResolver(@NotNull EntitySubject subject, @NotNull VariantIndex variants, @NotNull Diagnostics diagnostics) {
        this.subject = subject;
        this.variants = variants;
        this.diagnostics = diagnostics;
    }

    /**
     * The state node, or {@code null} when the family's variant table declares no
     * non-default state subkeys (single-asset families never carry a state axis).
     *
     * @return the node, or {@code null} to omit
     */
    @Nullable JsonNode resolve() {
        List<VariantIndex.Variant> table = this.variants.table(this.subject.localId());
        if (table == null) return null;

        LinkedHashSet<String> stateKeys = new LinkedHashSet<>();
        for (VariantIndex.Variant variant : table)
            for (String state : variant.textures().keySet())
                if (!"primary".equals(state)) stateKeys.add(state);
        boolean hasNonDefaultState = stateKeys.stream()
            .anyMatch(state -> !EntityAxisPolicies.STATE_PRECEDENCE.strings().contains(state));
        if (!hasNonDefaultState) return null;

        String dflt = null;
        for (String state : EntityAxisPolicies.STATE_PRECEDENCE.strings())
            if (stateKeys.contains(state)) {
                dflt = state;
                break;
            }
        if (dflt == null) dflt = stateKeys.iterator().next();

        JsonNode node = JsonNode.object().put("default", dflt);
        JsonNode values = node.childArray("values");
        values.add(dflt);
        for (String state : stateKeys)
            if (!state.equals(dflt)) values.add(state);
        this.diagnostics.info("state axis: %s, default '%s' [P22]", stateKeys, dflt);
        return node;
    }

}
