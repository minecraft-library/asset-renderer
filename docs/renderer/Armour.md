# Armour

Load when touching `bake/armor` - `Shell`, `ShellIndex`, `ArmorKit`, `EntityArmorKit`,
`PlayerArmorKit`, `WornBox`, `ElytraKit` - or `ArmorSlot` and `ArmorForm` in `vanilla/equipment`.
Indexed from [`CLAUDE.md`](../../CLAUDE.md).

Vanilla never derives worn armour from the wearer's own mesh - it builds a few `ArmorModelSet`s and
hands each renderer the one its subject wears. There is no armour file; the shell rides the wearer's
own `armor` node, its `geometry` pointing into `entity_geometry.json` like any other mesh.

```json
"armor": { "source": "HumanoidArmorLayer", "layer_index": 3,
           "geometry": "HumanoidModel#createBaseArmorMesh",
           "grow": { "inner": 0.5, "outer": 1.0 } }
```

- Being armoured is carrying a resolved shell: one `Optional<Shell>` with no classification flag, so
  a failed mesh walk fails loudly rather than falling back.
- The mesh is registered ungrown; both layer deformations ride the row, and `ShellIndex.of` sums a
  cube's deformation with the row's when the walk is resolved, in the parser's operand order. A set
  vanilla registers through a whole-mesh scaling - giant, husk, wither skeleton - names a mesh of its
  own, `HumanoidModel#createBaseArmorMesh@scaled=<f>`, whose bones carry the factor and the feet anchor
  as a scaled body's do and are held in the order the scaling's copy iterates the root ([Depth: the
  contract](Depth.md)).
- `EntityIndexBuilder.humanoidArmorOf` joins the shell's own mesh - what a wearer rests without, the
  subset a pass of its is restricted to and the deformation one surrounds it with are derived onto
  meshes the wearer names, and an armour row names none of them.
- The shell is built two-sided by `SurfaceTraits.WORN_SHELL`, vanilla submitting it through a no-cull
  cutout pipeline. Armour geometry differs from block geometry by those two bits and not by a code
  path, so one `buildBox` serves block, item, player, cape and shell alike.
- `Shell`, `ArmorForm` and `ArmorSlot` own what varies by shell, by that shell's shape, and by slot
  alone. Do not forward one onto another.
- `ArmorSlot` declares LEGGINGS first, vanilla's innermost layer, and all three armour walks iterate
  slot-outermost so a later slot paints over an earlier one whatever the rectangles do.
- `onLayer` is generic because its three call sites hand it two different types - a `LayerType` pair
  at `ArmorForm.layerType`, a `Vector3f` deformation pair at `WornBox.Mesh.boxFor` and at
  `EntityArmorKit.slotMesh` - so no concrete signature serves all three.
- `PlayerLattice.playerSlots(part)` is `static` and ADULT-only, because half the corpus's bone names
  have no player body part and a parameterised accessor would drop a box silently.
- The helmet's second box is a peer row on both paths - the shell's `hat` cube, or a second
  `WornBox.Body` over the part's overlay rectangle - and `keepsChildren()` is read at build.
- `ArmorKit.buildArmor3D` takes a `WornBox` list, a `UnaryOperator<Box>` frame mapping and an
  `ArmorForm`; those three are the whole difference between a player and a worn shell, and the
  mapping is an argument rather than a branch because it alone is arithmetic.
- **The two wearers are two kits, as the geometry kits are.** `EntityArmorKit` starts from the
  `Shell` an entity is dressed in; `PlayerArmorKit` holds no `Shell` at all and dresses the player's
  own body boxes; `ArmorKit` is what they share, from `buildArmor3D` down. Keeping them in one type
  is what let a shared class carry both subjects at once, and reach is resolved per CLASS - so a
  player producer read the whole entity appearance surface through the half of the kit it never
  enters. A kit that renders for one subject says so by being that subject's kit.
- `ShellIndex`'s coverage walk and pivot chain are bounded by a visiting set, not a depth cap.
- A genuinely distinct second shell repeats the node's members under `alternate` with the
  `when` that selects it and the `form` it keeps; `ArmorMeshIndex.Set.sameShellAs` decides
  distinctness by construction, never by name. `AppearanceOptions.shell` evaluates that gate once in
  the resolve, outside the age fork, so one slot serves two axes.
- The wings read that gate too. `AppearanceOptions.rendersBaby` answers baby for a subject drawn as
  its baby form and for a wearer the gate dresses in its second shell, because vanilla hands over the
  half-scale elytra on the flag that picks the baby armour set - which is how a small armour stand,
  which has no age, wears the baby wings. It is asked of the indexed definition; the resolved one
  names no second shell.
- The wings draw, and fold into the canvas, only on a row whose `layers().wings()` holds. The tooling
  emits a `wings` node at each `WingsLayer` site of a row's renderer, and the player rig sets the fact
  by hand, since `AvatarRenderer` builds one and the rig has no table row. `armor` is not that fact:
  the giant carries a shell and no wings. One boolean in `EntityRenderer` answers for the feature and
  both canvas folds, so the wings cannot draw where the canvas left no room or reserve room where
  they do not draw.
- A baby wears its own shell and nothing downstream branches on age; it draws `humanoid_baby` in all
  four slots and never a trim, and its pose is a mesh argument the geometry key names.
- A baby shell's `inner_body` cube is named by no slot and can never draw, and its feet are
  cross-parented onto the opposite legs. Both are vanilla's; normalising them edits shipped data.
- The canvas measures the shell on both sides - `EntityArmorKit.screenBounds` unions each equipped slot's
  alpha-tight bounds into the fit, and `EntityArmorKit.slotMesh` rebuilds the bone tree for
  `EntityGeometryKit.computeScreenBounds`. A reference canvas the same size armoured as bare is the
  symptom of an unmeasured shell, not evidence that vanilla clips.

## Decisions that stay closed

- **Do not swap or move one bone of a worn shell's walk on its own.** The walk is vanilla's draw
  order, not a choice of the renderer's: the base mesh's bake order, which draws `right_leg` after
  `left_leg`, and for a scaled set the order of its own mesh, whose copied root puts `body` between the
  arms and between the legs. Under the snapped depth plane the two shell legs' `south` faces are
  separated by their own corners' snap rather than tied, so a reorder reaches only the exact ties left,
  where vanilla's order is vanilla's answer by construction. A tie the renderer still loses is not an
  order it chose, and a constant moving one bone to win it back is a fitted constant; the corner snap
  the renderer does not reproduce is in [TexelFetch.md](TexelFetch.md).
- Do not unify the two corner assemblies - a `WornBox` row carries no bone-chain matrix where
  `EntityGeometryKit.computeScreenBounds` composes them, and the two frames sit a `HALF_X` apart.
- Do not fold `ArmorForm` into `Shell` - two shells of one shape share its part tables by reference,
  and the player's armour path holds no `Shell` at all.
- Do not invert the armour walks back to part-outermost - the six body-part rectangles happen to be
  disjoint and the contract must not rest on that.
- Do not give `SurfaceTraits` a second constructor - a four-argument form puts `boolean` in the same
  first three positions with two swapped, so a transposition compiles silently.
- Do not recover the armour frame from the wearer's `body` bone - it carries no rotation member, and
  a baby's body pivot is not a mesh transform.
