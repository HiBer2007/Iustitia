package dev.iustitia.render.engine

import dev.iustitia.config.ConfigManager
import dev.iustitia.history.FlagHistory
import dev.iustitia.tracking.EntityTrackerManager
import dev.iustitia.tracking.TrackedPlayer
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.minecraft.client.MinecraftClient
import net.minecraft.client.font.TextRenderer
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.render.RenderTickCounter
import net.minecraft.client.world.ClientWorld
import net.minecraft.entity.Entity
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import net.minecraft.util.hit.EntityHitResult
import net.minecraft.util.hit.HitResult
import net.minecraft.util.math.Box
import net.minecraft.util.math.Vec3d
import net.minecraft.world.RaycastContext
import kotlin.math.abs

/**
 * OVERRIDE render engine for 1.21.11: the display cues projected into screen space and drawn as a HUD
 * layer, so the whole layer can be handed to the optional Streamproof composite and stay out of
 * recordings.
 *
 * ## What it draws (1.6.3 rules — identical to the INGAME engine)
 *
 * Every tracked player gets the same three cues the INGAME engine draws, with the same gating:
 *
 * - **Box: crosshair target only.** A hitbox-accurate wireframe (the 12 projected edges of the
 *   entity's `boundingBox`) is drawn only for the player the crosshair is on. It is *not* a
 *   per-player "always on" box. A green target follows the same `nametagGreenEnabled` opt-in as the
 *   INGAME target highlight.
 * - **Nameplate**: tier glyph + the player's native display name above the hitbox, drawn **only when
 *   the vanilla nametag is hidden** (a sneak) and never for green players — exactly the INGAME plate
 *   rule. The standing label is deliberately left to the vanilla nametag prefix (which stays visible
 *   in-game and is not streamproofed).
 * - **Foot trail**: the fading recorded path, for every tracked player; green ones follow
 *   `footTrailGreen` like the INGAME trail.
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
 * coordinates, and the text/trail sizes are derived from the projected size of a 1-block world
 * segment at that point ([pixelsPerBlock]), so a distant player's label shrinks the same way vanilla
 * nametags do and a close one grows.
 *
 * Render-only and fail-open: no packets, no state written, every step wrapped.
 */
object OverrideHudRenderer : HudElement {

    private val ELEMENT_ID = Identifier.of("iustitia", "override_hud")

    private const val GREEN = 0xFF55FF55.toInt()
    private const val YELLOW = 0xFFFFEE55.toInt()
    private const val RED = 0xFFFF5555.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val RED_PLATE = 0xB0300000.toInt()

    /** Trail samples actually drawn (the ring can hold more; keeps the polyline readable). */
    private const val MAX_TRAIL_SAMPLES = 20

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

    override fun render(context: DrawContext, tickCounter: RenderTickCounter) {
        try {
            draw(context)
        } catch (_: Throwable) {
            // never crash the frame
        }
    }

