package dev.iustitia.render

import net.minecraft.world.entity.ItemOwner
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3

/**
 * An [ItemOwner] for a replay/clip ghost — supplies the world + the snap's position/yaw but NO live
 * entity ([asLivingEntity] stays the interface default null). Used by
 * [net.minecraft.client.renderer.item.ItemModelResolver.updateForTopItem] to populate a held-item
 * [net.minecraft.client.renderer.item.ItemStackRenderState] for a ghost that has no live
 * `LivingEntity` (the `LivingEntity` overload is bypassed). The null entity is fine for almost all
 * items; a few special models (trident / spyglass / custom-model items) may call `asLivingEntity()`
 * and NPE — contained by the per-feature try/catch in [ReplayRenderer.drawModel]. Fail-open by design.
 *
 * 26.3 note: `ItemOwner`'s members were renamed (`getEntityWorld`/`getEntityPos`/`getBodyYaw` →
 * `level()`/`position()`/`getVisualRotationYInDegrees()`) and `level()` is now non-null, which is why
 * this takes a non-null [Level].
 */
class GhostHeldItemContext(
    private val world: Level,
    private val pos: Vec3,
    private val bodyYaw: Float,
) : ItemOwner {
    override fun level(): Level = world
    override fun position(): Vec3 = pos
    override fun getVisualRotationYInDegrees(): Float = bodyYaw
    // asLivingEntity() stays the interface default (null) — a ghost has no live entity.
}
