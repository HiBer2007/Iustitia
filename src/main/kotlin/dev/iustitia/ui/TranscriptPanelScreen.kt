package dev.iustitia.ui

import dev.iustitia.Iustitia
import dev.iustitia.config.ConfigManager
import dev.iustitia.history.FlagHistory
import dev.iustitia.i18n.L10n
import dev.iustitia.persistence.NoteStore
import dev.iustitia.session.SessionStats
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import java.util.UUID

/**
 * #4 (transcript panel): a narrow side-panel overlay that stays open alongside the game (does not
 * pause; [isPauseScreen] = false) and shows the live transcript of a single watched player — last
 * ~15 events drawn from [SessionStats] (swings/hits/velocity) and [FlagHistory.flags] (checks
 * fired, with evidence), plus the tier, confidence score, and any moderator note. Refreshed every
 * render; toggled by the transcript keybind, `/ius transcript panel`, or `cfg.transcriptPanel`.
 *
 * This is a non-fullscreen overlay Screen — build-verifiable. Whole body fail-open; a reader error
 * shows "(unavailable)".
 */
class TranscriptPanelScreen(
    private val uuid: UUID,
    private val name: String,
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
            // Right-edge narrow panel (~150px or 1/5 of width, whichever is smaller).
            val panelW = minOf(150, w / 5)
            val x0 = w - panelW
            graphics.fill(x0, 0, w, this.height, BG)
            super.extractRenderState(graphics, mouseX, mouseY, partialTick)
            val tr = this.font
            var y = 6
            val tier = FlagHistory.tierFor(uuid)
            val glyph = when (tier) { FlagHistory.Tier.GREEN -> "§a[+]"; FlagHistory.Tier.YELLOW -> "§e[!]"; FlagHistory.Tier.RED -> "§c[X]" }
            graphics.text(tr, Component.literal("§8[§diustitia§8]"), x0 + 4, y, WHITE); y += 11
            graphics.text(tr, Component.literal("$glyph §f$name"), x0 + 4, y, WHITE); y += 11
            val score = FlagHistory.confidenceScore(uuid)
            graphics.text(tr, L10n.t("iustitia.screen.transcript.tier", tier.name, score), x0 + 4, y, WHITE); y += 13
            // session taps
            val st = SessionStats.stats(uuid)
            graphics.text(tr, L10n.t("iustitia.screen.transcript.stats", st.swings.get(), st.hits.get(), st.velocity.get()), x0 + 4, y, WHITE); y += 11
            graphics.text(tr, L10n.t("iustitia.screen.transcript.alertsFlags", FlagHistory.sessionAlertCount(uuid), FlagHistory.flagCounts(uuid).values.sum()), x0 + 4, y, WHITE); y += 13
            // note if any
            val note = NoteStore.get(uuid)
            if (note != null) {
                graphics.text(tr, L10n.t("iustitia.screen.transcript.note", NoteStore.categoryLabel(note.category)), x0 + 4, y, WHITE); y += 11
            }
            graphics.text(tr, L10n.t("iustitia.screen.transcript.lastEvents"), x0 + 4, y, WHITE); y += 11
            // Merge a small recent-events timeline: flags (with evidence) newest-first.
            val flags = FlagHistory.flags(uuid).take(15)
            if (flags.isEmpty()) {
                graphics.text(tr, L10n.t("iustitia.screen.transcript.noFlags"), x0 + 4, y, WHITE)
            } else {
                val now = Iustitia.tickCounter
                for (f in flags) {
                    val ageSec = (now - f.tick) / 20
                    val ev = f.evidence
                    // One enriched row: checkId · subLabel · measurement/threshold. The panel is
                    // ~150px; if the full row won't fit, fall back to checkId + the key number
                    // (the actionable part) so it survives over the sub-label rather than clipping.
                    val sub = ev?.subLabel
                    val numTxt = if (ev?.measurement != null || ev?.threshold != null)
                        " §b${fmt2(ev.measurement)}/${fmt2(ev.threshold)}" else ""
                    val subTxt = if (sub != null) " §8· §7$sub" else ""
                    val full = "§7${ageSec}s §f${f.checkId}$subTxt$numTxt"
                    val line = if (tr.width(full) > panelW - 8) {
                        val m = ev?.measurement?.let { " §b${fmt2(it)}" } ?: ""
                        "§7${ageSec}s §f${f.checkId}$m"
                    } else full
                    graphics.text(tr, Component.literal(line), x0 + 4, y, WHITE)
                    y += 11
                    if (y > this.height - 12) break
                }
            }
            graphics.text(tr, Component.literal("§8" + if (ConfigManager.config.compactMode) L10n.s("iustitia.screen.transcript.compact") else ""), x0 + 4, this.height - 12, WHITE)
        } catch (_: Throwable) {}
    }

    override fun onClose() { minecraft.gui.setScreen(parent) }

    companion object {
        private val TITLE get() = L10n.t("iustitia.screen.transcript.title")
        private const val WHITE = -1
        private val BG = 0xCC101010.toInt()
        private fun fmt2(v: Double?): String =
            if (v == null) "?" else String.format(java.util.Locale.US, "%.2f", v)
    }
}
