package lib.minecraft.renderer.bake.pose;

import dev.simplified.util.Possible;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.pose.PoseStyle;
import lib.minecraft.renderer.asset.pose.StyleCatalog;
import lib.minecraft.renderer.call.request.AppearanceOptions;
import lib.minecraft.renderer.content.index.StripMemo;
import lib.minecraft.renderer.engine.frame.StripFit;
import lib.minecraft.renderer.exception.RenderException;
import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * The subject, row and strip one entity render takes - the requested style resolved against the
 * definition's shipped catalog, the appearance folded into the subject with that row's entailed
 * toggles, the style resolved again on the subject's in-force view, and the strip {@link PoseStrips}
 * derives for it. A render and every reader of the schedule it draws at take this one resolution. An
 * id whose row draws nothing resolves against the bind-only catalog to the still strip, with no
 * subject.
 *
 * @param subject the definition with the appearance folded in, empty where the id's row draws nothing
 * @param row the in-force catalog row the render poses with
 * @param strip the strip a render naming no frame count and no step draws
 */
@Parity(claim = "engine-renders", mode = Mode.DEMOTE)
public record StyleResolution(@NotNull Optional<Entity> subject, @NotNull PoseStyle row, @NotNull StripFit strip) {

    /**
     * Resolves one style of one entity under one appearance.
     *
     * @param strips the memo the derived strip is held in - the one the context answering the entity
     *     id carries
     * @param found what the index answers for the entity id - present, or empty where its row draws
     *     nothing
     * @param entityId the namespaced entity id
     * @param style the requested style id
     * @param appearance the requested appearance
     * @return the subject, in-force row and derived strip a render of them takes
     * @throws RenderException if the index holds no row for the id, or the catalog refuses the style
     */
    public static @NotNull StyleResolution of(@NotNull StripMemo strips, @NotNull Possible<Entity> found,
                                              @NotNull String entityId, @NotNull String style,
                                              @NotNull AppearanceOptions appearance) {
        if (found.isAbsent())
            throw new RenderException("Entity '%s' is not an entity the index resolves", entityId);

        // An id whose row draws nothing: the style still has to be one the bind-only catalog answers,
        // and nothing is drawn, so the strip is the still one.
        if (found.isEmpty()) {
            PoseStyle row = StyleCatalog.BIND_ONLY.resolve(style, appearance::applies, entityId);
            return new StyleResolution(Optional.empty(), row, StripFit.STILL);
        }

        Entity definition = found.get();
        // Resolved twice on purpose: the first answers against the shipped union, so a refusal lists
        // every id the entity supports and the row's entailed toggles are in hand before the
        // appearance resolves; the second reads the same id off the in-force view, so what moves is
        // what the resolved subject moves.
        PoseStyle requested = definition.styles().resolve(style, appearance::applies, entityId);
        // Fold the age / carried policy into a single resolved definition up front, so every
        // downstream site (texture, ortho bounds, geometry contributors) reads it unconditionally
        // with no scattered !baby gates. The resolve is a no-op for a non-baby, non-carried appearance.
        Entity subject = appearance.styled(requested).resolve(definition);
        PoseStyle row = subject.styles().resolve(style, appearance::applies, entityId);
        return new StyleResolution(Optional.of(subject), row,
            PoseStrips.of(strips, subject, row, subject.styles().periodTicks()));
    }

}
