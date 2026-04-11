package dev.sbs.renderer.model.asset;

import com.google.gson.JsonObject;
import dev.simplified.collection.ConcurrentList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A parsed {@code "multipart"} blockstate definition. Each part carries an optional condition
 * and a model reference (with rotation) to apply when the condition matches the block's
 * properties. Parts without a condition are unconditional and always rendered.
 *
 * @param parts the ordered list of conditional or unconditional parts
 */
public record BlockStateMultipart(@NotNull ConcurrentList<Part> parts) {

    /**
     * A single entry in a multipart blockstate.
     *
     * @param when the raw condition JSON, or {@code null} for unconditional parts
     * @param apply the model reference and rotation to render when the condition matches
     */
    public record Part(
        @Nullable JsonObject when,
        @NotNull BlockStateVariant apply
    ) {}

}
