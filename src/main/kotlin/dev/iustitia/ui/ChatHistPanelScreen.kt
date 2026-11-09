package dev.iustitia.ui

import dev.iustitia.chathist.ChatHistory
import dev.iustitia.i18n.L10n
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import java.text.SimpleDateFormat
import java.util.Date

/**
 * `/ius chathist panel ...` — a narrow side-panel overlay that reuses the same chrome + render
 * layout as [TranscriptPanelScreen] (right-edge panel, same `0xCC101010` background, same
 * `§8[§diustitia§8]` header, `graphics.text` rows, `isPauseScreen = false` so the game keeps
 * running) but draws captured chat rows instead of flag events. The panel is a bit wider than the
 * transcript panel (~220px vs ~150px) and wraps long rows to the panel width, so a long message or
 * a long username continues on the next line instead of clipping off the right edge. Refreshed
 * every render via [rowsProvider], so new messages appear live while the panel is open.
 *
 * The row set is supplied as a `() -> List<ChatHistory.Row>` so the panel stays live without the
 * command having to re-open it: each render re-runs the query (a filtered copy of the in-memory
 * per-server bucket — cheap, packet-driven data). [limit] caps how many of the newest rows are
 * drawn (the `pageamount` argument; default 15, matching [TranscriptPanelScreen]'s `take(15)`).
 * Whole body fail-open; a reader error shows "(unavailable)".
 */
class ChatHistPanelScreen(
    private val subtitle: String,
    private val rowsProvider: () -> List<ChatHistory.Row>,
    private val limit: Int,
    private val parent: Screen?,
) : Screen(TITLE) {

    override fun isPauseScreen(): Boolean = false

    /**
     * 26.3 draws the blurred in-world menu background behind a screen that is not `isInGameUi`-
     * flagged, where 1.21.11 drew the plain in-game gradient whenever a level was loaded. Re-draw
     * that gradient (vanilla's [extractTransparentBackground] fill, same ARGB values) so the panel
     * keeps its old look over the live game; with no level loaded the vanilla panorama/blur/menu
     * background still runs.
     */
    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        if (minecraft.level != null) extractTransparentBackground(graphics)
        else super.extractBackground(graphics, mouseX, mouseY, partialTick)
    }

    override fun init() { try {} catch (_: Throwable) {} }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        try {
            val w = this.width
            // A bit wider than the transcript panel (which is ~150px) so usernames + wrapped
            // messages read comfortably; still a narrow right-edge overlay. Wrapping (below)
            // handles anything longer, so this is about comfort, not fitting one line.
            val panelW = minOf(220, w / 4)
            val x0 = w - panelW
            graphics.fill(x0, 0, w, this.height, BG)
            super.extractRenderState(graphics, mouseX, mouseY, partialTick)
            val tr = this.font
            var y = 6
            graphics.text(tr, Component.literal("§8[§diustitia§8]"), x0 + 4, y, WHITE); y += 11
            graphics.text(tr, Component.literal("§8chathist"), x0 + 4, y, WHITE); y += 11
            graphics.text(tr, Component.literal("§f$subtitle"), x0 + 4, y, WHITE); y += 13
            val rows = rowsProvider().take(limit)
            graphics.text(tr, L10n.t("iustitia.screen.chatHist.lastMsg", rows.size), x0 + 4, y, WHITE); y += 11
            val maxW = panelW - 8
            if (rows.isEmpty()) {
                graphics.text(tr, L10n.t("iustitia.screen.chatHist.empty"), x0 + 4, y, WHITE)
            } else {
                for (r in rows) {
                    val ts = try { FMT.format(Date(r.wallClockMs)) } catch (_: Throwable) { "??:??:??" }
                    val full = "§8[$ts] §7${r.name}§r ${r.text}"
                    // Wrap to the panel width so a long message (or a long username) continues on
                    // the next line instead of clipping off the right edge. The timestamp + name
                    // stay on the first wrapped line; continuation lines sit flush-left under them.
                    val wrapped = tr.split(Component.literal(full), maxW)
                    for (ln in wrapped) {
                        if (y > this.height - 12) return
                        graphics.text(tr, ln, x0 + 4, y, WHITE)
                        y += 11
                    }
                    y += 1 // small gap between messages for readability
                }
            }
        } catch (_: Throwable) {
            try {
                val tr = this.font
                graphics.text(tr, L10n.t("iustitia.screen.chatHist.unavailable"), 4, 4, WHITE)
            } catch (_: Throwable) {}
        }
    }

    override fun onClose() { minecraft.gui.setScreen(parent) }

    companion object {
        private val TITLE get() = L10n.t("iustitia.screen.chatHist.title")
        private const val WHITE = -1
        private val BG = 0xCC101010.toInt()
        private val FMT = SimpleDateFormat("HH:mm:ss")
    }
}
