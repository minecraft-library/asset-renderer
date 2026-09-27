/**
 * The values a mesh and its projection are written in.
 *
 * <p>The primitives are {@link lib.minecraft.renderer.engine.geometry.Box Box}, an immutable
 * axis-aligned min/max pair with the factories, growth and union a builder measures extents with;
 * {@link lib.minecraft.renderer.engine.geometry.EulerRotation EulerRotation}, a data-only triple of
 * angles in degrees that leaves every composition order to its call site; and
 * {@link lib.minecraft.renderer.engine.geometry.ModelUnits ModelUnits}, the sixteen-unit authoring grid
 * a model states its coordinates on and the divisor that carries them into the engine's unit cube.
 *
 * <p>The face vocabulary is {@link lib.minecraft.renderer.engine.geometry.Face Face}, the six cardinal
 * directions of an axis-aligned cube with each one's name, outward normal and in-plane axes, and
 * {@link lib.minecraft.renderer.engine.geometry.AxisSigns AxisSigns}, the eight ways to negate a
 * subset of the three axes - rotations and reflections alike, closed under composition - which is
 * every frame relation a face or a point is carried through.
 *
 * <p>How one face is wound and textured varies along two independent axes, one type each.
 * {@link lib.minecraft.renderer.engine.geometry.CornerPhase CornerPhase} picks the corner a quad
 * starts at and pairs each corner with its UV slot, which fixes the diagonal the quad splits on;
 * {@link lib.minecraft.renderer.engine.geometry.Unwrap Unwrap} derives a face's texture rectangle,
 * either projected from an element's own bounds or read as a strip of one shared sheet from a cube's
 * origin, size and mirror flag. {@link lib.minecraft.renderer.engine.geometry.FaceTextures
 * FaceTextures} answers the texture a box paints, asked one face at a time.
 *
 * <p>A type no render reaches does not belong here, and neither does one whose code, imports and
 * javadoc aside, names a Minecraft subject - a block, an item, an entity, a {@code minecraft:} id or
 * a vanilla class - or one that imports from {@code vanilla}, {@code asset}, {@code request},
 * {@code port}, {@code content}, {@code bake}, {@code screen} or the root package. What turns these
 * values into triangles is {@link lib.minecraft.renderer.engine.mesh engine.mesh}.
 *
 * <p><b>Parity.</b> Everything here is part of a render, and the pipeline dump serialises loaded data
 * without calling a renderer, so the package declares the {@code engine-renders} claim as a demotion
 * of the dump's verdict on a change made here. Every type here carries a declaration of its own
 * besides, joining the {@code face-vocabulary}, {@code tensor-math} or {@code box-builder} claim.
 */
@Parity(claim = "engine-renders", mode = Mode.DEMOTE, scope = Scope.SUBTREE)
package lib.minecraft.renderer.engine.geometry;

import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Scope;
