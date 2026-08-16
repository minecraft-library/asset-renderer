package lib.minecraft.renderer.tooling.blockentity;

import dev.simplified.annotations.AccessLevel;
import dev.simplified.annotations.RequiredArgsConstructor;
import dev.simplified.gson.JsonTree;
import org.jetbrains.annotations.NotNull;

/**
 * Resolves one split id in one pass - the {@link #resolve()} put-chain IS the on-disk
 * key order, declared once, here: {@code renderer} (provenance scalar) FIRST, then
 * {@code geometry}, {@code y_axis}, {@code tinted}, {@code inventory}, {@code icon},
 * {@code parts}, {@code blocks}.
 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class BlockEntityRendererResolver {

    private final @NotNull BlockEntitySubject subject;
    private final @NotNull BlockGeometrySourceResolver.Split split;
    private final @NotNull BlockTintFlagResolver tint;
    private final @NotNull BlockGuiResolver gui;
    private final @NotNull BlockCatalogResolver catalog;
    private final @NotNull InventoryTransformResolver transform;

    /**
     * The model node - invocation order IS on-disk member order.
     *
     * @return the model entry
     */
    @NotNull JsonTree resolve() {
        return JsonTree.object()
            .put("renderer", this.subject.rendererClass())          // provenance scalar, used for diagnostics
            .put("geometry", this.split.geometryKey())              // -> block_geometry manifest key
            .put("y_axis", this.split.yAxis().name())               // pivot-band heuristic
            .put("tinted", this.tint.isTinted(this.subject.rendererClass(), this.split.factoryClass()))
            .put("inventory", inventory())                          // { y_rotation, flip, transform? }
            .putIf("icon", this.gui.icon(this.split.splitId()))     // { rotation?, additive? }
            .putIf("parts", BlockPartsResolver.resolve(this.split.splitId()))  // sub-model composition
            .putIf("blocks", this.catalog.blocks(this.split.splitId()));  // ordered {block, texture, variant?, tint?}
    }

    /** The {@code inventory} node - GUI facing (BlockGuiResolver) plus the decomposed bytecode transform. */
    private @NotNull JsonTree inventory() {
        return JsonTree.object()
            .put("y_rotation", this.gui.yRotation(this.split.splitId()))
            .put("flip", this.gui.flip(this.subject.rendererClass(), this.split.splitId()))
            .putIf("transform", this.transform.resolve(this.subject.rendererClass(), this.split.splitId()));
    }

}
