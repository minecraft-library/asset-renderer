/**
 * ASM-driven build-time tooling that regenerates the bundled JSON snapshots the runtime
 * pipeline reads. <b>Run on every Minecraft version bump</b>; checked-in output lives under
 * {@code src/main/resources/lib/minecraft/renderer/}.
 *
 * <p>The tooling layer is the one place the codebase reaches into vanilla bytecode to
 * extract data that has no resource-pack representation - block tints, potion colors, block
 * entity hardcoded models, entity render layers, biome colormaps. Each generator is a
 * {@code public static void main(String[])} entry point invoked by a corresponding Gradle
 * JavaExec task:
 *
 * <table>
 *   <caption>Gradle task -&gt; tooling class -&gt; output resource</caption>
 *   <tr><td><b>Gradle task</b></td><td><b>Tooling class</b></td><td><b>Output</b></td></tr>
 *   <tr>
 *     <td>{@code blockTints}</td>
 *     <td>{@link ToolingBlockTints}</td>
 *     <td>{@code block_tints.json}</td>
 *   </tr>
 *   <tr>
 *     <td>{@code potionColors}</td>
 *     <td>{@link ToolingPotionColors}</td>
 *     <td>{@code potion_colors.json}</td>
 *   </tr>
 *   <tr>
 *     <td>{@code blockEntities}</td>
 *     <td>{@link ToolingBlockEntities}</td>
 *     <td>{@code block_entities.json}</td>
 *   </tr>
 *   <tr>
 *     <td>{@code entityModels}</td>
 *     <td>{@link ToolingEntityModels}</td>
 *     <td>{@code entity_models.json} + {@code entity_geometry.json}</td>
 *   </tr>
 *   <tr>
 *     <td>{@code colorMaps}</td>
 *     <td>{@link ToolingColorMaps}</td>
 *     <td>{@code color_maps.json}</td>
 *   </tr>
 *   <tr>
 *     <td>{@code atlas} / {@code diagnoseAtlas}</td>
 *     <td>{@link ToolingAtlas} /
 *         {@link ToolingAtlasDiagnose}</td>
 *     <td>{@code build/atlas/} (test only)</td>
 *   </tr>
 * </table>
 *
 * <p><b>ASM scanning approach.</b> Each tooling class opens the extracted client jar (via
 * {@link Pipeline Pipeline}), loads the relevant vanilla
 * class as a {@code ClassNode}, and walks the {@code <clinit>} or {@code createBodyLayer}
 * bytecode looking for the instruction patterns that produce the constant table. The patterns
 * are captured in code rather than maintained as data because the bytecode-level shape is
 * stable across Minecraft updates while the JSON / resource-pack representation that vanilla
 * sometimes ships as a fallback is not. Common scaffolding lives in
 * {@link AsmKit AsmKit}: opcode predicates, integer-literal
 * extraction, invokestatic-follow, and the static-field constant-pool reader the multiple
 * scanners share.
 *
 * <p><b>Sub-packages.</b>
 * <ul>
 *   <li>{@link lib.minecraft.renderer.tooling.blockentity blockentity} - shared block-entity
 *       scaffolding consumed by both {@code ToolingBlockEntities} and the entity pipeline.
 *       {@link Source Source} is the resolved
 *       call-site root (a {@code createBodyLayer} method or equivalent), and
 *       {@link SourceDiscovery SourceDiscovery},
 *       {@link BlockListDiscovery BlockListDiscovery},
 *       {@link TintDiscovery TintDiscovery},
 *       {@link InventoryTransformDecomposer
 *       InventoryTransformDecomposer}, and the
 *       {@link YAxis YAxis} convention helper round
 *       out the discovery surface.</li>
 *   <li>{@link lib.minecraft.renderer.tooling.entity entity} - the Java-derived entity-model
 *       pipeline phases A-D, orchestrated by
 *       {@link lib.minecraft.renderer.tooling.entity.ToolingEntityContext ToolingEntityContext}
 *       and the per-entity
 *       {@link lib.minecraft.renderer.tooling.entity.EntitySessionWalk EntitySessionWalk}:
 *       {@link EntityRegistryDiscovery mob + renderer registry},
 *       {@link EntityTextureResolver texture binding},
 *       {@link EntityVariantResolver variant tables},
 *       {@link EntityBoneResolver addLayer overlay scan + hidden bones},
 *       {@link EntityLayerDefinitionResolver layer definitions},
 *       {@link EntityOverlayResolver overlay resolution},
 *       {@link EntityBlockOverlayResolver block-overlay resolution},
 *       {@link EntityRendererOverrides renderer overrides (setupRotations yaw addend +
 *       scale residue)}, and the
 *       {@link lib.minecraft.renderer.tooling.entity.EntityDiagnosticsWriter EntityDiagnosticsWriter}
 *       /
 *       {@link lib.minecraft.renderer.tooling.entity.EntityRuntimeJsonWriter EntityRuntimeJsonWriter}
 *       pair that emit the diagnostic and runtime JSONs (cross-entity family clustering folded
 *       into {@code EntityRuntimeJsonWriter}). Procedural-loop entity factories
 *       (squid, blaze, ghast, magma_cube, ender_dragon, silverfish, endermite, guardian,
 *       elder_guardian) are folded by the shared
 *       {@link lib.minecraft.renderer.tooling.parser.GeometryParser GeometryParser} at parse
 *       time via for-loop unrolling, static-array fold, local-array tracking,
 *       Math.cos/sin + Mth.cos/sin handlers, makeConcatWithConstants resolution, and
 *       RandomSource simulation.</li>
 *   <li>{@link lib.minecraft.renderer.tooling.parser parser} -
 *       {@link lib.minecraft.renderer.tooling.parser.GeometryParser GeometryParser} - the
 *       shared bytecode walker for the
 *       {@code LayerDefinition.create / CubeListBuilder / PartPose / addOrReplaceChild}
 *       pattern, called by both the block-entity and entity tooling.</li>
 *   <li>{@link lib.minecraft.renderer.tooling.util util} - ASM scaffolding
 *       ({@link AsmKit AsmKit} plus its
 *       {@link lib.minecraft.renderer.tooling.util.ClassNodeCache ClassNodeCache}),
 *       {@link Diagnostics Diagnostics} dump scaffolding,
 *       {@link lib.minecraft.renderer.tooling.util.JsonOptional JsonOptional} helpers for
 *       the optional-with-default JSON access pattern,
 *       and {@link FastTrig FastTrig} - a port of
 *       vanilla's {@code net.minecraft.util.Mth} {@code cos / sin} 65536-entry lookup that
 *       matches the bytecode bit-for-bit; the tooling layer uses it when unrolling
 *       {@code Mth.cos / sin} call sites so the emitted geometry agrees with what
 *       {@link EntityGeometryKit EntityGeometryKit} would produce
 *       at runtime.</li>
 * </ul>
 *
 * <p><b>Diagnostics.</b> Each tooling class emits a JSON dump to
 * {@code cache/asset-renderer/diagnostics/} alongside its production output. The dump
 * captures intermediate state - candidate fields, untouched constants, fallback fires -
 * that the production output collapses away. {@code cache/} is gitignored, so diagnostics
 * are local-only artifacts intended for hand-eyeballing during a version bump.
 *
 * @see lib.minecraft.renderer.pipeline.Pipeline
 * @see lib.minecraft.renderer.kit.EntityGeometryKit
 * @see lib.minecraft.renderer.tooling.util.AsmKit
 */
