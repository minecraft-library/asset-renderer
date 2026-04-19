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
  - [Asset Regeneration](#asset-regeneration)
  - [JMH Benchmarks](#jmh-benchmarks)
- [Package Structure](#package-structure)
- [Bundled Resources](#bundled-resources)
- [Contributing](#contributing)
- [License](#license)

## Features

- **Pluggable renderers** - `BlockRenderer`, `ItemRenderer`, `EntityRenderer`, `PlayerRenderer`, `FluidRenderer`, `PortalRenderer`, `TextRenderer`, plus composite `AtlasRenderer`, `GridRenderer`, `LayoutRenderer`, and `MenuRenderer`
- **Any Minecraft version** - Pulls client JARs via the Piston API and loads overlay resource packs (CIT, CTM, banner patterns, custom item definitions) on top of vanilla
- **Isometric or 2D output** - `IsometricEngine` for 30/45° block previews, `RasterEngine` for flat tile icons, both sharing the same `ModelEngine` pipeline
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

Run the pipeline once to produce an `AssetPipeline.Result`, wrap it in a `PipelineRendererContext`, then instantiate any `Renderer<O>` against that context:

```java
// 1. Configure and run the pipeline. Downloads the client JAR on first call and
//    caches it under AssetPipelineOptions.cacheRoot for subsequent runs.
HttpFetcher fetcher = new HttpFetcher();
AssetPipeline pipeline = new AssetPipeline(fetcher);

AssetPipelineOptions pipelineOptions = AssetPipelineOptions.builder()
    .version("26.1")
    .texturePacks(Concurrent.newList(myResourcePackZip))
    .build();

AssetPipeline.Result result = pipeline.run(pipelineOptions);

// 2. Wrap the result in a context. Eagerly materialises every block/item entity;
//    textures stream from disk on first lookup and are then cached.
PipelineRendererContext context = PipelineRendererContext.of(result);

// 3. Render. Renderers are stateless - cache them for the lifetime of the context.
BlockRenderer renderer = new BlockRenderer(context);
BlockOptions blockOptions = BlockOptions.builder()
    .blockId("minecraft:diamond_ore")
    .outputSize(512)
    .build();

ImageData image = renderer.render(blockOptions);
ImageIO.write(image.toBufferedImage(), "PNG", new File("diamond_ore.png"));
```

> [!NOTE]
> `ImageData` is either `StaticImageData` (single frame) or `AnimatedImageData` (multiple frames with per-frame delay). Animated textures, fluids, and portals return the animated variant - branch on the concrete type or call `image.frames()` to iterate.

> [!TIP]
> `AssetPipelineOptions` defaults to Minecraft `26.1` and `./cache/asset-renderer` for the JAR cache. Set `forceDownload(true)` on the builder to bypass the cache after a version bump.

## Renderers

| Renderer | Options | Output | Notes |
|----------|---------|--------|-------|
| `BlockRenderer` | `BlockOptions` | Static or animated | Isometric cube preview |
| `ItemRenderer` | `ItemOptions` | Static or animated | Handles held-item transforms, durability bars, glint |
| `EntityRenderer` | `EntityOptions` | Static or animated | Parses Bedrock `.geo.json` entity models |
| `PlayerRenderer` | `PlayerOptions` | Static | Player skins with armor and held items |
| `FluidRenderer` | `FluidOptions` | Static or animated | Water, lava, biome variants, still + flowing |
| `PortalRenderer` | `PortalOptions` | Static or animated | End portal / end gateway, layered shader effect |
| `TextRenderer` | `TextOptions` | Static | SkyBlock-style tooltips, lore, stack counts |
| `AtlasRenderer` | `AtlasOptions` | Static + sidecar JSON | Full pack dump into a tile grid |
| `GridRenderer` | `GridOptions` | Static | Arbitrary child layout into a grid |
| `LayoutRenderer` | `LayoutOptions` | Static | Freeform placement of child renders |
| `MenuRenderer` | `MenuOptions` | Static | Container UIs (chest, furnace, etc.) |

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

Each task writes into `cache/<task-name>/` for side-by-side comparison. Flags use Gradle's `-P` property syntax.

```bash
./gradlew testRender       -PblockId=minecraft:tnt -PrenderSize=512 -Pssaa=2
./gradlew testRenderItem   -PitemId=minecraft:diamond_sword -PrenderSize=256
./gradlew testBed          -PrenderSize=1024
./gradlew testLore
./gradlew testStackCount   -Plabel=experiment1
./gradlew testStackCount   -Pdiff=experiment1,experiment2
./gradlew testFluid
./gradlew testPortal
```

### Asset Regeneration

These tasks rewrite the bundled JSON snapshots in `src/main/resources/lib/minecraft/renderer/`. Re-run on a Minecraft version bump and commit the updated JSON.

| Task | Output | Source |
|------|--------|--------|
| `atlas` | `build/atlas/atlas.png` + `atlas.json` | All blocks + items |
| `diagnoseAtlas` | `build/atlas/missing.json` | Blank-tile scan over the generated atlas |
| `blockTints` | `block_tints.json` | ASM scan of `net.minecraft.client.color.block.BlockColors` |
| `potionColors` | `potion_colors.json` | ASM scan of `net.minecraft.world.effect.MobEffects` |
| `blockEntities` | `block_entities.json` | ASM scan of block-entity model classes |
| `entityModels` | `entity_models.json` | Bedrock resource-pack `.geo.json` files |
| `colorMaps` | `color_maps.json` | Vanilla biome colormap PNGs |

> [!NOTE]
> `blockTints` and `potionColors` depend on a cached client JAR. They fetch it automatically on first run through `ClientJarDownloader`, then reuse `cache/clients/<version>/`.

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
│   │   ├── Renderer.java                # Root contract: Renderer<O> -> ImageData
│   │   ├── AtlasRenderer.java
│   │   ├── BlockRenderer.java
│   │   ├── EntityRenderer.java
│   │   ├── FluidRenderer.java
│   │   ├── GridRenderer.java
│   │   ├── ItemRenderer.java
│   │   ├── LayoutRenderer.java
│   │   ├── MenuRenderer.java
│   │   ├── PlayerRenderer.java
│   │   ├── PortalRenderer.java
│   │   ├── TextRenderer.java
│   │   ├── asset/          # Immutable domain: Block, Item, Entity, BlockTag
│   │   │   ├── binding/    # ArmorMaterial, BannerLayer, DyeColor, ...
│   │   │   ├── model/      # BlockModelData, EntityModelData, ModelElement, ...
│   │   │   └── pack/       # Texture, TexturePack, AnimationData, ColorMap
│   │   ├── engine/         # IsometricEngine, RasterEngine, ModelEngine, TextureEngine
│   │   ├── exception/      # RendererException, HttpFetchException, AssetPipelineException
│   │   ├── geometry/       # Biome, BlockFace, Box, ProjectionMath, ...
│   │   ├── kit/            # AnimationKit, BannerKit, GeometryKit, GlintKit, ItemStackKit, ...
│   │   ├── options/        # BlockOptions, ItemOptions, EntityOptions, ... (records)
│   │   ├── pipeline/       # AssetPipeline, client JAR download, resource-pack loaders
│   │   │   ├── client/     # ClientJarDownloader, ClientJarExtractor, HttpFetcher
│   │   │   ├── loader/     # BlockStateLoader, CitLoader, CtmLoader, ModelResolver, ...
│   │   │   └── pack/       # CitMatcher, CtmMatcher, ColorProperties, NbtCondition, ...
│   │   ├── tensor/         # FloatVector-backed Matrix4fOps, Vector3fOps
│   │   └── tooling/        # Tooling* Gradle entry points + ASM scanners + parity tests
│   ├── main/resources/lib/minecraft/renderer/    # Bundled JSON snapshots
│   ├── test/java/          # JUnit 5 tests (fast + @Tag("slow"))
│   └── jmh/java/lib/minecraft/renderer/bench/    # JMH benchmarks
├── build.gradle.kts
├── settings.gradle.kts
├── gradle/libs.versions.toml
├── LICENSE.md
├── COPYRIGHT.md
├── CONTRIBUTING.md
└── CLAUDE.md
```

## Bundled Resources

| File | Purpose |
|------|---------|
| `block_entities.json` | Block-entity model metadata (chest, sign, bed, etc.) |
| `block_entities_overrides.json` | Hand-curated fixes on top of the ASM scan |
| `block_tints.json` | Block-colour tint hooks extracted from `BlockColors` |
| `color_maps.json` | Grass/foliage/water biome tint maps |
| `entity_models.json` | Bedrock-derived entity geometry |
| `potion_colors.json` | Vanilla `MobEffects` colour values |

> [!NOTE]
> These are checked in so the library builds without network access. Regenerate them with the [asset-regeneration tasks](#asset-regeneration) after a Minecraft version bump.

### Runtime Directories

Created during execution and excluded from version control:

| Directory | Contents |
|-----------|----------|
| `cache/` | Client JARs, extracted assets, test-render output |
| `texturepacks/` | User-supplied overlay packs discovered by `TexturePackLoader` |
| `build/` | Gradle outputs and `atlas` task products |

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for development setup, code style guidelines, and how to submit a pull request.

## License

This project is licensed under the **Apache License 2.0** - see [LICENSE](LICENSE.md) for the full text.

See [COPYRIGHT.md](COPYRIGHT.md) for third-party attribution notices, including information about Mojang AB's copyrighted assets and upstream library licensing.
