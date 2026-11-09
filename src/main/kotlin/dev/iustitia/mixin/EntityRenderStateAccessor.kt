package dev.iustitia.mixin

import net.minecraft.client.renderer.entity.state.EntityRenderState
import net.minecraft.network.chat.Component
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.gen.Accessor

/**
 * Accessor for the MAIN nametag field on the base [EntityRenderState]. This is the team-colored
 * nametag vanilla draws above an entity (`EntityRenderer.extractNameTags` sets it from
 * `getNameTag(entity)` — which is `getDisplayName(entity)` — whenever the entity has a visible
 * label). [PlayerEntityRendererMixin] and [ArmorStandEntityRendererMixin] prepend the cheat-tier
 * prefix here.
 *
 * WHY this targets `EntityRenderState` and NOT `AvatarRenderState`: the nametag field is
 * **declared** on `EntityRenderState`, and inherited by `AvatarRenderState`. Mixin `@Accessor` only
 * matches fields declared on the mixin's target class — it does NOT walk the superclass hierarchy —
 * so an `@Accessor` on `@Mixin(AvatarRenderState)` fails at runtime with
 * `InvalidAccessorException: No candidates were found matching ...`. Targeting the declaring class
 * fixes it. An `AvatarRenderState` instance IS-A `EntityRenderState`, so casting a player render state
 * to this accessor works (the interface is added to the base class and inherited).
 *
 * ## 26.3 port
 *
 * The field is no longer called `displayName`: `EntityRenderState` now declares `nameTag`
 * (`Component`, `null` when vanilla would not draw a nametag) plus `nameTagAttachment` (`Vec3`), and
 * the BELOW_NAME score line moved from `AvatarRenderState.playerName` to the base
 * `EntityRenderState.scoreText` (see [PlayerEntityRenderStateAccessor]). The accessor *methods* keep
 * their old names/signatures so every call site (and the ghost renderer) stays source-compatible.
 */
@Mixin(EntityRenderState::class)
interface EntityRenderStateAccessor {
    @Accessor("nameTag")
    fun iustitia_getDisplayName(): Component?

    @Accessor("nameTag")
    fun iustitia_setDisplayName(value: Component)
}
