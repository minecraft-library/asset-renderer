# Pack content

Load when touching `content/read` - `PackSubtree`, a loader walking a pack subtree, `pack.mcmeta`'s
`filter.block` - or the value adapters under `content/json`. Indexed from
[`CLAUDE.md`](../../CLAUDE.md).

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
