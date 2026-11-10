package dev.iustitia.render

import dev.iustitia.Iustitia
import dev.iustitia.config.ConfigManager
import dev.iustitia.history.FlagHistory
import dev.iustitia.tracking.EntityTrackerManager
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents
import net.minecraft.client.render.RenderLayers
import net.minecraft.client.render.VertexRendering
import net.minecraft.util.math.Box
import net.minecraft.util.shape.VoxelShapes

/**
 * Foot trail (backported from the 26.3 port): a short trail of small tier-colored wireframe cubes at
 * each tracked player's feet, one per recorded tick, so a moderator can read a player's movement path
 * at a glance instead of inferring it from the live body.
 *
 * ## Why the recorded ring
 *
 * The samples come from [dev.iustitia.tracking.TrackedPlayer.ring] — the same per-tick position ring
 * the lag-compensated reach checks read — so the trail is exactly what the detector saw, not a
 * separately smoothed animation. A player who teleports shows a gap; a player who walks shows a
 * readable line. Length is capped by `footTrailTicks` (1..40).
 *
 * ## Color, size and fade
 *
 * Tier-colored: red / yellow always draw, clean (green) trails follow `footTrailGreen`. Each cube
 * starts fully opaque on the tick it is recorded and fades linearly to zero over the trail's whole
 * lifetime — it is already dimming on the frame it first appears, and it never pops out (the sample
 * drops out of the ring at the same tick its alpha reaches zero). [RenderLayers.linesTranslucent] is
 * used instead of [RenderLayers.lines] precisely because the plain line layer does not blend alpha.
 *
 * ## Rendering
 *
 * Registered on `WorldRenderEvents.AFTER_ENTITIES`, which fires on the render thread after entities
 * are drawn. The matrix there is the camera-rotation-only view matrix, so the renderer translates by
 * `-cameraPos` per sample and draws a local cube — exactly how [TargetHighlightRenderer] positions
 * itself. Depth-tested: a trail behind a wall is occluded, no wallhack. Pure render: adds no
 * detection, sends no packets, and every step is fail-open.
 */
object FootTrailRenderer {

    /** Half-size of one trail cube, in blocks (edge ≈ 0.11). */
    private const val HALF = 0.055

    /** Vertical lift so the cube sits just above the block face instead of z-fighting it. */
    private const val LIFT = 0.02

    /** Alpha of a freshly recorded cube; it ramps linearly to 0 across the trail lifetime. */
    private const val ALPHA_NEW = 0xFF

    private const val GREEN = 0xFF55FF55.toInt()
    private const val YELLOW = 0xFFFFEE55.toInt()
    private const val RED = 0xFFFF5555.toInt()

    fun register() {
        try {
            WorldRenderEvents.AFTER_ENTITIES.register(WorldRenderEvents.AfterEntities { ctx ->
                try {
                    render(ctx)
                } catch (_: Throwable) {
                    // Fail-open: a render error never crashes the frame; the trail just doesn't draw.
                }
            })
        } catch (_: Throwable) {
            // fail-open: a registration error only disables the trail
        }
    }

    private fun render(ctx: WorldRenderContext) {
        val cfg = try { ConfigManager.config } catch (_: Throwable) { return }
        if (!cfg.footTrail) return
        val ticks = cfg.footTrailTicks.coerceIn(1, 40)
        val players = try { EntityTrackerManager.all() } catch (_: Throwable) { return }
        if (players.isEmpty()) return

        val camPos = ctx.gameRenderer().camera.getCameraPos()
        val matrices = ctx.matrices()
        val lines = ctx.consumers().getBuffer(RenderLayers.linesTranslucent())
        val now = Iustitia.tickCounter

        for (tp in players) {
            try {
                val tier = tp.tier
                if (tier == FlagHistory.Tier.GREEN && !cfg.footTrailGreen) continue
                val rgb = when (tier) {
                    FlagHistory.Tier.RED -> RED
                    FlagHistory.Tier.YELLOW -> YELLOW
                    FlagHistory.Tier.GREEN -> GREEN
                }
                var index = 0
                tp.ring.forEachRecent(ticks, now) { x, y, z ->
                    matrices.push()
                    try {
                        matrices.translate(x - camPos.x, y - camPos.y, z - camPos.z)
                        // Newest sample (index 0) is fully opaque; it fades linearly to zero at the
                        // end of its lifetime, so a cube is already dimming when it first appears.
                        val t = if (ticks <= 1) 0f else (index.toFloat() / (ticks - 1)).coerceIn(0f, 1f)
                        val alpha = (ALPHA_NEW.toFloat() * (1f - t)).toInt().coerceIn(0, 255)
                        if (alpha > 0) {
                            val color = (alpha shl 24) or (rgb and 0x00FFFFFF)
                            VertexRendering.drawOutline(
                                matrices, lines,
                                VoxelShapes.cuboid(
                                    Box(-HALF, LIFT, -HALF, HALF, LIFT + HALF * 2.0, HALF),
                                ),
                                0.0, 0.0, 0.0, color, 1.0f,
                            )
                        }
                    } finally {
                        matrices.pop()
                    }
                    index++
                }
            } catch (_: Throwable) {
                // skip one player's trail, keep the rest
            }
        }
    }
}
