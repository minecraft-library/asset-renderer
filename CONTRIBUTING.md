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
  - [What gets reviewed](#what-gets-reviewed)
- [Reporting Issues](#reporting-issues)
- [Project Architecture](#project-architecture)
  - [Pipeline flow](#pipeline-flow)
  - [Regenerating bundled JSON](#regenerating-bundled-json)
- [Legal](#legal)

## Getting Started

### Prerequisites

| Requirement | Version | Notes |
|-------------|---------|-------|
| JDK | **21+** | Required. Must support `--add-modules=jdk.incubator.vector` |
| Gradle | 9.4.1 | Wrapper is bundled (`./gradlew`) |
| Git | 2.x+ | For cloning and contributing |
| IDE | Any | IntelliJ IDEA is the recommended editor |

> [!IMPORTANT]
> The Vector API (`jdk.incubator.vector`) is an **incubator** module. `FloatVector` math in `lib.minecraft.renderer.tensor.SimdOps`, which `Vector3f` and `Matrix4f` dispatch to behind the `SimdSupport` probe, powers `ModelEngine`'s Pass 1. Missing `--add-modules=jdk.incubator.vector` on a JVM launch is a SILENT fall back to the scalar path - `SimdSupport` probes with a non-initialising `Class.forName` inside `catch (Throwable)` and caches the answer. It is a hard failure on `compileJava` and `javadoc`, which read `SimdOps`'s incubator imports directly.

### Development Setup

1. **Fork and clone the repository**

   [Fork the repository](https://github.com/minecraft-library/asset-renderer/fork), then clone your fork:

   ```bash
   git clone https://github.com/<your-username>/asset-renderer.git
   cd asset-renderer
   ```

2. **Verify the JDK toolchain**

   The build declares a Java 21 toolchain and no auto-provisioning resolver, so Gradle resolves it against a JDK 21 already installed on the machine. Confirm with:

   ```bash
   ./gradlew --version
   ```

3. **Run the build**

   ```bash
   ./gradlew build
   ```

   This compiles the main sources, runs the fast test suite (excluding `@Tag("slow")`), assembles the jar, and runs the four gates `check` adds on top of `test` - `paritySelfTest`, `harnessClasses`, `toolingTest` and `parityReachCheck`.

   The fast suite reads the extracted client, so on a fresh clone run step 4 FIRST - or expect
   `ClientExtractionGuardTest` to fail and name the command that writes one.

4. **Run the slow integration suite (optional)**

   ```bash
   ./gradlew slowTest
   ```

   > [!NOTE]
   > `slowTest` selects `@Tag("slow")`: the client-jar acquisition tests, the texture-pack acquisition tests and the harness reference-key round-trip. It downloads the Minecraft client JAR and extracts resource assets. Results are cached under `cache/` and reused on subsequent runs.

### IntelliJ IDEA

1. Open the project root (the directory containing `settings.gradle.kts`). IntelliJ auto-imports the Gradle build.
2. Ensure the **Project SDK** under **File > Project Structure** is set to a JDK 21 installation.
3. The build script excludes `cache/` and `texturepacks/` from the IDE module, so indexing stays fast even after the client JAR is downloaded.
4. For per-task runs, open the **Gradle** tool window. The `tooling` group holds resource-regenerators (`blockDefaults`, `blockTints`, etc.); the `visual` group holds the renderer diagnostics (`blockRender3D`, `fluidRenderer`, `portalRenderer`, etc.); the `build` group holds `generateAtlas`, the worked example that drives `AtlasRenderer` over the texture pack into `build/atlas/`.

## Making Changes

### Branching Strategy

- Create a feature branch from `master` for your work.
- Use a descriptive branch name: `fix/fluid-animation-desync`, `feat/banner-cit-support`, `docs/jmh-profilers`.

```bash
git checkout -b feat/my-feature master
```

### Code Style

The repository uses [Simplified Annotations](https://github.com/simplified-dev/annotations) for boilerplate reduction and enforces a consistent Javadoc, exception, and control-flow style.

#### Accessor naming

`@Getter` and `@Setter` default to `style = NamingStyle.SIMPLIFIED`, which mints bean-shaped `getX()` / `isX()` / `setX(..)`. A field or type whose accessors read `x()` must say so - write `@Getter(style = NamingStyle.FLUENT)` at that site and import `dev.simplified.annotations.NamingStyle`. Nothing infers the shape from the call sites: a bare `@Getter` over a fluent site mints `getX()` and every `x()` caller stops resolving.

#### Javadoc

- **Punctuation** - Single hyphens ` - ` only as separators. Never em dashes, `&mdash;`, or `--`.
- **Voice** - Class/interface = noun phrase. Method = third-person singular verb ("Returns the..."). Field = sentence fragment, no tags.
- **Tags** - Always include `@param`, `@return`, `@throws` where applicable. Lowercase sentence fragments, no trailing period. Single space after the parameter name - never column-align.
- **Cross-references** - Use `{@link}` / `{@linkplain}` / `@see`. Use `{@code}` for inline code. Import link targets so they render with short names.
- **Overrides** - Use `/** {@inheritDoc} */` for methods that override library/framework types. Do not rewrite the parent doc.
- **Field getters** - Field-like interface methods (no params, non-void return) use a noun-phrase fragment without `@return` and without "Gets"/"Returns". `@Getter` implementations carry their doc on the field, not a separate method Javadoc block.
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

- **Slow integration suite** - required when your change touches asset loading, client-JAR extraction, or the pack stack:

  ```bash
  ./gradlew slowTest
  ```

- **Visual inspection** - required when your change touches a renderer, kit, or engine. Run the relevant task from the `visual` group (`blockRender3D`, `itemRender2D`, `loreTooltip`, `stackCountBadge`, `entityRender3D`, `fluidRenderer`, `portalRenderer`) and diff that task's own `cache/visual/` sub-tree (`blockRender3D` writes `cache/visual/block-render-3d/`) against `master` before and after:

  ```bash
  ./gradlew blockRender3D -PblockId=minecraft:tnt -PrenderSize=512
  ./gradlew stackCountBadge -Pdiff=before,after
  ```

- **JMH benchmarks** - required when your change touches hot paths in `ModelEngine`, the `engine.raster` math, `FluidRenderer`, `PortalRenderer`, or the `tensor` package. Run the relevant benchmark before and after and include both results in the PR description:

  ```bash
  ./gradlew jmh -PjmhInclude=ModelRasterizeMicroBenchmark -PjmhProfilers=gc
  ```

> [!TIP]
> Tag a test `@Tag("slow")` when it can reach the NETWORK - `ClientAcquisition.acquire` or `downloadJarToCache`, whether called directly or through `ClientAssetsExtension.assets()` / `.context()` without a gate. Reading the extracted client out of `cache/` is not slow and belongs in the fast suite: install `@ExtendWith(ClientAssetsExtension.class)`, which resolves the assets at production's own cache root and abandons the class where nothing has extracted them.
>
> `SlowTagRuleTest` holds that rule against the sources, so an untagged test that can download fails rather than costing every later run a client-jar download.

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

- **Correctness** of renderer output against the vanilla client. Regressions in `blockRender3D`, `fluidRenderer`, `portalRenderer`, or atlas diagnostics block a merge.
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
- **Steps to reproduce** - ideally a minimal Gradle invocation (`./gradlew blockRender3D -PblockId=...`) or a code snippet

## Project Architecture

A brief overview to help you find your way around the codebase:

```
lib.minecraft.renderer/
├── Renderer.java          # Root contract: Renderer<O> -> ImageData
├── <Name>Renderer.java    # One top-level renderer per subject
├── asset/                 # Immutable domain (Block, Item, Entity, textures, models)
├── client/                # ClientAcquisition: Mojang HTTP, client-jar download and extract
├── engine/                # ModelEngine + camera/ compose/ kit/ light/ raster/ texture/ subsystems
├── exception/             # RendererException + specializations
├── face/                  # Face identity, UV unwrap, corner phase, humanoid parts
├── option/                # Immutable options classes with generated builders, one per renderer
├── pipeline/              # Pack stack assembly
│   ├── PipelineRendererContext.java # Cached pack / model / texture view every renderer reads
│   ├── loader/            # Assemblers over the shipped tables (block defaults, tints, entity models, potion colours)
│   └── pack/              # Pack acquisition and per-asset loaders (blockstates, item model trees, CIT, CTM)
├── pose/                  # Pose vocabulary plus author/ compile/ audit/ install
└── tensor/                # Matrix4f, Vector3f and the FloatVector SimdOps path behind them
```

The generators are the `:tooling` subproject at `tooling/` - the `Tooling*` flow entry points and the ASM walkers behind them.

### Pipeline flow

```
ClientAcquisition.acquire(clientOptions)
  -> ClientAcquisition.downloadJarToCache(options)   # MojangContract via simplified-api/mojang
  -> ClientAcquisition.extractClientJar(jarPath, packRoot)
  -> PackAcquisition over the user packs -> PackStack
  -> BlockStateLoader / ItemModelTreeLoader / EntityModelLoader / ...
  -> PipelineRendererContext
  -> Renderer<O>.render(options) -> ImageData
```

`PipelineRendererContext` is the thread-safe, cached view that every top-level renderer consumes. Renderers are stateless between calls; all input flows through the options object.

### Regenerating bundled JSON

The files under `src/main/resources/lib/minecraft/renderer/` are checked in so the library builds without network access. After a Minecraft version bump, regenerate them:

```bash
./gradlew entityModels blockModels blockDefaults blockItems blockTints potionColors glintItems colorMaps
```

Commit the updated JSON as part of the version-bump PR.

## Legal

By submitting a pull request, you agree that your contributions are licensed under the [Apache License 2.0](LICENSE.md), the same license that covers this project.

This project processes copyrighted assets owned by Mojang AB at runtime. **Do not commit any Minecraft assets** (textures, JARs, JSON files extracted from the client, resource-pack archives) to the repository. The `.gitignore` excludes `cache/` and `texturepacks/` to make this harder to do accidentally - leave those entries in place.
