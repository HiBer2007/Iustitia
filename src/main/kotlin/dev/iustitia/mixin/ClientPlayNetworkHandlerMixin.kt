package dev.iustitia.mixin

import dev.iustitia.Iustitia
import dev.iustitia.event.DiggingSignal
import dev.iustitia.event.EffectSignal
import dev.iustitia.event.GameJoinSignal
import dev.iustitia.event.HurtSignal
import dev.iustitia.event.HurtSource
import dev.iustitia.event.SwingSignal
import dev.iustitia.event.VelocitySignal
import dev.iustitia.protocol.ProtocolDetector
import dev.iustitia.tracking.EntityTrackerManager
import dev.iustitia.tracking.ServerTickRate
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientPacketListener
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Entity
import net.minecraft.world.effect.MobEffects
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket
import net.minecraft.network.protocol.game.ClientboundHurtAnimationPacket
import net.minecraft.network.protocol.game.ClientboundSwingAnimationPacket
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket
import net.minecraft.network.protocol.game.ClientboundUpdateMobEffectPacket
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket
import net.minecraft.network.protocol.game.ClientboundLoginPacket
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.network.protocol.game.ClientboundRespawnPacket
import net.minecraft.network.protocol.game.ClientboundDisguisedChatPacket
import net.minecraft.network.protocol.game.ClientboundRemoveMobEffectPacket
import net.minecraft.network.protocol.game.ClientboundSetTimePacket
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo
import java.util.UUID

/**
 * The one and only packet-observer mixin class. Every inject is a read-only, HEAD-only capture of an
 * incoming clientbound packet; nothing is cancelled, nothing is sent, no local player/camera state is
 * touched. Each handler resolves the other player's uuid from the live world, drops the local player +
 * unknown entities, and publishes a signal to the [Iustitia] bus. All bodies are fail-open.
 *
 * ## 26.3 port (target renames)
 *
 * 26.3 renamed `ClientPlayNetworkHandler` to [ClientPacketListener] and every packet handler with it.
 * Each inject below targets the verified 26.3 handler (the old yarn name is noted per inject):
 *
 * | old yarn handler | 26.3 handler |
 * | --- | --- |
 * | `onGameJoin` | `handleLogin` |
 * | `onPlayerRespawn` | `handleRespawn` |
 * | `onWorldTimeUpdate` | `handleSetTime` |
 * | `onEntityAnimation` | **split** — `handleSwingAnimation` (see below) |
 * | `onBlockBreakingProgress` | `handleBlockDestruction` |
 * | `onEntityStatus` | `handleEntityEvent` |
 * | `onEntityDamage` | `handleDamageEvent` |
 * | `onChatMessage` | `handlePlayerChat` |
 * | `onGameMessage` | `handleSystemChat` |
 * | `onProfilelessChatMessage` | `handleDisguisedChat` |
 * | `onDamageTilt` | `handleHurtAnimation` |
 * | `onEntityVelocityUpdate` | `handleSetEntityMotion` |
 * | `onEntityStatusEffect` | `handleUpdateMobEffect` |
 * | `onRemoveEntityStatusEffect` | `handleRemoveMobEffect` |
 *
 * ## Semantic change: the swing packet was split in 26.3
 *
 * Old: `ClientboundAnimatePacket` carried the swing animation (`SWING_MAIN_HAND` / `SWING_OFF_HAND`).
 * 26.3 removed both constants — `ClientboundAnimatePacket` now only carries `WAKE_UP` / `CRITICAL_HIT`
 * / `MAGIC_CRITICAL_HIT` (i.e. the hit *effects*, which this observer never published), and swings
 * arrive as `ClientboundSwingAnimationPacket(entityId, InteractionHand, SwingAnimation)` handled by
 * [ClientPacketListener.handleSwingAnimation]. [iustitia_onSwingAnimation] therefore publishes the
 * same [SwingSignal] from that handler, mapping the packet's `hand()` back to the legacy numeric
 * animation ids (main hand 0 / off hand 3 — the pre-26.3 `EntityAnimationS2CPacket` values) so the
 * signal shape (`SwingSignal(uuid, tick, nanoTime, animId)`) is unchanged.
 */
