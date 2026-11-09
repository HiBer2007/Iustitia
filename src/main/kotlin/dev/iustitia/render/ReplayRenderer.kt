package dev.iustitia.render

import dev.iustitia.NumFmt
import dev.iustitia.config.ConfigManager
import dev.iustitia.history.FlagHistory
import dev.iustitia.i18n.L10n
import dev.iustitia.replay.ReplayBuffer
import dev.iustitia.replay.ReplayState
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.client.renderer.entity.EntityRenderer
import net.minecraft.client.renderer.entity.player.AvatarRenderer
import net.minecraft.client.renderer.entity.state.AvatarRenderState
import net.minecraft.client.renderer.entity.state.BoatRenderState
import net.minecraft.client.renderer.entity.state.EntityRenderState
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState
import net.minecraft.client.renderer.entity.state.MinecartRenderState
import net.minecraft.client.model.HumanoidModel
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.HumanoidArm
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Pose
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.Shapes
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Instant-replay renderer (Phase 2): while [ReplayState] is active, draws a HUMANOID "ghost" of every
 * tracked player at the playhead frame's buffered position — advanced through the timeline at the
 * replay speed. One shared renderer serves `/ius replay` (a live-buffer window) and `/ius playclip`
 * (a `.iusclip` loaded into [ReplayState]).
 *
 * ## Why a humanoid model, not a single box
 *
 * The first cut drew one wireframe box per player; it read as an anonymous gray crate and you
 * couldn't tell which ghost was which user. This draws the player's actual model when the real-model
 * path is enabled (the default) and falls back to a pose-aware wireframe humanoid otherwise, colors
 * each ghost by that player's cheat tier (green / yellow / red), highlights the replay focus in cyan
 * with a thicker outline + a `▶` name marker, and floats a name tag above every ghost so you can read
 * who is who. Both paths are depth-tested → occluded by walls, no wallhack.
 *
 * ## 26.3 render integration (what replaced what)
 *
 * * **World phase:** `WorldRenderEvents.AFTER_ENTITIES` → [LevelRenderEvents.COLLECT_SUBMITS]. Its
 *   [LevelRenderContext] gives `poseStack()` (a **world-origin identity** stack — vanilla's own
 *   `LevelRenderer.submitFeatures` builds a fresh `PoseStack` and translates every entity by
 *   `pos - cameraPos` itself), `submitNodeCollector()` (the frame's [SubmitNodeCollector]) and
 *   `levelState()` (with `cameraRenderState`, a fully populated [CameraRenderState] — no more
 *   hand-refreshing a shared camera state). We therefore translate by `-camPos + relocOffset` once and
 *   never touch the camera state.
 * * **Geometry:** `MultiBufferSource` is gone. Wireframe boxes go through
 *   [OrderedSubmitNodeCollector.submitShapeOutline] (the `ShapeRenderer`/`VertexRendering` replacement),
 *   and all world-space text goes through [SubmitNodeCollector.submitText] — which also removes the old
 *   hand-rolled "dedicated text Immediate, flushed at END_MAIN" workaround: the collector owns the
 *   draw order, so name tags no longer need to be flushed after the block/model pass.
 * * **Models:** `EntityRenderer.render(...)` → [EntityRenderer.submit] with a render state built by
 *   `createRenderState()` + `extractRenderState(...)`. The player ghost now drives vanilla's
 *   [AvatarRenderer.submit] end to end, so the skin, the armor + held-item feature renderers, the body
 *   rotation, the sneak offset and the `-1,-1,1` / `-1.501` seat transform are all vanilla's own — the
 *   old path had to replicate each of them by hand and could not run the feature renderers without the
 *   (now removed) entity command queue.
 *
 * ## In-world ghosts
 *
 * Color alpha is baked into the ARGB int (`submitShapeOutline` takes one ARGB int; the `float` is line
 * WIDTH, not alpha). Pose handling: sneak lowers the model (vanilla's own `getRenderOffset` for the
 * model path; `-0.12` for the fallback box), glide/swim/riptide lay it forward (the model path drives
 * it from the render-state flags). The per-player facing nub is computed directly from the buffered
 * yaw (guaranteed correct regardless of any matrix-rotation sign ambiguity). Display-only — adds NO
 * detection.
 *
 * ### Name tags
 *
 * A billboarded name floats above each ghost via [SubmitNodeCollector.submitText] with
 * [Font.DisplayMode.SEE_THROUGH] (always readable, even through terrain). The matrix is billboarded by
 * the camera's orientation quaternion (`CameraRenderState.orientation` — the same value vanilla stores
 * and multiplies in its own label pass), which cancels the view rotation so the tag is screen-aligned
 * (yaw + pitch), then scaled `(s,-s,s)` the way vanilla's `(0.025,-0.025,0.025)` label scale is.
 * Mild distance scaling keeps near ghosts from blowing up and far ghosts readable. Fail-open per ghost.
 *
 * ## "Rewind feel"
 *
 * [ReplayState.hideLive] (default on) hides every live OTHER player during a replay via
 * [dev.iustitia.mixin.EntityRendererMixin], so only the buffered ghosts render — a rewind-the-world
 * feel. The live game + detection keep running; rendering snaps back the instant the replay stops.
 *
 * ## Replay HUD
 *
 * A bottom-center banner (registered as a Fabric [HudElement], the 26.3 replacement for the removed
 * `HudRenderCallback`) shows the focus name + speed + a progress bar, so a 20–40s replay has a visible
 * "where am I in the rewind". It draws through [GuiGraphicsExtractor].
 *
 * Runtime-only-verifiable: a build confirms the model compiles + the matrices/colors match the
 * established overlay pattern; whether ghosts land exactly on the world, the lay-pose rotation
 * direction, and the name-tag orientation are confirmed only at runtime (all wrapped in fail-open).
 */
object ReplayRenderer {

    // Limb dimensions (blocks). Feet at origin, +Y up, +Z = body front.
    private const val LEG_H = 0.72
    private const val TORSO_H = 0.72
    private const val ARM_H = 0.72
    private const val HEAD = 0.4
    private const val HALF_W = 0.0625        // limb half-width
    private const val LIMB_D = 0.0625        // limb half-depth
    private const val TORSO_HALF = 0.25      // torso half-width (shoulders)
    private const val TORSO_HALF_D = 0.0625 // torso half-depth
    private const val LEG_OFF = 0.075        // leg center X offset from centerline
    private const val ARM_OFF = 0.3125      // arm center X offset (just outside torso)

    // Bright, full-alpha ARGB tier colors (drop the unreadable gray). Alpha in the high byte.
    private val GREEN_COLOR = 0xFF40E060.toInt()
    private val YELLOW_COLOR = 0xFFFFD020.toInt()
    private val RED_COLOR = 0xFFFF4040.toInt()
    private val FOCUS_COLOR = 0xFF40D0FF.toInt()   // cyan — distinct from all three tier colors

    private const val GHOST_WIDTH = 1.5f
    private const val FOCUS_WIDTH = 2.5f

    /** Non-player entity ghosts (v13) are only drawn within this radius (blocks) of the camera, so a
     *  big captured scene doesn't submit hundreds of far entity models per frame. */
    private const val ENTITY_RENDER_RADIUS = 64.0
    private const val ENTITY_RENDER_RADIUS_SQ = ENTITY_RENDER_RADIUS * ENTITY_RENDER_RADIUS

    // Fullbright light coord (LightCoordsUtil.FULL_BRIGHT / LightmapTextureManager.MAX_LIGHT_COORDINATE).
    private const val FULL_LIGHT = 0xF000F0

