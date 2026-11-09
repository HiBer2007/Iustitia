package dev.iustitia.render

import com.mojang.blaze3d.vertex.PoseStack
import dev.iustitia.config.ConfigManager
import dev.iustitia.replay.ReplayState
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.world.phys.Vec3

/**
 * Draws the active clip's captured chunk world as **solid, textured blocks** (the real map) each
 * frame, under the same shared origin translate [dev.iustitia.render.ReplayRenderer] applies, so the
 * chunk world + ghosts move as one rigid block to the user. The block list is built **lazily per
 * chunk** by [ChunkMesher] as **per-block, face-culled, precomputed quads** (only the quads vanilla
 * would draw — buried faces are dropped at bake time, and `getQuads(null)` general quads are kept so
 * non-cube blocks don't get holes).
 *
 * ## 26.3 geometry submission (the static-GPU-mesh path is gone)
 *
 * 1.21.11's optimised path baked each chunk into a static `GpuBuffer` and drew it with its own
 * `RenderPass` + `"DynamicTransforms"` uniform. 26.3 replaced the immediate `RenderSystem`/`RenderPass`
 * backend those calls relied on (`com.mojang.blaze3d.systems.RenderSystem` no longer hands out a
 * device/encoder, `RenderType` no longer exposes a render pipeline, and vertex buffers are owned by the
 * frame's staged geometry), so re-creating that path would mean re-implementing the terrain pipeline
 * rather than using the game's. The replay world therefore goes through the supported extension point:
 * **one [SubmitNodeCollector.submitCustomGeometry] node per [RenderType] per frame**, whose body
 * ([ImmediateRenderQueue]) emits the pre-baked quads with [com.mojang.blaze3d.vertex.VertexConsumer.putBakedQuad].
 * The bake (face culling, model resolution, tint) is unchanged and still happens once per chunk — only
 * the per-frame vertex write moved from "one indexed draw call" to "one emission pass", i.e. exactly the
 * cost of the old fail-open fallback. **Behaviour on screen is identical; only the static buffer's FPS
 * win is lost** (documented approximation).
 *
 * **Matrix semantics:** the node's pose is `matrices.last()` — the camera-relative origin translate
 * pushed by [ReplayRenderer] — and [ImmediateRenderQueue] translates a *copy* of it per block by the
 * block's absolute recorded coord, so vertices land at `recorded - camPos + offset`, the same frame as
 * the ghosts. (The old static path bound `getModelView × matrices.peek()` as a uniform; the
 * custom-geometry path applies the whole pose per vertex instead, which is equivalent.)
 *
 * **Fail-open:** the whole path is wrapped in a try/catch per layer; any throw skips that layer for the
 * frame so a chunk-world error never crashes the client (ghosts + live render continue).
 *
 * ## Per-chunk render-distance cull + lazy progressive baking (unchanged)
 *
 * A max-radius capture ([IustitiaConfig.clipChunkRadius], up to 17×17 = 289 chunks) can hold hundreds
 * of thousands of surface blocks. Two things keep a playclip playable:
 *
 * 1. **Render-distance cull** — each frame we only consider chunks within
 *    [IustitiaConfig.clipChunkRenderDistance] (default 6, clamped 4..12) of the camera in **recorded
 *    space** (the camera's recorded-space position = `camPos - relocOffset`). Chunks outside that
 *    chebyshev radius are neither rendered NOR baked.
 * 2. **Lazy progressive baking** — of the in-range chunks, only the ones already baked render; the
 *    nearest UNbaked in-range chunks are baked on demand up to a small **per-frame block budget**
 *    ([LAZY_BAKE_BLOCK_BUDGET], counted in surface blocks). So the clip's world streams in from the
 *    camera outward over the first second instead of appearing in one synchronous bake spike. Far
 *    chunks only bake if you fly toward them; chunks you never visit are never baked.
 *
 * ## Gating
 *
 * No-op unless `ReplayState.active && ReplayState.chunks != null` — so a wireframe-terrain clip
 * (`chunks == null`) and a plain `/ius replay` are unaffected; the live world is only hidden by
 * [dev.iustitia.mixin.SectionRenderStateMixin] when `chunks != null` too. [free] drops the mesher cache.
 */
object ChunkWorldRenderer {

    /**
     * Per-frame lazy-bake budget in **surface-block count**. Each frame we bake the nearest unbaked
     * in-range chunks until the cumulative surface-block count of newly-baked chunks reaches this, so
     * the bake work spreads evenly across frames. ~3000 blocks/frame ≈ a handful of arena chunks,
     * baking the whole in-range ring (≤169 chunks at radius 6) over ~1 s at 60 fps with no
     * single-frame spike. At least one chunk always bakes per frame (if any unbaked in-range) so the
     * view never stalls entirely.
     */
    private const val LAZY_BAKE_BLOCK_BUDGET = 3000

