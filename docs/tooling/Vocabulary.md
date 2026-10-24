# The vocabulary a table is written in

Load when touching where a shipped table's tokens come from - `PoseChannel`, `PoseOperator`,
`PoseExpr.Answered`, `BoneFlag`, `PoseProgram.flags` - or `PoseFold`'s flag carrier. Pruning
`PoseOperator`'s unused constants is refused in
[renderer/pose/Authoring.md](../renderer/pose/Authoring.md#decisions-that-stay-closed). Indexed from
[`tooling/CLAUDE.md`](../../tooling/CLAUDE.md).

Part of the vocabulary a shipped table is written in travels as a TYPE and part as a value. The nine
channel tokens come off the renderer's `PoseChannel`, which this build keys its channel maps on
directly, and the operator tokens come off the one `PoseOperator` both sides read - so a renderer
edit to either roster moves an emitted table, and that is the thing to weigh before reaching for a
renderer type. The expression and predicate grammar is the renderer's too, `PoseExpr.Answered`
holding the arms only a generator writes; the carried-figure arm's write rule is
[PoseWalk.md](PoseWalk.md).

**A `putfield` names a channel in camel case and a table spells it in snake case, and that is one
word rather than two rosters.** `PoseWalk.channelOf` converts and asks `PoseChannel.ofToken`, so
there is no second eleven-member enum to keep in step - which is what there used to be, and it drifted
by carrying its own token beside the channel's.

**The two members that decide whether a bone DRAWS are not channels and are not in the channel map.**
`visible` and `skipDraw` are `BoneFlag`, and a pose carries them as `PoseProgram.flags`, keyed by flag
and then by bone. They are apart because nothing at render reads one: a flag is settled while the
table is written, and which bones a subject rests without is stamped onto the mesh by
`EntityMeshMarking` rather than into a pose. Three things follow, and each of them used to be a
filter that could be forgotten:

- `Map<PoseChannel, PoseExpr>` structurally cannot hold a flag, so the writer and the style flow no
  longer skip one and a silhouette has no spelling for one.
- `PoseFold` folds the flag carrier WHOLE through the instance that resolves the one-hot states,
  rather than routing per entry on a property of a key. A flag folded by the main instance stays
  symbolic and stops the flow; a channel folded by the flag instance is over-folded in silence and
  moves bytes.
- A bone the body writes ONLY a flag on still keeps its row, and what keeps it is being a KEY of the
  bone map rather than anything under that key. Thirty-three rows are exactly this, one of them a
  frog's only bone. The mesh root is registered nowhere, being a container rather than a bone.
