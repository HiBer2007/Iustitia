package dev.iustitia.render

import dev.iustitia.config.ConfigManager
import dev.iustitia.history.FlagHistory
import dev.iustitia.tracking.EntityTrackerManager
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.network.chat.Component

/**
 * Floating nameplates (26.3 port addition): a tier-colored `[+]` / `[!]` / `[X]` glyph plus the
 * player's name, billboarded above every tracked player's head — in addition to the vanilla
 * nametag prefix, not instead of it.
 *
 * ## Why a second label
 *
 * The vanilla prefix ([dev.iustitia.mixin.PlayerEntityRendererMixin]) can only decorate a nametag
 * the server lets the client draw: a sneaking player hides their own nametag, and some servers hide
 * it entirely through team `nametagVisibility`. A moderator watching a suspected cheater should not
 * lose the tier cue the moment that player crouches, so this renderer draws its own plate from the
 * tracker state, independent of the vanilla visibility rules:
 *
 *  - RED / YELLOW plates are always drawn, including while the player sneaks (the plate is lifted
 *    slightly so it clears the crouched model).
 *  - GREEN plates follow vanilla: hidden while the player sneaks, and distance-capped, so a clean
 *    lobby is not plastered with labels.
 *
 * ## Red highlight
 *
 * Red plates are drawn at full brightness with a bright outline (`outlineColor`) — on a dark night
 * scene the outline is what makes a confirmed cheater read as "glowing" without adding a shader.
 * `nameplateRedGlow` turns the outline off.
 *
 * ## Rendering
 *
 * [LevelRenderEvents.COLLECT_SUBMITS] with [net.minecraft.client.renderer.SubmitNodeCollector.submitText]
 * (see [ReplayRenderer] for the same billboard recipe): translate to the head, rotate by the
 * camera orientation to cancel the view rotation, then scale `0.025` on X/Z and `-0.025` on Y so the
 * glyphs are upright. `SEE_THROUGH` keeps the plate readable through the model, and the text is
 * submitted at full light so it does not fade in darkness. Render-only, fail-open per player.
 */
object NameplateRenderer {

    /** Packed lightmap value: block 15 + sky 15 — the plate never dims. */
    private const val FULL_LIGHT = 0xF000F0

    /** Glyph scale — the same 0.025 vanilla uses for name tags. */
    private const val SCALE = 0.025f

    /** Vanilla hides nametags beyond 64 blocks; clean plates follow it. Suspect plates are unbounded. */
    private const val GREEN_RANGE_SQ = 64.0 * 64.0

    private const val GREEN = 0xFF55FF55.toInt()
    private const val YELLOW = 0xFFFFEE55.toInt()
    private const val RED = 0xFFFF5555.toInt()

    /** Outline for the red "glow": a bright red halo around every glyph. */
    private const val RED_OUTLINE = 0xFFFF2020.toInt()

    /** Full-plate red highlight drawn behind the text on a red-tier plate. */
    private const val RED_BACKGROUND = 0xB0300000.toInt()

    fun register() {
        try {
            LevelRenderEvents.COLLECT_SUBMITS.register(LevelRenderEvents.CollectSubmits { ctx ->
                try {
                    render(ctx)
                } catch (_: Throwable) {
                    // Fail-open: a render error never crashes the frame; no plate for this frame.
                }
            })
        } catch (_: Throwable) {
            // fail-open: a registration error only disables the plates
        }
    }

