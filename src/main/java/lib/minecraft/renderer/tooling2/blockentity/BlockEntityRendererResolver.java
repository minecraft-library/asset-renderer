package lib.minecraft.renderer.tooling2.blockentity;

import lib.minecraft.renderer.tooling2.kernel.JsonNode;
import org.jetbrains.annotations.NotNull;

/**
 * ONE split id, ONE pass (SPINE 3.3 stage 3) - the {@link #resolve()} put-chain IS the on-disk
 * key order, declared once, here. The chain follows the SPINE 4 sample (normative, doc-12
 * P1 / 02 F1): {@code renderer} (provenance scalar) FIRST, then {@code geometry}, {@code y_axis},
 * and (later sessions) {@code tinted}, {@code inventory}, {@code icon}, {@code parts},
 * {@code blocks}.
 */
final class BlockEntityRendererResolver {

    private final @NotNull BlockEntitySubject subject;
    private final @NotNull BlockGeometrySourceResolver.Split split;

    BlockEntityRendererResolver(@NotNull BlockEntitySubject subject, @NotNull BlockGeometrySourceResolver.Split split) {
        this.subject = subject;
        this.split = split;
    }

    /**
     * The model node - invocation order IS on-disk member order (SPINE 4, normative).
     *
     * @return the model entry
     */
    @NotNull JsonNode resolve() {
        return JsonNode.object()
            .put("renderer", this.subject.rendererClass())          // provenance scalar (bridge + diagnostics)
            .put("geometry", this.split.geometryKey())              // -> block_geometry manifest key
            .put("y_axis", this.split.yAxis().name());              // pivot-band heuristic [P40]
    }

}
