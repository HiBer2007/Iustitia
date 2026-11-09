package dev.iustitia.render

import dev.iustitia.config.ConfigManager
import dev.iustitia.history.FlagHistory
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.player.RemotePlayer
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.shapes.Shapes

/**
 * On-world target-highlight overlay (Phase B): draws a tier-colored wireframe box around the OTHER
 * player the local player's crosshair is on. Render-only, fail-open, depth-tested (the lines layer
 * writes depth, so the box is occluded by intervening walls — no wallhack, consistent with the
 * nametag-visibility-respecting philosophy).
 *
 * Registered on [LevelRenderEvents.COLLECT_SUBMITS], the 26.3 replacement for the removed
 * `WorldRenderEvents.AFTER_ENTITIES`: it fires once per frame while the level's submit nodes are being
 * collected, which is the canonical place for world-space overlays — the box therefore composites
 * against entities and is occluded by closer geometry exactly like vanilla's own block outline.
 *
 * ## Positioning
 *
 * The [net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext.poseStack] handed to
 * `COLLECT_SUBMITS` is a **world-origin identity** matrix: vanilla's own `LevelRenderer.submitFeatures`
 * creates a fresh `PoseStack` there and translates each entity/block by `(pos - cameraPos)` itself.
 * So we translate by the entity's bounding-box minimum minus the camera position
 * (`levelState().cameraRenderState.pos`) and then hand [OrderedSubmitNodeCollector.submitShapeOutline]
 * a box whose origin is local to that translate — the same "camera-relative origin" discipline vanilla
 * uses, just spelled out here instead of being pre-applied.
 *
 * ## Color
 *
 * [OrderedSubmitNodeCollector.submitShapeOutline] takes the color as one ARGB int, so the colors below
 * are full-alpha ARGB (0xFFRRGGBB). Note that the collector routes a full-alpha outline into the
 * *opaque* feature phase (vanilla's own block outline does the same); depth testing is unchanged.
 * Tier-colored: red/yellow always drawn; green only when the user
 * opted into green cues (`nametagGreenEnabled`), so clean players aren't boxed by default.
 *
 * Runtime-only-verifiable: a build cannot confirm the box lands on the entity. If the box is
 * offset/wrong-tinted, the translate offset or color packing is a one-line fix.
 */
object TargetHighlightRenderer {

    fun register() {
        try {
            LevelRenderEvents.COLLECT_SUBMITS.register(LevelRenderEvents.CollectSubmits { ctx ->
                try {
                    if (!ConfigManager.config.targetHighlight) return@CollectSubmits
                    val mc = Minecraft.getInstance()
                    val hit = mc.hitResult
                    if (hit !is EntityHitResult) return@CollectSubmits
                    val entity = hit.entity
                    if (entity !is RemotePlayer) return@CollectSubmits
                    val self = mc.player
                    if (self != null && entity.uuid == self.uuid) return@CollectSubmits

                    val tier = FlagHistory.tierFor(entity.uuid)
                    if (tier == FlagHistory.Tier.GREEN && !ConfigManager.config.nametagGreenEnabled) return@CollectSubmits
                    val color = when (tier) {
                        FlagHistory.Tier.GREEN -> GREEN
                        FlagHistory.Tier.YELLOW -> YELLOW
                        FlagHistory.Tier.RED -> RED
                    }

                    val camPos = ctx.levelState().cameraRenderState.pos
                    val bb = entity.boundingBox
                    val matrices = ctx.poseStack()
                    matrices.pushPose()
                    // World-origin identity stack → translate to the entity's box in camera space.
                    matrices.translate(bb.minX - camPos.x, bb.minY - camPos.y, bb.minZ - camPos.z)
                    ctx.submitNodeCollector().submitShapeOutline(
                        matrices,
                        Shapes.create(0.0, 0.0, 0.0, bb.xsize, bb.ysize, bb.zsize),
                        RenderTypes.lines(),
                        color,
                        1.0f,
                        false,
                    )
                    matrices.popPose()
                } catch (_: Throwable) {
                    // Fail-open: a render error never crashes the client; the box just doesn't draw.
                }
            })
        } catch (_: Throwable) {}
    }
}

// Full-alpha ARGB (0xFFRRGGBB) — see class doc on color packing.
private const val RED = 0xFFFF3030.toInt()
private const val YELLOW = 0xFFFFFF30.toInt()
private const val GREEN = 0xFF30FF30.toInt()
