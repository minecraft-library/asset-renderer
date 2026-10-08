package lib.minecraft.renderer.engine.draw;

import dev.simplified.collection.ConcurrentList;
import lib.minecraft.renderer.engine.math.Matrix4f;
import org.jetbrains.annotations.NotNull;

/**
 * One part of a picture drawn in one depth pass - a triangle list and the model transform it is
 * drawn through, which the rasterizer composes before its camera pose as it composes the model
 * transform of a single list.
 *
 * @param triangles the triangle list
 * @param modelTransform the model-space transform applied before the camera pose
 */
public record DrawPart(@NotNull ConcurrentList<VisibleTriangle> triangles, @NotNull Matrix4f modelTransform) {}