@Mixin(ClientPacketListener::class)
class ClientPlayNetworkHandlerMixin {

    private fun world() = Minecraft.getInstance().level

    private fun otherPlayer(id: Int): UUID? {
        val w = world() ?: return null
        // 26.3: yarn `getEntityById(int)` is now `Level.getEntity(int)` (`ClientLevel` overrides it).
        val e: Entity = w.getEntity(id) ?: return null
        if (e === Minecraft.getInstance().player) return null
        return e.uuid
    }

    private fun otherPlayer(entity: Entity?): UUID? {
        if (entity == null) return null
        if (entity === Minecraft.getInstance().player) return null
        return entity.uuid
    }

    /** yarn `onGameJoin` → 26.3 [ClientPacketListener.handleLogin]. */
    @Inject(method = ["handleLogin"], at = [At("HEAD")])
    private fun iustitia_onGameJoin(packet: ClientboundLoginPacket, ci: CallbackInfo) {
        try {
            ProtocolDetector.redetect()
            // resetAll touches mc.options (HUD/perspective), does disk I/O and clears every
            // engine structure — all client-thread-only concerns, and far too heavy for the
            // netty event loop mid join-burst. Hand off to the tick driver's defer queue.
            Iustitia.defer { Iustitia.resetAll() }
            // Record the world identity of this session so a later onPlayerRespawn can tell
            // a death-respawn (same world) from a dimension-transfer / Bungee sub-server switch
            // (different world). See iustitia_onPlayerRespawn.
            SpawnState.seed = packet.commonPlayerSpawnInfo().seed()
            Iustitia.bus.publish(GameJoinSignal(Iustitia.tickCounter))
            // Per-server chat history: (re)load the current server's persisted bucket on join. Fail-open.
            try { dev.iustitia.chathist.ChatHistory.onJoin() } catch (_: Throwable) {}
        } catch (_: Throwable) {}
    }

    /**
     * yarn `onPlayerRespawn` → 26.3 [ClientPacketListener.handleRespawn].
     *
     * onPlayerRespawn fires on BOTH death-respawn AND dimension transfer (portal) / Bungee
     * sub-server switch. We must reset VL and re-detect protocol ONLY on a genuine world
     * change, never on a same-world death-respawn (that would wipe a cheater's accumulated VL
     * every time they die — a trivial evasion: die, respawn, the slate is clean). Vanilla
     * intentionally keeps the same seed/dimension for a death-respawn, so:
     *  - dimension() != the live (pre-respawn) world's registry key → portal dimension transfer,
     *  - seed() != the seed recorded at GameJoin (or the last world-change respawn) → Bungee
     *    sub-server switch (same dimension key, freshly generated world).
     * Either => world changed: resetAll + redetect + record the new seed. Both equal => a
     * same-world death-respawn: preserve VL. Fail-open.
     */
    @Inject(method = ["handleRespawn"], at = [At("HEAD")])
    private fun iustitia_onPlayerRespawn(packet: ClientboundRespawnPacket, ci: CallbackInfo) {
        try {
            val info = packet.commonPlayerSpawnInfo()
            val newSeed = info.seed()
            val newDim = info.dimension()
            // 26.3: `ClientWorld.getRegistryKey()` is gone; `Level.dimension()` returns the
            // ResourceKey<Level> the client is in (same identity the spawn info carries).
            val oldDim = world()?.dimension()
            val dimChanged = oldDim != null && newDim != oldDim
            val seedChanged = newSeed != SpawnState.seed
            if (dimChanged || seedChanged) {
                ProtocolDetector.redetect()
                // client-thread handoff — same rationale as iustitia_onGameJoin.
                Iustitia.defer { Iustitia.resetAll() }
                SpawnState.seed = newSeed
            }
            // same world (death-respawn): intentionally preserve all VL — see PacketSignals.
        } catch (_: Throwable) {}
    }