    private fun render(ctx: LevelRenderContext) {
        val cfg = try { ConfigManager.config } catch (_: Throwable) { return }
        if (!cfg.nameplates) return
        val mc = Minecraft.getInstance()
        val self = mc.player ?: return
        val font = mc.font
        val tracked = try { EntityTrackerManager.all() } catch (_: Throwable) { return }
        if (tracked.isEmpty()) return

        val cameraState = ctx.levelState().cameraRenderState
        val camPos = cameraState.pos
        val collector = ctx.submitNodeCollector()
        val pose = ctx.poseStack()

        pose.pushPose()
        try {
            pose.translate(-camPos.x, -camPos.y, -camPos.z)
            for (tp in tracked) {
                try {
                    val entity = tp.entity ?: continue
                    if (entity.isRemoved) continue
                    if (entity.uuid == self.uuid) continue

                    val tier = tp.tier
                    val sneaking = try { entity.isShiftKeyDown } catch (_: Throwable) { false }
                    // Only supplement vanilla, never duplicate it: while the server lets the vanilla
                    // nametag render (the normal standing case) the tier already shows through the
                    // nametag prefix, so our own plate would just overlap it. The plate exists for the
                    // one case vanilla hides the name — a discrete (sneaking) player — and there only
                    // suspects are worth labelling.
                    if (!sneaking || tier == FlagHistory.Tier.GREEN) continue
                    val pos = entity.position()
                    val dx = pos.x - camPos.x
                    val dy = pos.y - camPos.y
                    val dz = pos.z - camPos.z
                    val distSq = dx * dx + dy * dy + dz * dz
                    if (distSq > GREEN_RANGE_SQ) continue

                    val color: Int
                    val glyph: String
                    when (tier) {
                        // Red already gets the full-plate highlight below, so the marker only needs
                        // the warning glyph (no second 'X').
                        FlagHistory.Tier.RED -> { color = RED; glyph = "!" }
                        FlagHistory.Tier.YELLOW -> { color = YELLOW; glyph = "!" }
                        FlagHistory.Tier.GREEN -> { color = GREEN; glyph = "+" }
                    }
                    // The native display name (team prefix + rank colours included) is what the user
                    // reads in every other UI; our plate only prepends the tier glyph to it.
                    val native = try { entity.displayName } catch (_: Throwable) {
                        try { Component.literal(entity.name.string) } catch (_: Throwable) { Component.empty() }
                    }
                    val marker = Component.literal("[$glyph] ").withStyle { it.withColor(color) }
                    val label = Component.empty().append(marker).append(native)
                    // Full-bright text + a bright outline is the red "glow"; the other tiers stay
                    // plain colored text so the confirmed cheater is the one that stands out.
                    val outline = if (tier == FlagHistory.Tier.RED && cfg.nameplateRedGlow) RED_OUTLINE else 0

                    // Head position: eye height + a gap; the sneak lift keeps the plate clear of the
                    // crouched model (the crouch pose drops the head by ~0.3 blocks).
                    val headY = pos.y + (try { entity.eyeHeight.toDouble() } catch (_: Throwable) { 1.62 }) +
                        if (sneaking) 0.62 else 0.52

                    pose.pushPose()
                    try {
                        pose.translate(pos.x, headY, pos.z)
                        pose.rotate(cameraState.orientation)
                        pose.scale(SCALE, -SCALE, SCALE)
                        val text = label.visualOrderText
                        val w = font.width(text)
                        // Red = a full highlight: a red band behind the whole plate, then the text
                        // on top. Yellow/green plates stay plain so only suspects are boxed.
                        if (tier == FlagHistory.Tier.RED && cfg.nameplateRedGlow) {
                            collector.submitTextBackground(
                                pose, -w / 2f - 2f, -1f, w / 2f + 2f, font.lineHeight + 1f,
                                RED_BACKGROUND, Font.DisplayMode.SEE_THROUGH, FULL_LIGHT,
                            )
                        }
                        collector.submitText(
                            pose, -w / 2f, 0f, text, true,
                            Font.DisplayMode.SEE_THROUGH, FULL_LIGHT, color, 0, outline,
                        )
                    } finally {
                        pose.popPose()
                    }
                } catch (_: Throwable) {
                    // skip one player's plate, keep the rest
                }
            }
        } finally {
            pose.popPose()
        }
    }
}