    /** Reused per-frame collection — render thread only ([render] runs there and it is cleared at
     *  frame start, so leftovers from a failed frame can't leak into the next one). */
    private val collectedChunks = ArrayList<ChunkMesher.ChunkBlocks>()

    /**
     * Draw the clip's chunk world. [matrices] is already translated by the shared origin translate
     * (`-camPos + relocOffset`) in [dev.iustitia.render.ReplayRenderer.drawGhosts]; each layer's
     * custom-geometry node captures that pose and [ImmediateRenderQueue] offsets it per block to the
     * block's absolute recorded coord, so the world lands at `recorded - camPos + offset` — the same
     * frame as the ghosts. [camPos] is the live camera world pos; [relocOffset] the clip relocation.
     *
     * Each frame: snapshot-key the mesher, compute the recorded-space camera chunk, then walk the
     * in-range chunk coords nearest-first (chebyshev rings outward from the camera chunk), applying
     * the behind-camera cull + lazy bake and **collecting** the in-range baked chunks. After the walk,
     * draw them all via [submitLayers] (one custom-geometry node per layer); on any throw the layer is
     * skipped for the frame. Fail-open per frame + per chunk.
     */
    fun render(matrices: PoseStack, collector: SubmitNodeCollector, camPos: Vec3, relocOffset: Vec3) {
        if (!ReplayState.active) return
        val snap = ReplayState.chunks ?: return
        try {
            ChunkMesher.ensureSnapshot(snap)
            // Recorded-space camera = camPos - relocOffset. Cull chunks whose chebyshev distance from
            // this point exceeds the configured render distance.
            val rdx = camPos.x - relocOffset.x
            val rdy = camPos.y - relocOffset.y
            val rdz = camPos.z - relocOffset.z
            val camChunkX = Math.floor(rdx / 16.0).toInt()
            val camChunkZ = Math.floor(rdz / 16.0).toInt()
            val dist = ConfigManager.config.clipChunkRenderDistance.coerceIn(4, 12)
            // Camera look vector for the behind-camera cull (see [behindCamera]). Taken from the live
            // camera's pitch/yaw through Vec3.directionFromRotation (the same helper
            // Entity.getViewVector uses); null when the camera can't be read → skip the cull this
            // frame (fail-open: render every in-range chunk, the pre-cull behaviour).
            val mc = Minecraft.getInstance()
            val fwd: Vec3? = try {
                val cam = mc.gameRenderer.mainCamera()
                if (cam.isInitialized) Vec3.directionFromRotation(cam.xRot(), cam.yRot()) else null
            } catch (_: Throwable) { null }

            // Collect the in-range, not-behind-camera, baked chunks (the behind-camera cull + lazy bake
            // happen in [processChunk]; it returns the chunk to draw via [submitLayers]).
            // [collectedChunks] is a reused field — render-thread-only, cleared at frame start.
            collectedChunks.clear()
            var budget = LAZY_BAKE_BLOCK_BUDGET
            for (d in 0..dist) {
                if (d == 0) {
                    budget -= processChunk(camChunkX, camChunkZ, 0, snap, collectedChunks, budget, rdx, rdy, rdz, fwd)
                    continue
                }
                for (cx in (camChunkX - d)..(camChunkX + d)) {
                    budget -= processChunk(cx, camChunkZ - d, d, snap, collectedChunks, budget, rdx, rdy, rdz, fwd)
                    budget -= processChunk(cx, camChunkZ + d, d, snap, collectedChunks, budget, rdx, rdy, rdz, fwd)
                }
                for (cz in (camChunkZ - d + 1)..(camChunkZ + d - 1)) {
                    budget -= processChunk(camChunkX - d, cz, d, snap, collectedChunks, budget, rdx, rdy, rdz, fwd)
                    budget -= processChunk(camChunkX + d, cz, d, snap, collectedChunks, budget, rdx, rdy, rdz, fwd)
                }
            }
            if (collectedChunks.isEmpty()) return
            submitLayers(collectedChunks, matrices, collector)
        } catch (_: Throwable) {
            // fail-open: chunk world drops this frame, ghosts + live render continue
        }
    }