    private fun draw(graphics: DrawContext) {
        val cfg = try { ConfigManager.config } catch (_: Throwable) { return }
        if (!cfg.enabled) return
        if (EngineManager.current() != RenderEngine.OVERRIDE) {
            hadPlayers = false
            return
        }
        val mc = MinecraftClient.getInstance()
        val self = mc.player ?: return
        if (mc.world == null) return
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
        graphics: DrawContext,
        mc: MinecraftClient,
        selfUuid: java.util.UUID,
        tracked: Collection<TrackedPlayer>,
    ) {
        val font = mc.textRenderer
        val w = graphics.scaledWindowWidth
        val h = graphics.scaledWindowHeight
        val camPos = mc.gameRenderer.camera.cameraPos
        val level = mc.world as? ClientWorld ?: return
        val ticks = try { ConfigManager.config.footTrailTicks.coerceIn(1, 40) } catch (_: Throwable) { 20 }
        val targetUuid = (mc.crosshairTarget as? EntityHitResult)?.entity?.uuid

        for (tp in tracked) {
            try {
                val entity: Entity = tp.entity ?: continue
                if (entity.isRemoved || entity.uuid == selfUuid) continue

                val tier = tp.tier
                // 1.6.3: the OVERRIDE layer carries the SAME cues as INGAME (trail / target box /
                // sneak-only nameplate) for every tracked player — the only thing that differs is
                // that the self-drawn NAME LABEL is restricted to the sneak case, exactly like the
                // INGAME plates, so a standing player's label is left to the vanilla nametag prefix.
                val cfg = ConfigManager.config
                val baseColor = when (tier) {
                    FlagHistory.Tier.RED -> RED
                    FlagHistory.Tier.YELLOW -> YELLOW
                    FlagHistory.Tier.GREEN -> GREEN
                }

                val box = entity.boundingBox
                val headPoint = Vec3d((box.minX + box.maxX) * 0.5, box.maxY - 0.2, (box.minZ + box.maxZ) * 0.5)
                val chestPoint = Vec3d((box.minX + box.maxX) * 0.5, box.minY + box.lengthY * 0.62, (box.minZ + box.maxZ) * 0.5)
                // 1.6.4: NO player-level skip. Occlusion is per element — the trail hides individual
                // samples that are blocked, the box hides individual edges, and only the name label
                // needs the head/chest to be visible (it hangs above the head).
                val headClear = sightLineClear(level, camPos, headPoint)
                val chestClear = sightLineClear(level, camPos, chestPoint)

                // --- nameplate: perspective-scaled, centred above the hitbox ---
                val glyph = when (tier) {
                    FlagHistory.Tier.RED -> "X"
                    FlagHistory.Tier.YELLOW -> "!"
                    FlagHistory.Tier.GREEN -> "+"
                }
                val native = try { entity.displayName } catch (_: Throwable) { Text.empty() }
                val label = Text.empty()
                    .append(Text.literal("[$glyph] ").styled { it.withColor(baseColor) })
                    .append(native)
                // Same rule as the INGAME plates: only supplement vanilla, never double it. While the
                // server lets the vanilla nametag render (the standing case) its prefix already shows
                // the tier, so our own plate is drawn only when vanilla hides it — a sneak — and, as
                // in INGAME, a clean (green) player is never labelled.
                val sneaking = try { entity.isSneaking } catch (_: Throwable) { false }
                if (sneaking && tier != FlagHistory.Tier.GREEN && (headClear || chestClear)) {
                    val headScreen = projectRaw(mc, w, h, headPoint)
                    if (headScreen != null && onScreen(headScreen, w, h)) {
                        val ppb = pixelsPerBlock(mc, h, headPoint) ?: 0.0
                        drawLabel(graphics, font, label, headScreen, ppb, tier == FlagHistory.Tier.RED, baseColor)
                    }
                }

                // --- box: the crosshair target only, hitbox-accurate, per-edge occlusion ---
                // Green targets follow the same opt-in as the INGAME target highlight.
                if (targetUuid != null && entity.uuid == targetUuid &&
                    (tier != FlagHistory.Tier.GREEN || cfg.nametagGreenEnabled)
                ) {
                    drawHitbox(graphics, mc, w, h, level, camPos, box, WHITE)
                }

                // --- foot trail: every tracked player, green ones following the INGAME opt-in ---
                if (tier != FlagHistory.Tier.GREEN || cfg.footTrailGreen) {
                    drawTrail(graphics, mc, level, camPos, tp, baseColor, ticks, w, h)
                }
            } catch (_: Throwable) {
                // skip one player, keep the rest
            }
        }
    }

    // ---- nameplate -----------------------------------------------------------------------

