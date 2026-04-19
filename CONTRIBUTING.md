# Contributing to Minecraft Asset Renderer

Thank you for your interest in contributing! This document explains how to get started, what to expect during the review process, and the conventions this project follows.

## Table of Contents

- [Getting Started](#getting-started)
  - [Prerequisites](#prerequisites)
  - [Development Setup](#development-setup)
  - [IntelliJ IDEA](#intellij-idea)
- [Making Changes](#making-changes)
  - [Branching Strategy](#branching-strategy)
  - [Code Style](#code-style)
  - [Commit Messages](#commit-messages)
  - [Validating Output](#validating-output)
- [Submitting a Pull Request](#submitting-a-pull-request)
- [Reporting Issues](#reporting-issues)
- [Project Architecture](#project-architecture)
- [Legal](#legal)

## Getting Started

### Prerequisites

| Requirement | Version | Notes |
|-------------|---------|-------|
| JDK | **21+** | Required. Must support `--add-modules=jdk.incubator.vector` |
| Gradle | 8.x | Wrapper is bundled (`./gradlew`) |
| Git | 2.x+ | For cloning and contributing |
| IDE | Any | IntelliJ IDEA is the recommended editor |

> [!IMPORTANT]
> The Vector API (`jdk.incubator.vector`) is an **incubator** module. `FloatVector` math in `lib.minecraft.renderer.tensor.Vector3fOps` / `Matrix4fOps` powers `ModelEngine`'s Pass 1 and `PortalRenderer`'s inner loop. Missing `--add-modules=jdk.incubator.vector` on compile, test, or JavaExec produces a clean `ClassNotFoundException` at load - not silent fallback.

### Development Setup

1. **Fork and clone the repository**

   [Fork the repository](https://github.com/minecraft-library/asset-renderer/fork), then clone your fork:

   ```bash
   git clone https://github.com/<your-username>/asset-renderer.git
   cd asset-renderer
   ```

2. **Verify the JDK toolchain**

   Gradle's Java toolchain feature will download JDK 21 automatically if needed. Confirm with:

   ```bash
   ./gradlew --version
   ```

3. **Run the build**

   ```bash
   ./gradlew build
   ```

   This compiles the main sources, runs the fast test suite (excluding `@Tag("slow")`), and assembles the jar.

4. **Run the slow integration suite (optional)**

   ```bash
   ./gradlew slowTest
   ```

   > [!NOTE]
   > `slowTest` downloads the Minecraft client JAR, extracts resource assets, and runs parity tests against the extracted class bytecode. Allow several minutes on first run. Results are cached under `cache/` and reused on subsequent runs.

### IntelliJ IDEA

1. Open the project root (the directory containing `settings.gradle.kts`). IntelliJ auto-imports the Gradle build.
2. Ensure the **Project SDK** under **File > Project Structure** is set to a JDK 21 installation.
3. The build script excludes `cache/` and `texturepacks/` from the IDE module, so indexing stays fast even after the client JAR is downloaded.
4. For per-task runs, open the **Gradle** tool window and expand the `tooling` group to see `atlas`, `testRender`, `testFluid`, etc.

## Making Changes

### Branching Strategy

- Create a feature branch from `master` for your work.
- Use a descriptive branch name: `fix/fluid-animation-desync`, `feat/banner-cit-support`, `docs/jmh-profilers`.

```bash
git checkout -b feat/my-feature master
```

### Code Style

The repository uses Lombok for boilerplate reduction and enforces a consistent Javadoc, exception, and control-flow style.

#### Javadoc

- **Punctuation** - Single hyphens ` - ` only as separators. Never em dashes, `&mdash;`, or `--`.
- **Voice** - Class/interface = noun phrase. Method = third-person singular verb ("Returns the..."). Field = sentence fragment, no tags.
- **Tags** - Always include `@param`, `@return`, `@throws` where applicable. Lowercase sentence fragments, no trailing period. Single space after the parameter name - never column-align.
- **Cross-references** - Use `{@link}` / `{@linkplain}` / `@see`. Use `{@code}` for inline code. Import link targets so they render with short names.
- **Overrides** - Use `/** {@inheritDoc} */` for methods that override library/framework types. Do not rewrite the parent doc.
- **Field getters** - Field-like interface methods (no params, non-void return) use a noun-phrase fragment without `@return` and without "Gets"/"Returns". Lombok `@Getter` implementations carry their doc on the field, not a separate method Javadoc block.
- **Structure** - `<p>` on its own line between paragraphs; `<ul>` / `<li>` for lists; `<b>` for emphasis inside list items.
- **Forbidden tags** - Never use `@author` or `@since`.

#### Control flow

Omit braces on single-line bodies; use braces when the body wraps across multiple lines. Applies to all single-statement forms (`if`, `for`, `while`, `do`, lambda bodies).

```java
if (options.isAnimated()) return renderAnimated(options);

for (Block block : pack.blocks()) {
    if (block.isFluid())
        continue;
    render(block, options);
}
```

#### Exception classes

All project exceptions follow a **five-constructor pattern** in this order:

1. `(Throwable cause)`
2. `(String message)`
3. `(Throwable cause, String message)`
4. `(@PrintFormat String message, Object... args)`
5. `(Throwable cause, @PrintFormat String message, Object... args)`

Root exceptions (extending `RuntimeException`) reverse the `super()` parameter order:

```java
super(message, cause);
super(String.format(message, args), cause);
```

Child exceptions pass through to the parent, which handles the reversal:

```java
super(cause, message);
super(cause, message, args);
```

Message conventions:

- No trailing punctuation.
- Start with an uppercase letter.
- Use `'%s'` for interpolated values in format strings.

Annotations:

- `@NotNull` on `Throwable cause` and `String message` parameters.
- `@PrintFormat` on format string parameters (from `org.intellij.lang.annotations`).
- `@Nullable` on `Object... args` parameters.

Javadoc:

- **Class-level** - "Thrown when [condition]." Never use the words "unchecked" or "exception" in the description.
- **Constructor** - "Constructs a new {@code ClassName} with [description]."
- **`@param` tags** - lowercase, no trailing period.

See [`exception/RendererException.java`](src/main/java/lib/minecraft/renderer/exception/RendererException.java) for the canonical root-exception template.

### Commit Messages

Write clear, concise commit messages that describe *what* changed and *why*.

```
Dispatch water/lava through FluidRenderer in AtlasRenderer

Vanilla block/water.json and block/lava.json only define a particle
texture - the standard block-model path produced blank tiles. Route
these ids to FluidRenderer.FluidFace2D so each fluid emits a flat
still-texture icon in the atlas.
```

- Use the imperative mood ("Add", "Fix", "Update", not "Added", "Fixes").
- Keep the subject line under 72 characters.
- Add a body when the *why* isn't obvious from the subject.

### Validating Output

- **Fast test suite**

  ```bash
  ./gradlew test
  ```

- **Slow integration suite** - required when your change touches asset loading, client-JAR extraction, the pack stack, or the ASM scanners under `tooling/`:

  ```bash
  ./gradlew slowTest
  ```

- **Visual inspection** - required when your change touches a renderer, kit, or engine. Run the relevant `testRender*`, `testFluid`, or `testPortal` task and diff the output against `master` before and after:

  ```bash
  ./gradlew testRender -PblockId=minecraft:tnt -PrenderSize=512
  ./gradlew testStackCount -Pdiff=before,after
  ```

- **JMH benchmarks** - required when your change touches hot paths in `ModelEngine`, `RasterEngine`, `FluidRenderer`, `PortalRenderer`, or the `tensor` package. Run the relevant benchmark before and after and include both results in the PR description:

  ```bash
  ./gradlew jmh -PjmhInclude=ModelRasterizeMicroBenchmark -PjmhProfilers=gc
  ```

> [!TIP]
> Tag slow tests with `@Tag("slow")`. Any test that downloads a client JAR, reads from `cache/`, or walks an extracted resource pack belongs in the slow suite.

## Submitting a Pull Request

1. **Push your branch** to your fork.

   ```bash
   git push origin feat/my-feature
   ```

2. **Open a Pull Request** against the `master` branch of [minecraft-library/asset-renderer](https://github.com/minecraft-library/asset-renderer).

3. **In the PR description**, include:
   - A summary of the changes and the motivation behind them.
   - The Minecraft version(s) you tested against.
   - Sample rendered output or JMH before/after numbers if the change is visual or performance-sensitive.
   - Any `slowTest` failures you knowingly accepted, with justification.

4. **Respond to review feedback.** PRs may go through one or more rounds of review before being merged.

### What gets reviewed

- **Correctness** of renderer output against the vanilla client. Regressions in `testRender`, `testFluid`, `testPortal`, or atlas diagnostics block a merge.
- **Performance** of changes in hot paths (model rasterization, fluid animation, atlas bake). JMH regressions require discussion.
- **Resource loading** behaviour. CIT, CTM, and overlay-pack semantics must stay faithful to the vanilla + OptiFine conventions they emulate.
- **Javadoc and exception style** as documented above. Inconsistent style will be flagged.

## Reporting Issues

Use [GitHub Issues](https://github.com/minecraft-library/asset-renderer/issues) to report bugs or request features.

When reporting a bug, include:

- **JDK version** (`java -version`)
- **Operating system**
- **Minecraft version** you targeted
- **Resource packs** active in the pack stack (if any)
- **Block / item / entity ID** that reproduces the issue
- **Full stack trace** (if applicable)
- **Expected vs. actual rendered output** - attach PNGs where helpful
- **Steps to reproduce** - ideally a minimal Gradle invocation (`./gradlew testRender -PblockId=...`) or a code snippet

## Project Architecture

A brief overview to help you find your way around the codebase:

```
lib.minecraft.renderer/
├── Renderer.java          # Root contract: Renderer<O> -> ImageData
├── <Name>Renderer.java    # One top-level renderer per subject
├── asset/                 # Immutable domain (Block, Item, Entity, textures, models)
├── engine/                # IsometricEngine, RasterEngine, ModelEngine, TextureEngine
├── exception/             # RendererException + specializations
├── geometry/              # Projection math, boxes, faces, biome tint logic
├── kit/                   # Reusable drawing helpers (glint, banners, stack counts, ...)
├── options/               # Immutable options records, one per renderer
├── pipeline/              # Pack stack assembly
│   ├── client/            # ClientJarDownloader, ClientJarExtractor, HttpFetcher
│   ├── loader/            # One loader per asset type (blockstates, CIT, CTM, ...)
│   └── pack/              # Match rules that loaders feed back into options
├── tensor/                # FloatVector-backed Matrix4fOps, Vector3fOps
└── tooling/               # Tooling* Gradle entry points, ASM scanners, parity tests
```

### Pipeline flow

```
AssetPipeline.builder()
  -> ClientJarDownloader.fetch(version)
  -> ClientJarExtractor.extract()
  -> TexturePackLoader.stack(user packs)
  -> BlockStateLoader / ItemDefinitionLoader / EntityModelLoader / ...
  -> PipelineRendererContext
  -> Renderer<O>.render(options) -> ImageData
```

`PipelineRendererContext` is the thread-safe, cached view that every top-level renderer consumes. Renderers are stateless between calls; all input flows through the options record.

### Regenerating bundled JSON

The files under `src/main/resources/lib/minecraft/renderer/` are checked in so the library builds without network access. After a Minecraft version bump, regenerate them:

```bash
./gradlew blockTints potionColors blockEntities entityModels colorMaps
```

Commit the updated JSON as part of the version-bump PR.

## Legal

By submitting a pull request, you agree that your contributions are licensed under the [Apache License 2.0](LICENSE.md), the same license that covers this project.

This project processes copyrighted assets owned by Mojang AB at runtime. **Do not commit any Minecraft assets** (textures, JARs, JSON files extracted from the client, resource-pack archives) to the repository. The `.gitignore` excludes `cache/` and `texturepacks/` to make this harder to do accidentally - leave those entries in place.
