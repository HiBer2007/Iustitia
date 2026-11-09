package dev.iustitia.mixin

import dev.iustitia.replay.ReplayState
import net.minecraft.client.player.ClientInput
import net.minecraft.client.player.LocalPlayer
import net.minecraft.world.entity.player.Input
import net.minecraft.world.phys.Vec2
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Redirect

/**
 * Suppresses the local player's walking while ANY replay/playclip is active (every cam mode), so
 * spectating a rewind/clip never moves the live player. Without this, both the camera and the
 * player move on WASD — the camera would just track the player instead of free-flying through the
 * clip's solid world (incl. underground).
 *
 * ## Mechanism (26.3)
 *
 * yarn 1.21.11 had `ClientPlayerEntity.tickMovement()` reading `input.playerInput` (a `PlayerInput`
 * record) and consuming it into the entity's movement fields; the old mixin zeroed that record at
 * HEAD. 26.3 removed `tickMovement` and `playerInput`: the local player's movement is derived in
 * [LocalPlayer.applyInput] (called from `LivingEntity.aiStep`) from two **separate** sources on
 * [ClientInput]:
 *
 *  - `input.keyPresses.jump()` → the `jumping` flag, and
 *  - `input.getMoveVector()` (a cached `Vec2` built by `KeyboardInput.tick()`) → `xxa`/`zza`, the
 *    strafe/forward impulses.
 *
 * There is no longer a single record to zero (writes to the record would still leave the cached move
 * vector driving `xxa`/`zza`), so this mixin redirects the two *reads* inside [LocalPlayer.applyInput]
 * instead — semantically the same "see no held keys" the old field write produced:
 *
 *  - `getMoveVector()` → [Vec2.ZERO] while a replay is active,
 *  - `Input.jump()` → `false` while a replay is active.
 *
 * The keys themselves (the vanilla `KeyBinding`s) stay pressed — [dev.iustitia.Iustitia.onClientTick]
 * still reads them to advance the camera, and `KeyboardInput.tick()` rebuilds `keyPresses` + the move
 * vector from the keys every tick, so when FREECAM ends the player resumes normally with no
 * stuck-state risk and no keybinding side effects.
 *
 * ## Gate + fail-open
 *
 * Each redirect only substitutes while [ReplayState.active]; every other tick it delegates to the
 * original call unchanged (one volatile read). Mouse-look (rotation) is untouched so the user can
 * still look around in modes with no freecam (Legacy, /ius replay). Both bodies are try/caught
 * (project fail-open posture): a throw delegates to the original value (worst case = the player walks
 * a tick during a freecam, never a crash). Camera-only — no packets, no server-side movement.
 *
 * ## Runtime-only-verifiable
 *
 * Targets verified against `minecraft-merged-deobf-26.3.jar` (`javap -c
 * net.minecraft.client.player.LocalPlayer`, method `applyInput`): one
 * `invokevirtual ClientInput.getMoveVector()Lnet/minecraft/world/phys/Vec2;` at offset 12 and one
 * `invokevirtual Input.jump()Z` at offset 43 — each unique in the method, so no ordinal ambiguity.
 * `defaultRequire: 1` means a mismatch fails launch — the build can't confirm mixin-apply, only a
 * live client run can.
 */
@Mixin(LocalPlayer::class)
class ClientPlayerEntityMixin {

    /** `this.input.getMoveVector()` inside `applyInput` (feeds `xxa`/`zza`). */
    @Redirect(
        method = ["applyInput"],
        at = [
            At(
                value = "INVOKE",
                target = "Lnet/minecraft/client/player/ClientInput;getMoveVector()Lnet/minecraft/world/phys/Vec2;",
                ordinal = 0,
            ),
        ],
    )
    private fun iustitia_suppressReplayMoveVector(input: ClientInput): Vec2 = try {
        // During any active replay/playclip (every cam mode) the user is spectating a rewind/clip, so
        // their walking inputs must not move the live player: return the "no keys held" vector. The
        // packet-cancel mixin (ClientConnectionMixin) is the belt-and-suspenders for movement + the
        // sole mechanism for break/place/attack/etc.
        if (ReplayState.active) Vec2.ZERO else input.getMoveVector()
    } catch (_: Throwable) {
        // fail-open: leave the player's input as-is (worst case = a one-tick walk, never a crash)
        input.getMoveVector()
    }

    /** `this.input.keyPresses.jump()` inside `applyInput` (feeds `jumping`). */
    @Redirect(
        method = ["applyInput"],
        at = [
            At(
                value = "INVOKE",
                target = "Lnet/minecraft/world/entity/player/Input;jump()Z",
                ordinal = 0,
            ),
        ],
    )
    private fun iustitia_suppressReplayJump(keyPresses: Input): Boolean = try {
        // Suppressed whenever a replay/clip is active (every mode incl. Legacy playclip) so the player
        // never jumps server-side while spectating a rewind/clip.
        if (ReplayState.active) false else keyPresses.jump()
    } catch (_: Throwable) {
        keyPresses.jump()
    }
}
