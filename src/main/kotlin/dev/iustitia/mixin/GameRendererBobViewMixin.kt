package dev.iustitia.mixin

import dev.iustitia.replay.ReplayState
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.client.renderer.state.level.CameraRenderState
import com.mojang.blaze3d.vertex.PoseStack
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

/**
 * FREECAM: kill the walking view-bob while the detached camera is flying (Task: freecam polish).
 * While [ReplayState.freecamActive] the camera is detached from the local player and WASD drives the
 * pose, not the player — but vanilla `GameRenderer.bobView` still applies the bob offset to the view
 * matrix whenever the player's horizontal speed is non-zero (and the user has View Bobbing on), so
 * the view bobs as if walking even though you're free-flying. Cancelling `bobView` at HEAD that frame
 * skips the matrix offset entirely, so the freecam view is rock-steady. The internal bob state may
 * keep ticking harmlessly but is never applied, so nothing visible bobs. The instant freecam ends the
 * inject stops and vanilla bobbing resumes unchanged.
 *
 * ## Why `bobView` (and not a redirect of the bob option read)
 *
 * `bobView` is the single point that applies the bob to the view matrix each frame, gated on the
 * already-extracted `cameraState.entityRenderState.isPlayer` / bob fields and the caller's
 * View Bobbing check. A HEAD `@Inject` + `ci.cancel()` is the simplest surgical kill — it mirrors the
 * existing [GameRendererMixin] hand-hide idiom (fail-open, freecam-gated) and avoids having to
 * redirect a record/field read. Mixins can inject into `private` methods of their target class.
 *
 * ## Target verified against `minecraft-merged-deobf-26.3.jar`
 *
 *  - `private void bobView(CameraRenderState, PoseStack)V` — the bob apply point. 26.3 changed the
 *    signature from yarn's `bobView(MatrixStack, float)`: the player's bob state now arrives on the
 *    extracted [CameraRenderState] (`backwardsInterpolatedWalkDistance`, `bob`), not as a partial
 *    tick, and it is called twice per frame (`renderItemInHand` and `renderLevel`) — cancelling covers
 *    both, which is what we want (no bob in the hand matrix either).
 *
 * ## Cost when freecam is off
 *
 * When [ReplayState.freecamActive] is false (the overwhelming common case) this does one volatile
 * read + returns — vanilla bobbing is entirely unchanged. Whole-body fail-open so a render error
 * never crashes the frame.
 */
@Mixin(GameRenderer::class)
class GameRendererBobViewMixin {

    @Inject(method = ["bobView"], at = [At("HEAD")], cancellable = true)
    private fun iustitia_killBobInFreecam(
        cameraState: CameraRenderState,
        poseStack: PoseStack,
        ci: CallbackInfo,
    ) {
        try {
            if (ReplayState.freecamActive) ci.cancel()
        } catch (_: Throwable) {
            // fail-open: leave vanilla bobbing applied (never crash the frame)
        }
    }
}
