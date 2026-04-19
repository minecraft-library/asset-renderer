# asset-renderer

Headless renderer for Minecraft blocks/items/entities/fluids/portals. Outputs `ImageData` (static PNG or animated frames) via `Renderer<O>` impls. Group `lib.minecraft`, package root `lib.minecraft.renderer.**` (legacy `dev.sbs.renderer.**` still present in some test/JMH/tooling paths).

## Build
- JDK 21 + **Vector API incubator** (`--add-modules=jdk.incubator.vector`). Wired into JavaCompile/Test/JavaExec/JMH in `build.gradle.kts` - missing it anywhere = class-not-found at load, not silent fallback.
- Deps: Gradle Kotlin DSL, `libs.versions.toml`. Strictly-pinned jitpack snapshots from `simplified-dev` (`collections`, `utils`, `image`, `gson-extras`, `client`) and `minecraft-library` (`text`). Bump by editing the version string.
- ASM 9.8 (Java 25 class files) - `VanillaTintsLoader` parses `BlockColors` from the extracted client jar.

## Tests
- `./gradlew test` - fast tests (excludes `@Tag("slow")`).
- `./gradlew slowTest` - hits network / filesystem cache (client-jar downloads, integration, parallelism, block-entity parity). Not up-to-date-cached.

## Tooling (re-run on Minecraft version bump)
Rewrites JSON in `src/main/resources/lib/minecraft/renderer/`:
- `blockTints` -> `block_tints.json` (ASM scan of `BlockColors`)
- `potionColors` -> `potion_colors.json` (ASM scan of `MobEffects`)
- `blockEntities` -> `block_entities.json` (ASM scan of block-entity model classes)
- `entityModels` -> `entity_models.json` (Bedrock `.geo.json`)
- `colorMaps` -> `color_maps.json` (vanilla biome colormap PNGs)
- `atlas` / `diagnoseAtlas` / `diagnoseAtlasTask10` -> `build/atlas/`

## Visual inspection (writes to `cache/`)
`testRender -PblockId=minecraft:tnt -PrenderSize=512 -Pssaa=2`, `testRenderItem -PitemId=...`, `testBed`, `testLore`, `testStackCount [-Plabel= | -Pdiff=A,B]`, `testFluid`, `testPortal`.

## JMH
`./gradlew jmh` with `-PjmhWarmup` (3), `-PjmhIters` (5), `-PjmhForks` (2), `-PjmhInclude=<regex>`, `-PjmhProfilers=gc,stack`. JVM forks get `-Xmx2g` + Vector module. Benches in `src/jmh/java/lib/minecraft/renderer/bench/`.

## Skip these
- `cache/` - runtime texture-packs, test-render output, font-generator. Excluded from IDE module and git. Do not grep/scan.
- `texturepacks/` - same.
- `build/` - Gradle output.
- Fonts live in sibling `minecraft-text` repo now; `./gradlew fonts` is gone from here.
