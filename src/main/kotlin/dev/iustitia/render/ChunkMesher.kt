package dev.iustitia.render

import dev.iustitia.replay.ChunkSnapshot
import net.minecraft.client.Minecraft
import net.minecraft.commands.arguments.blocks.BlockStateParser
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.client.color.block.BlockColors
import net.minecraft.client.renderer.block.dispatch.BlockStateModel
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.client.resources.model.geometry.BakedQuad
import net.minecraft.util.RandomSource

/**
 * Resolves a captured [ChunkSnapshot] into **per-block, face-culled, precomputed quads** grouped by
 * [RenderType], so [ChunkWorldRenderer] can draw the clip's solid world each frame by emitting those
 * quads directly — no per-frame model lookup, no buried faces. This replaces the old
 * `renderBlockAsEntity`-per-surface-block path (which emitted every block's full 6-face model every
 * frame, including faces buried against opaque neighbours — the ~380→40 FPS collapse on max-radius
 * clips) with a bake-once path that mirrors vanilla's own face-cull rule.
 *
 * ## Face cull = vanilla's rule (no holes)
 *
 * For each surface block, for each [BlockStateModelPart], we replicate vanilla's
 * `ModelBlockRenderer.renderFlat` exactly:
 *  - per [Direction] `d`: `part.getQuads(d)` is emitted only when
 *    [Block.shouldRenderFace] is true for the neighbour state in that direction (neighbour resolved
 *    from the raw snapshot; region-edge / air → air state → true → the face draws, closing the shell
 *    at the captured-region edge, as before).
 *  - `part.getQuads(null)` (the general / non-culled quads — torches, fences, plants, and any model
 *    detail without a cull face) is **always** emitted. This is the rule that prevents holes in
 *    non-cube blocks; a naive per-direction cull that skipped `getQuads(null)` would drop them.
 *
 * [Block.shouldRenderFace] (vanilla-correct: opaque + transparent + same-block rules, e.g.
 * glass-vs-glass, leaves-vs-leaves) replaces the old crude `isOpaqueAt` approximation for the
 * **per-face** decision. `isOpaqueAt` is retained only for the block-level surface-shell skip (a block
 * whose 6 neighbours are all opaque is fully interior and never seen — pure overdraw).
 *
 * ## 26.3 model pipeline
 *
 * `BlockRenderManager` / `ItemBlockRenderTypes` are gone. A block state resolves to a
 * [BlockStateModel] through `Minecraft.getModelManager().getBlockStateModelSet().get(state)`
 * (`collectParts`, was `getParts`), and every [BakedQuad] carries its own
 * `materialInfo().itemRenderType()` — the block-atlas item sheet render type
 * (`Sheets.cutoutBlockItemSheet()` / `Sheets.translucentBlockItemSheet()`), which is what vanilla's
 * own block-item path binds. That per-quad render type replaces the old per-block
 * `ItemBlockRenderTypes.getEntityBlockLayer(state)` guess (it is strictly more accurate: a model can
 * legitimately mix cutout and translucent quads), so quads are grouped by
 * `quad.materialInfo().itemRenderType()` at bake time.
 *
 * Tints come from `BlockColors.getTintSources(state)` + `BlockTintSource.color(state)` (the
 * position-independent tint the old `BlockColors.getColor(state, null, null, 0)` produced — replays
 * have no biome context) and are stored as a per-tint-index `IntArray` on the baked block; the quad's
 * own `materialInfo().tintIndex()` selects the entry at emit time, exactly like
 * `BlockModelFeatureRenderer.putQuad`.
 *
 * **Approximation (documented):** the per-state `BlockModelRenderState.transformation` (the model
 * transform vanilla applies around a resolved model) is not carried into the baked quads. The state
 * models themselves already bake their blockstate rotation into the geometry, and the fallback
 * wrapper vanilla uses for a bare state (`BlockModelSet.createFallbackModel`) is identity, so the only
 * visible difference would be for a model whose transform is non-identity — none of the shipped block
 * models rely on that for world rendering.
 *
 * ## Lazy per-chunk baking (unchanged)
 *
 * Each chunk bakes on demand the first time it enters the camera's render-distance ring, and
 * [ChunkWorldRenderer] only bakes a small per-frame block budget of the nearest unbaked in-range
 * chunks — so there's no single bake spike, far chunks only bake if you fly toward them, and a
 * scrolling-in chunk bakes alone. See [ChunkWorldRenderer] for the budget + ring walk.
 *
 * Neighbour lookups (both the block-level skip and the per-face cull) read the **raw snapshot** via
 * [ChunkSnapshot.nameAt] (O(1)), NOT the baked cache — so culling is correct regardless of which
 * chunks have baked yet. Bake order has no effect on correctness.
 *
 * ## Cache + fail-open
 *
 * Per-name caches: `BlockState` (`stateCache`) and per-tint-index ARGB (`tintCache`)
 * — block names repeat heavily across chunks, so these persist across chunk bakes and are a big win.
 * A new snapshot (new clip) clears all of it via [ensureSnapshot]. `Registries.BLOCK` is a
 * DefaultedRegistry → an unknown id resolves to AIR (renders as nothing, counts as a transparent
 * neighbour) — the fail-open behaviour we want. [free] drops everything (called from
 * [dev.iustitia.replay.ReplayState.stop]). All wrapped fail-open so a render/bake error degrades
 * (missing blocks / no chunk world) rather than crashing the client.
 *
 * ## v1 limits (documented follow-ups, unchanged)
 *
 * Block **properties** — since v7 the palette key carries the full state string for stateful blocks
 * and [stateFor] parses the EXACT recorded state, so a stairs renders in its recorded facing (not the
 * default), and non-full/transparent blocks feed [Block.shouldRenderFace] their real shape (neighbours
 * behind glass/stairs/slabs render). The block-level surface-shell skip uses [BlockState.isSolidRender]
 * (NOT [BlockState.canOcclude], which is true for opaque-material slabs/stairs and would wrongly skip a
 * block fully surrounded by them → the "missing texture behind slabs/stairs" bug). Pre-v7 clips have
 * only bare-id palette entries → default state (today's behaviour). Block **entities** (chest contents,
 * sign text, banners) and **fluids** (`INVISIBLE` render type, skipped here) are still not rendered.
 * Lighting stays fullbright (no AO/skylight).
 */
