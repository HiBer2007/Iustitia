package dev.iustitia.mixin

import dev.iustitia.VerboseLog
import dev.iustitia.config.ConfigManager
import dev.iustitia.history.FlagHistory
import dev.iustitia.tracking.EntityTrackerManager
import net.minecraft.client.Minecraft
import net.minecraft.client.player.RemotePlayer
import net.minecraft.client.renderer.entity.ArmorStandRenderer
import net.minecraft.client.renderer.entity.state.ArmorStandRenderState
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.network.chat.Component
import net.minecraft.world.phys.AABB
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

/**
 * Prefixes the floating name of an armor-stand **hologram** that belongs to another player, so the
 * cheat-tier cue appears on servers that render a player's name via a marker ArmorStand riding
 * (or ridden by) the player instead of via the player's own nametag — common on ranked-PvP /
 * nametag-plugin servers where the vanilla nametag is team-hidden. The armor stand has its OWN uuid
 * with no `FlagHistory`, so without this the hologram name is never prefixed (its `tierFor` is GREEN).
 *
 * This is the rider-coverage follow-up to [PlayerEntityRendererMixin] (which covers the player's own
 * nametag, with a `scoreText` fallback). Read-only like it: mutates only the label text held in
 * `state.nameTag` (inherited from `EntityRenderState`, accessed via [EntityRenderStateAccessor]).
 * No visibility/camera/packet touches. Whole body fail-open.
 *
 * ## Why a single `extractRenderState` inject (not `submitNameDisplay`)
 *
 * `ArmorStandRenderer` does NOT override `submitNameDisplay` — it inherits the base
 * `EntityRenderer.submitNameDisplay(EntityRenderState, …)`. Injecting an inherited method from a
 * subclass mixin is fragile (the method body lives on the shared base class, so an inject would
 * affect every non-overriding renderer). We avoid that entirely: `ArmorStandRenderer.
 * extractRenderState` calls `super.extractRenderState(entity, state, f)` first, and that base call
 * sets `state.nameTag = getNameTag(entity)` (the armor stand's custom name) when
 * `shouldShowName && within 64 blocks` (verified in the decompiled 26.3 `EntityRenderer.extractNameTags`).
 * So by the TAIL of the subclass `extractRenderState`, `state.nameTag` already holds the hologram
 * text — we just prefix it there. `submitNameDisplay` later reads `state.nameTag` at draw time and
 * sees our prefix. The base extraction re-sets `nameTag` to the plain name every cycle, so
 * there is no cross-frame accumulation; the double-prefix guard is kept only as a belt-and-braces.
 *
 * ## Owner resolution (vehicle graph, read live — no tracking map needed)
 *
 * A hologram armor stand rides the player (stand = passenger, player = vehicle); stacked holograms
 * ride each other, so [ArmorStand.getRootVehicle] walks up to the player. We also handle the
 * reverse (player rides the stand) via [ArmorStand.getFirstPassenger]. Only a `RemotePlayer` owner
 * (and not the local player) is accepted. Non-riding teleport-follow holograms are handled by the
 * proximity fallback below; only the vehicle-attached case is high-precision.
 *
 * ## GREEN is skipped entirely
 *
 * Only YELLOW/RED owners get a prefix on their hologram. A clean player shows no cue (consistent
 * with the hologram being the *only* label on those servers); and skipping GREEN means a decorative
 * / custom-named armor stand that happens to ride (or be ridden by) a clean player never gets a
 * spurious `[+]`. The risk of a decorative stand being vehicle-attached to a *suspect* player is
 * near-zero (decorative stands are static), so YELLOW/RED here is high-confidence.
 *
 * ## 26.3 port (target verified against `minecraft-merged-deobf-26.3.jar`)
 *
 * `ArmorStandEntityRenderer.updateRenderState(ArmorStandEntity, ArmorStandEntityRenderState, float)`
 * → `ArmorStandRenderer.extractRenderState(ArmorStand, ArmorStandRenderState, float)` (same
 * subclass-declared 3-arg shape, descriptor
 * `(Lnet/minecraft/world/entity/decoration/ArmorStand;L...ArmorStandRenderState;F)V`, which also
 * disambiguates it from the generic bridge overloads). Two API renames in the proximity scan:
 * `World.getOtherEntities(entity, box, predicate)` → `getEntities(entity, box, predicate)`, and the
 * `Box.of(center, dx, dy, dz)` helper is gone in 26.3 (`AABB.of` now takes a structure `BoundingBox`),
 * so the box is built with the equivalent explicit `AABB(minX, minY, minZ, maxX, maxY, maxZ)`
 * constructor.
 */