    // Health-indicator smoothing: the displayed number lerps toward the raw synced target by this
    // fraction per PLAYHEAD tick (geometric — 0.3/tick converges to ~94% in ~8 ticks). The server's
    // other-player HEALTH tracked-data is coarse/batched and decoupled from the hurt flash (a +15
    // heal batch, a -16 crit, delivered out of order — the health-sync investigation traced the wild
    // 3→18→7→17 swings are genuine server values, not a pipeline bug), so snapping the indicator to
    // the raw value flickers the number across consecutive ticks. Smoothing ramps a batched jump
    // visibly instead. Matches the WalkState limb-amp lerp rate at :632. Seek/scrub/rewind snaps.
    private const val HEALTH_SMOOTH_RATE = 0.3f

    /** Fabric id of the replay banner [HudElement]. */
    private val BANNER_ID = Identifier.fromNamespaceAndPath("iustitia", "replay_banner")

    fun register() {
        try {
            LevelRenderEvents.COLLECT_SUBMITS.register(LevelRenderEvents.CollectSubmits { ctx ->
                try {
                    if (!ReplayState.active) return@CollectSubmits
                    drawGhosts(ctx)
                } catch (_: Throwable) {
                    // fail-open: a render error never crashes the client
                }
            })
            // 2D banner: HudRenderCallback is gone; a HudElement registered through HudElementRegistry is
            // the supported hook. `addLast` keeps vanilla's HUD intact and paints the banner on top.
            HudElementRegistry.addLast(BANNER_ID, ReplayBanner)
        } catch (_: Throwable) {}
    }

    /**
     * The replay status banner as a HudElement. `extractRenderState` replaces the old
     * `HudRenderCallback` lambda: same guard (only while a replay is active) and same fail-open.
     */
    private object ReplayBanner : HudElement {
        override fun extractRenderState(graphics: GuiGraphicsExtractor, deltaTracker: DeltaTracker) {
            try {
                if (ReplayState.active) renderHud(graphics)
            } catch (_: Throwable) {}
        }
    }

    private fun drawGhosts(ctx: LevelRenderContext) {
        val mc = Minecraft.getInstance()
        // Partial-tick progress toward the next client tick; vanilla fills the level render state with
        // this same value, and the playhead lerp just below consumes it.
        val tickDelta = try { ctx.levelState().worldPartialTicks } catch (_: Throwable) {
            mc.getDeltaTracker().getGameTimeDeltaPartialTick(false)
        }
        val frame = ReplayState.currentFrameLerped(tickDelta) ?: return
        // Draw when EITHER ghost set is non-empty: a frame whose tracked players have all logged off can
        // still carry captured mobs/vehicles (SnapClip short-circuits on empty snaps only, which drops
        // those entity ghosts).
        if (frame.snaps.isEmpty() && frame.entities.isEmpty()) return
        // The frame's camera state: `pos` is the camera world position, `orientation` the camera
        // rotation quaternion used for billboarding. Vanilla filled both this frame, so no refresh.
        val camera = ctx.levelState().cameraRenderState
        val camPos = camera.pos
        val collector = ctx.submitNodeCollector()
        val tr = mc.font
        val focusUuid = ReplayState.focusUuid
        // In POV the camera sits at the focus ghost's eye — don't draw that ghost (you're inside its
        // head; rendering it would fill the view with a wireframe/model). Other ghosts still render.
        val skipFocus = ReplayState.cameraMode == ReplayState.CameraMode.POV && focusUuid != null
        // Active-alert labels for this frame: uuid → the offender's alert label, for any alert whose
        // captured tick is within ±1 of the playhead frame (so a ⚠ floats over a ghost exactly as the
        // playhead crosses the moment the cheat fired). Fail-open: an empty/missing map just draws none.
        val nowTick = frame.tick
        val alertLabelFor: Map<java.util.UUID, String> = try {
            // Cache per tick (frame.tick changes once per replay tick, not per render frame); the
            // common no-alerts case short-circuits to an empty map without allocating a filter.
            if (nowTick == cachedAlertTick) cachedAlertLabels
            else {
                val built: Map<java.util.UUID, String> =
                    if (ReplayState.alerts().isEmpty()) emptyMap()
                    else ReplayState.alerts().filter { abs(it.tick - nowTick) <= 1 }
                        .associate { it.uuid() to it.label }
                cachedAlertTick = nowTick
                cachedAlertLabels = built
                built
            }
        } catch (_: Throwable) { emptyMap() }
        val matrices = ctx.poseStack()
        matrices.pushPose()
        // Fold the relocation offset into the single shared origin translate: every per-snap
        // translate(s.x, s.y, s.z) below then lands the ghost at (recordedPos + offset) - camPos, i.e.
        // the whole scene shifts as a rigid block to the user (focus player's start → the user). The
        // terrain overlay draws in this same frame, so its absolute-coord quads relocate by the same
        // offset. null offset ⇒ legacy absolute rendering (relocate disabled / unavailable).
        val o = ReplayState.relocOffset ?: Vec3.ZERO
        matrices.translate(-camPos.x + o.x, -camPos.y + o.y, -camPos.z + o.z)
        try {
            // Chunk world (v6+ clip-only; null for /ius replay + pre-v6 clips). Solid, textured blocks
            // — the real captured map, face-culled to the surface shell — so the user can free-spectate
            // anywhere, including underground. Drawn first (under ghosts + the v5 wireframe terrain).
            // Skipped for a Legacy playclip (v1.1.0 = ghosts over the live world, no chunk world) —
            // belt-and-suspenders with ReplayState.start nulling chunks for Legacy.
            if (!ReplayState.legacyPlayclip) {
                try { ChunkWorldRenderer.render(matrices, collector, camPos, o) } catch (_: Throwable) {}
            }
            // Terrain shell (clip-only; null for /ius replay). Face-culled, so only the visible
            // surface blocks draw — fully-enclosed blocks contribute nothing. Fail-open per frame.
            // Also skipped for a Legacy playclip (no terrain in v1.1.0).
            if (!ReplayState.legacyPlayclip) {
                try { TerrainOverlay.render(matrices, collector) } catch (_: Throwable) {}
            }
            for (s in frame.snaps) {
                try {
                    if (skipFocus && s.uuid() == focusUuid) continue
                    drawHumanoid(matrices, collector, camera, tr, s, focusUuid, camPos, o, alertLabelFor[s.uuid()], nowTick)
                } catch (_: Throwable) {
                    // skip one bad ghost, keep the rest
                }
            }
            // v13 non-player entity ghosts (mobs / animals / boats / minecarts) — the rest of the
            // captured scene, drawn with each entity's vanilla renderer model (no tier color: a mob
            // isn't flagged). Gated by clipEntities; fail-open per entity. Drawn after the players so
            // the two ghost sets share the one relocation translate above.
            try {
                val cfg = try { ConfigManager.config } catch (_: Throwable) { null }
                // Skipped for a Legacy playclip: v1.1.0 was ghosts-over-the-live-world, with no captured
                // scene — belt-and-suspenders with the chunks/terrain gates above.
                if (cfg?.clipEntities == true && !ReplayState.legacyPlayclip && frame.entities.isNotEmpty()) {
                    for (e in frame.entities) {
                        try {
                            if (entityRendererFor(e.typeId) != null) {
                                drawEntityGhost(matrices, collector, camera, e, camPos, o, frame.tick)
                            } else if (nonLivingRendererFor(e.typeId) != null) {
                                drawNonLivingEntityGhost(matrices, collector, camera, e, camPos, o, frame.tick)
                            }
                        } catch (_: Throwable) {
                            // skip one bad entity, keep the rest
                        }
                    }
                }
            } catch (_: Throwable) {}
        } finally {
            matrices.popPose()
        }
    }

