# Bake order

Load when touching the order `entity_geometry.json` and `block_geometry.json` hold a mesh's bones in
- `PartTree`, `GeometryFlow.bakeOrder`, a surgery the replay appends. `GeometryBakeOrderTest` holds
the replay and the shipped order to vanilla's own factories ([Gates.md](Gates.md)); what the order
decides at render is [renderer/Depth.md](../renderer/Depth.md). Indexed from
[`tooling/CLAUDE.md`](../../tooling/CLAUDE.md).

- **`entity_geometry.json` holds each mesh's bones in vanilla's bake order, and that order is
  replayed, never read off the bones a mesh ends with.** A part's children draw in the order its
  `PartDefinition.children` hash map iterates them, which follows from the sequence of map calls the
  factory made - a `put` per new name, and a `putAll` copy wherever a scaling rebuilds the root, a part
  is replaced or a surgery empties one - so the walk replays each call on `PartTree`, one
  `java.util.HashMap` per part as `PartDefinition` holds one, as it meets it. Four things keep it
  exact:
  - **It runs on a JDK that sizes a copy as vanilla's does.** From JDK 19 on, `putAll` into an empty
    map sizes `n` names `tableSizeFor(ceil(n / 0.75))`, as the JDK vanilla runs on does; an older JDK
    sizes a copy larger and iterates it in another order. `PartTree.requireVanillaCopySizing` stops
    the flow on one, and `PartTreeTest` pins it.
  - **It holds every part vanilla holds.** The parser's own `clearChild` and `retainPartsAndChildren`
    drops and the marking's drops leave the tree whole, because a copy is sized by every name its
    level holds at that moment; dropping a name afterwards moves no other, so the bones that survive
    are written in the tree's order with the dropped names left out.
  - **It rides the entry as `PartTree.MEMBER` from the parse to the write**, a generation-only member
    as `carried_field` and `item_field` are, spelled as the calls it replayed. A derivation that deep
    copies an entry carries it, and the two that replay a vanilla surgery append it:
    `EntityMeshOverlays`' subset as `retainExactParts`, its no-hat mesh as
    `clearChild(name).clearRecursively()`. `GeometryFlow.bakeOrder`, the entity flow's last step
    before the write, replays the calls and orders the bones by the result, and `GeometryFlow.write`
    takes the member off, so no table ships it.
  - **A call the replay cannot place refuses the tree rather than guessing.** A part hung from a name
    it does not hold, a name hung under a second parent, a surgery on a receiver other than the mesh
    root, or a `PartDefinition` call the walk does not replay each stop the entity flow, and
    `GeometryFlow.requireBakeOrder` stops it right after the parse, ahead of every table the flow
    writes before the geometry.

  `block_geometry.json` is written in declaration order by the same writer, the block-entity flow
  taking no bake-order step: no block-entity render has been measured under the bake order, which is
  [renderer/Depth.md](../renderer/Depth.md)'s to decide.
