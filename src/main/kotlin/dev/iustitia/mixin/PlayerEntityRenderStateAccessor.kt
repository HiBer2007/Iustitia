package dev.iustitia.mixin

import net.minecraft.client.renderer.entity.state.EntityRenderState
import net.minecraft.network.chat.Component
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.gen.Accessor

/**
 * Accessor for the **BELOW_NAME scoreboard score line** on a player's render state (e.g. `"12 HP"`),
 * populated by `Entity.belowNameDisplay()` in `EntityRenderer.extractNameTags` only within ~10 blocks
 * of the camera and only when the server registered a BELOW_NAME objective. It is NOT the player's
 * name (the name is `EntityRenderState.nameTag`, accessed via [EntityRenderStateAccessor]).
 *
 * Used by the nametag prefix fallback: when a server hides the vanilla name (`nameTag == null` via
 * team `nametagVisibility` — e.g. mcpvp.club), the only label the vanilla player renderer still draws
 * is this score line, so we prefix it as a tier cue. See
 * [PlayerEntityRendererMixin.iustitia_prefixNametagAtDraw].
 *
 * ## 26.3 port — the mixin target moved to `EntityRenderState`
 *
 * Up to yarn 1.21.11 this was `@Mixin(PlayerEntityRenderState::class)` + `@Accessor("playerName")`,
 * because the score line was declared on the player state subclass. In 26.3 the field was renamed to
 * `scoreText` and **moved down to the base [EntityRenderState]** (it is extracted for every entity,
 * not just players), while `AvatarRenderState` no longer declares any score field. `@Accessor` does not
 * walk the superclass chain, so the mixin now targets the declaring class — exactly the pattern
 * [EntityRenderStateAccessor] already used for the main nametag. The accessor *methods* keep their
 * old names/signatures (`iustitia_getPlayerName` / `iustitia_setPlayerName`) so call sites stay
 * source-compatible; an `AvatarRenderState` IS-A `EntityRenderState`, so the cast works.
 *
 * `scoreText` is `@Nullable`, so the accessor uses `Component?`.
 */
@Mixin(EntityRenderState::class)
interface PlayerEntityRenderStateAccessor {

    @Accessor("scoreText")
    fun iustitia_getPlayerName(): Component?

    @Accessor("scoreText")
    fun iustitia_setPlayerName(value: Component?)
}