object ChunkMesher {

    /** One surface block's precomputed, face-culled quads at an absolute recorded coord, with the
     *  block's per-tint-index colors (ARGB; empty = no tint source). Built once at bake time;
     *  [ChunkWorldRenderer] emits these directly each frame via
     *  [com.mojang.blaze3d.vertex.VertexConsumer.putBakedQuad]. */
    data class BlockQuads(
        val x: Int, val y: Int, val z: Int,
        val tints: IntArray,
        val quads: List<BakedQuad>,
    )

    /** The precomputed quads of one captured chunk, grouped by [RenderType] (so the renderer submits
     *  each layer's geometry once per frame) + a [blockCount] (the number of surface blocks, for
     *  [ChunkWorldRenderer]'s per-frame lazy-bake budget — a chunk's bake cost scales with its
     *  surface-block count) + the baked-block AABB (`minX..maxX` etc, in **recorded** coords, true
     *  extent — `max` is `maxBlockCoord + 1` so the box covers the full 1-block cell). The AABB lets
     *  [ChunkWorldRenderer] cull chunks fully behind the camera plane without popping (an 8-corner
     *  test against the camera forward; a chunk is only dropped when EVERY corner is behind the
     *  camera, so a partly-in-front chunk always renders). All zero when the chunk has no blocks. */
    data class ChunkBlocks(
        val chunkX: Int, val chunkZ: Int,
        val byLayer: Map<RenderType, List<BlockQuads>>,
        val blockCount: Int,
        val minX: Int, val minY: Int, val minZ: Int,
        val maxX: Int, val maxY: Int, val maxZ: Int,
    )

    @Volatile private var snapRef: ChunkSnapshot? = null
    @Volatile private var snapIndex: HashMap<Long, ChunkSnapshot.ChunkRec>? = null
    private var baked: LinkedHashMap<Long, ChunkBlocks> = LinkedHashMap()

    /** Fullbright light coord (`LightCoordsUtil.FULL_BRIGHT` = 0xF000F0) — the same flat lighting the
     *  renderer applies; stamped onto every emitted vertex by [ImmediateRenderQueue]. */
    const val FULL_LIGHT = 0xF000F0

    private var stateCache: HashMap<String, BlockState?> = HashMap(256)
    private var tintCache: HashMap<String, IntArray> = HashMap(256)
    private val rand: RandomSource = RandomSource.create()
    private val directions: Array<Direction> = Direction.entries.toTypedArray()