    /**
     * Draw one ghost: a pose-aware humanoid (real model when enabled, wireframe fallback) + a facing
     * nub (direct, correct yaw) + a billboarded name tag. [camPos] is only used for the name-tag
     * distance scaling. [alertLabel], when non-null, floats a ⚠ marker above the ghost (the playhead
     * just crossed an alert for this player).
     */
    private fun drawHumanoid(
        matrices: PoseStack,
        collector: SubmitNodeCollector,
        camera: CameraRenderState,
        tr: Font,
        s: ReplayBuffer.PlayerSnap,
        focusUuid: java.util.UUID?,
        camPos: Vec3,
        offset: Vec3,
        alertLabel: String?,
        tick: Int,
    ) {
        // Offset floats for the name-tag / alert-marker distance scaling — the apparent distance must
        // be to the RELOCATED ghost (recorded + offset), not the original recorded spot (which on a
        // cross-server playclip could be far away and would blow the tag up to max scale).
        val oxF = offset.x.toFloat()
        val oyF = offset.y.toFloat()
        val ozF = offset.z.toFloat()
        val uuid = s.uuid()
        val isFocus = focusUuid != null && uuid == focusUuid
        val tier = try { FlagHistory.tierFor(uuid) } catch (_: Throwable) { FlagHistory.Tier.GREEN }
        val color = if (isFocus) FOCUS_COLOR else tierColor(tier)
        val width = if (isFocus) FOCUS_WIDTH else GHOST_WIDTH
        val laid = s.pose == ReplayBuffer.POSE_GLIDE || s.pose == ReplayBuffer.POSE_SWIM || s.pose == ReplayBuffer.POSE_RIPTIDE
        // v7 clip overlays (gated, default off): a numeric health indicator + a totem-pop counter badge
        // on the nametag. Fail-open off. Both are Modern-playclip-only conceptually but the gate is the
        // config flag (Legacy has no chunk world but ghosts + nametags still render, so the indicator
        // still applies to a Legacy replay too — harmless; the toggle is the source of truth).
        val cfg = try { ConfigManager.config } catch (_: Throwable) { null }
        val healthOn = try { cfg?.clipHealthIndicator == true } catch (_: Throwable) { false }
        val totemOn = try { cfg?.clipTotemPopCounter == true } catch (_: Throwable) { false }
        // Per-ghost totem-pop count within the clip window (Phase 4: ReplayState aggregates the clip's
        // totem events). 0 when the clip has no totems / the accessor is unavailable (pre-v7 clip).
        val totemCount = if (totemOn) try { ReplayState.totemCountFor(uuid) } catch (_: Throwable) { 0 } else 0

        // --- real player model path (default on): vanilla AvatarRenderer.submit, driven by a fabricated
        //     render state + the resolved skin. If it returns false (toggle off, renderer unavailable,
        //     or any throw) the box outline below is the fail-open fallback. ---
        val drewModel = drawModel(matrices, collector, camera, s, tick)
        if (!drewModel) {
            // --- body (feet at the world point; pose applied to the local frame) ---
            matrices.pushPose()
            try {
                matrices.translate(s.x.toDouble(), s.y.toDouble(), s.z.toDouble())
                if (s.pose == ReplayBuffer.POSE_SNEAK) matrices.translate(0.0, -0.12, 0.0)
                if (laid) matrices.rotateDegrees(com.mojang.math.Axis.XP, 90f)
                // legs
                box(matrices, collector, -LEG_OFF - HALF_W, 0.0, -LIMB_D, -LEG_OFF + HALF_W, LEG_H, LIMB_D, color, width)
                box(matrices, collector,  LEG_OFF - HALF_W, 0.0, -LIMB_D,  LEG_OFF + HALF_W, LEG_H, LIMB_D, color, width)
                // torso (sits on the legs)
                box(matrices, collector, -TORSO_HALF, LEG_H, -TORSO_HALF_D, TORSO_HALF, LEG_H + TORSO_H, TORSO_HALF_D, color, width)
                // arms (hang at the torso sides)
                box(matrices, collector, -ARM_OFF - HALF_W, LEG_H, -LIMB_D, -ARM_OFF + HALF_W, LEG_H + ARM_H, LIMB_D, color, width)
                box(matrices, collector,  ARM_OFF - HALF_W, LEG_H, -LIMB_D,  ARM_OFF + HALF_W, LEG_H + ARM_H, LIMB_D, color, width)
                // head (on top of the torso) — tilted by the buffered pitch around the neck pivot (top
                // of the torso) so the ghost nods to where the player was looking. Rotation sign is
                // runtime-verifiable; on any throw we draw it un-tilted (fail-open).
                val headY = LEG_H + TORSO_H
                val hh = HEAD / 2
                try {
                    matrices.pushPose()
                    matrices.translate(0.0, headY.toDouble(), 0.0)
                    matrices.rotateDegrees(com.mojang.math.Axis.XP, s.pitch)
                    box(matrices, collector, -hh, 0.0, -hh, hh, HEAD.toDouble(), hh, color, width)
                } catch (_: Throwable) {
                    box(matrices, collector, -hh, headY, -hh, hh, headY + HEAD, hh, color, width)
                } finally {
                    matrices.popPose()
                }
            } finally {
                matrices.popPose()
            }

            // --- facing nub: a small cube ahead of the head along the buffered LOOK ray (yaw + pitch)
            //     so the indicator always points where the player was looking (independent of the body
            //     lay-rotation above). Computed directly from yaw+pitch in world coords. ---
            try {
                val yawRad = s.yaw * (PI / 180.0)
                val pitchRad = s.pitch * (PI / 180.0)
                val fx = -sin(yawRad)
                val fz = cos(yawRad)
                val cp = cos(pitchRad)
                val sp = sin(pitchRad)
                val nubY = if (laid) LEG_H + TORSO_H * 0.5 else LEG_H + TORSO_H + HEAD * 0.5
                val reach = 0.45
                val nx = s.x + fx * cp * reach
                val ny = s.y + nubY - sp * reach   // pitch+ (looking down) → nub drops
                val nz = s.z + fz * cp * reach
                val r = 0.07
                box(matrices, collector, nx - r, ny - r, nz - r, nx + r, ny + r, nz + r, color, width)
            } catch (_: Throwable) {}
        }

        // --- name tag (billboarded, see-through) ---
        try {
            val name = s.name
            // v7 totem-pop counter badge: a gold ⚡<count> appended before the name when the toggle is
            // on and this ghost popped ≥1 totem in the clip window. Pure text — no feature renderer.
            val totemBadge = if (totemOn && totemCount > 0) "§6⚡$totemCount§r " else ""
            val label = if (isFocus) Component.literal("§f▶ $totemBadge$name") else Component.literal("$totemBadge$name")
            // labelY = the text's TOP in world space (after the −Y flip the glyphs extend DOWNWARD
            // from this point). The model + hat reach ~1.95 above the feet, so the baseline must sit
            // high enough that the text's bottom (labelY − ~0.25) clears the head — otherwise the tag
            // renders inside the model.
            val labelY = if (laid) 0.95 else if (s.pose == ReplayBuffer.POSE_SNEAK) 2.15 else 2.5
            matrices.pushPose()
            try {
                matrices.translate(s.x.toDouble(), (s.y + labelY).toDouble(), s.z.toDouble())
                val dx = (s.x + oxF) - camPos.x.toFloat()
                val dy = (s.y + labelY + oyF) - camPos.y.toFloat()
                val dz = (s.z + ozF) - camPos.z.toFloat()
                val dist = sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()
                val scale = (0.025f * (dist / 8f)).coerceIn(0.015f, 0.09f)
                // Billboard via the camera orientation quaternion — cancels the view rotation already
                // on the stack so the tag is screen-aligned (yaw + pitch), like vanilla's label pass.
                matrices.rotate(camera.orientation)
                matrices.scale(scale, -scale, scale)
                val w = tr.width(label)
                val textColor = if (isFocus) FOCUS_COLOR else color
                collector.submitText(
                    matrices, -w / 2f, 0f, label.visualOrderText, false,
                    Font.DisplayMode.SEE_THROUGH, FULL_LIGHT, textColor, 0, 0,
                )
            } finally {
                matrices.popPose()
            }
        } catch (_: Throwable) {}

        // --- v7 health indicator (billboarded, see-through): a numeric §c<hp>§r/§f<max> line above
        //     the name, plus a transient §c-<dmg> popup for ~40 ticks after a detected health drop
        //     (damage amount = health-diff; attack SOURCE is not available client-side for other
        //     players). Gated by clipHealthIndicator; fail-open per ghost. ---
        if (healthOn) {
            try {
                val hs = healthState.computeIfAbsent(uuid) { HealthState() }
                // Drop detection runs on the RAW target (s.health), not the smoothed display — a real
                // damage event pops -dmg immediately even while the displayed number is still ramping
                // down. The server's batched health sync is decoupled from the hurt flash, so a single
                // recorded -16 is a real hit worth flagging regardless of when the smoothed number
                // reaches it.
                val prev = hs.lastHealth
                if (!prev.isNaN() && s.health < prev - 0.5f) {
                    hs.dmgAmount = (prev - s.health).coerceIn(0.5f, 40f)
                    hs.dmgTick = tick
                }
                hs.lastHealth = s.health
                // Smoothed display: lerp the shown number toward the raw target once per PLAYHEAD TICK
                // (tickJump == 1), mirroring the WalkState limb-amp lerp — FPS-independent (the playhead
                // holds for ~3 render frames between ticks). A 0.3/tick geometric lerp converges in ~8
                // ticks, so the server's coarse/batched HEALTH sync ramps visibly instead of snapping.
                // Seek / scrub / rewind / first sighting (tickJump != 1) snaps to target so a timeline
                // jump doesn't ramp; tickJump == 0 (same frame redrawn) holds the smoothed value.
                val tickJump = tick - hs.displayTick
                when {
                    hs.displayHealth.isNaN() -> hs.displayHealth = s.health
                    tickJump == 1 -> hs.displayHealth += (s.health - hs.displayHealth) * HEALTH_SMOOTH_RATE
                    tickJump == 0 -> { }  // same playhead frame redrawn → hold
                    else -> hs.displayHealth = s.health   // seek / scrub / rewind
                }
                hs.displayTick = tick
                val maxH = if (s.maxHealth > 0f) s.maxHealth else 20f
                val hp = hs.displayHealth.coerceIn(0f, maxH)
                val baseY = if (laid) 0.95 else if (s.pose == ReplayBuffer.POSE_SNEAK) 2.15 else 2.5
                val hy = baseY + 0.35f
                matrices.pushPose()
                try {
                    matrices.translate(s.x.toDouble(), (s.y + hy).toDouble(), s.z.toDouble())
                    val dx = (s.x + oxF) - camPos.x.toFloat()
                    val dy = (s.y + hy + oyF) - camPos.y.toFloat()
                    val dz = (s.z + ozF) - camPos.z.toFloat()
                    val dist = sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()
                    val scale = (0.025f * (dist / 8f)).coerceIn(0.015f, 0.09f)
                    matrices.rotate(camera.orientation)
                    matrices.scale(scale, -scale, scale)
                    val hpInt = (hp + 0.5f).toInt()
                    val maxInt = (maxH + 0.5f).toInt()
                    val hpLabel = Component.literal("§c$hpInt§r/§f$maxInt")
                    val w = tr.width(hpLabel)
                    collector.submitText(
                        matrices, -w / 2f, 0f, hpLabel.visualOrderText, false,
                        Font.DisplayMode.SEE_THROUGH, FULL_LIGHT, WHITE, 0, 0,
                    )
                    if (tick - hs.dmgTick in 0..40 && hs.dmgAmount > 0f) {
                        val dmgInt = (hs.dmgAmount + 0.5f).toInt()
                        val popup = Component.literal("§c-$dmgInt")
                        collector.submitText(
                            matrices, w / 2f + 2f, 0f, popup.visualOrderText, false,
                            Font.DisplayMode.SEE_THROUGH, FULL_LIGHT, RED_COLOR, 0, 0,
                        )
                    }
                } finally {
                    matrices.popPose()
                }
            } catch (_: Throwable) {}
        }

        // --- alert marker: when the playhead crosses an alert for this player, float a red ⚠ label
        //     above the name tag (and above the health line when it's shown) so the moment a cheat
        //     fired is visible on the ghost itself ---
        if (alertLabel != null) {
            try {
                val alert = Component.literal("§c⚠ §f$alertLabel")
                val baseY = if (laid) 0.95 else if (s.pose == ReplayBuffer.POSE_SNEAK) 2.15 else 2.5
                val ay = baseY + if (healthOn) 0.7f else 0.35f
                matrices.pushPose()
                try {
                    matrices.translate(s.x.toDouble(), (s.y + ay).toDouble(), s.z.toDouble())
                    val dx = (s.x + oxF) - camPos.x.toFloat()
                    val dy = (s.y + ay + oyF) - camPos.y.toFloat()
                    val dz = (s.z + ozF) - camPos.z.toFloat()
                    val dist = sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()
                    val scale = (0.025f * (dist / 8f)).coerceIn(0.015f, 0.09f)
                    matrices.rotate(camera.orientation)
                    matrices.scale(scale, -scale, scale)
                    val w = tr.width(alert)
                    collector.submitText(
                        matrices, -w / 2f, 0f, alert.visualOrderText, false,
                        Font.DisplayMode.SEE_THROUGH, FULL_LIGHT, RED_COLOR, 0, 0,
                    )
                } finally {
                    matrices.popPose()
                }
            } catch (_: Throwable) {}
        }
    }

