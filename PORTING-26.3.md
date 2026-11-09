# Iustitia — Minecraft 26.3 port notes

This branch ports Iustitia from **Minecraft 1.21.11 / Yarn** to **Minecraft 26.3 / Mojang names**.
The detection core is unchanged; every change is a build/API migration plus two optional display
features requested for this port (foot trail, floating nameplates).

## Version matrix

| Component | Version |
| --- | --- |
| Minecraft | 26.3 |
| Fabric Loader | 0.20.1 |
| Fabric API | 0.165.2+26.3 |
| Fabric Language Kotlin | 1.14.2+kotlin.2.4.21 |
| Kotlin | 2.4.21 |
| Fabric Loom | 1.19.4 (`net.fabricmc.fabric-loom`, **not** `fabric-loom-remap`) |
| Gradle | 9.8.2 |
| Java | 25 (compile + runtime) |
| Yet Another Config Lib | 3.9.7+26.3-fabric |

Build:

```powershell
$env:JAVA_HOME="C:\Program Files\Microsoft\jdk-25.0.3.9-hotspot"
.\gradlew.bat build
```

## 26.3 is unobfuscated

26.3 ships an **unobfuscated** client jar (Mojang official names, no `client_mappings` in the version
JSON). Consequences, all reflected in `build.gradle.kts`:

1. The Loom plugin is the plain `net.fabricmc.fabric-loom`, not `fabric-loom-remap`.
2. There is no `mappings(...)` dependency and no remap step.
3. Mod dependencies are plain `implementation`, not `modImplementation`.
4. Mixin `@Inject`/`@Redirect` targets are written with official names at runtime, so no refmap is
   involved and `useLegacyMixinAp` is irrelevant.
5. `iustitia.mixins.json` uses `compatibilityLevel: JAVA_25` (the mod compiles to Java 25 bytecode).

## How the source migration was done

1. **Class names**: Yarn 1.21.11 ↔ Mojang 1.21.11 mappings were joined through intermediary
   (`official → intermediary → named` plus the Mojang proguard file), then every import, simple name
   and fully-qualified reference was rewritten mechanically (173 files, 33k lines). The join/migrate
   tooling lives outside the repo (`tools/mappings/join.py`, `tools/migrate.py` in the porting
   workspace); it is not needed to build.
2. **Member names / API changes**: driven by the Kotlin compiler. Each error was resolved against the
   decompiled 26.3 sources or `javap` on the real 26.3 jar — never guessed.
3. **Behaviour**: no thresholds, tolerances, encodings, file formats or public signatures changed.
   Every semantic compromise is listed below.

## API changes that touched the most code

| 1.21.11 (Yarn) | 26.3 |
| --- | --- |
| `MinecraftClient` / `mc.world` / `textRenderer` | `Minecraft` / `mc.level` / `mc.font` |
| `mc.currentScreen` / `mc.setScreen` | `mc.gui.screen()` / `mc.gui.setScreen(…)` |
| `mc.inGameHud.chatHud.addMessage` | `mc.gui.hud.chat.addClientSystemMessage` |
| `mc.networkHandler` | `mc.getConnection()` |
| `WorldRenderEvents.AFTER_ENTITIES` / `END_MAIN` | `LevelRenderEvents.COLLECT_SUBMITS` / `END_MAIN` |
| `MultiBufferSource` + `ShapeRenderer.drawOutline` | `SubmitNodeCollector.submitShapeOutline(…)` |
| `VertexConsumerProvider` text + private `Immediate` | `SubmitNodeCollector.submitText(…)` |
| `EntityRenderer.render(...)` | `EntityRenderer.submit(state, PoseStack, collector, CameraRenderState)` |
| `updateRenderState` / `renderLabelIfPresent` | `extractRenderState` / `submitNameDisplay` |
| `HudRenderCallback` | `HudElement` + `HudElementRegistry` |
| `Screen.render(GuiGraphics, …)` | `Screen.extractRenderState(GuiGraphicsExtractor, …)` |
| `Perspective` / `Options.perspective` | `CameraType` / `Options.cameraType` |
| `ClientCommandManager` | `ClientCommands` |
| `ServerboundSwingPacket` | `ServerboundAttackPacket` + `ServerboundPunchPacket` |
| `ClientboundAnimatePacket` swing action | `ClientboundSwingAnimationPacket` + `handleSwingAnimation` |
| GLFW key constants | `InputConstants.KEY_*` (SDL scancodes) |
| `Language.get` / `Options.allKeys` | `getOrDefault` / `keyMappings` |

Other renamed members worth knowing: `PoseStack.push/pop/peek/multiply` → `pushPose/popPose/last/rotate`,
`Box` → `AABB`, `isOf` → `` `is` `` (a Kotlin keyword, hence the backticks), `Block.getDefaultState` →
`defaultBlockState`, `BlockState.isOpaqueFullCube` → `isSolidRender`, `ItemStack.useAction` →
`getUseAnimation`, `Vec3.squaredDistanceTo` → `distanceToSqr`, `MathHelper.lerpAngleDegrees` →
`Mth.rotLerp`, `ClientLevel.chunkManager` → `getChunkSource()`, `getEntities()` →
`entitiesForRendering()`, `mainHandStack` → `getMainHandItem()`, `player.sendMessage` →
`sendSystemMessage`.

