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
 *     <td>{@link lib.minecraft.renderer.tooling.ToolingBlockTints ToolingBlockTints}</td>
 *     <td>{@code block_tints.json}</td>
 *   </tr>
 *   <tr>
 *     <td>{@code potionColors}</td>
 *     <td>{@link lib.minecraft.renderer.tooling.ToolingPotionColors ToolingPotionColors}</td>
 *     <td>{@code potion_colors.json}</td>
 *   </tr>
 *   <tr>
 *     <td>{@code blockModels}</td>
 *     <td>{@link lib.minecraft.renderer.tooling.ToolingBlockModels ToolingBlockModels}</td>
 *     <td>{@code block_models.json}</td>
 *   </tr>
 *   <tr>
 *     <td>{@code entityModels}</td>
 *     <td>{@link lib.minecraft.renderer.tooling.ToolingEntityModels ToolingEntityModels}</td>
 *     <td>{@code entity_models.json} + {@code entity_geometry.json}</td>
 *   </tr>
 *   <tr>
 *     <td>{@code colorMaps}</td>
 *     <td>{@link lib.minecraft.renderer.tooling.ToolingColorMaps ToolingColorMaps}</td>
 *     <td>{@code color_maps.json}</td>
 *   </tr>
 *   <tr>
 *     <td>{@code atlas} / {@code diagnoseAtlas}</td>
 *     <td>{@link lib.minecraft.renderer.tooling.ToolingAtlas ToolingAtlas} /
 *         {@link lib.minecraft.renderer.tooling.ToolingAtlasDiagnose ToolingAtlasDiagnose}</td>
 *     <td>{@code build/atlas/} (test only)</td>
 *   </tr>
 * </table>
 *
 * <p><b>ASM scanning approach.</b> Each tooling class opens the extracted client jar (via
 * {@link lib.minecraft.renderer.pipeline.Pipeline Pipeline}), loads the relevant vanilla
 * class as a {@code ClassNode}, and walks the {@code <clinit>} or {@code createBodyLayer}
 * bytecode looking for the instruction patterns that produce the constant table. The patterns
 * are captured in code rather than maintained as data because the bytecode-level shape is
 * stable across Minecraft updates while the JSON / resource-pack representation that vanilla
 * sometimes ships as a fallback is not. Common scaffolding lives in
 * {@link lib.minecraft.renderer.tooling.util.AsmKit AsmKit}: opcode predicates, integer-literal
 * extraction, invokestatic-follow, and the static-field constant-pool reader the multiple
 * scanners share.
 *
 * <p><b>Sub-packages.</b>
 * <ul>
 *   <li>{@link lib.minecraft.renderer.tooling.blockentity blockentity} - shared block-entity
 *       scaffolding consumed by both {@code ToolingBlockModels} and the entity pipeline.
 *       {@link lib.minecraft.renderer.tooling.blockentity.Source Source} is the resolved
 *       call-site root (a {@code createBodyLayer} method or equivalent), and
 *       {@link lib.minecraft.renderer.tooling.blockentity.SourceDiscovery SourceDiscovery},
 *       {@link lib.minecraft.renderer.tooling.blockentity.BlockListDiscovery BlockListDiscovery},
 *       {@link lib.minecraft.renderer.tooling.blockentity.TintDiscovery TintDiscovery},
 *       {@link lib.minecraft.renderer.tooling.blockentity.InventoryTransformDecomposer *       InventoryTransformDecomposer}, and the
 *       {@link lib.minecraft.renderer.tooling.blockentity.YAxis YAxis} convention helper round
 *       out the discovery surface.</li>
 *   <li>{@link lib.minecraft.renderer.tooling.entity entity} - the Java-derived entity-model
 *       pipeline (discovery, per-entity binding, geometry parse, emission), orchestrated by
 *       {@link lib.minecraft.renderer.tooling.entity.EntityToolingContext EntityToolingContext}
 *       and the per-entity
 *       {@link lib.minecraft.renderer.tooling.entity.EntitySessionWalk EntitySessionWalk}:
 *       {@link lib.minecraft.renderer.tooling.entity.EntityRegistryDiscovery mob + renderer registry},
 *       {@link lib.minecraft.renderer.tooling.entity.EntityTextureResolver texture binding},
 *       {@link lib.minecraft.renderer.tooling.entity.EntityVariantResolver variant tables},
 *       {@link lib.minecraft.renderer.tooling.entity.EntityBoneResolver addLayer overlay scan + hidden bones},
 *       {@link lib.minecraft.renderer.tooling.entity.EntityLayerDefinitionResolver layer definitions},
 *       {@link lib.minecraft.renderer.tooling.entity.EntityOverlayResolver overlay resolution},
 *       {@link lib.minecraft.renderer.tooling.entity.EntityBlockOverlayResolver block-overlay resolution},
 *       {@link lib.minecraft.renderer.tooling.entity.EntityRendererOverrides renderer overrides (setupRotations yaw addend +
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
 *       ({@link lib.minecraft.renderer.tooling.util.AsmKit AsmKit} plus its
 *       {@link lib.minecraft.renderer.tooling.util.ClassNodeCache ClassNodeCache}),
 *       {@link lib.minecraft.renderer.tooling.util.Diagnostics Diagnostics} dump scaffolding,
 *       {@link lib.minecraft.renderer.tooling.util.JsonOptional JsonOptional} helpers for
 *       the optional-with-default JSON access pattern,
 *       and {@link lib.minecraft.renderer.tooling.util.FastTrig FastTrig} - a port of
 *       vanilla's {@code net.minecraft.util.Mth} {@code cos / sin} 65536-entry lookup that
 *       matches the bytecode bit-for-bit; the tooling layer uses it when unrolling
 *       {@code Mth.cos / sin} call sites so the emitted geometry agrees with what
 *       {@link lib.minecraft.renderer.engine.kit.EntityGeometryKit EntityGeometryKit} would produce
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
 * @see lib.minecraft.renderer.engine.kit.EntityGeometryKit
 * @see lib.minecraft.renderer.tooling.util.AsmKit
 */
package lib.minecraft.renderer.tooling;