    /**
     * Real-player-model path (gated by [IustitiaConfig.replayPlayerModels], default on).
     *
     * Drives vanilla's [AvatarRenderer] through its normal [EntityRenderer.submit] entry point with a
     * hand-built [AvatarRenderState] and the player's REAL skin ([dev.iustitia.render.ReplaySkins.resolve]
     * — a per-UUID cache that reads the tab-list [net.minecraft.client.multiplayer.PlayerInfo] vanilla
     * already keeps warm; Steve/Alex fallback for players not in the tab list). Because `submit` is the
     * real pipeline, this now also gets:
     *
     *  * the body rotation (`setupRotations`: `180 - bodyRot`), the `-1,-1,1` flip, the
     *    `translate(0,-1.501,0)` seat offset **and** the avatar's own `0.9375` scale, all from vanilla;
     *  * the sneak body offset (`AvatarRenderer.getRenderOffset`), applied below;
     *  * the armor + held-item feature renderers, driven from the render state's equipment stacks and
     *    per-hand [net.minecraft.client.renderer.item.ItemStackRenderState]s (resolved through
     *    `Minecraft.getItemModelResolver()` with a no-entity [GhostHeldItemContext]);
     *  * the hurt red-flash, from `state.hasRedOverlay` (vanilla's `getOverlayCoords`).
     *
     * 26.3 renames applied here: `light` → `lightCoords`, `invisible` → `isInvisible`, `hurt` →
     * `hasRedOverlay`, `bodyYaw` → `bodyRot`, `relativeHeadYaw`/`pitch` → `yRot`/`xRot`,
     * `age` → `ageInTicks`, `limbSwingAmplitude`/`limbSwingAnimationProgress` →
     * `walkAnimationSpeed`/`walkAnimationPos`, `handSwingProgress` → `swingAnimation`,
     * `isInSneakingPose` → `isCrouching`, `isGliding` → `isFallFlying`, `isSwimming` →
     * `isVisuallySwimming`, `usingRiptide` → `isAutoSpinAttack`, `Pose.GLIDING` → `Pose.FALL_FLYING`.
     *
     * Returns true if the model was submitted (caller skips the box outline); false (toggle off,
     * renderer unavailable, or any throw) → caller falls back to the box outline.
     */
    @Volatile
    private var playerRenderer: AvatarRenderer<*>? = null