@Mixin(ArmorStandRenderer::class)
class ArmorStandEntityRendererMixin {

    /** "uuid|source" keys for which we've already logged the hologram-prefix diagnostic this session. */
    private val loggedHologram =
        java.util.Collections.synchronizedSet(java.util.LinkedHashSet<String>())

    @Inject(method = ["extractRenderState"], at = [At("TAIL")])
    private fun iustitia_prefixHologramName(
        entity: ArmorStand,
        state: ArmorStandRenderState,
        tickProgress: Float,
        ci: CallbackInfo,
    ) {
        try {
            if (!ConfigManager.config.nametagPrefixes) return

            // Owner = the player the hologram stand belongs to. Two resolution strategies:
            //  1) vehicle graph (high precision): the stand rides the player, or the player rides
            //     the stand -> rootVehicle / firstPassenger yields the RemotePlayer.
            //  2) proximity (fallback for teleport-follow holograms): many nametag plugins spawn a
            //     marker stand that teleports above the player each tick WITHOUT riding it, so the
            //     vehicle graph yields nothing. A name hologram sits ~2 blocks above the player's
            //     feet, horizontally centered, so a tight box + nearest-match recovers the owner.
            //     YELLOW/RED-only + a visible custom name (gated below) keeps false positives to
            //     near-zero (a decorative named stand would have to sit directly on a suspect's head).
            val self = Minecraft.getInstance().player?.uuid
            val vOwner = (entity.rootVehicle as? RemotePlayer)
                ?: (entity.firstPassenger as? RemotePlayer)
            val owner: RemotePlayer?
            val source: String
            if (vOwner != null) { owner = vOwner; source = "vehicle" }
            else {
                val prox = proximityOwner(entity)
                if (prox == null) return
                owner = prox; source = "proximity"
            }
            if (owner.uuid == self) return

            val tier = EntityTrackerManager.get(owner.uuid)?.tier ?: FlagHistory.tierFor(owner.uuid)
            if (tier == FlagHistory.Tier.GREEN) return   // only YELLOW/RED on holograms

            val acc = state as? EntityRenderStateAccessor ?: return
            // Base extractRenderState already set this to the stand's custom name (customNameVisible
            // && within 64 blocks); null means the server didn't make the stand's name visible.
            val name = acc.iustitia_getDisplayName() ?: return
            val s = name.string
            if (s.startsWith(GREEN_MARK) || s.startsWith(YELLOW_MARK) || s.startsWith(RED_MARK)) return

            val (color, glyph) = when (tier) {
                FlagHistory.Tier.GREEN -> "a" to "[+]"
                FlagHistory.Tier.YELLOW -> "e" to "[!]"
                FlagHistory.Tier.RED -> "c" to "[X]"
            }
            val prefix = Component.literal("§${color}${glyph}§r ")
            acc.iustitia_setDisplayName(Component.empty().append(prefix).append(name))
            logHologramOnce(owner.uuid, tier, source)
        } catch (_: Throwable) {
            // fail-open: a render error never crashes the client
        }
    }