## Behavioural notes / approximations

- **Swing detection moved packets.** 26.3 no longer broadcasts swings through `ClientboundAnimatePacket`
  (it now carries only wake-up / crit / magic-crit); swings arrive as
  `ClientboundSwingAnimationPacket(entityId, hand, animation)` and are captured on
  `ClientPacketListener.handleSwingAnimation`, mapped back to the existing `SwingSignal` shape.
- **Outgoing swing suppression** during a replay covers both new packets
  (`ServerboundAttackPacket` and `ServerboundPunchPacket`).
- **Hand swing phase** (`TrackedPlayer.handSwingTicks` / `PlayerSnap.swingTicks`): 26.3 removed the
  integer `swingTime`, so the tick phase is re-derived as `round(getSwingAnimation(1.0) * 6)` only
  while `isSwinging()`; the last tick of a swing can read 6 instead of 5.
- **Chunk-world rendering**: the old static-GPU-mesh fast path (`GpuBuffer` + custom `RenderPass`)
  no longer exists in 26.3's render backend. The recorded clip world is now submitted per frame
  through custom geometry, which looks identical but loses only that FPS optimisation.
- **Replay ghosts** now go through vanilla's own `AvatarRenderer.submit`, so armor and held items use
  the real feature renderers. The old `LivingEntityRendererAccessor` mixin is gone.
- **Screenshots** use `Screenshot.takeScreenshot(mainRenderTarget(), 1)` plus a PNG write on the IO
  pool (`Screenshot.grab(...)` was removed).
- **Freecam hand hiding** targets `GameRenderer.renderItemInHand` (the `renderHand` method is gone)
  and cancels the whole hand submission while freecam is active.

## New display features in this port

- **Foot trail** (`FootTrailRenderer`): a short trail of small tier-colored wireframe squares at each
  tracked player's feet, one per recorded tick, faded by age. Green (clean) players are included by
  default (`footTrailGreen`), because a not-yet-flagged player is exactly who a moderator wants to
  watch. Config: `footTrail`, `footTrailGreen`, `footTrailTicks` (1–40).
- **Floating nameplates** (`NameplateRenderer`): `[+]` / `[!]` / `[X]` plus the player name above a
  tracked head — but only as a *supplement*: while the server lets the vanilla nametag render (the
  normal standing case) the tier already shows through the nametag prefix, so the custom plate is
  skipped to avoid a doubled label. The plate appears for the one case vanilla hides the name — a
  discrete (sneaking) player — and there only for yellow/red tiers. Red plates are drawn at full
  brightness with a red outline ("glow"). Config: `nameplates`, `nameplateRedGlow`.
- Both are render-only, depth-tested, fail-open, and have en_us + zh_cn strings.
- **Preset note:** the everyday profiles (`standard` / `lenient` / `strict` / `moderation`) now keep
  `tabListBadge`, `footTrail`, `footTrailGreen`, `nameplates` and `nameplateRedGlow` **on**. They used
  to ship with the tab badge (and every world overlay) off, which made a fresh install look as if the
  features were broken. An existing `config/iustitia.json` keeps whatever it saved, so toggle the
  option in `/ius config` or re-apply the preset (`/ius preset standard`).

## Verification status

- `gradlew build`: green — `compileKotlin`, `compileGametestKotlin`, 89 JVM unit tests, jar.
- Dev client launches on 26.3: all 16 mixins apply (verified with `-Dmixin.debug.verbose=true`), the
  setup wizard renders (Chinese), a flat world loads and renders.
- Automated live tests: `gradlew runClientGameTest` runs on 26.3 after the harness port. The
  `reach`/`CHEAT` slice passes 6/6. One harness fix was required for 26.3: client-side bots must be
  given an explicit entity id (`Entity.getId()` throws while the id is 0).
- Full `CHEAT` pass (62 scenarios): **51 pass, 11 miss**. Every one of the 11 misses passes when run
  on its own (`-Pselftest.filter=…`): 13/13 re-runs green. The failures cluster on the block-material
  drives (no-fall, water-walk, no-slow, wall-sprint, sprint-hack, phase, spider), i.e. they are
  cross-scenario world-state interference in one long harness run, not a detection regression. Worth
  fixing in the harness (per-scenario world reset / distinct arena offsets) before it can be used as
  a release gate.
- Visual checks done on the running client: the setup wizard renders in Chinese; the tab-list tier
  badge renders (`[+] Name`); a second client's nametag no longer doubles.
- Still runtime-only (cannot be proven by a build): the exact on-screen placement of the new overlays
  and the freecam/replay camera overrides. Those paths are fail-open and were exercised by the
  in-world launch, but a visual pass on a real server is still worth doing before release.