    /**
     * Collect the chunk at (cx,cz) into [collected] if it's already baked (and not behind the camera);
     * otherwise bake it on demand (if the per-frame [budget] remains, or it's the camera's own chunk
     * — d == 0 — which always bakes so the view directly around the camera never stalls) and collect
     * it. The behind-camera cull skips the per-frame draw cost for a chunk whose ENTIRE baked-block
     * AABB is behind the camera plane (the chunk still bakes so it's ready the instant the user turns
     * around). Skips chunks the snapshot has no data for, and unbaked chunks with no budget left (they
     * stream in on a later frame).
     *
     * @return the surface-block count of the chunk it just baked (so the caller can decrement its
     *   running budget), or 0 when the chunk was already baked / had no data / was skipped for budget.
     *   Fail-open per chunk (returns 0 on a throw).
     */
    private fun processChunk(
        cx: Int, cz: Int, d: Int,
        snap: dev.iustitia.replay.ChunkSnapshot,
        collected: ArrayList<ChunkMesher.ChunkBlocks>,
        budget: Int,
        rdx: Double, rdy: Double, rdz: Double, fwd: Vec3?,
    ): Int = try {
        if (ChunkMesher.chunkRecAt(cx, cz) == null) return 0
        val cb = ChunkMesher.bakedChunk(cx, cz)
        if (cb != null) {
            if (cb.blockCount != 0 && fwd != null && behindCamera(cb, rdx, rdy, rdz, fwd)) return 0
            collected.add(cb)
            return 0
        }
        if (budget > 0 || d == 0) {
            val newly = ChunkMesher.bakeChunk(snap, cx, cz)
            if (newly != null) {
                if (newly.blockCount != 0 && fwd != null && behindCamera(newly, rdx, rdy, rdz, fwd)) return newly.blockCount
                collected.add(newly)
                return newly.blockCount
            }
        }
        // budget spent + not the camera chunk → skip this frame; it bakes on a later frame.
        0
    } catch (_: Throwable) {
        0
    }

    /**
     * Pop-free behind-camera cull for one baked chunk. The chunk's baked-block AABB (recorded coords,
     * true extent) is tested against the camera plane (through the recorded-space camera `(rdx,rdy,rdz)`,
     * normal [fwd] — the camera's look vector, rotation-only so identical in recorded space). Returns
     * true only when EVERY one of the 8 AABB corners is behind the camera plane (max dot < 0) — i.e.
     * the whole chunk is off-screen — so a chunk with any corner in front is NEVER culled (can't pop).
     * The max-dot is computed per-axis by picking the corner that maximizes each axis's contribution
     * given the sign of [fwd], avoiding an explicit 8-corner loop. [fwd] is non-null + normalized-ish
     * (Vec3.directionFromRotation returns a unit vector); the test is scale-invariant in sign anyway.
     */
    private fun behindCamera(
        cb: ChunkMesher.ChunkBlocks,
        rdx: Double, rdy: Double, rdz: Double, fwd: Vec3,
    ): Boolean {
        val dx0 = cb.minX - rdx; val dx1 = cb.maxX - rdx
        val dy0 = cb.minY - rdy; val dy1 = cb.maxY - rdy
        val dz0 = cb.minZ - rdz; val dz1 = cb.maxZ - rdz
        val mx = if (fwd.x > 0) dx1 * fwd.x else dx0 * fwd.x
        val my = if (fwd.y > 0) dy1 * fwd.y else dy0 * fwd.y
        val mz = if (fwd.z > 0) dz1 * fwd.z else dz0 * fwd.z
        return (mx + my + mz) < 0.0
    }

    /**
     * Group the collected chunks' baked blocks by [RenderType] and submit **one custom-geometry node
     * per layer** (the block-atlas cutout sheet + the translucent sheet, in practice). Grouping first
     * keeps the node count at ≤ 2 per frame regardless of how many chunks are in range, and lets the
     * collector own the phase/ordering decision for each render type (`hasBlending()` → translucent
     * phase, else opaque). Fail-open per layer.
     *
     * The per-layer block lists are freshly allocated each frame (two small lists) rather than reused:
     * the collector drains a node's geometry *after* `render` returns, so the list handed to
     * [ImmediateRenderQueue] must stay valid for the rest of the frame.
     */
    private fun submitLayers(
        chunks: List<ChunkMesher.ChunkBlocks>,
        matrices: PoseStack,
        collector: SubmitNodeCollector,
    ) {
        val byLayer = HashMap<RenderType, ArrayList<ChunkMesher.BlockQuads>>()
        for (cb in chunks) {
            for ((layer, blocks) in cb.byLayer) {
                if (blocks.isEmpty()) continue
                byLayer.getOrPut(layer) { ArrayList() }.addAll(blocks)
            }
        }
        if (byLayer.isEmpty()) return
        for ((layer, blocks) in byLayer) {
            try {
                collector.submitCustomGeometry(matrices, layer, ImmediateRenderQueue(blocks))
            } catch (_: Throwable) {
                // fail-open: this layer's chunk-world geometry drops for the frame
            }
        }
    }

    /** Drop the mesher cache (called from [dev.iustitia.replay.ReplayState.stop]). Idempotent. */
    fun free() = ChunkMesher.free()
}
