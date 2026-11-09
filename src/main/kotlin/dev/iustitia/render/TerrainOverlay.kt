package dev.iustitia.render

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import dev.iustitia.replay.ReplayState
import dev.iustitia.replay.TerrainSnapshot
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.rendertype.RenderTypes

/**
 * Renders the terrain shell bundled in a v5+ clip as a face-culled wireframe overlay, relocated to
 * the user by [ReplayState.relocOffset] (the same shared origin translate [ReplayRenderer] applies,
 * so terrain + ghosts move together). Drawn only when [ReplayState.terrain] is non-null — i.e. a
 * `/ius playclip` of a terrain-bearing clip; a `/ius replay` (terrain null) renders no map.
 *
 * ## Face culling — "don't render impossible-to-see blocks"
 *
 * A captured block is drawn ONLY if at least one of its 6 neighbors is exposed (air / transparent /
 * outside the captured bbox). A fully-enclosed block (6 solid opaque neighbors) contributes zero
 * quads, so the overlay is the thin visible *shell* of the captured region, not the solid volume.
 * This bounds the per-frame draw count to the surface (a few thousand cuboids for an arena) — the
 * buried interior never reaches the lines buffer. See [TerrainColors.isTransparent] for the
 * see-through set (glass/leaves/water/… keep the face behind them).
 *
 * ## Build cache
 *
 * The block grid + shell are built once per [TerrainSnapshot] (compared by reference) and cached;
 * rebuilding on the first render frame after a clip starts. Pure CPU, no world reads at render
 * time — a clip's terrain renders even after the source chunks unload or on a different server.
 * Fail-open: any build/render error skips the overlay that frame (ghosts still draw).
 *
 * ## Why wireframe cuboids (not filled translucent quads)
 *
 * Reuses the ghosts' depth-tested line path (no wallhack, consistent with the ghost wireframe
 * aesthetic). A filled translucent layer would need its own block-model bake; the wireframe shell
 * is the lower-risk v1 and is face-culled just the same. Filled render is a documented follow-up.
 *
 * ## 26.3 geometry submission
 *
 * `MultiBufferSource` is gone, so the whole shell is emitted through ONE
 * [SubmitNodeCollector.submitCustomGeometry] call against [RenderTypes.lines]: the callback receives
 * the collected [PoseStack.Pose] (the shared origin translate [ReplayRenderer] pushed) plus a
 * [VertexConsumer] for the lines layer, and every shell block emits its 12 cube edges there. That is
 * the same single-buffer, single-submit shape the old `ShapeRenderer.drawOutline` loop had, and it
 * keeps the per-frame cost to one node + one vertex emission pass over the shell.
 *
 * Read-only: adds no detection, sends no packets, edits no world.
 */
object TerrainOverlay {

    private const val TERRAIN_WIDTH = 1.0f

    /** One surface block to draw: absolute recorded-world coords + category tint. */
    private data class ShellBlock(val x: Int, val y: Int, val z: Int, val color: Int)

    /** Currently-built snapshot (compared by reference to detect change). Null = nothing built. */
    private var builtRef: TerrainSnapshot? = null
    /** pos → block-name for the captured non-air grid (neighbor lookups during culling). */
    private var grid: HashMap<Long, String> = HashMap()
    /** The face-culled shell (surface blocks only). */
    private var shell: List<ShellBlock> = emptyList()

