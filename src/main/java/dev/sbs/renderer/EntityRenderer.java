package dev.sbs.renderer;

import dev.sbs.renderer.draw.Canvas;
import dev.sbs.renderer.draw.EntityGeometryKit;
import dev.sbs.renderer.draw.GlintKit;
import dev.sbs.renderer.draw.armor.ArmorKit;
import dev.sbs.renderer.engine.ModelEngine;
import dev.sbs.renderer.engine.RenderEngine;
import dev.sbs.renderer.engine.RendererContext;
import dev.sbs.renderer.geometry.PerspectiveParams;
import dev.sbs.renderer.geometry.VisibleTriangle;
import dev.sbs.renderer.model.Entity;
import dev.sbs.renderer.model.asset.EntityModelData;
import dev.sbs.renderer.options.EntityOptions;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.ImageData;
import dev.simplified.image.PixelBuffer;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * Renders non-player entities using their {@link EntityModelData} bone/cube tree. Resolves the
 * entity definition from the {@link RendererContext} by id, loads its texture through the
 * active pack stack, and rasterizes through {@link ModelEngine} with a GUI-item perspective.
 */
@RequiredArgsConstructor
public final class EntityRenderer implements Renderer<EntityOptions> {

    private final @NotNull RendererContext context;

    @Override
    public @NotNull ImageData render(@NotNull EntityOptions options) {
        Canvas canvas = Canvas.of(options.getOutputSize(), options.getOutputSize());

        if (options.getEntityId().isEmpty())
            return RenderEngine.staticFrame(canvas);

        Optional<Entity> entityLookup = this.context.findEntity(options.getEntityId().get());
        if (entityLookup.isEmpty())
            return RenderEngine.staticFrame(canvas);

        Entity entity = entityLookup.get();
        Optional<PixelBuffer> texture = resolveEntityTexture(entity, options);
        if (texture.isEmpty())
            return RenderEngine.staticFrame(canvas);

        EntityModelData model = entity.getModel();
        if (model.getBones().isEmpty())
            return RenderEngine.staticFrame(canvas);

        EntityGeometryKit.BuildResult buildResult = EntityGeometryKit.buildTriangles(model, texture.get());
        if (buildResult.triangles().isEmpty())
            return RenderEngine.staticFrame(canvas);

        ConcurrentList<VisibleTriangle> triangles = buildResult.triangles();

        // Armor overlay for humanoid entities
        ModelEngine engine = new ModelEngine(this.context);
        triangles.addAll(ArmorKit.buildEntityArmor3D(buildResult.boneBounds(),
            options.getHelmet(), options.getChestplate(),
            options.getLeggings(), options.getBoots(), engine));

        engine.rasterize(triangles, canvas, PerspectiveParams.GUI_ITEM,
            options.getPitch(), options.getYaw(), options.getRoll());

        if (options.isAntiAlias())
            canvas.getBuffer().applyFxaa();

        if (ArmorKit.hasEnchantedArmor(
            options.getHelmet(), options.getChestplate(),
            options.getLeggings(), options.getBoots())) {
            GlintKit.GlintOptions glintOptions = GlintKit.GlintOptions.armorDefault(30);
            Optional<PixelBuffer> glintTexture = engine.tryResolveTexture(glintOptions.glintTextureId());
            if (glintTexture.isPresent()) {
                ConcurrentList<PixelBuffer> frames = GlintKit.apply(canvas.getBuffer(), glintTexture.get(), glintOptions);
                int frameDelayMs = Math.max(1, Math.round(1000f / 30f));
                return RenderEngine.output(frames, frameDelayMs);
            }
        }

        return RenderEngine.staticFrame(canvas);
    }

    /**
     * Resolves the entity texture through the pack stack. An explicit texture id on the options
     * takes priority over the entity definition's own texture id.
     */
    private @NotNull Optional<PixelBuffer> resolveEntityTexture(
        @NotNull Entity entity,
        @NotNull EntityOptions options
    ) {
        if (options.getTextureId().isPresent())
            return this.context.resolveTexture(options.getTextureId().get());

        if (entity.getTextureId().isPresent())
            return this.context.resolveTexture(entity.getTextureId().get());

        return Optional.empty();
    }

}
