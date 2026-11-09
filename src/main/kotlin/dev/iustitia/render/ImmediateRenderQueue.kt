package dev.iustitia.render

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.QuadInstance
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.texture.OverlayTexture

/**
 * Emits one render type's baked chunk quads into a [VertexConsumer] **immediately**, as the body of a
 * [SubmitNodeCollector.submitCustomGeometry] node.
 *
 * ## Why this exists
 *
 * On 1.21.11 this class was a bridge: the world overlay's `MultiBufferSource` was the only vertex sink
 * available, so entity renderers that only know how to submit into an `OrderedRenderCommandQueue`
 * (boats/minecarts) were drained straight into a `VertexConsumerProvider` by a hand-rolled
 * `SubmitNodeCollector` implementation. On 26.3 that whole bridge is unnecessary — the level render
 * context hands us the real [SubmitNodeCollector] — so the class was repurposed for the one place a
 * direct vertex emission is still the right tool: the clip's captured chunk world.
 *
 * [ChunkWorldRenderer] bakes each chunk into [ChunkMesher.BlockQuads] once and, per frame, submits one
 * custom-geometry node per render type. This object is that node's body: for every baked block it
 * translates the captured [PoseStack.Pose] to the block's absolute recorded coord and pushes each
 * [net.minecraft.client.resources.model.geometry.BakedQuad] through [VertexConsumer.putBakedQuad] with
 * the block's tint applied per tint index — exactly the per-block loop vanilla's
 * `BlockModelFeatureRenderer` runs for block models, just over our pre-baked quad lists.
 *
 * Ordering/layering is a property of the render type the node was submitted with (the collector files
 * blending layers into the translucent phase and lets the pipeline sort them). Everything is fail-open:
 * an emission error drops that layer's geometry for the frame and never crashes the client.
 *
 * Render-thread only: [render] runs while the frame's feature geometry is being built.
 */
class ImmediateRenderQueue(
    /** The baked blocks of one render type, already face-culled and tint-resolved. */
    private val blocks: List<ChunkMesher.BlockQuads>,
) : SubmitNodeCollector.CustomGeometryRenderer {

    /** Reused vertex scratch: one instance per node, so one per layer per frame (render thread only). */
    private val quadInstance = QuadInstance()

    /** True when any geometry was emitted (diagnostics only; the renderer ignores it). */
    var emitted: Boolean = false
        private set

    override fun render(pose: PoseStack.Pose, buffer: VertexConsumer) {
        try {
            quadInstance.setLightCoords(ChunkMesher.FULL_LIGHT)
            quadInstance.setOverlayCoords(OverlayTexture.NO_OVERLAY)
            val scratch = pose.copy()
            for (b in blocks) {
                scratch.set(pose)
                scratch.translate(b.x.toFloat(), b.y.toFloat(), b.z.toFloat())
                val tints = b.tints
                for (q in b.quads) {
                    val tintIndex = try { q.materialInfo().tintIndex() } catch (_: Throwable) { -1 }
                    quadInstance.setColor(if (tintIndex >= 0 && tintIndex < tints.size) tints[tintIndex] else -1)
                    buffer.putBakedQuad(scratch, q, quadInstance)
                }
            }
            emitted = true
        } catch (_: Throwable) {
            // fail-open: this layer's chunk-world geometry drops for the frame
        }
    }
}