    /** Chunk-coord → long key, delegating to the one definition of the encoding
     *  ([dev.iustitia.replay.BlockDeltaBuffer.chunkKey]) so the bake cache and the snapshot index it
     *  is keyed against can never drift apart. This local spelling stays because it is called ten
     *  times below. */
    private fun key(chunkX: Int, chunkZ: Int): Long = dev.iustitia.replay.BlockDeltaBuffer.chunkKey(chunkX, chunkZ)

    /**
     * Key the mesher to [snap]. A **different snapshot reference** (a new clip, or re-play of a reloaded
     * clip) clears the bake cache + all per-name caches + rebuilds the chunk index — so a new playclip
     * always starts a fresh lazy bake. Same reference is a no-op. Reassigns fresh map instances (rather
     * than `.clear()`) so a concurrent render thread holding the old `baked` ref keeps using a
     * consistent stale map instead of throwing `ConcurrentModificationException` — fail-open under a
     * stop/free race.
     */
    fun ensureSnapshot(snap: ChunkSnapshot) {
        if (snapRef === snap && snapIndex != null) return
        snapRef = snap
        baked = LinkedHashMap()
        stateCache = HashMap(256)
        tintCache = HashMap(256)
        val m = HashMap<Long, ChunkSnapshot.ChunkRec>(snap.chunks.size)
        for (c in snap.chunks) m[key(c.chunkX, c.chunkZ)] = c
        snapIndex = m
    }

    /** The captured [ChunkSnapshot.ChunkRec] at chunk coord (cx,cz), or null if the snapshot has no
     *  chunk there. Used by the renderer to know which in-range coords actually have data to bake. */
    fun chunkRecAt(cx: Int, cz: Int): ChunkSnapshot.ChunkRec? = snapIndex?.get(key(cx, cz))

    /** The already-baked [ChunkBlocks] at (cx,cz), or null if that chunk hasn't been baked yet (or the
     *  snapshot has no chunk there). Read every frame by the renderer. */
    fun bakedChunk(cx: Int, cz: Int): ChunkBlocks? = baked[key(cx, cz)]

    /**
     * The chunk keys whose bake must be redone after a block changes at (x,y,z): the containing chunk
     * plus any neighbour when the cell sits on a chunk border (a border block's face-cull depends on
     * the neighbour chunk's blocks). Ported from SnapClip; used by the block-delta overlay so a replayed
     * world edit re-meshes the affected chunk(s) instead of showing the stale baked mesh.
     */
    fun invalidateAround(x: Int, y: Int, z: Int): Set<Long> {
        val cx = x shr 4; val cz = z shr 4
        val lx = x and 15; val lz = z and 15
        val out = HashSet<Long>(5)
        out.add(key(cx, cz))
        if (lx == 0) out.add(key(cx - 1, cz))
        if (lx == 15) out.add(key(cx + 1, cz))
        if (lz == 0) out.add(key(cx, cz - 1))
        if (lz == 15) out.add(key(cx, cz + 1))
        return out
    }

    /**
     * Drop the baked chunks at [keys] (so the next frame lazy-bakes them from the current snapshot +
     * overlay). The baked quads are plain CPU data now, so there is no GPU buffer to close. Fail-open
     * per chunk.
     */
    fun invalidateChunks(keys: Set<Long>) {
        try {
            for (k in keys) {
                baked.remove(k)
            }
        } catch (_: Throwable) {
        }
    }

