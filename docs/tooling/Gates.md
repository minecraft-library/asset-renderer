# Tooling gates

Load when a pin that holds a table to the jar fails after a version bump. The suite's shape, and
diffing a flow's bytes member by member, are in [`tooling/CLAUDE.md`](../../tooling/CLAUDE.md).

**Five of the nine jar walks [tooling/CLAUDE.md](../../tooling/CLAUDE.md#gates) lists are the only pins holding the geometry table to the jar.**
`GeometryParserTest` value-matches shipped entries with floats exact, and `PosePartIndexTest` and
`PoseWalkTest` hold class rosters the table's coordinates feed. Those three used to sit in a
`slowTest` of their own that nothing scheduled - not `check`, not the renderer's `check`, not any
gate skill - so a rename compiled clean and failed at runtime with nothing to say so. They are in
`test` for that reason, and a suite keyed on a tag nothing ran is what this build no longer has.
`BoneRestRadianTest` holds the table's rest degrees to the radians each geometry's factory spells,
pinning the rests the degrees lose by one ULP and the ones vanilla computes rather than spells, so a
version bump that moves either set fails as the re-measurement the [renderer/Geometry.md](../renderer/Geometry.md) bullet "Do
not carry a bone's rotation in radians" owes.
`GeometryFlowPartRestsTest` value-pins four shipped floats besides - the x of each armour stand's
arm pivots - reading the table alone rather than holding it to the jar.
`GeometryBakeOrderTest` holds the part tree a walk replays to the orders vanilla's own factories bake
on the JDK 25 they run on, at the levels vanilla fills with a copy - the scaled roots, the scaled
armour shells, the replaced parts and the retain surgeries - and holds the shipped table's bone order
to that replay, so a version bump that moves one fails there rather than shipping a draw order nothing
measured.
