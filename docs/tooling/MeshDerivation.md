# Mesh derivation and geometry keys

Load when touching `EntityMeshOverlays`, `GeometryIds`, `EntityOverlayResolver`'s `retain_bones`
subset, a geometry key's suffixes, or `GeometryManifest.register`. Indexed from
[`tooling/CLAUDE.md`](../../tooling/CLAUDE.md).

**A mesh is derived at generation and named, never described for the reader to build.** The three
overlay surgeries - the `retainExactParts` subset, the `CubeDeformation` inflate and the
`clearChild().clearRecursively()` a suppressed pass draws - are `EntityMeshOverlays`, which mints
`@retain=` / `@inflate=` / `@cleared=` through `GeometryIds`. An inflate is NOT the request's
`@grow=`: that pre-seeds the factory's default deformation, which a cube deformed inline overrides,
where an inflate adds to whatever a cube ended up carrying. The equine's `body` is deformed inline at
0.05 and the request's `@grow=0.1` leaves it there, where an inflate of the same amount would have
taken it to 0.15; the baby zombie's `head` is built with an explicit zero and stays absent under
`@grow=0.25` while its `body` takes the seed. So the two never share a spelling. The drowned is not a
witness either way - its `hat` reaches `HumanoidModel.createMesh` through `param.extend(0.5f)`, a
relative extension that composes additively, so its pass's 0.25 taking it to 0.75 is what an inflate
would have done too.

**A mesh every site derives the same way is derived where it stands; one the body also draws mints a
key.** Deriving a shared mesh in place would deform the body along with the pass, and minting for a
mesh nothing else draws would leave an orphan `GeometryRefClosureTest` refuses. The same rule decides
both the marking's splits and the overlays'.

**A provider layer drawing a subset of its body draws the subset vanilla registers, at any alpha.**
`EntityOverlayResolver` registers the layer's own factory and carries its `retainExactParts` set as
`retain_bones`, so the warden's bioluminescent pass, drawn at a constant one, draws
`WardenModel#createBioluminescentLayer` - the parts vanilla empties emptied and the levels vanilla
copies copied - as its spots and heart passes draw theirs. A subset layer whose retain set the walk
cannot read drops its pass rather than drawing the whole body.

**A geometry key is not a complete identity, and `GeometryManifest` is what makes that safe.** Two
request members the emitted mesh IS a function of are spelled in no key: the texture-size override,
which `GeometryFlow.parse` stamps `texture_size` from; and the float-parameter table, whose mere
presence selects the parser's `EVALUATING` walk where `@fparam=` encodes only a non-zero slot 0 -
so a bound all-zero table and an absent one mint one key and walk differently. `@iparam=` faced the
same choice and took the other one, encoding `0:0` for a bound-but-all-zero table.

Rather than widen the grammar for a distinction no shipped key needs, `register` refuses: a key
already held by a request differing in ANY member the mesh is a function of raises, naming the
member. So the gap cannot become a wrong mesh - it becomes a stopped generation. Two things that
guard depends on:

- **The comparison is by VALUE and by exclusion.** Three components are arrays and `PoseParam` holds
  a fourth, which a record compares by reference - a generated `equals` would call every legitimate
  re-registration a difference and refuse the corpus, so `PoseParam` declares its own and the walk
  uses `Objects.deepEquals`. And the members that do NOT count are named (`subjectId` is provenance,
  `yAxis` reaches neither the parse nor the entry), so a component added to `GeometryRequest` is
  compared by default rather than falling outside the guard in silence.
- **It is quiet on the corpus today**, over all eight flows with every table byte-identical, so
  nothing is currently relying on the gap. If a version bump makes it fire, the answer is to encode
  the named member in the key - `@tex=<w>x<h>`, or `@fparam=` widened to `@iparam=`'s bound-table
  rule - which moves keys and owes a promote.