    /**
     * The 12 edges of a unit cube, each as `x1,y1,z1,x2,y2,z2,nx,ny,nz` (the normal is the
     * normalized edge direction — exactly what vanilla's `ShapeOutlineFeatureRenderer` derives per
     * edge). Precomputed so a shell of thousands of blocks emits with no per-block allocation.
     */
    private val CUBE_EDGES: Array<FloatArray> = arrayOf(
        floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f),
        floatArrayOf(0f, 0f, 1f, 1f, 0f, 1f, 1f, 0f, 0f),
        floatArrayOf(0f, 1f, 0f, 1f, 1f, 0f, 1f, 0f, 0f),
        floatArrayOf(0f, 1f, 1f, 1f, 1f, 1f, 1f, 0f, 0f),
        floatArrayOf(0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 1f),
        floatArrayOf(1f, 0f, 0f, 1f, 0f, 1f, 0f, 0f, 1f),
        floatArrayOf(0f, 1f, 0f, 0f, 1f, 1f, 0f, 0f, 1f),
        floatArrayOf(1f, 1f, 0f, 1f, 1f, 1f, 0f, 0f, 1f),
        floatArrayOf(0f, 0f, 0f, 0f, 1f, 0f, 0f, 1f, 0f),
        floatArrayOf(1f, 0f, 0f, 1f, 1f, 0f, 0f, 1f, 0f),
        floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f, 0f),
        floatArrayOf(1f, 0f, 1f, 1f, 1f, 1f, 0f, 1f, 0f),
    )

    /**
     * Draw the terrain shell if a clip with terrain is playing. Call from [ReplayRenderer] inside the
     * shared origin translate (so the offset that relocates ghosts also relocates the terrain).
     * [collector] is the frame's submit-node collector — the shell is emitted as one custom-geometry
     * node on the lines render type. Fail-open.
     */
    fun render(matrices: PoseStack, collector: SubmitNodeCollector) {
        try {
            if (!ReplayState.active) return
            // A v6+ chunk-bearing clip renders its world as SOLID textured blocks via ChunkWorldRenderer,
            // which fully supersedes this wireframe shell — drawing both at once is the "block outlines
            // on top of solid blocks" double-render. Skip the wireframe whenever a chunk world is present;
            // it only shows for a terrain-only (v5) clip with no chunk world. ChunkWorldRenderer itself
            // no-ops when chunks == null, so for /ius replay + pre-v5 clips this stays a no-op too.
            if (ReplayState.chunks != null) return
            val ts = ReplayState.terrain ?: return
            ensureBuilt(ts)
            val shell = shell
            if (shell.isEmpty()) return
            collector.submitCustomGeometry(matrices, RenderTypes.lines()) { pose, lines ->
                for (b in shell) {
                    emitCube(pose, lines, b.x.toFloat(), b.y.toFloat(), b.z.toFloat(), b.color)
                }
            }
        } catch (_: Throwable) {
            // fail-open: a render error never crashes the client
        }
    }

    /**
     * Emit the 12 edges of the unit cube at ([x],[y],[z]) in the pose's frame. Mirrors vanilla's
     * `ShapeOutlineFeatureRenderer`: each vertex carries the edge color, the edge-direction normal and
     * the requested line width (the width lives on the vertex on 26.3, not on a per-draw state).
     */
    private fun emitCube(
        pose: PoseStack.Pose,
        lines: VertexConsumer,
        x: Float, y: Float, z: Float,
        color: Int,
    ) {
        for (e in CUBE_EDGES) {
            lines.addVertex(pose, x + e[0], y + e[1], z + e[2])
                .setColor(color)
                .setNormal(pose, e[6], e[7], e[8])
                .setLineWidth(TERRAIN_WIDTH)
            lines.addVertex(pose, x + e[3], y + e[4], z + e[5])
                .setColor(color)
                .setNormal(pose, e[6], e[7], e[8])
                .setLineWidth(TERRAIN_WIDTH)
        }
    }

    /** Rebuild the grid + shell if [ts] differs from the last-built snapshot; no-op otherwise. */
    private fun ensureBuilt(ts: TerrainSnapshot) {
        if (ts === builtRef) return
        builtRef = ts
        try {
            val blocks = ts.blocks()
            val g = HashMap<Long, String>(blocks.size)
            for (b in blocks) g[pack(b.x, b.y, b.z)] = b.name
            grid = g
            val ox = ts.originX; val oy = ts.originY; val oz = ts.originZ
            val mx = ox + ts.sizeX - 1; val my = oy + ts.sizeY - 1; val mz = oz + ts.sizeZ - 1
            val out = ArrayList<ShellBlock>(blocks.size / 3)
            for (b in blocks) {
                if (isExposed(b.x, b.y, b.z, ox, oy, oz, mx, my, mz, g)) {
                    out.add(ShellBlock(b.x, b.y, b.z, TerrainColors.tint(b.name)))
                }
            }
            shell = out
        } catch (_: Throwable) {
            shell = emptyList()
            grid = HashMap()
        }
    }

    /** True iff any of the 6 neighbors of (x,y,z) is exposed (outside the bbox / air / transparent).
     *  A block with no exposed neighbor is fully enclosed → culled (drawn nothing). */
    private fun isExposed(
        x: Int, y: Int, z: Int,
        ox: Int, oy: Int, oz: Int, mx: Int, my: Int, mz: Int,
        g: HashMap<Long, String>,
    ): Boolean {
        // 6 neighbor offsets; check each. Outside-bbox and air are exposed; transparent is exposed;
        // an opaque captured block is not. Early-return true on the first exposed neighbor.
        if (neighborExposed(x + 1, y, z, ox, oy, oz, mx, my, mz, g)) return true
        if (neighborExposed(x - 1, y, z, ox, oy, oz, mx, my, mz, g)) return true
        if (neighborExposed(x, y + 1, z, ox, oy, oz, mx, my, mz, g)) return true
        if (neighborExposed(x, y - 1, z, ox, oy, oz, mx, my, mz, g)) return true
        if (neighborExposed(x, y, z + 1, ox, oy, oz, mx, my, mz, g)) return true
        if (neighborExposed(x, y, z - 1, ox, oy, oz, mx, my, mz, g)) return true
        return false
    }

    private fun neighborExposed(
        nx: Int, ny: Int, nz: Int,
        ox: Int, oy: Int, oz: Int, mx: Int, my: Int, mz: Int,
        g: HashMap<Long, String>,
    ): Boolean {
        // outside the captured bbox → exposed (the shell is closed at the region edges)
        if (nx < ox || nx > mx || ny < oy || ny > my || nz < oz || nz > mz) return true
        val name = g[pack(nx, ny, nz)] ?: return true   // not in grid → air → exposed
        return TerrainColors.isTransparent(name)        // glass/leaves/water/… → see-through → exposed
    }

    /** Pack a block pos to a long key for the grid HashMap. x/z 24 bits (±8M), y 16 bits (±32k) —
     *  plenty for any arena; low-bits masking is bijective over the captured range so negative
     *  world coords collide-free. We never unpack (the scan reads x/y/z directly from [TerrainSnapshot.Block]). */
    private fun pack(x: Int, y: Int, z: Int): Long =
        ((x.toLong() and 0xFFFFFF) shl 40) or ((y.toLong() and 0xFFFF) shl 24) or (z.toLong() and 0xFFFFFF)
}
