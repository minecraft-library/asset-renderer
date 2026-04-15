package dev.sbs.renderer;

import dev.sbs.renderer.engine.ModelEngine;
import dev.sbs.renderer.engine.RenderEngine;
import dev.sbs.renderer.engine.RendererContext;
import dev.sbs.renderer.geometry.PerspectiveParams;
import dev.sbs.renderer.geometry.VisibleTriangle;
import dev.sbs.renderer.kit.EntityGeometryKit;
import dev.sbs.renderer.kit.GlintKit;
import dev.sbs.renderer.kit.ArmorKit;
import dev.sbs.renderer.asset.Entity;
import dev.sbs.renderer.asset.model.EntityModelData;
import dev.sbs.renderer.options.EntityOptions;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.ImageData;
import dev.simplified.image.pixel.PixelBuffer;
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
        PixelBuffer buffer = PixelBuffer.create(options.getOutputSize(), options.getOutputSize());

        if (options.getEntityId().isEmpty())
            return RenderEngine.staticFrame(buffer);

        Optional<Entity> entityLookup = this.context.findEntity(options.getEntityId().get());
        if (entityLookup.isEmpty())
            return RenderEngine.staticFrame(buffer);

        Entity entity = entityLookup.get();
        Optional<PixelBuffer> texture = resolveEntityTexture(entity, options);
        if (texture.isEmpty())
            return RenderEngine.staticFrame(buffer);

        EntityModelData model = entity.getModel();
        if (model.getBones().isEmpty())
            return RenderEngine.staticFrame(buffer);

        EntityGeometryKit.BuildResult buildResult = EntityGeometryKit.buildTriangles(model, texture.get());
        if (buildResult.triangles().isEmpty())
            return RenderEngine.staticFrame(buffer);

        ConcurrentList<VisibleTriangle> triangles = buildResult.triangles();

        // Armor overlay for humanoid entities
        ModelEngine engine = new ModelEngine(this.context);
        triangles.addAll(ArmorKit.buildEntityArmor3D(buildResult.boneBounds(),
            options.getHelmet(), options.getChestplate(),
            options.getLeggings(), options.getBoots(), engine));

        engine.rasterize(triangles, buffer, PerspectiveParams.GUI_ITEM,
            options.getPitch(), options.getYaw(), options.getRoll());

        if (options.isAntiAlias())
            buffer.applyFxaa();

        boolean enchanted = ArmorKit.hasEnchantedArmor(
            options.getHelmet(), options.getChestplate(),
            options.getLeggings(), options.getBoots()
        );
        return engine.finaliseWithGlint(buffer, enchanted, GlintKit.GlintOptions.armorDefault(30));
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
