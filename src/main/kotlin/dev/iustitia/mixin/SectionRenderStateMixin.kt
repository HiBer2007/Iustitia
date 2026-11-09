package dev.iustitia.mixin

import dev.iustitia.replay.ReplayState
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

/**
 * Hides the **live world's terrain** while a chunk-bearing `/ius playclip` runs, so the clip's
 * captured chunk world (rendered as solid textured blocks by [dev.iustitia.render.ChunkWorldRenderer])
 * replaces it instead of z-fighting / bleeding through. The Iustitia-native equivalent of ReplayMod's
 * "no live world" without ReplayMod's connection-replacement — we coexist with the live server.
 *
 * ## Target + why this is the surgical point
 *
 * `ChunkSectionsToRender` is the object `LevelRenderer.prepareChunkRenders` hands back for the frame;
 * it is the only thing that draws terrain layers. Cancelling its two public draw entry points at HEAD
 * skips **all** terrain layers, while **entities keep rendering** (they are submitted separately in
 * `LevelRenderer.submitFeatures` -> `submitEntities`) and the Fabric `LevelRenderEvents.
 * COLLECT_SUBMITS` hook still fires — so the clip's chunk world + the buffered ghosts (both drawn
 * there by [dev.iustitia.render.ReplayRenderer]) keep working. Sky / fog / clouds are separate passes,
 * left untouched so the background stays natural.
 *
 * 26.3 replaced yarn's single `renderSection(BlockRenderLayerGroup, GpuSampler)` with two entry
 * points, both of which must be cancelled (verified against `minecraft-merged-deobf-26.3.jar` and the
 * decompiled `LevelRenderer`):
 *  - `renderGroup(ChunkSectionLayerGroup, RenderPass, GpuSampler, GpuTextureView, boolean)` — called
 *    for the OPAQUE group (`LevelRenderer.executeSolid`) and, in classic transparency mode, for the
 *    TRANSLUCENT group (`LevelRenderer.executeClassicTransparency`);
 *  - `renderOit(GpuSampler, OitStage, OitRenderPassProvider.Parameters, GpuTextureView, GpuTextureView)`
 *    — called per `OitStage` for the TRANSLUCENT group when "improved transparency" (order-independent
 *    transparency) is on (`LevelRenderer.executeOit`). Without cancelling this one the translucent
 *    terrain (water, glass, ice) would still draw through the recorded world in that mode.
 *
 * ## Gating — back-compat + fail-open
 *
 * Cancels **only** when `ReplayState.active && ReplayState.chunks != null && !legacyPlayclip` — i.e. a
 * v6+ chunk-bearing playclip. A wireframe-terrain clip (`chunks == null`, terrain-only) and a plain
 * `/ius replay` (no map) are completely unaffected: the live world renders as normal and the ghosts /
 * wireframe overlay sit on top, exactly as before. The whole body is try/caught (project fail-open
 * posture): a throw leaves the live terrain running (worst case = live world shows through the clip,
 * never a crash).
 *
 * ## Runtime-only-verifiable (per project convention for render-path mixins)
 *
 * The `renderGroup` / `renderOit` targets are verified against the 26.3 jar (both public, single
 * overload each). `defaultRequire: 1` means a mismatch fails launch — the build can't confirm
 * mixin-apply, only a live client run can. If it ever fails at runtime, [active] is the single point
 * to flip.
 */
@Mixin(ChunkSectionsToRender::class)
class SectionRenderStateMixin {

    /** Opaque + classic-transparency terrain groups (yarn `renderSection`'s successor). */
    @Inject(method = ["renderGroup"], at = [At("HEAD")], cancellable = true)
    private fun iustitia_suppressLiveTerrain(ci: CallbackInfo) {
        try {
            // Legacy playclip never has chunks (ReplayState.start nulls them) and must show the live
            // world (v1.1.0 = ghosts over live terrain), so the !legacyPlayclip guard is belt-and-
            // suspenders against any future path that re-populates chunks during a Legacy playclip.
            if (ReplayState.active && ReplayState.chunks != null && !ReplayState.legacyPlayclip) {
                ci.cancel()
            }
        } catch (_: Throwable) {
            // fail-open: leave the live terrain drawing (worst case = show-through, never a crash)
        }
    }

    /** Order-independent-transparency terrain (improved-transparency mode only). */
    @Inject(method = ["renderOit"], at = [At("HEAD")], cancellable = true)
    private fun iustitia_suppressLiveTerrainOit(ci: CallbackInfo) {
        try {
            if (ReplayState.active && ReplayState.chunks != null && !ReplayState.legacyPlayclip) {
                ci.cancel()
            }
        } catch (_: Throwable) {
            // fail-open: leave the live translucent terrain drawing
        }
    }
}
