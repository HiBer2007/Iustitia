package dev.iustitia.mixin

import net.fabricmc.loader.api.FabricLoader
import org.objectweb.asm.tree.ClassNode
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin
import org.spongepowered.asm.mixin.extensibility.IMixinInfo

/**
 * Mixin-config plugin for Iustitia's mixin set.
 *
 * ## Why this exists
 *
 * Iustitia runs alongside its single-feature extracts (SnapClip/Scrollback/FollowCam). Most of the
 * overlapping mixins are `@Inject`/`@Accessor`, and multiple mods injecting the same method is fine
 * — they chain, and each guard no-ops while its feature is inactive. But two mixins USED to target the
 * SAME instruction with `@Redirect`:
 *
 *  - `GameRenderer.renderHand` → `Perspective.isFirstPerson()Z` (ordinal 0)
 *  - `WorldRenderer.render` → `ClientPlayerEntity.isSpectator()Z` (ordinal 0)
 *
 * SnapClip's `GameRendererMixin`/`WorldRendererMixin` redirect exactly those call sites (SnapClip is
 * derived from Iustitia), and Mixin hard-fails a class when two `@Redirect`s claim one instruction
 * ("Redirect conflict"). Skipping IUSTITIA's copies when SnapClip is present removes the collision;
 * SnapClip's copies then own the freecam-hand-hide / no-blackout cosmetics, which is exactly the
 * intended ownership (Iustitia yields freecam to SnapClip).
 *
 * ## 26.3 note — the two colliding redirects no longer exist as redirects
 *
 * The MC 26.3 port removed both target methods, so both Iustitia mixins became non-redirects:
 *  - [GameRendererMixin] is now a HEAD `@Inject` + `ci.cancel()` on `GameRenderer.renderItemInHand`
 *    (`renderHand` is gone and the first-person gate is no longer a method call);
 *  - [WorldRendererMixin] is now two HEAD `@Inject`s on `LevelRenderer.submitBlockEntities` /
 *    `submitBlockOutline` (`LevelRenderer.render` no longer reads `isSpectator()` at all).
 * The no-blackout redirect MOVED to [CameraMixin] (`Camera.extractRenderState` →
 * `LocalPlayer.isSpectator()`), where it is again a single-instruction `@Redirect`. If SnapClip's
 * 26.3 port still redirects that same call site, the collision now appears on `Camera` rather than on
 * `LevelRenderer`; [CameraMixin] cannot simply be skipped (it also drives the replay POV/FOLLOW/
 * selfie camera), so in that case the no-blackout override needs its own dedicated mixin class added
 * to the skip list below. The skip list itself is kept as-is so the "Iustitia yields freecam to
 * SnapClip" ownership is unchanged for the hand-hide / block-entity / outline overrides.
 *
 * Feature-level yielding (see [dev.iustitia.compat.CompanionMods]) can only run at runtime, after
 * classes are loaded — too late for a mixin conflict — so this plugin is the mixin-time half.
 *
 * Fail-open: if the mod lookup itself throws we return true (apply), which is the safe default when
 * SnapClip is absent. Only a confident "snapclip is loaded" skips the two cosmetic mixins.
 */
class IustitiaMixinPlugin : IMixinConfigPlugin {

    override fun onLoad(mixinPackage: String) {}

    override fun getRefMapperConfig(): String? = null

    override fun shouldApplyMixin(targetClassName: String, mixinClassName: String): Boolean = when (mixinClassName) {
        "dev.iustitia.mixin.GameRendererMixin",
        "dev.iustitia.mixin.WorldRendererMixin" -> !snapClipLoaded()
        else -> true
    }

    override fun acceptTargets(myTargets: MutableSet<String>?, otherTargets: MutableSet<String>?) {}

    override fun getMixins(): MutableList<String>? = null

    override fun preApply(targetClassName: String?, targetClass: ClassNode?, mixinClassName: String?, mixinInfo: IMixinInfo?) {}

    override fun postApply(targetClassName: String?, targetClass: ClassNode?, mixinClassName: String?, mixinInfo: IMixinInfo?) {}

    /**
     * True only when SnapClip is confidently loaded; a failed lookup returns false (apply ours).
     * Uses FabricLoader directly (not [dev.iustitia.compat.CompanionMods]) so this early mixin-time
     * path never pulls in Iustitia's wider object graph before classes are ready.
     */
    private fun snapClipLoaded(): Boolean = try {
        FabricLoader.getInstance().isModLoaded("snapclip")
    } catch (_: Throwable) {
        false
    }
}
