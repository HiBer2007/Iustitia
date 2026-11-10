package dev.iustitia.render

import dev.iustitia.config.ConfigManager
import dev.iustitia.history.FlagHistory
import dev.iustitia.tracking.EntityTrackerManager
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents
import net.minecraft.client.MinecraftClient
import net.minecraft.client.font.TextRenderer
import net.minecraft.client.render.VertexConsumerProvider
import net.minecraft.client.util.BufferAllocator
import net.minecraft.text.Style
import net.minecraft.text.Text

/**
 * Floating nameplates (backported from the 26.3 port): the tier glyph (`[+]` / `[!]` / `[X]`) plus the
 * player's **native display name** — team prefix and rank colours included — above a tracked head.
 *
 * ## It supplements vanilla, it never duplicates it
 *
 * While the server lets the vanilla nametag render (the normal standing case) the tier already shows
 * through the nametag prefix (see `PlayerEntityRendererMixin`), so a second plate would just overlap
 * it. The plate therefore appears only when vanilla hides its own nametag — a discrete (sneaking)
 * player — and there only for yellow/red tiers: exactly the case where the user asked for "even while
 * crouching you still see it".
 *
 * ## Red = full-plate highlight
 *
 * A red plate is drawn full-bright with a translucent red band behind the whole label (the
 * `backgroundColor` argument of [TextRenderer.draw]) so a confirmed cheater reads as highlighted
 * rather than merely red-lettered. Yellow/green plates stay plain so only suspects get a band.
 *
 * ## Rendering
 *
 * Registered on `WorldRenderEvents.AFTER_ENTITIES`; the text is buffered into a dedicated
 * [VertexConsumerProvider.Immediate] and flushed at `END_MAIN`, so the plates draw on top of the
 * world geometry behind them instead of being overdrawn by it (same recipe the replay ghost labels
 * use). Render-only, fail-open, no packets.
 */
object NameplateRenderer {

    private const val FULL_LIGHT = 0xF000F0
    private const val SCALE = 0.025f
    private const val GREEN_RANGE_SQ = 64.0 * 64.0

    private const val GREEN = 0xFF55FF55.toInt()
    private const val YELLOW = 0xFFFFEE55.toInt()
    private const val RED = 0xFFFF5555.toInt()

    /** Full-plate red highlight drawn behind the text on a red-tier plate (ARGB, ~69% alpha). */
    private const val RED_BACKGROUND = 0xB0300000.toInt()

    private var textVcpAlloc: BufferAllocator? = null
    private var textVcpImmediate: VertexConsumerProvider.Immediate? = null

    private fun textVcp(): VertexConsumerProvider.Immediate? = try {
        textVcpImmediate ?: run {
            val alloc = BufferAllocator(262144)
            val v = VertexConsumerProvider.immediate(alloc)
            textVcpAlloc = alloc
            textVcpImmediate = v
            v
        }
    } catch (_: Throwable) {
        null
    }

    fun register() {
        try {
            WorldRenderEvents.AFTER_ENTITIES.register(WorldRenderEvents.AfterEntities { ctx ->
                try {
                    render(ctx)
                } catch (_: Throwable) {
                    // Fail-open: a render error never crashes the frame; no plate for this frame.
                }
            })
            WorldRenderEvents.END_MAIN.register(WorldRenderEvents.EndMain { _ ->
                try { textVcpImmediate?.draw() } catch (_: Throwable) {}
            })
        } catch (_: Throwable) {
            // fail-open: a registration error only disables the plates
        }
    }

    private fun render(ctx: WorldRenderContext) {
        val cfg = try { ConfigManager.config } catch (_: Throwable) { return }
        if (!cfg.nameplates) return
        val mc = MinecraftClient.getInstance()
        val self = mc.player ?: return
        val font = mc.textRenderer
        val tracked = try { EntityTrackerManager.all() } catch (_: Throwable) { return }
        if (tracked.isEmpty()) return
        val vcp = textVcp() ?: ctx.consumers()

        val camera = ctx.gameRenderer().camera
        val camPos = camera.getCameraPos()
        val camRot = camera.getRotation()
        val matrices = ctx.matrices()

        for (tp in tracked) {
            try {
                val entity = tp.entity ?: continue
                if (entity.isRemoved) continue
                if (entity.uuid == self.uuid) continue

                val tier = tp.tier
                val sneaking = try { entity.isSneaking } catch (_: Throwable) { false }
                // Only supplement vanilla, never duplicate it — see the class doc.
                if (!sneaking || tier == FlagHistory.Tier.GREEN) continue

                // `Entity.pos` is a private field on 1.21.11 and there is no public getPos(); the
                // entity's position is the (getX, getY, getZ) triple every tracker here uses.
                val px = entity.x
                val py = entity.y
                val pz = entity.z
                val dx = px - camPos.x
                val dy = py - camPos.y
                val dz = pz - camPos.z
                if (dx * dx + dy * dy + dz * dz > GREEN_RANGE_SQ) continue

                val color: Int
                val glyph: String
                when (tier) {
                    // Tier glyphs match the nametag prefix / tab badge: + green, ! yellow, X red.
                    FlagHistory.Tier.RED -> { color = RED; glyph = "X" }
                    FlagHistory.Tier.YELLOW -> { color = YELLOW; glyph = "!" }
                    FlagHistory.Tier.GREEN -> { color = GREEN; glyph = "+" }
                }
                // The native display name (team prefix + rank colours) is what the player reads in
                // every other UI; our plate only prepends the tier glyph to it.
                val native = try { entity.displayName } catch (_: Throwable) { Text.empty() }
                val marker = Text.literal("[$glyph] ").setStyle(Style.EMPTY.withColor(color))
                val label = Text.empty().append(marker).append(native)

                // Head position: standing eye height + a gap; the sneak lift keeps the plate clear of
                // the crouched model (the crouch pose drops the head by ~0.3 blocks).
                val eye = try { entity.standingEyeHeight.toDouble() } catch (_: Throwable) { 1.62 }
                val headY = py + eye + if (sneaking) 0.62 else 0.52

                matrices.push()
                try {
                    matrices.translate(dx, headY - camPos.y, dz)
                    matrices.multiply(camRot)
                    matrices.scale(SCALE, -SCALE, SCALE)
                    val w = font.getWidth(label)
                    val bg = if (tier == FlagHistory.Tier.RED && cfg.nameplateRedGlow) RED_BACKGROUND else 0
                    font.draw(
                        label, -w / 2f, 0f, color, false,
                        matrices.peek().positionMatrix, vcp,
                        TextRenderer.TextLayerType.SEE_THROUGH, bg, FULL_LIGHT,
                    )
                } finally {
                    matrices.pop()
                }
            } catch (_: Throwable) {
                // skip one player's plate, keep the rest
            }
        }
    }
}