    /**
     * yarn `onWorldTimeUpdate` → 26.3 [ClientPacketListener.handleSetTime].
     *
     * The server's 20-tick clock sync (`MinecraftServer.tickWorlds`: `ticks % 20 == 0`). The
     * gap between two of these is the server's own measure of "is it still ticking", which is
     * what [ServerTickRate] turns into the shared lag signals — see that file for why this
     * replaced the per-player position-delta heuristic.
     *
     * Deliberately NOT routed through [Iustitia.defer] like the entity signals: the whole point
     * is timestamp accuracy, and a tick-queue hop would add up to 50 ms of error to every
     * measurement. The write is a single volatile long store. Fail-open.
     */
    @Inject(method = ["handleSetTime"], at = [At("HEAD")])
    private fun iustitia_onWorldTimeUpdate(packet: ClientboundSetTimePacket, ci: CallbackInfo) {
        try {
            ServerTickRate.onWorldTimeUpdate()
        } catch (_: Throwable) {}
    }

    /**
     * yarn `onEntityAnimation` → 26.3 [ClientPacketListener.handleSwingAnimation].
     *
     * 26.3 split the old swing action out of `ClientboundAnimatePacket` into
     * `ClientboundSwingAnimationPacket`, which reports the hand (`hand()`) instead of the legacy
     * numeric animation id and also carries the swing's `SwingAnimation` (type + duration). Both
     * hands are real attack swings, exactly like the old `SWING_MAIN_HAND` / `SWING_OFF_HAND`
     * pair, so both are published. [SwingSignal.animationId] keeps the *legacy* values (0 = main
     * hand, 3 = off hand) so every existing subscriber and `SelfTest`'s `animationId = 0` seed
     * stay valid. Fail-open.
     */
    @Inject(method = ["handleSwingAnimation"], at = [At("HEAD")])
    private fun iustitia_onSwingAnimation(packet: ClientboundSwingAnimationPacket, ci: CallbackInfo) {
        try {
            val anim = when (packet.hand()) {
                InteractionHand.MAIN_HAND -> SWING_MAIN_HAND
                InteractionHand.OFF_HAND -> SWING_OFF_HAND
            }
            val attacker = otherPlayer(packet.entityId()) ?: return
            Iustitia.bus.publish(SwingSignal(attacker, Iustitia.tickCounter, System.nanoTime(), anim))
        } catch (_: Throwable) {}
    }

    /**
     * yarn `onBlockBreakingProgress` → 26.3 [ClientPacketListener.handleBlockDestruction].
     *
     * Block-break progress for another player: the only observation that distinguishes a
     * swing the server relayed because a dig was running from a swing the player chose.
     * A digging player's arm is animated on the server's own `getHandSwingDuration()/2`
     * clock (4 ticks bare-handed or with a tool, 3 under Haste I/II, 5 under Mining
     * Fatigue I), so their swing stream is exactly constant and reads as a fixed-delay
     * autoclicker to the cadence checks, and as a permanently eligible attacker to
     * attack inference. See [dev.iustitia.event.DiggingSignal] for the broadcast rules
     * (including the one-packet-per-dig case) and for what this does and does not attest.
     *
     * Fail-open. */
    @Inject(method = ["handleBlockDestruction"], at = [At("HEAD")])
    private fun iustitia_onBlockBreakingProgress(
        packet: ClientboundBlockDestructionPacket, ci: CallbackInfo
    ) {
        try {
            val digger = otherPlayer(packet.id) ?: return
            Iustitia.bus.publish(
                DiggingSignal(digger, Iustitia.tickCounter, packet.progress, packet.pos)
            )
        } catch (_: Throwable) {}
    }

