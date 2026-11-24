package dev.iustitia.render.engine

import org.slf4j.LoggerFactory
import java.lang.reflect.Method

/**
 * Optional bridge to the external **Streamproof** mod (`win.transgirls.streamproof`).
 *
 * Streamproof provides an offscreen composite target that is presented *after* the game frame, so
 * anything drawn into it never reaches the game's back buffer and therefore never appears in OBS
 * game/window capture (the "streamproof" property). We do not depend on it at build time; it is
 * reached reflectively exactly like the reference implementation in FireballAlarm does, so:
 *
 * - **Not installed** → [isReady] stays false, [beginHud]/[endHud] are no-ops, and the OVERRIDE
 *   engine still renders — it just renders visibly (through the normal HUD), i.e. Streamproof is an
 *   *option*, not a requirement.
 * - **Installed but not streaming / not ready** → same as above: we only take the offscreen path
 *   while the target is actually stream-proofed ([hudStreamproof]).
 * - **Installed and ready** → the OVERRIDE HUD is composed offscreen and is invisible to capture.
 *
 * Every call is fail-open: a missing method, a changed signature or a throwing call only disables
 * the offscreen path; it can never crash the client.
 */
object StreamproofBridge {

    private val log = LoggerFactory.getLogger("iustitia/streamproof")

    private const val API_CLASS = "win.transgirls.streamproof.api.StreamproofAPI"
    private const val CATEGORY_CLASS = "win.transgirls.streamproof.api.types.ComponentCategory"
    private const val TARGET_CLASS = "win.transgirls.streamproof.api.types.RenderTarget"

    /** Component id registered with Streamproof; also the begin/end handle. */
    const val HUD_ID = "IUSTITIA_OVERRIDE_HUD"

    @Volatile private var initialized = false
    @Volatile private var available = false

    private var addMethod: Method? = null
    private var beginMethod: Method? = null
    private var endMethod: Method? = null
    private var isReadyMethod: Method? = null
    private var isStreamproofMethod: Method? = null
    private var startMethod: Method? = null
    private var stopMethod: Method? = null
    private var guiTarget: Any? = null

    /** Resolve the (optional) API once. Safe to call from any thread; failures cache as unavailable. */
    fun init() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            initialized = true
            try {
                val api = Class.forName(API_CLASS)
                val categoryClass = Class.forName(CATEGORY_CLASS)
                val targetClass = Class.forName(TARGET_CLASS)
                addMethod = api.getMethod("add", String::class.java, String::class.java, categoryClass, Boolean::class.javaPrimitiveType)
                beginMethod = api.getMethod("begin", String::class.java)
                endMethod = api.getMethod("end", String::class.java)
                isReadyMethod = api.getMethod("isReady")
                isStreamproofMethod = api.getMethod("isStreamproof", String::class.java)
                startMethod = api.getMethod("start", targetClass)
                stopMethod = api.getMethod("stop", targetClass)
                guiTarget = enumValue(targetClass, "Gui")
                val guiCategory = enumValue(categoryClass, "Gui") ?: enumValue(categoryClass, "Hud")
                if (guiCategory != null) {
                    addMethod?.invoke(null, HUD_ID, "Iustitia Override HUD", guiCategory, true)
                    available = true
                    log.info("Streamproof API detected; Iustitia OVERRIDE will composite offscreen")
                }
            } catch (_: Throwable) {
                available = false
                log.info("Streamproof API not available; the OVERRIDE engine will render visibly")
            }
        }
    }

    private fun enumValue(enumClass: Class<*>, name: String): Any? = try {
        @Suppress("UNCHECKED_CAST")
        val ok = enumClass.isEnum
        if (!ok) null else (enumClass.enumConstants as Array<Enum<*>>).firstOrNull { it.name.equals(name, true) }
    } catch (_: Throwable) {
        null
    }

    fun isAvailable(): Boolean {
        init()
        return available
    }

    /** True while the API is present AND its offscreen composite is live. */
    fun isReady(): Boolean {
        if (!isAvailable()) return false
        return try {
            isReadyMethod?.invoke(null) == true
        } catch (_: Throwable) {
            false
        }
    }

    /** True when the Iustitia HUD component is actually being composed offscreen (stream-proofed). */
    fun hudStreamproof(): Boolean {
        if (!isReady()) return false
        return try {
            isStreamproofMethod?.invoke(null, HUD_ID) == true
        } catch (_: Throwable) {
            false
        }
    }

    fun beginHud() = invoke(beginMethod, HUD_ID)

    fun endHud() = invoke(endMethod, HUD_ID)

    /**
     * Clear the offscreen target. Called when the user switches back to INGAME so a stale OVERRIDE
     * frame cannot keep compositing on top of the game after the engine changed.
     */
    fun clearTargets() {
        if (!isReady()) return
        val target = guiTarget ?: return
        try {
            startMethod?.invoke(null, target)
            try {
                val gl = Class.forName("org.lwjgl.opengl.GL11")
                gl.getMethod("glClearColor", Float::class.javaPrimitiveType, Float::class.javaPrimitiveType, Float::class.javaPrimitiveType, Float::class.javaPrimitiveType)
                    .invoke(null, 0f, 0f, 0f, 0f)
                gl.getMethod("glClear", Int::class.javaPrimitiveType).invoke(null, 0x4000 or 0x0100)
            } catch (_: Throwable) {
                // clearing is best-effort; a failed clear only leaves one stale frame
            }
        } catch (_: Throwable) {
            // ignore
        } finally {
            try {
                stopMethod?.invoke(null, target)
            } catch (_: Throwable) {
                // ignore
            }
        }
    }

    private fun invoke(method: Method?, id: String) {
        if (method == null) return
        try {
            method.invoke(null, id)
        } catch (_: Throwable) {
            // fail-open
        }
    }
}