    /** Per-ghost rolling walk-animation state. Vanilla drives a player's legs from a `LimbAnimator`
     *  that the entity ticks; a replay ghost has no entity, so we derive the equivalent amplitude +
     *  phase from the horizontal position delta between consecutive snaps. `lastX/lastZ` track the
     *  previous snap (NaN sentinel = first frame for this uuid); `lastTickDist` is the per-tick
     *  horizontal distance (held between ticks so the stride doesn't collapse when the snap is static
     *  for the 3 frames between playhead ticks); `amp` lerps toward the clamped-speed target each
     *  frame (smooth ramp up/down); `phase` advances every frame scaled by amplitude (a smooth, not
     *  20-tps-stepped, leg swing). Dist is capped so a seek/teleport never flings the stride. The map
     *  survives across the replay session; cap-bounded in practice by player count. */
    private class WalkState {
        var lastX: Float = Float.NaN
        var lastZ: Float = Float.NaN
        var lastTick: Int = Int.MIN_VALUE
        var lastTickDist: Float = 0f
        var amp: Float = 0f
        var phase: Float = 0f
    }
    private val walkState = ConcurrentHashMap<java.util.UUID, WalkState>()

    /** Per-ghost rolling health for the [IustitiaConfig.clipHealthIndicator] damage popup + the
     *  smoothed displayed number: the last snap's RAW health (to detect a drop → popup), the smoothed
     *  [displayHealth] that lerps toward the raw target once per playhead tick (so the server's
     *  coarse/batched health sync ramps instead of snapping 3→18→7), + the last detected damage
     *  amount + the tick it happened (the `-dmg` popup shows for ~40 ticks). Survives across the
     *  replay session; bounded in practice by player count. Render-thread only (drawHumanoid is
     *  single-threaded). */
    private class HealthState {
        /** Last RAW target health (drop detection → -dmg popup). */
        var lastHealth: Float = Float.NaN
        /** Smoothed displayed health — lerps toward [lastHealth]'s target once per playhead tick.
         *  NaN until the first sighting (then snaps to the raw value). */
        var displayHealth: Float = Float.NaN
        /** Last playhead tick the smoothing was stepped on (gates the per-tick lerp — FPS-independent). */
        var displayTick: Int = Int.MIN_VALUE
        var dmgAmount: Float = 0f
        var dmgTick: Int = Int.MIN_VALUE
    }
    private val healthState = ConcurrentHashMap<java.util.UUID, HealthState>()

    // v8 ghost-equipment: registry-id-string → ItemStack cache. The same few items repeat across
    // ticks + players, so memoizing avoids re-resolving `BuiltInRegistries.ITEM.getValue` every ghost
    // every frame. Render-thread only (drawModel is single-threaded); bounded by the (small) set of
    // distinct item ids ever observed in a clip. ItemStack.EMPTY for blank / unknown / AIR ids.
    private val stackCache = HashMap<String, ItemStack>()
    private fun stackFor(id: String): ItemStack {
        if (id.isBlank()) return ItemStack.EMPTY
        stackCache[id]?.let { return it }
        val stack = try {
            val item = BuiltInRegistries.ITEM.getValue(Identifier.parse(id))
            if (item === Items.AIR) ItemStack.EMPTY else ItemStack(item)
        } catch (_: Throwable) { ItemStack.EMPTY }
        stackCache[id] = stack
        return stack
    }

    // Per-tick alert-label cache (B3): the active-alert map only changes when the playhead frame
    // (frame.tick) changes — i.e. once per replay tick, not per render frame. Caching it avoids
    // rebuilding a filtered map every frame (and short-circuits to an empty map in the common
    // no-alerts case). Render-thread only (drawGhosts is single-threaded).
    private var cachedAlertTick: Int = Int.MIN_VALUE
    private var cachedAlertLabels: Map<java.util.UUID, String> = emptyMap()