    /** yarn `onEntityStatus` → 26.3 [ClientPacketListener.handleEntityEvent]. */
    @Inject(method = ["handleEntityEvent"], at = [At("HEAD")])
    private fun iustitia_onEntityStatus(packet: ClientboundEntityEventPacket, ci: CallbackInfo) {
        try {
            val w = world() ?: return
            // EntityStatus byte 2: `KINETIC_ATTACK` (a mace smash) on 1.21, the living entity's
            // "play hurt animation" on legacy protocols. Either way it is a real hit on the victim,
            // so it is published as an unnamed hurt; consumers must not read a name out of it.
            if (packet.eventId == 2.toByte()) {
                val victim = otherPlayer(packet.getEntity(w)) ?: return
                Iustitia.bus.publish(HurtSignal(victim, Iustitia.tickCounter, -1, HurtSource.STATUS))
                return
            }
            // EntityStatus byte 35 == Totem-of-Undying pop (broadcast to every tracking client).
            // Recorded into the replay buffer as a (tick, uuid) event → the `⚡<count>` badge on the
            // ghost's nametag during /ius playclip when clipTotemPopCounter is on. Fail-open.
            if (packet.eventId == 35.toByte()) {
                val who = otherPlayer(packet.getEntity(w)) ?: return
                dev.iustitia.replay.ReplayBuffer.recordTotemPop(Iustitia.tickCounter, who)
                // Mirror into the manual long-recording buffer (no-op unless `/ius record` is active).
                try { dev.iustitia.replay.RecordManager.recordTotemPop(Iustitia.tickCounter, who) } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }

    /** yarn `onEntityDamage` → 26.3 [ClientPacketListener.handleDamageEvent]. */
    @Inject(method = ["handleDamageEvent"], at = [At("HEAD")])
    private fun iustitia_onEntityDamage(packet: ClientboundDamageEventPacket, ci: CallbackInfo) {
        try {
            val victim = otherPlayer(packet.entityId) ?: return
            Iustitia.bus.publish(
                HurtSignal(victim, Iustitia.tickCounter, packet.sourceCauseId, HurtSource.ENTITY_DAMAGE)
            )
        } catch (_: Throwable) {}
    }

    /** yarn `onChatMessage` → 26.3 [ClientPacketListener.handlePlayerChat]. It is the only player-chat
     *  hook that exposes a raw sender UUID (`packet.sender()`) + the signed plaintext
     *  (`packet.body().content()`) before decoration — `onGameMessage` carries only a pre-rendered
     *  `Component` with no UUID, and `ChatHud.addMessage` bakes the UUID into a decorated `Component`.
     *  26.3 dropped `serializedParameters()`; the sender display name now lives on the chat-type bound
     *  (`packet.chatType().name()`). Capture OTHER players' messages only (sender is a tracked player
     *  AND not the local player); system/game messages never reach this handler. Fail-open. */
    @Inject(method = ["handlePlayerChat"], at = [At("HEAD")])
    private fun iustitia_onChatMessage(packet: ClientboundPlayerChatPacket, ci: CallbackInfo) {
        try {
            val sender = packet.sender()
            if (sender == Minecraft.getInstance().player?.uuid) return
            val tracked = EntityTrackerManager.all().firstOrNull { it.uuid == sender } ?: return
            // 26.3: `body()` is a non-null `SignedMessageBody.Packed` and `content()` a String;
            // `unsignedContent()` is still an Optional<Component> for unsigned/edited messages.
            val text = packet.body().content().takeIf { it.isNotBlank() }
                ?: packet.unsignedContent().orElse(null)?.string ?: return
            // Resolve a clean username (tab-list profile name for this sender) so the stored `name`
            // field is consistent with the decorated paths — the packet's serialized name can be a
            // decorated display name that would break `/ius chathist <name>`'s name-match filter.
            val packetName = packet.chatType().name().string
                .ifEmpty { tracked.username().ifEmpty { sender.toString().take(8) } }
            val name = dev.iustitia.chathist.DecoratedChatParser.resolveCleanName(sender, packetName)
            dev.iustitia.chathist.ChatHistory.record(Iustitia.tickCounter, System.currentTimeMillis(), sender, name, text)
        } catch (_: Throwable) {}
    }

    /** yarn `onGameMessage` → 26.3 [ClientPacketListener.handleSystemChat].
     *
     *  Decorated system/game chat (Hypixel/ArchMC/Minemen/Cavern/Evox/Stray/mcpvp/PvpHQ/Mineplex): the
     *  server broadcasts `[rank] username: message` as a `GameMessageS2CPacket` with no sender UUID.
     *  `ChatHistory.captureDecorated` recovers `(username, message)` structurally and resolves the
     *  UUID from the tab list. System noise (join/leave/death) is dropped — it has no separator. Fail-open. */
    @Inject(method = ["handleSystemChat"], at = [At("HEAD")])
    private fun iustitia_onGameMessage(packet: ClientboundSystemChatPacket, ci: CallbackInfo) {
        try {
            dev.iustitia.chathist.ChatHistory.captureDecorated(packet.content())
        } catch (_: Throwable) {}
    }

    /** yarn `onProfilelessChatMessage` → 26.3 [ClientPacketListener.handleDisguisedChat].
     *
     *  Profileless chat: the packet already splits the sender display name (`chatType().name()`)
     *  from the message body (`message()`), so this is the cleanest decorated path when a server
     *  uses it. `ChatHistory.captureProfileless` resolves the display name → clean username + UUID.
     *  Fail-open. */
    @Inject(method = ["handleDisguisedChat"], at = [At("HEAD")])
    private fun iustitia_onProfilelessChatMessage(packet: ClientboundDisguisedChatPacket, ci: CallbackInfo) {
        try {
            val params = packet.chatType() ?: return
            dev.iustitia.chathist.ChatHistory.captureProfileless(params.name(), packet.message())
        } catch (_: Throwable) {}
    }

    /** yarn `onDamageTilt` → 26.3 [ClientPacketListener.handleHurtAnimation].
     *
     * The victim of a damage tilt is the damaged player, and the server sends this packet to that
     * player's own client only (`ServerPlayerEntity.tiltScreen`), so in the wild this inject fires
     * for the LOCAL player and `otherPlayer` filters it out. The channel it feeds is exercised by
     * the live-test harness, which publishes the signal directly; it is not evidence an observer
     * ever has about somebody else's hit. */
    @Inject(method = ["handleHurtAnimation"], at = [At("HEAD")])
    private fun iustitia_onDamageTilt(packet: ClientboundHurtAnimationPacket, ci: CallbackInfo) {
        try {
            val victim = otherPlayer(packet.id) ?: return
            Iustitia.bus.publish(HurtSignal(victim, Iustitia.tickCounter, -1, HurtSource.DAMAGE_TILT))
        } catch (_: Throwable) {}
    }

    /** yarn `onEntityVelocityUpdate` → 26.3 [ClientPacketListener.handleSetEntityMotion]. */
    @Inject(method = ["handleSetEntityMotion"], at = [At("HEAD")])
    private fun iustitia_onEntityVelocityUpdate(packet: ClientboundSetEntityMotionPacket, ci: CallbackInfo) {
        try {
            val entity = otherPlayer(packet.id) ?: return
            // 26.3: record component `velocity` is now `movement()`.
            val vel = packet.movement
            val tick = Iustitia.tickCounter
            // markVelocity writes TrackedPlayer state the tick driver reads — hand off to the
            // defer queue (FIFO, so it still lands before the VelocitySignal subscribers that
            // the publish below enqueues after it).
            Iustitia.defer { EntityTrackerManager.markVelocity(entity, tick, vel) }
            Iustitia.bus.publish(VelocitySignal(entity, tick, vel))
        } catch (_: Throwable) {}
    }

    /** yarn `onEntityStatusEffect` → 26.3 [ClientPacketListener.handleUpdateMobEffect].
     *  `getEffectId()` became `getEffect()` (a `Holder<MobEffect>`, the same shape as
     *  [MobEffects.SPEED]) and `getAmplifier()` became `getEffectAmplifier()`. */
    @Inject(method = ["handleUpdateMobEffect"], at = [At("HEAD")])
    private fun iustitia_onEntityStatusEffect(packet: ClientboundUpdateMobEffectPacket, ci: CallbackInfo) {
        try {
            val entity = otherPlayer(packet.getEntityId()) ?: return
            val entry = packet.effect
            val isSpeed = try { entry === MobEffects.SPEED } catch (_: Throwable) { false }
            val isBlind = try { entry === MobEffects.BLINDNESS } catch (_: Throwable) { false }
            val amp = if (isSpeed) packet.effectAmplifier else -1
            val blindAmp = if (isBlind) packet.effectAmplifier else -1
            // markEffect is Speed-only (feeds the SpeedEnvelope cap raise); Blindness is tracked
            // per-check (sprintHack subscribes EffectSignal) — no shared TrackedPlayer field.
            Iustitia.defer { EntityTrackerManager.markEffect(entity, isSpeed, amp, added = true) }
            Iustitia.bus.publish(EffectSignal(entity, Iustitia.tickCounter, isSpeed, amp, isBlind, blindAmp, added = true))
        } catch (_: Throwable) {}
    }

    /** yarn `onRemoveEntityStatusEffect` → 26.3 [ClientPacketListener.handleRemoveMobEffect]. */
    @Inject(method = ["handleRemoveMobEffect"], at = [At("HEAD")])
    private fun iustitia_onRemoveEntityStatusEffect(packet: ClientboundRemoveMobEffectPacket, ci: CallbackInfo) {
        try {
            val entity = otherPlayer(packet.entityId()) ?: return
            val entry = packet.effect()
            val isSpeed = try { entry === MobEffects.SPEED } catch (_: Throwable) { false }
            val isBlind = try { entry === MobEffects.BLINDNESS } catch (_: Throwable) { false }
            // Forward both Speed and Blindness removal; drop other effects (unobserved). The
            // remove packet carries no amplifier, so blindAmplifier = -1 on removal (the
            // sprintHack check only cares that Blindness ended, not the level).
            if (!isSpeed && !isBlind) return
            if (isSpeed) Iustitia.defer { EntityTrackerManager.markEffect(entity, isSpeed, -1, added = false) }
            Iustitia.bus.publish(EffectSignal(entity, Iustitia.tickCounter, isSpeed, -1, isBlind, -1, added = false))
        } catch (_: Throwable) {}
    }

    private companion object {
        // Mixin rejects non-private static fields at apply time; companion vals are static fields in
        // bytecode, so these MUST be private. Legacy `EntityAnimationS2CPacket` swing ids, kept so the
        // SwingSignal shape is unchanged after 26.3 moved swings onto ClientboundSwingAnimationPacket
        // (which only carries the InteractionHand).
        /** Legacy animation id for a main-hand swing (`SWING_MAIN_HAND` before 26.3). */
        private const val SWING_MAIN_HAND = 0
        /** Legacy animation id for an off-hand swing (`SWING_OFF_HAND` before 26.3). */
        private const val SWING_OFF_HAND = 3
    }
}

/**
 * Holds the seed of the world the client is currently in, as reported by the last
 * GameJoin / world-change respawn. Used by [ClientPlayNetworkHandlerMixin.iustitia_onPlayerRespawn]
 * to tell a death-respawn (same seed → preserve VL) from a dimension-transfer / Bungee
 * sub-server switch (different seed → reset VL). Volatile: read by the respawn handler from
 * the network thread, written by the game-join/respawn handlers.
 */
private object SpawnState {
    @Volatile var seed: Long = Long.MIN_VALUE
}