    /**
     * Bake one chunk's face-culled quads **on demand** (the lazy-bake primitive). Idempotent — returns
     * the cached [ChunkBlocks] if (cx,cz) was already baked. Returns null when the snapshot has no
     * chunk at (cx,cz), or on any throw (fail-open → renderer skips that chunk, keeps going).
     * [ensureSnapshot] is called first so a bare [bakeChunk] still works.
     */
    fun bakeChunk(snap: ChunkSnapshot, cx: Int, cz: Int): ChunkBlocks? = try {
        ensureSnapshot(snap)
        val k = key(cx, cz)
        baked[k]?.let { return it }
        val rec = snapIndex?.get(k) ?: return null
        val byLayer = bakeChunkBlocks(snap, rec)
        val blockCount = byLayer.values.sumOf { it.size }
        // Baked-block AABB (recorded coords, true extent: max = coord+1). One pass over the baked
        // blocks at bake time so the renderer can do a pop-free 8-corner behind-camera cull each frame.
        var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE; var minZ = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE; var maxY = Int.MIN_VALUE; var maxZ = Int.MIN_VALUE
        if (blockCount > 0) {
            for (bqs in byLayer.values) for (bq in bqs) {
                if (bq.x < minX) minX = bq.x
                if (bq.y < minY) minY = bq.y
                if (bq.z < minZ) minZ = bq.z
                if (bq.x > maxX) maxX = bq.x
                if (bq.y > maxY) maxY = bq.y
                if (bq.z > maxZ) maxZ = bq.z
            }
            maxX++; maxY++; maxZ++
        } else {
            minX = 0; minY = 0; minZ = 0; maxX = 0; maxY = 0; maxZ = 0
        }
        val cb = ChunkBlocks(rec.chunkX, rec.chunkZ, byLayer, blockCount, minX, minY, minZ, maxX, maxY, maxZ)
        baked[k] = cb
        cb
    } catch (_: Throwable) {
        null
    }

    /** Compute the face-culled quads of one chunk, grouped by RenderType. Block names + tint + layer
     *  resolve through the shared per-name caches. */
    private fun bakeChunkBlocks(snap: ChunkSnapshot, rec: ChunkSnapshot.ChunkRec): Map<RenderType, List<BlockQuads>> {
        val byLayer = HashMap<RenderType, MutableList<BlockQuads>>()
        val mc = Minecraft.getInstance()
        val blockColors = mc.blockColors
        val modelSet = try { mc.modelManager.blockStateModelSet } catch (_: Throwable) { return emptyMap() }
        val baseX = rec.chunkX shl 4
        val baseZ = rec.chunkZ shl 4
        for (sec in rec.sections) {
            val baseY = sec.sectionY shl 4
            val pw = if (sec.palette.size <= 256) 1 else 2
            for (entry in 0 until 4096) {
                // Local cell coords (iteration order y-major → z → x, inverse of ChunkCapture).
                val lx = entry and 15
                val lz = (entry shr 4) and 15
                val ly = entry shr 8
                val x = baseX + lx
                val y = baseY + ly
                val z = baseZ + lz
                // Overlay first (SnapClip port): a block change recorded during the replay outranks the
                // captured palette — a placed block (palette says air) bakes, a broken block (overlay
                // says air) is skipped. No-op when no overlay is active (the common live-clip case).
                val name = snap.overlayAt(x, y, z) ?: (readIndex(sec, entry, pw) ?: continue)
                if (name == "minecraft:air") continue
                val st = stateFor(name) ?: continue
                // Fluids + invisible-render-type blocks render nothing (matches the old
                // renderBlockAsEntity self-skip). MODEL + ENTITYBLOCK_ANIMATED proceed via their model.
                if (st.renderShape == RenderShape.INVISIBLE) continue
                // Block-level surface-shell skip: all 6 neighbours opaque ⇒ fully interior ⇒ never seen.
                if (isOpaqueAt(snap, x + 1, y, z) &&
                    isOpaqueAt(snap, x - 1, y, z) &&
                    isOpaqueAt(snap, x, y + 1, z) &&
                    isOpaqueAt(snap, x, y - 1, z) &&
                    isOpaqueAt(snap, x, y, z + 1) &&
                    isOpaqueAt(snap, x, y, z - 1)
                ) continue
                val model: BlockStateModel = try { modelSet.get(st) } catch (_: Throwable) { continue }
                val parts = try {
                    val out = ArrayList<BlockStateModelPart>(2)
                    model.collectParts(rand, out)
                    out
                } catch (_: Throwable) { emptyList() }
                if (parts.isEmpty()) continue
                val tints = tintsFor(name, st, blockColors)
                // Per-quad RenderType is authoritative on 26.3 (a model may mix cutout/translucent).
                val quadsByLayer = HashMap<RenderType, MutableList<BakedQuad>>(2)
                for (part in parts) {
                    // Per-direction face quads: emit only when vanilla's shouldRenderFace says the face
                    // is visible (neighbour not opaque-and-same-block). This is the buried-face cull.
                    for (d in directions) {
                        val faceQuads = try { part.getQuads(d) } catch (_: Throwable) { emptyList() }
                        if (faceQuads.isEmpty()) continue
                        if (!shouldDrawSide(st, snap, x, y, z, d)) continue
                        for (q in faceQuads) quadsByLayer.getOrPut(layerOf(q)) { ArrayList(8) }.add(q)
                    }
                    // General / non-culled quads (getQuads(null)) — torches, fences, plants, model
                    // detail with no cull face. ALWAYS emitted (vanilla renderFlat does the same) —
                    // skipping these would punch holes in non-cube blocks.
                    val generalQuads = try { part.getQuads(null) } catch (_: Throwable) { emptyList() }
                    for (q in generalQuads) quadsByLayer.getOrPut(layerOf(q)) { ArrayList(8) }.add(q)
                }
                for ((layer, quads) in quadsByLayer) {
                    if (quads.isEmpty()) continue
                    byLayer.getOrPut(layer) { ArrayList() }.add(BlockQuads(x, y, z, tints, quads))
                }
            }
        }
        return byLayer
    }

