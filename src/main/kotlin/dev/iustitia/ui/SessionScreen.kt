package dev.iustitia.ui

import dev.iustitia.config.ConfigManager
import dev.iustitia.history.FlagHistory
import dev.iustitia.i18n.L10n
import dev.iustitia.tracking.EntityTrackerManager
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import java.util.UUID

/**
 * #12 (session screen): the dense one-screen session summary opened by `/ius session screen` and
 * the session keybind. Built on demand from the [FlagHistory] readers: tier counts over the union
 * of tracked + flagged uuids, the peak-confidence player, the top offenders by alert count, and
 * the session totals. Compact-mode renders the same data as condensed one-liners. Display-only and
 * fail-open — a reader error shows "(unavailable)" rather than crashing the screen.
 */
class SessionScreen(private val parent: Screen?) : Screen(TITLE) {

    private data class Summary(
        val total: Int, val green: Int, val yellow: Int, val red: Int,
        val totalAlerts: Int, val peakName: String?, val peakScore: Int,
        val top: List<Pair<String, Int>>,
    )

    private var summary: Summary = Summary(0, 0, 0, 0, 0, null, 0, emptyList())
    private var compact: Boolean = false

    override fun isPauseScreen(): Boolean = false

    /**
     * 26.3 draws the blurred in-world menu background behind a screen that is not `isInGameUi`-
     * flagged, where 1.21.11 drew the plain in-game gradient whenever a level was loaded. Re-draw
     * that gradient (vanilla's [extractTransparentBackground] fill, same ARGB values) so the
     * summary keeps its old look over the live game; with no level loaded the vanilla
     * panorama/blur/menu background still runs.
     */
    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        if (minecraft.level != null) extractTransparentBackground(graphics)
        else super.extractBackground(graphics, mouseX, mouseY, partialTick)
    }

    override fun init() {
        try { compact = ConfigManager.config.compactMode; rebuild() } catch (_: Throwable) {}
    }

    private fun rebuild() {
        try {
            val uuids = LinkedHashSet<UUID>()
            try { EntityTrackerManager.all().forEach { uuids.add(it.uuid) } } catch (_: Throwable) {}
            try { FlagHistory.knownUuids().forEach { uuids.add(it) } } catch (_: Throwable) {}
            var green = 0; var yellow = 0; var red = 0
            var peakName: String? = null; var peakScore = -1
            for (u in uuids) {
                when (FlagHistory.tierFor(u)) {
                    FlagHistory.Tier.GREEN -> green++
                    FlagHistory.Tier.YELLOW -> yellow++
                    FlagHistory.Tier.RED -> red++
                }
                val s = try { FlagHistory.confidenceScore(u) } catch (_: Throwable) { 0 }
                if (s > peakScore) { peakScore = s; peakName = FlagHistory.nameFor(u) ?: u.toString().take(8) }
            }
            val top = try { FlagHistory.topOffenders(8) } catch (_: Throwable) { emptyList() }
            summary = Summary(uuids.size, green, yellow, red, FlagHistory.totalAlerts, peakName, peakScore, top)
        } catch (_: Throwable) {
            summary = Summary(0, 0, 0, 0, 0, null, 0, emptyList())
        }
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        try {
            graphics.fill(0, 0, this.width, this.height, BG)
            super.extractRenderState(graphics, mouseX, mouseY, partialTick)
            val tr = this.font
            val s = summary
            graphics.text(tr, L10n.t("iustitia.screen.session.header"), 10, 10, WHITE)
            if (compact) {
                graphics.text(tr, Component.literal(
                    L10n.s("iustitia.screen.session.compact", s.total, s.green, s.yellow, s.red, s.totalAlerts) +
                    (s.peakName?.let { L10n.s("iustitia.screen.session.peak", it, s.peakScore) } ?: "")
                ), 10, 28, WHITE)
                return
            }
            var y = 30
            graphics.text(tr, L10n.t("iustitia.screen.session.playersTracked", s.total), 10, y, WHITE); y += 12
            graphics.text(tr, L10n.t("iustitia.screen.session.tierCounts", s.green, s.yellow, s.red), 10, y, WHITE); y += 16
            graphics.text(tr, L10n.t("iustitia.screen.session.alerts", s.totalAlerts), 10, y, WHITE); y += 12
            if (s.peakName != null && s.peakScore > 0) {
                graphics.text(tr, L10n.t("iustitia.screen.session.peakLine", s.peakName, s.peakScore), 10, y, WHITE); y += 16
            } else y += 8
            graphics.text(tr, L10n.t("iustitia.screen.session.topOffenders"), 10, y, WHITE); y += 12
            if (s.top.isEmpty()) {
                graphics.text(tr, L10n.t("iustitia.screen.session.noAlerts"), 14, y, WHITE)
            } else {
                s.top.forEach { (name, count) ->
                    graphics.text(tr, Component.literal(" §f$name §7$count"), 14, y, WHITE); y += 12
                }
            }
        } catch (_: Throwable) {}
    }

    override fun mouseClicked(click: MouseButtonEvent, button: Boolean): Boolean {
        try { if (super.mouseClicked(click, button)) return true } catch (_: Throwable) {}
        return false
    }

    override fun onClose() { minecraft.gui.setScreen(parent) }

    companion object {
        private val TITLE get() = L10n.t("iustitia.screen.session.title")
        private const val WHITE = -1
        private val BG = 0xCC101010.toInt()
    }
}