package lib.minecraft.renderer.tooling;

import lib.minecraft.renderer.kit.EntityGeometryKit;
import lib.minecraft.renderer.pipeline.Pipeline;
import lib.minecraft.renderer.tooling.ToolingAtlas;
import lib.minecraft.renderer.tooling.ToolingAtlasDiagnose;
import lib.minecraft.renderer.tooling.ToolingBlockEntities;
import lib.minecraft.renderer.tooling.ToolingBlockTints;
import lib.minecraft.renderer.tooling.ToolingColorMaps;
import lib.minecraft.renderer.tooling.ToolingEntityModels;
import lib.minecraft.renderer.tooling.ToolingPotionColors;
import lib.minecraft.renderer.tooling.blockentity.BlockListDiscovery;
import lib.minecraft.renderer.tooling.blockentity.InventoryTransformDecomposer;
import lib.minecraft.renderer.tooling.blockentity.Source;
import lib.minecraft.renderer.tooling.blockentity.SourceDiscovery;
import lib.minecraft.renderer.tooling.blockentity.TintDiscovery;
import lib.minecraft.renderer.tooling.blockentity.YAxis;
import lib.minecraft.renderer.tooling.entity.EntityBlockOverlayResolver;
import lib.minecraft.renderer.tooling.entity.EntityBoneResolver;
import lib.minecraft.renderer.tooling.entity.EntityLayerDefinitionResolver;
import lib.minecraft.renderer.tooling.entity.EntityOverlayResolver;
import lib.minecraft.renderer.tooling.entity.EntityRegistryDiscovery;
import lib.minecraft.renderer.tooling.entity.EntityRendererOverrides;
import lib.minecraft.renderer.tooling.entity.EntityTextureResolver;
import lib.minecraft.renderer.tooling.entity.EntityVariantResolver;
import lib.minecraft.renderer.tooling.util.AsmKit;
import lib.minecraft.renderer.tooling.util.Diagnostics;
import lib.minecraft.renderer.tooling.util.FastTrig;