    private fun drawModel(
        matrices: PoseStack,
        collector: SubmitNodeCollector,
        camera: CameraRenderState,
        s: ReplayBuffer.PlayerSnap,
        tick: Int,
    ): Boolean {
        if (!try { ConfigManager.config.replayPlayerModels } catch (_: Throwable) { false }) return false
        // v8 ghost equipment toggle (held items + armor). Additive, default on. When off the ghost
        // renders skin-only — today's behavior (slots stay empty, so the feature renderers no-op).
        val equipOn = try { ConfigManager.config.clipGhostEquipment } catch (_: Throwable) { false }
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return false
        // resolve + cache the player renderer (stable per entity type; doesn't change with world)
        val renderer = playerRenderer ?: try {
            @Suppress("UNCHECKED_CAST")
            (mc.entityRenderDispatcher.getRenderer(player) as? AvatarRenderer<*>).also { playerRenderer = it }
        } catch (_: Throwable) { null }
        if (renderer == null) return false
        // Real skin via the per-UUID cache (async-loaded by vanilla; Steve/Alex fallback). Fail-open → box.
        val skin = try { ReplaySkins.resolve(s.uuid()) } catch (_: Throwable) { return false }
        // Per-ghost walk state (rolling position-delta stride).
        val ws = walkState.computeIfAbsent(s.uuid()) { WalkState() }
        // A FRESH AvatarRenderState per ghost per frame, deliberately — NOT a per-type reuse: the
        // submitted `submitModel` node holds the state and is drained after the whole ghost loop, so a
        // shared state would be overwritten by the next player's pose before the first one drew
        // (`createRenderState()` is also the cheap half). Fail-open → box.
        val state: AvatarRenderState = try { renderer.createRenderState() } catch (_: Throwable) { return false }

        // --- populate the render state so the ghost poses, faces, walks and swings like the live
        //     player did. Field-by-field rationale:
        //       skin/scale/ageScale/visibility/lightCoords/invisible — basic posed-skin setup.
        //       hasRedOverlay drives the vanilla hurt flash (red overlay row) from the recorded hurtTime.
        //       deathTime=0 / isFullyFrozen=false — explicitly suppress the death/freeze channels.
        //       bodyRot — setupRotations reads it for the body-facing rotation (the old path had to
        //         apply `180-bodyYaw` by hand); yRot=0 + xRot=s.pitch — the head nods to the look pitch
        //         (the whole ghost faces where the player looked; the body yaw IS the look yaw).
        //       ageInTicks — running time for the subtle idle arm/body sway.
        //       walkAnimationSpeed / walkAnimationPos — walk animation, derived below from the
        //         per-uuid position delta.
        //       swingAnimation — arm-swing (attack) animation; arm poses EMPTY = natural hang. ---
        state.skin = skin
        state.scale = 1f
        state.ageScale = 1f
        state.showHat = true; state.showJacket = true
        state.showLeftPants = true; state.showRightPants = true
        state.showLeftSleeve = true; state.showRightSleeve = true
        state.showCape = true
        state.lightCoords = FULL_LIGHT
        state.isInvisible = false
        state.hasRedOverlay = s.hurtTime > 0
        state.deathTime = 0f
        state.isFullyFrozen = false
        state.bodyRot = if (s.bodyYaw != 0f) s.bodyYaw else s.yaw
        state.yRot = 0f
        state.xRot = s.pitch
        state.ageInTicks = tick.toFloat()
        state.entityType = EntityTypes.PLAYER
        state.isBaby = false
        // Walk animation from the per-uuid rolling position delta (see WalkState). The advance is
        // gated on the PLAYHEAD FRAME advancing (tick - lastTick == 1), NOT on every render frame:
        // the playhead moves one frame per replay tick at 1× (slower at 0.5/0.25×), so the legs swing
        // at the replay's tick rate. Tick-gated advance is FPS-independent and scales with replay speed.
        // Dead-zone: < 0.05 b/tick = standing still / server position noise → amp→0, legs freeze.
        // Capped dist + a seek/gap reset stop teleport/seek from flinging.
        val tickJump = tick - ws.lastTick
        when {
            tickJump == 1 -> {
                if (!ws.lastX.isNaN()) {
                    val dx = s.x - ws.lastX
                    val dz = s.z - ws.lastZ
                    ws.lastTickDist = sqrt((dx * dx + dz * dz).toDouble()).toFloat().coerceAtMost(1.5f)
                }
                ws.lastX = s.x; ws.lastZ = s.z; ws.lastTick = tick
                val walkTarget = if (ws.lastTickDist > 0.05f) (ws.lastTickDist / 0.3f).coerceIn(0f, 1f) else 0f
                ws.amp += (walkTarget - ws.amp) * 0.3f
                // ~0.94/tick at full amp → ~0.5s stride cycle sprinting, ~1.5s strolling; cadence scales
                // with distance like vanilla. Capped dist above stops a teleport/gap fling.
                ws.phase += ws.amp * 0.94f
            }
            tickJump != 0 -> {
                // seek / gap / first frame: resync position + drop amp to 0 so the stride settles
                // instead of flinging. Phase keeps its value (resumes from mid-stride).
                ws.lastX = s.x; ws.lastZ = s.z; ws.lastTick = tick; ws.lastTickDist = 0f; ws.amp = 0f
            }
            // tickJump == 0: same playhead frame redrawn → hold the pose (no per-frame leg pump).
        }
        state.walkAnimationSpeed = ws.amp
        state.walkAnimationPos = ws.phase
        // Arm swing (attack): handSwingTicks polled from the live entity + captured per snap.
        // progress = ticks/6 ≈ vanilla getHandSwingProgress over the ~6-tick swing. Arm poses EMPTY
        // so the arms hang at the sides and swing via swingAnimation when the player attacked.
        state.swingAnimation = (s.swingTicks / 6f).coerceIn(0f, 1f)
        state.leftArmPose = HumanoidModel.ArmPose.EMPTY
        state.rightArmPose = HumanoidModel.ArmPose.EMPTY
        state.mainArm = HumanoidArm.RIGHT
        when (s.pose) {
            ReplayBuffer.POSE_SNEAK -> { state.isCrouching = true; state.pose = Pose.CROUCHING }
            ReplayBuffer.POSE_GLIDE -> {
                state.isFallFlying = true
                // fallFlyingScale() clamps (ticks²/100) to 1 — a settled elytra pose needs ~10 ticks.
                state.fallFlyingTimeInTicks = 20f
                state.pose = Pose.FALL_FLYING
            }
            ReplayBuffer.POSE_SWIM -> {
                state.isVisuallySwimming = true
                state.swimAmount = 1f
                state.isInWater = true
                state.pose = Pose.SWIMMING
            }
            ReplayBuffer.POSE_RIPTIDE -> { state.isAutoSpinAttack = true; state.pose = Pose.SPIN_ATTACK }
            else -> state.pose = Pose.STANDING
        }
        // v8 ghost equipment: reconstruct the 6 recorded stacks (registry-id strings → ItemStack) and
        // populate the render state so the armor + held-item feature renderers draw them. Each step
        // fail-open — a bad id / a special-item NPE degrades to an empty slot, never breaks the frame.
        if (equipOn) {
            try {
                state.headEquipment = stackFor(s.head)
                state.chestEquipment = stackFor(s.chest)
                state.legsEquipment = stackFor(s.legs)
                state.feetEquipment = stackFor(s.feet)
                state.rightHandItemStack = stackFor(s.mainHand)
                state.leftHandItemStack = stackFor(s.offHand)
                val level = mc.level
                if (level != null) {
                    // A no-entity ItemOwner: the ghost has no live LivingEntity, so the LivingEntity
                    // overload (which requires one) is bypassed. Most item models don't touch the owner;
                    // a few special ones (trident/spyglass/custom) might NPE → contained per call.
                    val itemCtx = GhostHeldItemContext(
                        level,
                        Vec3(s.x.toDouble(), s.y.toDouble(), s.z.toDouble()),
                        if (s.bodyYaw != 0f) s.bodyYaw else s.yaw,
                    )
                    val resolver = mc.itemModelResolver
                    try {
                        resolver.updateForTopItem(
                            state.rightHandItemState, state.rightHandItemStack,
                            ItemDisplayContext.THIRD_PERSON_RIGHT_HAND, level, itemCtx, 0,
                        )
                    } catch (_: Throwable) {}
                    try {
                        resolver.updateForTopItem(
                            state.leftHandItemState, state.leftHandItemStack,
                            ItemDisplayContext.THIRD_PERSON_LEFT_HAND, level, itemCtx, 0,
                        )
                    } catch (_: Throwable) {}
                }
            } catch (_: Throwable) {}
        }

        try {
            matrices.pushPose()
            try {
                matrices.translate(s.x.toDouble(), s.y.toDouble(), s.z.toDouble())
                // The sneak body offset vanilla applies outside `submit` (the dispatcher does it for a
                // live entity; we bypass the dispatcher).
                val renderOffset = try { renderer.getRenderOffset(state) } catch (_: Throwable) { Vec3.ZERO }
                if (renderOffset.lengthSqr() > 0.0) matrices.translate(renderOffset.x, renderOffset.y, renderOffset.z)
                renderer.submit(state, matrices, collector, camera)
            } finally {
                matrices.popPose()
            }
        } catch (_: Throwable) {
            return false
        }
        return true
    }

    /** Draw an axis-aligned box outline in the CURRENT matrix frame (offset 0; caller positions).
     *  Routes through [OrderedSubmitNodeCollector.submitShapeOutline] — the 26.3 replacement for the
     *  removed `ShapeRenderer`/`VertexRendering.drawOutline` helpers. */
    private fun box(
        matrices: PoseStack,
        collector: SubmitNodeCollector,
        minx: Double, miny: Double, minz: Double,
        maxx: Double, maxy: Double, maxz: Double,
        color: Int, width: Float,
    ) {
        collector.submitShapeOutline(
            matrices,
            Shapes.create(AABB(minx, miny, minz, maxx, maxy, maxz)),
            RenderTypes.lines(),
            color,
            width,
            false,
        )
    }

