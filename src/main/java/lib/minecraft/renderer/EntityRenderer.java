package lib.minecraft.renderer;

import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.ImageData;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.engine.IsometricEngine;
import lib.minecraft.renderer.engine.RenderEngine;
import lib.minecraft.renderer.engine.RendererContext;
import lib.minecraft.renderer.geometry.EulerRotation;
import lib.minecraft.renderer.geometry.PerspectiveParams;
import lib.minecraft.renderer.geometry.VisibleTriangle;
import lib.minecraft.renderer.kit.ArmorKit;
import lib.minecraft.renderer.kit.EntityGeometryKit;
import lib.minecraft.renderer.kit.GlintKit;
import lib.minecraft.renderer.options.EntityOptions;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * Renders mob entities as isometric 3D icons using their {@link EntityModelData} bone/cube tree.
 * Resolves the entity definition from the {@link RendererContext} by id, loads its texture
 * through the active pack stack, and rasterizes through {@link IsometricEngine} at the standard
 * {@code [30, 225, 0]} block-icon pose so entity and block icons compose consistently. Callers
 * who want to reorient the model supply {@link lib.minecraft.renderer.options.EntityOptions#getRotation()
 * EntityOptions.rotation} as the user-override layer on top of the baked iso camera.
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

        // Armor overlay for humanoid entities. The isometric engine's camera bakes the standard
        // [30, 225, 0] block-icon pose; model.inventoryYRotation composes the entity's GUI-facing
        // yaw (mirrors block_entities' inventory_y_rotation for chest/banner/skull), then
        // options.getRotation() is the user-override layer on top. Mirrors BlockRenderer.Isometric3D.
        IsometricEngine engine = IsometricEngine.standard(this.context);
        triangles.addAll(ArmorKit.buildEntityArmor3D(buildResult.boneBounds(),
            options.getHelmet(), options.getChestplate(),
            options.getLeggings(), options.getBoots(), engine));

        EulerRotation user = options.getRotation();
        EulerRotation effective = new EulerRotation(
            user.pitch(),
            user.yaw() + model.getInventoryYRotation(),
            user.roll()
        );
        engine.rasterize(triangles, buffer, PerspectiveParams.ISOMETRIC_BLOCK, effective);

        if (options.isAntiAlias())
            buffer.applyFxaa();

        boolean enchanted = ArmorKit.hasEnchantedArmor(
            options.getHelmet(), options.getChestplate(),
            options.getLeggings(), options.getBoots()
        );
        return engine.finaliseWithGlint(buffer, enchanted, GlintKit.GlintOptions.armorDefault(30));
    }

    /**
     * Resolves the entity texture. Precedence: an explicit {@link EntityOptions#getTextureId()
     * texture id on options} (user override; looked up against the Java atlas via the pack
     * stack) &gt; the entity's own bundled {@link Entity#getTextureRef() texture_ref} (loaded
     * from the Bedrock-sourced {@code /lib/minecraft/renderer/entity_textures/<ref>.png}
     * classpath resource).
     * <p>
     * The entity-default path is classpath-only, deliberately bypassing the Java atlas - the
     * entire point of the entity pipeline is that its textures come from Bedrock and do not
     * depend on Java's texture naming. The user-override path remains atlas-aware so callers
     * can point a render at any arbitrary texture the pack stack knows about.
     */
    private @NotNull Optional<PixelBuffer> resolveEntityTexture(
        @NotNull Entity entity,
        @NotNull EntityOptions options
    ) {
        if (options.getTextureId().isPresent())
            return this.context.resolveTexture(options.getTextureId().get());

        if (entity.getTextureRef().isPresent())
            return loadBundledEntityTexture(entity.getTextureRef().get());

        return Optional.empty();
    }

    /**
     * Loads a Bedrock-bundled entity PNG from the classpath. The {@code ref} is the sub-path
     * under {@code /lib/minecraft/renderer/entity_textures/} without the {@code .png} suffix
     * (e.g. {@code "cow/cow_v2"}). Missing resources return empty; callers fall back to a
     * static blank frame.
     */
    private static @NotNull Optional<PixelBuffer> loadBundledEntityTexture(@NotNull String ref) {
        String path = "/lib/minecraft/renderer/entity_textures/" + ref + ".png";
        try (java.io.InputStream stream = EntityRenderer.class.getResourceAsStream(path)) {
            if (stream == null) return Optional.empty();
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(stream);
            if (img == null) return Optional.empty();
            return Optional.of(PixelBuffer.wrap(img));
        } catch (java.io.IOException ex) {
            return Optional.empty();
        }
    }

}
