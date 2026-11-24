package dev.iustitia.render.engine

import dev.iustitia.config.ConfigManager
import dev.iustitia.history.FlagHistory
import dev.iustitia.tracking.EntityTrackerManager
import dev.iustitia.tracking.TrackedPlayer
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3

/**
 * OVERRIDE render engine: the same display cues the in-world renderers draw, but **projected into
 * screen space and drawn as a HUD layer**.
 *
 * ## Why a HUD layer
 *
 * A HUD layer can be handed to the optional **Streamproof** composite ([StreamproofBridge]), which
 * presents after the game frame — so the cues never enter the game's back buffer and are absent from
 * OBS game/window capture. That is the whole point of the engine: keep the cheat markers on the
 * moderator's monitor and off the stream.
 *
 * The engine does not depend on Streamproof: without it (or with the Streamproof toggle off) the same
 * layer is drawn visibly, so switching engines never loses information — it only changes *how* the
 * cues are composed.
 *
 * ## Occlusion (遮挡解算)
 *
 * A HUD layer has no depth buffer, so the in-game depth test has to be reproduced by hand: every
 * marker is tested against world geometry with a `ClipContext` ray from the camera to the sample
 * point, and a sample whose sight line is blocked is skipped (transparent blocks such as leaves and
 * panes don't count as occluders because they have no colliding shape). The player is drawn when its
 * head, chest or feet is visible, so a target half-hidden behind a wall still reads, while one fully
 * behind it does not — matching the INGAME engine's "no wallhack" behaviour.
 *
 * ## What it draws
 *
 * - a tier-colored screen box per tracked player (red/yellow always, green only when green cues are on),
 * - the floating nameplate above the box (tier glyph + the player's native display name; on a red
 *   plate a translucent red band behind the text is the "highlight"),
 * - the foot trail as a dotted screen polyline of fading tier-colored squares,
 * - a brighter accent box on the crosshair target.
 *
 * Render-only and fail-open: no packets, no state written, every step wrapped.
 */
object OverrideHudRenderer : HudElement {

    private val ELEMENT_ID: Identifier = Identifier.fromNamespaceAndPath("iustitia", "override_hud")

    private const val GREEN = 0xFF55FF55.toInt()
    private const val YELLOW = 0xFFFFEE55.toInt()
    private const val RED = 0xFFFF5555.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val RED_PLATE = 0xB0300000.toInt()

    /** Trail samples actually drawn (the ring can hold more; keeps the polyline readable). */
    private const val MAX_TRAIL_SAMPLES = 20

    /** Occlusion rays spent on one player's trail per frame (the newest ones). */
    private const val MAX_TRAIL_RAYS = 8

    private var hadPlayers = false

    fun register() {
        try {
            HudElementRegistry.addLast(ELEMENT_ID, this)
        } catch (_: Throwable) {
            // fail-open: the OVERRIDE engine just never draws if the HUD element cannot register
        }
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, deltaTracker: DeltaTracker) {
        try {
            render(graphics)
        } catch (_: Throwable) {
            // never crash the frame
        }
    }

    private fun render(graphics: GuiGraphicsExtractor) {
        val cfg = try { ConfigManager.config } catch (_: Throwable) { return }
        if (!cfg.enabled) return
        if (EngineManager.current() != RenderEngine.OVERRIDE) {
            hadPlayers = false
            return
        }
        val mc = Minecraft.getInstance()
        val self = mc.player ?: return
        val level = mc.level ?: return
        val tracked = try { EntityTrackerManager.all() } catch (_: Throwable) { return }

        // No one tracked: clear the offscreen target once so a stale frame does not linger on stream.
        if (tracked.isEmpty()) {
            if (hadPlayers) {
                hadPlayers = false
                try { StreamproofBridge.clearTargets() } catch (_: Throwable) {}
            }
            return
        }
        hadPlayers = true

        val streamproof = EngineManager.streamproofActive()
        if (streamproof) StreamproofBridge.beginHud()
        try {
            drawAll(graphics, mc, self.uuid, tracked)
        } finally {
            if (streamproof) StreamproofBridge.endHud()
        }
    }