    // ---- v13 non-player entity ghosts (SnapClip port) ------------------------------------------------
    //
    // A captured mob/animal is drawn through the vanilla entity renderer resolved from its captured
    // registry type (`BuiltInRegistries.ENTITY_TYPE`), driven by a render state built from its
    // prototype entity (which is what gives the model its correct dimensions/parts) plus the snap's
    // pose/yaw/health, and submitted through [EntityRenderer.submit] with the frame's real
    // [SubmitNodeCollector] — so boats/minecarts (whose renderers only know how to submit) work on
    // 26.3 without the old immediate-drain bridge. Both paths are display-only and fail-open per
    // entity; the renderer/prototype caches are keyed by type id so a scene with 20 zombies resolves
    // the renderer once. Runtime-only-verifiable (the model transforms + the submit path can only be
    // confirmed in a live client).

    /** typeId → vanilla living renderer (null when the type has no living renderer / resolution failed). */
    private val entityRendererCache = ConcurrentHashMap<String, EntityRenderer<*, *>?>()
    /** typeId → a fabricated prototype living entity (used to seed `extractRenderState`). */
    private val entityPrototypeCache = ConcurrentHashMap<String, LivingEntity?>()
    /** typeId → vanilla non-living renderer (boats/minecarts). */
    private val nonLivingRendererCache = ConcurrentHashMap<String, EntityRenderer<*, *>?>()
    /** typeId → a fabricated prototype non-living entity. */
    private val nonLivingPrototypeCache = ConcurrentHashMap<String, Entity?>()

    /** Resolve (and cache) the vanilla living renderer for a captured entity type id, or null. */
    private fun entityRendererFor(typeId: String): EntityRenderer<*, *>? {
        entityRendererCache[typeId]?.let { return it }
        val r = lookupEntityRenderer(typeId)
        entityRendererCache[typeId] = r
        return r
    }

    private fun lookupEntityRenderer(typeId: String): EntityRenderer<*, *>? = try {
        val mc = Minecraft.getInstance()
        val world = mc.level ?: return null
        val id = Identifier.tryParse(typeId) ?: return null
        if (BuiltInRegistries.ENTITY_TYPE.get(id).isEmpty) return null
        val type = BuiltInRegistries.ENTITY_TYPE.getValue(id)
        val proto = type.create(world, EntitySpawnReason.COMMAND) ?: return null
        if (proto !is LivingEntity) return null
        entityPrototypeCache[typeId] = proto
        val renderer = mc.entityRenderDispatcher.getRenderer(proto)
        renderer
    } catch (_: Throwable) { null }

    /** Resolve (and cache) the vanilla non-living renderer for a captured entity type id, or null. */
    private fun nonLivingRendererFor(typeId: String): EntityRenderer<*, *>? {
        nonLivingRendererCache[typeId]?.let { return it }
        val r = lookupNonLivingRenderer(typeId)
        nonLivingRendererCache[typeId] = r
        return r
    }

    private fun lookupNonLivingRenderer(typeId: String): EntityRenderer<*, *>? = try {
        val mc = Minecraft.getInstance()
        val world = mc.level ?: return null
        val id = Identifier.tryParse(typeId) ?: return null
        if (BuiltInRegistries.ENTITY_TYPE.get(id).isEmpty) return null
        val type = BuiltInRegistries.ENTITY_TYPE.getValue(id)
        val proto = type.create(world, EntitySpawnReason.COMMAND) ?: return null
        if (proto is LivingEntity) return null
        nonLivingPrototypeCache[typeId] = proto
        mc.entityRenderDispatcher.getRenderer(proto)
    } catch (_: Throwable) { null }

    /**
     * Draw one living entity ghost (mob/animal) at its recorded pose: resolve the renderer for its
     * type, build a render state from the prototype, overwrite the recorded pose/yaw/health, derive a
     * limb-swing from consecutive-snap movement, then submit it through the entity renderer (which
     * applies the vanilla body transform itself). Culled beyond [ENTITY_RENDER_RADIUS].
     * Fail-open (returns quietly on any missing piece).
     */
    private fun drawEntityGhost(
        matrices: PoseStack,
        collector: SubmitNodeCollector,
        camera: CameraRenderState,
        e: ReplayBuffer.EntitySnap,
        camPos: Vec3,
        offset: Vec3,
        tick: Int,
    ) {
        val ex = e.x.toDouble() + offset.x
        val ey = e.y.toDouble() + offset.y
        val ez = e.z.toDouble() + offset.z
        val dx = ex - camPos.x
        val dy = ey - camPos.y
        val dz = ez - camPos.z
        if (dx * dx + dy * dy + dz * dz > ENTITY_RENDER_RADIUS_SQ) return

        val cached = entityRendererFor(e.typeId) ?: return
        @Suppress("UNCHECKED_CAST")
        val r = cached as EntityRenderer<LivingEntity, LivingEntityRenderState>
        // A FRESH state per ghost per frame: the submit node holds it until the feature pass drains.
        val state: LivingEntityRenderState = try { r.createRenderState() } catch (_: Throwable) { return }

        val proto = entityPrototypeCache[e.typeId]
        if (proto != null) {
            try { r.extractRenderState(proto, state, 0f) } catch (_: Throwable) {}
        }

        // Overwrite the recorded channels. Name tags / leashes / fire come from the prototype's live
        // state — clear them so a ghost can't inherit a label or a rope to a prototype holder.
        state.nameTag = null
        state.scoreText = null
        state.leashStates = null
        state.displayFireAnimation = false
        state.scale = 1f
        state.ageScale = 1f
        state.lightCoords = FULL_LIGHT
        state.isInvisible = false
        state.hasRedOverlay = e.hurtTime > 0
        state.deathTime = 0f
        state.isFullyFrozen = false
        state.isBaby = false
        state.bodyRot = e.bodyYaw
        state.yRot = net.minecraft.util.Mth.wrapDegrees(e.headYaw - e.bodyYaw)
        state.xRot = e.pitch
        state.ageInTicks = tick.toFloat()
        state.isAutoSpinAttack = (e.pose == ReplayBuffer.POSE_RIPTIDE)
        when (e.pose) {
            ReplayBuffer.POSE_SNEAK -> state.pose = Pose.CROUCHING
            ReplayBuffer.POSE_GLIDE -> state.pose = Pose.FALL_FLYING
            ReplayBuffer.POSE_SWIM -> state.pose = Pose.SWIMMING
            ReplayBuffer.POSE_RIPTIDE -> state.pose = Pose.SPIN_ATTACK
            else -> state.pose = Pose.STANDING
        }

        // Movement-derived limb swing (no entity to tick a LimbAnimator): distance between consecutive
        // snaps → amplitude, integrated into phase. Reuses the players' [WalkState] keyed by uuid (a mob
        // and a player never share a uuid, so one map is fine). Seek/scrub (tickJump != 1) resets to 0.
        val ws = walkState.computeIfAbsent(e.uuid()) { WalkState() }
        val tickJump = tick - ws.lastTick
        when {
            tickJump == 1 -> {
                if (!ws.lastX.isNaN()) {
                    val ddx = e.x - ws.lastX
                    val ddz = e.z - ws.lastZ
                    ws.lastTickDist = sqrt((ddx * ddx + ddz * ddz).toDouble()).toFloat().coerceAtMost(1.5f)
                }
                ws.lastX = e.x; ws.lastZ = e.z; ws.lastTick = tick
                val walkTarget = if (ws.lastTickDist > 0.05f) (ws.lastTickDist / 0.3f).coerceIn(0f, 1f) else 0f
                ws.amp += (walkTarget - ws.amp) * 0.3f
                ws.phase += ws.amp * 0.94f
            }
            tickJump != 0 -> {
                ws.lastX = e.x; ws.lastZ = e.z; ws.lastTick = tick; ws.lastTickDist = 0f; ws.amp = 0f
            }
        }
        state.walkAnimationSpeed = ws.amp
        state.walkAnimationPos = ws.phase

        try {
            matrices.pushPose()
            try {
                matrices.translate(ex, ey, ez)
                r.submit(state, matrices, collector, camera)
            } finally {
                matrices.popPose()
            }
        } catch (_: Throwable) {
        }
    }

