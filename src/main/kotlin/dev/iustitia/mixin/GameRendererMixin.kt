package dev.iustitia.mixin

import com.mojang.renderpearl.api.textures.GpuTextureView
import dev.iustitia.replay.ReplayState
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.client.renderer.state.level.PlayerRenderState
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

/**
 * FREECAM hide first-person hand (Task 6, cosmetic). While [ReplayState.freecamActive] the camera is
 * detached from the local player, but vanilla still renders the player's hand/item in first person
 * (the view is first-person by default). This cancels `GameRenderer.renderItemInHand` at HEAD while
 * freecam is active, so the hand/item render is skipped that frame — mirroring the Zergatul FreeCam
 * v26.2 reference (which redirected the first-person gate in `GameRenderer.renderItemInHand`).
 *
 * ## 26.3 port — why this is no longer a `@Redirect`
 *
 * Up to yarn 1.21.11 this redirected `Perspective.isFirstPerson()` (ordinal 0) inside
 * `GameRenderer.renderHand(float, boolean, Matrix4f)`. 26.3 removed `renderHand` entirely: the
 * first-person gate moved into the extracted `PlayerRenderState` / `OptionsRenderState`, and
 * `renderItemInHand` no longer calls `isFirstPerson()` at all — it reads
 * `optionsRenderState.cameraType.isFirstPerson()`, a record/field read with no `INVOKE` to redirect.
 * First-person hands/items are now submitted by
 * `FirstPersonHandsAndItemsRenderer.submitHandsWithItems(...)` from inside
 * `GameRenderer.renderItemInHand(CameraRenderState, PlayerRenderState, GpuTextureView)`.
 *
 * `renderItemInHand` is therefore the remaining single point that exists, is invoked once per frame
 * from `render3dHud`, and does *only* the hand/item work (the "screen effects" pass — fire/water
 * overlay, spyglass — is a separate `screenEffectRenderer.submit(...)` call right after it), so a
 * HEAD `ci.cancel()` is the closest equivalent of the old gate: it hides exactly the hand and item
 * while freecam is active, and nothing else.
 *
 * Verified against `minecraft-merged-deobf-26.3.jar`: `private void
 * renderItemInHand(CameraRenderState, PlayerRenderState, GpuTextureView)V` — mixins can inject into
 * private methods of their target class.
 *
 * Fail-open: when freecam is NOT active the method simply runs unchanged. Cosmetic only; the core
 * freecam works without it. Runtime-only-verifiable.
 */
@Mixin(GameRenderer::class)
class GameRendererMixin {

    @Inject(method = ["renderItemInHand"], at = [At("HEAD")], cancellable = true)
    private fun iustitia_hideHandInFreecam(
        cameraState: CameraRenderState,
        playerState: PlayerRenderState,
        depthTextureView: GpuTextureView,
        ci: CallbackInfo,
    ) {
        try {
            if (ReplayState.freecamActive) ci.cancel()
        } catch (_: Throwable) {
            // fail-open: leave the hand render alone (never crash the frame)
        }
    }
}