    private fun drawAll(
        graphics: GuiGraphicsExtractor,
        mc: Minecraft,
        selfUuid: java.util.UUID,
        tracked: Collection<TrackedPlayer>,
    ) {
        val font = mc.font
        val w = graphics.guiWidth()
        val h = graphics.guiHeight()
        val camera = try { mc.gameRenderer.mainCamera() } catch (_: Throwable) { null } ?: return
        val camPos = camera.position()
        val level = mc.level ?: return
        val ticks = try { ConfigManager.config.footTrailTicks.coerceIn(1, 40) } catch (_: Throwable) { 20 }

        for (tp in tracked) {
            try {
                val entity = tp.entity ?: continue
                if (entity.isRemoved || entity.uuid == selfUuid) continue

                val tier = tp.tier
                if (tier == FlagHistory.Tier.GREEN && !ConfigManager.config.nametagGreenEnabled) continue
                val baseColor = when (tier) {
                    FlagHistory.Tier.RED -> RED
                    FlagHistory.Tier.YELLOW -> YELLOW
                    FlagHistory.Tier.GREEN -> GREEN
                }

                val pos = entity.position()
                val px = pos.x
                val py = pos.y
                val pz = pos.z

                // --- occlusion: head / chest / feet, the player counts as visible if any passes ---
                val head = Vec3(px, py + 1.82, pz)
                val chest = Vec3(px, py + 1.05, pz)
                val feetPoint = Vec3(px, py + 0.10, pz)
                val headVisible = sightLineClear(level, camPos, head)
                val chestVisible = sightLineClear(level, camPos, chest)
                val feetVisible = sightLineClear(level, camPos, feetPoint)
                if (!headVisible && !chestVisible && !feetVisible) continue

                val feetScreen = project(mc, w, h, feetPoint)
                val headScreen = project(mc, w, h, head)
                if (feetScreen != null && headScreen != null) {
                    val halfW = (((headScreen.y - feetScreen.y).coerceAtLeast(10)) * 0.34f).coerceAtLeast(5f)
                    val cx = feetScreen.x.toFloat()
                    val topY = headScreen.y.toFloat() - 2f
                    val botY = feetScreen.y.toFloat() + 2f
                    drawBox(graphics, cx - halfW, topY, cx + halfW, botY, baseColor)

                    // Crosshair target accent: a second box one pixel out.
                    val hit = mc.hitResult
                    if (hit is net.minecraft.world.phys.EntityHitResult && hit.entity?.uuid == tp.uuid) {
                        drawBox(graphics, cx - halfW - 1.5f, topY - 1.5f, cx + halfW + 1.5f, botY + 1.5f, WHITE)
                    }

                    // --- nameplate: glyph + native display name, above the head ---
                    val glyph = when (tier) {
                        FlagHistory.Tier.RED -> "X"
                        FlagHistory.Tier.YELLOW -> "!"
                        FlagHistory.Tier.GREEN -> "+"
                    }
                    val native = try { entity.displayName } catch (_: Throwable) { Component.empty() }
                    val label = Component.empty()
                        .append(Component.literal("[$glyph] ").withStyle { it.withColor(baseColor) })
                        .append(native)
                    val textW = font.width(label)
                    val textX = (cx - textW / 2f).toInt()
                    val textY = (topY - 11f).toInt().coerceAtLeast(2)
                    if (tier == FlagHistory.Tier.RED) {
                        graphics.fill(textX - 2, textY - 1, textX + textW + 2, textY + 9, RED_PLATE)
                    }
                    graphics.text(font, label, textX, textY, if (tier == FlagHistory.Tier.RED) RED else baseColor)
                }

                // --- foot trail: fading squares along the recorded ring ---
                drawTrail(graphics, mc, level, camPos, tp, baseColor, ticks, w, h)
            } catch (_: Throwable) {
                // skip one player, keep the rest
            }
        }
    }

