package lib.minecraft.renderer.tooling2.blockentity;

import lib.minecraft.renderer.tooling2.kernel.JsonNode;
import org.jetbrains.annotations.NotNull;

/**
 * ONE split id, ONE pass (SPINE 3.3 stage 3) - the {@link #resolve()} put-chain IS the on-disk
 * key order, declared once, here. The chain follows the SPINE 4 sample (normative, doc-12
 * P1 / 02 F1): {@code renderer} (provenance scalar) FIRST, then {@code geometry}, {@code y_axis},
 * {@code tinted}, {@code inventory}, {@code icon}, {@code parts}, and (later sessions)
 * {@code blocks}.
 */
final class BlockEntityRendererResolver {

    private final @NotNull BlockEntitySubject subject;
    private final @NotNull BlockGeometrySourceResolver.Split split;
    private final @NotNull BlockTintFlagResolver tint;
    private final @NotNull BlockGuiResolver gui;

    BlockEntityRendererResolver(
        @NotNull BlockEntitySubject subject,
        @NotNull BlockGeometrySourceResolver.Split split,
        @NotNull BlockTintFlagResolver tint,
        @NotNull BlockGuiResolver gui
    ) {
        this.subject = subject;
        this.split = split;
        this.tint = tint;
        this.gui = gui;
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
            .put("y_axis", this.split.yAxis().name())               // pivot-band heuristic [P40]
            .put("tinted", this.tint.isTinted(this.subject.rendererClass(), this.split.factoryClass()))  // [D51]
            .put("inventory", inventory())                          // { y_rotation, flip, transform? }
            .putIf("icon", this.gui.icon(this.split.splitId()))     // { rotation?, additive? }
            .putIf("parts", BlockPartsResolver.resolve(this.split.splitId()));  // [P32] sub-model composition
    }

    /** The {@code inventory} node - GUI facing (BlockGuiResolver) plus, later, the bytecode transform. */
    private @NotNull JsonNode inventory() {
        return JsonNode.object()
            .put("y_rotation", this.gui.yRotation(this.split.splitId()))
            .put("flip", this.gui.flip(this.subject.rendererClass(), this.split.splitId()));
    }

}
