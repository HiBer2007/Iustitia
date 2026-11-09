package dev.iustitia.mixin

import dev.iustitia.replay.ReplayState
import net.minecraft.client.renderer.LevelRenderer
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

/**
 * "Hide the live world" overrides that live on `LevelRenderer` (`WorldRenderer` up to yarn 1.21.11):
 * suppress the LIVE world's block entities and the LIVE targeted-block outline while a chunk-bearing
 * `/ius playclip` runs, and the live outline while FREECAM is active.
 *
 * The FREECAM spectator **no-black-out** override used to live here too; 26.3 removed the
 * `LocalPlayer.isSpectator()` call from `LevelRenderer.render` entirely (it now happens inside
 * `Camera.extractRenderState`), so it moved to [CameraMixin] — see
 * `CameraMixin.iustitia_freecamSpectatorNoBlackout` for the full rationale.
 *
 * ## 26.3 port (targets verified against `minecraft-merged-deobf-26.3.jar` + the decompiled source)
 *
 * | old yarn target | 26.3 target |
 * | --- | --- |
 * | `renderBlockEntities(MatrixStack, WorldRenderState, OrderedRenderCommandQueueImpl)` | `submitBlockEntities(PoseStack, LevelRenderState, SubmitNodeCollector)` |
 * | `renderTargetBlockOutline(VertexConsumerProvider$Immediate, MatrixStack, boolean, WorldRenderState)` | `submitBlockOutline(PoseStack, SubmitNodeCollector, LevelRenderState)` |
 *
 * Both are now private submit-phase helpers called from `LevelRenderer.submitFeatures(...)`; the
 * `boolean renderOutline` that used to be a parameter of `renderBlockEntities` is now the flag
 * guarding the `submitBlockOutline` call in `submitFeatures`, so the outline target is simply
 * `submitBlockOutline`. `MultiBufferSource` no longer exists (the queue is `SubmitNodeCollector`).
 * Cancelling at HEAD works exactly as before: `submitFeatures` still clears
 * `levelRenderState.blockEntityRenderStates` after the call, so a cancelled submit leaks nothing.
 * Handlers deliberately take no target arguments — each method name is unique on the class and the
 * parameters are unused.
 */
@Mixin(LevelRenderer::class)
class WorldRendererMixin {

    /**
     * MODERN chunk-bearing playclip: suppress the LIVE world's block entities (signs, chests,
     * banners, ...). The recorded chunk world rendered by [dev.iustitia.render.ChunkWorldRenderer]
     * is relocated by `relocOffset` and renders only block *models* — it never renders block
     * entities. So every sign/chest/banner on screen during a chunk playclip is a LIVE block entity
     * at its LIVE block coord, detached from the relocated recorded world (the reported signs bug:
     * sign text floats at `-relocOffset` from the recorded sign block). Cancelling
     * `submitBlockEntities` at HEAD while the chunk gate is active hides them all, so only the
     * recorded chunk world + buffered ghosts show — the block-entity parallel of
     * [SectionRenderStateMixin]'s terrain cancel and [EntityRendererMixin]'s entity cancel, under
     * the exact same gate (`active && chunks != null && !legacyPlayclip`).
     *
     * Recorded block-entity NBT (sign text, chest contents) is a documented v1 follow-up
     * (`ChunkCapture.kt:22-23`); until then chunk playclip shows the recorded block models with no
     * block-entity overlay. The legacy `/ius playclip` + `/ius replay` paths (no chunks) skip this.
     *
     * `defaultRequire: 1` → a mismatch fails launch; render-time apply is runtime-only-verifiable.
     */
    @Inject(method = ["submitBlockEntities"], at = [At("HEAD")], cancellable = true)
    private fun iustitia_suppressLiveBlockEntities(ci: CallbackInfo) {
        try {
            if (ReplayState.active && ReplayState.chunks != null && !ReplayState.legacyPlayclip) {
                ci.cancel()
            }
        } catch (_: Throwable) {
            // fail-open: leave the live block entities drawing (worst case = show-through, never a crash)
        }
    }

    /**
     * Suppress the LIVE world's targeted-block outline (the black wireframe box around the block the
     * local player is looking at) during FREECAM or a MODERN chunk-bearing playclip. In those modes the
     * camera is detached (FREECAM) or the recorded chunk world replaces the live one (MODERN), so the
     * live player's targeted-block outline renders at a LIVE coord that has nothing to do with what the
     * user is spectating — it floats detached in the replay view (the reported "block outlines in the
     * wrong spot" in freecam). Cancelling `submitBlockOutline` at HEAD removes it for that frame.
     *
     * Gate: `freecamActive` (any FREECAM — the camera isn't the live player, so the live target is
     * meaningless) OR the MODERN gate (`active && chunks != null && !legacyPlayclip` — the recorded
     * chunk world is what's on screen, the live target is wrong). The legacy `/ius replay` + FREE view
     * (camera IS the live player, live world on screen) is NOT gated → the outline stays correct there.
     *
     * `defaultRequire: 1` → a signature mismatch fails launch; render-time apply is runtime-only-verifiable.
     */
    @Inject(method = ["submitBlockOutline"], at = [At("HEAD")], cancellable = true)
    private fun iustitia_suppressLiveBlockOutline(ci: CallbackInfo) {
        try {
            if (ReplayState.freecamActive ||
                (ReplayState.active && ReplayState.chunks != null && !ReplayState.legacyPlayclip)) {
                ci.cancel()
            }
        } catch (_: Throwable) {
            // fail-open: leave the live outline drawing (worst case = a stray box, never a crash)
        }
    }
}
