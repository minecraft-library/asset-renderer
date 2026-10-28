# Pack content

Load when touching `content/read` - `PackSubtree`, a loader walking a pack subtree, `pack.mcmeta`'s
`filter.block` - the value adapters under `content/json`, or a pack's text shader - `TextPaletteLoader`,
`TextPalette`. Indexed from [`CLAUDE.md`](../../CLAUDE.md).

## The pack filter

`pack.mcmeta`'s `filter.block` is the only place a resource pack can **remove** something a lower
pack shipped.

- The pattern tests the **file path**, subdirectory and extension included, because
  `PathPackResources` relativises against `<pack>/assets/<ns>`. An id-keyed loader has no path to
  offer, so the erase lives at the file listing rather than the predicate.
- Namespace and path are answered **independently**, each an `anyMatch` over the whole list, and an
  absent pattern is constant-true: `[{"namespace":"x"},{"path":"y"}]` hides every file below.
  `IdentifierPattern.locationPredicate` is the per-entry conjunction, never called here.
- The client installs the filter once per pack at the resource manager, blind to what is listed, so
  it reaches every subtree and no loader is exempt. One `PackStack` serves both roots here where
  vanilla keeps two.
- `content/read/PackSubtree` is the one `(pack x root x namespace)` walk and the one filter site,
  yielding **files** in resolution order rather than a winner per id, because its callers disagree
  about what winning means. Do not simplify it to a winner map - that deletes `BlockStateLoader`'s
  malformed-file fallback.
- The walk takes its **root** as a value, as `PackType` does, so `data/` registries walk the same
  code. A `data/` namespace is **named, not enumerated**, because `PackAcquisition.namespaces`
  discovers namespaces from `assets/` alone and reusing that set is wrong in both directions.

Subtrees go through the walk; **point reads stay hand-written**, because a named file has nothing to
enumerate. `BlockRendererOverrides` is exempt by API - three fixed pack-root paths through
`PackContainer.bytes`, zero `entries()` calls, unreachable by a per-namespace `filter.block`.
`BlockEntityShadows.report` runs the enumeration backwards, probing `exists()` for a
supplied id set. Extract a diagnostic when two callers need it, not one.

## A pack's text palette

A pack can recolour text through its text shader: a switch over every text vertex colour that draws
some colours as others. `TextPaletteLoader` reads that switch into the `TextPalette` the stack holds
(`PackStack.textPalette`, answered by `RendererContext.findTextPalette`), at acquisition, once per
stack.

- **Which file.** The 26.1 program, `minecraft:shaders/core/rendertype_text.vsh`, where its winning
  copy is a pack's above vanilla; otherwise `minecraft:shaders/core/text.vsh`, the program a newer
  client runs, where a pack above vanilla ships it; otherwise none, and the palette is absent. The
  winner is `PackSubtree`'s walk over every `.vsh`, `.fsh` and `.glsl` under `shaders/`, so the highest
  pack, its overlays and `filter.block` decide it as the client's resource manager does. The newer name
  is what makes a pack built for a newer client read in its own colours; where a pack ships the 26.1
  name, the 26.1 program is the one read.
- **Which shape.** Exactly one. Imports are followed as the 26.1 preprocessor follows them: `<ns:path>`
  from `<ns>:shaders/include/`, `"path"` from the program's own directory, each file once and a repeat
  importing nothing, and an import no pack ships refuses. `main` is vanilla's with one statement added,
  `vec4 V = F(Color)`, outside every preprocessor condition and ahead of anything naming `V`, and every
  `vertexColor` it writes reads `V`. `F` switches over `SEL(P.rgb)` with each case returning
  `H(0xRRGGBB, P.a)` and the default the colour itself; `SEL` packs the red, green and blue into one
  24-bit key; each `H` rebuilds the colour from its argument's channels over 255, or from those channels
  each integer-divided by 4 first - the shadow form. The helpers are told apart by their bodies, never
  their names, so a helper dividing by 3 is refused rather than read as one dividing by 4. A pack
  shipping the program's fragment shader, or any directive but `#version` and the conditionals, is
  another shape. A pack's copy of vanilla's own program reads as a table of no swaps.
- **Another shape is reported, never guessed at.** One line on standard error names the pack, the
  file and the first rule the shape broke, and the palette is empty - distinct from absent - so every
  text draw keeps the colours it is given.
- **Derive, then swap.** The client computes a glyph's shadow vertex colour before its shader runs and
  the shader swaps each vertex after, so `TextKit` derives the shadow from the unswapped text colour and
  swaps the text colour and the shadow colour each as a colour of its own. Swapping first and shadowing
  the swap agrees with the client only where a pack's shadow keys are its quartered swaps. The
  strikethrough and underline bars take their pass's swapped colour, being text vertices of that pass.
  A swap keeps every colour's top byte, so it moves no pixel's place or presence.
- **Every text draw takes it.** The client draws a tooltip, a menu label, a field's text and a stack
  count through one text program, so `TextRenderer`, `MenuRenderer`, `TextField` and `ItemRenderer` each
  hand their context's palette to the kits, and no kit supplies one on a caller's behalf. A renderer
  holding no context draws through `TextPalette.VANILLA`, which swaps nothing.
- **What is no text keeps its colour.** The bar a full text field fills beside its text, and the chrome
  every screen and tooltip paints, are no text vertex.
- **Gradients swap per colour laid down.** A per-letter gradient swaps each letter's colour and its
  shadow's; a per-pixel one swaps each band's colour and its shadow's, the shadow darkened from the
  unswapped band. A gradient has no client twin, so this is the reading that agrees with the client
  wherever one exists.

## Decisions that stay closed

- Do not give the value adapters - `Vector2fAdapter`, `Vector3fAdapter`, `Vector4fAdapter`,
  `EulerRotationAdapter`, `TextureSizeAdapter`, `ModelTextureAdapter` and `ResourceIdAdapter` - or
  `CubeGrowFactory` a declaration that plans the renders their decoded values feed. The contributor
  that constructs them is loaded from a service file, so the reference graph reaches none of them
  from a producer, and a change to one plans the two pipeline dumps and nothing else. That pair is
  the evidence such a change owes: the dump serialises every value they decode, and a
  byte-identical pair is byte-identical render input while no render code has changed. A decode
  change meant to move a value moves a dump row, and the parity gate's decision table says what
  that mover owes before it is registered.
- Do not fall through to a lower pack's text shader when the winning one is refused - the client runs
  the winning one.
- Do not parse vanilla's own text program for a table - it swaps nothing, and a stack whose packs ship
  no text shader answers absent.
