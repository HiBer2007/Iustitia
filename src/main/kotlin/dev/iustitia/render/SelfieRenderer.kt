package dev.iustitia.render

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.Screenshot
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.util.Util
import java.io.File

/**
 * Captures the offender-selfie framebuffer (Phase B). Registers [LevelRenderEvents.END_MAIN] (the 26.3
 * successor of the removed `WorldRenderEvents.END_MAIN`), which fires on the render thread at the END
 * of the main world render pass — i.e. in the SAME frame [dev.iustitia.mixin.CameraMixin] repointed the
 * camera at the offender, and BEFORE the HUD is drawn, so the screenshot is a clean world view of the
 * offender with no hotbar/chat overlay.
 *
 * ## 26.3 capture path
 *
 * `MinecraftClient.framebuffer` and `Screenshot.saveScreenshot(...)` no longer exist. The frame is
 * grabbed with [Screenshot.takeScreenshot] against `gameRenderer.mainRenderTarget()` — the same
 * `RenderTarget` vanilla's own F2 uses ([Screenshot.grab] calls into it) — which copies the colour
 * texture into a staging buffer and hands back a [net.minecraft.client.renderer.texture.NativeImage].
 * We then write that image to `snapshots/<tick>_<name>.png` on the IO pool (the old helper wrote the
 * file itself; that responsibility is ours now) and echo the vanilla "saved / error" [Component] to
 * chat (clickable file link) via `Hud.chat`. Then we clear the capture state so the camera reverts next
 * frame. Fail-open throughout; the `finally` clear is what enforces the single-frame guarantee even if
 * the GPU read throws.
 *
 * Runtime-only-verifiable: a build cannot confirm `END_MAIN` fires after the world render holds the
 * offender view, nor that the colour attachment is still readable from that callback (the copy is a
 * command-encoder op, so it can be rejected if a render pass is still open — the fail-open catch then
 * just drops the PNG). If PNGs come out blank/black, the capture point may need to move to
 * `BEFORE_GIZMOS`/`AFTER_TRANSLUCENT_FEATURES`.
 */
object SelfieRenderer {

    fun register() {
        try {
            LevelRenderEvents.END_MAIN.register(LevelRenderEvents.EndMain { _ ->
                try {
                    val req = OffenderCapture.activeReq
                    if (req == null || !OffenderCapture.frameActive) return@EndMain
                    val mc = Minecraft.getInstance()
                    val target = mc.gameRenderer.mainRenderTarget()
                    Screenshot.takeScreenshot(target, 1) { image -> save(mc, req, image) }
                } catch (_: Throwable) {
                    // The finally clears state so the camera can't stay overridden.
                } finally {
                    OffenderCapture.frameActive = false
                    OffenderCapture.activeReq = null
                }
            })
        } catch (_: Throwable) {}
    }

    /** Write [image] to the armed request's file and report the outcome to chat. IO pool, fail-open. */
    private fun save(mc: Minecraft, req: OffenderCapture.Req, image: NativeImage) {
        try {
            Util.ioPool().execute {
                val file = File(req.dir, req.file)
                try {
                    image.use { it.writeToFile(file) }
                    val link = Component.literal(file.name).withStyle(ChatFormatting.UNDERLINE)
                        .withStyle { style -> style.withClickEvent(ClickEvent.OpenFile(file.absoluteFile)) }
                    mc.gui.hud.chat.addClientSystemMessage(Component.translatable("screenshot.success", link))
                } catch (t: Throwable) {
                    try { image.close() } catch (_: Throwable) {}
                    mc.gui.hud.chat.addClientSystemMessage(
                        Component.translatable("screenshot.failure", t.message ?: "write failed"),
                    )
                }
            }
        } catch (_: Throwable) {
            try { image.close() } catch (_: Throwable) {}
        }
    }
}