    /**
     * Draw one non-living entity ghost (boat/minecart) at its recorded pose via the vanilla entity
     * renderer + the frame's submit collector (the renderer submits its model; the collector drains it).
     * Culled beyond [ENTITY_RENDER_RADIUS]. Fail-open.
     */
    private fun drawNonLivingEntityGhost(
        matrices: PoseStack,
        collector: SubmitNodeCollector,
        camera: CameraRenderState,
        e: ReplayBuffer.EntitySnap,
        camPos: Vec3,
        offset: Vec3,
        tick: Int,
    ) {
        val ex = e.x.toDouble() + offset.x
        val ey = e.y.toDouble() + offset.y
        val ez = e.z.toDouble() + offset.z
        val dx = ex - camPos.x
        val dy = ey - camPos.y
        val dz = ez - camPos.z
        if (dx * dx + dy * dy + dz * dz > ENTITY_RENDER_RADIUS_SQ) return

        val cached = nonLivingRendererFor(e.typeId) ?: return
        @Suppress("UNCHECKED_CAST")
        val r = cached as EntityRenderer<Entity, EntityRenderState>
        // A FRESH state per ghost per frame — it is retained by the submitted node until drain time.
        val state: EntityRenderState = try { r.createRenderState() } catch (_: Throwable) { return }

        val proto = nonLivingPrototypeCache[e.typeId]
        if (proto != null) {
            try { r.extractRenderState(proto, state, 0f) } catch (_: Throwable) {}
        }

        state.nameTag = null
        state.scoreText = null
        state.leashStates = null
        state.displayFireAnimation = false
        state.passengerOffset = null
        state.x = e.x.toDouble()
        state.y = e.y.toDouble()
        state.z = e.z.toDouble()
        state.lightCoords = FULL_LIGHT
        state.ageInTicks = tick.toFloat()
        state.isInvisible = false

        when (state) {
            is BoatRenderState -> state.yRot = e.bodyYaw
            is MinecartRenderState -> {
                state.yRot = e.bodyYaw
                // The prototype cart's new-behaviour lerp data is anchored at the prototype's position,
                // which we just discarded — fall back to the legacy straight-line transform (which only
                // uses `yRot`) instead of translating by a stale renderPos.
                state.isNewRender = false
                state.renderPos = null
                state.posOnRail = null
                state.frontPos = null
                state.backPos = null
            }
        }

        try {
            matrices.pushPose()
            try {
                matrices.translate(ex, ey, ez)
                r.submit(state, matrices, collector, camera)
            } finally {
                matrices.popPose()
            }
        } catch (_: Throwable) {
        }
    }

    /** Drop every replay-scoped ghost cache (called from [ReplayState.stop]). Idempotent.
     *  The renderer/prototype caches are the important ones: their entries are fabricated
     *  against the `mc.level`/dispatcher live at LOOKUP time, so a clip played in a
     *  different world/dimension after this replay would keep rendering ghosts with
     *  renderers + prototype entities bound to the previous world.
     *  Render-thread only (stop is client-thread; the maps are either thread-safe or only
     *  touched on this thread, and they are empty again by the next replay's first frame). */
    fun clearGhostCaches() {
        try {
            walkState.clear()
            healthState.clear()
            entityRendererCache.clear()
            entityPrototypeCache.clear()
            nonLivingRendererCache.clear()
            nonLivingPrototypeCache.clear()
            stackCache.clear()
            playerRenderer = null
            cachedAlertLabels = emptyMap()
            cachedAlertTick = Int.MIN_VALUE
        } catch (_: Throwable) {}
    }

    private fun tierColor(tier: FlagHistory.Tier): Int = when (tier) {
        FlagHistory.Tier.RED -> RED_COLOR
        FlagHistory.Tier.YELLOW -> YELLOW_COLOR
        FlagHistory.Tier.GREEN -> GREEN_COLOR
    }

    /** Bottom-center replay banner: focus name + speed + a progress bar.
     *  2D drawing now goes through [GuiGraphicsExtractor] (`fill`/`text` replace the removed
     *  `GuiGraphics` + `drawTextWithShadow`; the scaled screen size comes from `guiWidth()/guiHeight()`
     *  instead of `Window.scaledWidth/scaledHeight`). */
    private fun renderHud(graphics: GuiGraphicsExtractor) {
        val mc = Minecraft.getInstance()
        if (mc.gui.screen() != null) return
        val tr = mc.font
        val speed = ReplayState.currentSpeed()
        val paused = ReplayState.isPaused()
        val focus = ReplayState.focusName() ?: L10n.s("iustitia.render.replayScene")
        val pauseTxt = if (paused) "§e⏸ " else ""
        val line = L10n.t("iustitia.render.replayStatus", focus, pauseTxt, NumFmt.d(digits = 2, v = speed))
        val padX = 6
        val sw = graphics.guiWidth()
        val sh = graphics.guiHeight()
        val lineW = tr.width(line)
        val barW = 160
        val panelW = maxOf(lineW, barW) + padX * 2
        val x = (sw - panelW) / 2
        val y = sh - 30
        graphics.fill(x, y, x + panelW, y + 22, BG)
        graphics.text(tr, line, x + padX, y + 3, WHITE, true)
        // Progress bar.
        val bx = x + padX
        val by = y + 15
        graphics.fill(bx, by, bx + barW, by + 4, BAR_BG)
        val prog = ReplayState.progress()
        if (prog > 0f) graphics.fill(bx, by, bx + (barW * prog).toInt().coerceIn(0, barW), by + 4, BAR_FG)
        // Alert markers on the bar: one tick per captured alert, colored by the offender's tier,
        // positioned where the alert sits in the window. Capped so a busy replay doesn't paint a
        // solid bar. Fail-open: a bad marker never breaks the HUD.
        try {
            val range = ReplayState.windowTickRange()
            val alerts = ReplayState.alerts()
            if (range != null && alerts.isNotEmpty()) {
                val span = (range.last - range.first).coerceAtLeast(1)
                val cap = 40
                var drawn = 0
                for (a in alerts) {
                    if (drawn >= cap) break
                    val frac = ((a.tick - range.first).toFloat() / span).coerceIn(0f, 1f)
                    val mx = bx + (barW * frac).toInt().coerceIn(0, barW)
                    val tier = try { FlagHistory.tierFor(a.uuid()) } catch (_: Throwable) { FlagHistory.Tier.GREEN }
                    val col = when (tier) { FlagHistory.Tier.RED -> RED_COLOR; FlagHistory.Tier.YELLOW -> YELLOW_COLOR; FlagHistory.Tier.GREEN -> GREEN_COLOR }
                    graphics.fill(mx, by - 2, mx + 1, by + 6, col)
                    drawn++
                }
            }
        } catch (_: Throwable) {}
    }

    private const val WHITE = -1
    private val BG = 0x90000000.toInt()
    private val BAR_BG = 0x50000000.toInt()
    private val BAR_FG = 0xC0FFE070.toInt()
}
