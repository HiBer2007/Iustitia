package dev.iustitia.render.engine

import dev.iustitia.config.ConfigManager
import dev.iustitia.history.FlagHistory
import dev.iustitia.tracking.EntityTrackerManager
import dev.iustitia.tracking.TrackedPlayer
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext
import kotlin.math.abs
import kotlin.math.hypot

/**
 * OVERRIDE render engine: the display cues projected into screen space and drawn as a HUD layer, so
 * the whole layer can be handed to the optional Streamproof composite and stay out of recordings.
 *
 * ## What it draws (1.6.1 rules)
 *
 * - **Box: crosshair target only.** A hitbox-accurate wireframe (the 12 projected edges of the
 *   entity's bounding box) is drawn only for the player the crosshair is on — exactly the INGAME
 *   engine's target-highlight semantics. It is *not* a per-player "always on" box.
 * - **Nameplates**: tier glyph + the player's native display name above the hitbox, for yellow/red
 *   players (green only when green cues are on).
 * - **Foot trail**: the fading recorded path as a dotted screen polyline.
 *
 * ## Occlusion (遮挡解算)
 *
 * Every element is gated by a world raycast ([sightLineClear]) from the camera:
 * - a player whose **head and chest** are both blocked is not drawn at all (feet-only visibility does
 *   not count — a target behind a wall must not have a floating label),
 * - a box **edge** whose midpoint is blocked is skipped, so the part of the hitbox behind cover stays
 *   hidden while the visible part still reads,
 * - a trail sample that is blocked is skipped.
 * Transparent, non-colliding blocks (leaves, panes, grass) do not occlude.
 *
 * ## Perspective (近大远小)
 *
 * Nothing is drawn at a fixed pixel size. Screen positions come from projecting the real 3D
 * coordinates, and text/trail sizes are derived from the projected size of a world block at that
 * point ([pixelsPerBlock]), so a distant player's label shrinks the way vanilla nametags do and a
 * close one grows.
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

    /** Label height in blocks — used to turn [pixelsPerBlock] into a font scale. */
    private const val LABEL_BLOCK_HEIGHT = 0.26

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
        if (mc.level == null) return
        val tracked = try { EntityTrackerManager.all() } catch (_: Throwable) { return }

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
        val camPos = try { mc.gameRenderer.mainCamera() } catch (_: Throwable) { null }?.position() ?: return
        val level: Level = mc.level ?: return
        val ticks = try { ConfigManager.config.footTrailTicks.coerceIn(1, 40) } catch (_: Throwable) { 20 }
        val targetUuid = (mc.hitResult as? net.minecraft.world.phys.EntityHitResult)?.entity?.uuid

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

                val box: AABB = entity.boundingBox
                val headPoint = Vec3((box.minX + box.maxX) * 0.5, box.maxY - 0.2, (box.minZ + box.maxZ) * 0.5)
                val chestPoint = Vec3((box.minX + box.maxX) * 0.5, box.minY + box.ysize * 0.62, (box.minZ + box.maxZ) * 0.5)
                // Feet-only visibility does not count: if the upper body is behind cover, stay hidden.
                val headClear = sightLineClear(level, camPos, headPoint)
                val chestClear = sightLineClear(level, camPos, chestPoint)
                if (!headClear && !chestClear) continue

                val headScreen = project(mc, w, h, headPoint) ?: continue
                val ppb = pixelsPerBlock(mc, w, h, headPoint) ?: 0.0

                // --- nameplate: perspective-scaled, centred above the hitbox ---
                val glyph = when (tier) {
                    FlagHistory.Tier.RED -> "X"
                    FlagHistory.Tier.YELLOW -> "!"
                    FlagHistory.Tier.GREEN -> "+"
                }
                val native = try { entity.displayName } catch (_: Throwable) { Component.empty() }
                val label = Component.empty()
                    .append(Component.literal("[$glyph] ").withStyle { it.withColor(baseColor) })
                    .append(native)
                drawLabel(graphics, font, label, headScreen, ppb, tier == FlagHistory.Tier.RED, baseColor)

                // --- box: the crosshair target only, hitbox-accurate, per-edge occlusion ---
                if (targetUuid != null && entity.uuid == targetUuid) {
                    drawHitbox(graphics, mc, w, h, level, camPos, box, WHITE)
                }

                // --- foot trail ---
                drawTrail(graphics, mc, level, camPos, tp, baseColor, ticks, w, h)
            } catch (_: Throwable) {
                // skip one player, keep the rest
            }
        }
    }

    // ---- nameplate -----------------------------------------------------------------------

    private fun drawLabel(
        graphics: GuiGraphicsExtractor,
        font: Font,
        label: Component,
        anchor: Pair<Int, Int>,
        pixelsPerBlock: Double,
        red: Boolean,
        color: Int,
    ) {
        // Font line height is 9; render the label ~LABEL_BLOCK_HEIGHT blocks tall at that distance.
        val scale = if (pixelsPerBlock > 0.0) {
            (pixelsPerBlock * LABEL_BLOCK_HEIGHT / 9.0).coerceIn(0.15, 4.0).toFloat()
        } else {
            1f
        }
        val textW = font.width(label)
        val x = anchor.first - textW * scale / 2f
        val y = anchor.second - 12f * scale
        val m = graphics.pose()
        m.pushMatrix()
        try {
            m.translate(x, y)
            m.scale(scale, scale)
            if (red) graphics.fill(-2, -1, textW + 2, 10, RED_PLATE)
            graphics.text(font, label, 0, 0, color)
        } finally {
            m.popMatrix()
        }
    }

    // ---- hitbox wireframe ----------------------------------------------------------------

    /**
     * Draws the entity's AABB as its 12 projected edges. Each edge is skipped when its midpoint is
     * occluded, so the part of the box behind cover is not drawn.
     */
    private fun drawHitbox(
        graphics: GuiGraphicsExtractor,
        mc: Minecraft,
        w: Int,
        h: Int,
        level: Level,
        camPos: Vec3,
        box: AABB,
        color: Int,
    ) {
        val corners = arrayOf(
            Vec3(box.minX, box.minY, box.minZ), // 0
            Vec3(box.maxX, box.minY, box.minZ), // 1
            Vec3(box.minX, box.maxY, box.minZ), // 2
            Vec3(box.maxX, box.maxY, box.minZ), // 3
            Vec3(box.minX, box.minY, box.maxZ), // 4
            Vec3(box.maxX, box.minY, box.maxZ), // 5
            Vec3(box.minX, box.maxY, box.maxZ), // 6
            Vec3(box.maxX, box.maxY, box.maxZ), // 7
        )
        val screen = arrayOfNulls<Pair<Int, Int>>(8)
        for (i in corners.indices) {
            screen[i] = project(mc, w, h, corners[i])
            if (screen[i] == null) return // any corner behind the camera → skip the whole box
        }
        val edges = intArrayOf(
            0, 1, 1, 3, 3, 2, 2, 0, // bottom
            4, 5, 5, 7, 7, 6, 6, 4, // top
            0, 4, 1, 5, 2, 6, 3, 7, // verticals
        )
        var i = 0
        while (i < edges.size) {
            val a = edges[i]
            val b = edges[i + 1]
            i += 2
            val mid = Vec3(
                (corners[a].x + corners[b].x) * 0.5,
                (corners[a].y + corners[b].y) * 0.5,
                (corners[a].z + corners[b].z) * 0.5,
            )
            if (!sightLineClear(level, camPos, mid)) continue
            val sa = screen[a] ?: continue
            val sb = screen[b] ?: continue
            drawLine(graphics, sa.first, sa.second, sb.first, sb.second, color)
        }
    }

    // ---- trail ---------------------------------------------------------------------------

    private fun drawTrail(
        graphics: GuiGraphicsExtractor,
        mc: Minecraft,
        level: Level,
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
            // One cube ≈ 0.12 blocks wide, scaled by the local pixels-per-block (近大远小).
            val ppb = pixelsPerBlock(mc, w, h, point) ?: 0.0
            val half = ((ppb * 0.06).coerceIn(1.0, 5.0)).toInt()
            graphics.fill(
                screen.first - half, screen.second - half,
                screen.first + half + 1, screen.second + half + 1, argb,
            )
        }
    }

    // ---- geometry helpers ----------------------------------------------------------------

    /**
     * World → GUI pixel projection. `GameRenderer.projectPointToScreen` returns NDC (x/y/z in -1..1
     * for points in front of the camera); z outside that range is behind the near plane or past the
     * far plane.
     */
    private fun project(mc: Minecraft, w: Int, h: Int, world: Vec3): Pair<Int, Int>? {
        val ndc = try { mc.gameRenderer.projectPointToScreen(world) } catch (_: Throwable) { return null }
        if (ndc.z < -1.0 || ndc.z > 1.0) return null
        val sx = (ndc.x * 0.5 + 0.5) * w
        val sy = (1.0 - (ndc.y * 0.5 + 0.5)) * h
        if (sx < -w || sx > w * 2.0 || sy < -h || sy > h * 2.0) return null
        return sx.toInt() to sy.toInt()
    }

    /**
     * Pixels covered by one world block at [world]'s depth, measured by projecting a known vertical
     * segment — this is what makes every element scale with distance instead of using a fixed size.
     */
    private fun pixelsPerBlock(mc: Minecraft, w: Int, h: Int, world: Vec3): Double? {
        val a = project(mc, w, h, world) ?: return null
        val b = project(mc, w, h, world.add(0.0, 0.25, 0.0)) ?: return null
        val d = hypot((a.first - b.first).toDouble(), (a.second - b.second).toDouble())
        return (d * 4.0).takeIf { it.isFinite() && it > 0.05 }
    }

    /**
     * True when nothing solid sits between the camera and [point]. Transparent, non-colliding blocks
     * (leaves, panes, grass) don't occlude because they contribute no colliding shape.
     */
    private fun sightLineClear(level: Level, from: Vec3, to: Vec3): Boolean = try {
        val hit = level.clip(
            ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()),
        )
        hit.type == HitResult.Type.MISS
    } catch (_: Throwable) {
        true
    }

    /** Screen-space Bresenham line, used for the projected hitbox edges. */
    private fun drawLine(
        graphics: GuiGraphicsExtractor,
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        color: Int,
    ) {
        var x = x1
        var y = y1
        val dx = abs(x2 - x1)
        val dy = -abs(y2 - y1)
        val sx = if (x1 < x2) 1 else -1
        val sy = if (y1 < y2) 1 else -1
        var err = dx + dy
        var guard = 0
        while (true) {
            graphics.fill(x, y, x + 1, y + 1, color)
            if (x == x2 && y == y2) break
            if (++guard > 4096) break
            val e2 = err * 2
            if (e2 >= dy) {
                err += dy
                x += sx
            }
            if (e2 <= dx) {
                err += dx
                y += sy
            }
        }
    }
}
