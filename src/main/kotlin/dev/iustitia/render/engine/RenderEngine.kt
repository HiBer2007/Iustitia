package dev.iustitia.render.engine

import dev.iustitia.config.ConfigManager
import dev.iustitia.config.IustitiaConfig

/**
 * Which render engine the display cues use.
 *
 * - [INGAME] (default): the cues are submitted into the world render pass — depth-tested 3D overlay
 *   geometry drawn by `FootTrailRenderer`, `NameplateRenderer` and `TargetHighlightRenderer`. This is
 *   the engine that shipped before 1.6.0 and is what the 26.3 port uses today.
 * - [OVERRIDE]: the same cues are **projected into screen space** and drawn as a HUD layer
 *   ([OverrideHudRenderer]). Because it is a HUD layer it can be routed through the optional
 *   **Streamproof** offscreen composite, in which case it is presented after the game frame and is
 *   invisible to OBS game/window capture. When Streamproof is off or not installed the OVERRIDE
 *   engine still runs — it simply renders visibly like any other HUD.
 *
 * The engine is a user setting ([IustitiaConfig.renderEngine]) and is also switchable at runtime with
 * the `engine` keybind.
 */
enum class RenderEngine {
    INGAME,
    OVERRIDE,
}

/** Reads/flips the engine and answers whether the streamproof path is actually live this frame. */
object EngineManager {

    private const val TAG = "iustitia/engine"

    fun current(): RenderEngine = try {
        ConfigManager.config.renderEngine
    } catch (_: Throwable) {
        RenderEngine.INGAME
    }

    fun isOverride(): Boolean = current() == RenderEngine.OVERRIDE

    /**
     * True only when ALL of these hold: the OVERRIDE engine is selected, the user kept
     * **Streamproof** enabled, and the external API reports the HUD component as stream-proofed.
     * When false the OVERRIDE engine still draws — just visibly.
     */
    fun streamproofActive(): Boolean = try {
        isOverride() && ConfigManager.config.streamproof && StreamproofBridge.hudStreamproof()
    } catch (_: Throwable) {
        false
    }

    /** Flip INGAME ⇄ OVERRIDE, persist it, and return the new engine. */
    fun toggle(): RenderEngine {
        val next = if (isOverride()) RenderEngine.INGAME else RenderEngine.OVERRIDE
        try {
            ConfigManager.config.renderEngine = next
            ConfigManager.save()
        } catch (_: Throwable) {
            // fail-open: the flip still applies for this session
        }
        if (next == RenderEngine.INGAME) StreamproofBridge.clearTargets()
        return next
    }

    /** Human-readable engine name for chat/HUD (localized by the caller). */
    fun label(): String = when (current()) {
        RenderEngine.INGAME -> "INGAME"
        RenderEngine.OVERRIDE -> "OVERRIDE"
    }
}