    private fun drawTrail(
        graphics: GuiGraphicsExtractor,
        mc: Minecraft,
        level: net.minecraft.world.level.Level,
        camPos: Vec3,
        tp: TrackedPlayer,
        color: Int,
        ticks: Int,
        w: Int,
        h: Int,
    ) {
        val total = minOf(ticks, MAX_TRAIL_SAMPLES)
        if (total <= 1) return
        var index = 0
        var rays = 0
        var visible = true
        tp.ring.forEachRecent(total, dev.iustitia.Iustitia.tickCounter) { x, y, z ->
            val sampleIndex = index
            index++
            if (sampleIndex >= total) return@forEachRecent
            val point = Vec3(x, y + 0.12, z)
            if (rays < MAX_TRAIL_RAYS) {
                rays++
                visible = sightLineClear(level, camPos, point)
            }
            if (!visible) return@forEachRecent
            val screen = project(mc, w, h, point) ?: return@forEachRecent
            val t = sampleIndex.toFloat() / (total - 1).coerceAtLeast(1)
            val alpha = ((1f - t).coerceIn(0f, 1f) * 220f).toInt().coerceIn(0, 255)
            if (alpha <= 8) return@forEachRecent
            val argb = (alpha shl 24) or (color and 0x00FFFFFF)
            val size = if (sampleIndex == 0) 2 else 1
            graphics.fill(screen.x - size, screen.y - size, screen.x + size + 1, screen.y + size + 1, argb)
        }
    }

    // ---- geometry helpers ----------------------------------------------------------------

    private data class ScreenPos(val x: Int, val y: Int)

    /**
     * World → GUI pixel projection. `GameRenderer.projectPointToScreen` returns NDC (x/y/z in -1..1
     * for points in front of the camera); z outside that range is behind the near plane or past the
     * far plane, so the point is dropped.
     */
    private fun project(mc: Minecraft, w: Int, h: Int, world: Vec3): ScreenPos? {
        val ndc = try { mc.gameRenderer.projectPointToScreen(world) } catch (_: Throwable) { return null }
        if (ndc.z < -1.0 || ndc.z > 1.0) return null
        val sx = ((ndc.x * 0.5 + 0.5) * w).toInt()
        val sy = ((1.0 - (ndc.y * 0.5 + 0.5)) * h).toInt()
        if (sx < -w / 2 || sx > w * 3 / 2 || sy < -h / 2 || sy > h * 3 / 2) return null
        return ScreenPos(sx, sy)
    }

    /**
     * True when nothing solid sits between the camera and [point]. Transparent, non-colliding blocks
     * (leaves, glass panes, grass) don't occlude because they contribute no colliding shape.
     */
    private fun sightLineClear(level: net.minecraft.world.level.Level, from: Vec3, to: Vec3): Boolean = try {
        // CollisionContext.empty() = "no entity", i.e. only world geometry can block the line.
        val hit = level.clip(
            ClipContext(
                from, to,
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                net.minecraft.world.phys.shapes.CollisionContext.empty(),
            ),
        )
        hit.type == HitResult.Type.MISS
    } catch (_: Throwable) {
        true
    }

    private fun drawBox(graphics: GuiGraphicsExtractor, x1: Float, y1: Float, x2: Float, y2: Float, color: Int) {
        val left = x1.toInt()
        val top = y1.toInt()
        val right = x2.toInt()
        val bottom = y2.toInt()
        if (right <= left || bottom <= top) return
        graphics.fill(left, top, right, top + 1, color)
        graphics.fill(left, bottom, right, bottom + 1, color)
        graphics.fill(left, top, left + 1, bottom, color)
        graphics.fill(right, top, right + 1, bottom, color)
    }
}