    /**
     * Proximity owner resolution for a teleport-follow hologram stand (no vehicle link). Scans the
     * player entities in a tight box around the stand and returns the nearest one whose head-name
     * position aligns with the stand (horiz < [PROX_HORIZ], |stand.y - (player.y + PROX_NAME_Y)| <
     * [PROX_VERT]). Uses `Level.getEntities(entity, box, predicate)` (entity-section indexed) so it's
     * cheap. Fail-open.
     */
    private fun proximityOwner(stand: ArmorStand): RemotePlayer? {
        val world = Minecraft.getInstance().level ?: return null
        val self = Minecraft.getInstance().player?.uuid
        // 26.3: yarn `Box.of(center, dx, dy, dz)` is gone (`AABB.of` now takes a structure
        // BoundingBox), so build the same center±half-sweep box with the explicit constructor.
        val box = AABB(
            stand.x - PROX_BOX_X / 2.0, stand.y - PROX_BOX_Y / 2.0, stand.z - PROX_BOX_X / 2.0,
            stand.x + PROX_BOX_X / 2.0, stand.y + PROX_BOX_Y / 2.0, stand.z + PROX_BOX_X / 2.0,
        )
        // 26.3: yarn `getOtherEntities(entity, box, predicate)` → `getEntities(entity, box, predicate)`.
        var best: RemotePlayer? = null
        var bestHoriz = Double.MAX_VALUE
        for (e in world.getEntities(stand, box) { it is RemotePlayer }) {
            val p = e as RemotePlayer
            if (p.uuid == self) continue
            val dx = p.x - stand.x
            val dz = p.z - stand.z
            val horiz = dx * dx + dz * dz
            if (horiz > PROX_HORIZ * PROX_HORIZ) continue
            val dy = stand.y - (p.y + PROX_NAME_Y)
            if (dy * dy > PROX_VERT * PROX_VERT) continue
            if (horiz < bestHoriz) { bestHoriz = horiz; best = p }
        }
        return best
    }

    /**
     * One verbose line per player per session the first time we prefix their armor-stand hologram
     * name. [source] is `"vehicle"` (stand rides / is ridden by the player) or `"proximity"`
     * (teleport-follow stand matched by position) — that tag tells us which hologram style a server
     * uses, which is exactly the unknown for mcpvp-style servers where the vanilla nametag is
     * team-hidden. Writes to `latest.log` via [VerboseLog], not chat. CAP-bounded.
     */
    private fun logHologramOnce(uuid: java.util.UUID, tier: FlagHistory.Tier, source: String) {
        try {
            if (!VerboseLog.isEnabled()) return
            val key = uuid.toString() + "|" + source
            if (loggedHologram.add(key)) {
                if (loggedHologram.size > CAP) loggedHologram.clear()
                val nm = FlagHistory.nameFor(uuid) ?: uuid.toString().take(8)
                VerboseLog.log(
                    "nametag-hologram: prefixed armor-stand hologram name for $nm (tier=$tier, owner=$source)"
                )
            }
        } catch (_: Throwable) {}
    }

    private companion object {
        // Mixin requires all static fields to be private (rejects non-private static fields at apply
        // time). Companion vals/consts are static fields in bytecode, so they MUST be `private`.
        private const val GREEN_MARK = "§a[+]§r "
        private const val YELLOW_MARK = "§e[!]§r "
        private const val RED_MARK = "§c[X]§r "
        private const val CAP = 512
        // Proximity-match constants for teleport-follow hologram stands. A name hologram sits
        // ~2 blocks above the player's feet, horizontally centered on the player.
        private const val PROX_HORIZ = 0.8       // max horizontal offset (blocks) from the player
        private const val PROX_NAME_Y = 2.0      // expected stand.y = player.y + this
        private const val PROX_VERT = 0.7        // max vertical offset (blocks) from player.y + PROX_NAME_Y
        private const val PROX_BOX_X = 2.0       // getEntities box full x/z sweep (±1.0)
        private const val PROX_BOX_Y = 4.0       // box full y sweep (±2.0)
    }
}