    private fun drawLabel(
        graphics: DrawContext,
        font: TextRenderer,
        label: Text,
        anchor: Pair<Int, Int>,
        pixelsPerBlock: Double,
        red: Boolean,
        color: Int,
    ) {
        // Font pixel height is 9; render the label ~LABEL_BLOCK_HEIGHT blocks tall at that distance.
        val scale = if (pixelsPerBlock > 0.0) {
            (pixelsPerBlock * LABEL_BLOCK_HEIGHT / 9.0).coerceAtLeast(0.01).toFloat()
        } else {
            1f
        }
        val textW = font.getWidth(label)
        val x = anchor.first - textW * scale / 2f
        val y = anchor.second - 12f * scale
        // DrawContext carries a 2D matrix stack on 1.21.11: translate to the anchor, scale, draw at 0,0.
        val m = graphics.matrices
        m.pushMatrix()
        try {
            m.translate(x, y)
            m.scale(scale, scale)
            if (red) graphics.fill(-2, -1, textW + 2, 10, RED_PLATE)
            graphics.drawText(font, label, 0, 0, color, false)
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
        graphics: DrawContext,
        mc: MinecraftClient,
        w: Int,
        h: Int,
        level: ClientWorld,
        camPos: Vec3d,
        box: Box,
        color: Int,
    ) {
        val corners = arrayOf(
            Vec3d(box.minX, box.minY, box.minZ), // 0
            Vec3d(box.maxX, box.minY, box.minZ), // 1
            Vec3d(box.minX, box.maxY, box.minZ), // 2
            Vec3d(box.maxX, box.maxY, box.minZ), // 3
            Vec3d(box.minX, box.minY, box.maxZ), // 4
            Vec3d(box.maxX, box.minY, box.maxZ), // 5
            Vec3d(box.minX, box.maxY, box.maxZ), // 6
            Vec3d(box.maxX, box.maxY, box.maxZ), // 7
        )
        val screen = arrayOfNulls<Pair<Int, Int>>(8)
        for (i in corners.indices) {
            screen[i] = projectRaw(mc, w, h, corners[i])
            if (screen[i] == null) return // any corner behind the camera → skip the whole box
        }
        val edges = intArrayOf(
            0, 1, 1, 3, 3, 2, 2, 0, // bottom (y min)
            4, 5, 5, 7, 7, 6, 6, 4, // top (y max)
            0, 4, 1, 5, 2, 6, 3, 7, // verticals
        )
        var i = 0
        while (i < edges.size) {
            val a = edges[i]
            val b = edges[i + 1]
            i += 2
            val mid = Vec3d(
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
        graphics: DrawContext,
        mc: MinecraftClient,
        level: ClientWorld,
        camPos: Vec3d,
        tp: TrackedPlayer,
        color: Int,
        ticks: Int,
        w: Int,
        h: Int,
    ) {
        val total = minOf(ticks, MAX_TRAIL_SAMPLES)
        if (total <= 1) return
        var index = 0
        tp.ring.forEachRecent(total, dev.iustitia.Iustitia.tickCounter) { x, y, z ->
            val sampleIndex = index
            index++
            if (sampleIndex >= total) return@forEachRecent
            val point = Vec3d(x, y + 0.12, z)
            // 1.6.4: EVERY sample is occlusion-tested on its own — a point behind a wall is dropped
            // even when the player itself is visible, so the trail never draws through terrain.
            if (!sightLineClear(level, camPos, point)) return@forEachRecent
            val screen = projectRaw(mc, w, h, point) ?: return@forEachRecent
            if (!onScreen(screen, w, h)) return@forEachRecent
            val t = sampleIndex.toFloat() / (total - 1).coerceAtLeast(1)
            val alpha = ((1f - t).coerceIn(0f, 1f) * 220f).toInt().coerceIn(0, 255)
            if (alpha <= 8) return@forEachRecent
            val argb = (alpha shl 24) or (color and 0x00FFFFFF)
            // One cube ≈ 0.12 blocks wide, strictly scaled by the local pixels-per-block.
            val ppb = pixelsPerBlock(mc, h, point) ?: 0.0
            val half = ((ppb * 0.06).coerceIn(1.0, 40.0)).toInt()
            graphics.fill(
                screen.first - half, screen.second - half,
                screen.first + half + 1, screen.second + half + 1, argb,
            )
        }
    }

    // ---- geometry helpers ----------------------------------------------------------------

    /**
     * World → GUI pixel projection. `GameRenderer.project` returns NDC (x/y/z in -1..1 for points in
     * front of the camera); z outside that range is behind the near plane or past the far plane.
     */
    private fun projectRaw(mc: MinecraftClient, w: Int, h: Int, world: Vec3d): Pair<Int, Int>? {
        val ndc = try { mc.gameRenderer.project(world) } catch (_: Throwable) { return null }
        if (ndc.z < -1.0 || ndc.z > 1.0) return null
        val sx = (ndc.x * 0.5 + 0.5) * w
        val sy = (1.0 - (ndc.y * 0.5 + 0.5)) * h
        if (!sx.isFinite() || !sy.isFinite()) return null
        return sx.toInt() to sy.toInt()
    }

    /** False only when the point is clearly outside the viewport (used to skip off-screen draws). */
    private fun onScreen(pos: Pair<Int, Int>, w: Int, h: Int): Boolean =
        pos.first >= -w && pos.first <= w * 2 && pos.second >= -h && pos.second <= h * 2

    /**
     * Pixels covered by one world block at [world]'s depth, measured by projecting a known vertical
     * segment — this is what makes every element scale with distance (perspective), instead of using
     * a fixed pixel size.
     */
    private fun pixelsPerBlock(mc: MinecraftClient, screenH: Int, world: Vec3d): Double? {
        val camera = try { mc.gameRenderer.camera } catch (_: Throwable) { return null }
        val camPos = camera.cameraPos
        val forward = try {
            org.joml.Vector3f(0f, 0f, -1f).rotate(camera.rotation)
        } catch (_: Throwable) {
            return null
        }
        val depth = (world.x - camPos.x) * forward.x.toDouble() +
            (world.y - camPos.y) * forward.y.toDouble() +
            (world.z - camPos.z) * forward.z.toDouble()
        if (depth <= 0.05) return null
        val fov = try {
            // 1.21.11 keeps the projection FOV on the (private) renderer; the configured FOV option is
            // the same base value the projection is built from (sprint only adds a few degrees).
            Math.toRadians(mc.options.fov.value.toDouble())
        } catch (_: Throwable) {
            Math.toRadians(70.0)
        }
        val half = kotlin.math.tan(fov / 2.0)
        if (half <= 1e-4) return null
        return (screenH / 2.0) / (depth * half)
    }

    /**
     * True when nothing solid sits between the camera and [point]. Transparent, non-colliding blocks
     * (leaves, panes, grass) don't occlude because they contribute no colliding shape.
     */
    private fun sightLineClear(level: ClientWorld, from: Vec3d, to: Vec3d): Boolean = try {
        val hit = level.raycast(
            RaycastContext(
                from, to,
                RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE,
                net.minecraft.block.ShapeContext.absent(),
            ),
        )
        hit.type == HitResult.Type.MISS
    } catch (_: Throwable) {
        true
    }

    /** Screen-space Bresenham line, used for the projected hitbox edges. */
    private fun drawLine(graphics: DrawContext, x1: Int, y1: Int, x2: Int, y2: Int, color: Int) {
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
