package dev.iustitia.ui

import dev.iustitia.i18n.L10n
import dev.iustitia.keybind.Keybinds
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.KeyMapping
import net.minecraft.network.chat.Component

/**
 * #14 (keybind hub): a manual-render screen listing every Iustitia keybind, its currently bound
 * key, and a one-line description. Any bind whose key is also held by another (non-identical)
 * keybinding is highlighted `§c` red with the conflicting binding's name, so the user knows to
 * rebind before it collides at runtime. Display-only — rebinds happen in vanilla Controls →
 * Iustitia (the binds are standard [net.minecraft.client.KeyMapping]s). Fail-open.
 */
class KeybindHubScreen(private val parent: Screen?) : Screen(TITLE) {

    /** Per-bind conflict: the other keybinding sharing this bind's key, or null if clean. */
    private data class Row(val bind: Keybinds.Bind, val conflict: KeyMapping?)

    private var rows: List<Row> = emptyList()

    override fun isPauseScreen(): Boolean = false

    /**
     * 26.3 draws the blurred in-world menu background behind a screen that is not `isInGameUi`-
     * flagged, where 1.21.11 drew the plain in-game gradient whenever a level was loaded. Re-draw
     * that gradient (vanilla's [extractTransparentBackground] fill, same ARGB values) so the hub
     * keeps its old look over the live game; with no level loaded the vanilla panorama/blur/menu
     * background still runs.
     */
    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        if (minecraft.level != null) extractTransparentBackground(graphics)
        else super.extractBackground(graphics, mouseX, mouseY, partialTick)
    }

    override fun init() {
        try { rebuild() } catch (_: Throwable) {}
    }

    private fun rebuild() {
        try {
            val client = this.minecraft ?: run { rows = emptyList(); return }
            // Every registered keybinding in the game (vanilla + mods), for conflict detection.
            // KeyMapping's bound key is protected, so compare via the public saved-key name
            // (a `key.keyboard.*`/`key.mouse.*` identifier, unique per physical key) — two binds
            // sharing a key share this string.
            val allKeys: Array<KeyMapping> = try { client.options.keyMappings } catch (_: Throwable) { emptyArray() }
            rows = Keybinds.all.map { b ->
                val myKey = try { b.keyBinding?.saveString() } catch (_: Throwable) { null }
                val conflict = if (myKey == null) null else allKeys.firstOrNull { other ->
                    b.keyBinding != null && other !== b.keyBinding &&
                        try { other.saveString() == myKey } catch (_: Throwable) { false }
                }
                Row(b, conflict)
            }
        } catch (_: Throwable) { rows = emptyList() }
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        try {
            graphics.fill(0, 0, this.width, this.height, BG)
            super.extractRenderState(graphics, mouseX, mouseY, partialTick)
            val tr = this.font
            graphics.text(tr, L10n.t("iustitia.screen.keybinds.header"), 10, 10, WHITE)
            graphics.text(tr, L10n.t("iustitia.screen.keybinds.hint"), 10, 24, WHITE)
            var y = 44
            for (r in rows) {
                try {
                    val conflictColor = r.conflict != null
                    val kb = r.bind.keyBinding
                    val keyText = if (kb == null) L10n.t("iustitia.screen.keybinds.unregistered")
                        else try { kb.translatedKeyMessage } catch (_: Throwable) { Component.literal("?") }
                    val keyStr = "§f${keyText.string}"
                    val nameColor = if (conflictColor) "§c" else "§f"
                    graphics.text(tr, Component.literal("$nameColor${r.bind.label}"), 14, y, WHITE)
                    val keyDrawn = Component.literal("§7[§r$keyStr§7]")
                    graphics.text(tr, keyDrawn, 200, y, WHITE)
                    graphics.text(tr, Component.literal("§7${r.bind.description}"), 14, y + 11, WHITE)
                    if (conflictColor && r.conflict != null) {
                        val otherName = try { Component.translatable(r.conflict.name).string }
                        catch (_: Throwable) { r.conflict.name }
                        graphics.text(tr, L10n.t("iustitia.screen.keybinds.conflict", otherName), 200, y + 11, WHITE)
                    }
                } catch (_: Throwable) {}
                y += 26
            }
        } catch (_: Throwable) {}
    }

    override fun onClose() { minecraft.gui.setScreen(parent) }

    companion object {
        private val TITLE get() = L10n.t("iustitia.screen.keybinds.title")
        private const val WHITE = -1  // opaque 0xFFFFFFFF (see PlayerSearchScreen for the gotcha)
        private val BG = 0xCC101010.toInt()
    }
}
