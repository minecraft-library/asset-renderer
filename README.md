# Minecraft Asset Renderer

Headless rendering library for Minecraft blocks, items, entities, fluids, and portals. Reads a vanilla client JAR and any stack of resource packs, then produces isometric or 2D previews as static PNGs or animated frame sequences.

> [!IMPORTANT]
> This library downloads and processes **copyrighted assets owned by [Mojang AB](https://www.minecraft.net/)** (a Microsoft subsidiary) at runtime. Models, textures, and sounds are extracted directly from the official Minecraft client JAR and are **never distributed** with this repository. You are responsible for ensuring your use of the rendered output complies with the [Minecraft EULA](https://www.minecraft.net/en-us/eula) and [Minecraft Usage Guidelines](https://www.minecraft.net/en-us/usage-guidelines).

## Table of Contents

- [Features](#features)
- [Getting Started](#getting-started)
  - [Prerequisites](#prerequisites)
  - [Installation](#installation)
  - [Usage](#usage)
- [Renderers](#renderers)
- [Gradle Tasks](#gradle-tasks)
  - [Build and Test](#build-and-test)
  - [Visual Inspection](#visual-inspection)
  - [JMH Benchmarks](#jmh-benchmarks)
- [Package Structure](#package-structure)
- [Resource Tooling](#resource-tooling)
- [Contributing](#contributing)
- [License](#license)

## Features

- **Pluggable renderers** - `BlockRenderer`, `ItemRenderer`, `EntityRenderer`, `PlayerRenderer`, `FluidRenderer`, `PortalRenderer`, `TextRenderer`, plus composite `AtlasRenderer`, `GridRenderer`, `LayoutRenderer`, and `MenuRenderer`
- **Minecraft 26.1 and later** - Pulls client JARs via the Piston API and loads overlay resource packs (CIT, CTM, banner patterns, custom item definitions) on top of vanilla (the asset / pack-format parsing targets the 26.1+ client-jar layout)
- **Isometric or 2D output** - `ModelEngine` with a `Camera` pose for 30/45° block previews, `RasterEngine` for flat tile icons, both composing the same texture/light subsystems
- **Static PNG or animated frames** - Returns `StaticImageData` or `AnimatedImageData` from [simplified-dev/image](https://github.com/simplified-dev/image) - animated textures, portals, and fluids drive multi-frame output transparently
- **Vector API SIMD** - JDK 21 incubator `FloatVector` backs `ModelEngine` matrix math and `PortalRenderer` layer transforms
- **Stateless renderers** - All input flows through an immutable options record; renderers share an ambient `RendererContext` and can be cached for the lifetime of a pack stack

## Getting Started

### Prerequisites

| Requirement | Version | Notes |
|-------------|---------|-------|
| [JDK](https://adoptium.net/) | **21+** | Required. Vector API (`jdk.incubator.vector`) must be on the module path |
| [Gradle](https://gradle.org/) | 8.x | Wrapper is bundled (`./gradlew`) |
| [Git](https://git-scm.com/) | 2.x+ | For cloning the repository |

> [!IMPORTANT]
> The `--add-modules=jdk.incubator.vector` flag is required at **both compile time and every JVM invocation that loads this code** (tests, JavaExec tooling, JMH forks). The Gradle build wires it into every task automatically, but downstream consumers must add it themselves or see a class-not-found failure at load.

### Installation

Add the JitPack repository and the dependency to your `build.gradle.kts`:

```kotlin
repositories {
    maven(url = "https://jitpack.io")
}

dependencies {
    implementation("com.github.minecraft-library:asset-renderer:master-SNAPSHOT")
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("--add-modules=jdk.incubator.vector")
}
tasks.withType<Test>().configureEach {
    jvmArgs("--add-modules=jdk.incubator.vector")
}
tasks.withType<JavaExec>().configureEach {
    jvmArgs("--add-modules=jdk.incubator.vector")
}
```

Or clone and build locally:

```bash
git clone https://github.com/minecraft-library/asset-renderer.git
cd asset-renderer
./gradlew build
```

### Usage

Run the pipeline once to produce a `Pipeline.Result`, wrap it in a `PipelineRendererContext`, then instantiate any `Renderer<O>` against that context:

```java
// 1. Configure and run the pipeline. Pipeline.run is static; it downloads the client JAR on
//    first call and caches it under PipelineOptions.cacheRoot for subsequent runs. All Mojang
//    network access flows through a shared MojangContract proxy on Pipeline (see
//    api.simplified.mojang for the upstream contract).
PipelineOptions pipelineOptions = PipelineOptions.builder()
    .version("26.1")
    .texturePacks(Concurrent.newList(myResourcePackZip))
    .build();

Pipeline.Result result = Pipeline.run(pipelineOptions);

// 2. Wrap the result in a context. Eagerly materialises every block/item entity;
//    textures stream from disk on first lookup and are then cached.
PipelineRendererContext context = PipelineRendererContext.of(result);

// 3. Render. Renderers are stateless - cache them for the lifetime of the context. Output size,
//    projection, and SSAA / FXAA live on the shared OutputOptions.
BlockRenderer blockRenderer = new BlockRenderer(context);
BlockOptions blockOptions = BlockOptions.builder()
    .blockId("minecraft:diamond_ore")
    .output(OutputOptions.builder().canvasSize(512).build())
    .build();
ImageData block = blockRenderer.render(blockOptions);
ImageIO.write(block.toBufferedImage(), "PNG", new File("diamond_ore.png"));

// Entities render from vanilla-client-jar-derived models. EntityAppearance selects the per-entity
// axes (age, behavioural state, dye tints, tropical-fish pattern / shape, equipment, ...); the
// default appearance renders the plain mob.
EntityRenderer entityRenderer = new EntityRenderer(context);
EntityOptions entityOptions = EntityOptions.builder()
    .entityId(Optional.of("minecraft:zombie"))
    .output(OutputOptions.builder().canvasSize(512).supersample(2).build())
    .build();
ImageData entity = entityRenderer.render(entityOptions);
ImageIO.write(entity.toBufferedImage(), "PNG", new File("zombie.png"));
```

> [!NOTE]
> `ImageData` is either `StaticImageData` (single frame) or `AnimatedImageData` (multiple frames with per-frame delay). Items (enchant glint / animated sprites), fluids, and portals return the animated variant; see the [Renderers](#renderers) table for which produce animation. Branch on the concrete type or call `image.frames()` to iterate.

> [!IMPORTANT]
> `PipelineOptions` supports Minecraft **`26.1` (the default) and later only** - the asset extraction and pack-format parsing target the 26.1+ client-jar layout, so earlier versions are not supported. The JAR is cached under `cacheRoot` (default `./cache/asset-renderer`); pass `forceDownload(true)` on the builder to re-fetch after a version bump.

## Renderers

| Renderer | Options | Static | Animated | Notes |
|----------|---------|:------:|:--------:|-------|
| `BlockRenderer` | `BlockOptions` | ✅ | ❌ | Isometric cube or 2D face; animated textures pinned to frame 0 |
| `ItemRenderer` | `ItemOptions` | ✅ | ✅ | GUI + held transforms, durability bars, animated enchant glint |
| `EntityRenderer` | `EntityOptions` | ✅ | ❌ | Vanilla-client-jar-derived models; per-entity `EntityAppearance` axes |
| `PlayerRenderer` | `PlayerOptions` | ✅ | ❌ | Skins with armor, trims, and held items |
| `FluidRenderer` | `FluidOptions` | ✅ | ✅ | Water / lava, biome variants, still + flowing |
| `PortalRenderer` | `PortalOptions` | ✅ | ✅ | End portal / gateway, layered shader effect |
| `TextRenderer` | `TextOptions` | ✅ | ❌ | SkyBlock-style tooltips, lore, stack counts |
| `AtlasRenderer` | `AtlasOptions` | ✅ | ❌ | Full pack dump into a tile grid (+ sidecar JSON) |
| `GridRenderer` | `GridOptions` | ✅ | ❌ | Arbitrary child layout into a grid |
| `LayoutRenderer` | `LayoutOptions` | ✅ | ❌ | Freeform placement of child renders |
| `MenuRenderer` | `MenuOptions` | ✅ | ❌ | Container UIs (chest, furnace, etc.) |

## Gradle Tasks

### Build and Test

```bash
./gradlew build       # compile, test, assemble jar
./gradlew test        # fast unit tests
./gradlew slowTest    # integration + parallelism tests (hit network and cache)
```

> [!TIP]
> `slowTest` is tagged `@Tag("slow")` and is excluded from the default `test` task. It downloads Minecraft client JARs, decompresses asset archives, and runs parity tests against extracted classes - expect it to take several minutes the first time.

### Visual Inspection

Every task here is in the `visual` Gradle group (`./gradlew tasks --group visual`) and writes into `cache/visual/<task-name>/` for side-by-side inspection; the underlying `main()` entry points live in `src/test/java/lib/minecraft/renderer/visual/`. Flags use Gradle's `-P` property syntax.

**Free-form renders** - render a subject (or the whole set) to eyeball:

```bash
./gradlew blockRender3D     -PblockId=minecraft:tnt -PrenderSize=512 -Pssaa=2
./gradlew projectionSmoke   -PblockId=minecraft:tnt -PrenderSize=512
./gradlew itemRender2D      -PitemId=minecraft:diamond_sword -PrenderSize=256 -Ptype=gui   # or -Ptype=held
./gradlew playerRender      -PrenderSize=256
./gradlew entityRender3D    -PentityId=minecraft:zombie -PrenderSize=512 -Pprojection=ISOMETRIC
./gradlew entityProjections -PentityId=minecraft:zombie -PrenderSize=256   # one entity under every projection
./gradlew loreTooltip
./gradlew stackCountBadge   -Plabel=experiment1                            # or -Pdiff=A,B to pixel-diff two labels
./gradlew fluidRenderer
./gradlew portalRenderer
./gradlew packOverlay       -PrenderSize=256   # vanilla vs overlay pack, side-by-side
./gradlew redstoneTints     -PrenderSize=64
```

> [!TIP]
> `entityRender3D` selects per-entity `EntityAppearance` axes through `-Dasset.entity.*` system properties, e.g. `-Dasset.entity.state=tame`, `-Dasset.entity.age=baby`, `-Dasset.entity.collar=magenta`, `-Dasset.entity.wool=lime`, `-Dasset.entity.base_color=orange`, `-Dasset.entity.pattern=clayfish`, `-Dasset.entity.pattern_color=white`, `-Dasset.entity.sheared=true`, `-Dasset.entity.toggles=horn`, `-Dasset.entity.equipment=body:diamond`. All `-Dasset.*` flags auto-forward to the fork.

**Parity** - diff the pipeline against pixel-perfect ground truth from the [vanilla-reference-harness] in `harness/` (a headless Fabric mod that drives the actual MC client to render every block, item, and living entity at a locked iso pose). Reference PNGs live under `cache/asset-renderer/vanilla/<mc-version>/references/{blocks,items,entities,glint}/`; each `*ParityVanilla` task writes per-subject vanilla/java/diff panels to `cache/visual/<subject>-parity-vanilla/` and groups results into mean-ARGB delta buckets (`<0.25 / <0.5 / <0.75 / <1` per pixel).

```bash
./gradlew entityParityVanilla -PentityId=minecraft:zombie          # omit -P for the full sweep
./gradlew blockParityVanilla  -PblockId=minecraft:tnt
./gradlew itemParityVanilla   -PitemId=minecraft:diamond_sword
./gradlew glintParityVanilla  -PitemId=minecraft:nether_star       # animated enchant-glint parity
```

Re-render the ground truth (only on MC version bumps or harness fixes; `tooling`-group tasks):

```bash
./gradlew renderVanillaReferences        # blocks + items + entities
./gradlew renderVanillaGlintReferences   # animated glint strips (then run glintParityVanilla)
```

See `CLAUDE.md` for the parity / harness session-refresh checklist and per-renderer override gotchas.

### JMH Benchmarks

```bash
./gradlew jmh
./gradlew jmh -PjmhInclude=FluidAnimationBenchmark
./gradlew jmh -PjmhWarmup=1 -PjmhIters=3 -PjmhForks=1 -PjmhProfilers=gc,stack
```

| Property | Default | Description |
|----------|---------|-------------|
| `jmhWarmup` | `3` | Warmup iterations per fork |
| `jmhIters` | `5` | Measurement iterations per fork |
| `jmhForks` | `2` | Number of JVM forks |
| `jmhInclude` | `.*` | Regex limiting which benchmark classes run |
| `jmhProfilers` | _unset_ | Comma-separated JMH profilers (e.g. `gc`, `stack`) |

Benchmarks live in `src/jmh/java/lib/minecraft/renderer/bench/`. Forks inherit `-Xmx2g` and the Vector API module.

## Package Structure

```
asset-renderer/
├── src/
│   ├── main/java/lib/minecraft/renderer/
│   │   ├── Renderer.java             # Root contract: Renderer<O> -> ImageData
│   │   ├── BlockRenderer.java  ItemRenderer.java  EntityRenderer.java  PlayerRenderer.java
│   │   ├── FluidRenderer.java  PortalRenderer.java  TextRenderer.java
│   │   ├── AtlasRenderer.java  GridRenderer.java  LayoutRenderer.java  MenuRenderer.java
│   │   ├── asset/           # Immutable domain: Block, Item, Entity, ResourceId, ...
│   │   │   └── model/       # ModelData, EntityModelData, ModelElement, ModelFace, ...
│   │   ├── engine/          # ModelEngine + RasterEngine
│   │   │   ├── camera/      # Camera, Projection, Placement, FitRequest, ...
│   │   │   ├── compose/     # Finalize, FrameCompositor, Layer/LayerStack, GlintStage
│   │   │   ├── kit/         # EntityGeometryKit, BannerKit, GlintKit, ItemStackKit, ...
│   │   │   ├── light/       # Shading
│   │   │   ├── raster/      # rasterizer (top-left fill rule, snap, depth)
│   │   │   └── texture/     # texture / atlas resolution
│   │   ├── exception/       # PipelineException, RendererException, ...
│   │   ├── face/            # BlockFace, EntityFace, SixFaces, SkinFace
│   │   ├── option/          # BlockOptions, EntityOptions, ..., EntityAppearance, Age
│   │   │   ├── slot/        # equipment / armor slot enums
│   │   │   └── spec/        # OutputOptions, ArmorOptions, SkinOptions, ...
│   │   ├── pipeline/        # Pipeline (client-jar download/extract via simplified-api/mojang), pack loaders
│   │   │   ├── loader/      # BlockStateLoader, EntityModelLoader, EntityFamilyFlattener, ...
│   │   │   ├── pack/        # PackStack, ResourcePack, PackContainer, IndexedTexture, PackCapability, ...
│   │   │   │   └── rule/    # OptiFine rule layer: CIT/CTM/RuleSet, NBT conditionals, color.properties
│   │   │   ├── resolver/    # model / texture resolvers
│   │   │   └── util/        # SPI + shared pipeline utils
│   │   ├── request/         # Biome, DyeColor, TintAxis, TropicalFishPattern, EulerRotation, ArmorMaterial, ...
│   │   ├── tensor/          # FloatVector-backed Matrix4fOps, Vector3fOps
│   │   └── tooling/         # Tooling* Gradle entry points + ASM scanners
│   │       ├── blockentity/ # block-entity / block-model ASM emitters
│   │       ├── entity/      # entity model / geometry / family ASM emitters
│   │       ├── parser/      # GeometryParser
│   │       └── util/        # ClassKit, ClassNodeCache, VanillaSourceClasses, ...
│   ├── main/resources/lib/minecraft/renderer/    # Bundled JSON snapshots
│   ├── test/java/           # JUnit 5 tests (fast + @Tag("slow")) + visual/ and example/ main() entry points
│   └── jmh/java/lib/minecraft/renderer/bench/    # JMH benchmarks
├── build.gradle.kts  settings.gradle.kts  gradle/libs.versions.toml
└── LICENSE.md  COPYRIGHT.md  CONTRIBUTING.md  CLAUDE.md
```

## Resource Tooling

The library ships pre-generated JSON snapshots under `src/main/resources/lib/minecraft/renderer/` so it builds and runs without network access. Each is regenerated by its `tooling`-group Gradle task (`./gradlew <task>`, ASM-scanning the cached client JAR) after a Minecraft version bump - re-run the task, then commit the updated JSON.

| Resource | Purpose | Task | Source |
|----------|---------|------|--------|
| `block_defaults.json` | Per-block default blockstate (read by `BlockDefaultsLoader`) | `blockDefaults` | ASM bytewalk of each block's `registerDefaultState` |
| `block_items.json` | Secondary block to standing block-item alias map | `blockItems` | ASM walk of `Items.<clinit>` |
| `block_models.json` + `block_geometry.json` | Block-entity / block-model metadata (chest, sign, bed, banner, ...) + the bone trees it points at | `blockModels` | ASM scan of block-entity model classes |
| `block_tints.json` | Block-colour tint hooks | `blockTints` | ASM scan of `BlockColors` |
| `color_maps.json` | Grass / foliage / water biome tint maps | `colorMaps` | Vanilla biome colormap PNGs |
| `entity_models.json` + `entity_geometry.json` | Entity family form + geometry | `entityModels` | ASM scan of vanilla client-jar entity `Model` factories |
| `glint_items.json` | Always-foil items (`ENCHANTMENT_GLINT_OVERRIDE`) | `glintItems` | ASM scan of `Items` |
| `potion_colors.json` | Vanilla `MobEffects` colour values | `potionColors` | ASM scan of `MobEffects` |

> [!NOTE]
> These tasks fetch the client JAR automatically on first run through `Pipeline`, then reuse `<cacheRoot>/vanilla/<version>/client.jar`. Every table above is guarded by `manifest.tooling-tables` in the parity store, which takes that whole directory as its source and holds a digest per shipped table beside a digest per flow log. Re-run the flow, then `./gradlew parityCapture -Partifacts=manifest.tooling-tables` and `./gradlew parityCompare` to see what moved; `./gradlew parityPromote` is what makes a moved value the new baseline, and it takes a reason.

The single `generateAtlas` task dumps every block + item into `build/atlas/atlas.png` (+ `atlas.json`). It sits in the `build` group rather than `tooling` and runs from the test sourceset as a worked example of driving `AtlasRenderer`: `-Pdiagnose` also scans the atlas for blank and sparse tiles into `missing.json`, `-PsourceFilter=<source>` also writes a mini-atlas of that one source, and `-PskipRender` reads the atlas already on disk instead of re-rendering it. A build diagnostic, not a bundled resource.

### Runtime Directories

Created during execution and excluded from version control:

| Directory | Contents |
|-----------|----------|
| `cache/` | Client JARs, extracted assets, test-render output |
| `texturepacks/` | User-supplied overlay packs discovered by `TexturePackLoader` |
| `build/` | Gradle outputs and `generateAtlas` task products |

[vanilla-reference-harness]: harness

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for development setup, code style guidelines, and how to submit a pull request.

## License

This project is licensed under the **Apache License 2.0** - see [LICENSE](LICENSE.md) for the full text.

See [COPYRIGHT.md](COPYRIGHT.md) for third-party attribution notices, including information about Mojang AB's copyrighted assets and upstream library licensing.
