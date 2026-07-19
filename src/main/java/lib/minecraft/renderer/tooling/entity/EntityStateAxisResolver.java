package lib.minecraft.renderer.tooling.entity;

import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import dev.simplified.gson.JsonTree;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * Node {@code axes.state} - the option-encoded state axis (wolf wild / tame / angry,
 * the sole 26.1 family). The domain comes from the multi-asset variant-JSON
 * {@code assets} subkeys already held by {@link VariantIndex}; the node is a pure
 * selector - per-state textures stay INSIDE the variant options' state-keyed {@code textures}
 * maps, so every option body is EMPTY (a state option never carries a delta body). The
 * {@code options} key-order IS the domain, with no separate {@code values} list.
 *
 * <p>Default and option ordering follows precedence, {@code wild} first, then the remaining
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
    @Nullable JsonTree resolve() {
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

        JsonTree node = JsonTree.object().put("default", dflt);
        JsonTree options = node.child("options");
        options.put(dflt, JsonTree.object());
        for (String state : stateKeys)
            if (!state.equals(dflt)) options.put(state, JsonTree.object());
        this.diagnostics.info("state axis: %s, default '%s' [P22]", stateKeys, dflt);
        return node;
    }

}
