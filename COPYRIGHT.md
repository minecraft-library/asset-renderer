# Copyright Notice

## Project License

Copyright 2025 CraftedFury

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

> <http://www.apache.org/licenses/LICENSE-2.0>

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

## Third-Party Notices

### Minecraft Client Assets - Mojang AB

The block models, item models, entity models, textures, and animation metadata processed by this library are the copyrighted property of **Mojang AB** (a Microsoft subsidiary). These assets are downloaded at runtime from the official Piston API, extracted from the Minecraft client JAR, and are **not distributed** with this repository.

> "Minecraft" is a trademark of Mojang AB. This project is not affiliated with or endorsed by Mojang AB or Microsoft Corporation.

Users are responsible for ensuring their use of the rendered output complies with the [Minecraft End User License Agreement (EULA)](https://www.minecraft.net/en-us/eula) and [Minecraft Usage Guidelines](https://www.minecraft.net/en-us/usage-guidelines).

### Bedrock Edition Resource Pack - Mojang AB

The `entityModels` tooling task downloads the Bedrock Edition vanilla resource pack to extract `.geo.json` entity geometry. These assets are likewise copyrighted by Mojang AB and are **not distributed** with this repository. The generated [`entity_models.json`](src/main/resources/lib/minecraft/renderer/entity_models.json) snapshot contains only the derived geometry metadata needed at runtime, not the original art files.

### OptiFine CIT / CTM Conventions

The Custom Item Textures (CIT) and Connected Textures (CTM) format support implemented in `lib.minecraft.renderer.pipeline.pack` is a reimplementation of the matching rules popularized by the OptiFine mod. This project is not affiliated with or endorsed by OptiFine; no OptiFine source code is copied or redistributed.

## Upstream Libraries

| Library | License | Use |
|---------|---------|-----|
| [simplified-dev/collections](https://github.com/simplified-dev/collections) | Apache-2.0 | Concurrent collection utilities |
| [simplified-dev/utils](https://github.com/simplified-dev/utils) | Apache-2.0 | Shared helpers |
| [simplified-dev/image](https://github.com/simplified-dev/image) | Apache-2.0 | `ImageData`, `PixelBuffer`, PNG I/O |
| [simplified-dev/gson-extras](https://github.com/simplified-dev/gson-extras) | Apache-2.0 | Gson adapters and helpers |
| [simplified-dev/client](https://github.com/simplified-dev/client) | Apache-2.0 | HTTP client for Piston API downloads |
| [minecraft-library/text](https://github.com/minecraft-library/text) | Apache-2.0 | Font loading for `TextRenderer`; source of `RendererException` / `FontException` base classes |
| [ASM](https://asm.ow2.io/) | BSD-3-Clause | Client-JAR bytecode scanning in `tooling/` |
| [Gson](https://github.com/google/gson) | Apache-2.0 | JSON parsing for blockstates, models, and bundled snapshots |
| [Lombok](https://projectlombok.org/) | MIT | Compile-time boilerplate reduction |
| [JMH](https://github.com/openjdk/jmh) | GPL-2.0 with Classpath Exception | Benchmark harness (test-scope only) |
| [JUnit 5](https://junit.org/junit5/) | EPL-2.0 | Test framework |
| [Hamcrest](https://hamcrest.org/JavaHamcrest/) | BSD-3-Clause | Test matchers |

The Vector API (`jdk.incubator.vector`) used by `lib.minecraft.renderer.tensor` is part of the OpenJDK and is governed by the JDK's own license.

## Attribution

- **Project author** - [CraftedFury](https://sbs.dev/)
- **Organization** - [Minecraft Library](https://github.com/minecraft-library)
- **Sibling project** - [minecraft-library/font-generator](https://github.com/minecraft-library/font-generator) (font extraction pipeline consumed via `minecraft-library/text`)