    /** Fallback render type when a quad's material info cannot be read (fail-open: the cutout block
     *  sheet, i.e. the block atlas — the same sheet a vanilla block item binds). */
    private val fallbackLayer: RenderType by lazy(LazyThreadSafetyMode.NONE) {
        net.minecraft.client.renderer.Sheets.cutoutBlockItemSheet()
    }

    /** The render type a baked quad belongs to — its own material's block-atlas item sheet. */
    private fun layerOf(quad: BakedQuad): RenderType =
        try { quad.materialInfo().itemRenderType() } catch (_: Throwable) { fallbackLayer }

    /** Vanilla face-cull rule (mirrors `ModelBlockRenderer`'s `Block.shouldRenderFace` call): a face
     *  draws when the neighbour state allows it. The neighbour state resolves from the raw snapshot;
     *  region-edge / air → AIR default state → true → the face draws (closes the shell at the
     *  captured-region edge). Fail-open to true (draw the face) on any throw — drawing an extra face is
     *  harmless, dropping one would leave a hole. */
    private fun shouldDrawSide(state: BlockState, snap: ChunkSnapshot, x: Int, y: Int, z: Int, d: Direction): Boolean = try {
        val nName = snap.nameAt(x + d.stepX, y + d.stepY, z + d.stepZ)
        val nState = if (nName == null || nName == "minecraft:air") Blocks.AIR.defaultBlockState() else stateFor(nName)
        if (nState == null) true else Block.shouldRenderFace(state, nState, d)
    } catch (_: Throwable) { true }

    /** Resolve a palette name to its BlockState, memoized in [stateCache]. null = bad name / throw.
     *
     *  v7 palette keys carry the EXACT recorded state when the block has properties —
     *  `"minecraft:stairs[facing=east,half=top,shape=straight]"` (BlockArgumentParser-compatible form,
     *  written by [dev.iustitia.replay.ChunkCapture.stateKey]). A key with `[` is parsed via
     *  [BlockStateParser.parseForBlock] into the precise state → correct facing/shape/half (fixes the v1
     *  "stairs/slabs render in their default facing" bug) AND feeds [shouldDrawSide]/[isOpaqueAt] the
     *  real neighbour shape (fixes the "blocks behind non-full/transparent blocks don't render" cull
     *  bug). A propertyless key (`"minecraft:stone"`) takes the fast path (today's defaultBlockState
     *  lookup). Pre-v7 clips have only propertyless/bare-id keys → identical to today. Fail-open: a bad
     *  property value (CommandSyntaxException) falls back to the bare-id defaultBlockState (today's
     *  behaviour — the block renders in its default orientation instead of not at all); a bad id → null
     *  (skip / treat as transparent, same as today's AIR-from-DefaultedRegistry result).
     *
     *  NB: Registries.BLOCK is a DefaultedRegistry, so getValue(unknownId) returns AIR (never null) —
     *  an unknown block resolves to air, which renders as nothing and is a transparent neighbour —
     *  exactly the fail-open behaviour we want, so no special unknown-skip needed. */
    private fun stateFor(name: String): BlockState? {
        // `containsKey`, NOT `getOrPut`. Kotlin's getOrPut keys off `get(name) == null`, so a cached
        // null reads back as "absent" and the resolver re-runs — which meant an unresolvable palette
        // name was re-parsed on every single access for the whole clip, not once. A null here is a real
        // answer ("this name resolves to nothing, skip it"), so the miss is memoized like any other.
        if (stateCache.containsKey(name)) return stateCache[name]
        val resolved = resolveState(name)
        stateCache[name] = resolved
        return resolved
    }

