# Adding a new entity pin mixin

Load when writing or gating an entity pin or freeze mixin. What each existing one does, and when one
may be deleted, is [PinCatalog.md](PinCatalog.md). Indexed from
[`harness/CLAUDE.md`](../../harness/CLAUDE.md).

> **Vanilla state-extraction call chain.** Read this before writing a new state-pin mixin. Every entity render goes through:
> ```
> EntityType.create(level, LOAD)                        → fresh, never-ticked entity
> renderer.extractRenderState(entity, state, partialTick)  ← snapshot fields
> dispatcher.submit(state, ...)
>   └─ renderer.submit(state, poseStack, ...)
>        ├─ scale(state.scale, ...)                      (LivingEntityRenderer only)
>        ├─ setupRotations(state, ps, bodyRot, scale)    ← VIRTUAL (14 overrides in 26.1)
>        ├─ scale(-1, -1, 1)                             (chirality)
>        ├─ scale(state, ps)                             (per-renderer scale override)
>        ├─ translate(0, -1.501, 0)                      (model offset)
>        └─ submitModel(model, state, ps, ...)
>             └─ model.setupAnim(state)                  ← bypassed by SkipSetupAnimMixin
>             └─ model.root.submit(...)
> ```

**Subclass ordering caveat.** Subclass `extractRenderState` calls `super.extractRenderState(...)` FIRST, then writes subclass-specific fields. A mixin on the BASE `LivingEntityRenderer` fires before the subclass writes and gets overwritten. **Pin subclass-specific fields in dedicated subclass-renderer mixins** (`GuardianStateMixin` targets `GuardianRenderer`, not `LivingEntityRenderer`).

## Randomization to pin (common sources)

When a new transient entity renders inconsistently across runs, check its constructor for `random.nextX()` and these methods:

| Entity | Source | Symptom | Pin |
|---|---|---|---|
| Guardian | `clientSideTailAnimation = random.nextFloat()` | tail snaps to different shape | `state.tailAnimation` = the roster's clock for the guardian a posed frame draws, 0 on every other render |
| Phantom | `getUniqueFlapTickOffset()` (per-instance) | wings at different cycle phase | `state.flapTime=0` |
| (any) | `getEntityToLookAt(...)` → falls back to `Minecraft.getCameraEntity()` | lookAt drifts with player camera | `state.lookAtPosition=null` |
| ZombieVillager | `BuiltInRegistries.VILLAGER_PROFESSION.getRandom(random)` | profession overlay flips between runs | pin `villagerData` to default |
| Bat | none - every `random` use is `nextInt(I)` in server-side AI a never-ticked subject does not run | none; `BatRenderState` carries no per-instance float | none needed |
| Witch | `entityId % 10` nose-bob frequency | nose sits at a different angle every run from tick 1 on | `state.entityId=9` (`IdleFigures.PINNED_ENTITY_ID`) |

**A per-instance offset is a randomization even where no `random` call is in sight.** Vanilla spreads
an idle animation across a crowd by seeding it from the entity's network id, and that id is a counter
over everything the client has built - so it reads as deterministic per subject and is not. It is
invisible while `setupAnim` is frozen, and it is what a strip catches: frame 0 is stable, because
every one of these enters as a phase and `sin(0)` is zero whatever the frequency.
