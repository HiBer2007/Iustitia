package dev.iustitia.render

import dev.iustitia.Iustitia
import dev.iustitia.config.ConfigManager
import dev.iustitia.history.FlagHistory
import dev.iustitia.tracking.EntityTrackerManager
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.shapes.Shapes

/**
 * Foot trail (26.3 port addition): a short trail of small tier-colored wireframe squares at each
 * tracked player's feet, one per recorded position, so a moderator can read a player's movement
 * path at a glance instead of inferring it from the live body.
 *
 * ## Why the recorded ring
 *
 * The samples come from [dev.iustitia.tracking.TrackedPlayer.ring] — the same per-tick position
 * ring the lag-compensated reach checks read — so the trail is exactly what the detector saw, not a
 * separately smoothed animation. A player who teleports shows a gap; a player who walks shows a
 * readable line. Length is capped by `footTrailTicks` (1..40).
 *
 * ## Color and visibility
 *
 * Tier-colored: red / yellow always draw, clean (green) trails follow `footTrailGreen` — a
 * not-yet-flagged player is precisely the one worth watching, so the default shows them too.
 * Opacity fades with sample age so the newest square is unmistakable and the tail does not clutter
 * the view.
 *
 * ## Rendering
 *
 * Registered on [LevelRenderEvents.COLLECT_SUBMITS]. Each sample is submitted under its own
 * camera-relative translate with a small local cube, because the collector stores the pose and
 * transforms the shape vertices later. Translucent lines are used so the per-sample alpha actually
 * blends (a plain line layer ignores alpha and the fade would read as a pop). Everything is
 * depth-tested, so a trail behind a wall is occluded — no wallhack. Pure render: adds no detection,
 * sends no packets, and every step is fail-open.
 */
object FootTrailRenderer {

    /** Half-size of one trail cube, in blocks (edge ≈ 0.11 — a third of the first cut's 0.32). */
    private const val HALF = 0.055

    /** Vertical lift so the cube sits just above the block face instead of z-fighting it. */
    private const val LIFT = 0.02

    /**
     * The cube starts at full opacity the tick it is recorded and fades linearly to zero over the
     * trail's whole lifetime (`footTrailTicks`), so it never pops out — the total on-screen time is
     * unchanged; only the alpha ramp is different.
     */
    private const val ALPHA_NEW = 0xFF

    private const val GREEN = 0xFF55FF55.toInt()
    private const val YELLOW = 0xFFFFEE55.toInt()
    private const val RED = 0xFFFF5555.toInt()

    fun register() {
        try {
            LevelRenderEvents.COLLECT_SUBMITS.register(LevelRenderEvents.CollectSubmits { ctx ->
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

    private fun render(ctx: LevelRenderContext) {
        val cfg = try { ConfigManager.config } catch (_: Throwable) { return }
        if (!cfg.footTrail) return
        val ticks = cfg.footTrailTicks.coerceIn(1, 40)
        val players = try { EntityTrackerManager.all() } catch (_: Throwable) { return }
        if (players.isEmpty()) return

        val collector = ctx.submitNodeCollector()
        val pose = ctx.poseStack()
        val camPos = ctx.levelState().cameraRenderState.pos
        val now = Iustitia.tickCounter
        // Translucent lines: alpha has to actually blend, otherwise every cube is either fully
        // drawn or absent and the fade reads as a pop. Depth testing is unchanged.
        val lines = RenderTypes.linesTranslucent()

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
                    // 26.3: `submitShapeOutline` takes the shape in the pose's LOCAL space (the
                    // collector stores the pose and transforms the shape vertices later), so each
                    // cube is submitted under its own camera-relative translate. Translating the
                    // shared pose by -camPos and passing world coordinates instead double-applies the
                    // camera offset and puts every square off-screen — that was the "trail never
                    // shows" bug.
                    pose.pushPose()
                    try {
                        pose.translate(x - camPos.x, y - camPos.y, z - camPos.z)
                        // Newest sample (index 0) is fully opaque; it fades linearly to zero at the
                        // end of its lifetime (the sample drops out of the ring at that same tick),
                        // so a cube is already dimming on the frame it first appears.
                        val t = if (ticks <= 1) 0f else (index.toFloat() / (ticks - 1)).coerceIn(0f, 1f)
                        val alpha = (ALPHA_NEW.toFloat() * (1f - t)).toInt().coerceIn(0, 255)
                        if (alpha <= 0) {
                            index++
                            return@forEachRecent
                        }
                        val color = (alpha shl 24) or (rgb and 0x00FFFFFF)
                        // A real little cube at the feet (not a flat square) so it reads while
                        // looking down at a walking player.
                        val box = AABB(-HALF, LIFT, -HALF, HALF, LIFT + HALF * 2.0, HALF)
                        collector.submitShapeOutline(pose, Shapes.create(box), lines, color, 1.0f, false)
                    } finally {
                        pose.popPose()
                    }
                    index++
                }
            } catch (_: Throwable) {
                // skip one player's trail, keep the rest
            }
        }
    }
}