    /** The uncached half of [stateFor]: resolve a palette name once, no memoization. */
    private fun resolveState(name: String): BlockState? = try {
        if ('[' in name) {
            // Registries.BLOCK is a Registry, which IS-A HolderLookup.RegistryLookup<Block> on 26.3, so
            // it is passed directly as the lookup the parser resolves block ids + properties against.
            BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, name, false).blockState()
        } else {
            val id = Identifier.tryParse(name) ?: return null
            BuiltInRegistries.BLOCK.getValue(id).defaultBlockState()
        }
    } catch (_: Throwable) {
        // fall back to the bare-id default state (today's behaviour) — a bad property value still
        // renders the block in its default orientation instead of skipping it entirely.
        val bare = name.substringBefore('[')
        val id = Identifier.tryParse(bare) ?: return null
        try { BuiltInRegistries.BLOCK.getValue(id).defaultBlockState() } catch (_: Throwable) { null }
    }

    /** Resolve a block's per-tint-index colors (ARGB ints) for [BlockTintSource.color], memoized in
     *  [tintCache]. This is the position-independent tint the old
     *  `BlockColors.getColor(state, null, null, 0)` produced (a replay has no biome context; no-tint
     *  blocks return an empty array, which the emitter treats as white). Fail-open to empty. */
    private fun tintsFor(name: String, st: BlockState, blockColors: BlockColors): IntArray =
        tintCache.getOrPut(name) {
            try {
                val sources = blockColors.getTintSources(st)
                if (sources.isEmpty()) IntArray(0) else IntArray(sources.size) { i ->
                    try { sources[i].color(st) } catch (_: Throwable) { -1 }
                }
            } catch (_: Throwable) { IntArray(0) }
        }

    /** Read palette index [entry] (0..4095) → palette name, honouring the 1-vs-2 byte width. Fail-open null. */
    private fun readIndex(sec: ChunkSnapshot.SectionRec, entry: Int, pw: Int): String? = try {
        if (pw == 1) sec.palette.getOrNull(sec.data[entry].toInt() and 0xFF)
        else {
            val i = entry * 2
            if (i + 1 >= sec.data.size) return null
            val idx = ((sec.data[i].toInt() and 0xFF) shl 8) or (sec.data[i + 1].toInt() and 0xFF)
            sec.palette.getOrNull(idx)
        }
    } catch (_: Throwable) { null }

    /** True when the block at (x,y,z) is a **full opaque cube** (used for the block-level surface-shell
     *  skip only — the per-face cull uses [shouldDrawSide]/[Block.shouldRenderFace], which is
     *  shape-aware). `null` (outside the captured region) and air count as NOT opaque. Reads the raw
     *  snapshot; fail-open to not-opaque.
     *
     *  NB: this MUST use [BlockState.isSolidRender] (the old `isOpaqueFullCube`), NOT
     *  [BlockState.canOcclude]. `canOcclude` is the raw "this state is opaque" flag — TRUE for
     *  stone/oak **slabs and stairs** (opaque material) even though they are not full cubes. Using it
     *  here would wrongly skip a block whose 6 neighbours are all slabs/stairs even though parts of its
     *  faces are visible through the non-full neighbours' open halves → the intermittent "missing
     *  texture behind slabs/stairs" bug. `isSolidRender` is true only when the neighbour both occludes
     *  AND is a full 1×1×1 cube, so a block is skipped only when it is genuinely fully buried (the
     *  per-face cull would draw nothing anyway). Slabs/stairs/glass/leaves all return false → never
     *  trigger the skip → the block bakes and the shape-aware per-face cull handles it correctly. */
    private fun isOpaqueAt(snap: ChunkSnapshot, x: Int, y: Int, z: Int): Boolean = try {
        val n = snap.nameAt(x, y, z) ?: return false
        if (n == "minecraft:air") return false
        val st = stateFor(n) ?: return false
        st.isSolidRender
    } catch (_: Throwable) { false }

    /** Drop the cache (called from [dev.iustitia.replay.ReplayState.stop]). Idempotent + fail-open.
     *  Reassigns fresh instances so a concurrent render thread can't see a half-cleared map. */
    fun free() {
        try {
            snapRef = null
            snapIndex = null
            baked = LinkedHashMap()
            stateCache = HashMap(256)
            tintCache = HashMap(256)
        } catch (_: Throwable) {}
    }
}
