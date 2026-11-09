package dev.iustitia.mixin

import dev.iustitia.replay.ReplayState
import net.minecraft.network.Connection
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket
import net.minecraft.network.protocol.game.ServerboundAttackPacket
import net.minecraft.network.protocol.game.ServerboundPunchPacket
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket
import net.minecraft.network.protocol.game.ServerboundInteractPacket
import net.minecraft.network.protocol.game.ServerboundUseItemPacket
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

/**
 * Cancels the local player's OWN outgoing **gameplay** packets while a replay/playclip is active, so
 * spectating a rewind/clip never acts on the live server (no move/look/break/place/use/interact/
 * attack/swing). This is the packet-cancel half of the v1.2.0 spectator-like input suppression; the
 * other half is [ClientPlayerEntityMixin] zeroing the movement input.
 *
 * ## Scope (user-authorized exception to the no-outgoing-packets rule)
 *
 * Acts ONLY on the local observer's own packets, ONLY while [ReplayState.active] is true, and ONLY on
 * the closed list of gameplay packet types below. Chat (`ChatMessageC2SPacket`), commands
 * (`ChatCommandC2SPacket`), inventory clicks / button / pick-item, hotbar slot changes, client
 * settings, client status, and abilities packets PASS THROUGH — a true spectator can still chat, run
 * commands, open inventory, and pick a hotbar slot while watching a clip. It never touches incoming
 * S2C packets (the read-only [ClientPlayNetworkHandlerMixin] handles those), other players, or
 * detection.
 *
 * ## Semantic change: `ServerboundSwingPacket` was split in 26.3
 *
 * 26.3 removed `ServerboundSwingPacket` and replaced it with two packets: `ServerboundAttackPacket`
 * (`entityId` — an attack on a specific entity) and `ServerboundPunchPacket` (a no-target swing, i.e.
 * swinging at air / a block). Both replace the single old swing packet for the replay-suppression
 * purpose, so BOTH are in the suppress list below; omitting either would let the moderator punch or
 * attack the live server while watching a clip.
 *
 * ## `ClientCommandC2SPacket` — action-targeted suppression
 *
 * `ClientCommandC2SPacket` carries an `Action` enum (26.3 renamed yarn's `Mode` to `Action`; values:
 * `STOP_SLEEPING`, `START_SPRINTING`, `STOP_SPRINTING`, `START_RIDING_JUMP`, `STOP_RIDING_JUMP`,
 * `OPEN_INVENTORY`, `START_FALL_FLYING`). The gameplay-state actions (sprint toggle, leave-bed,
 * ride-jump, elytra deploy) are suppressed — they mutate the moderator's live server state (sprint
 * flag, sleep state, horse-jump charge, elytra deployment) and are NOT spectator-adjacent.
 * `OPEN_INVENTORY` passes through (inventory is spectator-adjacent, allowed per the spec's "inventory
 * passes through" rule). Note: the original review finding posited a `SWAP_HANDS` action on this
 * packet — that action does NOT exist (held-item swap is signaled elsewhere); the targeted
 * suppression below covers the actual gameplay-state actions on this packet type.
 *
 * ## Gate + fail-open
 *
 * One `@Volatile` read ([ReplayState.active]) + a runtime type check. Cancels (ci.cancel()) only when
 * active AND the packet is one of the gameplay types; every other call is a no-op. The whole body is
 * try/caught (project fail-open posture): a throw leaves the packet going through (worst case = the
 * player can act during a replay, never a crash). Camera-only side effect — no world edits.
 *
 * ## Runtime-only-verifiable target
 *
 * `Connection.send` is overloaded with three variants in 26.3 — `send(Packet)`,
 * `send(Packet, ChannelFutureListener)`, `send(Packet, ChannelFutureListener, boolean)` (verified with
 * javap against `minecraft-merged-deobf-26.3.jar`). With `defaultRequire: 1` the name-only
 * `method = ["send"]` would be ambiguous and fail at game launch, so the descriptor form
 * `method = ["send(Lnet/minecraft/network/protocol/Packet;)V"]` is used to target the single-arg
 * public entry point that all gameplay code calls. The build can't confirm mixin-apply; only a live
 * client run can.
 */
@Mixin(Connection::class)
class ClientConnectionMixin {

    @Inject(method = ["send(Lnet/minecraft/network/protocol/Packet;)V"], at = [At("HEAD")], cancellable = true)
    private fun iustitia_suppressGameplayPackets(packet: Packet<*>, ci: CallbackInfo) {
        try {
            // Legacy playclip (v1.1.0) skips suppression so the player can act on the live server
            // normally while watching the clip. /ius replay passes legacy=false, so this never
            // short-circuits on the replay path — replay suppression stays unchanged.
            if (!ReplayState.active || ReplayState.legacyPlayclip) return
            if (isGameplay(packet)) ci.cancel()
        } catch (_: Throwable) {
            // fail-open: a throw leaves the packet going through (worst case = the player can act
            // during a replay, never a crash)
        }
    }

    /** The closed list of outgoing gameplay packets suppressed during a replay. Anything NOT in this
     *  list (chat, commands, inventory, hotbar, settings, abilities, …) passes through.
     *
     *  `ClientCommandC2SPacket` is handled specially: only its gameplay-state actions (sprint toggle,
     *  leave-bed, ride-jump, elytra deploy) are suppressed; `OPEN_INVENTORY` passes through.
     *
     *  26.3 split `ServerboundSwingPacket` into `ServerboundAttackPacket` (attacking an entity) and
     *  `ServerboundPunchPacket` (swinging at air / a block) — both are suppressed. */
    private fun isGameplay(p: Packet<*>): Boolean {
        if (p is ServerboundPlayerCommandPacket) {
            // Suppress the gameplay-state actions; let OPEN_INVENTORY through (inventory is
            // spectator-adjacent, allowed per spec). `getAction()` (yarn `getMode()`) is
            // javap-confirmed public on the 26.3 jar.
            return p.action != ServerboundPlayerCommandPacket.Action.OPEN_INVENTORY
        }
        return p is ServerboundMovePlayerPacket ||
            p is ServerboundPlayerActionPacket ||
            p is ServerboundUseItemOnPacket ||
            p is ServerboundUseItemPacket ||
            p is ServerboundInteractPacket ||
            p is ServerboundAttackPacket ||
            p is ServerboundPunchPacket ||
            p is ServerboundPlayerInputPacket
    }
}
